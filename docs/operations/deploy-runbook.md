# Production deploy runbook

LibreClinica MUW Ophthalmology — Phase A7 (pre-launch hardening).

## Changelog

- **2026-06-10 (Phase B hardening, Stream 8 / PR #170 follow-up):** resolved
  all 6 in-doc gaps left from v1: §1 documents the existing
  `liquibase-maven-plugin` v1.9.1.0 (`pom.xml:1310`) `status` goal; §2
  adds a pre-deploy validation subsection covering `liquibase:validate`
  + the `LIQUIBASE_CONTEXTS` env var; §3 prescribes the
  `ghcr.io/luviku/libreclinicamuw:<semver>` image-tag convention and
  documents the new Dockerfile `HEALTHCHECK` + its 90 s `start-period`
  rationale; §5 documents the already-pinned compose project name
  (`compose.yaml:3`) and underscore-separated volume convention; §6
  gains a sysadmin walk-through with `reqId` correlation steps, a
  screenshots placeholder (pending Stream 6) and an on-call rotation
  placeholder.
- **2026-06-08 (Phase A7, PR #170):** initial publication.

Operational walk-through for the MUW Vienna single-site deployment
(1 sysadmin, 2 service accounts). The compose stack is the unit of
deployment; Liquibase runs in-process when the `libreclinica`
container starts. There is no separate migration step — that is the
hazard this runbook is designed around.

Scope: routine release of the WAR + the bundled Liquibase
changesets. Not in scope: first-time install
([administrator-manual.md](../manuals/administrator-manual.md)),
schema-major modernization phases (per-phase playbooks under
[`docs/development/modernization/`](../development/modernization/)),
SSO sidecar redeploys ([sso-deployment-guide.md](../development/sso-deployment-guide.md)).

Key files: [`compose.yaml`](../../compose.yaml) (the stack) and
[`config/DataSourceConfig.java`](../../core/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/config/DataSourceConfig.java)
(the `SpringLiquibase` bean — the only knob is the
`LIQUIBASE_CONTEXTS` env var; production leaves it unset, the
in-code default `!demo` skips dev-only seed changesets).

---

## 1. Pre-deploy

Run on the production host as the deploy user.

```bash
# (a) Backup. Five rolling backups in /var/backups/libreclinica.
mkdir -p /var/backups/libreclinica
pg_dump -h db.internal -U clinica libreclinica \
  > /var/backups/libreclinica/backup-$(date +%Y%m%d-%H%M).sql
ls -1t /var/backups/libreclinica/backup-*.sql | tail -n +6 | xargs -r rm

# (a2) The file stores, in the same window as the dump. Images, OCT
#      volumes and inference artifacts live on disk; the database holds
#      only their paths. A restore of the dump alone gives a database
#      full of references to files that are no longer there. Every store
#      is a directory under /var/lib/libreclinica, bound at the same path
#      in every container (deploy/compose.production.yaml).
tar -C /var/lib/libreclinica \
  -czf /var/backups/libreclinica/files-$(date +%Y%m%d-%H%M).tar.gz \
  dicom-ingest ingest e2e-uploads retinal-artifacts 2>/dev/null
ls -1t /var/backups/libreclinica/files-*.tar.gz | tail -n +6 | xargs -r rm

# (b) Drain new traffic at the reverse proxy. If you do not have a
#     drain mechanism, stop the container — the proxy will return
#     503 to clients, which is the explicit "down for maintenance"
#     signal:
docker compose stop libreclinica
```

Before stopping, confirm no operator is mid-CRF-save. The tail of
`audit_log_event` is the source of truth — if the most recent
`audit_date` is within the last 60 seconds, someone is still typing.
Wait for a quiet window:

```bash
docker compose exec db psql -U clinica libreclinica -c \
  "SELECT MAX(audit_date) FROM audit_log_event;"
```

The `db` and `smtp` services stay running. The Liquibase changelog
runs against the live database when `libreclinica` starts again, so
the database MUST be reachable through the rest of this runbook.

### Preview pending changesets (optional, recommended)

The `liquibase-maven-plugin` v1.9.1.0 is already declared at
[`pom.xml:1310`](../../pom.xml). Its `status` goal lists the
changesets that would be applied on the next `update`, against a live
DB whose credentials come from the `<config.file>` property — no
extra compose service required. Run it on the deploy host (or any
host with network reach to the production DB and the project
checkout):

```bash
docker run --rm \
  -v "$(pwd)":/app -v "$(pwd)/.m2-cache":/root/.m2 -w /app \
  maven:3-eclipse-temurin-25 \
  mvn -B -pl core liquibase:status \
      -Dconfig.file=docker/config/datainfo.properties
```

A clean release prints something like
`X changesets have not been applied to clinica@jdbc:postgresql://…`
or `clinica is up to date` — if it lists anything you did not expect
to ship, stop and reconcile against the diff.

---

## 2. Dry-run on staging clone

A staging clone is a second compose stack pointed at a freshly
restored Postgres dump. Do this on a non-production host (or at
least a second `compose -p libreclinica-staging` project on the
same host bound to a different port).

```bash
# (a) Restore the latest production backup into the staging DB.
LATEST=$(ls -1t /var/backups/libreclinica/backup-*.sql | head -1)
docker compose -p libreclinica-staging up -d db
docker compose -p libreclinica-staging exec -T db \
  psql -U clinica -d libreclinica < "$LATEST"

# (b) Pull the candidate image (or `docker compose build` if the
#     institution does not publish a `:latest` tag).
docker compose -p libreclinica-staging pull libreclinica
```

Liquibase has no first-class "list pending changesets" command in
this codebase — the `SpringLiquibase` bean always runs `update` on
boot. The supported dry-run is to bring the staging stack up with
the new image and watch the Liquibase log lines:

```bash
docker compose -p libreclinica-staging up -d libreclinica
docker compose -p libreclinica-staging logs -f libreclinica \
  | grep -iE 'liquibase|changeset|sqlexception'
```

Expected output for a clean release:

```
INFO liquibase.changelog : Reading from public.databasechangelog
INFO liquibase.changelog : ChangeSet migration/lc-muw-2026-06-08-...::muw applied
INFO liquibase.changelog : ChangeSet migration/lc-muw-2026-06-09-...::muw applied
INFO liquibase.changelog : Database is up to date, no changesets to execute
```

Read the applied changeset list against the diff being deployed
and flag the three highest-risk patterns before approving:

1. **NOT-NULL added to a populated column with no backfill
   `<update>`** — Liquibase fails mid-changeset and the WAR refuses
   to deploy. Mitigation: add-nullable → backfill → add-not-null,
   three changesets.
2. **`DROP COLUMN` on a column still read by the running WAR.**
   The old WAR (in the deploy window) throws
   `column does not exist`. Mitigation: ship a release that stops
   reading the column first, drop in the next release.
3. **Index creation on a large table.** `CREATE INDEX` without
   `CONCURRENTLY` takes an exclusive write lock. On
   `audit_log_event` or `item_data` this can stall boot for
   minutes. Mitigation: create with `CREATE INDEX CONCURRENTLY`
   manually pre-deploy and gate the Liquibase changeset with a
   `<preConditions>` index-exists check.

If any of those land unmitigated, abort the deploy.

### Pre-deploy validation (changelog parses cleanly without writing)

`SpringLiquibase` reads the `LIQUIBASE_CONTEXTS` env var (see
[`DataSourceConfig.java:82-111`](../../core/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/config/DataSourceConfig.java)).
Production deployments leave it unset — the in-code default `!demo`
skips dev-only seed changesets. Staging can override it (e.g.
`LIQUIBASE_CONTEXTS=demo` to seed demo data) by adding the env var to
`compose.yaml`.

For a read-only "does the candidate changelog even parse?" check
that does NOT write to the DB, use the plugin's `validate` goal —
it walks the changelog and reports parse errors / duplicate
changeset IDs / preCondition syntax errors without applying
anything:

```bash
docker run --rm \
  -v "$(pwd)":/app -v "$(pwd)/.m2-cache":/root/.m2 -w /app \
  maven:3-eclipse-temurin-25 \
  mvn -B -pl core liquibase:validate \
      -Dconfig.file=docker/config/datainfo.properties
```

Run this before the staging boot-log dry-run; it surfaces
authoring mistakes in seconds and avoids burning a staging restart
cycle on a typo.

---

## 3. Deploy

### Image-tag convention

| Environment | Tag pattern | Source of truth |
|---|---|---|
| **Production** | `ghcr.io/luviku/libreclinicamuw:<semver>` (e.g. `:1.5.0-beta.4-muw`) | `project.version` in `pom.xml:8` — published by `.github/workflows/release-image.yml` on every release. |
| **Staging** | `ghcr.io/luviku/libreclinicamuw:latest` | release workflow re-tags the latest semver push as `:latest`. |
| **Local dev** | `libreclinica-muw:dev` (built by `docker compose build`) | the `:dev` tag in `compose.yaml` is for local Docker daemons only — never pushed. |

Production **must** pin to a semver tag. `:latest` is acceptable on
staging because the cost of a rollback is "restart with the
previous semver tag" — but on production it leaves you unable to
pin to a known-good prior image (a re-pushed `:latest` poisons the
record). For pinned semver, edit `compose.yaml` to
`image: ghcr.io/luviku/libreclinicamuw:<semver>` and commit
alongside the release.

### HEALTHCHECK (Dockerfile, Phase B/A7)

The runtime stage of `Dockerfile` carries a `HEALTHCHECK` that
probes `/LibreClinica/actuator/health` every 30 s after a 90 s
start-period. The start-period budget covers:

| Phase | Typical duration | Source |
|---|---|---|
| Liquibase apply | ~20–30 s | steady-state changelog on the MUW DB |
| Quartz scheduler init | ~5 s | `@DependsOn("liquibase")` at [`QuartzConfig.java:63`](../../core/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/config/QuartzConfig.java) — Quartz waits for Liquibase |
| Tomcat WAR deploy | ~30–40 s | post-context-refresh servlet init |

90 s gives the cluster orchestrator (or `docker compose up
--wait`) a clean signal — health goes `UP` once all three phases
land and the actuator endpoint serves — without false-negative
restarts during a cold boot.

### Deploy steps

For the single-site MUW deployment the default convention is the
`:latest` tag (institutional registry pushes the released tag to
`:latest`). For pinned SHAs, edit `compose.yaml` to
`image: libreclinica-muw:<sha>` and commit alongside the release.

```bash
docker compose down libreclinica
docker compose pull libreclinica
docker compose up -d libreclinica
docker compose logs -f libreclinica
```

Liquibase log lines interleave with the Tomcat startup banner —
expect a ~30 s window where Liquibase applies pending changesets
before the WAR finishes deploying. Wait for `Server startup in
<N> ms` AND a clean Liquibase pass (`Database is up to date` or
the list of newly applied changesets, with no `SQLException` or
`LiquibaseException` between them).

Probe the two unauthenticated endpoints:

```bash
# Unauthenticated probe — expect 401 (the SPA "me" endpoint).
curl -sS -o /dev/null -w '%{http_code}\n' \
  http://127.0.0.1:8080/LibreClinica/pages/api/v1/me
# Expect: 401

# Boot's actuator health probe — expect {"status":"UP"}.
curl -sS http://127.0.0.1:8080/LibreClinica/actuator/health
# Expect: {"status":"UP"}
```

Lift the reverse-proxy drain (or restart it if you used the
`docker compose stop` form in §1).

---

## 4. Smoke test

Sign in as the sysadmin via the institutional SSO path and walk
the highest-value clinical write paths the team uses day-to-day:

- Home → study picker → land on the institutional study.
- **Create test subject** — `Patient anlegen`, save, confirm the
  subject appears in the subject matrix.
- **Schedule a visit** — open the new subject, schedule one of the
  scheduled events from the protocol.
- **Save a CRF** — open the visit, enter a handful of items
  (including at least one item with a show-when condition), save.
- **Eye-cohort transition** — if the deployment runs an
  ophthalmology cohort, perform one transition (e.g. OD enrolment).

Confirm the audit trail recorded each action AND no failures
appeared:

```bash
docker compose exec db psql -U clinica libreclinica -c \
  "SELECT COUNT(*), MAX(audit_date)
     FROM audit_log_event
    WHERE audit_date > now() - interval '15 minutes';"

# A1 hardening: failure rows. Should be zero across the smoke window.
docker compose exec db psql -U clinica libreclinica -c \
  "SELECT audit_log_event_type_id, COUNT(*)
     FROM audit_log_event
    WHERE audit_date > now() - interval '15 minutes'
      AND audit_log_event_type_id IN (61, 62)
    GROUP BY audit_log_event_type_id;"
# Expect: 0 rows.
```

If any `OPERATION_FAILED` (type 61) or `JOB_FAILED` (type 62) row
appeared during the smoke, treat as a deploy regression and go to §5.

---

## 5. Rollback

> **One release cannot be rolled back by redeploying the old image: the one
> that renames `image_ingest` to `ingest_item` (Phase 3 / P3.1, changelog
> `lc-muw-2026-10-05-ingest-item.xml`).** An older WAR queries `image_ingest`,
> which no longer exists, so its entire ingest surface — the DICOM receiver,
> both upload portals, the reconciliation inbox — fails on every request while
> the rest of the application looks healthy. Rolling that release back means
> **restoring the §1 dump**, not step (b) below. Check `databasechangelog` for
> `2026-10-05-ingest-item-rename-table` before assuming an image rollback is
> enough. The changelog does carry a complete `<rollback>` (verified to restore
> the schema byte-identically with rows preserved), so a database-only rollback
> is possible via `liquibase rollbackToDate` — but it must be run *before* the
> old WAR starts writing, and it is the dump that is the tested path.

Roll back when any of the following is true:

- A clinical write path is failing (subject create, CRF save, eye
  transition, signature).
- The sysadmin cannot sign in (SSO sidecar reachable, app rejects
  the principal).
- Liquibase failed during boot (WAR not deployed; container in
  restart loop or stopped).
- `OPERATION_FAILED` / `JOB_FAILED` rows appeared during the smoke.

```bash
# (a) Stop the failing app.
docker compose down libreclinica

# (b) Roll the image tag back to the previous version. If you
#     deploy by `:latest`, this means re-tagging the previous SHA
#     to `:latest` in the registry and pulling again. If you deploy
#     by pinned SHA, edit compose.yaml back to the previous SHA.
docker compose pull libreclinica

# (c) DB rollback. Liquibase's `--rollback` only works for
#     changesets with explicit <rollback> handlers, which most
#     clinical-data migrations do NOT have. For routine releases
#     the institutional pattern is "roll forward with a hotfix
#     changeset" rather than `--rollback`.
#
#     If the failed changeset corrupted data (rare but possible
#     mid-DML), restore from the §1 backup. The volume name is
#     derived from the pinned compose project name `libreclinica-muw`
#     (see `compose.yaml:3` `name: libreclinica-muw`) and the volume
#     key `libreclinica-db-data`, joined by Docker Compose's
#     standard `<project>_<volume>` underscore separator. The
#     resulting `libreclinica-muw_libreclinica-db-data` name is the
#     intentional convention; do NOT change either side without a
#     coordinated DB-volume migration.
docker compose down db
# wipe the data volume only if the dump is verified intact
docker volume rm libreclinica-muw_libreclinica-db-data
docker compose up -d db
docker compose exec -T db psql -U clinica -d libreclinica \
  < /var/backups/libreclinica/backup-<TIMESTAMP>.sql

# (d) Bring the rolled-back app up and re-run §4.
docker compose up -d libreclinica
```

Document the incident: image tag rolled to, DB action (untouched
vs. restored), audit row IDs of any failure rows captured. The
incident note belongs in the institutional ops log; the audit
table itself is the GxP-relevant trail.

---

## 6. Reference: audit-table forensics

The sysadmin's "what failed since the last deploy" query:

```sql
SELECT *
  FROM audit_log_event
 WHERE audit_log_event_type_id IN (61, 62)
 ORDER BY audit_date DESC
 LIMIT 50;
```

Type IDs 61 (`OPERATION_FAILED`) and 62 (`JOB_FAILED`) are seeded
by the Phase A1 hardening changeset. Both carry
`is_user_visible = false` on the `audit_log_event_type` lookup, so
they are filtered out of the per-study audit-log SPA view but stay
in the table for compliance + sysadmin queries. The system-wide
audit view (sysadmin/compliance role) shows them. Each row carries
the request ID (Phase A4 `reqId` in MDC) — cross-reference against
`libreclinica.log` to recover the stack trace and the operator's
SPA toast text.

### Sysadmin screenshots

> Screenshots are pending Stream 6 (sysadmin audit-log UI) landing.
> When the system audit-log UI is live, add screenshots showing:
> - TopBar Administrator menu with "System audit" link
> - /system/audit-log view with at least one OPERATION_FAILED row
> - Filter dropdowns (actor / variant / subject) for narrowing the system trail

### reqId correlation walk-through

When a user reports an error, the SPA toast shows a Fehler-ID like
`7f3e4a2b-...`. Use it to correlate across the three layers:

1. SPA toast: Fehler-ID: `7f3e4a2b-c1d4-...`
2. Server log (text): `docker compose exec libreclinica grep 7f3e4a2b /usr/local/tomcat/logs/libreclinica.log`
3. Server log (JSON for SIEM): `docker compose exec libreclinica grep 7f3e4a2b /usr/local/tomcat/logs/libreclinica.json | jq`
4. Audit row: `SELECT * FROM audit_log_event WHERE error_message LIKE '%7f3e4a2b%' OR description LIKE '%7f3e4a2b%';`

The reqId is the same UUIDv4 across all four layers — generated by
RequestIdFilter (Phase A4), echoed in the X-Request-Id response header
to the SPA, recorded in the audit row via FailureAuditTemplate, and
serialized to the JSON appender via includeMdcKeyName>reqId.

### On-call rotation

> To be filled in by MUW IT at deploy time:
> - Primary on-call sysadmin: [name, contact]
> - Secondary on-call sysadmin: [name, contact]
> - Pager rotation schedule: [link to roster]
> - Escalation to clinical lead: [name, contact]

---

## 7. DICOM fundus receiver (DR-025)

Only on a host that serves a camera. The sidecar is behind the `dicom`
compose profile, so a host that does not set `COMPOSE_PROFILES=dicom`
never starts it and nothing below applies.

### What has to be true before a camera is connected

| # | Item | Why |
|---|---|---|
| 1 | `core.dicom.ingest.token` set to 32+ hex characters, and `DICOM_SCP_INGEST_TOKEN` in `/etc/libreclinica/env` set to the same value | The sidecar cannot post an image without it, and the worklist answers 503. They are compared byte for byte. |
| 2 | `DICOM_SCP_ALLOWED_CALLING_AE_TITLES` set to the camera's AE title | Blank accepts an association from anything that can reach the port. |
| 3 | `core.dicom.worklist.studyOids` set to the study the camera serves | A blank value offers every study's schedule to the device. |
| 4 | `LIBRECLINICA_DICOM_BIND_ADDR` set to the camera-facing address | The default publishes 11112 on every interface. |
| 5 | Campus firewall admits the camera VLAN to 11112 and nothing else | A C-FIND returns subject labels, sex and dates of birth. Verify from a general workstation that the port is closed. |
| 6 | nginx returns 404 for `/LibreClinica/pages/api/v1/internal/` | Shipped in `deploy/nginx/ecrf.conf`. Check after any proxy change. |
| 7 | `DICOM_SCP_LOG_LEVEL=INFO` | DEBUG makes pynetdicom dump whole datasets, which puts patient name and ID into the container log. |
| 8 | `core.ingest.storePath` (default `/var/lib/libreclinica/ingest`) mounted at the **same path in the app and the sidecar** — `deploy/compose.production.yaml` binds `/var/lib/libreclinica/ingest` into both | DR-029: the upload page writes JPEG/PNG and DICOM files here; the sidecar describes and pseudonymises an uploaded `.dcm` in place through the same bind. A path that exists in one container and not the other turns every DICOM upload into a 503. Included in the backup tar (a2). |
| 9 | `core.dicom.describe.url` points at the sidecar (`http://dicom-scp:8081/describe`) and `DICOM_SCP_DESCRIBE_PORT` is **not** in any `ports:` list | The endpoint rewrites files on the shared volume and is gated by the same token as the ingest hand-off; it must never be reachable from outside the compose network. Leaving the URL blank refuses DICOM uploads rather than storing a clinic camera's export with the hospital's patient in it. |

### Dry run

```bash
COMPOSE_PROFILES=dicom deploy/dicom-dry-run.sh
```

Performs the three exchanges a camera performs (C-ECHO, worklist
C-FIND, C-STORE), checks in the database that the image bound to the
visit, removes what it created, and checks that no patient identifier
from its test image reached the sidecar log. Schedule a visit for a test
subject today first, or the worklist and bind checks have nothing to
check against and say so.

Then connect the real camera on that test subject. On the Optomed Lumo:
remote AE `LIBRECLINICA`, the VM's camera-facing address, port 11112,
its own AE title matching item 2 above, worklist pointed at the same
host and port. **Clear the device's stored-study backlog first** — it
flushes everything it holds on first contact, and those images will
arrive as unbound rows in the inbox.

### Day one

```bash
# The sidecar's per-association summary lines.
docker compose logs -f dicom-scp

# The app side. Both lines carry counts, never patient data.
docker compose logs libreclinica | grep -E "DICOM ingest:|DICOM worklist served:"

# Reconciliation backlog. UNBOUND should trend to zero as operators
# work the inbox; a growing UNBOUND count means the worklist is not
# being used, so images arrive without an accession.
docker compose exec db psql -U clinica libreclinica -c \
  "SELECT status, count(*) FROM image_ingest GROUP BY 1 ORDER BY 1;"

# Disk. A fundus study is a few MB; a camera flushing a backlog is not.
du -sh /var/lib/libreclinica/dicom-ingest /var/lib/libreclinica/ingest
```

### Retention

Dismissing an image in the inbox is the operator's decision that it is
not study data. The nightly sweep (03:30) then deletes the file and the
row once `core.ingest.retention.dismissedDays` has passed, default 30,
and writes one audit row per pass recording how many it removed. Bound
images are study data and are never swept; unbound ones are still
awaiting a decision and are never swept either — a growing unbound
backlog is a workflow problem, not something retention will clear.

Full rules, including what is deliberately never swept and where the
files live: [data-retention.md](data-retention.md).

To check the sweep is running:

```bash
docker compose exec db psql -U clinica libreclinica -c \
  "SELECT audit_date, new_value FROM audit_log_event
    WHERE audit_table = 'image_ingest' AND entity_name = 'Retention sweep'
    ORDER BY audit_date DESC LIMIT 5;"
```
