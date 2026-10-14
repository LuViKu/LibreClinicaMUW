# LibreClinica MUW · 1.5.0-beta.19-muw release notes

_Successor to **1.5.0-beta.18-muw**. A feature release: **multimodal Spectralis exports** (DR-041). A Heidelberg `.E2E` export holds a whole session for both eyes: OCT volumes and line scans, IR, BAF, BR, IRAF, qAF, ICGA and FA. It is now uploaded once, through the Export Watcher or the browser, and every series becomes its own DICOM item in the inbox. The browser upload shows the series before the upload and lets you leave some out. The release also fixes three things seen on the VM after beta.18: a site user's empty visit list, new accounts silently bound to the admin's study, and unclear study and site labels. The app VM's own host name changes to `vtc-internal`._

For older releases see [release-notes-1.5.0-beta.18-muw.md](release-notes-1.5.0-beta.18-muw.md) and its predecessors.

**Deployment-breaking:** nothing has to be set. Four things to know before the window:

1. **Two new changelog files (seven changesets) run on the first start.**
   - They add five nullable columns: four on `ingest_item`, one on `imaging_modality`.
   - They add audit type 209.
   - They replace the partial unique index on `(sha256, scan_index)` with one that ignores split rows, and add the unique index for split rows.
2. **Upgrade the retinal-inference image together with the app** (same tag, as always). The app now asks it to list and convert the series of an E2E (`/e2e/describe`, `/e2e/convert`). An older image answers 404 there, and a split upload then fails.
3. **Run the host setup script.** It creates two new directories, `e2e-convert` and `e2e-source`, mounts them, and adds `e2e-source` to the backup list.
4. **The nginx configuration changes.** Upload locations take up to 2 GiB, are no longer buffered in nginx, and get long timeouts. The server blocks answer for `vtc-internal.augen.meduniwien.ac.at` instead of `vrc-lin-tasks.augen.meduniwien.ac.at` (#423), so the old name stops working. Requests through `ecrf.augen.meduniwien.ac.at` are unaffected.

Work through [Upgrading the app VM](#upgrading-the-app-vm) in order, then through [What needs a human to check](#what-needs-a-human-to-check-after-the-upgrade).

---

## Highlights

### Multimodal Spectralis exports: one upload, one DICOM item per series (#430, DR-041)

**The problem.** For the drusen (iAMD) study, each patient is exported from the Spectralis as `.E2E` files of 10–640 MB. Until now an E2E counted only as OCT:

- **the clients sent the same file once per OCT volume;**
- **every other series was dropped;**
- **the app refused anything over 200 MiB.**

**What happens now:**

- **One upload per file, up to 2 GiB.** The Export Watcher and the browser stream it. Duplicates are recognised by the file's SHA-256, also before the upload, so a file that is already here is not sent again.
- **The server lists the series** from the file's own per-series metadata: device modality, scan pattern, eye and acquisition time. It does this through the retinal-inference sidecar.
- **Each series becomes a DICOM item in the inbox,** converted in one pass:
  - OCT volumes and line scans as 16-bit, with the same pixel values the retinal analysis received until now;
  - en-face images and time sequences (IR, BAF, BR, IRAF, qAF, ICGA, FA) as 8-bit Ophthalmic Photography, multi-frame where a series holds several images.

  The only patient data in these DICOMs is the study label.
- **Retinal analysis runs for OCT volumes only,** through the existing DICOM OCT path (DR-039).
- **The original `.E2E`** moves to `e2e-source`, read-only. Nothing serves or analyses it there, and it is deleted after **90 days** (`core.retinalInference.e2eSourceRetentionDays`, audit type 209). The items stay. A second upload of the same export is refused even after its source copy is gone.

**The subject comes from the study label in the file.**

- **The label is the header's surname slot.** That is where the study label (`EIAMDxxx`) sits in these exports, so several patients' files can share one watch folder.
- **A patient folder must agree.** If the file sits in a patient folder whose name differs from the label, every series is put in the inbox for a person to decide.
- **The other two name slots are never used.** The first-name and patient-ID slots can hold a clinic number, so they are never read as the subject, sent, stored or logged.
- **An unmatched label is not kept.** A label that matches no subject parks the file, and the label is stored nowhere.

**Browser upload** (*Eingang → Hochladen*):

- **Selecting files.** A folder picker and folder drag-and-drop.
- **Before the upload,** each file is read in the browser from its chunk directory; a 600 MB export needs about 2 MB of reads. The row then shows:
  - the label from the file, and whether it agrees with the folder;
  - the series per eye, each with a thumbnail, modality, frame count, date, time and visit suggestion.
- **Leaving series out.** You can untick series, with all/none per eye and a toggle per modality, and unticked series are not uploaded. The server checks each left-out series against its own list, so a mismatch cannot drop the wrong one.
- **After the upload,** the row lists the items created and the series left out.
- **The de-identification mode of internet-facing deployments** keeps its previous per-volume behaviour.

**Until the drusen study is configured,** its series wait in the inbox. Each series reaches its modality through the new catalogue token `imaging_modality.e2e_series` once the study has entries for OCT, IR, BAF, BR, IRAF, qAF, ICGA and FA.

### Export Watcher 2026-10-13.1

- **Each `.E2E` is uploaded once, streamed.** The watcher no longer reads it into memory: a 449 MB upload peaked at 124 MB on Windows PowerShell 5.1.
- **The server splits the file.** The watcher sends the subject folder's name, where the file sits in one; otherwise the server takes the label from the file.
- **A file over 2 GiB** is set aside in `_failed\` without being sent, and the heartbeat reports `file-too-large` (shown on System Status).
- **DICOM handling (Clarus) is unchanged.**
- **Optional on PCs that send DICOM only;** needed on the PC that exports from the Spectralis for the drusen study.

### A site user sees the study's visits (#431)

- **The bug.** A physician working at a site created a patient in HealthAEye and saw no visits in the "create visit" list, until they signed out and in again. The patient page asked the site for the study design, which the design endpoints refuse for a site.
- **The fix.** The visit dialog, the camera-worklist strip and the group editor now read the design from the parent study. The list shows a load error instead of staying empty. It is reloaded when the active study changes.
- **`GET /api/v1/me`** gains `activeStudy.parentOid`.

### Study and site scope of user accounts (#432)

- **No default study for a new account.** The invite dialog has a required *Studie / Prüfzentrum* field with nothing preselected. `POST /users` answers 400 without `studyOid`/`studyId`. An admin no longer needs an active study to create an account.
- **No overlapping roles.** A site role is refused (409) for a user who already has a study-wide role in that study, and the reverse. Changing an existing role still works. Existing overlaps are left as they are.
- **Clearer labels.** Patient enrolments and role cards read "HealthAEye › Wien". The user list's *Prüfzentrum* column shows every scope, e.g. "HealthAEye (studienweit)".

### The VM's host name is vtc-internal (#423)

- **What changes.** The app VM is now `vtc-internal.augen.meduniwien.ac.at`. The nginx server blocks answer for `ecrf.augen.meduniwien.ac.at` and `vtc-internal…`, no longer for `vrc-lin-tasks…`.
- **Certificates.** A reissued certificate must cover `ecrf` and `vtc-internal`. An existing one that still names `vrc-lin-tasks` keeps working for `ecrf`.

---

## Smaller items

- **The split preflight** ("is this export already here?") answers only for items the caller may see, like the per-volume preflight. A duplicate from another location is still refused when it is committed.
- **A latent OCT geometry error is fixed.** The previous E2E path applied the first volume's B-scan geometry to every OCT volume in a file, so slice spacing of the others was off by about 5%. Each volume now uses its own geometry. This applies to OCT volumes uploaded from now on.
- **Code scanning.** The 11 alerts the E2E work raised are fixed: output paths are confined to their directory, the stored-name check uses no regular expression, empty `except` blocks log, and an assert has no side effect. CodeQL reports no open alerts on `lc-develop`.
- **`GET /api/v1/ingest/upload/preflight` and `…/commit` with `split=true`** are typed operations in the OpenAPI spec: `preflightSplitStaffUpload`, `commitSplitStaffUpload`, and the public `…SplitUpload` pair.

---

## Upgrading the app VM

Do this outside clinic hours. The restart takes the app down for a few minutes, and everyone signs in again afterwards.

0. **The DNS change (#423) must be in place.** `vtc-internal.augen.meduniwien.ac.at` must resolve to the VM, and `ecrf` must point at it. If IT has not made the change yet, wait: after this upgrade nginx no longer answers for the old name.

1. **Back up, and dry-run the migration against that backup.**
   ```sh
   sudo systemctl start libreclinica-backup-db.service   # or take a pg_dump as in the runbook, §1
   IMAGE=ghcr.io/luviku/libreclinicamuw:1.5.0-beta.19-muw sudo -E /opt/libreclinica/deploy/dry-run-migration.sh
   ```
   Expect the two files above (seven changesets), nothing else, and a healthy start. Note the **current image tag**: it is the rollback. **Keep the dump** until the smoke test has passed.

2. **Run the host setup script, then pull and restart:**
   ```sh
   sudo bash /opt/libreclinica/deploy/setup-ubuntu-host.sh --image-tag 1.5.0-beta.19-muw   # --dicom too, if a camera speaks DICOM here
   sudo systemctl restart libreclinica
   ```
   The script:
   - creates `/var/lib/libreclinica/e2e-convert` (0755) and `/var/lib/libreclinica/e2e-source` (0700);
   - mounts them;
   - adds `e2e-source` to the backup list;
   - moves the deploy checkout to the tag, which brings the new nginx configuration (`deploy/nginx/ecrf.conf`, mounted into the nginx container).

   The app and the retinal-inference sidecar must run the same tag.

3. **Expect everyone to sign in again.** Sessions do not survive the restart.

4. **Smoke test, then decide.** Run the runbook's §4 and the checks below. **If anything is wrong, roll back now.**

5. **On the Spectralis export PC, when the drusen uploads start:** install Export Watcher 2026-10-13.1. Other PCs can stay on 2026-10-12.2.

### Rolling back

Stop the service and start the previous image tag (set `--image-tag` back to the beta.18 value, re-run the setup script, restart). The new columns are nullable, and beta.18 ignores them. Things to know:

- **Split uploads made on beta.19 stay as DICOM items.** Beta.18 shows them as ordinary DICOM files in the inbox. Their source copies stay in `e2e-source` and are not deleted until beta.19 runs again.
- **The replaced unique index** behaves the same for every row beta.18 writes.
- **The nginx configuration** of beta.18 answers for the old host name again. Re-run the setup script with the beta.18 tag.
- **Restoring the pre-upgrade dump** is needed only to undo data changes. The usual Liquibase rule holds: never start a 3.x image on this database ([§5 of the runbook](deploy-runbook.md#5-rollback)).

---

## What needs a human to check after the upgrade

- **Host names:**
  - `https://ecrf.augen.meduniwien.ac.at` works;
  - `https://vtc-internal.augen.meduniwien.ac.at` reaches the same app;
  - the certificate covers the name you use.
- **A site physician** creating a patient in HealthAEye sees the study's visits in "create visit" at once, without signing in again (#431).
- **Creating an account** asks for a study or Prüfzentrum and preselects none. Granting a site role to someone with a study-wide role in that study is refused with a reason (#432).
- **A test E2E upload in the browser** (*Eingang → Hochladen*, a test patient):
  - the series appear with thumbnails before the upload;
  - leave one series out;
  - after the upload, the inbox shows one item per remaining series, each with a DICOM preview.
- **The same file again** is reported as already uploaded.
- **On the VM after the test upload:**
  - `/var/lib/libreclinica/e2e-source` holds the `.E2E`;
  - `/var/lib/libreclinica/e2e-convert` is empty.
- **Analysis.** In a study with OCT analysis, a bound OCT volume from a split upload starts its analysis job.
- **The dry run** ended with the expected two files and no FAIL.

---

## Still open at this release

- **CI results:** to be filled in from the CI run on the release commit.
- **The drusen study is not configured yet.** Its visits, a catalogue entry per series type with its `e2e_series` token, and the CRF items for "performed" are study-team work. Until then its series wait in the inbox.
- **OCT contrast.** `oct-converter` log-compresses OCT intensities, which is not Heyex's display curve, so OCT images look flatter than in Heyex. Analysis keeps 16-bit precision. Whether the models expect another intensity scaling needs a comparison with a Heyex DICOM export of the same scan. See [heidelberg-e2e-format-notes.md](../development/heidelberg-e2e-format-notes.md).
- **Development setup only.** The DICOM sidecar needs the same token in `DICOM_SCP_INGEST_TOKEN` and `core.dicom.ingest.token`. The shipped development `datainfo.properties` leaves it blank. Production is not affected: with `--dicom`, the setup script pairs the two.
- **Carried over from beta.18 and still open:** see the [beta.18 notes](release-notes-1.5.0-beta.18-muw.md#still-open-at-this-release).
