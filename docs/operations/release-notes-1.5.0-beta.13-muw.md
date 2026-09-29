# LibreClinica MUW · 1.5.0-beta.13-muw release notes

_Successor to **1.5.0-beta.12-muw**. A release for the running pilots: Remidio captures made into a visit's pre-created exam reach the inbox again, the platform recognises the same picture arriving under another patient label, an acquisition PC may delete what it has uploaded, and the deploy scripts clean up after themselves._

For older releases see [release-notes-1.5.0-beta.12-muw.md](release-notes-1.5.0-beta.12-muw.md) and its predecessors.

**Deployment-breaking:** none. One migration file, no new configuration key. **Upgrade soon:** Remidio exams affected by the defect below are picked up automatically only while their date lies within the last 14 days. See [Upgrading](#upgrading-the-app-vm).

---

## Highlights

### Remidio captures into a visit's exam reach the inbox again (#349)

The Remidio pull recorded an exam as done the first time it listed it, and skipped it on every later poll. An exam is listed before it holds images. The patient sync creates one per scheduled visit, days before the photographer shoots into it, and the phone uploads a sitting image by image. So every capture made the intended way, by picking the visit's exam on the phone, was skipped, and nothing was logged. A second eye uploaded a poll after the first was lost the same way. Found on production on 2026-09-28, when a HealthAEye baseline capture did not arrive.

- The pull now looks at an exam again whenever its listing offers more images with a download link than it had handled. Images already in the inbox are recognised by their Remidio id before any download, so only the new ones are fetched.
- An entry the cloud lists before its file is uploaded no longer closes the exam.
- **Exams closed too early reopen by themselves** on the first pass after the upgrade, if their date lies within the pull's look-back, 14 days by default. No manual step is needed for them. For an older one, see [step 4](#upgrading-the-app-vm).
- The pull's log line gains a `reopened=` count, and a pass that reopened an exam logs at INFO, for example `Remidio pull …: exams=13 new=0 reopened=1 images=2 bound=2 …`.

### The same picture under another label is held, not filed (#348, DR-036)

A re-export from the Clarus or HEYEX, or a photo re-saved from a phone, has different bytes from the first copy but shows the same picture. If a different patient id was typed at the device, the second copy used to be filed under that patient. The platform now digests what a file shows rather than its bytes: the image data of a JPEG or PNG, the decoded pixels of a DICOM file, and the image data of each OCT volume in a Spectralis `.e2e`. Headers, comments and patient records do not enter the digest.

- **Same picture, same label:** refused like a byte-identical file, pointing at the file already there. A DICOM camera still receives success, so it does not retry.
- **Same picture, another label**, or a twin that was dismissed: the file lands in the inbox **unfiled**, whatever visit the upload page, the worklist or the Remidio exam named. The label it arrived with stays as its hint, no analysis starts for an OCT volume, and audit type 141 records the hold.
- In the inbox, each such card says where the other file is: filed under which subject, waiting, or dismissed. Filing it anyway asks for confirmation, and the bind records which file it matched. The upload page shows the row as *zurückgehalten*.
- Every ingress asks this before it writes a row: both upload page routes, the OCT route, the legacy image page, the DICOM receiver and the Remidio pull.
- **Files already stored are digested by a background job**, starting two minutes after the app starts and handling up to 2000 files an hour. DICOM files wait for the DICOM receiver, which computes their digest, so it must run the same release.
- Not recognised: a picture cropped, rotated or re-compressed by hand, and a DICOM file a device transcoded into different pixels.

### An acquisition PC may delete what it has uploaded (#347)

Every export the Export Watcher uploaded stayed in `_uploaded\` for good. On the Clarus PC that is three files per capture, and the `.dcm` carries the patient's name in its header until the platform pseudonymises its copy.

- New setting in the tray's Settings dialog: *Delete uploaded exports from _uploaded*, with the number of days to keep them after the upload. **Off by default, 30 days.**
- With it on, once an hour, the watcher deletes the `.dcm` and `.e2e` files at the top of `_uploaded\` that were uploaded longer ago than that. `_skipped\`, `_failed\`, subfolders and other files are never touched.
- The watcher's version is `2026-09-28.1`.

### The deploy scripts clean up and speak up (#346)

Two defects found during the beta.12 upgrade.

- **The migration dry run left a copy of the production database behind** every time, as an unused Docker volume. It now removes the volumes with its containers. Copies from earlier dry runs are still on the VM; [step 5](#upgrading-the-app-vm) lists them.
- **The one-time copy off the anonymous volumes skipped in silence** when the app was not running. It now reports every outcome: it confirms a bind that is already in place, and warns when it cannot copy.

---

## Migrations

**One** new Liquibase file, guarded and reversible. Back up the database before deploying.

- `lc-muw-2026-12-09-ingest-item-pixel-fingerprint.xml`: one nullable column on `ingest_item`, a partial index on it, and audit event type 141, *ingest_duplicate_held*. No existing row is rewritten; the background job fills the column.

---

## Configuration

**No new keys.** The DICOM receiver's new read-only digest route uses the existing DICOM token.

---

## Upgrading the app VM

Do this outside clinic hours: the restart takes the app down for a few minutes. Upgrade before a Remidio exam affected by the defect above is more than 14 days old.

1. **Back up** the database and the file stores. `deploy/dry-run-migration.sh` rehearses the migration against last night's dump, and since this release it leaves nothing behind.
2. **Run the setup script, then restart**, as for beta.12:
   ```sh
   sudo bash /opt/libreclinica/deploy/setup-ubuntu-host.sh --image-tag 1.5.0-beta.13-muw   # --dicom too, if a camera speaks DICOM here
   sudo systemctl restart libreclinica
   ```
   The script now confirms that the data directory and the logs are already bound. The DICOM receiver follows the same tag unless `/etc/libreclinica/env` pins `LIBRECLINICA_DICOM_IMAGE_TAG`; if it does, move that pin to this release too.
3. **Watch the first Remidio pass**, about two minutes after the start. *Remidio pull: client built for the configured site* shows the pull is running. A `Remidio pull …` line follows only when a pass found something; `reopened=` above zero means exams closed too early were picked up. Their images appear in the inbox or, for an exam the patient sync created, directly on its visit. No such line means no exam was affected.
   ```sh
   cd /opt/libreclinica && sudo docker compose -f compose.yaml -f deploy/compose.production.yaml logs --since 15m libreclinica | grep -i 'remidio pull'
   ```
4. **Only if an affected exam is older than 14 days:** raise `core.remidio.pull.overlapDays` in `datainfo.properties` to cover it, restart, wait for one pass, then set it back and restart again.
5. **Remove the database copies left by earlier dry runs.** They sit among the unused Docker volumes, each holding a Postgres data directory with a copy of production data. List the unused volumes, check that none holds files still needed, then remove them with `docker volume prune`:
   ```sh
   for v in $(sudo docker volume ls -qf dangling=true); do
     p=$(sudo docker volume inspect -f '{{.Mountpoint}}' "$v")
     echo "== $v $(sudo du -sh "$p" | cut -f1)"; sudo ls "$p" | head -5
   done
   ```
6. **Acquisition PCs, when convenient.** Copy the new `deploy/export-watcher/` over the folder the watcher runs from on each Clarus and Spectralis PC, and restart it from its tray menu or by logging off and on. The existing settings file is kept; without the two new settings the watcher deletes nothing. The System Status page shows each PC's watcher version, which should read `2026-09-28.1`. The Optomed Bridge is unchanged.

---

## Still open at this release

- **A capture into a visit's exam dated in the future arrives on that date.** The patient sync dates an exam on the visit's scheduled day, and the pull lists only up to today. A patient photographed before the scheduled day appears when the day comes, not before.
- **The public rate limit never applies in the deployed app**, and `/resolve` takes any number of labels per request (DR-033). Switching it on changes what the Export Watcher and the Optomed Bridge see under load, so it needs its own decision.
- **Older audit rows keep some gaps**, as described in the beta.12 notes.
- The Clarus Raw Data objects in `_skipped\`, two of the three files per capture and most of the space, are never deleted by the watcher; whether they may be is a separate decision.
- The upload programs' tray parts have run only as far as the Windows test, parse and self-test; the new Settings fields were not run on Windows.
- The HealthAEye CRF item identifiers and the coded value for "yes" are still seeded with the platform's best guess.
- The today's-visits list on the unauthenticated upload page ships **off**, pending data-protection sign-off, and so does the Optomed worklist endpoint, which is the same list.
- The SAS output needs acceptance by the study statistician; the nAMD thresholds ship behaviour-preserving pending the clinical lead.
- The device scripts are unsigned PowerShell.
- `check-i18n` reports 124 identical de/en strings; `pnpm lint` cannot run (ESLint 9, no flat config). Both pre-existing, neither treated as blocking.
