#!/usr/bin/env bash
# ------------------------------------------------------------------------------
# LibreClinicaMUW — production host setup for Ubuntu 24.04 LTS (Noble)
#
# Idempotent. Re-run any time the host configuration drifts; every block guards
# itself against doing the work twice.
#
# What it does:
#   1. Preflight (root, OS check, network)
#   2. System packages + unattended-upgrades
#   3. Timezone Europe/Vienna
#   4. Docker Engine + Compose v2 (official Docker apt repo)
#   5. Deploy user `libreclinica` (no shell, member of docker group)
#   6. Stack tree under /opt/libreclinica + /etc/libreclinica
#   7. systemd unit `libreclinica.service` that brings the compose stack up
#   8. Nightly pg_dump backup via systemd timer to /var/backups/libreclinica
#   9. Log rotation for backup files
#
# What it does NOT do:
#   - TLS termination: the MUW institutional reverse proxy is expected to
#     front this host on the internal network. If you need local TLS,
#     add nginx separately.
#   - Host-level firewall / SSH hardening / fail2ban: the production VM
#     is reachable only via the MUW internal network. Network-level
#     access control sits at the institutional perimeter; host-level
#     hardening was removed to keep the deploy script minimal.
#   - Pull the libreclinica image. The first `systemctl start libreclinica`
#     after setup will pull from ghcr.io/luviku/libreclinicamuw:<tag>. Make
#     sure the image exists (run the `Release image` workflow on GitHub
#     against a tagged main release) before starting the stack.
#   - Configure institutional SMTP. After the first start, edit
#     /opt/libreclinica/config/datainfo.properties (mailHost / mailPort /
#     mailUsername / mailPassword) and restart. See deploy/README.md.
#
# Usage:
#   sudo bash deploy/setup-ubuntu-host.sh \
#     --image-tag v1.4.0-muw \
#     --trusted-cidrs '128.131.0.0/16,10.0.0.0/8'
#
# All knobs can also be set via env vars before invocation; flags override.
# `--trusted-cidrs` is consumed by the SSO header-trust filter
# (LIBRECLINICA_SSO_TRUSTED_CIDRS in /etc/libreclinica/env), not by any
# host-level firewall — there isn't one.
#
# Tested against: Ubuntu 24.04 LTS (Noble Numbat), kernel 6.8+
# ------------------------------------------------------------------------------

set -euo pipefail

# ----------------------------- defaults ---------------------------------------

: "${LIBRECLINICA_IMAGE_TAG:=latest}"
: "${LIBRECLINICA_TRUSTED_CIDRS:=}"      # comma-separated list, e.g. "10.0.0.0/8,192.168.0.0/16" — consumed by SSO header trust
: "${LIBRECLINICA_TIMEZONE:=Europe/Vienna}"
: "${LIBRECLINICA_HOST_PORT:=8080}"      # the host port the reverse proxy targets
: "${LIBRECLINICA_REPO_URL:=https://github.com/LuViKu/LibreClinicaMUW.git}"
: "${LIBRECLINICA_REPO_REF:=main}"       # branch/tag the deploy tree clones to /opt/libreclinica
: "${LIBRECLINICA_BACKUP_RETENTION_DAYS:=30}"
: "${LIBRECLINICA_GHCR_USER:=LuViKu}"    # GitHub username the PAT belongs to
: "${LIBRECLINICA_GHCR_TOKEN:=}"         # fine-grained PAT: 'Contents: read' on the repo + 'Packages: read' on the org. See deploy/README.md for minting instructions.

INSTALL_PREFIX=/opt/libreclinica
CONFIG_DIR=/etc/libreclinica
ENV_FILE=${CONFIG_DIR}/env
BACKUP_DIR=/var/backups/libreclinica
PG_DATA_DIR=/var/lib/libreclinica/postgres
E2E_UPLOADS_DIR=/var/lib/libreclinica/e2e-uploads
RETINAL_OUTPUT_DIR=/var/lib/libreclinica/retinal-inference

# ----------------------------- arg parsing ------------------------------------

while [[ $# -gt 0 ]]; do
  case "$1" in
    --image-tag)        LIBRECLINICA_IMAGE_TAG="$2"; shift 2 ;;
    --trusted-cidrs)    LIBRECLINICA_TRUSTED_CIDRS="$2"; shift 2 ;;
    --timezone)         LIBRECLINICA_TIMEZONE="$2"; shift 2 ;;
    --host-port)        LIBRECLINICA_HOST_PORT="$2"; shift 2 ;;
    --repo-ref)         LIBRECLINICA_REPO_REF="$2"; shift 2 ;;
    --backup-days)      LIBRECLINICA_BACKUP_RETENTION_DAYS="$2"; shift 2 ;;
    --ghcr-user)        LIBRECLINICA_GHCR_USER="$2"; shift 2 ;;
    --ghcr-token)       LIBRECLINICA_GHCR_TOKEN="$2"; shift 2 ;;
    -h|--help)
      sed -n '/^# ---/,/^# ---/p' "$0" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *) echo "Unknown flag: $1" >&2; exit 2 ;;
  esac
done

# ----------------------------- helpers ----------------------------------------

log()  { printf '\033[1;32m[setup]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[warn ]\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31m[fail ]\033[0m %s\n' "$*" >&2; exit 1; }

# Run a command idempotently — log what we're doing and let the command
# decide if there's actual work to do.
section() { printf '\n\033[1;36m=== %s ===\033[0m\n' "$*"; }

# Generate a 32-char alphanumeric secret. Used once per host for the
# Postgres password — written to /etc/libreclinica/env on first run and
# never regenerated, so re-running this script doesn't lock the DB out.
gen_secret() { tr -dc 'A-Za-z0-9' </dev/urandom | head -c 32; }

# ----------------------------- preflight --------------------------------------

section "Preflight"

[[ $EUID -eq 0 ]] || die "Run as root (sudo bash $0)"

if ! grep -q '^VERSION_ID="24\.' /etc/os-release; then
  warn "This script targets Ubuntu 24.04 LTS. Detected:"
  warn "  $(grep PRETTY_NAME /etc/os-release | cut -d= -f2-)"
  warn "Proceed with caution."
fi

# On a re-run, the env file already has the GHCR creds — re-read them
# so the operator doesn't have to pass --ghcr-token every time.
if [[ -z "$LIBRECLINICA_GHCR_TOKEN" && -s "$ENV_FILE" ]]; then
  set +u
  # shellcheck disable=SC1090
  source <(grep -E '^LIBRECLINICA_GHCR_(USER|TOKEN)=' "$ENV_FILE")
  set -u
fi

# Hard requirement: the repo + GHCR are private. Both git clone and
# `docker pull` will fail without auth. Fail loud now rather than
# 15 minutes into the build.
if [[ -z "$LIBRECLINICA_GHCR_TOKEN" ]]; then
  die "No --ghcr-token set (and none in ${ENV_FILE}). The private repo + GHCR pulls require a fine-grained PAT — see deploy/README.md § 'Provisioning the GHCR token' for the minting recipe."
fi

# Validate the PAT against the GitHub API before we burn time on
# `apt upgrade`. Cheap call, surfaces typo / expired-token / wrong-scope
# errors with a clear message.
log "Validating GitHub PAT against api.github.com/repos/LuViKu/LibreClinicaMUW …"
http_code=$(curl -fsS -o /dev/null -w '%{http_code}' \
  -H "Authorization: Bearer ${LIBRECLINICA_GHCR_TOKEN}" \
  -H "Accept: application/vnd.github+json" \
  -H "X-GitHub-Api-Version: 2022-11-28" \
  "https://api.github.com/repos/LuViKu/LibreClinicaMUW" 2>/dev/null || true)
case "$http_code" in
  200) log "PAT validated (HTTP 200)" ;;
  401) die "PAT validation failed: HTTP 401 Unauthorized. Token is invalid or expired. Mint a new one (deploy/README.md)." ;;
  403) die "PAT validation failed: HTTP 403 Forbidden. Token lacks 'Contents: read' permission on the repo. Re-mint with the correct repo permission." ;;
  404) die "PAT validation failed: HTTP 404. Either the token can't see the repo (missing repository access on the fine-grained PAT) or the repo URL is wrong (got ${LIBRECLINICA_REPO_URL})." ;;
  *)   die "PAT validation failed: HTTP ${http_code} from api.github.com. Check network access + token validity." ;;
esac

log "OS:                $(grep PRETTY_NAME /etc/os-release | cut -d= -f2- | tr -d '"')"
log "Image tag:         ghcr.io/luviku/libreclinicamuw:${LIBRECLINICA_IMAGE_TAG}"
log "GHCR user:         ${LIBRECLINICA_GHCR_USER}"
log "Trusted CIDRs (SSO): ${LIBRECLINICA_TRUSTED_CIDRS:-<none>}"
log "Timezone:          ${LIBRECLINICA_TIMEZONE}"

# ----------------------------- system packages --------------------------------

section "System update + base packages"

export DEBIAN_FRONTEND=noninteractive

apt-get update -qq
apt-get upgrade -y -qq
apt-get install -y -qq \
  ca-certificates curl gnupg lsb-release \
  unattended-upgrades \
  jq git rsync htop ncdu \
  postgresql-client-16 \
  logrotate \
  apparmor apparmor-utils

# ----------------------------- timezone ---------------------------------------

section "Timezone"

current_tz=$(timedatectl show --property=Timezone --value 2>/dev/null || echo unknown)
if [[ "$current_tz" != "$LIBRECLINICA_TIMEZONE" ]]; then
  log "Setting timezone: $current_tz → $LIBRECLINICA_TIMEZONE"
  timedatectl set-timezone "$LIBRECLINICA_TIMEZONE"
else
  log "Timezone already $LIBRECLINICA_TIMEZONE"
fi

# ----------------------------- unattended-upgrades ----------------------------

section "Unattended security upgrades"

cat >/etc/apt/apt.conf.d/52libreclinica-unattended-upgrades <<'EOF'
// Managed by deploy/setup-ubuntu-host.sh — edit there, not here.
APT::Periodic::Update-Package-Lists "1";
APT::Periodic::Unattended-Upgrade "1";
APT::Periodic::AutocleanInterval "7";

Unattended-Upgrade::Allowed-Origins {
    "${distro_id}:${distro_codename}-security";
    "${distro_id}ESMApps:${distro_codename}-apps-security";
    "${distro_id}ESM:${distro_codename}-infra-security";
};

Unattended-Upgrade::Automatic-Reboot "false";
Unattended-Upgrade::Automatic-Reboot-WithUsers "false";
Unattended-Upgrade::Remove-Unused-Kernel-Packages "true";
Unattended-Upgrade::Remove-Unused-Dependencies "true";
EOF
systemctl enable --now unattended-upgrades.service >/dev/null 2>&1 || true

# ----------------------------- Docker -----------------------------------------

section "Docker Engine + Compose v2"

if ! command -v docker >/dev/null; then
  install -m 0755 -d /etc/apt/keyrings
  curl -fsSL https://download.docker.com/linux/ubuntu/gpg \
    | gpg --dearmor -o /etc/apt/keyrings/docker.gpg
  chmod a+r /etc/apt/keyrings/docker.gpg

  # shellcheck disable=SC1091
  . /etc/os-release
  printf 'deb [arch=%s signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu %s stable\n' \
    "$(dpkg --print-architecture)" "$VERSION_CODENAME" \
    > /etc/apt/sources.list.d/docker.list

  apt-get update -qq
  apt-get install -y -qq \
    docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

  systemctl enable --now docker.service >/dev/null
  log "Docker installed: $(docker --version)"
else
  log "Docker already installed: $(docker --version)"
fi

# Daemon config: log rotation + live-restore + default address pool that
# avoids the 172.17.0.0/16 default (clashes with some MUW lab subnets).
install -d -m 0755 /etc/docker
cat >/etc/docker/daemon.json <<'EOF'
{
  "log-driver": "json-file",
  "log-opts": { "max-size": "50m", "max-file": "5" },
  "live-restore": true,
  "default-address-pools": [
    { "base": "172.30.0.0/16", "size": 24 }
  ],
  "userland-proxy": false
}
EOF
if ! systemctl is-active --quiet docker; then
  systemctl start docker.service
fi
# `reload` is enough for log-driver + address-pool changes that come up on
# next container restart. live-restore tweaks the daemon process itself.
systemctl restart docker.service

# ----------------------------- GHCR login -------------------------------------

section "GHCR login (ghcr.io)"

# Pipe via stdin so the token never lands in /proc/<pid>/cmdline or in
# `ps auxe`. Stored on disk only in /root/.docker/config.json (mode 0600
# by docker convention) — the systemd unit runs as root so it consumes
# this config when pulling the libreclinica + retinal-inference images.
if printf '%s\n' "$LIBRECLINICA_GHCR_TOKEN" \
     | docker login ghcr.io --username "$LIBRECLINICA_GHCR_USER" --password-stdin >/dev/null; then
  log "Logged in as ${LIBRECLINICA_GHCR_USER} on ghcr.io (config: /root/.docker/config.json)"
else
  die "docker login ghcr.io failed. Check token + 'Packages: read' org permission."
fi

# ----------------------------- deploy user ------------------------------------

section "Deploy user"

if ! id -u libreclinica >/dev/null 2>&1; then
  useradd --system --create-home --home-dir /var/lib/libreclinica/home \
    --shell /usr/sbin/nologin --comment 'LibreClinicaMUW deploy user' \
    --groups docker libreclinica
  log "Created system user 'libreclinica'"
else
  # Make sure the existing user is in the docker group (re-runs).
  usermod -aG docker libreclinica
  log "User 'libreclinica' exists; ensured docker group membership"
fi

# ----------------------------- stack directory --------------------------------

section "Stack directory + repo checkout"

install -d -m 0755 -o libreclinica -g libreclinica "$INSTALL_PREFIX"
install -d -m 0750 -o libreclinica -g libreclinica "$CONFIG_DIR"
install -d -m 0750 -o libreclinica -g libreclinica "$BACKUP_DIR"
install -d -m 0755 -o libreclinica -g libreclinica "$PG_DATA_DIR"
install -d -m 0755 -o libreclinica -g libreclinica "$E2E_UPLOADS_DIR"
install -d -m 0755 -o libreclinica -g libreclinica "$RETINAL_OUTPUT_DIR"

# Clone or update the repo. The production VM needs only:
#   - compose.yaml (root file; pulled in by sparse-checkout's implicit
#     top-level-files behaviour in cone mode)
#   - deploy/ (this script + production overlay + README)
#   - docker/config/ (datainfo.properties + extract.properties seed)
# Everything else (core/, web/, odm/, retinal-inference/, docs/) is built
# elsewhere and pulled from ghcr.io — it has no place on the deploy host.
#
# Implementation: shallow clone + sparse-checkout in cone mode. Cone
# mode auto-includes every top-level file (compose.yaml, Dockerfile, the
# READMEs, etc. — all small text) and only the subdirs we name.
# Working-tree footprint lands around 1-2 MB instead of ~150 MB.
#
# --filter=blob:none is belt-and-braces alongside --depth 1: it makes git
# defer blob downloads for any path not currently checked out. With both
# in place a `sparse-checkout add` later would lazy-fetch only the newly
# needed blobs.
# Auth for the private-repo clone. Use a per-process GIT_ASKPASS helper
# so the token NEVER lands in /opt/libreclinica/.git/config (which is
# world-readable on most setups, and persists across runs). The URL
# embeds the username so git only asks for the password — the askpass
# helper supplies it from the env var that GIT inherits.
#
# Belt-and-braces: GIT_TERMINAL_PROMPT=0 makes git fail loud rather
# than hanging on a TTY prompt if the askpass mechanism breaks.
ASKPASS_HELPER=$(mktemp /tmp/libreclinica-askpass.XXXXXX.sh)
trap 'rm -f "$ASKPASS_HELPER"' EXIT
cat >"$ASKPASS_HELPER" <<'EOF'
#!/usr/bin/env bash
printf '%s' "${LIBRECLINICA_GHCR_TOKEN}"
EOF
chmod 0700 "$ASKPASS_HELPER"
chown libreclinica:libreclinica "$ASKPASS_HELPER"

# Strip any inline credentials the operator may have passed and inject
# the username only; git asks for password → askpass returns the token.
REPO_URL_WITH_USER=$(echo "$LIBRECLINICA_REPO_URL" \
  | sed -E "s#^https://([^@/]*@)?#https://${LIBRECLINICA_GHCR_USER}@#")

SPARSE_PATHS=(deploy docker/config)
if [[ ! -d "${INSTALL_PREFIX}/.git" ]]; then
  log "Sparse-cloning ${LIBRECLINICA_REPO_URL} → ${INSTALL_PREFIX} (paths: ${SPARSE_PATHS[*]})"
  sudo -u libreclinica \
    LIBRECLINICA_GHCR_TOKEN="$LIBRECLINICA_GHCR_TOKEN" \
    GIT_ASKPASS="$ASKPASS_HELPER" \
    GIT_TERMINAL_PROMPT=0 \
    git clone \
      --branch "$LIBRECLINICA_REPO_REF" \
      --depth 1 \
      --filter=blob:none \
      --sparse \
      "$REPO_URL_WITH_USER" "$INSTALL_PREFIX"
  sudo -u libreclinica git -C "$INSTALL_PREFIX" sparse-checkout set "${SPARSE_PATHS[@]}"
  # Rewrite the persisted remote URL so the username isn't embedded
  # on disk (it'd be re-supplied on every fetch by GIT_ASKPASS anyway).
  sudo -u libreclinica git -C "$INSTALL_PREFIX" remote set-url origin "$LIBRECLINICA_REPO_URL"
else
  log "Updating ${INSTALL_PREFIX} (${LIBRECLINICA_REPO_REF})"
  # Re-assert the sparse-checkout pattern on every run. If this dir was
  # ever a full clone (e.g. created by an older version of this script),
  # this trims it down on the next reset.
  sudo -u libreclinica git -C "$INSTALL_PREFIX" sparse-checkout set "${SPARSE_PATHS[@]}"
  sudo -u libreclinica \
    LIBRECLINICA_GHCR_TOKEN="$LIBRECLINICA_GHCR_TOKEN" \
    GIT_ASKPASS="$ASKPASS_HELPER" \
    GIT_TERMINAL_PROMPT=0 \
    git -C "$INSTALL_PREFIX" \
      -c "credential.helper=" \
      -c "url.${REPO_URL_WITH_USER}.insteadOf=${LIBRECLINICA_REPO_URL}" \
      fetch --depth 1 origin "$LIBRECLINICA_REPO_REF"
  sudo -u libreclinica git -C "$INSTALL_PREFIX" reset --hard "FETCH_HEAD"
fi
rm -f "$ASKPASS_HELPER"
trap - EXIT

# ----------------------------- env file (secrets) -----------------------------

section "Environment file"

if [[ ! -s "$ENV_FILE" ]]; then
  pg_password=$(gen_secret)
  cat >"$ENV_FILE" <<EOF
# /etc/libreclinica/env — populated on first run of setup-ubuntu-host.sh.
# Edit cautiously; the Postgres password here must match what's in the
# database itself, so changes here without a matching ALTER USER will
# lock the application out.

LIBRECLINICA_IMAGE_TAG=${LIBRECLINICA_IMAGE_TAG}
# Sidecar image tag. Defaults to the app's tag — both images publish
# together via the Release image workflow, so a single tag rolls both
# in lockstep. Override only when you need to pin the sidecar at a
# different version than the app.
#LIBRECLINICA_RETINAL_IMAGE_TAG=${LIBRECLINICA_IMAGE_TAG}
POSTGRES_PASSWORD=${pg_password}

# Fine-grained GitHub PAT used for:
#   (a) the private-repo clone of /opt/libreclinica (compose + config seed)
#   (b) docker pulls from ghcr.io for the libreclinica + retinal-inference images
# Required scopes on the fine-grained PAT:
#   - Repository: LuViKu/LibreClinicaMUW — Contents: read
#   - Organization: LuViKu (or user account) — Packages: read
# See deploy/README.md § "Provisioning the GHCR token" for the minting recipe.
LIBRECLINICA_GHCR_USER=${LIBRECLINICA_GHCR_USER}
LIBRECLINICA_GHCR_TOKEN=${LIBRECLINICA_GHCR_TOKEN}

# Phase D SSO (DR-014). Defaults are safe-off; flip when the institutional
# reverse proxy is wired and forwarding the right headers.
LIBRECLINICA_SSO_ENABLED=false
LIBRECLINICA_SSO_PROVIDER=shibboleth-meduniwien
LIBRECLINICA_SSO_TRUSTED_CIDRS=127.0.0.1/32,${LIBRECLINICA_TRUSTED_CIDRS:-10.0.0.0/8}

# Retinal inference adapter — placeholder until the MIRAGE model lands.
RETINAL_INFERENCE_ADAPTER=placeholder
EOF
  chown libreclinica:libreclinica "$ENV_FILE"
  chmod 0640 "$ENV_FILE"
  log "Generated $ENV_FILE (Postgres password rolled, 32 chars; GHCR token persisted)"
else
  log "$ENV_FILE already exists; preserving secrets"
  # Update the image-tag pin on re-runs if --image-tag changed.
  sed -i "s|^LIBRECLINICA_IMAGE_TAG=.*|LIBRECLINICA_IMAGE_TAG=${LIBRECLINICA_IMAGE_TAG}|" "$ENV_FILE"
  # Update the GHCR token if --ghcr-token was explicitly passed (rotation
  # path). Insert the line if it wasn't there yet (env file pre-dates
  # this feature).
  if grep -q '^LIBRECLINICA_GHCR_TOKEN=' "$ENV_FILE"; then
    sed -i "s|^LIBRECLINICA_GHCR_TOKEN=.*|LIBRECLINICA_GHCR_TOKEN=${LIBRECLINICA_GHCR_TOKEN}|" "$ENV_FILE"
    sed -i "s|^LIBRECLINICA_GHCR_USER=.*|LIBRECLINICA_GHCR_USER=${LIBRECLINICA_GHCR_USER}|" "$ENV_FILE"
  else
    {
      printf '\n# GHCR credentials (added on setup re-run)\n'
      printf 'LIBRECLINICA_GHCR_USER=%s\n' "$LIBRECLINICA_GHCR_USER"
      printf 'LIBRECLINICA_GHCR_TOKEN=%s\n' "$LIBRECLINICA_GHCR_TOKEN"
    } >> "$ENV_FILE"
  fi
fi

# ----------------------------- runtime config ---------------------------------

section "Runtime config (datainfo.properties)"

# We don't overwrite a config the operator has customised. First run copies
# docker/config from the repo to /opt/libreclinica/config so the systemd
# bind-mount in libreclinica.service has a stable target.
RUNTIME_CONFIG=${INSTALL_PREFIX}/config
if [[ ! -d "$RUNTIME_CONFIG" ]]; then
  cp -a "${INSTALL_PREFIX}/docker/config" "$RUNTIME_CONFIG"
  chown -R libreclinica:libreclinica "$RUNTIME_CONFIG"
  log "Seeded runtime config from docker/config/ → $RUNTIME_CONFIG"
  warn "  Edit $RUNTIME_CONFIG/datainfo.properties to point mailHost at the"
  warn "  institutional MUW SMTP relay before sending production email."
else
  log "Runtime config exists at $RUNTIME_CONFIG (left untouched)"
fi

# ----------------------------- systemd unit -----------------------------------

section "systemd unit"

# The compose project is invoked with the base compose.yaml + the
# production override. The libreclinica service has `pull_policy: always`
# in the override so tag rollovers (e.g. bump LIBRECLINICA_IMAGE_TAG in
# /etc/libreclinica/env, then systemctl restart) trigger a fresh pull.
# The retinal-inference sidecar has no GHCR image yet (placeholder
# adapter ships from source), so compose builds it locally on first
# start and reuses the cached image on subsequent restarts.
cat >/etc/systemd/system/libreclinica.service <<EOF
[Unit]
Description=LibreClinicaMUW compose stack
Documentation=https://github.com/LuViKu/LibreClinicaMUW
After=network-online.target docker.service
Requires=docker.service
Wants=network-online.target

[Service]
Type=oneshot
RemainAfterExit=yes
WorkingDirectory=${INSTALL_PREFIX}
EnvironmentFile=${ENV_FILE}
# Bring up only the production-relevant services. mailcrab is excluded
# because production SMTP is the institutional MUW relay (configure via
# ${RUNTIME_CONFIG}/datainfo.properties).
ExecStart=/usr/bin/docker compose -f compose.yaml -f deploy/compose.production.yaml up --remove-orphans -d libreclinica db retinal-inference
ExecStop=/usr/bin/docker compose -f compose.yaml -f deploy/compose.production.yaml down
TimeoutStartSec=900

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable libreclinica.service >/dev/null
log "Enabled libreclinica.service (start manually with: systemctl start libreclinica)"

# ----------------------------- backup script + timer --------------------------

section "Postgres backup"

cat >/usr/local/sbin/libreclinica-backup-db <<'EOF'
#!/usr/bin/env bash
# Nightly pg_dump of the libreclinica database to /var/backups/libreclinica.
# Retention is enforced by find -mtime; tune via the systemd timer's
# environment if 30 days isn't the right window.
set -euo pipefail

BACKUP_DIR=/var/backups/libreclinica
RETENTION_DAYS=${LIBRECLINICA_BACKUP_RETENTION_DAYS:-30}
STAMP=$(date +%Y%m%dT%H%M%S)
DUMP_FILE="${BACKUP_DIR}/libreclinica-${STAMP}.sql.gz"

mkdir -p "$BACKUP_DIR"

if ! docker inspect libreclinica-muw-db-1 >/dev/null 2>&1; then
  echo "[backup] db container not running — skipping" >&2
  exit 0
fi

# pg_dump runs inside the container; gzip on the host so the file size
# is what we actually keep on disk.
docker exec libreclinica-muw-db-1 \
  pg_dump --username=clinica --format=plain --no-owner --no-privileges libreclinica \
  | gzip -9 > "$DUMP_FILE"

chmod 0600 "$DUMP_FILE"
chown libreclinica:libreclinica "$DUMP_FILE"

# Rotate.
find "$BACKUP_DIR" -name 'libreclinica-*.sql.gz' -mtime +"$RETENTION_DAYS" -delete

# Surface size to journald so `journalctl -u libreclinica-backup-db.service`
# tells the operator at a glance whether the dump shrank suspiciously.
size_bytes=$(stat -c '%s' "$DUMP_FILE")
size_human=$(numfmt --to=iec --suffix=B "$size_bytes")
echo "[backup] $(basename "$DUMP_FILE") (${size_human})"
EOF
chmod 0755 /usr/local/sbin/libreclinica-backup-db

cat >/etc/systemd/system/libreclinica-backup-db.service <<EOF
[Unit]
Description=LibreClinicaMUW nightly pg_dump
After=libreclinica.service
Requires=libreclinica.service

[Service]
Type=oneshot
Environment=LIBRECLINICA_BACKUP_RETENTION_DAYS=${LIBRECLINICA_BACKUP_RETENTION_DAYS}
ExecStart=/usr/local/sbin/libreclinica-backup-db
EOF

cat >/etc/systemd/system/libreclinica-backup-db.timer <<'EOF'
[Unit]
Description=LibreClinicaMUW nightly pg_dump (03:00 local + random 30 min jitter)

[Timer]
OnCalendar=*-*-* 03:00:00
RandomizedDelaySec=30min
Persistent=true
Unit=libreclinica-backup-db.service

[Install]
WantedBy=timers.target
EOF

systemctl daemon-reload
systemctl enable --now libreclinica-backup-db.timer >/dev/null
log "Enabled libreclinica-backup-db.timer (next run: $(systemctl show libreclinica-backup-db.timer -p NextElapseUSecRealtime --value 2>/dev/null || echo 'unknown'))"

# ----------------------------- logrotate --------------------------------------

section "Logrotate (backup directory)"

# Docker container logs are already rotated by the json-file driver
# (50MB × 5 files = 250MB max per container — set in daemon.json above).
# We only need logrotate for the human-readable summary log we leave
# in /var/log/libreclinica/setup.log, plus a safety net on the backup
# directory in case retention via find ever misfires.
cat >/etc/logrotate.d/libreclinica <<EOF
${BACKUP_DIR}/*.sql.gz {
    weekly
    rotate ${LIBRECLINICA_BACKUP_RETENTION_DAYS}
    missingok
    notifempty
    nocreate
    su libreclinica libreclinica
}
EOF

# ----------------------------- summary ----------------------------------------

section "Summary"

cat <<EOF
Host setup complete.

Next steps:
  1. Confirm /etc/libreclinica/env (image tag, Postgres password).
  2. Edit ${RUNTIME_CONFIG}/datainfo.properties to point mailHost / mailPort /
     mailUsername / mailPassword at the institutional MUW SMTP relay.
  3. (If not already done) trigger the 'Release image' workflow on GitHub
     against a tagged release so BOTH images exist at this tag:
       - ghcr.io/luviku/libreclinicamuw:${LIBRECLINICA_IMAGE_TAG}
       - ghcr.io/luviku/libreclinicamuw/retinal-inference:${LIBRECLINICA_IMAGE_TAG}
     The workflow's matrix job publishes both in one dispatch.
  4. Start the stack:
        sudo systemctl start libreclinica
     Watch the boot:
        sudo journalctl -u libreclinica -f
     and the Tomcat log:
        sudo docker logs -f libreclinica-muw-libreclinica-1
  5. From a host on the MUW internal network, confirm:
        curl -I http://<vm-ip>:${LIBRECLINICA_HOST_PORT}/LibreClinica/
  6. Wire the institutional reverse proxy to forward HTTPS → http://<vm-ip>:${LIBRECLINICA_HOST_PORT}.
  7. Verify the backup timer fires tonight:
        systemctl list-timers libreclinica-backup-db.timer
EOF
