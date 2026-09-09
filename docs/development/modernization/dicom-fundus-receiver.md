# DICOM C-STORE receiver for handheld fundus cameras (HealthAEye)

**Status:** Accepted as [DR-025](decision-record.md#dr-025--dicom-c-store-receiver-for-handheld-fundus-cameras) (2026-09-09) — implementation started; **Slice 0 (schema + config) landed**. Q1 resolved: **pynetdicom sidecar**. This doc is the living implementation plan.
**Owner:** Lead Developer (Lukas Kuchernig)
**Purpose:** Let handheld fundus cameras (HealthAEye) push images into the platform over a DICOM interface, for testing first, then routine capture.

This doc holds two things: the proposed **DR-025** (ready to fold into
[decision-record.md](decision-record.md) once accepted) and a **minimal-slice
implementation plan**. It builds on [DR-022](decision-record.md#dr-022--remote-stateless-gpu-sidecar-for-retinal-inference)
(sidecar pattern) and [DR-024](decision-record.md#dr-024--single-bscandcm-ingestion-seam)
(the OCT `bscan.dcm` seam), and reuses the retinal pipeline's subject-matching
and artifact-store machinery.

> **Framing — this is NOT the OCT path.** HealthAEye handhelds produce 2-D
> **fundus photographs** (a VL / ophthalmic-photography DICOM object), not the
> multi-frame OCT `bscan.dcm` the retinal pipeline crunches. So the receiver
> **stores the photo and attaches it to a subject/event/CRF** — it does *not*
> touch the GPU inference runners. It is a new, simpler modality ingress that
> reuses existing plumbing.

---

## DR-025 — decision & rationale (Accepted 2026-09-09)

> The **canonical** entry is [DR-025 in decision-record.md](decision-record.md#dr-025--dicom-c-store-receiver-for-handheld-fundus-cameras). The text below is retained as fuller rationale. **Q1 resolved: `pynetdicom` sidecar** (over in-process `dcm4che`).

**Date:** 2026-09-09
**Status:** **Accepted**
**Owner:** Lead Developer (Lukas Kuchernig)
**Related:** DR-022, DR-024; `StudySubjectFinder` / `StudySubjectMatch` /
`EventCandidate` (`core/.../service/retinal/`); `retinal_inference_job` table
(`migration/lc-muw-2026-06-10-retinal-inference-tables.xml`);
`RetinalArtifactStorageService`; `RetinalResultsApiController` (`/artifacts/{name}`);
`PublicOctUploadController`; the HealthAEye CRF (#26).

**Context.** The institution wants handheld fundus cameras (HealthAEye) to send
images to the platform via a DICOM interface. In DICOM terms the camera is a
**Storage SCU** that pushes objects with **C-STORE**; the platform must provide
a **Storage SCP** (a receiver) — which does not exist today. The platform *does*
already handle DICOM on the outbound/processing side: the retinal-inference
sidecar synthesises a PHI-redacted `bscan.dcm` and serves/accepts
`application/dicom` (DR-024), and DR-022 already earmarks `dcm4che-core 5.x` for
the deferred OCT-SEG work. It also already has the hard part of ingestion —
`StudySubjectFinder` matches an image to a study subject + event, and the
`retinal_inference_job` table is a proven persisted work queue.

The two binding strategies the team chose: **reconciliation queue first**
(images arrive unbound; an operator links each one to a subject/event/CRF in the
SPA), then **PatientID/AccessionNumber auto-match** (reuse `StudySubjectFinder`)
as a fast-path once the tag conventions from a real device are known.

**Decision.** Add a **DICOM C-STORE Storage SCP** as an **app-VM sidecar**
(alongside the existing `retinal-preprocess` posture — *not* the stateless GPU
cluster sidecar, since this receiver persists), reusing the sidecar's Python
DICOM stack (`pydicom` + `pynetdicom`). On C-STORE the sidecar writes the Part-10
object + a rendered preview PNG to a store, extracts identity/exam tags, and
hands off to the app over a shared-secret internal endpoint, which inserts a row
into a new persisted **`dicom_ingest`** queue with **nullable** subject/event
binding. The SPA surfaces a **reconciliation inbox**; binding associates the
image with an `event_crf` (and, where a fundus-image CRF item exists, writes an
`item_data` reference so it renders in the eCRF, reusing the
`RetinalResultsApiController` artifact-serving route). Auto-match is a later
slice that calls the same finder methods on receipt.

**Trade-offs considered.**

| Axis | Choice | Rejected / deferred alternative |
|---|---|---|
| Receiver host | **Python `dicom-scp` sidecar** (`pynetdicom`) on the app VM, handing off to the app via an internal API | **In-process `dcm4che` `StoreSCP`** bean in the WAR — gold-standard Java DICOM + direct DB writes, but puts a long-lived raw DICOM socket inside Tomcat and pulls a heavyweight dep in now. *(Kept open — see Q1.)* |
| Patient→subject binding | **Reconciliation queue first**, PatientID/Accession auto-match second | **MWL SCP** (platform serves a worklist; operator picks patient on the device) — robust clinical flow but a second DICOM service; deferred |
| Queue model | New **`dicom_ingest`** table modelled on `retinal_inference_job`, binding columns nullable | Reuse `retinal_inference_job` — wrong shape (OCT task/laterality/e2e_path; NOT-NULL `event_crf_id` origin) |
| Storage | Reuse the artifact store + `RetinalArtifactStorageService` conventions; serve via a `/artifacts`-style route | New bespoke image store |
| PHI | **Keep PatientName/ID** — images stay on-prem, the platform is the trusted custodian; PatientID is the match key | Redact on ingest (DR-022 redacts only because scan data *leaves* to the GPU host; not applicable here) |
| Transport security | Internal DICOM port + calling-AE allowlist for v1 | DICOM-TLS — deferred (single-site internal network) |

**Consequences.**

- A new opt-in sidecar service + a new `dicom_ingest` table + migration; all
  gated so an unset config is a no-op for existing deployments (mirrors DR-022's
  opt-in discipline).
- The receiver is on the **app VM**, not the GPU cluster — it persists PHI and
  must reach the camera network; the GPU-sidecar "never persists" invariant is
  untouched.
- Introduces DICOM networking to the stack. If Q1 lands on `dcm4che`, that also
  seeds the DR-022 OCT-SEG follow-up.
- Operator workflow gains a "DICOM inbox" (reconciliation) view; audited
  bind/dismiss actions.

**Reversible.** Disabling is a single config flag (`core.dicom.scp.enabled=false`)
+ not deploying the sidecar; the `dicom_ingest` table is additive and unused when
the receiver is off. No change to the retinal or CRF paths.

**Out of scope (for this DR).** MWL SCP; MPPS; C-FIND/C-MOVE query-retrieve;
DICOM-TLS; OCT/SEG creation (that is the DR-022 follow-up); PACS forwarding;
multi-institution AE-title management.

---

## Minimal-slice implementation plan

### Flow

```
HealthAEye camera (Storage SCU)
        │  C-STORE  (fundus photo, Part-10)
        ▼
dicom-scp sidecar (app VM, pynetdicom Storage SCP)
   • accept VL Photographic / Ophthalmic Photography / Secondary Capture
   • write Part-10 + rendered preview.png to the ingest store
   • extract PatientID, AccessionNumber, StudyDate, Modality, Laterality, AE title
   • POST /internal/dicom-ingest  (shared-secret header)
        ▼
LibreClinica app  →  INSERT dicom_ingest (status = UNBOUND)
        ▼
SPA "DICOM inbox"  →  operator links image → subject → event (→ CRF item)
        ▼
bind → status = BOUND, associate with event_crf (+ optional item_data reference)
```

Phase 2 inserts an **auto-match** step between ingest and the inbox: on receipt,
`StudySubjectFinder.findByLabelAcrossStudies(PatientID)` + `findEventOnDate(StudyDate)`;
an unambiguous hit binds automatically (`match_policy = 'auto'`), everything else
falls to the inbox.

### Reuse map

| Need | Reuse | Ref |
|---|---|---|
| "Which subject/event?" | `StudySubjectFinder.findByLabelAcrossStudies`, `findByLabelPrefix` (inbox search), `findEventOnDate` | `core/.../service/retinal/StudySubjectFinder.java` |
| Match record shapes | `StudySubjectMatch`, `EventCandidate` records | same package |
| On-disk companion storage | `RetinalArtifactStorageService` conventions (or a sibling `DicomIngestStorageService`) | `RetinalArtifactStorageService.java` |
| Serve preview/DICOM to SPA | `/artifacts/{name}`-style streaming route | `RetinalResultsApiController.java:1372` |
| Persisted queue shape | model on `retinal_inference_job` (BIGSERIAL PK, status enum, status_message, timestamps) — but binding cols nullable | `migration/lc-muw-2026-06-10-retinal-inference-tables.xml` |
| CRF item write pattern | `RetinalResultItemDataPopulator` (idempotent `item_data` upsert stamped with a `source_kind`) | `RetinalResultItemDataPopulator.java` |
| Sidecar handoff pattern | DR-022 remote-push (shared-secret `X-MUW-*` header, opt-in URL) | `RemoteRetinalInferenceClient` |
| Python DICOM stack | `pydicom` (already in `muw-e2e-converter`), add `pynetdicom` | DR-024 |

### Data model — `dicom_ingest` (migration `lc-muw-2026-09-09-dicom-ingest.xml`)

```
id                     BIGSERIAL PK
sop_instance_uid       TEXT UNIQUE NOT NULL   -- dedup; C-STORE may resend
sop_class_uid          TEXT
study_instance_uid     TEXT
series_instance_uid    TEXT
modality               TEXT                   -- OP / XC / OT …
patient_id             TEXT                   -- (0010,0020) → study_subject.label
patient_name           TEXT                   -- kept (on-prem PHI); nullable
accession_number       TEXT                   -- (0008,0050)
study_date             DATE                   -- (0008,0020)
laterality             TEXT                   -- (0020,0060) L/R → OS/OD; nullable
source_ae_title        TEXT
dicom_path             TEXT NOT NULL          -- stored Part-10
preview_png_path       TEXT
received_at            TIMESTAMPTZ NOT NULL DEFAULT now()
status                 TEXT NOT NULL DEFAULT 'UNBOUND'  -- UNBOUND / BOUND / DISMISSED
status_message         TEXT
match_policy           TEXT                   -- manual / auto (phase 2)
bound_study_subject_id INT
bound_study_event_id   INT
bound_event_crf_id     INT
bound_item_id          INT
bound_by_user_id       INT
bound_at               TIMESTAMPTZ
-- indexes: UNIQUE(sop_instance_uid); (status); (patient_id, study_date)
```

Binding columns are **nullable by design** (images arrive unbound) — note the
retinal table made `event_crf_id` NOT NULL and later relaxed it for the public
portal; we start nullable.

### Config keys (`datainfo.properties`, all opt-in)

```
core.dicom.scp.enabled=false
core.dicom.scp.aeTitle=LIBRECLINICA
core.dicom.scp.port=11112
core.dicom.scp.allowedCallingAeTitles=      # comma list; empty = allow any (test only)
core.dicom.ingest.storePath=/var/lib/libreclinica/dicom-ingest
core.dicom.ingest.token=                     # shared secret, sidecar → app handoff
```

### Slices

- **Slice 0 — schema + config.** `dicom_ingest` migration; config keys; a
  `dicom-scp` compose service (opt-in, app-VM). *Effort: S.*
- **Slice 1 — receiver + ingest API.** `pynetdicom` Storage SCP accepting the
  fundus SOP classes; write Part-10 + preview PNG; extract tags;
  `POST /pages/api/v1/internal/dicom-ingest` (shared-secret guarded) → INSERT
  `dicom_ingest` (UNBOUND). *Effort: M.*
- **Slice 2 — reconciliation inbox.** App API: `GET /dicom-ingest?status=UNBOUND`,
  `GET /dicom-ingest/{id}/preview`, `GET /dicom-ingest/{id}/dicom`,
  `POST /dicom-ingest/{id}/bind`, `POST /dicom-ingest/{id}/dismiss`
  (admin/datamanager-scoped, audited). SPA "DICOM inbox" view: preview grid,
  subject search (reuse `findByLabelPrefix`), event picker (`findEventOnDate`
  suggestions), bind/dismiss. On bind, associate with `event_crf` and — where a
  fundus-image CRF item exists — write an `item_data` reference
  (`source_kind='dicom_ingest'`). *Effort: M.*
- **Slice 3 (Phase 2, deferred) — auto-match.** On ingest, run the two finder
  methods; unambiguous → auto-bind; else → inbox. *Effort: S (reuses finder).*

### Testing (no HealthAEye sample yet)

1. **Synthesize** a VL Photographic / Secondary Capture object with `pydicom`
   (set `PatientID` = a known `study_subject.label`, `StudyDate` = a scheduled
   event date), send it with `pynetdicom`'s `storescu` or dcmtk `storescu` →
   assert: file stored, `dicom_ingest` UNBOUND row, preview renders, bind
   associates, (Phase 2) auto-match hits.
2. **On first real HealthAEye sample:** confirm the actual **SOP Class UID** and
   which tags carry PatientID / AccessionNumber / laterality; adjust the SCP's
   accepted presentation contexts + tag mapping. *(Blocking for production;
   testable with synthesized objects until then.)*

### PHI & security

- Images stay on the app VM (trusted custodian) → **no redaction** (unlike
  DR-022, which redacts only because data leaves to the GPU host). PatientID/Name
  retained for matching + audit.
- SCP: calling-AE allowlist; bind the listener to the internal network only.
  DICOM-TLS deferred.
- Sidecar → app handoff: shared-secret header (DR-022 `remotePushToken`
  pattern); the internal endpoint is `permitAll` but token-gated and never
  exposed through the reverse proxy.

### Open questions / needs input

1. ~~**Q1 — receiver host**~~ — **RESOLVED (2026-09-09): `pynetdicom` sidecar**
   (over in-process `dcm4che`).
2. **Real HealthAEye sample** → SOP class + tag conventions (esp. where PatientID
   and laterality live).
3. **Attachment target:** which CRF item(s) fundus images bind to (an
   image/attachment item on the HealthAEye CRF?), or event-level only for v1.
4. **Laterality:** does the device tag `(0020,0060)`, or does the operator set
   OD/OS at bind time?
5. **Retention:** delete DICOM on dismiss? keep raw Part-10 indefinitely?

### Deferred / out of scope

MWL SCP · MPPS · C-FIND/C-MOVE query-retrieve · DICOM-TLS · OCT/SEG (DR-022
follow-up) · PACS forwarding · multi-institution AE management.
