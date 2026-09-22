# LibreClinica MUW · 1.5.0-beta.9-muw release notes

_Successor to **1.5.0-beta.8-muw**. A short window with a clear cause: the September outage of the GPU cluster, which stopped every OCT job for nineteen days without the application saying so, and the first real reconciliation of a HealthAEye scan, which turned out to verify nothing. Two corrections and three pieces of operability follow from those two days._

For older releases see [release-notes-1.5.0-beta.8-muw.md](release-notes-1.5.0-beta.8-muw.md) and its predecessors.

**Deployment-breaking:** none. One changeset, two optional keys, and one nginx change that only matters once you point the retinal pipeline at it. See [Upgrading](#upgrading-the-app-vm).

---

## Highlights

### The acquisition date is the file's, not the operator's

Binding an OCT scan to a HealthAEye baseline looked verified and was not. Three separate things were wrong, and each is fixed at its own level.

- **The date read out of the `.e2e` file was discarded.** The write that persisted it was keyed on a column that has never existed, failed on every call since June, and hit a soft-fail. Nobody noticed because the screen falls back to the visit date when the column is empty, so it looked populated. The date is now written against the job's own file path, and onto the ingest item as well, where reconciliation reads it.
- **One column held two different claims.** For a DICOM file the acquisition date came off the header; for an `.e2e` or a photograph it was whatever the upload page had in its date box — which is the key the visit list is searched by, so it equalled the chosen visit's date by construction and read like corroboration. The date now travels with its provenance: **file**, **operator**, or **unknown** for rows that predate the distinction. The inbox marks a non-file date as unverified.
- **Nothing compared the two dates.** A scan from any day could be filed against any visit in silence; one production job is a 2023 scan on a 2026 visit. Binding now compares a file-sourced date against the visit's date, and a mismatch comes back as a question carrying both dates. The operator can file it anyway — a device with a wrong clock or a visit recorded on the wrong day is a judgement a person may make — and the acknowledgement and both dates go into the audit row. Only a file-sourced date is ever compared: checking an operator-typed date against the visit would pass on every upload and catch nothing.

### The inference cluster, visible from inside the application

During the outage the only symptom was jobs failing with "Remote /run returned null", and the diagnosis took an afternoon of SSH. The sysadmin **Systemstatus** page now carries a **Retinal-Inference-Cluster** panel: one row per node with its state (healthy / degraded / unhealthy / unreachable), the tasks it registered against the expected six, host and GPU, and latency; below it, the tail of the app-VM cron monitor's log.

Two choices that are easy to get wrong: it probes the **real nodes** (`core.retinalInference.clusterNodes`), not the failover address, because behind nginx the failover address answers as long as *either* node is up and would read green with the primary dead; and a hung node is a **row, not an error** — probes run in parallel with a short timeout, so one dead node costs one timeout, not a page that never loads. The sidecar's `/health` now reports which node and which card it is actually on.

### The cluster survives a dead node, and starts on a usable GPU

- **Failover.** One configured node was one outage away from every OCT job failing, which is exactly what happened. The app VM's nginx, which already fronts the eCRF, gains an internal listener on port 8088 with a primary and a backup node; `core.retinalInference.remotePushUrl` points at it. No Java change: a failed dispatch already requeues, and nginx does not retry the POST, so the same multi-minute GPU job never starts twice. Literal IPs, because nginx resolves upstream names once at startup and this nginx is the clinical application's front door.
- **The launcher degrades instead of dying.** A missing module path for two of the six tasks used to abort the whole server — which is why every cron restart from 3 September died and the four unrelated tasks stayed down for nineteen days. It now starts without those two and warns loudly; the reduced task list is itself the alarm the monitor already mails about. The derivation also works under cron now, where no login shell had exported `MODULEPATH`.
- **GPU selection.** The launcher hardcoded device 0. On a shared node that is a coin flip; on the primary node device 0 was more than half occupied by another tenant and one model died with CUDA out-of-memory while three cards sat idle. It picks the emptiest card at startup.
- **An architecture constraint, documented.** Two of the models ship CUDA kernels that do not run on Ampere GPUs; a node with those cards answers `/health` with all six tasks and then fails two per job, which is worse than an outage because the monitor sees a healthy server. Pick nodes by architecture, not speed — see `retinal-inference/docs/cluster-deployment.md` §1a.

### Backups are pruned again, and the dry run finds them

- Nothing had been pruned since logrotate first ran. A stanza installed "as a safety net on the backup directory" renamed each dump to `.sql.gz.1`, the prune matched only `.sql.gz`, and every dump back to June was still on disk, 100 days into a 30-day window. The backup directory is no longer managed by logrotate; the prune glob clears the renamed backlog on its next run; an existing stanza is replaced on the next run of the setup script.
- `deploy/dry-run-migration.sh` could never find a nightly backup: it looked for the runbook's example name rather than the name the backup job writes, and would have fed a compressed dump straight to `psql`. It now finds the newest dump under either convention and streams it through `zcat`, deliberately never writing a plain-text copy of the trial database to disk.

### Finding your way around: the System section

Five instance-level pages — Systemstatus, System-Audit-Protokoll, Passwort-Richtlinie, Anwendungskonfiguration, Geplante Jobs — were linked from nowhere after the beta.8 navigation rework; the manual named their addresses and that was the only way in. They are now the **System** section: one Administrator-only entry in the top bar, kept to the right of the study chip because everything left of the chip is study-scoped and these pages are not, and a side rail on all five pages with the current one highlighted. The cluster panel above is on the first of them.

---

## Migrations

**One** new Liquibase file, two changesets, both guarded and reversible. Back up the database and the file stores before deploying, as always.

- `lc-muw-2026-12-03-ingest-acquisition-date-source.xml` — adds `ingest_item.acquisition_date_source` (`file` / `operator` / `unknown`), then backfills every existing dated row to `unknown`. Marking them unknown rather than guessing keeps them out of the bind check and out of any screen that claims a date is verified; rows with no date stay null on both columns.

`deploy/dry-run-migration.sh` now works against a real nightly backup (see above), so this is a good release to rehearse it on.

---

## Configuration

Two new keys, both optional. As with beta.8, the bind-mounted `datainfo.properties` replaces the bundled copy rather than overlaying it, so re-run the setup script after rolling the image to have them appended:

```sh
sudo bash /opt/libreclinica/deploy/setup-ubuntu-host.sh
sudo systemctl restart libreclinica
```

| Key | Default | Set it when |
|---|---|---|
| `core.retinalInference.clusterNodes` | blank (probe `remotePushUrl` alone) | You run the nginx failover; list the real nodes as `name=baseUrl,…` so the panel sees each one |
| `core.retinalInference.clusterMonitorLog` | `/var/lib/libreclinica/retinal-cluster-monitor.log` | The cron monitor writes elsewhere; blank hides the alerts panel |

---

## Upgrading the app VM

1. **Back up** the database and the file stores. Rehearse with `deploy/dry-run-migration.sh` against last night's dump — it can find it now.
2. **Roll the image tag.** One changeset applies on boot.
3. **Re-run the setup script** (command above). Besides the two keys it removes the logrotate stanza over the backup directory; the widened prune clears the `.sql.gz.1` backlog on the next nightly run. Check `/var/backups/libreclinica` a day later.
4. **nginx.** The shipped `deploy/nginx/ecrf.conf` gained the `retinal_cluster` upstream and the 8088 listener. The file is bind-mounted from the deploy checkout, so validate it with the command in `deploy/nginx/README.md` and restart the stack. Then set `core.retinalInference.remotePushUrl=http://nginx:8088` and list the nodes in `core.retinalInference.clusterNodes`. The `remotePushUrl` takes a **base** URL: the client appends `/run` itself, and the old runbook text produced `/run/run`.
5. **Rotate the shared inference secret.** It shipped as the repository's public placeholder; set a real value on both ends.
6. **Verify** as an Administrator: **System → Systemstatus** shows every node healthy with 6 of 6 tasks and a latency. A node listed as unreachable is the panel working.

## Upgrading the cluster

The launcher script and the sidecar's `/health` changed, and both run bare-metal on the cluster nodes from a checkout, not from an image. Update that checkout on every serving node, let the cron watchdog restart the server (or run `start-cluster-server.sh` by hand), and confirm with `--check`. `--strict` is there for a caller that wants an abort rather than a degraded start. Choose nodes by GPU architecture per §1a of the cluster document; the two configured in the shipped nginx conf are known good.

---

## Still open at this release

- The HealthAEye CRF item identifiers and the coded value for "yes" are still seeded with the platform's best guess; a mismatch skips the checklist tick with a warning naming the item.
- The today's-visits list on the unauthenticated upload page ships **off**, pending data-protection sign-off.
- The SAS output needs acceptance by the study statistician.
- The nAMD thresholds ship behaviour-preserving; adopting the specification's literal figures is a clinical decision.
- The GPU selection and the cron-safe module loading run only on the cluster and have no automated test on either side; the dry run and the new panel are the check.
- `check-i18n` reports 119 keys where the German and English strings are identical, up from 116 at beta.8. The three new ones are the word "System", which is the same in both languages. The gate was already failing and is not treated as blocking.
- `pnpm lint` cannot run: the SPA is on ESLint 9, which needs a flat config file, and none exists. Nothing has been linted for as long as that has been true. Worth its own small change.

Closed since beta.8: the compose smoke test now has a DICOM leg (association, worklist C-FIND, C-STORE, auto-bind), so that item is no longer open.
