#!/usr/bin/env bash
# =============================================================================
# PostgreSQL major-version upgrade of the production database (14 -> 17)
# =============================================================================
# A PostgreSQL major version cannot open another major's data directory, so
# this is a dump and restore: pg_dumpall inside the running 14 container,
# restored into a fresh 17 data directory beside the live one. (pg_upgrade is
# faster, but it needs both versions' binaries in one image, and the official
# images carry one each.)
#
# The runbook is docs/operations/postgresql-17-upgrade.md. In short:
#
#   pg-major-upgrade.sh --check      preflight only; changes nothing
#   pg-major-upgrade.sh              the upgrade; the app is down meanwhile
#   pg-major-upgrade.sh --rollback   back to the 14 directory it kept
#
# The upgrade, in order. It stops at the first step that fails, and until
# step 8 the live data directory is exactly as it was:
#   1. preflight: versions, layout, free disk space, the target image
#   2. stops every service of the stack except the database and waits until
#      no client is connected; the nightly backup timer pauses meanwhile
#   3. records an inventory: the exact row count of every table, sequence
#      values, schema objects, roles, databases and extensions (the Liquibase
#      databasechangelog is one of the tables)
#   4. pg_dumpall into a verified gzip under $BACKUP_DIR/pg-major-upgrade-*/,
#      then stops the 14 server cleanly
#   5. initialises a 17 cluster in <data dir>-17, in a throwaway container
#      with no network and the old cluster's encoding, locale and checksum
#      setting, and restores the dump into it
#   6. ANALYZE, then the same inventory, compared with step 3: any difference
#      stops the upgrade
#   7. stops the 17 server cleanly
#   8. moves the old directory aside to <data dir>-14, untouched, and the new
#      one into its place
#   9. prints the final step: switch production's pin to 17, then restart
#
# Nothing here prints or stores the database password: both servers are
# reached through `docker exec`, on their local socket, and the compose
# configuration is only searched for the db service's image, data bind and
# user. The dump holds the whole cluster, role password hashes included, like
# any full backup, and is written 0600 into a 0700 directory.
#
# USAGE (on the VM, as root; inside tmux or screen, so that a dropped SSH
# session cannot interrupt it):
#   sudo bash /opt/libreclinica/deploy/pg-major-upgrade.sh [--check]
#   sudo bash /opt/libreclinica/deploy/pg-major-upgrade.sh --rollback [--discard-changes]
#
# Environment (the defaults are the layout setup-ubuntu-host.sh builds; the
# overrides exist to rehearse this somewhere else):
#   TARGET_TAG       postgres image tag to move to (default: 17-alpine)
#   INSTALL_PREFIX   the deploy checkout (default: /opt/libreclinica)
#   ENV_FILE         compose env file (default: /etc/libreclinica/env)
#   PG_DATA_DIR      the live data directory (default: /var/lib/libreclinica/postgres)
#   BACKUP_DIR       where the dump and the reports go (default: /var/backups/libreclinica)
#   COMPOSE_PROJECT  compose project name (default: libreclinica-muw)
#   COMPOSE_FILES    compose files, relative to INSTALL_PREFIX
#                    (default: "compose.yaml deploy/compose.production.yaml")
#
# Exit: 0 done, 1 failed or refused, 2 bad arguments. The last lines always
# say what state the host is in and what to run next.
# =============================================================================
set -Eeuo pipefail

MODE=upgrade
DISCARD=0
for arg in "$@"; do
  case "$arg" in
    --check)           MODE=check ;;
    --rollback)        MODE=rollback ;;
    --discard-changes) DISCARD=1 ;;
    -h|--help)
      awk 'NR > 2 && /^# =+$/ { if (++n == 2) exit; next } NR > 2 { sub(/^# ?/, ""); print }' "$0"
      exit 0 ;;
    *) echo "unknown argument: $arg (see --help)" >&2; exit 2 ;;
  esac
done
if [ "$DISCARD" = 1 ] && [ "$MODE" != rollback ]; then
  echo "--discard-changes only goes with --rollback" >&2; exit 2
fi

TARGET_TAG="${TARGET_TAG:-17-alpine}"
INSTALL_PREFIX="${INSTALL_PREFIX:-/opt/libreclinica}"
ENV_FILE="${ENV_FILE:-/etc/libreclinica/env}"
PG_DATA_DIR="${PG_DATA_DIR:-/var/lib/libreclinica/postgres}"
PG_DATA_DIR="${PG_DATA_DIR%/}"
BACKUP_DIR="${BACKUP_DIR:-/var/backups/libreclinica}"
COMPOSE_PROJECT="${COMPOSE_PROJECT:-libreclinica-muw}"
COMPOSE_FILES="${COMPOSE_FILES:-compose.yaml deploy/compose.production.yaml}"

# The variable deploy/compose.production.yaml reads for the db image tag.
PIN_VAR=LIBRECLINICA_POSTGRES_IMAGE_TAG
IMAGE_REPO=docker.io/library/postgres
TARGET_IMAGE="${IMAGE_REPO}:${TARGET_TAG}"
CONTAINER_PGDATA=/var/lib/postgresql/data
UNIT=libreclinica.service
BACKUP_TIMER=libreclinica-backup-db.timer
STAMP="$(date +%Y%m%dT%H%M%S)"

say()  { printf '  %s\n' "$*"; }
ok()   { printf '  \033[32mok\033[0m    %s\n' "$*"; }
warn() { printf '  \033[33mwarn\033[0m  %s\n' "$*"; }
bad()  { printf '  \033[31mFAIL\033[0m  %s\n' "$*"; }
step() { printf '\n\033[1m== %s ==\033[0m\n' "$*"; }
die()  { bad "$*"; exit 1; }
human() { numfmt --to=iec --suffix=B "$1"; }
# Throwaway password for initialising the new cluster. The restore replaces
# it with the role's real password hash from the dump, and the container has
# no network, so it is never usable. (`|| true`: head closing the pipe is
# not a failure.)
gen_secret() { LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom 2>/dev/null | head -c 32 || true; }

compose() {
  local files=() f
  for f in $COMPOSE_FILES; do files+=(-f "${INSTALL_PREFIX}/${f}"); done
  docker compose -p "$COMPOSE_PROJECT" --project-directory "$INSTALL_PREFIX" \
    --env-file "$ENV_FILE" "${files[@]}" "$@"
}

have_unit() { command -v systemctl >/dev/null 2>&1 && systemctl cat "$1" >/dev/null 2>&1; }

# How to bring the stack up again, as this host does it: through the systemd
# unit in production, else the services this script found running.
SERVICES=""
restart_cmd() {
  if have_unit "$UNIT"; then
    echo "sudo systemctl restart ${UNIT%.service}"
  else
    local flags="" f
    for f in $COMPOSE_FILES; do flags="$flags -f $f"; done
    echo "(cd $INSTALL_PREFIX && docker compose -p $COMPOSE_PROJECT --env-file $ENV_FILE$flags up -d${SERVICES:+ $SERVICES})"
  fi
}

# One query on one database, over the container's local socket (trust
# authentication there, so no password is involved). Unaligned, no header,
# '|' between columns.
SU=""
q() { docker exec "$1" psql -X -q -At -F '|' -v ON_ERROR_STOP=1 -U "$SU" -d "$2" -c "$3"; }

server_major() { q "$1" postgres 'SHOW server_version_num' | awk '{ print int($1 / 10000) }'; }
image_major()  { local tag="${1##*:}"; echo "${tag%%[!0-9]*}"; }
is_running()   { [ "$(docker inspect -f '{{.State.Running}}' "$1" 2>/dev/null)" = true ]; }
db_container() { compose ps -q db 2>/dev/null | head -1; }

# The services of the project that are up, except db. (With none up,
# `compose ps --services` prints an empty line, hence the filter.)
running_others() {
  compose ps --services --status running --status restarting 2>/dev/null \
    | grep -vxE 'db|' || true
}

# The container must keep its data in PG_DATA_DIR itself, not merely be
# configured to: this is what makes a wrong COMPOSE_PROJECT stop here instead
# of reaching another project's database.
check_mount() {
  local m
  m="$(docker inspect -f "{{range .Mounts}}{{if eq .Destination \"${CONTAINER_PGDATA}\"}}{{.Type}} {{.Source}}{{end}}{{end}}" "$1" 2>/dev/null || true)"
  [ "$m" = "bind $PG_DATA_DIR" ] \
    || die "the running db container keeps its data in '${m:-nothing}', not in $PG_DATA_DIR; wrong project?"
}

# Waits until the server in container $1 answers, twice across a pause. A
# fresh cluster ($2 = fresh) starts twice, once for its init scripts, and
# answers during the first run too, so wait for the init to finish first.
wait_ready() {
  local c=$1 fresh=${2:-} logs
  if [ "$fresh" = fresh ]; then
    for _ in $(seq 1 90); do
      logs="$(docker logs "$c" 2>&1 || true)"
      grep -q 'PostgreSQL init process complete' <<<"$logs" && break
      if ! is_running "$c"; then
        bad "the new server stopped during initialisation:"
        tail -5 <<<"$logs" | sed 's/^/    /'
        return 1
      fi
      sleep 2
    done
  fi
  for _ in $(seq 1 60); do
    if q "$c" postgres 'SELECT 1' >/dev/null 2>&1; then
      sleep 2
      q "$c" postgres 'SELECT 1' >/dev/null 2>&1 && return 0
    fi
    sleep 2
  done
  return 1
}

# The db service as compose resolves it: the image it would start, the host
# directory bound at the container's PGDATA, and its POSTGRES_USER.
pinned_image()  { compose config db 2>/dev/null | sed -n 's/^    image: //p' | head -1; }
pinned_source() {
  compose config db 2>/dev/null | awk -v t="target: ${CONTAINER_PGDATA}" '
    $1 == "source:" { s = $2 } $0 ~ t "$" { print s; exit }'
}
pinned_user() { compose config db 2>/dev/null | sed -n 's/^ *POSTGRES_USER: *//p' | tr -d "\"'" | head -1; }

# --------------------------------------------------------------- inventory ---
# Everything the comparison needs, per database. Row counts are exact
# count(*), not statistics estimates; objects that belong to an extension are
# left out, because the new server installs its own version of each. The
# "char" catalog columns are cast to text: 17 no longer resolves
# unknown || "char", which 14 did.

read -r -d '' SQL_ROWS <<'SQL' || true
SELECT format('%I.%I', n.nspname, c.relname),
       (xpath('/row/n/text()',
              query_to_xml(format('SELECT count(*) AS n FROM %I.%I', n.nspname, c.relname),
                           false, true, '')))[1]::text
  FROM pg_class c
  JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE c.relkind IN ('r', 'p', 'm')
   AND (c.relkind <> 'm' OR c.relispopulated)
   AND n.nspname NOT IN ('pg_catalog', 'information_schema')
   AND n.nspname !~ '^pg_(toast|temp)'
 ORDER BY 1
SQL

read -r -d '' SQL_SEQUENCES <<'SQL' || true
SELECT format('%I.%I', schemaname, sequencename), coalesce(last_value::text, 'unused')
  FROM pg_sequences
 ORDER BY 1
SQL

read -r -d '' SQL_OBJECTS <<'SQL' || true
WITH ext AS (SELECT classid, objid FROM pg_depend WHERE deptype = 'e'),
objs AS (
  SELECT c.relnamespace AS nsp, 'relation ' || c.relkind::text AS kind
    FROM pg_class c
   WHERE c.relkind <> 't'
     AND NOT EXISTS (SELECT 1 FROM ext WHERE classid = 'pg_class'::regclass AND objid = c.oid)
  UNION ALL
  SELECT p.pronamespace, 'function ' || p.prokind::text
    FROM pg_proc p
   WHERE NOT EXISTS (SELECT 1 FROM ext WHERE classid = 'pg_proc'::regclass AND objid = p.oid)
  UNION ALL
  SELECT c.relnamespace, 'trigger'
    FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid
   WHERE NOT t.tgisinternal
  UNION ALL
  SELECT con.connamespace, 'constraint ' || con.contype::text
    FROM pg_constraint con
  UNION ALL
  SELECT t.typnamespace, 'type ' || t.typtype::text
    FROM pg_type t
   WHERE t.typtype IN ('d', 'e', 'r', 'm')
     AND NOT EXISTS (SELECT 1 FROM ext WHERE classid = 'pg_type'::regclass AND objid = t.oid)
  UNION ALL
  SELECT e.extnamespace, 'extension ' || e.extname
    FROM pg_extension e
)
SELECT n.nspname, o.kind, count(*)
  FROM objs o JOIN pg_namespace n ON n.oid = o.nsp
 WHERE n.nspname NOT IN ('pg_catalog', 'information_schema')
   AND n.nspname !~ '^pg_(toast|temp)'
 GROUP BY 1, 2
UNION ALL
SELECT '-', 'large objects', count(*) FROM pg_largeobject_metadata
 ORDER BY 1, 2
SQL

read -r -d '' SQL_EXTENSIONS <<'SQL' || true
SELECT extname, extversion FROM pg_extension ORDER BY 1
SQL

# Cluster-wide. The password column says only whether a role has one, and of
# which kind; the hash itself never leaves the server.
read -r -d '' SQL_ROLES <<'SQL' || true
SELECT a.rolname, a.rolsuper, a.rolinherit, a.rolcreaterole, a.rolcreatedb,
       a.rolcanlogin, a.rolreplication, a.rolbypassrls, a.rolconnlimit,
       CASE WHEN a.rolpassword IS NULL THEN 'no password'
            WHEN a.rolpassword LIKE 'SCRAM-SHA-256$%' THEN 'scram-sha-256'
            WHEN a.rolpassword LIKE 'md5%' THEN 'md5'
            ELSE 'other' END,
       coalesce((SELECT string_agg(DISTINCT g.rolname, ',' ORDER BY g.rolname)
                   FROM pg_auth_members m JOIN pg_roles g ON g.oid = m.roleid
                  WHERE m.member = a.oid), '')
  FROM pg_authid a
 WHERE a.rolname !~ '^pg_'
 ORDER BY 1
SQL

read -r -d '' SQL_DATABASES <<'SQL' || true
SELECT datname, pg_encoding_to_char(encoding), datcollate, datctype,
       pg_get_userbyid(datdba), datallowconn, datconnlimit
  FROM pg_database
 ORDER BY 1
SQL

# Compared, but only reported: a new major writes its own postgresql.conf and
# pg_hba.conf, and hand edits in the old ones do not travel in a dump.
read -r -d '' SQL_SETTINGS <<'SQL' || true
SELECT name,
       CASE WHEN name ~* '(password|passphrase|conninfo|secret|key)' THEN '(value hidden)'
            ELSE setting END
  FROM pg_settings
 WHERE source = 'configuration file'
 ORDER BY 1
SQL

read -r -d '' SQL_HBA <<'SQL' || true
SELECT type, array_to_string(database, ','), array_to_string(user_name, ','),
       coalesce(address, ''), coalesce(netmask, ''), auth_method,
       coalesce(array_to_string(options, ','), '')
  FROM pg_hba_file_rules
 ORDER BY line_number
SQL

prefix() { awk -v p="$1" '{ print p "|" $0 }'; }

# 17 authenticates with scram-sha-256 (the pg_hba.conf the image writes), so
# a role whose password is still an md5 hash could not log in after the move.
refuse_md5() {   # refuse_md5 ROLES_FILE
  if awk -F'|' '$10 == "md5" || $10 == "other" { found = 1 } END { exit !found }' "$1"; then
    die "a role has an md5 or unrecognised password hash, which PostgreSQL $DST_MAJOR would refuse; reset its password on $SRC_MAJOR first (see the runbook)"
  fi
}

inventory() {   # inventory CONTAINER OUTPUT_PREFIX
  local c=$1 out=$2 db dbs
  q "$c" postgres "$SQL_DATABASES" > "$out.databases"
  q "$c" postgres "$SQL_ROLES"     > "$out.roles"
  q "$c" postgres "$SQL_SETTINGS"  > "$out.settings"
  q "$c" postgres "$SQL_HBA"       > "$out.hba"
  : > "$out.rows"; : > "$out.sequences"; : > "$out.objects"; : > "$out.extensions"
  mapfile -t dbs < <(q "$c" postgres 'SELECT datname FROM pg_database WHERE datallowconn ORDER BY 1')
  for db in "${dbs[@]}"; do
    q "$c" "$db" "$SQL_ROWS"       | prefix "$db" >> "$out.rows"
    q "$c" "$db" "$SQL_SEQUENCES"  | prefix "$db" >> "$out.sequences"
    q "$c" "$db" "$SQL_OBJECTS"    | prefix "$db" >> "$out.objects"
    q "$c" "$db" "$SQL_EXTENSIONS" | prefix "$db" >> "$out.extensions"
  done
}

summarise() {   # summarise OUTPUT_PREFIX
  awk -F'|' '{ t[$1]++; n[$1] += $3 }
             END { for (d in t) printf "  %-14s %4d tables, %d rows\n", d ":", t[d], n[d] }' "$1.rows" | sort
  awk -F'|' '$2 == "public.databasechangelog" { printf "  %-14s databasechangelog: %d changesets\n", $1 ":", $3 }' "$1.rows"
}

# The difference between two inventory files, for display. diff exits 1 when
# they differ, which pipefail would turn into a failure of the whole script.
show_diff() {   # show_diff A B MAX LABEL_A LABEL_B
  { diff "$1" "$2" || true; } | grep '^[<>]' | head -"$3" \
    | sed "s/^</    $4/; s/^>/    $5/" || true
}

compare() {   # compare LABEL BEFORE AFTER: 0 when identical, else prints the difference
  if cmp -s "$2" "$3"; then ok "$1: identical ($(wc -l < "$2") lines)"; return 0; fi
  bad "$1 differ:"
  show_diff "$2" "$3" 40 'before:' 'after: '
  return 1
}

report_diff() {   # report_diff LABEL BEFORE AFTER: differences are reported, not fatal
  if cmp -s "$2" "$3"; then ok "$1: same"; return 0; fi
  warn "$1 differ (old / new server):"
  show_diff "$2" "$3" 20 'old:' 'new:'
}

# ------------------------------------------------------------------ traps ---
PHASE=preflight
ERR_LINE=""
NEW_C=""
TIMER_PAUSED=0
SRC_MAJOR="?"
NEW_STAGE=""

on_exit() {
  local rc=$?
  set +e
  trap - ERR
  if [ -n "$NEW_C" ] && docker inspect "$NEW_C" >/dev/null 2>&1; then
    docker stop -t 120 "$NEW_C" >/dev/null 2>&1
    docker rm -fv "$NEW_C" >/dev/null 2>&1
  fi
  if [ "$TIMER_PAUSED" = 1 ]; then
    if systemctl start "$BACKUP_TIMER" >/dev/null 2>&1; then
      say "resumed $BACKUP_TIMER"
    else
      warn "could not resume the nightly backup: sudo systemctl start $BACKUP_TIMER"
    fi
  fi
  [ "$rc" -eq 0 ] && return
  echo
  bad "stopped during: ${PHASE}${ERR_LINE:+ (line $ERR_LINE)}"
  case "$PHASE" in
    preflight|rollback-preflight)
      say "nothing was changed." ;;
    *)
      say "the live data directory $PG_DATA_DIR was not touched: it is still PostgreSQL ${SRC_MAJOR}."
      say "bring production back as it was:  $(restart_cmd)"
      if [ -n "$NEW_STAGE" ] && [ -e "$NEW_STAGE" ]; then
        say "the incomplete new cluster is in $NEW_STAGE; remove it before the next attempt:"
        say "  sudo rm -rf $NEW_STAGE"
      fi ;;
  esac
}
trap 'ERR_LINE=$LINENO' ERR
trap on_exit EXIT

# -------------------------------------------------------------- preflight ---
preflight_common() {
  [ "$(id -u)" = 0 ] || die "run as root (sudo bash $0)"
  local t err src
  for t in docker gzip sha256sum numfmt awk sed diff cmp du df stat mountpoint; do
    command -v "$t" >/dev/null 2>&1 || die "$t is not installed"
  done
  docker compose version >/dev/null 2>&1 || die "docker compose (v2) is not available"
  [ -r "$ENV_FILE" ] || die "cannot read $ENV_FILE"
  for t in $COMPOSE_FILES; do
    [ -r "${INSTALL_PREFIX}/${t}" ] || die "missing ${INSTALL_PREFIX}/${t}"
  done
  if ! err="$(compose config -q 2>&1)"; then
    bad "the compose files do not resolve:"
    sed 's/^/    /' <<<"$err" | head -5
    exit 1
  fi
  src="$(pinned_source)"
  [ "$src" = "$PG_DATA_DIR" ] \
    || die "the db service does not bind $PG_DATA_DIR to $CONTAINER_PGDATA (it uses: ${src:-nothing}); wrong project or layout"
  SU="$(pinned_user)"; SU="${SU:-postgres}"
  [ -r "$PG_DATA_DIR/PG_VERSION" ] || die "$PG_DATA_DIR is not a PostgreSQL data directory (no PG_VERSION)"
}

preflight_upgrade() {
  step "Preflight"
  preflight_common
  [[ "$TARGET_TAG" =~ ^([0-9]+)(\.[0-9]+)?(-[A-Za-z0-9._-]+)?$ ]] \
    || die "TARGET_TAG=$TARGET_TAG does not look like a postgres image tag (e.g. 17-alpine)"
  DST_MAJOR="${BASH_REMATCH[1]}"
  SRC_MAJOR="$(tr -d '[:space:]' < "$PG_DATA_DIR/PG_VERSION")"
  OLD_KEEP="${PG_DATA_DIR}-${SRC_MAJOR}"
  NEW_STAGE="${PG_DATA_DIR}-${DST_MAJOR}"

  [ "$SRC_MAJOR" -le "$DST_MAJOR" ] \
    || die "$PG_DATA_DIR is PostgreSQL $SRC_MAJOR, newer than $TARGET_IMAGE; this script does not downgrade"
  [ "$SRC_MAJOR" -lt "$DST_MAJOR" ] \
    || die "$PG_DATA_DIR is already PostgreSQL $SRC_MAJOR; nothing to upgrade. (If this script switched it, the final step is to set ${PIN_VAR}=${TARGET_TAG} in $ENV_FILE and restart.)"
  local pinned; pinned="$(pinned_image)"
  [ "$(image_major "$pinned")" = "$SRC_MAJOR" ] \
    || die "compose would start $pinned on $PG_DATA_DIR, which is PostgreSQL $SRC_MAJOR; the pin and the data disagree"
  [ ! -e "$OLD_KEEP" ] || die "$OLD_KEEP already exists (an earlier upgrade's old cluster?); move it out of the way first"
  [ ! -e "$NEW_STAGE" ] \
    || die "$NEW_STAGE already exists: an incomplete cluster from an earlier attempt. Look, then remove it: sudo rm -rf $NEW_STAGE"
  if mountpoint -q "$PG_DATA_DIR" || [ "$(stat -c %d "$PG_DATA_DIR")" != "$(stat -c %d "$(dirname "$PG_DATA_DIR")")" ]; then
    die "$PG_DATA_DIR is a mount point; the switch renames it, so this layout needs a hand-made plan"
  fi

  DB_C="$(db_container)"
  if [ -n "$DB_C" ] && is_running "$DB_C"; then
    check_mount "$DB_C"
    local running_major; running_major="$(server_major "$DB_C")"
    [ "$running_major" = "$SRC_MAJOR" ] \
      || die "the running db container is PostgreSQL $running_major, the data directory $SRC_MAJOR"
    ok "db container $(docker inspect -f '{{.Name}}' "$DB_C" | tr -d /) runs PostgreSQL $(q "$DB_C" postgres 'SHOW server_version')"
    local roles; roles="$(mktemp)"
    q "$DB_C" postgres "$SQL_ROLES" > "$roles"
    refuse_md5 "$roles"
    rm -f "$roles"
  else
    say "the db container is not running; the upgrade will start it on its own"
  fi

  # The target image: pulled now, so the day does not depend on Docker Hub,
  # and checked to keep its data where the production overlay binds it
  # (the 18 images moved PGDATA, which this script and the overlay do not
  # handle).
  if ! docker image inspect "$TARGET_IMAGE" >/dev/null 2>&1; then
    docker pull -q "$TARGET_IMAGE" >/dev/null || die "cannot pull $TARGET_IMAGE"
  fi
  local env; env="$(docker image inspect -f '{{range .Config.Env}}{{println .}}{{end}}' "$TARGET_IMAGE")"
  [ "$(sed -n 's/^PG_MAJOR=//p' <<<"$env")" = "$DST_MAJOR" ] || die "$TARGET_IMAGE is not PostgreSQL $DST_MAJOR"
  [ "$(sed -n 's/^PGDATA=//p' <<<"$env")" = "$CONTAINER_PGDATA" ] \
    || die "$TARGET_IMAGE keeps its data outside $CONTAINER_PGDATA, where the overlay binds it"
  ok "target image $TARGET_IMAGE (PostgreSQL $(sed -n 's/^PG_VERSION=//p' <<<"$env"))"

  # Disk: the new cluster needs about what the old one uses (a restore is
  # usually smaller, being free of bloat) plus room for the WAL the restore
  # writes; the gzip dump is generously estimated at half the old size.
  local data_bytes need_new need_dump data_fs dump_fs free_new free_dump
  data_bytes="$(du -sb "$PG_DATA_DIR" | cut -f1)"
  need_new=$(( data_bytes + 1024 * 1024 * 1024 ))
  need_dump=$(( data_bytes / 2 ))
  data_fs="$(stat -c %d "$(dirname "$PG_DATA_DIR")")"
  dump_fs="$(stat -c %d "$BACKUP_DIR")"
  free_new="$(df -PB1 "$(dirname "$PG_DATA_DIR")" | awk 'NR == 2 { print $4 }')"
  free_dump="$(df -PB1 "$BACKUP_DIR" | awk 'NR == 2 { print $4 }')"
  say "data directory: $(human "$data_bytes") in $PG_DATA_DIR"
  if [ "$data_fs" = "$dump_fs" ]; then
    [ "$free_new" -ge $(( need_new + need_dump )) ] \
      || die "not enough disk: needs about $(human $(( need_new + need_dump ))) on $(dirname "$PG_DATA_DIR"), $(human "$free_new") free"
    ok "disk: needs about $(human $(( need_new + need_dump ))), $(human "$free_new") free"
  else
    [ "$free_new" -ge "$need_new" ] \
      || die "not enough disk for the new cluster: needs about $(human "$need_new") beside $PG_DATA_DIR, $(human "$free_new") free"
    [ "$free_dump" -ge "$need_dump" ] \
      || die "not enough disk for the dump: needs about $(human "$need_dump") in $BACKUP_DIR, $(human "$free_dump") free"
    ok "disk: about $(human "$need_new") of $(human "$free_new") beside the data, $(human "$need_dump") of $(human "$free_dump") for the dump"
  fi

  if have_unit "${BACKUP_TIMER%.timer}.service" && systemctl is-active --quiet "${BACKUP_TIMER%.timer}.service"; then
    die "the nightly backup is running right now; wait until it has finished"
  fi
  ok "plan: PostgreSQL $SRC_MAJOR -> $DST_MAJOR, built in $NEW_STAGE; the old directory is kept as $OLD_KEEP"
}

# ------------------------------------------------------------------ upgrade ---
upgrade() {   # upgrade REPORT_DIR
  local work=$1 dump

  PHASE="stopping the stack"
  step "Stopping everything but the database"
  if have_unit "$BACKUP_TIMER" && systemctl is-active --quiet "$BACKUP_TIMER"; then
    systemctl stop "$BACKUP_TIMER"
    TIMER_PAUSED=1
    ok "paused $BACKUP_TIMER until this script ends"
  fi
  local others=()
  mapfile -t others < <(running_others)
  SERVICES="db${others[*]:+ ${others[*]}}"
  if [ "${#others[@]}" -gt 0 ]; then
    compose stop -t 60 "${others[@]}"
    ok "stopped: ${others[*]}"
  else
    say "no other service was running"
  fi
  DB_C="$(db_container)"
  if [ -z "$DB_C" ] || ! is_running "$DB_C"; then
    compose up -d --no-deps db
    DB_C="$(db_container)"
  fi
  check_mount "$DB_C"
  wait_ready "$DB_C" || die "the PostgreSQL $SRC_MAJOR server does not answer"
  [ "$(server_major "$DB_C")" = "$SRC_MAJOR" ] || die "the db container is not PostgreSQL $SRC_MAJOR"
  [ "$(q "$DB_C" postgres 'SELECT rolname FROM pg_roles WHERE oid = 10')" = "$SU" ] \
    || die "POSTGRES_USER ($SU) is not the cluster's bootstrap superuser"
  local sql_clients="SELECT count(*) FROM pg_stat_activity WHERE backend_type = 'client backend' AND pid <> pg_backend_pid()"
  local clients=""
  for _ in $(seq 1 60); do
    clients="$(q "$DB_C" postgres "$sql_clients")"
    [ "$clients" = 0 ] && break
    sleep 2
  done
  if [ "$clients" != 0 ]; then
    bad "clients are still connected to the database:"
    q "$DB_C" postgres "SELECT usename, coalesce(nullif(application_name, ''), '-'), coalesce(client_addr::text, 'local'), state
                          FROM pg_stat_activity WHERE backend_type = 'client backend' AND pid <> pg_backend_pid()" \
      | sed 's/^/    /'
    die "stop them first; the dump must be the last state of the database"
  fi
  ok "no client is connected to PostgreSQL $SRC_MAJOR"

  PHASE="inventory of the old cluster"
  step "Inventory of PostgreSQL $SRC_MAJOR"
  local enc coll ctype checksums initdb_args
  IFS='|' read -r enc coll ctype < <(q "$DB_C" postgres \
    "SELECT pg_encoding_to_char(encoding), datcollate, datctype FROM pg_database WHERE datname = 'template1'")
  checksums="$(q "$DB_C" postgres 'SHOW data_checksums')"
  initdb_args="--encoding=${enc} --lc-collate=${coll} --lc-ctype=${ctype}"
  [ "$checksums" = on ] && initdb_args="$initdb_args --data-checksums"
  inventory "$DB_C" "$work/before"
  summarise "$work/before"
  refuse_md5 "$work/before.roles"

  PHASE="dump"
  step "Dump"
  dump="$work/cluster-pg${SRC_MAJOR}.sql.gz"
  # --quote-all-identifiers: the dump is read by another major version, whose
  # reserved words may differ from the ones this pg_dumpall quotes.
  docker exec "$DB_C" pg_dumpall -U "$SU" --quote-all-identifiers 2> "$work/pg_dumpall.stderr" \
    | gzip -6 > "$dump.partial"
  if [ -s "$work/pg_dumpall.stderr" ]; then
    warn "pg_dumpall said (it exited 0):"
    sed 's/^/    /' "$work/pg_dumpall.stderr" | head -10
  fi
  local tail_lines; tail_lines="$(gzip -dc "$dump.partial" | tail -n 3)"
  grep -q 'PostgreSQL database cluster dump complete' <<<"$tail_lines" \
    || die "the dump does not end with pg_dumpall's completion line; it is incomplete"
  mv -T "$dump.partial" "$dump"
  chmod 0600 "$dump"
  (cd "$work" && sha256sum "$(basename "$dump")" > "$(basename "$dump").sha256")
  ok "dump verified (gzip intact, complete): $dump ($(human "$(stat -c %s "$dump")"))"
  say "sha256 $(cut -d' ' -f1 < "$dump.sha256")"

  PHASE="stopping the old server"
  compose stop -t 300 db
  [ ! -e "$PG_DATA_DIR/postmaster.pid" ] \
    || die "postmaster.pid is still in $PG_DATA_DIR: PostgreSQL $SRC_MAJOR did not shut down cleanly"
  ok "PostgreSQL $SRC_MAJOR stopped cleanly; $PG_DATA_DIR is left as it is"

  PHASE="initialising the new cluster"
  step "PostgreSQL $DST_MAJOR in $NEW_STAGE"
  install -d -m 0700 "$NEW_STAGE"
  NEW_C="${COMPOSE_PROJECT}-pg-major-upgrade"
  docker rm -fv "$NEW_C" >/dev/null 2>&1 || true
  POSTGRES_PASSWORD="$(gen_secret)" docker run -d --name "$NEW_C" --network none \
    --mount "type=bind,source=${NEW_STAGE},target=${CONTAINER_PGDATA}" \
    -e POSTGRES_USER="$SU" -e POSTGRES_DB=postgres -e POSTGRES_PASSWORD \
    -e POSTGRES_INITDB_ARGS="$initdb_args" \
    "$TARGET_IMAGE" >/dev/null
  wait_ready "$NEW_C" fresh || die "the new server did not come up"
  [ "$(server_major "$NEW_C")" = "$DST_MAJOR" ] || die "the new server is not PostgreSQL $DST_MAJOR"
  ok "initialised PostgreSQL $(q "$NEW_C" postgres 'SHOW server_version') ($initdb_args)"

  PHASE="restore"
  # pg_dumpall always creates the roles, so on a fresh cluster the bootstrap
  # superuser's CREATE ROLE fails ("already exists") and its ALTER ROLE
  # carries the attributes and the password hash across. Any other error
  # stops the upgrade.
  gzip -dc "$dump" | docker exec -i "$NEW_C" psql -X -q -U "$SU" -d postgres >/dev/null 2> "$work/restore.stderr"
  grep -E '(^|: )ERROR:' "$work/restore.stderr" | grep -vF "role \"${SU}\" already exists" > "$work/restore.errors" || true
  if [ -s "$work/restore.errors" ]; then
    bad "the restore reported errors:"
    head -10 "$work/restore.errors" | sed 's/^/    /'
    die "not switching; the full list is in $work/restore.errors"
  fi
  ok "restored ($(grep -cE '(^|: )(NOTICE|WARNING):' "$work/restore.stderr" || true) notices, no unexpected errors)"
  docker exec "$NEW_C" vacuumdb -U "$SU" --all --analyze-only --quiet
  ok "ANALYZE done"

  PHASE="verification"
  step "Comparing PostgreSQL $DST_MAJOR with $SRC_MAJOR"
  inventory "$NEW_C" "$work/after"
  local failed=0
  compare "row counts, every table"  "$work/before.rows"      "$work/after.rows"      || failed=1
  compare "sequence values"          "$work/before.sequences" "$work/after.sequences" || failed=1
  compare "schema objects"           "$work/before.objects"   "$work/after.objects"   || failed=1
  compare "roles"                    "$work/before.roles"     "$work/after.roles"     || failed=1
  compare "databases"                "$work/before.databases" "$work/after.databases" || failed=1
  [ "$failed" = 0 ] || die "the new cluster does not match the old one; nothing was switched (reports in $work)"
  summarise "$work/after"
  report_diff "extension versions" "$work/before.extensions" "$work/after.extensions"
  report_diff "server settings from the configuration files" "$work/before.settings" "$work/after.settings"
  report_diff "pg_hba.conf rules" "$work/before.hba" "$work/after.hba"

  PHASE="stopping the new server"
  docker stop -t 300 "$NEW_C" >/dev/null
  [ ! -e "$NEW_STAGE/postmaster.pid" ] || die "PostgreSQL $DST_MAJOR did not shut down cleanly"
  docker rm -v "$NEW_C" >/dev/null
  NEW_C=""

  PHASE="switching the directories"
  # Two renames in one directory. Signals are held off in between, so the
  # data directory never goes missing: compose would bind an empty one, and
  # PostgreSQL would initialise a new, empty database in it.
  trap '' HUP INT TERM QUIT
  mv -T "$PG_DATA_DIR" "$OLD_KEEP"
  if ! mv -T "$NEW_STAGE" "$PG_DATA_DIR"; then
    mv -T "$OLD_KEEP" "$PG_DATA_DIR"
    trap - HUP INT TERM QUIT
    die "could not move $NEW_STAGE into place; $PG_DATA_DIR is back as it was"
  fi
  trap - HUP INT TERM QUIT
  NEW_STAGE=""
  PHASE="done"
  # Owned like the nightly dumps beside it (still 0700 and 0600).
  if id -u libreclinica >/dev/null 2>&1; then chown -R libreclinica:libreclinica "$work"; fi
  ok "$PG_DATA_DIR is PostgreSQL $DST_MAJOR; the untouched $SRC_MAJOR directory is $OLD_KEEP"

  step "Final step: switch production to PostgreSQL $DST_MAJOR"
  say "The stack stays down until you run:"
  echo
  say "  sudo sed -i '/^${PIN_VAR}=/d' $ENV_FILE"
  say "  echo '${PIN_VAR}=${TARGET_TAG}' | sudo tee -a $ENV_FILE >/dev/null"
  say "  $(restart_cmd)"
  echo
  say "Then check the version, the health endpoint, and sign in:"
  say "  sudo docker exec ${COMPOSE_PROJECT}-db-1 psql -U $SU -d postgres -Atc 'SHOW server_version'"
  say "  curl -fsS http://127.0.0.1:8080/LibreClinica/actuator/health"
  echo
  say "Before the switch, a restart fails safely: PostgreSQL $SRC_MAJOR refuses the $DST_MAJOR directory."
  say "To go back to $SRC_MAJOR (now, or after the switch while users are still out):"
  say "  sudo bash $INSTALL_PREFIX/deploy/pg-major-upgrade.sh --rollback"
  say "Reports and the dump: $work"
}

# ----------------------------------------------------------------- rollback ---
rollback() {
  PHASE="rollback-preflight"
  step "Rollback preflight"
  preflight_common
  local cur old_major="" old_keep="" d v n
  cur="$(tr -d '[:space:]' < "$PG_DATA_DIR/PG_VERSION")"
  SRC_MAJOR="$cur"
  for d in "${PG_DATA_DIR}"-[0-9]*; do
    [ -d "$d" ] && [ -r "$d/PG_VERSION" ] || continue
    v="$(tr -d '[:space:]' < "$d/PG_VERSION")"
    [ "$d" = "${PG_DATA_DIR}-${v}" ] && [ "$v" -lt "$cur" ] || continue
    [ -z "$old_keep" ] || die "more than one older cluster beside $PG_DATA_DIR ($old_keep, $d); roll back by hand"
    old_keep="$d"; old_major="$v"
  done
  [ -n "$old_keep" ] || die "no older cluster beside $PG_DATA_DIR (looked for ${PG_DATA_DIR}-<major>); nothing to roll back to"
  ok "$PG_DATA_DIR is PostgreSQL $cur; $old_keep is PostgreSQL $old_major"

  # Rolling back discards whatever was written to the new cluster since the
  # upgrade. When it is running, compare it with the inventory the upgrade
  # recorded, and refuse unless that loss was asked for.
  # The newest upgrade's inventory (the directory names sort by time).
  local after="" f
  for f in "$BACKUP_DIR"/pg-major-upgrade-*/after.rows; do [ -e "$f" ] && after="$f"; done
  DB_C="$(db_container)"
  if [ -n "$DB_C" ] && is_running "$DB_C"; then check_mount "$DB_C"; fi
  if [ -n "$DB_C" ] && is_running "$DB_C" && [ "$(server_major "$DB_C" 2>/dev/null)" = "$cur" ]; then
    if [ -z "$after" ]; then
      [ "$DISCARD" = 1 ] || die "no upgrade inventory in $BACKUP_DIR to compare with; add --discard-changes to roll back anyway"
    else
      local now dbs db
      now="$(mktemp)"
      mapfile -t dbs < <(q "$DB_C" postgres 'SELECT datname FROM pg_database WHERE datallowconn ORDER BY 1')
      for db in "${dbs[@]}"; do q "$DB_C" "$db" "$SQL_ROWS" | prefix "$db"; done > "$now"
      if cmp -s "$after" "$now"; then
        ok "PostgreSQL $cur holds exactly what the upgrade restored; rolling back loses nothing"
      else
        n="$({ diff "$after" "$now" || true; } | grep -c '^>' || true)"
        warn "PostgreSQL $cur has changed since the upgrade, in $n table(s) (upgrade rows / now):"
        show_diff "$after" "$now" 40 'upgrade:' 'now:    '
        if [ "$DISCARD" != 1 ]; then
          rm -f "$now"
          die "rolling back loses those changes; run again with --rollback --discard-changes if that is intended"
        fi
        warn "--discard-changes: they will not be in PostgreSQL $old_major"
      fi
      rm -f "$now"
    fi
  else
    say "the db container is not running PostgreSQL $cur; nothing to compare"
  fi

  PHASE="stopping the stack"
  step "Rolling back to PostgreSQL $old_major"
  local others=()
  mapfile -t others < <(running_others)
  SERVICES="db${others[*]:+ ${others[*]}}"
  [ "${#others[@]}" -eq 0 ] || compose stop -t 60 "${others[@]}"
  compose stop -t 300 db 2>/dev/null || true
  DB_C="$(db_container)"
  if [ -n "$DB_C" ] && is_running "$DB_C"; then die "the db container is still running; nothing was moved"; fi
  [ ! -e "$PG_DATA_DIR/postmaster.pid" ] \
    || die "postmaster.pid is in $PG_DATA_DIR: its server did not shut down cleanly (or still runs); nothing was moved"
  local aside="${PG_DATA_DIR}-${cur}.rolled-back-${STAMP}"
  PHASE="switching the directories"
  trap '' HUP INT TERM QUIT
  mv -T "$PG_DATA_DIR" "$aside"
  if ! mv -T "$old_keep" "$PG_DATA_DIR"; then
    mv -T "$aside" "$PG_DATA_DIR"
    trap - HUP INT TERM QUIT
    die "could not move $old_keep back; $PG_DATA_DIR is unchanged (PostgreSQL $cur)"
  fi
  trap - HUP INT TERM QUIT
  PHASE="done"
  ok "$PG_DATA_DIR is PostgreSQL $old_major again; the $cur cluster is kept as $aside"

  step "Final step: start production on PostgreSQL $old_major"
  if [ -n "$(sed -n "s/^${PIN_VAR}=//p" "$ENV_FILE" | tail -1)" ]; then
    say "  sudo sed -i '/^${PIN_VAR}=/d' $ENV_FILE"
  fi
  say "  $(restart_cmd)"
  say "  sudo docker exec ${COMPOSE_PROJECT}-db-1 psql -U $SU -d postgres -Atc 'SHOW server_version'   # expect $old_major.x"
}

case "$MODE" in
  check)
    preflight_upgrade
    echo
    ok "preflight passed; nothing was changed"
    ;;
  upgrade)
    # Everything from here on is also written to the report directory.
    [ -d "$BACKUP_DIR" ] || die "no backup directory at $BACKUP_DIR"
    WORK="${BACKUP_DIR}/pg-major-upgrade-${STAMP}"
    install -d -m 0700 "$WORK"
    exec > >(tee -a "$WORK/upgrade.log") 2>&1
    preflight_upgrade
    upgrade "$WORK"
    ;;
  rollback)
    [ -d "$BACKUP_DIR" ] || die "no backup directory at $BACKUP_DIR"
    exec > >(tee -a "${BACKUP_DIR}/pg-major-rollback-${STAMP}.log") 2>&1
    rollback
    ;;
esac
