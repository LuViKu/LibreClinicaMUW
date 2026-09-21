# LibreClinica MUW · 1.5.0-beta.8-muw release notes

_Successor to **1.5.0-beta.7-muw**. This window is **pilot readiness**: the DICOM fundus receiver reaching production shape for the HealthAEye study, correctness fixes across data export and the nAMD decision aid, the test safety net that found most of them — and then the consolidation those fixes argued for, where every inbound file, whatever device sent it, joined one queue._

For older releases see [release-notes-1.5.0-beta.7-muw.md](release-notes-1.5.0-beta.7-muw.md) and its predecessors.

**Deployment-breaking:** none, but the DICOM receiver needs configuration before a camera is connected. See [Before connecting a camera](#before-connecting-a-camera).

---

## Highlights

Two studies launch on this platform: **HealthAEye** (handheld fundus photography) and the **nAMD AI study** (OCT fluid segmentation with a treat-and-extend decision aid). This release is what those two need in order to run.

The honest summary of what was found along the way: several features that reported success did nothing. A dataset export wrote an empty file and recorded it as complete. The filter builder never persisted a filter. The one-click export failed three separate ways. Fluid volumes on a clinical screen were ten times too small. Each of these looked like it worked.

### Fundus image ingest (DR-025)

- **The Optomed Lumo works end to end**, verified against the real device: it pulls a Modality Worklist, the operator picks the patient on the camera, and the image that comes back is filed against that visit automatically.
- **The Remidio InstaFOP uploads through a browser page** that needs no login, because the operator is standing at a camera with a phone. It can now carry its visit, picked from the day's list or from a typed subject label, so the image is filed on arrival rather than queueing for reconciliation.
- **Binding an image ticks the visit's modality checklist.** An image from a device, bound to a visit, *is* the evidence that the device was used. An operator's own value is never overwritten, including when they recorded that the modality was not performed.
- **A reconciliation inbox** for images that arrive without an identity, with binding and dismissal, both audited.
- **Dismissed images no longer live forever.** A nightly sweep removes them once the review window has passed. Bound and unbound images are never swept.

### One queue for every inbound file (DR-026, DR-029)

The fundus work above landed beside an OCT uploader that had its own queue, its own inbox and its own word for an unmatched file. Two pipelines doing the same job diverge, and the second one is always the less tested. They are now one.

- **`image_ingest` became `ingest_item`**, behind a single repository, with the retinal queue backfilled into it. The OCT portal files into the same inbox; the separate "parked" state is retired.
- **One inbox, one bind service, and an unbind that actually undoes** — including the audit rows, which the previous bind path left behind.
- **One upload page for OCT, DICOM and photographs.** An operator no longer has to know which door a device's file goes through. **Clarus DICOM is pseudonymised on ingest.**
- **One artifact store, and path confinement that resolves symlinks.** Two copies of "whose visit is this file for?" and two of the removed-form case became one each — the duplicates disagreed.

### What a study declares about itself (DR-027)

- **The imaging catalogue is editable.** What a study photographs is configuration rather than a constant in the code, with a settings panel for it.
- **Per-study settings and item bindings**, so ingest scope resolves per study rather than per instance.
- **An architecture test stops one study's vocabulary spreading into shared code** — nAMD terms had been reaching parts of the platform every study uses.

### Data export

- **ODM export works.** It failed on any study whose CRFs were seeded with a `width_decimal` in SQL notation, which included the nAMD visit CRF — so that study could not be exported at all.
- **Tabular exports work.** They crashed on any visit recorded without a location.
- **SAS export produces real files.** Every route outside the scheduled-job screens wrote a zero-byte file and recorded it as a successful export.
- **Dataset filters are saved and applied.** The filter builder was decorative: predicates were never persisted and never reached the extract.
- **The one-click ODM export works.** It selected nothing, crashed rather than exporting nothing when a selection was empty, and aborted entirely if any CRF in the study had been marked complete.
- **An export that produces no file answers 500** instead of handing back a download link to nothing.
- **A subject's whole record can leave as one bundle**, files included — offered where a study enables it, written through the job queue so a large dataset does not block a request. Per [DR-028](../development/modernization/decision-record.md) the bundle **carries its evidence, and the casebook says which values no person typed**.

### nAMD decision aid

- **Fluid volumes were ten times too small.** The conversion to nanolitres multiplied by 100 rather than 1000, so every threshold fired at ten times the volume it named. Corrected, with thresholds scaled so **no recommendation changes**; adopting the specification's literal figures is a clinical decision recorded in [the rules document](../development/study-modules/namd-treat-and-extend-rules.md).
- **A finished inference writes its numbers into the CRF.** The endpoint that does this had no caller, so measurements lived in a viewer and never reached an export.
- **A decision can schedule the next visit.** The panel recorded an interval and stopped; the appointment existed only if somebody remembered to make it.
- **Trial blinding is pinned by tests** at both the predicate and the endpoint.
- **The mock-data URL parameter is inert in production builds.**

### Study scoping and data protection

- **The worklist and the portals are scoped to configured studies.** A Modality Worklist hands a camera subject labels, sex and dates of birth; a handheld on a clinic bench is shared between studies. Blank keeps the previous behaviour, so **production must set these** (see below).
- **The internal device API is refused at the edge** by nginx, under both the context path and the clean-URL root.
- **No patient identifiers in log statements**, enforced in CI. The gate found five lines logging the operator-typed subject label on an ordinary success path.
- **Sites inherit their study's modules.** The nAMD workspace was absent for everyone working at a site, which is where the patients are.

### Finding your way around (DR-030)

- **The home page is a dashboard** and the top bar is navigation, rather than both being a list of links.
- **One landmark per concern** — top bar, page trail, section rail — so a screen reader announces a page once, and a sighted operator does not meet three competing ways back.
- **A subject's page shows the camera worklist**, so the person at the camera and the person at the screen can see the same thing.
- CRF Library version actions now meet the WCAG 2.2 target-size rule.

### Operations

- `deploy/dry-run-migration.sh` — restores a backup into a throwaway database, boots the candidate image, and names the changesets it applied.
- [`docs/operations/data-retention.md`](data-retention.md) — what expires, what never does.
- [`docs/tests/t046.md`](../tests/t046.md) — the operator acceptance script for image ingest.
- `deploy/dicom-dry-run.sh` — proves the camera path before a patient is in the room.
- The backup step now archives the file stores alongside the database dump. The database holds only paths.
- **Every file store now lives under `/var/lib/libreclinica`**, bound at the same path in every container. Previously each store was mounted wherever its service happened to want it, so a path that resolved in one container did not in another. Check your bind mounts before deploying — this is the one change in this release that can strand existing files.

---

## Migrations

**Fourteen** new Liquibase changesets run on boot. **Back up the database and the file stores before deploying**, and consider running `deploy/dry-run-migration.sh` against that backup first.

Image ingest (DR-025):

- `lc-muw-2026-09-09-image-ingest.xml` — the `image_ingest` table behind both ingress paths
- `lc-muw-2026-09-09-audit-event-type-image-ingest.xml` — audit types for image bind and dismiss
- `lc-muw-2026-09-18-ingest-performed-item-map.xml` — the device-to-checklist-item map, the `item_data` back-reference, the auto-tick audit type, and a locked `system` service account for writes nobody performed

The unified ingest queue (DR-026, DR-029):

- `lc-muw-2026-10-05-ingest-item.xml` — `image_ingest` becomes `ingest_item`, the one queue
- `lc-muw-2026-10-05-audit-types-ingest.xml` — audit types for the unified bind / unbind
- `lc-muw-2026-10-19-retinal-job-ingest-item.xml` — the retinal queue's back-reference into it
- `lc-muw-2026-12-02-ingest-item-deidentified.xml` — records that a Clarus DICOM was pseudonymised on arrival

What a study declares (DR-027):

- `lc-muw-2026-11-02-imaging-modality.xml`, `…-audit-types-imaging-modality.xml`, `…-seed-imaging-modalities.xml` — the editable imaging catalogue and its seed
- `lc-muw-2026-12-02-imaging-modality-dicom-kinds.xml` — which DICOM kinds a modality accepts
- `lc-muw-2026-11-16-study-setting.xml` — per-study settings and item bindings
- `lc-muw-2026-12-01-namd-flag-bindings-oid.xml` — nAMD flag bindings hold OIDs rather than names

Export (DR-028):

- `lc-muw-2026-11-30-export-format-zip.xml` — the bundle format

The `system` account cannot log in. It exists so that a value written by a machine does not carry a person's name in the audit trail.

> **Note on the filenames.** Eleven of these carry dates between October and December 2026 although they were written in September. The date is only part of the filename — Liquibase identifies a changeset by `id` + `author` + file path — so ordering and application are unaffected. They are **not** renamed here: the path is part of the changeset's identity, so renaming an applied changeset breaks checksum validation. Worth correcting the habit, not the history.

---

## Configuration

New keys, all with safe defaults. Blank study-scope keys keep the previous unrestricted behaviour, which is right for a single-study development instance and **wrong for production**.

**These keys will not reach an existing host on their own.** `/opt/libreclinica/config` is seeded once and left alone on re-runs, and the bind-mounted `datainfo.properties` *replaces* the bundled copy rather than overlaying it — so a key missing from the host file is one the app never sees, silently falling back to the calling code's default. This release adds a merge step to `setup-ubuntu-host.sh` that appends template keys the host file lacks without touching any value already set. After upgrading the image:

```sh
sudo bash /opt/libreclinica/deploy/setup-ubuntu-host.sh
sudo systemctl restart libreclinica
```

Use the copy under `/opt/libreclinica/deploy/`; the `/root/libreclinica-setup/` bootstrap copy is frozen at first install. Then set the production values below.

| Key | Default | Set it when |
|---|---|---|
| `core.dicom.scp.enabled` | `false` | A camera talks DICOM to this host |
| `core.dicom.scp.aeTitle` | `LIBRECLINICA` | The camera expects a different AE title |
| `core.dicom.scp.port` | `11112` | — |
| `core.dicom.scp.allowedCallingAeTitles` | blank (accepts any) | **Always**, in production |
| `core.dicom.ingest.token` | blank | **Always**, when DICOM is enabled; 32+ hex characters |
| `core.dicom.ingest.storePath` | `/var/lib/libreclinica/dicom-ingest` | — |
| `core.dicom.describe.url` | blank | A DICOM describe service is reachable |
| `core.ingest.storePath` | `/var/lib/libreclinica/ingest` | — |
| `core.dicom.worklist.studyOids` | blank (every study) | **Always**, on a multi-study instance |
| `core.ingest.portal.studyOids` | blank (every study) | **Always**, on a multi-study instance |
| `core.ingest.portal.todaysVisits` | `false` | Only after data-protection sign-off |
| `core.ingest.retention.dismissedDays` | `30` | The review window should differ |

---

## Before connecting a camera

The full pre-flight table is in the [deploy runbook](deploy-runbook.md) §7. In short:

1. `core.dicom.ingest.token` and `DICOM_SCP_INGEST_TOKEN` set to the same 32+ hex value.
2. `DICOM_SCP_ALLOWED_CALLING_AE_TITLES` set to the camera's AE title. Blank accepts an association from anything that can reach the port.
3. `core.dicom.worklist.studyOids` set to the study the camera serves.
4. `LIBRECLINICA_DICOM_BIND_ADDR` set to the camera-facing address, and the campus firewall admitting the camera VLAN to port 11112 and nothing else. **A C-FIND against that port returns subject labels, sex and dates of birth.**
5. nginx returning 404 for `/LibreClinica/pages/api/v1/internal/`.
6. `DICOM_SCP_LOG_LEVEL=INFO`. Debug level makes the DICOM library dump whole datasets into the container log.

Then run `COMPOSE_PROFILES=dicom deploy/dicom-dry-run.sh`, and only then connect the real device. **Clear the camera's stored-study backlog first** — it flushes everything it holds on first contact.

---

## Still open at this release

- The HealthAEye CRF item identifiers and the coded value for "yes" are seeded with the platform's best guess. If the study's CRF differs, the checklist tick is skipped with a warning naming the item, and the seeded rows need correcting.
- The today's-visits list on the unauthenticated upload page ships **off**, pending data-protection sign-off.
- The SAS output needs acceptance by the study statistician.
- The nAMD thresholds ship behaviour-preserving. The clinical lead decides whether to adopt the specification's literal figures.
- The compose smoke test has no DICOM leg; the camera path is covered by the dry-run script and by the sidecar's own test suite.
- **Translation coverage slipped.** `check-i18n` reports 116 keys where the German and English strings are identical, up from 38 at beta.7. Many are legitimate (`Java`, `JAVA_OPTS`, `n = {n}`), but this release added a lot of operator-facing screens and some are genuinely untranslated — at least one, `crfEntry.prefill.button`, has German text sitting in the *English* bundle. The gate was already failing before this release and is not treated as blocking; worth a translator sweep before the pilots begin.
