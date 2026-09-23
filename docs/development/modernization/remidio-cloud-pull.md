# Remidio FOP — pulling captures from the Remidio cloud (proposed DR-031)

**Status:** Accepted (2026-09-23) — feasibility verified live against our tenant the same day; minimal slice implemented on `feature/muw-remidio-pull` behind `core.remidio.pull.enabled` (default off).
**Owner:** Lead Developer (Lukas Kuchernig)
**Purpose:** Let images taken with the Remidio FOP (HealthAEye study) arrive in the reconciliation inbox automatically, instead of the photographer uploading each JPEG through the browser page.

This doc holds the proposed **DR-031** (ready to fold into
[decision-record.md](decision-record.md) once accepted) and a minimal-slice
implementation plan. It sits beside [dicom-fundus-receiver.md](dicom-fundus-receiver.md)
([DR-025](decision-record.md#dr-025--dicom-c-store-receiver-for-handheld-fundus-cameras)), which gave the Optomed Lumo its worklist +
C-STORE path and the Remidio its upload page. The upload page stays; this adds a
second, automatic ingress for the same device.

---

## DR-031 — decision & rationale

**Context.** The Remidio FOP is an iPhone-based camera whose app uploads every
capture to Remidio's cloud; it has no DICOM and nothing can be pushed to it
(no worklist, no patient list — the Remidio "InstaZ" line has a DICOM option,
the FOP does not). Today the photographer re-uploads each JPEG through our
public upload page ([DR-025](decision-record.md#dr-025--dicom-c-store-receiver-for-handheld-fundus-cameras)), i.e. every capture is
handled twice. Remidio exposes a read-only **gateway API** on the cloud backend
that serves our organisation, documented publicly by AIIMS Delhi's integration
([fundus_img_xtract](https://github.com/drguptavivek/fundus_img_xtract/blob/main/docs/API/remidio-integration/README.md)).
On 2026-09-23 we verified the whole chain against **our** tenant with the
client token Remidio issued us — facts that differ from the AIIMS docs and cost
a day to find are recorded here so nobody finds them twice:

| Fact | Value (verified 2026-09-23) |
|---|---|
| Backend host | `https://remidio-backend-germany.appspot.com` — per-user via `remidio-proxy-production.appspot.com/api/domain/domainForUser`; the India host in the AIIMS docs is theirs, not ours |
| Organisation / site | org `5599969650147328` (Medical University of Vienna); site `5898310359449600` "Vienna", `siteCustomIdentifier` **`muw_vienna`** (set by us in the dashboard), `dataStorageLocation: CLOUD` |
| Client identity | header `clientName` **must be `PACS_GATEWAY`** (the JWT's `systemActorID`) + `clientIdentificationToken` (the JWT Remidio sent). Any other client name yields an *unhandled HTTP 500* on `loginUser`/`getAuthToken` — it means "unknown client", not a server outage |
| Auth chain | `POST /api/user/loginUser` `{"emailAddress","password","deviceId":""}` → bearer JWT (≈10 h) → `GET /api/gateway/getAuthToken` (`Authorization: Bearer`) → long-lived `clientAuthToken`. **Each `getAuthToken` call invalidates the previous `clientAuthToken`** → one dedicated Remidio account for the integration, and the token is cached, not re-minted per poll |
| Data calls | headers `clientName` + `clientIdentificationToken` + `clientAuthToken` (+ `Authorization: Bearer` for the exam calls). `GET /api/gateway/getSites`; `GET /api/gateway/getExamsByDate/{DD-MM-YYYY}/{DD-MM-YYYY}/muw_vienna?includeFilePaths=true` (the **custom** id — the numeric id returns 404); `GET /api/gateway/getQueueItem` (404 `NOT_FOUND` = queue empty; our org has `dataQueueEnabled: false` until Remidio switches it on) |
| Payload | exam: `id`, `localId`, `examCustomId`, `examDate` (epoch ms), `reportDate`, `deviceType[]` (`FOP`), `examState`, `customFieldsData`; `patientDetails`: `mrn`, `firstName`, `lastName`, `dateOfBirth`, `gender`, `siteId`; `images.fopImages.STANDARD[]` (also `EDITED`): `id`, `localId`, `examId`, `date`, `laterality` (`RIGHT`/`LEFT`), `field`, `quality`, `width`, `height`, `path` + `thumbnailPath` (**signed URLs, 1 h expiry**), `metadata`, `discQualityResults`. 60-day window on 2026-09-23: 37 exams, 53 images, all with a path and a laterality |
| Not available | any write: no patient creation, no worklist, no MRN assignment. The `WEB_DASHBOARD` client is refused by gateway calls ("Only an authorised Remidio gateway is allowed") |

**Decision.** A **server-side poller** in `core` — the *Remidio pull* — that
treats the Remidio cloud as a third ingress into the existing `image_ingest`
queue and the reconciliation inbox ([DR-025](decision-record.md#dr-025--dicom-c-store-receiver-for-handheld-fundus-cameras)), with
`source_kind = 'remidio'` next to `upload` and `dicom`.

1. **Poll from a watermark, don't queue.** Every `core.remidio.pull.intervalSeconds`
   (default 120) the job logs in if it has no cached bearer, refreshes the
   cached `clientAuthToken` only when a call answers 401, and calls
   `getExamsByDate` on `core.remidio.siteCustomId` for the window
   *(last successful pass − `core.remidio.pull.overlapDays`, default 14) … today*;
   the very first pass lists from `core.remidio.pull.since` (default one
   year back) in 30-day chunks. The watermark (`remidio_pull_state`) moves
   only after every chunk succeeded, so a failed pass is redone and downtime
   catches up by itself. The overlap is not the poll interval: the listing
   filters by **capture date, not upload time**, so it is how late a phone
   may sync and still be caught. The queue endpoint would be the tidier
   contract (each exam delivered once, acknowledged after we persisted it),
   but it is disabled for our org, its payload has never been observed, and
   enabling it means asking the vendor — which we choose not to do. Polling
   needs nothing from anyone.
2. **Dedupe on Remidio ids, never on content.** A new table
   `remidio_exam` (`remidio_exam_id` PK, `site_custom_id`, `exam_date`,
   `first_seen_at`, `image_count`) and `image_ingest.remidio_image_id`
   (unique, nullable) — an exam is fetched once; an image is stored once; an
   `EDITED` variant is ignored (we keep `STANDARD` only). The 1-hour URL expiry
   means download happens in the same poll that discovers the exam, never
   deferred.
3. **One `image_ingest` row per image, identified like an upload.** The
   JPEG goes through `IngestArtifactStore` exactly as a page upload does
   (stored file + preview); the row carries `patient_id ← patientDetails.mrn`,
   `acquisition_date ← the image's own stamp, else examDate` (as a
   Europe/Vienna day), `laterality ← RIGHT→OD / LEFT→OS` (the inbox's own
   vocabulary), `device = 'remidio'`, `content_type` from the sniffed bytes,
   `original_filename = <examId>_<imageId>_<lat>.<ext>`, and
   `acquisition_date_source = 'device'` — a new provenance beside
   `file`/`operator`/`unknown`: stamped by the camera app, nobody typed it,
   so the bind-time visit-date check ([PR #312](https://github.com/LuViKu/LibreClinicaMUW/pull/312))
   trusts it exactly like a DICOM header (`IngestItemRepository.isTrustedAcquisitionSource`).
   **No name, DOB or sex is copied** — `firstName`/`lastName`/`dateOfBirth`/
   `gender` are read and discarded; `patient_name` stays null.
4. **Bind like the upload page does, by label + date.** `IngestResolutionService.resolve(mrn, examDate)`
   (the `StudySubjectFinder` path) — a single candidate visit on that day
   auto-binds with `match_policy = 'mrn'`; anything else lands `UNBOUND` in the
   inbox with the MRN and date pre-filled, which is the same manual step the
   operator performs today for a page upload without a visit. The date check
   from [PR #312](https://github.com/LuViKu/LibreClinicaMUW/pull/312)
   applies unchanged.
5. **Photographer convention.** The MRN typed into the Remidio app **is the
   study subject label** (`HAE-002`), nothing else; name and DOB fields get the
   same placeholder discipline as the Optomed worklist. This is the entire
   "worklist" for this device and belongs in the HealthAEye SOP.
6. **Secrets and switches in `datainfo.properties`**, mirroring
   `core.optomed.worklist.*`: `core.remidio.pull.enabled` (default `false`),
   `core.remidio.baseUrl`, `core.remidio.clientName` (`PACS_GATEWAY`),
   `core.remidio.clientIdentificationToken`, `core.remidio.email`,
   `core.remidio.password`, `core.remidio.siteCustomId`, the interval and the
   look-back. Disabled = the job is not scheduled; the upload page keeps working
   either way.

**Alternatives considered.** *(a) Keep the upload page only* — works, but every
capture is handled twice and the photographer's phone must be able to reach
our reverse proxy. *(b) Use the dashboard's own API* (`/api/exam/getExamsForSites`
with a user bearer) — it works from a script too, but it is the web app's
private contract with no vendor commitment; the gateway API is what Remidio
hands to integrators. Kept as a documented fallback only. *(c) Remidio's queue
from day one* — preferable contract, but disabled on our org today; the poller
is written to switch. *(d) A sidecar like `dicom-scp`* — unnecessary: this is
plain HTTPS/JSON from the app VM, no raw sockets, no Python-only libraries.

**Consequences.**
- Data protection: captures and whatever the photographer types transit
  Remidio's Google-hosted cloud regardless of this DR (the app already uploads
  there); the pull adds an institutional consumer of that data and needs the
  DPA/region answer (`germany` backend, `dataStorageLocation: CLOUD`) on
  record. The poller never stores identity beyond the pseudonymous MRN.
- The app VM needs outbound HTTPS to `*.appspot.com` and to the signed-URL host
  (`storage.googleapis.com`). Verified from the developer machine; to be
  checked from `vrc-lin-tasks`.
- A second Remidio device (another site or the same site) needs no code: one
  more `siteCustomId` in the property, or a second poller instance.
- Remidio outages surface as a stale "last successful pull" timestamp on the
  system status page, not as lost data — the window is re-polled.

**Reversible** — additive schema (one table, two nullable columns), one job,
one property block; disabling the flag returns the system to DR-025 behaviour.

---

## Minimal-slice implementation plan

Estimated 2–3 days, testable against the live tenant with the credentials in
hand. Order chosen so each step is demonstrable.

What landed on `feature/muw-remidio-pull` (2026-09-23):

1. **`RemidioGatewayClient`** (`core/…/service/ingest/remidio/`): the auth chain
   with cached bearer + `clientAuthToken`, `getSites`, `getExamsByDate`, the
   signed-URL download (https only, no gateway headers); one re-login on 401,
   then `UNAUTHORIZED`; the envelope's own message on a remote error; the
   wire abstracted (`Transport`/`Downloader`) so `RemidioGatewayClientTest`
   scripts every case without a network, including a listing in the exact
   shape our tenant returned. Query strings and tokens never reach a log line.
2. **Liquibase** `lc-muw-2026-12-04-remidio-pull.xml` (the sequence in
   `master.xml` runs ahead of the calendar): `remidio_exam` and
   `ingest_item.remidio_image_id` with a partial unique index.
3. **`RemidioPullService`** (`web/…/controller/api/`, beside the other ingest
   services so it can reuse their package-private bind/audit/tick helpers):
   window → exams → `STANDARD` images → download with a 64 MB cap → sniff →
   `IngestArtifactStore` → `IngestItemRepository` → `IngestResolutionService`
   one-click bind (`match_policy = 'mrn'`, subject to the study's
   `ingest.image.enabled` setting) or `UNBOUND` with the candidate subject
   recorded; system bind audit + performed-tick as the worklist path does. An
   exam is marked seen only once all its images were handled, so a failed
   download is retried next poll. `RemidioPullServiceTest` covers the mapping.
4. **`RemidioPullScheduler`**: `@Scheduled` fixed-delay tick every 30 s, runs a
   pass when `intervalSeconds` elapsed, reads every switch from
   `datainfo.properties` on each tick, rebuilds the client when the settings
   change, one pass at a time; exposes `lastSuccess()`/`lastError()`.
5. **Provenance**: `acquisition_date_source = 'device'` added and trusted by
   the bind-time check; the inbox stops flagging such dates as unverified.
6. **Ops**: `docker/config/datainfo.properties` block, `deploy/README.md`
   section, `deploy/remidio/remidio-probe.sh` (walks the chain by hand,
   tokens masked, listing summarised as counts).

Still open, deliberately: the status-page tile for `lastSuccess`, a `remidio`
source badge in the inbox (the inbox does not badge sources today), and the
HealthAEye SOP line for the MRN convention. Roll-out behind the flag on beta.10+1, the upload
page kept in parallel for the first week.

Nothing is asked of Remidio (decision 2026-09-23): the integration runs on the
token already issued, the polling path needs no org setting flipped, and the
data-protection facts the record needs — Germany backend, `CLOUD` storage —
were read from the tenant itself.
