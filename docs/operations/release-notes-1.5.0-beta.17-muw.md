# LibreClinica MUW · 1.5.0-beta.17-muw release notes

_Successor to **1.5.0-beta.16-muw**. A platform release: the application moves from Spring Boot 3.5 to **Spring Boot 4.1** (Spring 7, Spring Security 7, Hibernate 7, Jackson 3) and the image from Tomcat 10.1 to **Tomcat 11**, because both were out of open-source support or about to be (DR-037). It is meant to change nothing a user sees; the few behaviours that do change are listed below. The same release repairs the production defects that the live verification of the new stack turned up, lets the retinal pipeline analyse OCT volumes delivered as DICOM, starts an analysis on a single scan, and makes the nightly clean-up of dismissed scans complete its work._

For older releases see [release-notes-1.5.0-beta.16-muw.md](release-notes-1.5.0-beta.16-muw.md) and its predecessors.

**Deployment-breaking:** no key to set, no setup-script change. Three things to know before the window:

1. **The image base changes** (`tomcat:11.0-jdk25-temurin`), so the first start pulls a new base and everyone signs in again.
2. **Two new changelog files run on the first start.** Both are additive (nullable columns and a marker value), so they are quick.
3. **Rollback is the previous image tag.** Whether the database also needs a restore is spelled out in [Rolling back](#rolling-back).

Work through [Upgrading the app VM](#upgrading-the-app-vm) in order, then through [What needs a human to check](#what-needs-a-human-to-check-after-the-upgrade).

---

## Highlights

### The platform moves to Spring Boot 4.1 and Tomcat 11 (#413, DR-037)

| Component | beta.16 | beta.17 |
|---|---|---|
| Spring Boot | 3.5.16 | 4.1.1 |
| Spring Framework | 6.2.19 | 7.0.9 |
| Spring Security | 6.5.11 | 7.1.1 |
| Hibernate ORM | 6.6 | 7.4 |
| Jackson | 2.x | 3.1 (`tools.jackson`) |
| Servlet container (the Docker base image) | `tomcat:10.1-jdk25-temurin` | `tomcat:11.0-jdk25-temurin` (Jakarta EE 11, Servlet 6.1) |
| Java | 25 | 25 (unchanged) |
| Liquibase | 4.31.1 | 4.31.1 (kept pinned: Boot 4.1 would otherwise pull 5.x, which is under the FSL licence) |
| logback | 1.5.34 | 1.5.34 (kept pinned) |

Jackson 2 stays on the classpath, but only through `logstash-logback-encoder` and swagger-core.

What this means for operators:

- **The base image changes.** The WAR, the `/usr/local/tomcat/...` paths and the health check are the same as in the previous Dockerfile. A custom `server.xml`, valve or connector tuning mounted into the container is yours to re-check. TLS is terminated by the shared nginx, outside this image, and does not change.
- **The JSON the SPA exchanges with the server is unchanged.** The Jackson 3 mapper is configured to read and write what Jackson 2 did. Contract tests on the production converter and golden files recorded on Jackson 2 pin it, as does every JSON document the application stores (fill map, dataset filters, retinal payloads, export manifest). The generated OpenAPI document is byte-identical.
- **Administrator job times are ISO-8601 text.** `previousFireTime`, `nextFireTime` and `finalFireTime` in `GET /pages/api/v1/admin/jobs` were epoch milliseconds on the wire; they are now UTC strings such as `2026-10-07T10:15:30Z`, which is what the SPA's jobs screen has always expected. A script that reads these three fields must parse text. This is the one deliberate wire change.
- **An unreadable request body gets one stable error message.** A body that cannot be parsed, or a missing body where one is required, used to return the parser's own wording (which changes with the library). It now returns the same 400 with a fixed message in the usual `message` / `errors` shape. Monitoring that matches the old wording must be updated.
- **A path the framework cannot parse answers 400, not 500.** `/LibreClinica/%70ages/...` (a percent-encoded letter in the servlet-path prefix) produced a 500 under Spring 7's path parsing. A filter ahead of the security chain now answers a bare 400. No allow or deny decision changes: such a request was never served. Expect a few 400s, not 500s, from scanners in the access log.
- **`Accept-Language: *` no longer answers 500 on Tomcat 11** (3dcebf665). A client that sends the wildcard is served normally.
- **Access decisions are unchanged.** The security path matchers were rewritten for Security 7; a test compares the old and new allow/deny decision for every public path and deny list across about 3,400 URL shapes per run.
- **Hibernate 7 removed `save` and `saveOrUpdate`.** All call sites were reviewed (see [spring-boot-4-hibernate-7-call-sites.md](../development/modernization/spring-boot-4-hibernate-7-call-sites.md)). The review found one real mapping defect, now fixed: the audit rows of rule sets and rule set rules cascaded into the existing rule set, which Hibernate 7 would have turned into a failure when saving an audit row for an existing rule set.

### Production defects found on the running stack, fixed (#412)

These reproduced on the previous platform as well. They were found by running the real stack during the Spring Boot 4 verification.

- **Queued CSV and ODM exports work again.** The scheduled worker had no locale bound and failed with a `NullPointerException` on every run. The export now runs with the requester's locale. (`ResourceBundleProvider` also stops sharing an unsynchronised map between threads.)
- **Three SPA actions answered an error and now work.** The `pages` dispatcher has no `String` or `Resource` message converter; the endpoints were written as if it did.
  - **CRF version upload** (`POST /pages/api/v1/crfs/{oid}/versions`): version name, description and revision notes are read as form fields. It answered 415 before.
  - **Study metadata XML download** (`GET /pages/api/v1/studies/{oid}/metadata`): written as UTF-8 bytes. It answered 500 before; the document itself is unchanged.
  - **Download of a file attached to a CRF item** (`GET /pages/api/v1/eventCrfs/{id}/items/{itemOid}/file`): sent as bytes. It answered 500 before.
- **The discrepancy-note page no longer breaks after a restart.** `ViewDiscrepancyNote`, `CreateDiscrepancyNote` and `CreateStudy` read their message bundles when first created, so the first request after a start answered 500 and later ones 404 until the next restart.
- **The API error handler is active.** `ApiExceptionHandler` never applied to the SPA's API dispatcher. SPA API errors now carry the handler's JSON (messages sanitised, status codes kept). The SPA reads only `message`.
- **Line endings are pinned.** `.gitattributes` fixes LF for scripts and container files; CRLF checkouts had broken `docker-entrypoint.sh` and the cluster cron scripts.

### Retinal analysis: DICOM OCT volumes, one scan at a time (#410, #409, #408)

- **An OCT volume delivered as DICOM is analysed like an `.e2e`** (#410, DR-039). A DICOM object of the Ophthalmic Tomography class with more than one frame is recognised as an OCT volume, filed, and sent through the same preprocessing and analysis path.
  - **The device is checked.** Every task declares the devices it was validated for (today, all tasks: Heidelberg Spectralis). A volume from another device is refused before the analysis, with the reason on the job page. The job page also shows the source format, the device and how the pixel spacing was read.
  - **Spacing comes from the file or the file is refused** (422 `spacing_ambiguous`). It is never guessed.
  - **An analysis is started only on a modality marked as OCT.** The marker is set for every modality that accepts `.e2e`, so existing plans keep working. A fundus camera (Clarus, Optomed) offers no tasks, and an administrator can set the marker in the modalities admin.
  - **On arrival.** A DICOM OCT volume that arrives already filed to a visit starts the plan's analyses at once, as an `.e2e` does.
  - **Undo of an upload** no longer refuses an upload that has jobs: nothing run yet means the upload and its jobs are removed; a running job means the scan is dismissed (restorable) and the job finishes unattached.
  - **Two defects fixed on the way:** reused preprocessing lost its geometry, so metrics fell back to pixel units; and in a two-volume `.e2e` the first volume could be analysed and shown with the second volume's data.
  - **Validation before clinical use.** `deploy/compare-oct-jobs.sh` compares an `.e2e`-derived and a DICOM-derived job of the same scan. DR-039 requires this check before DICOM analyses are relied on clinically (see [Still open](#still-open-at-this-release)).
- **Start an analysis on one scan** (#409). Each scan card on the visit page lists its analyses and offers "Auswertung starten" for the missing tasks. The checks run in this order: role, task, visibility, filed to a visit, OCT volume, record not closed, scan file present, study switch on, analysis not already there. Blinded roles may start an analysis but are not taken to a page they cannot open.
- **One stable address for a job** (#409). `/subjects/<label>/jobs/<jobId>` replaces the per-subject running number, which shifted when an earlier scan was removed from its visit. The job page shows a trail (subject, visit, date) and a switcher between the same scan's analyses. Old `/retinal-jobs/<id>` bookmarks forward to the new address. In the subject's job list the entry is now `#<jobId>`.
- **A missing scan file is said plainly** (#408). Re-running a job whose `.e2e` is gone (uploads before 2026-09-24 lived in an anonymous volume that every restart emptied) is refused with 409 `SCAN_FILE_MISSING` and the message "The original scan file is no longer on the server, so this scan cannot be analysed again. Upload the scan again and assign it to the visit." No job is created.
- **A multi-frame OCT volume previews as its middle B-scan** (#407). A Spectralis export uploaded as DICOM previewed as a thin striped strip. Thumbnails stored before this release stay as they are; new uploads and files received after the upgrade are correct.

### The retention sweep removes a dismissed scan's whole chain (#411)

- **No more stranded rows.** The nightly sweep deleted a dismissed scan's file and then failed on its row (jobs still referenced it), leaving the row, jobs, results and artifacts behind and retrying every night. It now removes the whole chain in one transaction and deletes the files only after the commit. Rows that were already stranded clear on the first run.
- **A shared file is never deleted under a live sibling.** A two-volume `.e2e` is one file with two scan rows; dismissing one volume deleted the file even when the other volume was filed to a visit. It no longer does.
- **Dismissing a scan cancels its jobs that have not run** (queued, remote pending, parked). Restoring still works.
- A job stuck in a running state after a worker crash keeps its scan; the sweep logs it nightly.
- On the production instance, checked 2026-10-09 (per #411): no dismissed scan was stuck, and none of the 4 scans with a missing file belongs to the shared-file case (they are the pre-2026-09-24 uploads).

### Smaller items

- **The migration dry run no longer reports a false FAIL** (#406). The script treated any log line matching `liquibase.*(exception|failed)` as a failure, and Liquibase 4 logs each checksum rewrite with the changeset's file name, one of which contains "failed". Only real Liquibase errors count now. The VM's copy of `deploy/dry-run-migration.sh` gets this fix with this release; until then, use the copy from the checked-out release for step 1 below.
- **Dependabot** groups `tools.jackson*` with the other Jackson updates.
- **Documentation:** the retirement log records that waves W0 and W1 reached production on 2026-10-08.

---

## Migrations

Two new changelog files, four changesets. Both files are additive; nothing is dropped, and the only data changes are two marker backfills.

| Changelog | What it does |
|---|---|
| `lc-muw-2026-10-09-dicom-oct-volume.xml` | adds nullable `oct_volume`, `manufacturer`, `manufacturer_model` to `ingest_item` and nullable `source_format`, `device_manufacturer`, `device_model`, `spacing_order` to `retinal_inference_job`; backfills `oct_volume = FALSE` on DICOM rows that are not Ophthalmic Tomography. A DICOM row of that class stays `NULL` (frame count never recorded) and is not analysed until it is classified. Rollback statements are included. |
| `lc-muw-2026-10-09-imaging-modality-oct-marker.xml` | appends `,oct` to `kinds_accepted` of every imaging modality that accepts `e2e`, so existing plans and their tasks stay valid. Rollback statement included. |

The platform move itself adds no migration, and Liquibase stays at 4.31.1 (the `8:` to `9:` checksum rewrite already happened with beta.16).

---

## Configuration

No key is new or changed in `application.yml`, `datainfo.properties`, the compose files or `/etc/libreclinica/env` (checked against the diff since 1.5.0-beta.16-muw). `deploy/compare-oct-jobs.sh` reads `DB_USER`, `DB_NAME`, `BSCAN_STORE` and `COMPOSE_FILES` from its own environment, with defaults; it is a validation helper, not part of the service. The Docker base image changes from `tomcat:10.1-jdk25-temurin` to `tomcat:11.0-jdk25-temurin`.

---

## Upgrading the app VM

Do this outside clinic hours. The restart takes the app down for a few minutes and everyone signs in again afterwards.

1. **Back up, and dry-run the migration against that backup.**
   ```sh
   sudo systemctl start libreclinica-backup-db.service   # or take a pg_dump as in the runbook, §1
   IMAGE=ghcr.io/luviku/libreclinicamuw:1.5.0-beta.17-muw sudo -E /opt/libreclinica/deploy/dry-run-migration.sh
   ```
   It boots the new image against a throwaway copy of the newest backup and lists the changesets it applied. Expect the two files above (four changesets), nothing else, and a healthy start. Note the **current image tag**: it is the rollback. **Keep the dump** until the smoke test has passed.

2. **Run the host setup script, then pull and restart:**
   ```sh
   sudo bash /opt/libreclinica/deploy/setup-ubuntu-host.sh --image-tag 1.5.0-beta.17-muw   # --dicom too, if a camera speaks DICOM here
   sudo systemctl restart libreclinica
   ```
   The first start pulls the Tomcat 11 based image, so it takes longer than usual. The DICOM receiver follows the same tag, unless `/etc/libreclinica/env` pins `LIBRECLINICA_DICOM_IMAGE_TAG`.

3. **Update the retinal-inference code on the GPU host** if DICOM OCT analysis is to be used. The preprocessing sidecar and the device gating changed in this release (see [retinal-inference-remote-deployment.md](../development/modernization/retinal-inference-remote-deployment.md)). `.e2e` analyses are not affected.

4. **Expect everyone to sign in again.** Sessions do not survive the restart.

5. **Smoke test, then decide.** Run the runbook's §4 and the checks below. **If anything is wrong, roll back now.**

6. **Check the access log for the first day.** The Jackson 3 and Spring 7 changes touch every API call. A rise in 500s is the signal to roll back.

7. **If a script reads the administrator jobs API,** change it to parse the three time fields as text.

### Rolling back

Stop the service and start the previous image tag (set `--image-tag` back to the beta.16 value, re-run the setup script, restart). **The platform move rewrites no stored data**, so it never needs a restore.

The two new changesets have run once the new image has started. **An older image ignores them:** the added columns are nullable and beta.16 does not read them, and the `oct` marker in `kinds_accepted` is a value beta.16 does not interpret. So the beta.16 image should run on the migrated database without a restore, as between any two beta releases. Two cautions:

- Anything written after the upgrade (DICOM OCT scans and jobs, `oct_volume` classifications) stays in the database; beta.16 will not analyse a DICOM volume.
- The Liquibase checksums are not touched by this release, so the standing rule holds: never start a Liquibase 3.x image on this database (see [§5 of the runbook](deploy-runbook.md#5-rollback)). Restoring the pre-upgrade dump is only needed to undo data changes, not for the older image to start.

---

## What needs a human to check after the upgrade

No automated test drives a browser against the production instance. Each of these is a page or a flow a person has to open. (The release was verified on a local live stack with a Playwright smoke of 28 checks, per #413; that is not the production instance.)

- **Sign in and open each workspace** (investigator, monitor, data manager, administrator, imaging): the SPA, the legacy JSP pages that remain, and a PDF and Excel export. These sit on Spring 7, Security 7 and the new Tomcat. If you mount a custom `server.xml`, valve or connector setting, check that it still takes effect on Tomcat 11.
- **Queue a CSV and an ODM export** from the export page and confirm the file arrives.
- **Upload a CRF version** with a name, description, revision notes and a spreadsheet; it must create the version.
- **Download a study's metadata** (XML) and **a file attached to a CRF item** uploaded before the upgrade.
- **Open a discrepancy note, add one, and open it again after the first request following a restart:** no 500, then 404.
- **The administrator's job list** shows next and previous fire times as readable dates.
- **Retinal analysis on a real scan:** start one analysis from a filed scan's card, follow it to the job page, check the trail and the switcher. Re-run a job whose scan file no longer exists and read the message. If DICOM OCT is in use: upload a Spectralis volume as DICOM and check the preview, the device on the job page, and that a fundus DICOM offers no tasks.
- **Accept-Language:** request the login page with the header `Accept-Language: *` (for example `curl -s -o /dev/null -w '%{http_code}\n' -H 'Accept-Language: *' https://<host>/LibreClinica/login`) and expect a normal response, not 500.
- **Edit a rule set, then remove a rule set rule**, and look at the rule audit: the audit rows are written.
- **A session lasting longer than the idle time-out**, then the first request: the usual redirect to sign-in, no error page.
- **The dry run** ended with the expected two files and no FAIL.

---

## Still open at this release

- **CI results.** CI run [37991475342](https://github.com/LuViKu/LibreClinicaMUW/actions/runs/37991475342) (attempt 2, on `3dcebf665`) passed all seven jobs: Maven build and unit tests, integration tests on PostgreSQL 14 and 17, the three Python sidecar suites, and the compose smoke test. The counts reported on #413 before its merge were core 538 and web 1,451 unit tests, 4,242 in the web integration profile, 1,999 SPA tests, and 104 / 9 / 282 for the sidecars.
- **Docker Hub rate limit in CI.** Base-image pulls in CI can hit Docker Hub's rate limit and fail a job; the run above is attempt 2.
- **SSO and `/me`.** After a header-only SSO sign-in, `GET /pages/api/v1/me` answered 401. This was investigated and not fixed.
- **DICOM OCT is not validated for clinical use.** `deploy/compare-oct-jobs.sh` still has to be run on a real `.e2e` and DICOM pair of the same scan (DR-039). Validation of de-identification on real samples is also pending.
- **SLURM account.** The cluster account for the retinal GPU jobs is pending.
- **Carried over from beta.16 and still open:**
  - the public portals cannot be throttled until the uploaders postpone a refused capture instead of filing it unbound;
  - administrative correction of signed data is refused in both UIs; decide whether that path is needed;
  - SPA annotations stored as "New" before #390 are not migrated;
  - the retinal sidecar's audit insert names a column `audit_log_event` does not have, so its rows land in `retinal_inference_audit`;
  - SD-RetinaNet on the cluster needs its image and models copied into place, validation against a volume the SWITCHER study also segmented, and the right GPU type (see the [beta.16 notes](release-notes-1.5.0-beta.16-muw.md#still-open-at-this-release));
  - PostgreSQL 14 reaches end of life on 2026-11-12; plan the move with [postgresql-17-upgrade.md](postgresql-17-upgrade.md);
  - the internet-facing deployment's go-live gates are open: [multicenter-internet-readiness.md](multicenter-internet-readiness.md).
- **Closed by this release:** CVE-2026-47884 in `spring-webmvc` 6.2.19 was listed as open in beta.16; the fixed release is Spring 7.0.9, which this release ships. Confirm with the next Trivy run.
- **Jackson 2 stays on the classpath** through `logstash-logback-encoder` 7.4 and swagger-core, until those release Jackson 3 versions. **Logback is pinned below 1.5.37** and **Liquibase at 4.31.1**, as before.
