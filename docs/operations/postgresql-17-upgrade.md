# PostgreSQL 17 upgrade

Moving the production database from PostgreSQL 14 to 17. Community support for
14 ends on 2026-11-12; 17 is supported until November 2029.

The short version: `deploy/pg-major-upgrade.sh` does the upgrade while the app
is down, and leaves the 14 data directory untouched beside the new one.
Switching production to 17 is a separate final step: one line in
`/etc/libreclinica/env` and a restart. Until that line exists, production runs
14, whatever the dev stack runs.

---

## Why this needs a runbook

- **A major version cannot open another major's data directory.** PostgreSQL 17
  refuses a 14 directory (`database files are incompatible with server`), and
  14 refuses a 17 one. The data has to be moved; changing the image tag is not
  enough.
- **Production pins its own version.** `compose.yaml`, the dev stack, runs 17
  on a new volume. `deploy/compose.production.yaml` sets the `db` image itself,
  as `postgres:${LIBRECLINICA_POSTGRES_IMAGE_TAG:-14-alpine}`. Without that
  line production would inherit 17 from `compose.yaml` on its next restart,
  and its database would not start.
- **The switch is an env-file line, not an edit of the overlay.**
  `setup-ubuntu-host.sh` resets `/opt/libreclinica` with `git reset --hard` on
  every run. A hand edit of the overlay would be undone by the next release
  deploy, and the restart after it would start 14 on the 17 directory.
  `/etc/libreclinica/env` survives those runs.
- **17, not 18.** The PostgreSQL 18 images keep their data under
  `/var/lib/postgresql/18/docker` and mount their volume at
  `/var/lib/postgresql`, which is not the layout the overlay binds. The script
  refuses an image whose data directory is elsewhere.
- **Dump and restore, not `pg_upgrade`.** `pg_upgrade` needs the old and new
  binaries side by side, and each official image carries one version. At this
  database's size a dump and restore takes minutes.

---

## What the script does

`sudo bash /opt/libreclinica/deploy/pg-major-upgrade.sh` runs these steps and
stops at the first one that fails. Until step 8 the live data directory is
exactly as it was, so stopping early leaves nothing to undo.

1. **Preflight.** Versions, the data bind, free disk space; pulls
   `postgres:17-alpine`.
2. **Stops the stack except the database** and waits until no client is
   connected. The nightly backup timer is paused for the duration.
3. **Inventory of 14.** The exact row count of every table, sequence values,
   schema objects, roles, databases and extensions. The Liquibase
   `databasechangelog` is one of the tables, and its count is printed.
4. **Dump.** `pg_dumpall` in the 14 container, into
   `/var/backups/libreclinica/pg-major-upgrade-<stamp>/cluster-pg14.sql.gz`.
   The dump is verified: gzip intact, ending with `pg_dumpall`'s completion
   line, with a sha256 beside it. Then the 14 server stops cleanly.
5. **New cluster.** 17 is initialised in `/var/lib/libreclinica/postgres-17`, in
   a throwaway container with no network, with the old cluster's encoding,
   locale and checksum setting. The dump is restored into it.
6. **Comparison.** After `ANALYZE`, the same inventory is taken on 17 and
   compared with step 3. Any difference stops the upgrade.
7. The 17 server stops cleanly.
8. **Switch of directories.** The 14 directory is moved aside to
   `/var/lib/libreclinica/postgres-14`, untouched, and the 17 one takes its
   place.
9. **Final step.** The script prints it; nothing starts until you run it.

The script never prints or stores the database password: both servers are
reached through `docker exec`, on their local socket. The dump is a full
backup of the cluster, role password hashes included, written `0600` into a
`0700` directory.

What it reports but does not stop for: extension versions, server settings
from `postgresql.conf` or `ALTER SYSTEM`, and `pg_hba.conf` rules. A new major
writes its own configuration files, and hand edits in the old ones do not
travel in a dump. If one is listed, carry it over by hand after the switch.

It refuses, before stopping anything, when:

- the running `db` container does not keep its data in
  `/var/lib/libreclinica/postgres` (a wrong project or layout);
- the pin and the data directory disagree;
- `postgres-14` or `postgres-17` already exists beside the data directory;
- the data directory is a mount point (the switch renames it);
- the disk is short, or the nightly backup is running;
- a role has an `md5` password hash. The new server authenticates with
  scram-sha-256; reset the password on 14 first
  (`sudo docker exec -it libreclinica-muw-db-1 psql -U clinica -c '\password clinica'`,
  with the value from `POSTGRES_PASSWORD` in `/etc/libreclinica/env`).

---

## Before the day

1. **Deploy the release that contains this runbook** as a normal release. It
   brings the script and the explicit 14 pin; production keeps running 14.
   Check:
   ```sh
   sudo docker exec libreclinica-muw-db-1 psql -U clinica -d postgres -Atc 'SHOW server_version'   # 14.x
   ```
2. **Run the preflight** the day before. It changes nothing, prints the size
   of the data directory and pulls the 17 image, so the day does not depend on
   Docker Hub:
   ```sh
   sudo bash /opt/libreclinica/deploy/pg-major-upgrade.sh --check
   ```
3. **Plan the downtime.** In the rehearsal (an 81 MB database, 300 MB data
   directory) the whole script took 24 to 35 seconds. The time grows with the
   data; allow 15 minutes including the checks after the switch.
4. **Rehearse on a copy if you can.** See [Rehearsing elsewhere](#rehearsing-elsewhere).

## On the day

1. Open a `tmux` (or `screen`) session, so a dropped SSH connection cannot
   interrupt the script:
   ```sh
   tmux new -s pgupgrade
   ```
2. Run the upgrade. The app is stopped by the script:
   ```sh
   sudo bash /opt/libreclinica/deploy/pg-major-upgrade.sh
   ```
3. Read the comparison section. Every line must say `identical`. Then read the
   warnings, if any, about settings and `pg_hba.conf`.
4. Run the final step the script printed:
   ```sh
   sudo sed -i '/^LIBRECLINICA_POSTGRES_IMAGE_TAG=/d' /etc/libreclinica/env
   echo 'LIBRECLINICA_POSTGRES_IMAGE_TAG=17-alpine' | sudo tee -a /etc/libreclinica/env >/dev/null
   sudo systemctl restart libreclinica
   ```
5. Check, before users are back in:
   ```sh
   sudo docker exec libreclinica-muw-db-1 psql -U clinica -d postgres -Atc 'SHOW server_version'   # 17.x
   curl -fsS http://127.0.0.1:8080/LibreClinica/actuator/health                                   # UP
   ```
   Sign in, open a subject and a CRF, and take a backup from 17 now rather than
   waiting for the night:
   ```sh
   sudo systemctl start libreclinica-backup-db.service
   sudo journalctl -u libreclinica-backup-db.service -n 3
   ```

---

## If something goes wrong

**The script stopped.** Nothing needs undoing: it says so, and prints the
command that brings production back on 14 (`sudo systemctl restart
libreclinica`). If it had created `/var/lib/libreclinica/postgres-17`, it names
it; remove it before the next attempt.

**After the switch.** Before users are back in, roll back with:

```sh
sudo bash /opt/libreclinica/deploy/pg-major-upgrade.sh --rollback
```

- It compares the running 17 with what the upgrade restored and refuses if
  anything has been written since, listing the tables. Any start of the app
  writes a few rows (a login audit, the scheduler, storage samples); the list
  shows whether anything clinical is among them. `--rollback
  --discard-changes` accepts the loss.
- It stops the stack, moves the 17 directory aside to
  `postgres-17.rolled-back-<stamp>` and moves the 14 directory back.
- It prints the final step: remove the `LIBRECLINICA_POSTGRES_IMAGE_TAG` line,
  restart.

What was written on 17 is not in 14 after a rollback. Once users have entered
data on 17, fix forward instead.

---

## Afterwards

- Keep `/var/lib/libreclinica/postgres-14` and the upgrade directory in
  `/var/backups/libreclinica/` until you are confident, for example for the 30
  days the nightly backups are kept. Then remove both with `sudo rm -rf`.
  Neither is pruned automatically.
- In the repository, once production runs 17: set the overlay's default to
  `17-alpine` (the env line then becomes redundant) and drop `14-alpine` from
  the CI integration-test matrix. `deploy/dry-run-migration.sh` needs no
  change: it follows `LIBRECLINICA_POSTGRES_IMAGE_TAG`, so it rehearses on 17
  from the switch on.
- A host installed before that change should get
  `LIBRECLINICA_POSTGRES_IMAGE_TAG=17-alpine` in `/etc/libreclinica/env` before
  its first start, so it never initialises a 14 directory.

---

## Development stack

`compose.yaml` runs 17 on a new named volume,
`libreclinica-muw_libreclinica-db-data-17`. The old 14 volume,
`libreclinica-muw_libreclinica-db-data`, is left as it was and never opened by
17.

**Starting fresh** needs nothing: on the first `docker compose up` Liquibase
builds the schema, with the demo data (`LIQUIBASE_CONTEXTS=demo`).

**Carrying the old data over**, from the repository root. The 17 container's
`pg_dump` reads the old volume through a temporary 14 server, and nothing may
hold that volume meanwhile:

```sh
docker compose down
docker compose up -d db
docker run -d --name pg14-old --network libreclinica-muw_default -v libreclinica-muw_libreclinica-db-data:/var/lib/postgresql/data postgres:14-alpine
docker compose exec -T db sh -c 'until pg_isready -q -h pg14-old -U clinica; do sleep 1; done; PGPASSWORD=clinica pg_dump -h pg14-old -U clinica libreclinica | psql -q -U clinica -d libreclinica'
docker rm -f pg14-old
docker compose up -d
```

When the old volume is no longer needed:
`docker volume rm libreclinica-muw_libreclinica-db-data`.

**Tests.** The web database ITs start `postgres:17-alpine`; add
`-Dit.postgres.image=postgres:14-alpine` to run them on 14. CI runs the
integration-test job on both until production has moved.

---

## Rehearsing elsewhere

The script's defaults are production's layout. These environment variables
point it at a copy, for example on a staging VM: `INSTALL_PREFIX`, `ENV_FILE`,
`PG_DATA_DIR`, `BACKUP_DIR`, `COMPOSE_PROJECT` and `COMPOSE_FILES` (see
`--help`). Always set `COMPOSE_PROJECT` for a copy. The script refuses to run
when the project's running database does not keep its data in `PG_DATA_DIR`,
but the project name is what keeps it away from the real stack.

It was rehearsed that way before release: PostgreSQL 14 on a host directory,
holding the dev database plus 250,000 generated audit rows whose text
exercises dump escaping, with the app running. The run stopped the app,
compared all 139 tables and switched. The full contents of every table hashed
identically on 14 and 17, the app started on 17 with Liquibase finding nothing
to apply, and the rollback brought back a 14 whose contents matched the
original. An early run that stopped halfway showed the failure path as well:
the live directory untouched, the restart command printed, and that restart
bringing 14 back.
