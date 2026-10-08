# LibreClinica MUW · 1.5.0-beta.16-muw release notes

_Successor to **1.5.0-beta.15-muw**. The release in which the legacy screens start to close: the administration screens and the heritage REST API now answer `410` and point at their SPA replacements. Everything they did is in the SPA, and every clinical write is checked on the server for the caller's role and the record's state. The database layer moves to Liquibase 4, Hibernate 6.6 and Quartz 2.5. The work for a separate, internet-facing multicenter deployment lands as well: cross-site isolation between the sites of a study, de-identification of imaging uploads, and a hardened edge. That deployment is a mode that stays off on this one, but the isolation fixes and part of the edge hardening apply here too._

For older releases see [release-notes-1.5.0-beta.15-muw.md](release-notes-1.5.0-beta.15-muw.md) and its predecessors.

**Deployment-breaking, in three ways.**

1. **The rollback is a database restore.** The first start rewrites every stored Liquibase checksum, and an older image started on the upgraded database re-runs 25 changesets and inserts duplicate data. Rolling back means restoring the dump taken before the upgrade, and it must be decided in the deploy window.
2. **The setup script must run before the restart.** The production compose file now requires `POSTGRES_PASSWORD` and mounts the nginx sites directory and the `edge` network. The script provides all three.
3. **Users will notice.** The legacy administration screens close, a large set of legacy links become POST-only, account lockout is on, and the self-service "forgot password" page is gone.

Work through [Upgrading the app VM](#upgrading-the-app-vm) in order, then through [What needs a human to check](#what-needs-a-human-to-check-after-the-upgrade). **Read [The upload rate limit now works](#the-upload-rate-limit-now-works--watch-the-acquisition-pcs-8a9779085-fe87f5387) before the upgrade**: it changes what the acquisition PCs see.

---

## Highlights

### The legacy administration screens close (DR-018, waves W0 and W1; #370, #371, #396)

DR-018 retires the legacy JSP layer in waves. Each closed screen gets a six-month bake-in before it is deleted.

- **How a closed screen behaves.** A closed path answers `410 Gone` to everyone but a system administrator. The response names the SPA page that replaces it.
- **A system administrator** is redirected to `/legacy/<path>`, which still serves the old screen for the bake-in. `/legacy/` is administrator-only; everyone else gets 404.
- **Every hit on a legacy path is logged** on the `legacy-access` logger. The lines also go to their own file, `<log dir>-legacy-access.log`, which is kept 220 days so the bake-in can be reviewed.
- **Wave W0 (17 screens and the heritage REST API).** These have no SPA replacement because none is needed:
  - the Enterprise and admin home pages;
  - the schema change log;
  - the heritage subject registry;
  - scheduled ODM imports;
  - the two-factor letter;
  - the hard delete of event-CRF data;
  - the password-requirements page;
  - the OpenClinica 3.x REST API under `/pages/auth`, which nothing in the app, the SPA or the deployment calls.
- **Wave W1 (43 paths, the administration screens).** These are now in the SPA:
  - users and roles;
  - system settings and the test e-mail;
  - login and audit logs;
  - the study list with remove and restore;
  - the metadata download;
  - scheduled jobs;
  - the CRF library.

  The full table, with the replacement for each path, is in `phase-e-retirement-log.md`.
- **Held open:** `/ViewCRF`, `/ViewStudy`, `/SystemStatus`, `/MainMenu`, `/Logout`, `/CreateDataset` and the study-build screens. They go with later waves.
- **Reopening without a release.** `LIBRECLINICA_LEGACY_CLOSED_PATHS` overrides the closed list, and an empty value reopens everything.

### Administration is in the SPA (#377, #381, #387, #389, #395)

- **Studies.** System administrators get an all-studies list at `/admin/studies`. Remove and restore cascade exactly like the legacy servlets, in one transaction, need a reason, and show a preview of what they will take. Contact e-mail, collaborators and detailed description are editable; a study's contact e-mail used to be dropped on save. A new study's switch-in no longer gets a 404. ODM metadata can be downloaded.
- **Users.** Administrator types can be granted and revoked; a business administrator cannot demote a technical one. There are access-review details (who created and changed an account). A system administrator can grant a role in any study.
- **Login history** at `/admin/login-history`, with server-side filters and a CSV export.
- **Lockout settings** sit on the password-policy page, and every setting change is audited (type 145).
- **A test e-mail** to the caller's own address, on System Status.
- **Two password-policy bugs are fixed:**
  - "Force a password change on first login" was switched off by any save of the page.
  - The seeded "no maximum length" could not be saved.
- **CRF library.**
  - A CRF can be edited, viewed with the legacy integrity check, previewed and restored.
  - Remove and restore are exact inverses, and each event CRF they touch is audited (types 190/191).
  - Existing event CRFs can be moved to another CRF version, in a batch or one at a time, at `/crf-library/:oid/migrate`.
  - A Data Manager or CRC of one study can no longer remove or restore a shared CRF whose data lives in another study.
- **Export schedules** can be edited, paused, resumed and deleted on `/export`. An optional address gets a mail when a scheduled run finishes or fails; the mail carries no file and no error text. A running export can be cancelled, except in the tabular formats, which finish.
- **Legacy XSLT export jobs** are listed read-only on `/admin/jobs` and can only be deleted there. See upgrade step 3.
- **The CRC (legacy "Data Manager", the role every study's creator gets)** now reaches the 15 study-build, CRF-library, export and audit pages the backend already admitted it to. No backend right is widened.
- **The study audit log API** now requires a director, coordinator, monitor or administrator, as the legacy page always did.

### Every clinical write is checked on the server (#386, #390, #391, #392, #397)

- **Role matrix.** Data entry, SDV, signing, eye transitions and retinal actions check the caller's study role before any row is read, and answer `403` otherwise.
- **One record-state guard.** A write into a removed record, a locked or signed subject, or a locked or frozen study answers `409` with a code that names the widest reason.
- **SDV stays true to the data.**
  - Only complete CRFs are offered and verified.
  - A change to verified data withdraws the verification.
  - Un-verifying needs a reason, and the reason is stored.
- **Reason for change** is required after a CRF is reopened, and the study's `adminForcedReasonForChange` is honoured.
- **Mark complete** answers `400` with the list of required items that are still empty. Show-when conditions count, and an item with an active note is accepted, as in legacy. Saves are checked against the CRF's `func:` and `regexp:` validations, with the legacy messages.
- **Subject identifiers** (Person ID, date of birth, sex) are enforced as each study's parameters say. Three bugs in the subject edit are fixed:
  - the secondary ID was written into Person ID;
  - the O and U sexes were nulled;
  - year-only dates of birth were hidden.
- **The automatic writers keep the form's rules.** The retinal populator, nAMD flags and the BCVA portal refuse closed records, withdraw SDV on a change, and record their provenance.
- **Data Manager.**
  - ODM import can commit from the SPA.
  - The audit logs are paged in the database; the 500-row cap is gone.
  - An event CRF can be removed with a reason (type 155) and restored at its prior status.
  - Removing an event definition or group class cascades like legacy, and a restore brings back only what that removal took.
- **Monitor.**
  - Read-only access to subjects and CRFs.
  - Re-query, close, re-open and reassign queries, as legacy allows.
  - SDV export and a per-subject verify.
  - Queries on subject, visit and CRF-header fields.
- **Value provenance** survives every status cascade, legacy and SPA (#397).
- **The tri-state reason** (Ja / Nein / Unbekannt with a reason) was shown twice, and text typed into the inline field was lost. It is now shown once and saved (#362). **Check existing data:** where a tri-state item was answered "Nein" or "Unbekannt", the reason may be empty.

### Legacy actions that change data accept POST only (#367, #380)

- **107 legacy servlets** used to commit on a plain GET: removals, restores, reorders, notes, signing, study switches, mail and scheduler actions. A GET that would commit now answers `405`, before any work is done. A GET that only renders a page still works. The pages' own buttons and links submit POST forms and work as before.
- **Legacy authorization fixes:**
  - Initial and double data entry require a data-entry role and stay within the current study.
  - Reordering, rule pages and notes keep to the current study.
  - A note's status follows what the page offers the role.
  - Legacy data entry saves nothing into a closed record.
- **Administrative editing of a signed subject is refused** in both UIs. Legacy allowed it and withdrew the signature. See [Still open](#still-open-at-this-release).
- **`/MainMenu`** records the visit with a single-column update. It used to rewrite the account and re-insert a system administrator's admin role on every visit.

### Sign-in, lockout and the forgotten-password page (#384, 22cd59466, 09211322a)

- **Sign-in and sign-out in the SPA** no longer go through `/MainMenu` and `/Logout`. Each sign-out writes exactly one logout audit row; two paths used to write two. A second login in the same browser no longer inherits the previous account's study and role.
- **Account lockout is on by default.** It locks after 5 failed attempts. The changeset sets this only where the switch still has its seeded value (off), so a setting someone chose is kept. Locked accounts are unlocked on the SPA's user page. That unlock used to leave the account unable to sign in.
- **Self-service password reset is removed** (`/RequestPassword`), as decided on 2026-06-05. An administrator resets passwords from the user page, as before.

### Cross-site isolation between the sites of a study (#401)

A user whose roles are all on one site of a multicenter study must read or change nothing of another site. Two new test suites drive every SPA handler (150 of the 300 request-mapped handlers) and the legacy servlets as each site role, with the other site's ids. Everything they found is fixed, on both deployments:

- **Legacy.** `ChangeStudy` binds only a study the user holds a live role in. A role-check denial now ends the request. Every servlet that resolved a record from a request id now checks it against the session's study tree.
- **Ingest.** Staff uploads record the uploader's study (new column `ingest_item.origin_study_id`). The inbox, binding, undo and duplicate checks show a site only its own items. Items without an origin (device and anonymous uploads) stay visible to all, so the internal deployment behaves as before.
- **Datasets, study configuration and retinal jobs.** These are read within the caller's study tree; another site's object answers `404`. A retinal job of another site answers `403`/`404`, not a status that reveals it.
- **Match preflight** names no person the caller has no enrolment for.

### nAMD recommendations fail closed (447a69873, 045784084)

- **Unknown data no longer counts as "no disease".** The treat-and-extend engine treated unknown inputs that way: a missing volume, a failed BCVA fetch or an unrecorded flag became 0 or false, so a visit with nothing measured could be recommended EXTEND.
- **Any missing input now gives "insufficient data".** The card lists what is missing. KEEP and EXTEND cannot come from unknown inputs; a SHORTEN from known data still fires.
- **Placeholder-model results** carry a banner that cannot be dismissed and is printed in the report. They never yield a recommendation.
- **Unknown volumes are gaps in the charts**, not zeros.
- **Thresholds and rule precedence are unchanged.**

### The internet-facing deployment mode (#401; off on this deployment)

A separate VM behind the MUW DMZ proxy will serve the multicenter studies. `setup-ubuntu-host.sh --internet-facing` (or `INTERNET_FACING=true`) builds it:

- **What the mode does:**
  - a host firewall;
  - loopback-only binds;
  - no DICOM listener (a verify-only sidecar instead);
  - encrypted backups;
  - closed portals and device APIs;
  - a uniform login failure;
  - a start-up refusal while any account has the seeded password;
  - every legacy screen closed for non-administrators.
- **De-identification of imaging uploads.** It is required in that mode:
  - the browser strips patient identity from E2E and DICOM files before upload;
  - the server re-verifies every file;
  - a nightly scan re-checks what is stored (audit types 192–194).
- **The mode is refused** without the required settings (admin CIDRs, a backup key, a real retinal adapter and an https push URL).

Nothing of this is active here unless the mode is switched on. The runbook is [internet-facing-deployment.md](internet-facing-deployment.md), and the go-live gates and open questions are in [multicenter-internet-readiness.md](multicenter-internet-readiness.md).

### The shared nginx is hardened, and also serves DutyPlan (#401, #400)

These apply to **this** deployment too:

- **TLS** is the Mozilla "intermediate" profile: TLS 1.2 and 1.3 with AEAD ciphers only, and no session tickets. **Clients that only speak CBC suites can no longer connect**: Windows 7, Server 2008 R2 and Server 2012 (non-R2), Java 7, and Android below 5. See step 7.
- **HSTS** is on for a year. Port 80 only redirects, so the site was already HTTPS-only.
- **Headers.**
  - `server_tokens off`.
  - Added: `Referrer-Policy: same-origin`, `X-Content-Type-Options: nosniff`, and a CSP limited to `frame-ancestors`, `base-uri` and `object-src`.
  - `X-Forwarded-For` is set by nginx.
  - The SSO identity headers are blanked on every proxied request.
- **Unchanged here:** body limits, timeouts and rate limiting. Those are per mode, and this deployment keeps the old values.
- **DutyPlan** (DR-038) is served at `https://einteilung.augen.meduniwien.ac.at` through this nginx, over the external Docker network `edge`. Its server block is installed only once its certificate is in `/etc/libreclinica/tls/einteilung-augen.{crt,key}`, so a missing DutyPlan certificate can never stop the eCRF's nginx.

### The upload rate limit now works — watch the acquisition PCs (8a9779085, fe87f5387)

The public-upload rate limit compared paths without the `/LibreClinica` context, so it had never throttled anything. It now works on both deployments:

- **The budgets.** Lookups are capped at 30 per hour per address and portal. Commits on the combined upload page get 300 per hour, and heartbeats 240 per hour.
- **The key** is the client address Tomcat resolves, not a self-set `X-Forwarded-For`.

**This changes what the Export Watcher and the Optomed Bridge see.** Both call `/public/upload/resolve` to find the visit of a capture:

- the Watcher once per sweep;
- the Bridge once per file.

On a `429` they file that capture **without a visit**, and it waits unbound in the ingest inbox. On a busy clinic day a single acquisition PC can pass 30 lookups an hour. Nothing is lost, but captures may need binding by hand. See step 10, and [Still open](#still-open-at-this-release) for the fix this needs.

### The database layer moves (#388, #385, #376)

- **Liquibase 3.6.3 → 4.31.1.** This closes CVE-2022-0839.
  - The seven heritage `modifyColumn` elements are still skipped, as 3.6.3 did.
  - New databases keep `serial` columns rather than identity columns.
  - The version is pinned below 4.33.0, which would change the checksum of every `valueDate` changeset.
  - A new IT replays a frozen 3.6.3 changelog of 1,202 rows and requires that nothing re-runs.
  - **See the rollback warning at the top.**
- **Hibernate 6.6.53 and Quartz 2.5.2**, now managed by Spring Boot.
  - All untyped queries are now typed.
  - A role query that had thrown on every call is fixed. Because of it, the REST clinical-data export's role check used to refuse everyone.
  - Quartz's job-store tables are unchanged, and stored jobs keep loading.
- **PostgreSQL 17** is the version for development, tests and CI.
  - **Production stays on 14.** The production overlay pins it (`LIBRECLINICA_POSTGRES_IMAGE_TAG`, default `14-alpine`).
  - PostgreSQL 14 reaches end of life on 2026-11-12. The move is a separate, rehearsed step: [postgresql-17-upgrade.md](postgresql-17-upgrade.md) and `deploy/pg-major-upgrade.sh`.

### Library updates (#385, #366, #368, #402)

- **OpenPDF 2.0.5** replaces iText 2.1.2 (DR-007).
  - Both PDF documents, the discrepancy-note export and the casebook, are pinned by golden-text tests.
  - The casebook now prints ≥ and ≤; they were dropped before.
  - BouncyCastle 1.38 leaves the WAR.
- **Jackson 2.18.11**, for four advisories.
- **xom 1.3.9.** `xml-apis` is gone, and `xercesImpl` and `xalan` are declared explicitly at their old versions, so XML parsing and XSLT behave as before.
- **SPA.**
  - `vue` 3.5.43.
  - Overrides for `adm-zip` 0.6.1, `source-map-js` 1.2.2, `brace-expansion`, `fast-uri`, `markdown-it` and `postcss-selector-parser`.
  - The development story build (Histoire) starts again.
- **Trivy is now a blocking check in CI**, against an accepted-findings list with expiries.

### Retinal inference on SLURM (8a70faa10, dafc6f2ea)

The GPU sidecar can run each task as an `srun` job on a SLURM cluster:

- typed GPU resources, checked at start-up;
- up to four concurrent runs;
- clean cancellation;
- an exclude or node list;
- the IOWA chain as a CPU-only job.

This concerns the GPU host only. Direct mode is unchanged. Section 4 of [retinal-inference/docs/cluster-deployment.md](../../retinal-inference/docs/cluster-deployment.md) describes the setup.

The sidecar's `/health` now lists only the tasks the node can run, not every task the code knows. A task whose model is not configured shows as missing, so the launcher and the app VM's cluster monitor report the node as degraded. Before this, that alarm could not fire.

### SD-RetinaNet: layers and lesions in one task (`sdretinanet`)

A seventh retinal task runs SD-RetinaNet (Fazekas et al., [arXiv 2509.20864](https://arxiv.org/abs/2509.20864)). It segments, per B-scan:

- 12 layer boundaries, ILM to the choroid–sclera boundary;
- 7 lesion classes: IRF, SRF, PED, SHRM, SDD, ORT and HRF.

The output is stored in the OPTIMA group's native formats (layerlib `.yml`, lesionlib `.png`), the same files the SWITCHER study and iamd-ws read.

- **Metrics** are a port of the SWITCHER quantification, and a test holds the Java code to the Python reference's numbers on a shared fixture:
  - the fovea, from the inner-retina pit; a doubtful pit falls back to the scan centre and is flagged;
  - 18 layer thicknesses and volumes, and lesion volume, area and height, over the central 1, 3 and 6 mm discs and the 1–3 and 3–6 mm rings;
  - CRT (ILM–BM, central 1 mm).
- **Left out on purpose:**
  - The ETDRS quadrant sectors. They depend on the B-scan order and A-scan direction of the app's own `.e2e` conversion, which is not verified yet.
  - CRF items. The task fills none, so it cannot overwrite the fluid task's values. Which numbers feed a CRF is a clinical decision.
- **Viewer:** the B-scan viewer shows the 12 boundaries and the lesion masks. Both are read-only.
- **Off until configured.** A study enables the task in its visit imaging plan. The cluster runs it once the model image is copied into place (see [Still open](#still-open-at-this-release)); until then the node reports the task as missing.

### Smaller repairs

- **`/ViewStudy`** answered 500 for every study and works again. `/ConfigurePasswordRequirements` rendered blank. A session displaced by a second login answered 500 and now redirects. The export-job list failed whenever a cron trigger existed.
- **Rule notification e-mails** had never been sent; they now go through the local mail server.
- **The participant-form chain is removed** (XForm / OpenRosa / participant portal). It could not work on any build since the jakarta migration.
  - Its settings are no longer offered, but existing values are still read.
  - `/oauth/*` is gone. It also exposed every `/pages` controller under a second prefix that the `/pages` filters did not cover.
- **Uploads and logs:**
  - An import with no target directory no longer writes into the shared temp directory.
  - A form-context handle and two configured paths no longer reach the logs.
  - Nine more log lines that echoed request values are removed.
- **Exports and the rule engine:**
  - Null names and missing values no longer fail the ODM export, the dataset extract, SPSS export or the CRF upload.
  - Rules: an ordinal of `END` or `ALL` fails only its own rule; `eq` compares numbers exactly; a rule set is looked up only within the current study.
  - The legacy import stops at a failed check, and checks every repeat key before it writes anything.
- **Code quality:**
  - The CodeQL quality alerts in core and web are triaged: the real ones fixed, dead code deleted, the rest dismissed with a reason.
  - 73 TODO markers are down to 12.
  - VS Code's Java problems drop from 646 to 23.

---

## Migrations

Sixteen new changelog files. All are additive, apart from the one-time lockout default:

| Changelog | What it does |
|---|---|
| `lc-muw-2026-09-30-audit-log-event-date-index.xml` | index for the paged audit logs |
| `lc-muw-2026-09-30-login-history-index.xml` | index for the login history |
| `lc-muw-2026-09-30-audit-type-system-setting.xml` | audit type 145 |
| `lc-muw-2026-09-30-audit-types-crf-lifecycle-cascade.xml`, `…-audit-types-event-crf-migration.xml`, `…-audit-type-crf-field-updated.xml` | audit types 142–144, 190–191 |
| `lc-muw-2026-09-30-audit-type-event-crf-removed.xml` | audit type 155 |
| `lc-muw-2026-09-30-event-crf-cascade-removal.xml` | records what an SPA removal took, so a restore returns exactly that |
| `lc-muw-2026-09-30-dde-second-pass-date.xml` | the DDE second-pass date that SDV reads |
| `lc-muw-2026-09-30-dn-stats-unanswered-threads.xml` | `view_dn_stats` counts threads nobody has answered (with a rollback) |
| `lc-muw-2026-09-30-export-schedule-enabled.xml`, `…-export-completion-mail.xml` | pause and completion mail for export schedules |
| `lc-muw-2026-10-06-account-lockout.xml` | lockout on, 5 attempts, only where the seeded "off" is still set |
| `lc-muw-2026-10-07-audit-types-deidentification.xml` | audit types 192–194 |
| `lc-muw-2026-10-07-ingest-origin-study.xml` | `ingest_item.origin_study_id` (nullable) |
| `lc-muw-2026-10-07-retinal-task-sdretinanet.xml` | allows `sdretinanet` in the per-event-definition retinal task list |

On top of these, **the first start under Liquibase 4 rewrites every stored checksum from `8:` to `9:`.** That rewrite is what makes the rollback a restore.

## Configuration

Nothing has to be set for this deployment. New settings:

| Setting | Default | Purpose |
|---|---|---|
| `LIBRECLINICA_LEGACY_CLOSED_PATHS` (`libreclinica.legacy.closedPaths`) | waves W0 + W1 | override the closed legacy paths; empty reopens all |
| `LIBRECLINICA_DEPLOYMENT_INTERNET_FACING` | `false` | the internet-facing mode; the setup script sets it |
| `libreclinica.ingest.deidentification.required` | the internet-facing flag | require de-identified imaging uploads |
| `LIBRECLINICA_POSTGRES_IMAGE_TAG` | `14-alpine` | production's database image; change only as the PostgreSQL 17 runbook's last step |
| `LIBRECLINICA_NGINX_EDGE_CONF`, `…_EDGE_HTTP_CONF`, `…_REALIP_CONF` | the internal deployment's | per-deployment nginx includes |
| `POSTGRES_PASSWORD` in `/etc/libreclinica/env` | **now required** | the overlay no longer falls back to the published default |

## Upgrading the app VM

Do this outside clinic hours. The restart takes a few minutes, and everyone signs in again afterwards. Plan time for the dry run in step 2.

1. **If this host still runs 1.5.0-beta.14-muw,** also read the beta.15 notes' [upgrade steps](release-notes-1.5.0-beta.15-muw.md#upgrading-the-app-vm) 2, 4 and 5: the PDF post-processor check, the GPU push token and the log level. Step 4 below covers its setup-script step.

2. **Back up, and dry-run the migration against that backup.** Liquibase 4 makes the rollback a restore, so prove the upgrade first.
   ```sh
   sudo systemctl start libreclinica-backup-db.service   # or take a pg_dump as in the runbook, §1
   IMAGE=ghcr.io/luviku/libreclinicamuw:1.5.0-beta.16-muw sudo -E /opt/libreclinica/deploy/dry-run-migration.sh
   ```
   It boots the new image against a throwaway copy of the newest backup and lists the changesets it applied. Expect the fifteen files above, nothing else, and a healthy start. **Keep that dump.** It is the only way back: see the runbook, [§5](deploy-runbook.md#5-rollback).

3. **Re-create the legacy XSLT export jobs.** The legacy job screens close with this release. Before the upgrade, list them on the legacy jobs screen (`/ViewJob`). After the upgrade, create each as a dataset schedule on `/export`, then delete the old one on `/admin/jobs`. Legacy jobs keep running until they are deleted; they just cannot be edited or paused any more.

4. **Run the setup script — before the restart, not after.**
   ```sh
   sudo bash /opt/libreclinica/deploy/setup-ubuntu-host.sh --image-tag 1.5.0-beta.16-muw   # --dicom too, if a camera speaks DICOM here
   ```
   - It writes `POSTGRES_PASSWORD`, which the compose overlay now requires, and creates `/etc/libreclinica/nginx-sites` and the Docker network `edge`. The systemd unit now re-creates `edge` before each start.
   - Without the script the stack refuses to start, and it says so.
   - Do **not** pass `--internet-facing` on this host.

5. **Restart.**
   ```sh
   sudo systemctl restart libreclinica
   ```
   The DICOM receiver follows the same tag, unless `/etc/libreclinica/env` pins `LIBRECLINICA_DICOM_IMAGE_TAG`.

6. **Smoke test, then decide.** Run the runbook's §4. **If anything is wrong, roll back now, before anyone enters data.** A restore loses everything written after the dump.

7. **Check the acquisition PCs and older clients against the new TLS settings.**
   - On each acquisition PC (Clarus, Spectralis, Optomed bridge) run:
     ```powershell
     Invoke-WebRequest https://<host>/LibreClinica/login -UseBasicParsing
     ```
   - Windows 10/11 and Server 2016+ are fine. **Windows 7, Server 2008 R2 and Server 2012 (non-R2) cannot connect any more.**
   - Ask whether any other device or script talks to the eCRF over HTTPS.

8. **Tell the users** before the upgrade:
   - the administration screens moved to the SPA, and an old bookmark shows a `410` page that names the new place;
   - "forgot password" is gone, so they ask an administrator;
   - after 5 wrong passwords an account is locked until an administrator unlocks it.

9. **Start the bake-in clock.** Record the upgrade date in `docs/development/modernization/phase-e-retirement-log.md` as the start of the six-month bake-in for waves W0 and W1. Review `<log dir>-legacy-access.log` monthly.

10. **On the first clinic day, watch the ingest inbox and the uploader heartbeats.** Captures filed without a visit, and `rate-limited` problems in the uploader panel of *System Status*, mean the rate limit is biting. Bind those captures by hand, and report it: the fix is listed under [Still open](#still-open-at-this-release).

11. **Watch the first working day more closely than usual.** Liquibase, Hibernate, Quartz and the PDF library all moved, and every clinical write gained a check. A refusal that should not happen shows as a `403` or `409` in the SPA with a reason; note the request id and send it in.

---

## What needs a human to check after the upgrade

- **Sign-in:** with a password, and through SSO if it is in use. Then sign out from the SPA.
- **Data entry:** with each role (investigator, CRC, data-entry person, monitor read-only), on one CRF, through to mark complete. A study whose parameters require Person ID or date of birth now has them enforced.
- **SDV:** verify, change a verified value (the verification is withdrawn), then un-verify with a reason.
- **Administration:**
  - create a user, change a role, unlock an account;
  - send the test e-mail;
  - open the login history;
  - edit a CRF;
  - edit and pause an export schedule;
  - confirm that a re-created export schedule runs.
- **PDFs:** a casebook and a discrepancy-note export. The library changed, though golden-text tests pin both.
- **Legacy pages** that stay open (`/ViewStudy`, `/ViewCRF`, data entry, notes): their buttons still work. A closed path answers `410` to a non-administrator and redirects an administrator to `/legacy/`.
- **A DutyPlan certificate,** once it is installed: `https://einteilung.augen.meduniwien.ac.at` answers, and the eCRF is unaffected.
- **Tri-state items** answered "Nein" or "Unbekannt" before this release: is the reason empty? (#362)

## Still open at this release

- **The upload rate limit and the uploaders.** On a `429`, the Export Watcher and the Optomed Bridge file captures unbound. The fix needs one or more of these:
  - a larger lookup budget for the internal deployment, or the limit only in the internet-facing mode;
  - the uploaders postponing instead of filing unbound;
  - the Bridge batching its lookups.
- **CVE-2026-47884 in `spring-webmvc` 6.2.19** (critical, in `XsltView`). The app configures no XSLT view, only the JSP resolver, so it is not reachable. The only fixed release is Spring 7.0.9, which needs Spring Boot 4, and open-source support for Spring 6.2 has ended. Until it is recorded as accepted, the Trivy step of the Security scan fails.
- **Administrative correction of signed data.** Legacy allowed it and withdrew the signature. Both UIs now refuse it. Decide whether that correction path is needed.
- **SPA annotations stored as "New"** before #390 are not migrated. Migrating them would be an audited data change.
- **The retinal sidecar's audit insert** names a column `audit_log_event` does not have, so its rows land in its fallback table `retinal_inference_audit`.
- **SD-RetinaNet on the cluster.** Three things before a study enables it:
  - Copy `retinanet-spectralis_main.sif` to `$RI_HOME/ri/`, and its models `model0..4.pt2` to `$RI_HOME/ri/sdretinanet_aot_models/`. The image's own copy of the models cannot be read by other users.
  - Validate the output on a volume the SWITCHER study also segmented, file by file and against its ETDRS numbers. The output formatter gives an overlapped pixel to the most probable main lesion class, and the step that produced the SWITCHER files may not have done that ([runners/sdretinanet/README.md](../../retinal-inference/runners/sdretinanet/README.md)).
  - Check whether the image runs on Ampere nodes. Until then the global `gpu:nv2080ti:1` request keeps it on the 2080 Ti nodes.
- **PostgreSQL 14 reaches end of life on 2026-11-12.** Plan the move with [postgresql-17-upgrade.md](postgresql-17-upgrade.md).
- **The internet-facing deployment's go-live gates** are open questions for MUW IT, the DPO and the clinical lead: [multicenter-internet-readiness.md](multicenter-internet-readiness.md).
