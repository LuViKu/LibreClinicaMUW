# Internet-facing deployment

A second deployment of this application may sit behind the MUW DMZ reverse proxy and serve a multicenter study to the internet. It runs the same image as the internal one. One switch changes its posture:

```
LIBRECLINICA_DEPLOYMENT_INTERNET_FACING=true      # property: libreclinica.deployment.internet-facing
```

Default `false`; the internal deployment is unchanged. Set it in `/etc/libreclinica/env` (compose passes it through).

## What the switch does

| Area | Effect when `true` | Where |
|------|--------------------|-------|
| Account-less portals | `/pages/api/v1/public/**` (OCT upload, BCVA entry, image upload, combined upload) answer a bare 404 | `SecurityConfig.INTERNET_FACING_DENIED_PATHS`, `InternetFacingPathBlockFilter` |
| Sidecar / device APIs | `/pages/api/v1/internal/**` (DICOM ingest, worklist) and `/pages/api/v1/device/**` (Optomed worklist, uploader heartbeat) answer 404 | same |
| Operational endpoints | `/actuator/info`, `/actuator/prometheus`, springdoc (`/pages/v3/api-docs*`, `/pages/swagger-ui*` and the bare `/v3/api-docs*`, `/swagger-ui*`) answer 404 | same |
| Healthcheck | `/actuator/health` stays open | unchanged |
| Login failures | locked, 2FA-outdated, unknown and wrong-password all redirect to the same `errorLogin`; the audit row and the denied-login mail still carry the real reason | `SpaLoginFailureHandler` |
| Startup | refuses to start if any active account still has the default password (`12345678`, as seeded for `root`), in either the seeded MD5 form or a bcrypt rehash of it | `InternetFacingStartupGuard` |
| Legacy screens | every legacy servlet and JSP screen, and every `/pages` Spring MVC route that is not on the open list below, answers 410 to anyone who is not a system administrator (signed in or not). External site staff use the SPA only. A system administrator is redirected to the `/legacy/` alias (catalogued screens) or passed on (uncatalogued `/pages` routes) | `LegacyServletTelemetryFilter` (`closedPaths` mechanism) |

The deny list is applied twice: the 404 filter runs first, and the authorization rules deny the same patterns if the filter were ever absent.

## Before the first start

1. Start once with the switch **off**, sign in as `root`, change its password (and any other seeded account's), then set the switch and restart. With the switch on, the start fails with a message naming each account that still has the default password.
2. Scrape metrics from inside the network only. `/actuator/prometheus` is not reachable on this deployment; use the internal one or an exporter on the host.

## Account lockout

Migration `lc-muw-2026-10-06-account-lockout.xml` turns lockout on (`user.lock.switch = TRUE`) with five allowed failed logins, but only where the switch still has its seeded value `FALSE`. An administrator unlocks an account in the SPA (Users, Unlock) or in the legacy UI; the SPA unlock also restores the account status, which a failed-login lock changes to `locked`.

On this deployment the locked state is not shown to the person typing, so a locked user learns of it from the administrator.

## What the switch does not do

- `/app/**` (the SPA shell) stays public; the upload routes in it render but every API behind them is 404.
- The remaining anonymous paths are the login page, `/RequestAccount`, `/Contact`, `/pages/api/v1/contact`, the static assets, `/error` and `/actuator/health`. `/pages/auth/**` (heritage API-key REST API), `/SystemStatus` and, with SSO off, `/pages/sso/reauth` also answer 404 here. The OpenRosa, ODM and anonymous-form paths were removed from the public list for both deployments with the participant chain (#373).

## Legacy screens: what stays open

The SPA calls no legacy servlet. Its login is `POST /j_spring_security_check` (a security filter), its logout `POST /pages/api/v1/auth/logout`, and the active study, password change and everything else go through `/pages/api/v1`. So the servlet allow-list (`INTERNET_FACING_OPEN_SERVLETS`) is empty, with one conditional entry:

| Path | Open for | Why |
|------|----------|-----|
| `/MainMenu` | callers with no signed-in user only | The concurrent-session filter and a legacy form login send the browser here, and it forwards an anonymous caller to the login page. For a signed-in non-administrator it would render the legacy home page, so it answers `302` to `<context>/app/` (the SPA home). A session replaced by a second login still holds its user, so it lands in the SPA, whose first call gets 401 and shows the login view. |

Open `/pages` paths (`INTERNET_FACING_OPEN_PAGES`): `/api/` (the SPA API, and the portals and device endpoints, which the path block above handles), `/login/` (the login page and the target of a failed form login), `/sso/reauth`, `/v3/` and `/swagger-ui` (denied by the path block on this deployment).

Everything else is closed. The closed set is the whole legacy catalogue plus any `/pages` path outside the open list, so a route added later is closed by default. `libreclinica.legacy.closedPaths` still applies on top.

No SPA code links to or fetches a legacy servlet. A role that still depends on one (for example the legacy print views `/PrintDataEntry` and `/PrintCRF`) gets 410 here; check with the study team before go-live.

## Uploaded files must be de-identified

```
LIBRECLINICA_INGEST_DEIDENTIFICATION_REQUIRED=true   # property: libreclinica.ingest.deidentification.required
LIBRECLINICA_INGEST_DEIDENTIFICATION_SCAN_CRON=0 30 2 * * *   # nightly re-scan; "-" turns it off
DICOM_SCP_DEIDENTIFY_STRICT=true                     # dicom-scp sidecar; compose derives it from the switch above
```

The default of the first is the value of `LIBRECLINICA_DEPLOYMENT_INTERNET_FACING`, so the internet-facing deployment has it on and the internal one off, unchanged. It is reported to the SPA as `deidentificationRequired` on `GET /pages/api/v1/me`.

Three layers. The browser strips the file first (layer 1). The server never trusts that (layer 2) and looks again at what is stored (layer 3).

**Layer 2, on every authenticated upload (`POST /pages/api/v1/ingest/upload/commit`):**

| Check | Failure |
|-------|---------|
| Only E2E and DICOM, by their bytes. JPEG, PNG, anything else | 415 |
| `patientId` is the label of a live study subject the caller's site visibility reaches (and, when a visit is named, that visit's subject). Never free text | 422 `patientId` |
| `deidConfirmed=true` and `deidSha256` equal to the SHA-256 of the bytes received | 422 `deidConfirmed`, `deidSha256` |
| The received filename is `<label>_<yyyyMMdd>_<OD\|OS>[_<n>].<e2e\|dcm>` for that label; that name is what is stored and what the preprocess sidecar is sent | 422 `filename` |
| E2E: every type-9 patient record has `first_name`, `title`, `birthdate`, `sex` blank, and `surname` and `patient_id` blank or exactly the label; the structure must walk, and image chunks need a patient record | 422 `e2e.*` |
| DICOM: the sidecar's `/verify` runs on the file BEFORE it is modified: name and id blank or the label, the cleared attributes and a further list (sex, size, weight, descriptions, device and station, operators) empty, no private tag at any depth, no burned-in annotation, `PatientIdentityRemoved=YES`. The in-place rewrite then still runs, strict | 422 with the keyword |

A refusal is `422 {"code":"DEID_REQUIRED","message":...,"violations":[field names]}` and the stored file is deleted. No value from the request or the file is returned or logged; the audit trail gets a `deid_upload_rejected` row (193) holding the field names and the file's SHA-256. An accepted upload gets a `deid_upload_confirmed` row (192) on its `ingest_item`: user, SHA-256 and time, nothing else.

Closed while required: the account-less upload routes (`/public/**`, already 404 when internet-facing), `POST /pages/api/v1/event-crfs/{id}/oct-upload` (no caller in the SPA; it takes no label, confirmation or neutral name) and the CRF item file upload (`POST /pages/api/v1/eventCrfs/{id}/items/{oid}/file`, 403 with a message: it stores a file nobody reads).

**Layer 3** (`DeidentificationScanner`): nightly while required, and on demand with `POST /pages/api/v1/admin/deidentification/scan` (sysadmin; works whether or not the mode is on). It re-verifies every `.e2e` under `core.retinalInference.e2eUploadsPath` and every E2E and DICOM `ingest_item` with the same verifiers (DICOM through `/verify`, read-only), and checks that `ingest_item.patient_id` is a study label, `original_filename` is the neutral name and `patient_name` is empty. A finding is an ERROR log line, an audit row (194, once per distinct finding) and an entry in `GET /pages/api/v1/admin/deidentification`. A scan that could not check something (sidecar down) reports `incomplete`, never clean. Files stored before the mode was switched on will be reported until they are removed.

Not covered: ODM XML imports (`/api/v1/import/**`) can carry identifying text in item values, and CRF template uploads are spreadsheets; neither is a patient file but neither is checked here.

**Server-initiated ingest while de-identification is required** (independent of the internet-facing flag, since required means nothing unverified gets in): the account-less upload routes and the DICOM C-STORE hand-off (`/internal/dicom-ingest`) answer 404 from the security chain and 403 from their controllers; the Remidio pull does not run on its schedule and `RemidioPullScheduler.runOnce()` refuses by hand (the Remidio patient sync, which only sends labels out, is unchanged); startup logs one line naming the closed paths. A camera's C-STORE then fails at the sidecar instead of being kept; use the staff upload page. Not closed because they only act on files already verified or on stored rows: follow-up inference jobs (`RetinalJobFollower`), starting a job by hand from a stored E2E, and the fingerprint backfill.
