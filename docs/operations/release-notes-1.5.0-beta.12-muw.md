# LibreClinica MUW · 1.5.0-beta.12-muw release notes

_Successor to **1.5.0-beta.11-muw**. A small release for the running pilots: the app's data directory and Tomcat logs survive a restart, the audit trail says what each event was and what it concerned, and "today" is the clinic's day rather than the server's._

For older releases see [release-notes-1.5.0-beta.11-muw.md](release-notes-1.5.0-beta.11-muw.md) and its predecessors.

**Deployment-breaking:** none. One migration file, and one new configuration key that ships set to Vienna. This upgrade must run the setup script **before** the restart: that run moves the app's files off the anonymous Docker volume they have lived on. See [Upgrading](#upgrading-the-app-vm).

---

## Highlights

### The app's data directory and logs survive a restart (#342)

The app image declares its data directory, `/usr/local/tomcat/libreclinica.data`, and its Tomcat logs as Docker volumes. The production stack mounted nothing there, so Docker gave each an anonymous volume. The systemd unit stops the stack with `docker compose down` and starts it with `up -d`, which creates the app container afresh, with new, empty anonymous volumes. So every restart, whether an upgrade or an unplanned reboot for patching, left CRF attachments, dataset exports and the logs behind in an orphaned volume the app no longer saw. That volume sat outside `/var/lib/libreclinica` and every backup of it.

- Both are now bound to host directories next to the other file stores: `/var/lib/libreclinica/app-data` and `/var/lib/libreclinica/tomcat-logs`. The setup script creates them.
- On the upgrade, the setup script copies what the running container holds into the new directories once, before the restart, so the restarted app finds its current files. See [step 4](#upgrading-the-app-vm) for files from before the last restart.

### The audit trail says what happened, and to what (#344)

The audit log showed many events under the wrong label and dropped context it had stored.

- **Labels.** Many labels given to the older event types in June did not match what the database triggers record under those types. A visit's start date moving read *Study event reset*, a completed visit *Study event location updated*, a skipped visit *Study event started*, and a subject moving to another site *Discrepancy note added*. Eighteen types carry the right label again. The recorded rows were always correct and are not changed; only the displayed names are.
- **Four actions get types of their own.** Reopening a CRF, restoring a CRF, recording a reason for change and restoring a dismissed file had been written under types that already meant something else. They now have their own types, and the reason for change is recorded with the row. Rows written before are shown as what they record.
- **Context.** Visit rows name the subject and the visit. Failures name the operation that failed and the error. Rows with a single value, such as a deleted value or an error, show it; the views used to show values only when a row had two. Status changes show names such as *Available* and *Removed* instead of numbers.
- **Which file.** Every audit row about an ingested file now records which file it was: its number, device, kind, eye, arrival time and the start of its checksum, never the file name, which can carry a patient's name. This outlives the retention sweep that deletes a dismissed file. A dismissal also records its reason, and filing a file to a visit or taking it off records the visit.
- **Each study's log shows its own rows.** Rows recording a checklist tick caused by a filed image could appear in an unrelated study's log, naming a subject of the study they belonged to, and were missing from their own; they are now placed by the CRF they record, which also corrects rows already written. Filing a file to a visit, taking it off, and the analysis jobs following it now appear in that visit's study log, not only in the system log. Dismissing or restoring a file that was never filed stays in the system log, also when a subject had been suggested for it: such a file is not study data.

### "Today" is the clinic's day (#343)

The server runs in UTC, and seventeen places took today's date from that clock. Between midnight and 01:00 in winter, or 02:00 in summer, Vienna time, each of them still said yesterday: the DICOM and Optomed camera worklists, the upload page's visits of the day, the subject's camera-worklist strip, the due-visits list and its overdue flag, the "not in the future" checks on enrolment and eye-cohort transitions, the year-of-birth limit, and the date on subject exports.

- Every calendar-date decision now uses the clinic's zone, `core.clinicZone`, Vienna by default. The Remidio pull uses the same setting.
- The server clock and every stored timestamp stay in UTC, so nothing stored is read differently.
- **System → Anwendungskonfiguration** shows the clinic zone beside the server's zone, and labels which governs what, so a UTC server zone there reads as intended.

---

## Migrations

**One** new Liquibase file, guarded and reversible. Back up the database before deploying.

- `lc-muw-2026-12-08-audit-labels-and-context.xml`: corrects the display names of eighteen audit event types and adds four (137 to 140). No data table changes and no audit row is touched.

---

## Configuration

**One new key**, appended by the setup script: `core.clinicZone=Europe/Vienna`. A blank or invalid value also means Vienna. Change it only for a deployment in another time zone.

---

## Upgrading the app VM

Do this outside clinic hours: the restart takes the app down for a few minutes.

1. **Back up** the database and the file stores. `deploy/dry-run-migration.sh` rehearses the migration against last night's dump.
2. **Run the setup script, then restart.** This run matters more than usual. It re-runs its updated copy (one *re-running the new copy* line), copies the app's data directory and logs out of the running container into the new host directories (one *Copied … the restart binds it* line each), and appends `core.clinicZone`. Only then restart:
   ```sh
   sudo bash /opt/libreclinica/deploy/setup-ubuntu-host.sh --image-tag 1.5.0-beta.12-muw   # --dicom too, if a camera speaks DICOM here
   sudo systemctl restart libreclinica
   ```
   Restart only after the script. A restart before it is one more restart of the old setup: the app comes back on fresh, empty volumes, the script then has nothing to move, and the files stay in the previous volume, where step 4 finds them.
3. **Check the binds took.** Both lines must say `bind`:
   ```sh
   sudo docker inspect -f '{{range .Mounts}}{{.Type}} {{.Destination}}{{"\n"}}{{end}}' libreclinica-muw-libreclinica-1 \
     | grep -E 'libreclinica.data|/logs'
   ```
4. **Look through the older volumes before pruning them.** The script moves only the volume the running container uses. CRF attachments (under `attached_files`) or dataset exports written before an earlier restart are still in older anonymous volumes. List what each holds:
   ```sh
   for v in $(sudo docker volume ls -qf dangling=true); do
     p=$(sudo docker volume inspect -f '{{.Mountpoint}}' "$v")
     echo "== $v $(sudo du -sh "$p" | cut -f1)"; sudo ls "$p"
   done
   ```
   Copy anything still needed into `/var/lib/libreclinica/app-data`, owned by `libreclinica`. Only then remove the rest with `docker volume prune`.
5. Nothing changes on the acquisition PCs in this release.

---

## Still open at this release

- **The public rate limit never applies in the deployed app.** The filter compares its paths without the `/LibreClinica` context path against request paths that carry it, and `/resolve` takes any number of labels per request. Switching the limit on changes what the Export Watcher and the Optomed Bridge see under load, so it needs its own decision (DR-033).
- **Older audit rows keep some gaps.** A dismissal from before this release names its file only while the file is still in the inbox, and never its reason, which was not recorded in the row. Files filed to or taken off a visit by hand before this release appear only in the system log, because those rows did not record the visit.
- The upload programs' tray parts (timers, Exit, the session-end report) have run only as far as #336's Windows test, parse and self-test.
- The HealthAEye CRF item identifiers and the coded value for "yes" are still seeded with the platform's best guess.
- The today's-visits list on the unauthenticated upload page ships **off**, pending data-protection sign-off, and so does the Optomed worklist endpoint, which is the same list.
- The SAS output needs acceptance by the study statistician; the nAMD thresholds ship behaviour-preserving pending the clinical lead.
- The device scripts are unsigned PowerShell; a killed Optomed Bridge leaves the Client minimised until the bridge runs again.
- `check-i18n` reports 124 identical de/en strings; `pnpm lint` cannot run (ESLint 9, no flat config). Both pre-existing, neither treated as blocking.
