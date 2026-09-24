# LibreClinica MUW · 1.5.0-beta.10-muw release notes

_Successor to **1.5.0-beta.9-muw**. Every HealthAEye device now reaches the platform without a person re-uploading files: the Remidio FOP's captures are pulled from the vendor's cloud, with the subjects due for a visit pushed there first so the photographer can pick them, and the Clarus and Spectralis exports upload themselves from the folder they are exported to. Plus four things learned on the day beta.9 went live and one long-standing gap on the scheduling side._

For older releases see [release-notes-1.5.0-beta.9-muw.md](release-notes-1.5.0-beta.9-muw.md) and its predecessors.

**Deployment-breaking:** none. One migration file, seventeen optional Remidio keys behind two switches that ship off, one new key for the cluster monitor's log, a setup-script flag that finally does what the runbook said it did, and a new tray app for the two acquisition PCs. See [Upgrading](#upgrading-the-app-vm).

---

## Highlights

### Remidio FOP captures are pulled from the Remidio cloud (DR-031)

The Remidio FOP has no DICOM and nothing can be pushed to it; its app uploads every capture to Remidio's cloud, and until now the photographer re-uploaded each JPEG through the browser page. Remidio's read-only **gateway API** lets the app VM list that cloud for our site instead. The chain was verified against our own tenant with the token Remidio issued — 37 exams, 53 images, all with signed download paths and laterality.

- **A third ingress into the one queue.** A scheduler lists new exams every couple of minutes, downloads each image (64 MB cap, https only, no gateway headers on the signed link), sniffs it, stores it through the same artifact store as every other file, and writes an `ingest_item` with `source_kind = remidio`. An exam is marked as seen only after all its images were handled, so a failed download is retried on the next pass.
- **One-click bind, on the same rule as the upload page.** When the **MRN typed into the Remidio app is the study subject label** and that subject has exactly one visit on the exam date in a study that accepts image ingest, the image binds itself, with the system-bind audit row and the performed-checklist tick the worklist path writes. Otherwise it waits in the inbox with the label and date pre-filled. That convention — the MRN *is* the label — is the whole "worklist" for this device and belongs in the HealthAEye SOP.
- **Name, date of birth and sex are read from the API and discarded.** The acquisition date is the one the camera app stamped; it carries the provenance `device`, which the bind-time date check from beta.9 trusts like a DICOM header.
- **Catches up on its own.** Each pass lists from *(last successful pass − overlapDays)* to today; the first pass starts at `core.remidio.pull.since` in 30-day chunks. Exams already filed are skipped by id, so overlap never files twice and downtime is caught up without intervention.
- **The subjects due for a visit are pushed to Remidio first — the worklist side.** The FOP app has no patient list, only an exam list, so a subject the photographer should shoot must exist in Remidio's cloud as a patient *with an exam*. With `core.remidio.patientSync.enabled=true` each pass takes the visits scheduled from yesterday to a week ahead (the same scope as the DICOM worklist) and creates what is missing: a patient whose MRN is the subject label, with a placeholder identity (first name = label, last name = study, birth date = epoch, sex mapped to male/female), and an exam named `<label> <visit>`. A capture made into that exam binds straight to its visit. The gateway API is read-only, so this goes through the web dashboard's own API, with the same account but the dashboard's client pair and the site's numeric id. Remidio cannot delete a patient, so the sync creates only for live visits in scope and looks the MRN up before every create. Verified 2026-09-24: records created this way reached the phone, a capture into one came back through the gateway with both images.
- **The gateway's end date is exclusive.** Found the same day: an exam captured that morning was missing from a listing that ended on that date. A pass asking for "today" saw nothing from today; the client now sends the day after, and so does the probe script.
- **What differs from the only public description** of this API (an Indian hospital's integration) is on record in `docs/development/modernization/remidio-cloud-pull.md`: our backend is the Germany host, `clientName` must be `PACS_GATEWAY` (anything else is an unhandled 500 on every endpoint), `getExamsByDate` wants the site's custom identifier and never the numeric id, and `getAuthToken` invalidates the previous token — so the integration needs its own Remidio account.

### Scheduled jobs in the web context never ran

Found while running the Remidio pull locally: switched on, correctly configured, probe green on every endpoint — and nothing happened. No log line, no watermark row, an idle scheduler thread. The scheduler bean is component-scanned into the MVC child context, while `@EnableScheduling` lived only on the root context; a child does not inherit the root's bean post-processors, so the JVM held one scheduling post-processor and it never saw the child's beans. The same applies to the **nightly medication-catalogue refresh** (03:30, since August): its first-boot load runs through a different hook and worked, which is why nobody noticed the cron never fired. Scheduling is now enabled in the child context as well, on the root's existing scheduler thread. The Remidio pull was then verified end to end on a development stack against the live tenant: a 14-day first pass filed 13 exams and 21 images into the inbox, the next pass two minutes later found nothing new. Its per-pass log line now also reaches the console, where the README tells you to look; before, it went to the JSON log file only.

### Clarus and Spectralis exports upload themselves (DR-032)

Neither the Zeiss Clarus nor the Heidelberg Spectralis talks to the platform: the photographer exports to a folder on the acquisition PC and, until now, re-uploaded every file through the browser page. The **Export Watcher** (`deploy/export-watcher/`) is a tray app of the Optomed bridge's shape that watches that folder and carries each finished file through the same public upload front door the page uses, so nothing changes server-side and the item that lands is indistinguishable from a hand upload.

- **One instance per PC**, each pointed at that PC's own export folder: on the Clarus PC beside the Optomed bridge, on the Spectralis PC alone. No secret is entered anywhere.
- **Identify.** `.dcm` from the header; `.e2e` per OCT volume from the Heidelberg chunk directory, a port of the upload page's own reader. One upload per volume, as the page does.
- **One lookup per sweep**, for every file found, because the public front door budgets lookups at 30 per hour per client and a Clarus session is easily 40 files. Exactly one subject with one visit that day binds on arrival; anything else goes to the inbox with label and date as hints. An `.e2e` that does not resolve is parked, so no inference starts until somebody binds it.
- **A Clarus capture is three DICOM objects**, verified on real Clarus 700 exports: the photograph, an 8 MB Raw Data object with the vendor's sensor data, and a small Raw Data object stamped with the export time. Only the photograph is an image a visit can show; the other two go to `_skipped\` unless `UploadNonImage` is switched on. Without that, every capture would have filed three items.
- Files that were filed or already there move to `_uploaded\`; anything else is retried, and after five attempts moved to `_failed\`. Nothing is ever deleted. The log carries counts and labels only.

The same Clarus exports showed two defects in the DICOM header reader the Optomed bridge uses as well: it stopped at the first undefined-length sequence (the Clarus writes two before the patient group, so the label came back empty), and its check for undefined length never matched, because PowerShell reads the hex literal `0xFFFFFFFF` as -1. Both are fixed in the bridge too. The Lumo's files carry no such sequence, which is why the bridge never showed it.

### The DICOM sidecar comes up when you ask for it

Found on the beta.9 deploy while switching the Optomed bridge on: the first upload answered **503 — "the DICOM service is not reachable"**. Every DICOM file the platform takes in goes through the sidecar's `/describe` for pseudonymisation, and the sidecar was not running: `COMPOSE_PROFILES=dicom` in the env file did not start it, because the systemd unit names its services explicitly. `setup-ubuntu-host.sh --dicom` now does the whole job — sets the profile, mints the shared ingest token once and stamps it into `datainfo.properties` when that side is blank, **warns when the two disagree** (that disagreement is a 503 on every DICOM upload with nothing in the log to explain it), and appends `dicom-scp` to the unit's service list. Re-runs add missing env keys blank and never clobber a value.

### The Optomed worklist survives two visits on one day

Found in production an hour after the Optomed bridge went live: a subject got a second HealthAEye visit for the day, and the camera stopped receiving the worklist altogether. The file rendered one record per *visit*, so it named the subject twice, and the vendor's Client refuses a file that names one patient twice — for every patient in it. The list is now one record per subject: a second same-day visit joins the first record's name field so the photographer still sees both, the earlier start is kept, and the join is re-capped to the field limit. The bridge warns, once per file, when a dropped file is still in the folder a minute later. Two questions the beta.9 notes left open were settled on the camera itself: `O` **is accepted** as a sex value, and a **0-byte file clears the camera's list**.

Worth knowing alongside: a subject with two visits on one day cannot auto-bind on upload either (the resolver reports ambiguous), so those images go to the inbox. The design assumes one visit per subject per day; this release makes the worklist survive the exception rather than adopt it.

### The migration dry run and the cluster monitor work on a fresh host

Two deploy scripts that failed on the beta.9 deploy, fixed so the next host gets them right from the script:

- **`deploy/dry-run-migration.sh`** reported that the backup could not be restored, and guessed why. The guess was wrong: the postgres image starts its server twice, and the restore began during the first run, which was shutting down. The script now waits for the init process to finish and for two successful queries across a pause, and on failure prints psql's own last lines instead of a theory.
- **The cluster monitor** on the new System Status page read *"Monitor-Log nicht lesbar"*. Nothing had ever installed `check-retinal-cluster.sh`, it wrote no log, and the default log path lay outside every directory the app container can see. `setup-ubuntu-host.sh` now writes one cron probe every five minutes per node in `core.retinalInference.clusterNodes`, teeing into `core.retinalInference.clusterMonitorLog` under `/var/lib/libreclinica/monitor/`, which the production compose file binds read-only into the app. Cron still mails the output on a state change.

### A visit can carry a start time

The visit's start timestamp has always had a flag saying whether its time part means anything, and the old scheduler asked for a time; the SPA dialog only ever sent a date, so every visit scheduled from the SPA was date-only. Scheduling and editing now take an optional `timeStarted` (`HH:mm`), validated before any database access; the DTO returns it when meaningful and `null` for date-only visits; the audit row records the time only when it is meaningful. The Optomed worklist already emitted the visit's time, so it now shows the scheduled time instead of `00:00` for visits that have one. Clearing a time from the subject-detail form is not offered yet.

---

## Migrations

**One** new Liquibase file, five changesets, all guarded and reversible. Back up the database and the file stores before deploying.

- `lc-muw-2026-12-04-remidio-pull.xml` — `remidio_exam` (exams already processed), `ingest_item.remidio_image_id` with a partial unique index (one row per Remidio image, ever), and `remidio_pull_state` (the watermark each pass advances), and for the patient sync `remidio_patient` (subject label to cloud patient id) and `remidio_visit_exam` (visit to cloud exam id).

---

## Configuration

**Seventeen new keys under `core.remidio.*`, behind two switches that ship off.** Nothing reaches out until `core.remidio.pull.enabled=true`, and nothing is written to Remidio's cloud until `core.remidio.patientSync.enabled=true` as well. The patient sync additionally needs the dashboard's client pair (`core.remidio.dashboard.*`, the public pair every browser session sends) and the site's **numeric** id (`core.remidio.siteId`); its window is `aheadDays` (7) and `behindDays` (1). As with the last two releases, re-run the setup script after rolling the image so the keys are appended to the host's `datainfo.properties`; then set the values from Remidio: the client identification token (a JWT; its `clientName` is `PACS_GATEWAY`), a **dedicated** Remidio account for the integration, and the site's custom identifier as set in the Remidio dashboard (`muw_vienna`). `since` is where the very first pass starts; blank means one year back.

These credentials live in **`/opt/libreclinica/config/datainfo.properties`**, the file the app reads — not in `/etc/libreclinica/env`, which holds only what compose and systemd need (image tag, profile, the Postgres and DICOM secrets). The probe script `deploy/remidio/remidio-probe.sh` walks the chain by hand before you enable the pull; it reads the same values as `REMIDIO_*` variables from a local env file, `~/.remidio.env` by default, which should be `chmod 600` and is not part of the deployment.

One new key for the cluster monitor: `core.retinalInference.clusterMonitorLog`, defaulting to `/var/lib/libreclinica/monitor/retinal-cluster-monitor.log`. It must stay inside that directory, which is the one the app container can read.

Three env keys, added blank by the setup script if missing: `COMPOSE_PROFILES`, `DICOM_SCP_INGEST_TOKEN`, `LIBRECLINICA_DICOM_BIND_ADDR`. `--dicom` fills the first two.

The app VM needs outbound HTTPS to `*.appspot.com` and `storage.googleapis.com` (the signed image links, valid for one hour).

---

## Upgrading the app VM

1. **Back up** the database and the file stores; `deploy/dry-run-migration.sh` rehearses against last night's dump.
2. **Roll the image tag.** One migration file applies on boot.
3. **Re-run the setup script.** If a camera speaks DICOM to this host, run it with `--dicom`; it will tell you if the token in `datainfo.properties` and the one in the env file disagree. It also installs the cluster-monitor cron file; **remove the monitor entries added by hand to root's crontab during the beta.9 deploy**, or every node is probed twice. If `clusterMonitorLog` was pointed elsewhere by hand then, point it into `/var/lib/libreclinica/monitor/` and restart the app.
4. **Remidio, when you are ready:** run `deploy/remidio/remidio-probe.sh` from a machine with the credentials in `~/.remidio.env`; when it lists exams, put the same values under `core.remidio.*` in the app's `datainfo.properties`, set `pull.enabled=true` and `pull.since`, restart, and watch the log for one line per pass (`Remidio pull <from>..<to>: exams=… new=… bound=… unbound=…`). An unhandled 500 from every endpoint means the client name is wrong; a 404 naming the site custom id means it is not set in the dashboard.
5. **Optomed bridge:** update `OptomedBridge.ps1` on the clinic PC, for the refused-file warning and the header-reader fix; the worklist endpoint change needs nothing on that side.
6. **Export Watcher, on each acquisition PC:** copy `deploy/export-watcher/` somewhere stable, run `Install-ExportWatcher.ps1 -BaseUrl … -WatchFolder …`, check the reader on a real export with `ExportWatcher.ps1 -SelfTest -File …`, then enable it from the tray and point the device's export at the folder. The deploy README has the details.
7. **Remidio patient sync, when the pull runs:** set the `core.remidio.dashboard.*` pair and `core.remidio.siteId`, then `core.remidio.patientSync.enabled=true`. Tell the photographers to shoot into the exam named after the subject; a capture into that exam binds to the visit directly.

---

## Still open at this release

- The HealthAEye CRF item identifiers and the coded value for "yes" are still seeded with the platform's best guess.
- The today's-visits list on the unauthenticated upload page ships **off**, pending data-protection sign-off — and so does the Optomed worklist endpoint, which is the same list.
- The SAS output needs acceptance by the study statistician; the nAMD thresholds ship behaviour-preserving pending the clinical lead.
- The Remidio pull and the patient sync have run from the app against the live tenant only on a development stack; the first pass on the VM happens with the switch on. A status-page tile for the last successful pass, a `remidio` badge in the inbox, and the SOP line about the MRN are listed in DR-031 as follow-ups. DR-031 itself still sits in `docs/development/modernization/remidio-cloud-pull.md` and is not yet folded into the decision record.
- The patient sync creates Remidio patients that cannot be deleted from the cloud. It creates only for live visits in scope, but a visit scheduled by mistake leaves a patient behind.
- The Export Watcher's tray has not been run on Windows; its headless modes were verified from PowerShell 7 against a development stack with real Clarus and Spectralis exports. An `.e2e` date read out of the file arrives marked as typed by an operator, as it does from the page (DR-032 follow-up).
- The GPU selection and the cron-safe module loading run only on the cluster and have no automated test on either side.
- The Optomed bridge runs on the clinic PC as an unsigned PowerShell script; a killed bridge leaves the Client minimised until the bridge runs again.
- `check-i18n` reports 119 identical de/en strings; `pnpm lint` cannot run (ESLint 9, no flat config). Both pre-existing, neither treated as blocking.

Closed since beta.9: the two open questions about the Optomed camera (sex value `O`, empty file) — both verified on the device.
