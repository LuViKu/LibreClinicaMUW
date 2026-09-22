#!/usr/bin/env bash
# =============================================================================
# Migration dry run against a copy of production
# =============================================================================
# Answers one question before a release touches the real database: what will
# Liquibase do, and does it finish?
#
# The runbook's §2 describes this as a manual sequence. Done by hand it is
# skipped under time pressure, and this codebase has no "list pending
# changesets" command — the SpringLiquibase bean runs `update` on boot, so the
# only honest dry run is to actually run it somewhere disposable and read what
# happened.
#
# This restores the latest backup into a throwaway Postgres, boots the
# candidate image against it, and reports the changesets that were applied.
# It never touches the production database or the running stack: it uses its
# own compose project name, its own container, and a temporary port.
#
# USAGE (on the VM, as the deploy user):
#   deploy/dry-run-migration.sh [path/to/backup.sql]
#
# With no argument it takes the newest backup in /var/backups/libreclinica.
#
# Environment:
#   IMAGE          candidate image (default: the tag in /etc/libreclinica/env,
#                  else ghcr.io/luviku/libreclinicamuw:latest)
#   BACKUP_DIR     where backups live (default: /var/backups/libreclinica)
#   PG_IMAGE       postgres image (default: postgres:14-alpine)
#   KEEP           set to 1 to leave the throwaway containers running
#
# Exit: 0 the migration completed, 1 it failed or something is missing.
# =============================================================================
set -uo pipefail

BACKUP_DIR="${BACKUP_DIR:-/var/backups/libreclinica}"
PG_IMAGE="${PG_IMAGE:-postgres:14-alpine}"
PROJECT="libreclinica-dryrun-$$"
PG_NAME="${PROJECT}-db"
APP_NAME="${PROJECT}-app"
NET_NAME="${PROJECT}-net"

if [ -n "${1:-}" ]; then
  BACKUP="$1"
else
  BACKUP="$(ls -1t "$BACKUP_DIR"/backup-*.sql 2>/dev/null | head -1)"
fi

IMAGE="${IMAGE:-}"
if [ -z "$IMAGE" ] && [ -r /etc/libreclinica/env ]; then
  TAG="$(sed -n 's/^LIBRECLINICA_IMAGE_TAG=//p' /etc/libreclinica/env | tail -1)"
  [ -n "$TAG" ] && IMAGE="ghcr.io/luviku/libreclinicamuw:${TAG}"
fi
IMAGE="${IMAGE:-ghcr.io/luviku/libreclinicamuw:latest}"

say()  { printf '  %s\n' "$1"; }
ok()   { printf '  \033[32mok\033[0m    %s\n' "$1"; }
bad()  { printf '  \033[31mFAIL\033[0m  %s\n' "$1"; }

cleanup() {
  if [ "${KEEP:-0}" = "1" ]; then
    say "KEEP=1 — leaving $PG_NAME and $APP_NAME running."
    return
  fi
  docker rm -f "$APP_NAME" >/dev/null 2>&1
  docker rm -f "$PG_NAME" >/dev/null 2>&1
  docker network rm "$NET_NAME" >/dev/null 2>&1
}
trap cleanup EXIT

echo "Migration dry run — $(date '+%Y-%m-%d %H:%M:%S')"
echo

if [ -z "$BACKUP" ] || [ ! -r "$BACKUP" ]; then
  bad "no readable backup found (looked in $BACKUP_DIR)"
  echo "  Pass one explicitly: deploy/dry-run-migration.sh /path/to/backup.sql" >&2
  exit 1
fi
say "backup: $BACKUP"
say "image:  $IMAGE"
echo

# --- 1. a throwaway database, restored from the backup ------------------------
docker network create "$NET_NAME" >/dev/null 2>&1
# The image reads its database settings from the baked datainfo.properties
# (dbHost=db, dbUser=clinica, dbPass=clinica), not from environment variables,
# so the throwaway database answers to the hostname "db" on this network and
# uses the same credentials. Production overrides those in
# /opt/libreclinica/config; a dry run deliberately does not, because it is
# talking to a copy, not to production.
if ! docker run -d --name "$PG_NAME" --network "$NET_NAME" --network-alias db \
      -e POSTGRES_USER=clinica -e POSTGRES_PASSWORD=clinica \
      -e POSTGRES_DB=libreclinica "$PG_IMAGE" >/dev/null; then
  bad "could not start the throwaway Postgres"
  exit 1
fi

say "waiting for the throwaway database …"
for _ in $(seq 1 60); do
  docker exec "$PG_NAME" pg_isready -U clinica -d libreclinica >/dev/null 2>&1 && break
  sleep 2
done
if ! docker exec "$PG_NAME" pg_isready -U clinica -d libreclinica >/dev/null 2>&1; then
  bad "the throwaway database never became ready"
  exit 1
fi

if ! docker exec -i "$PG_NAME" psql -q -U clinica -d libreclinica < "$BACKUP" >/dev/null 2>&1; then
  bad "restoring the backup failed — the dump may be from a newer server"
  exit 1
fi
ok "backup restored"

before="$(docker exec "$PG_NAME" psql -qAt -U clinica -d libreclinica \
  -c 'SELECT count(*) FROM databasechangelog' 2>/dev/null | tr -d '\r')"
say "changesets already applied: ${before:-unknown}"

# --- 2. boot the candidate image against it ----------------------------------
say "booting the candidate image (this runs the migration) …"
docker pull -q "$IMAGE" >/dev/null 2>&1
docker run -d --name "$APP_NAME" --network "$NET_NAME" \
  -e LIQUIBASE_CONTEXTS="" \
  "$IMAGE" >/dev/null 2>&1

# Liquibase runs during context startup; wait for the log to settle rather than
# for the health check, which also waits on the whole web application.
migrated=0
for _ in $(seq 1 150); do
  logs="$(docker logs "$APP_NAME" 2>&1)"
  # grep on the variable rather than through a pipe: `grep -q` exits at the
  # first match and closes the pipe, which makes the writer complain.
  # Only completion counts. "Acquired change log lock" is the *start* of the
  # migration; breaking on it reads the changelog before Liquibase has written
  # to it and reports that nothing was applied.
  if grep -qiE 'Successfully released change log lock|Database is up to date' <<<"$logs"; then
    migrated=1
  fi
  if grep -qiE 'liquibase.*(exception|failed)|Migration failed' <<<"$logs"; then
    bad "Liquibase reported a failure:"
    grep -iE 'liquibase.*(exception|failed)|Migration failed' <<<"$logs" | tail -5
    exit 1
  fi
  [ "$migrated" = "1" ] && break
  sleep 2
done

after="$(docker exec "$PG_NAME" psql -qAt -U clinica -d libreclinica \
  -c 'SELECT count(*) FROM databasechangelog' 2>/dev/null | tr -d '\r')"

if [ -z "$after" ]; then
  bad "could not read databasechangelog after the boot — see: docker logs $APP_NAME"
  exit 1
fi

applied=$(( after - before ))
ok "migration completed: ${applied} new changeset(s) (${before} → ${after})"

if [ "$applied" -gt 0 ]; then
  echo
  echo "Applied in this release:"
  docker exec "$PG_NAME" psql -qAt -U clinica -d libreclinica -c \
    "SELECT '  ' || filename || ' :: ' || id FROM (
       SELECT filename, id, orderexecuted FROM databasechangelog
        ORDER BY orderexecuted DESC LIMIT ${applied}
     ) recent ORDER BY orderexecuted ASC" 2>/dev/null
  echo
  echo "Read that list against the diff being deployed. The runbook's §2 names"
  echo "the three patterns worth stopping for: a NOT NULL added to a populated"
  echo "column with no backfill, a DROP COLUMN the running WAR still reads, and"
  echo "an index built without CONCURRENTLY on a large table."
fi

echo
echo "The production database was not touched."
