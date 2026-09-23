# DICOM C-STORE receiver for handheld fundus cameras (HealthAEye)

**Status:** Accepted as [DR-025](decision-record.md) (2026-09-09) — in progress.

> **Design updated 2026-09-09.** "HealthAEye" is the *study*, not a camera. Two devices, different capabilities: **Remidio FOP** (an iPhone + browser → a **web upload page**, no DICOM) and **Optomed Lumo** (a full **DICOM modality** → **Modality Worklist + C-STORE**: it pulls our worklist, then stores the study carrying accession `LC<study_event_id>`, which the ingest endpoint auto-binds to the visit — MWL was un-deferred 2026-09-11 after a live capture showed the Lumo won't send an association without a worklist). **Live-verified with the Lumo 2026-09-17** (after a device firmware update): C-ECHO → MWL C-FIND (calling AE `OPTOMEDLUMO`, station AE `LUMO`, keys = SPS start date + station AE, charset `ISO_IR 192`; it also proposes Study-Root Q/R MOVE, which we reject) → C-STORE of *Ophthalmic Photography 8 Bit Image Storage* in **JPEG Baseline (Process 1)** (one object per eye, Laterality R/L) → `image_ingest` row `BOUND` via `match_policy='worklist'` with the StudyInstanceUID we issued. Two device facts to plan around: the sidecar must offer compressed transfer syntaxes (fixed: all storage contexts × `ALL_TRANSFER_SYNTAXES`), and the Lumo flushes its **entire** stored-study backlog (local-patient studies included) the moment a storage target answers — those land `UNBOUND` in the inbox. One source-agnostic **`image_ingest`** queue (`source_kind` = `upload` | `dicom`) + one reconciliation inbox serves both. **Landed:** Slice 0 (schema + config), Slice 1a (DICOM ingest endpoint), schema generalized to `image_ingest`. Sections below still read DICOM-first and are being generalized as slices land.
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

MPPS · image Query/Retrieve (study-root C-FIND/C-MOVE) · DICOM-TLS · OCT/SEG (DR-022
follow-up) · PACS forwarding · multi-institution AE management.

## Optomed Lumo over USB — the Client worklist file and the bridge (2026-09-23)

The wireless path above never left the bench. The Lumo speaks WPA2-PSK only;
the institutional WLAN is WPA2-Enterprise with no PSK SSID, and departments may
not operate a WLAN of their own. A mobile-hotspot-plus-VPN detour was tested
and fails for a reason worth recording: iOS does not route Personal Hotspot
clients through the phone's VPN. What the camera does have is USB to a PC
running the vendor's **Optomed Client**, and that turned out to carry the whole
workflow — established against the real Client on 2026-09-23:

| Finding | Consequence |
|---|---|
| The Client watches `…\Optomed Lumo\Worklist\` for a file named exactly `worklist_optomed_lumo.txt`, imports it, deletes it | a watched drop-folder with a fixed name: the simplest automation target there is |
| An import **replaces** the Client's whole worklist (3 in the file → 3 in `worklist.json`, the previous 6 gone) | every fetch sends the complete current list; stale entries vanish on their own; nothing deduplicates |
| The Client pushes the list to the camera over its USB "Web API" | the camera's list updates on dock, so the photographer docks between patients after enrolling someone |
| The exported DICOM carries `PatientID` from the worklist, `StudyDate`/`AcquisitionDateTime`, `Laterality`, `Modality=OP` and all three instance UIDs | the upload path has everything: identity, a `source='file'` acquisition date, and duplicate detection |
| `AccessionNumber` is **camera-generated** and the template has no accession field | the sidecar's `LC<study_event_id>` auto-bind cannot fire; binding goes through label + date via `/resolve` — the same thing the upload page does |
| The exported header carries the real name and date of birth | the Client's `Studies\` folder on the clinic PC is a PHI surface; ingest pseudonymises, the folder does not |

Two pieces:

- **`OptomedWorklistApiController`** — `GET /api/v1/device/optomed/worklist.txt`,
  the day's open visits rendered by `OptomedWorklistFormat` in the vendor's
  six-line CRLF/ASCII shape. It is *not* the DICOM worklist with a different
  content type: that endpoint is refused at nginx precisely because it hands
  out real dates of birth behind a shared secret, and this one must be reached
  from a clinic PC through nginx. It earns the exposure by carrying a
  placeholder date of birth, having its own token, answering 404 until
  `core.optomed.worklist.enabled=true`, and honouring
  `core.dicom.worklist.studyOids`. Same `ScheduledVisitQuery` as the DICOM
  worklist and the upload page, so the three never disagree about what is open.
- **`deploy/optomed/OptomedBridge.ps1`** — a tray app on the clinic PC that
  fetches the file every 30 s by default (dropping it only when the content
  changed — a poll is one scoped single-day query, so the rate is cheap, and
  it has to beat the photographer's walk from the enrolment PC to the dock),
  uploads pulled studies through the public front door with `PatientID` +
  `StudyDate` read from each header, and lets `/resolve` bind them. Settings in
  a dialog; token DPAPI-protected; counts and labels in the log, never names.
  It also acts as the Client's keeper: starts it if it is not running and
  minimises its window with the taskbar button removed (`SW_MINIMIZE` plus the
  shell's `ITaskbarList::DeleteTab`, restored with `AddTab` + `SW_RESTORE`).
  The Client is WinForms hosting a WebView2 with no tray support of its own;
  its USB and folder watching live in the process, not the window. Minimised,
  never hidden: `SW_HIDE` from outside detaches the WebView2's render target
  for good (white, then black, only a Client restart recovers), and DWM
  cloaking is refused cross-process — all three established on the real
  Client. Show/Hide in the menu; the window is given back on exit.

Spontaneous enrolment is why the fetch is periodic rather than a morning pull —
and why it lists *visits*, not subjects: a subject enrolled without today's
visit scheduled is not on the camera. The worklist is a convenience against
typos, not a requirement — a label typed on the camera binds exactly the same
way, because `/resolve` keys on `PatientID` + date and does not know where the
label came from.

Verified on the Client, still to verify on the camera: that `O` is accepted as
a sex value (the vendor template shows only `M`/`F`), and that an empty file
clears the camera's list rather than being ignored.

## Uploaded DICOM files — the describe endpoint (DR-029, 2026-09-20)

The receiver above answers C-STORE. A Clarus or PlexElite export arrives as a *file* on the combined upload page instead, and the app has no DICOM parser — so the sidecar gained a second, internal entry:

```
POST http://dicom-scp:8081/describe        X-MUW-Dicom-Token: <core.dicom.ingest.token>
{ "path": "/var/lib/libreclinica/ingest/dicom/<uuid>.dcm", "pseudonym": "HAE-001" | null }
→ 200 { sopInstanceUid, sopClassUid, studyInstanceUid, seriesInstanceUid, modality,
        studyDate, acquisitionDate, laterality (OD/OS/OU), manufacturer,
        manufacturerModelName, transferSyntaxUid, rows, columns,
        previewPngPath, identityRemoved: true, changedTags: n }
```

Order of operations in the app (`IngestUploadService`): store the file under `core.ingest.storePath` → dedup by SHA-256 → resolve the visit the operator picked (the label written into the file is the visit's subject and nothing else) → per-study gate → **describe** (the sidecar pseudonymises in place, renders `<file>.png`, answers the tags) → dedup by SOP Instance UID → row with `deidentified_at`. Any refusal after the store deletes the file and the preview.

What the sidecar rewrites is `dicom_scp/deidentify.py` — a list, deliberately, so what leaves and what stays is legible there: identity replaced, demographics/contacts/staff/institution/hospital numbers blanked, identity-only sequences removed, UIDs and dates and device and private tags kept, pixels not decoded, `PatientIdentityRemoved` and `DeidentificationMethod` stamped. `DICOM_SCP_DEIDENTIFY_DROP_PRIVATE=true` removes vendor private tags for a deployment that would rather lose calibration than keep them.

Failure modes, by design: sidecar unconfigured or unreachable → 503 to the page and no file kept; a file the sidecar cannot read → 400, no file kept; a path outside the ingest roots → 403 from the sidecar (never acted on); token missing → 401. Paths never reach the routine log on either side: they carry an operator-supplied filename fragment.

Config: `DICOM_SCP_DESCRIBE_PORT` (0 = off; never in `ports:`), `DICOM_SCP_DESCRIBE_ROOTS` (CSV; blank = the C-STORE store + `/var/lib/libreclinica/ingest`), `core.dicom.describe.url` on the app. Both containers must mount `core.ingest.storePath` at the same path.
