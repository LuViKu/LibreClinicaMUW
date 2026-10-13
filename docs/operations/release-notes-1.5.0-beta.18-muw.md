# LibreClinica MUW · 1.5.0-beta.18-muw release notes

_Successor to **1.5.0-beta.17-muw**. A feature release: platform-wide **locations** (DR-040). A location (*Standort*) is a physical place: a clinic, a department or a partner hospital. Studies and their sites are placed at locations, every file a device sends has an origin location, and the inbox shows a person only the device files of their own locations. Acquisition PCs authenticate with a per-uploader **token** issued for one location. Whether an installation has one location or several is a **deploy-time mode**, and the default (`single`) changes nothing for the internal deployment. The per-study site is now called **Prüfzentrum** in German, so that "Standort" means only the global place. The release also resolves every open code-scanning alert (#427)._

For older releases see [release-notes-1.5.0-beta.17-muw.md](release-notes-1.5.0-beta.17-muw.md) and its predecessors.

**Deployment-breaking:** nothing. No key has to be set, the setup script is unchanged, and the acquisition PCs keep working without a token. Three things to know before the window:

1. **Five new changelog files (eight changesets) run on the first start.** They create the location tables and point every existing study, site and inbox file at one seeded location, **MUW-AUGEN** ("Medizinische Universität Wien – Augenklinik", renameable on System → Standorte).
2. **The location mode defaults to `single`.** Leave `LIBRECLINICA_DEPLOYMENT_LOCATIONS` unset. Multi-location mode is a later, deliberate deploy step (see [Location mode](#single-or-multi-a-deploy-time-mode)).
3. **New uploader scripts are optional now and required before a switch to multi:** Export Watcher `2026-10-12.2`, Optomed Bridge `2026-10-12.1`. The Export Watcher version also fixes `.e2e` files with several volumes that were set aside instead of uploaded; such files have to be moved back by hand (see [Smaller items](#smaller-items)).

Work through [Upgrading the app VM](#upgrading-the-app-vm) in order, then through [What needs a human to check](#what-needs-a-human-to-check-after-the-upgrade).

---

## Highlights

### Locations, and System → Standorte (#418, DR-040 phase 1)

- **A location is global** and managed by system administrators on **System → Standorte** (`/admin/locations`). Each one has a code (fixed after creation), a name and an address. The list counts its sites, studies, files and assigned people. Disabling is refused while a study or site there is still active.
- **A study is active at a location by having a site there.** Each site points to one location. A study without sites points to its location directly. For a parent study with sites, the location is its coordinating one and may be empty.
- **A person's locations** are those of the sites they hold a role at, plus, for a role on a parent study, the locations of all its sites. Staff without a study role, such as technicians or photographers, are added to a location under *Zugewiesene Personen*. A system administrator has every location.
- **Audited:** location created, edited, disabled and restored, and a person assigned or removed (audit types 195–200, system audit log only).

### The inbox is scoped by location (#418)

- **An unfiled device file** (camera, upload page, DICOM receiver, Remidio) is visible to people at its origin location. Until this release, every device file was visible to everyone who could open the inbox, whatever study or site they worked in.
- **A staff upload** stays visible through its origin study, as before, and is also stamped with that study's location.
- **A file with no known origin** is visible to system administrators only.
- **Filed files are unchanged:** the subject's study decides, and so does all study data (subjects, CRFs, notes, SDV, exports).
- **On a single-location installation nothing changes.** Everything is at the one location, so everyone who saw a file before still sees it.

### Ingest sources (#419, DR-040 phase 2)

- **Every file records its source.** A source is an uploader on an acquisition PC, a DICOM calling AE title or the Remidio account (by its screening site, `core.remidio.siteCustomId`). Each file stores its source and, as its origin, the source's location.
- **No sources exist right after the upgrade.** An uploader appears when its token is issued; a DICOM AE title and the Remidio account appear the first time they deliver. In single mode a new source gets the one location automatically.
- **The *Quellen* tab** on System → Standorte lists every source with its location and its waiting files. Assigning a location to a source moves its waiting files into that location's inbox at once.
- **At most 200 DICOM sources** are created while the receiver's calling-AE allow-list is empty (its default). A title past that limit is filed without a source, and a log line once an hour says so. Setting `DICOM_SCP_ALLOWED_CALLING_AE_TITLES` to the cameras' titles keeps the limit out of reach.

### Uploader tokens, the Standort-Admin right and the Uploader page (#419)

- **One token per uploader, valid for one location.** It is sent as `X-MUW-Uploader-Token` on the visit lookup, the upload, the heartbeat and the Optomed worklist. With a token, the lookup answers only for visits at that location and the worklist lists only that location's visits. The upload preflight ("is this file already here?") answers only about files at that location.
- **Shown once, stored as a hash.** A token is `mwu_` followed by 43 characters. It is shown once at issue. The platform keeps its SHA-256 and an eight-character hint. A revoked or replaced token is refused on the next request.
- **Narrow.** A token opens no session and reads no study data.
- **System → Uploader und Tokens** (`/uploaders`) issues, renames, replaces and revokes tokens, and shows each token's last use and the program's last report. Next to the token, the issue dialog offers a link for the browser upload page, `…/upload#token=…`. The token sits in the URL fragment, which no server or access log sees, and the page keeps it in `sessionStorage` only.
- **The Standort-Admin right** is granted by a system administrator per person and location (checkbox *Standort-Admin* under the location's assigned people). It lets the person manage that location's uploaders and nothing else. A study role does not include it. A Standort-Admin without any study role is taken to the Uploader page after signing in, instead of an empty study picker.
- **Audited:** source created, token replaced, token revoked, uploader renamed, source assigned to a location, Standort-Admin right granted or revoked (audit types 201–207). No audit row contains a token, only its hint at most. The system audit log names the uploader or AE title next to its row.
- **System Status** spells out the two token problems an uploader reports (*Token fehlt*, *Token abgelehnt*) when its heartbeat still gets through.

### Single or multi: a deploy-time mode (#419, DR-040 amendment)

`LIBRECLINICA_DEPLOYMENT_LOCATIONS` (Spring property `libreclinica.deployment.locations`) chooses the mode. The number of locations never changes it.

| | `single` (default; the internal VM) | `multi` |
|---|---|---|
| Active locations | exactly one; a second can be neither created nor restored (409) | any number |
| Device request without a token | served, filed at the one location | `401 {"message":"uploader token required"}` |
| Device request with a valid token | served, filed under that uploader | served, for that uploader's location only |
| Unknown, revoked or replaced token, or one at a disabled location | `401 {"message":"uploader token rejected"}` | the same |
| Study, site, source or file without a location | belongs to the one location | system administrators only, until assigned |
| The shared `core.optomed.worklist.token` | accepted for the worklist | not accepted; the bridge needs its uploader token |

- **The application refuses to start** on any other value, and in single mode on a database with more than one active location. The log names the variable and the remedy.
- **Every single-mode start** gives the one location to studies, sources and unfiled files that have none. Each table it changed writes one row of audit type 208 (`location_backfilled`), so a later switch to multi finds nothing without a place.
- **The switch to multi** is a runbook step: every PC gets its token while still in single mode, the Uploader page's count of programs without a token must read 0, then the variable is set and the stack restarted. See [deploy/README.md, "Uploader tokens and locations (DR-040)"](../../deploy/README.md#uploader-tokens-and-locations-dr-040).
- **A token that cannot be checked** because the database is unreachable answers 503 with `Retry-After`. It is never treated as a pass.
- **A signed-in browser session does not get past the token check** on the device endpoints.

### Sites are "Prüfzentren" and sit at a location (#420, DR-040 phase 4)

- **Wording.** In German the per-study site is a **Prüfzentrum** (plural *Prüfzentren*): on the Sites page, its home card, the rails, System → Standorte and the manual. "Standort" means only the global place. English keeps "site" and "location".
- **Creating a site** (`/sites`) requires a **Standort**. The facility name, city, postcode and country are copied from the location; the free facility name and city fields are gone, and the contact e-mail stays. The select is preselected when there is only one option. The site list shows each site's location.
- **Moving a site** (*Standort ändern*, multi mode only, for those who may manage sites; not offered for a removed, locked or frozen site) re-copies the facility fields from the new location. The new location must be available and in the mover's own scope. The move is audited as a site field change (`location`, old and new location code), so it needs no new audit type.
- **Creating a study** (system administrators) **and editing one** (also its study directors): in multi mode a Standort select, for the study or, with several sites, its coordinating location. In single mode the server takes the one location. A site's location is changed only on the Sites page.
- **The legacy JSP pages `CreateStudy` and `CreateSubStudy` answer 410 in multi mode**, because they cannot choose a location. Single mode is unchanged.
- **API:** new `GET /api/v1/locations` returns the locations the caller may place a study or site at. `StudyIdentityDto` gains `locationId`, `locationCode` and `locationName`. `PUT /api/v1/studies/{parentOid}/sites/{siteOid}` and `PUT /api/v1/studies/{studyOid}` take an optional `locationId`.

### Schedules by location: camera worklist and Remidio sync (DR-040 phase 3)

- **The camera worklist (DICOM MWL C-FIND) is the camera's location's.** The dicom-scp receiver now passes the calling AE title to the app, which lists only the visits of studies at that title's location (still within `core.dicom.worklist.studyOids`). A worklist query also makes the title a source, so a camera that only queries shows on *Quellen* too.
- **The Remidio patient sync pushes only the subjects at the account's location.**
- **Single mode:** both see the whole schedule, as before.
- **Multi mode:** an unassigned camera is offered an empty worklist, an unassigned Remidio account pushes nothing, and a worklist request without a calling AE title (an older receiver) is refused. **Upgrade the dicom-scp receiver together with the app** (same tag, as always).
- The Optomed worklist already follows the bridge's uploader token (phase 2).

### Code scanning: every open alert resolved (#427, #425, #426)

- **#427:** the 855 alerts open on `main` (848 CodeQL, 7 Trivy) are resolved. 532 are fixed, and 323 are dismissed on GitHub, each with its reason. They close on `main` once it is rescanned after this release.
- **#425, #426:** the six alerts that the location work raised are resolved. Five are fixed: request-supplied values no longer reach log lines, and the uploaders query is a constant statement. One, a CSRF warning on the internal camera worklist, which accepts only the sidecar's token header, is dismissed as a false positive.
- **Jackson 3.1.5 → 3.1.7**, fixing the 7 Trivy CVEs. `jackson-bom` is now imported ahead of Spring Boot's.
- **Real defects among the fixes:**
  - a `PreparedStatement` left open in `NewCRFBean`;
  - the context path doubled in the URL of rule e-mails;
  - a `NullPointerException` in the domain status types' `getI18n*` on a null field;
  - an integer overflow in an hours-to-milliseconds conversion beyond about 596 hours;
  - `UpdateEventDefinitionServlet` and `ExportExcelStudySubjectAuditLogServlet` going on after an error or a forward;
  - the System status probe throwing, and leaking its connection, when the probe failed;
  - non-numeric ids answering 500 in `RemoveCRFVersionServlet`, `Validator` and `ExtractController`;
  - unbounded regular expressions in `SdRetinaNetReader`.
- **Behaviour changes to know about:**
  - Data entry fails with a clear error when the form has no section or event CRF; it used to throw a `NullPointerException`.
  - The site audit log lines keep the OIDs and the user name, filtered for control characters.
  - The discrepancy-note table search matches the note type's name.
  - A role pointing at a missing study returns to the user list with a message (`EditStudyUserRoleServlet`, a screen that closes in wave W1).

### Uploader scripts: Export Watcher 2026-10-12.2, Optomed Bridge 2026-10-12.1

- **A token setting.** Both scripts take a token in their settings dialog (tray icon → *Settings...* → *Uploader-Token*). It is DPAPI-protected and never passed on a command line. They send it on every request when it is set, and behave as before while it is not.
- **A 401 leaves every file where it is.** Nothing is moved, failed or filed without a visit. The tray says *Token fehlt* or *Token abgelehnt - bitte in den Einstellungen pruefen*, and the next round tries again. A 401 is never treated like a 429.
- **When to install.** Optional on a single-location installation; required on every PC before a switch to multi.

---

## Smaller items

- **Creating a site no longer answers 500** (#417). `POST /studies/{oid}/sites` saved the site and then failed while setting its OID, so the site existed, the client saw 500, and every retry got 400 because its name and protocol id were taken (seen on production on 2026-10-10). The OID is now set before the insert, `StudyDAO.updateStepOne` tolerates a missing old status, and the SPA confirms a created site with a notice.
- **The Export Watcher uploads `.e2e` files with several volumes.** Before 2026-10-12.2, a `.e2e` with more than one OCT volume was set aside in `_skipped\` as if it were a non-image object. After updating, move every `.e2e` out of `_skipped\` (and, where *UploadNonImage* was on, out of `_failed\`) back into the watched folder. The Clarus Raw Data objects in `_skipped\` are `.dcm`; leave them there. A volume the platform already has is answered "already there". Details: [deploy/README.md](../../deploy/README.md#uploader-tokens-and-locations-dr-040).
- **The internal deployment throttles no device**, with or without a token (#404). The per-token budgets apply only where the rate limiter is on, which is the internet-facing deployment.
- **`GET /api/v1/me`** gains `locationMode` (`single` | `multi`) and `manageableLocations` (the locations whose uploaders the caller may manage).
- **New administrator endpoints:**
  - `/api/v1/admin/locations` (CRUD and assigned people, incl. `canManageSources`);
  - `/api/v1/admin/ingest-sources` and `PUT …/{id}/location`;
  - `GET /api/v1/admin/ingest-sources/anonymous-uploaders` (the pre-switch count).
- **Uploader endpoints** for administrators and Standort-Admins:
  - `GET /api/v1/uploaders`;
  - `POST /api/v1/locations/{id}/uploaders`;
  - `PUT /api/v1/uploaders/{id}`;
  - `POST /api/v1/uploaders/{id}/replace` and `…/revoke`.

  A Standort-Admin who names another location's uploader gets 404.
- **Documentation:** DR-040 and its two plans ([location-scoping-plan-2026-10-10.md](../development/modernization/location-scoping-plan-2026-10-10.md), [location-mode-plan-2026-10-11.md](../development/modernization/location-mode-plan-2026-10-11.md), #416); the deploy README section on tokens and the location mode; the in-app and app manuals (sections *Standorte* and *Uploader und Tokens*, "Prüfzentrum" wording).

---

## Migrations

Five new changelog files, eight changesets, all additive. The only data changes are the seed location and the backfill of the new location columns.

| Changelog | What it does |
|---|---|
| `lc-muw-2026-10-11-locations.xml` | three changesets: creates `location` and `user_location` (with `can_manage_sources`), adds nullable `study.location_id` and `ingest_item.origin_location_id`; seeds one location (`MUW-AUGEN`) when none exists and sets it on every `study` and `ingest_item` row without one; then adds the foreign keys and the index after the backfill. Rollback statements included. |
| `lc-muw-2026-10-12-audit-types-locations.xml` | seeds audit types 195–200 (location created, updated, disabled, restored, user assigned, user unassigned). |
| `lc-muw-2026-10-12-ingest-source.xml` | two changesets: creates `ingest_source` (kind, source key, display name, nullable location, unique token hash, token hint, created/used/revoked columns); adds nullable `ingest_item.ingest_source_id`. No backfill: no source exists after the upgrade. |
| `lc-muw-2026-10-12-audit-types-ingest-sources.xml` | seeds audit types 201–207 (source created, token replaced, token revoked, uploader renamed, source assigned to a location, Standort-Admin right granted, revoked). |
| `lc-muw-2026-10-13-location-mode.xml` | seeds audit type 208 (`location_backfilled`, written by the single-mode startup). |

The backfill updates every row of `study` and `ingest_item` once; the dry run shows how long that takes on the production data. Liquibase stays at 4.31.1. Phase 4 adds no changeset.

---

## Configuration

- **New, optional: `LIBRECLINICA_DEPLOYMENT_LOCATIONS`** in `/etc/libreclinica/env` (`single` or `multi`, default `single`).
  - `deploy/compose.production.yaml` passes it with the default `single`.
  - `application.yml` reads it as `libreclinica.deployment.locations`.
  - `setup-ubuntu-host.sh` does not write it.
  - Leave it unset on the internal VM.
- **No other key is new or changed.** #427 changes no configuration and adds no migration; it only pins Jackson to 3.1.7 in `pom.xml`.
- **`datainfo.properties`:** both files only gained a comment that the token requirement is the location mode, not a setting there (checked against the diff since 1.5.0-beta.17-muw).
- **`core.optomed.worklist.token`** keeps working in single mode only.

---

## Upgrading the app VM

Do this outside clinic hours. The restart takes the app down for a few minutes, and everyone signs in again afterwards.

1. **Back up, and dry-run the migration against that backup.**
   ```sh
   sudo systemctl start libreclinica-backup-db.service   # or take a pg_dump as in the runbook, §1
   IMAGE=ghcr.io/luviku/libreclinicamuw:1.5.0-beta.18-muw sudo -E /opt/libreclinica/deploy/dry-run-migration.sh
   ```
   Expect the five files above (eight changesets), nothing else, and a healthy start. Note the **current image tag**: it is the rollback. **Keep the dump** until the smoke test has passed.

2. **Run the host setup script, then pull and restart:**
   ```sh
   sudo bash /opt/libreclinica/deploy/setup-ubuntu-host.sh --image-tag 1.5.0-beta.18-muw   # --dicom too, if a camera speaks DICOM here
   sudo systemctl restart libreclinica
   ```
   Do not set `LIBRECLINICA_DEPLOYMENT_LOCATIONS`. The log should say `Location mode: single, location MUW-AUGEN (…)`. No audit row of type 208 is expected after the upgrade, because the migration has already placed everything.

3. **Expect everyone to sign in again.** Sessions do not survive the restart.

4. **Smoke test, then decide.** Run the runbook's §4 and the checks below. **If anything is wrong, roll back now.**

5. **Optional, at your own pace: update the uploader scripts and issue tokens.** Nothing requires this while the installation stays in single mode. Per PC:
   - install Export Watcher 2026-10-12.2 or Optomed Bridge 2026-10-12.1;
   - move the `.e2e` files out of `_skipped\` (see [Smaller items](#smaller-items));
   - issue a token on *Uploader und Tokens* and enter it in the PC's settings.

   The deploy README's rollout steps apply. Once every PC sends a token, its uploads are filed under its own name.

### Rolling back

Stop the service and start the previous image tag (set `--image-tag` back to the beta.17 value, re-run the setup script, restart). The new tables and columns are additive and nullable, and beta.17 does not read them, so the beta.17 image should run on the migrated database without a restore, as between any two beta releases. Three cautions:

- **What beta.17 writes has no location.** Studies, sites and files it creates get none. Starting beta.18 again in single mode places them on its first start (audit 208).
- **Uploader scripts with a token keep working against beta.17.** It ignores the header.
- **Restoring the pre-upgrade dump** is needed only to undo data changes. The usual Liquibase rule holds: never start a 3.x image on this database ([§5 of the runbook](deploy-runbook.md#5-rollback)).

---

## What needs a human to check after the upgrade

- **System → Standorte:**
  - one location, MUW-AUGEN, with every study and site counted and the inbox's files;
  - no *Standort anlegen*, and the single-location note shown;
  - rename it if the name should differ.
- **The inbox, as a physician with a study role and no administrator rights:** the same device files as before the upgrade.
- **An acquisition PC without a token** (old script) still uploads and appears on System Status.
- **Uploader und Tokens:**
  - create a test uploader and check that the token is shown once and the dialog closes only after *Ich habe den Token gesichert*;
  - open the upload page through the link and upload a test file;
  - revoke the uploader, and check the system audit log for the rows with the uploader's name.
- **Standort-Admin:**
  - grant the right to a test account and sign in as it: only *Uploader und Tokens*, only for that location;
  - an account without a study role lands on the Uploader page;
  - withdraw the right.
- **Create a site** in a test study:
  - 201 and a success notice, no 500 (#417);
  - with phase 4: the Standort select preselected, and the facility name and city taken from the location.
- **System audit log:** rows of types 195–207 for the changes made during these checks, each with its name.
- **The dry run** ended with the expected five files and no FAIL.

---

## Still open at this release

- **CI results.** CI run [38355458878](https://github.com/LuViKu/LibreClinicaMUW/actions/runs/38355458878) on `b2d24982a` (lc-develop with #425–#427) passed all seven jobs:
  - Maven build and unit tests: core 574, web 1,540;
  - integration tests on PostgreSQL 14 and 17: web integration profile 4,547, core 654;
  - the compose smoke test, including `api.ts` against the live spec;
  - the SPA: 2,504 vitest tests, 4 skipped;
  - the three Python sidecar suites: muw-e2e-converter 14, dicom-scp 107, retinal-inference 289.

  CodeQL and the security scan passed on the same commit. The location work alone (`6fbc9081d`, run 38260435301) had passed the same jobs before #425–#427.
- **One Remidio account is one source:** a second clinic on Remidio would need a second account (not supported yet).
- **Not yet sources:** the public upload page without a token (the `portal` kind exists in the schema, but nothing creates one).
- **Duplicate pictures across locations.** A tokened upload of a picture already filed at another location still gets that location's duplicate or held verdict, without its ids.
- **Multi mode has not run in production.** Before a second location goes live:
  - every PC has its token and the pre-switch count reads 0;
  - the new location's sites, people and sources are in place.
- **DR-040 is still *Proposed*** in the decision record.
- **Carried over from beta.17 and still open:**
  - SSO `/me` after a header-only sign-in;
  - DICOM OCT clinical validation (`deploy/compare-oct-jobs.sh`) and de-identification validation on real samples;
  - the SLURM account;
  - administrative correction of signed data;
  - SPA annotations stored as "New" before #390;
  - the retinal sidecar's audit insert column;
  - SD-RetinaNet on the cluster;
  - PostgreSQL 14 end of life on 2026-11-12 ([postgresql-17-upgrade.md](postgresql-17-upgrade.md));
  - the internet-facing deployment's go-live gates ([multicenter-internet-readiness.md](multicenter-internet-readiness.md)).

  See the [beta.17 notes](release-notes-1.5.0-beta.17-muw.md#still-open-at-this-release).
