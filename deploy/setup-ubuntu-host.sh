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
#   4. SSH hardening (key-only, no root, no passwords)
#   5. UFW firewall (SSH + 8080 from trusted CIDRs only)
#   6. fail2ban (sshd jail)
#   7. Docker Engine + Compose v2 (official Docker apt repo)
#   8. Deploy user `libreclinica` (no shell, member of docker group)
#   9. Stack tree under /opt/libreclinica + /etc/libreclinica
#  10. systemd unit `libreclinica.service` that brings the compose stack up
#  11. Nightly pg_dump backup via systemd timer to /var/backups/libreclinica
#  12. Log rotation for backup files
#
# What it does NOT do:
#   - TLS termination: the MUW institutional reverse proxy is expected to
#     front this host. UFW opens 8080 only to trusted CIDRs you configure.
#     If you need local TLS, add nginx separately.
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
#     --trusted-cidrs '128.131.0.0/16,10.0.0.0/8' \
#     --admin-ssh-key 'ssh-ed25519 AAAA... admin@muw'
#
# All knobs can also be set via env vars before invocation; flags override.
#
# Tested against: Ubuntu 24.04 LTS (Noble Numbat), kernel 6.8+
# ------------------------------------------------------------------------------

set -euo pipefail

# ----------------------------- defaults ---------------------------------------

: "${LIBRECLINICA_IMAGE_TAG:=latest}"
: "${LIBRECLINICA_TRUSTED_CIDRS:=}"      # comma-separated list, e.g. "10.0.0.0/8,192.168.0.0/16"
: "${LIBRECLINICA_ADMIN_SSH_KEY:=}"      # single SSH pubkey to add to root's authorized_keys
: "${LIBRECLINICA_TIMEZONE:=Europe/Vienna}"
: "${LIBRECLINICA_HOST_PORT:=8080}"      # the host port the reverse proxy targets
: "${LIBRECLINICA_REPO_URL:=https://github.com/LuViKu/LibreClinicaMUW.git}"
: "${LIBRECLINICA_REPO_REF:=main}"       # branch/tag the deploy tree clones to /opt/libreclinica
: "${LIBRECLINICA_BACKUP_RETENTION_DAYS:=30}"

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
    --admin-ssh-key)    LIBRECLINICA_ADMIN_SSH_KEY="$2"; shift 2 ;;
    --timezone)         LIBRECLINICA_TIMEZONE="$2"; shift 2 ;;
    --host-port)        LIBRECLINICA_HOST_PORT="$2"; shift 2 ;;
    --repo-ref)         LIBRECLINICA_REPO_REF="$2"; shift 2 ;;
    --backup-days)      LIBRECLINICA_BACKUP_RETENTION_DAYS="$2"; shift 2 ;;
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

if [[ -z "$LIBRECLINICA_TRUSTED_CIDRS" ]]; then
  warn "No --trusted-cidrs set. UFW will allow port ${LIBRECLINICA_HOST_PORT}"
  warn "from 0.0.0.0/0 — fine for a private VM, bad for an internet-facing one."
fi

if [[ -z "$LIBRECLINICA_ADMIN_SSH_KEY" && ! -s /root/.ssh/authorized_keys ]]; then
  die "No --admin-ssh-key passed AND /root/.ssh/authorized_keys is empty. Refusing to harden SSH — you would lock yourself out."
fi

log "OS:           $(grep PRETTY_NAME /etc/os-release | cut -d= -f2- | tr -d '"')"
log "Image tag:    ghcr.io/luviku/libreclinicamuw:${LIBRECLINICA_IMAGE_TAG}"
log "Trusted CIDRs: ${LIBRECLINICA_TRUSTED_CIDRS:-<none>}"
log "Timezone:     ${LIBRECLINICA_TIMEZONE}"

# ----------------------------- system packages --------------------------------

section "System update + base packages"

export DEBIAN_FRONTEND=noninteractive

apt-get update -qq
apt-get upgrade -y -qq
apt-get install -y -qq \
  ca-certificates curl gnupg lsb-release \
  ufw fail2ban unattended-upgrades \
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

# ----------------------------- SSH hardening ----------------------------------

section "SSH hardening"

# 1) Drop in admin key (if supplied) before we disable password login.
if [[ -n "$LIBRECLINICA_ADMIN_SSH_KEY" ]]; then
  install -d -m 700 /root/.ssh
  touch /root/.ssh/authorized_keys
  chmod 600 /root/.ssh/authorized_keys
  if ! grep -qF "$LIBRECLINICA_ADMIN_SSH_KEY" /root/.ssh/authorized_keys; then
    echo "$LIBRECLINICA_ADMIN_SSH_KEY" >> /root/.ssh/authorized_keys
    log "Added admin SSH key to /root/.ssh/authorized_keys"
  else
    log "Admin SSH key already present"
  fi
fi

# 2) Replace the per-host config with a single drop-in. We touch only our
#    file, never /etc/ssh/sshd_config, so package upgrades don't fight us.
cat >/etc/ssh/sshd_config.d/10-libreclinica.conf <<'EOF'
# Managed by deploy/setup-ubuntu-host.sh
PermitRootLogin prohibit-password
PasswordAuthentication no
ChallengeResponseAuthentication no
KbdInteractiveAuthentication no
UsePAM yes
X11Forwarding no
AllowAgentForwarding no
AllowTcpForwarding no
MaxAuthTries 3
LoginGraceTime 30
ClientAliveInterval 300
ClientAliveCountMax 2
EOF

# Validate before reload so a typo doesn't strand us.
sshd -t
systemctl reload ssh.service

# ----------------------------- UFW firewall -----------------------------------

section "UFW firewall"

ufw --force reset >/dev/null
ufw default deny incoming >/dev/null
ufw default allow outgoing >/dev/null

# SSH: rate-limited so brute-force attempts get throttled by the kernel.
ufw limit 22/tcp comment 'SSH (rate-limited)' >/dev/null

if [[ -n "$LIBRECLINICA_TRUSTED_CIDRS" ]]; then
  IFS=',' read -ra CIDRS <<< "$LIBRECLINICA_TRUSTED_CIDRS"
  for cidr in "${CIDRS[@]}"; do
    cidr_trimmed=$(echo "$cidr" | tr -d ' ')
    ufw allow from "$cidr_trimmed" to any port "$LIBRECLINICA_HOST_PORT" proto tcp comment "LibreClinica from $cidr_trimmed" >/dev/null
  done
else
  ufw allow "$LIBRECLINICA_HOST_PORT/tcp" comment 'LibreClinica (UNRESTRICTED — set --trusted-cidrs)' >/dev/null
fi

ufw --force enable >/dev/null
log "UFW status:"
ufw status numbered | sed 's/^/  /'

# ----------------------------- fail2ban ---------------------------------------

section "fail2ban"

cat >/etc/fail2ban/jail.d/libreclinica.local <<'EOF'
# Managed by deploy/setup-ubuntu-host.sh
[sshd]
enabled  = true
port     = ssh
maxretry = 5
findtime = 10m
bantime  = 1h
backend  = systemd
EOF
systemctl enable --now fail2ban.service >/dev/null
systemctl reload fail2ban.service >/dev/null

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

# Clone or update the repo. We need it for compose.yaml + the production
# override + docker/config/. Pinned to LIBRECLINICA_REPO_REF (main by default).
if [[ ! -d "${INSTALL_PREFIX}/.git" ]]; then
  log "Cloning ${LIBRECLINICA_REPO_URL} → ${INSTALL_PREFIX}"
  sudo -u libreclinica git clone --branch "$LIBRECLINICA_REPO_REF" --depth 1 \
    "$LIBRECLINICA_REPO_URL" "$INSTALL_PREFIX"
else
  log "Updating ${INSTALL_PREFIX} (${LIBRECLINICA_REPO_REF})"
  sudo -u libreclinica git -C "$INSTALL_PREFIX" fetch --depth 1 origin "$LIBRECLINICA_REPO_REF"
  sudo -u libreclinica git -C "$INSTALL_PREFIX" reset --hard "FETCH_HEAD"
fi

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
POSTGRES_PASSWORD=${pg_password}

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
  log "Generated $ENV_FILE (Postgres password rolled, 32 chars)"
else
  log "$ENV_FILE already exists; preserving secrets"
  # Update the image-tag pin on re-runs if --image-tag changed.
  sed -i "s|^LIBRECLINICA_IMAGE_TAG=.*|LIBRECLINICA_IMAGE_TAG=${LIBRECLINICA_IMAGE_TAG}|" "$ENV_FILE"
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
     against a tagged release so ghcr.io/luviku/libreclinicamuw:${LIBRECLINICA_IMAGE_TAG}
     exists.
  4. Start the stack:
        sudo systemctl start libreclinica
     Watch the boot:
        sudo journalctl -u libreclinica -f
     and the Tomcat log:
        sudo docker logs -f libreclinica-muw-libreclinica-1
  5. From a host on a trusted CIDR, confirm:
        curl -I http://<vm-ip>:${LIBRECLINICA_HOST_PORT}/LibreClinica/
  6. Wire the institutional reverse proxy to forward HTTPS → http://<vm-ip>:${LIBRECLINICA_HOST_PORT}.
  7. Verify the backup timer fires tonight:
        systemctl list-timers libreclinica-backup-db.timer
EOF
