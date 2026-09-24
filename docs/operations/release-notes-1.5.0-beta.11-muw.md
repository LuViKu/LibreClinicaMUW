# LibreClinica MUW · 1.5.0-beta.11-muw release notes

_Successor to **1.5.0-beta.10-muw**. What beta.10's first day in production turned up, and what running the device side needs: the System Status page now watches the upload programs on the acquisition PCs and the disks, the Export Watcher runs on Windows at all, a visit shows its images and says which ones it still expects, inference follows the scan wherever it is filed, the reconciliation inbox gets its way back, and AI blinding becomes a per-study setting._

For older releases see [release-notes-1.5.0-beta.10-muw.md](release-notes-1.5.0-beta.10-muw.md) and its predecessors.

**Deployment-breaking:** none. Three migration files, one new configuration key that ships on. The Export Watcher and the Optomed Bridge must be updated on their PCs: the beta.10 watcher does not start on Windows. See [Upgrading](#upgrading-the-app-vm).

---

## Highlights

### The System Status page sees the uploaders and the storage (DR-033)

Three programs on two Windows PCs carry HealthAEye images to the platform, and nothing on the platform showed whether they run. They now report.

- **A heartbeat every two minutes**, switched on or off: running, uploading on, files waiting and how long the oldest waited, files given up on, free space on the export drive, uploads today, and coded problems from the last round. For the Optomed Bridge also whether the Optomed Client runs and whether the Client took the last worklist. Counts, ages and codes only; nothing about a patient.
- **System → Systemstatus** shows each program as *In Ordnung*, *Braucht Aufmerksamkeit* (with the problem in words), *Ausgeschaltet*, *Beendet* (closed from its menu), *Abgemeldet oder heruntergefahren* (Windows ended the session: an evening, not an outage) or *Keine Meldung* (silent for three reporting intervals). Beside it, what actually arrived per device and way in over 90 days, from every ingress. A healthy program with no arrivals usually means the export goes into another folder.
- **Storage** is measured once an hour and on demand: every file store's size and file count, the free space on each disk, and the database with its largest tables, kept 90 days. The page shows the week's change and, where free space shrank, roughly how many days remain.
- The heartbeat endpoint is unauthenticated like the upload front door; each program's row is keyed by a random id it keeps in its settings file. Bodies are capped at 16 KB and at most 50 programs are followed. `core.uploaderHealth.heartbeat.enabled=false` turns it off.

### The Export Watcher runs on Windows

The first Windows run of beta.10's Export Watcher, with a real Clarus export, ended in twelve parse errors. The script held em-dashes in a UTF-8 file without a byte-order mark; Windows PowerShell 5.1 reads such a file as cp1252, where the last byte of an em-dash is a smart quote, and PowerShell accepts a smart quote as the end of a string. It had only ever run on PowerShell 7, whose default is UTF-8. All four device scripts are ASCII now, checked the way 5.1 reads them. Both tray apps also hide their own console window when started by hand.

### A visit shows its images; AI blinding is per study

- **Bilder dieser Visite** on the visit page: every image filed against the visit, grouped by eye, with thumbnails and full size in a new tab. Whoever may open the visit may see them, a Monitor included. "CRFs öffnen" on the subject page is now "Visite öffnen".
- **`ai.blinding.enabled`**, a per-study setting, default on, so nothing changes by itself. HealthAEye is a screening study with no treatment decision to protect; with blinding off there, the treating roles see the AI metrics. The server's masking follows the setting without further change, and the fail-closed default stays.
- The study settings panel showed raw keys instead of labels; each switch now has a label and one sentence on what it does.

### A visit definition says which images it expects (DR-034)

- **Expected imaging**, per visit definition: which catalogue modalities are required or optional there, for which eye, and which retinal inference tasks run on a file of that modality at that visit. A posterior and an anterior OCT in one study no longer share one task list.
- **Signing checks it:** a required modality without a filed image, for the eye it names, blocks signing the way an unfinished CRF does. Cancelled, unscheduled and skipped visits are left out.
- **The visit page** lists expected against present, with a banner when a required image is missing.
- **`inference.enabled` is real:** with the study setting off, the OCT upload files the scan and starts no job.
- Existing per-visit task lists are carried over only for a study with exactly one catalogue modality that accepts `.e2e` files. **HealthAEye has four** (OCT, and the IR, red-free and blue-autofluorescence rows that also arrive as `.e2e`), so its lists are not carried over. Its OCT tasks keep running from the old per-visit list, but its visit definitions show an empty *Expected imaging* table until someone fills it.

### Inference follows the scan (DR-035)

- **Filing a scan starts its analysis.** A scan filed from the inbox (parked at upload, or moved after a wrong-visit correction) used to get no inference at all. Now, on filing, every existing analysis of the file is attached to the visit (finished results are re-pointed, not recomputed), a cancelled one the visit's plan wants is revived, and a planned task that never ran is started, exactly as the OCT upload does.
- **Removing a scan from a visit takes its results with it.** Its analyses are detached from the visit, and those that have not started are cancelled (a new status, *cancelled*, that no worker picks up). One already on the GPU finishes and keeps its result, detached.
- **A changed imaging plan catches up on request.** The visit-definition editor shows how many scans and analyses a plan change would start, and starts them on a click; saving alone never starts work.
- Filing through the image inbox, the DICOM receiver or the Remidio pull attaches existing analyses but starts none, and the study's inference switch applies throughout.

### The reconciliation inbox gets its way back

- **Wiederherstellen**: a dismissed file returns to the inbox during the retention window, with the audit trail recording both steps.
- **Auswahl verwerfen**: dismiss a selection at once; what was refused is reported.
- **Aus Visite entfernen** on the visit page asks which of two things is meant: the wrong visit (back to the inbox, where the right one can be picked) or not study data (unbind and dismiss in one step, with a reason).
- **A signed or locked visit is sealed:** a file can no longer be pulled off it, and its checklist tick no longer retracted, until the visit is un-signed or unlocked.

### Found on the beta.10 deploy and its first day

- **The setup script unpinned the image tag and skipped its own new sections.** A re-run rewrote `LIBRECLINICA_IMAGE_TAG` to `latest`, so the next restart would have rolled whatever release came out, unannounced. And because the script replaces itself during the run, the first re-run after a release executed the old text against the new tree. Now the tag changes only with `--image-tag`, and the script re-runs its updated copy when it changed.
- **The JDBC transcript wrote about 4 GB a day.** Four log4jdbc categories inherited INFO and logged every statement to the console and the JSON log, and the log files had no size cap. They now log warnings only, and both log files are capped (100 MB per file, 14 days, 2 GB and 1 GB in total). The audit trail is unaffected; it lives in the database.
- **A wrong Remidio site id failed forever, one subject at a time.** The patient sync now checks the id once per settings change, logs one error naming the ids the account can write to, and stays off until the key is fixed. Repeated failures are summarised once per pass.
- **A cancelled visit showed as planned** and still offered *Cancel*. It now shows as cancelled with no actions; stopped and skipped visits no longer read as never planned; and a cancelled visit no longer blocks signing the subject.

---

## Migrations

**Three** new Liquibase files, all guarded and reversible. Back up the database and the file stores before deploying.

- `lc-muw-2026-12-05-uploader-health-storage.xml` — `uploader_instance` (the last heartbeat per upload program) and `storage_usage_sample` (the hourly measurements, 90 days).
- `lc-muw-2026-12-06-visit-imaging-plan.xml` — `event_definition_imaging` (the expected imaging per visit definition), and the carry-over of existing per-visit task lists where a study has exactly one `.e2e` modality.
- `lc-muw-2026-12-07-retinal-jobs-follow-file.xml` — no table change: the new job status is documented on its column, and two audit event types record attaching and detaching a file's analyses.

---

## Configuration

**One new key**, appended by the setup script: `core.uploaderHealth.heartbeat.enabled=true`. Set it to `false` only to switch the heartbeat endpoint off.

Two per-study settings are new or now enforced, under **Studienaufbau → Plattform-Einstellungen**: `ai.blinding.enabled` (new, default on) and `inference.enabled` (existing, now honoured by the OCT upload).

---

## Upgrading the app VM

1. **Back up** the database and the file stores; `deploy/dry-run-migration.sh` rehearses the three migration files against last night's dump.
2. **Pin the image and restart.** The setup script pins the tag and appends new keys but **does not restart the stack**; the restart is what pulls the new images and runs the migrations:
   ```sh
   sudo bash /opt/libreclinica/deploy/setup-ubuntu-host.sh --image-tag 1.5.0-beta.11-muw   # --dicom too, if a camera speaks DICOM here
   sudo systemctl restart libreclinica
   ```
   Expect one *re-running the new copy* line on this first run of the script; the tag is not touched otherwise.
3. **After the restart**, glance at `docker logs`: the JDBC transcript is gone, and a malformed log policy would show here first.
4. **Update the device scripts** on every acquisition PC: the Export Watcher on the Clarus and Spectralis PCs (the beta.10 copy does not start on Windows), the Optomed Bridge on its PC. Each appears on **System → Systemstatus** within two minutes; `ExportWatcher.ps1 -Heartbeat` and `OptomedBridge.ps1 -Heartbeat` check it by hand.
5. **HealthAEye:** to let the treating physicians see the AI output, set *KI-Verblindung für Behandler* to *Aus* in its Plattform-Einstellungen. To give its visits per-modality inference, fill *Expected imaging* on its visit definitions; until then its OCT tasks run from the old list.

---

## Still open at this release

- **The public rate limit never applies in the deployed app.** The filter compares its paths without the `/LibreClinica` context path against request paths that carry it; on a development stack 36 lookups in a row all answered. `/resolve` also takes any number of labels per request. Switching the limit on changes what the Export Watcher and the Optomed Bridge see under load, so it needs its own decision (DR-033).
- **CRF attachments and dataset exports live in an anonymous Docker volume**, outside `/var/lib/libreclinica` and every backup of it, and a `docker compose down` followed by `up` orphans it. Moving it to a bind mount is a deployment change still to make (DR-033).
- The upload programs' tray parts (timers, Exit, the session-end report) have run only as far as #336's Windows test (parse and self-test); the heartbeat itself was verified from PowerShell 7 against a development stack.
- The HealthAEye CRF item identifiers and the coded value for "yes" are still seeded with the platform's best guess.
- The today's-visits list on the unauthenticated upload page ships **off**, pending data-protection sign-off, and so does the Optomed worklist endpoint, which is the same list.
- The SAS output needs acceptance by the study statistician; the nAMD thresholds ship behaviour-preserving pending the clinical lead.
- The device scripts are unsigned PowerShell; a killed Optomed Bridge leaves the Client minimised until the bridge runs again.
- `check-i18n` reports 124 identical de/en strings; `pnpm lint` cannot run (ESLint 9, no flat config). Both pre-existing, neither treated as blocking.
