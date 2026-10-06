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
#      (optionally also the file stores, age-encrypted, copied off-host)
#   9. Log rotation for backup files
#  10. INTERNET_FACING=true only: host firewall, session timeout, edge rules,
#      mandatory encrypted backups (see "Internet-facing mode" below)
#
# TLS: the compose stack runs its own nginx sidecar (deploy/nginx/ecrf.conf)
# that terminates HTTPS on 80/443 for ecrf.augen.meduniwien.ac.at; the app's
# port 8080 is only for that sidecar (and, on the internal deployment, an
# institutional reverse proxy on another host). The certificate is NOT created
# here: place it in /etc/libreclinica/tls (see deploy/nginx/README.md).
#
# What it does NOT do (default, internal mode):
#   - Host-level firewall / SSH hardening / fail2ban: the internal VM is
#     reachable only via the MUW internal network; network-level access control
#     sits at the institutional perimeter. INTERNET_FACING=true installs a
#     firewall; nothing else here ever does.
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
# host-level firewall.
#
# Internet-facing mode (a separate VM behind the MUW DMZ reverse proxy; see
# deploy/README.md § "Internet-facing (multicenter) deployment"):
#
#   sudo INTERNET_FACING=true \
#     ADMIN_CIDRS='10.1.2.0/24' DMZ_PROXY_CIDRS='192.0.2.10/32' \
#     BACKUP_AGE_RECIPIENT='age1...' \
#     RETINAL_INFERENCE_ADAPTER=optima \
#     LIBRECLINICA_RETINAL_REMOTE_PUSH_URL='https://gpu.example.org:8000' \
#     LIBRECLINICA_RETINAL_REMOTE_PUSH_TOKEN='<long random>' \
#     bash deploy/setup-ubuntu-host.sh --image-tag v1.5.0-muw
#
#   (flag form: --internet-facing / --no-internet-facing)
#   INTERNET_FACING          true to enable the mode. Once the env file says the
#                            host is internet-facing, a re-run without it stays
#                            in the mode; INTERNET_FACING=false turns it off.
#   ADMIN_CIDRS      (required) comma list allowed to reach SSH.
#   DMZ_PROXY_CIDRS  comma list allowed to reach 80/443; also the trusted
#                    real-IP source for nginx rate limiting. Strongly advised.
#   BACKUP_AGE_RECIPIENT (required) age public key; dump + file archives are
#                    encrypted to it. Keep the private key OFF this host.
#   BACKUP_RSYNC_TARGET  optional user@host:/dir for an off-host copy.
#   SSH_PORT         default 22.
# The mode refuses to run with the placeholder retinal push token, a non-https
# retinal push URL, or RETINAL_INFERENCE_ADAPTER=placeholder.
#
# Tested against: Ubuntu 24.04 LTS (Noble Numbat), kernel 6.8+
# ------------------------------------------------------------------------------

set -euo pipefail

# ----------------------------- defaults ---------------------------------------

# Whether the operator actually named a tag — as a flag or, per the header, as
# an env var. An existing env file's pin is only rewritten when they did; see
# the --image-tag flag and the env-file block. Evaluated before the default
# below, because afterwards "was it set?" is unanswerable.
IMAGE_TAG_GIVEN=${IMAGE_TAG_GIVEN:-0}
[[ -n "${LIBRECLINICA_IMAGE_TAG:-}" ]] && IMAGE_TAG_GIVEN=1
: "${LIBRECLINICA_IMAGE_TAG:=latest}"
: "${LIBRECLINICA_TRUSTED_CIDRS:=}"      # comma-separated list, e.g. "10.0.0.0/8,192.168.0.0/16" — consumed by SSO header trust
: "${LIBRECLINICA_TIMEZONE:=Europe/Vienna}"
: "${LIBRECLINICA_HOST_PORT:=8080}"      # the host port the reverse proxy targets
: "${LIBRECLINICA_REPO_URL:=https://github.com/LuViKu/LibreClinicaMUW.git}"
: "${LIBRECLINICA_REPO_REF:=main}"       # branch/tag the deploy tree clones to /opt/libreclinica
: "${LIBRECLINICA_BACKUP_RETENTION_DAYS:=30}"
: "${LIBRECLINICA_GHCR_USER:=LuViKu}"    # GitHub username the PAT belongs to
: "${LIBRECLINICA_GHCR_TOKEN:=}"         # classic PAT with 'repo' + 'read:packages' scopes (GHCR needs a classic token). See deploy/README.md for minting instructions.
# DICOM sidecar (DR-025/DR-029). `--dicom` switches the compose profile on,
# pairs one shared secret between the sidecar (DICOM_SCP_INGEST_TOKEN) and
# the app (core.dicom.ingest.token), and adds the service to the systemd
# unit. Without the sidecar every DICOM upload is refused with 503, because
# the sidecar is what pseudonymises the header before a row is written.
: "${LIBRECLINICA_DICOM:=}"              # set to 1 (or pass --dicom) to run the dicom-scp sidecar
: "${LIBRECLINICA_DICOM_BIND_ADDR:=127.0.0.1}"   # C-STORE port 11112 stays on loopback until a camera needs it

# Production mail (SMTP). Stamped into datainfo.properties so a fresh deploy can
# send email out of the box — the repo's dev default points mailHost at the
# absent mailcrab 'smtp' host, which 500s the first-login root password change
# (UnknownHostException: smtp). Defaults target the MUW *internal* outgoing
# relay, which requires NO authentication. For an authenticated/external relay
# (e.g. smtpa.meduniwien.ac.at:587 from outside the MUW net) set these via env
# before invocation: LIBRECLINICA_MAIL_HOST/PORT/SMTP_AUTH/STARTTLS/USERNAME/PASSWORD.
: "${LIBRECLINICA_MAIL_HOST:=smtpi.meduniwien.ac.at}"
: "${LIBRECLINICA_MAIL_PORT:=25}"
: "${LIBRECLINICA_MAIL_SMTP_AUTH:=false}"
: "${LIBRECLINICA_MAIL_STARTTLS:=false}"
: "${LIBRECLINICA_MAIL_USERNAME:=}"
: "${LIBRECLINICA_MAIL_PASSWORD:=}"
: "${LIBRECLINICA_MAIL_CONNECTION_TIMEOUT:=10000}"   # ms — the repo's 100ms default is too short for a real relay
: "${LIBRECLINICA_ADMIN_EMAIL:=}"                    # From/reply-to on system mail; empty keeps the seeded value

# Retinal remote-inference pipeline (DR-022 + DR-024). Stamped into
# datainfo.properties so a fresh deploy dispatches OCT scans to the GPU cluster
# out of the box — the repo's dev defaults point remotePushUrl at
# host.docker.internal (the Mac/SSH-tunnel pattern) and preprocessUrl at a
# separate dev-only 'retinal-preprocess' sidecar, neither of which exists in
# prod. The cluster URL/token default to the MUW reading-centre host; the
# preprocess endpoint is served by the retinal-inference container itself.
# RETINAL_PREPROCESS_TOKEN must match RETINAL_INFERENCE_PREPROCESS_TOKEN passed
# to compose (deploy/compose.production.yaml) — same value, two consumers.
: "${LIBRECLINICA_RETINAL_REMOTE_PUSH_URL:=http://cn5.cir.meduniwien.ac.at:8000}"
: "${LIBRECLINICA_RETINAL_REMOTE_PUSH_TOKEN:=choose-a-long-shared-secret}"
: "${LIBRECLINICA_RETINAL_PREPROCESS_URL:=http://retinal-inference:8000}"
# The preprocess token is NOT defaulted here any more: the old default was a
# value committed in this public repository. Unless the operator passes one,
# the setup mints a per-host secret below and pairs it between the env file
# (compose) and datainfo.properties (the app).
: "${LIBRECLINICA_RETINAL_PREPROCESS_TOKEN:=}"

# Public base URL (datainfo `sysURL`) behind the nginx TLS reverse proxy — used
# in system emails + absolute links. The seeded config ships the dev default
# (http://localhost:8080/...); this stamps the institutional https URL.
: "${LIBRECLINICA_SYS_URL:=https://ecrf.augen.meduniwien.ac.at/LibreClinica/MainMenu}"

# Internet-facing mode (see the header). INTERNET_FACING is deliberately left
# empty here: "not given" must stay distinguishable from "false", because a host
# already running in the mode keeps it on a plain re-run.
: "${INTERNET_FACING:=}"
: "${ADMIN_CIDRS:=}"
: "${DMZ_PROXY_CIDRS:=}"
: "${SSH_PORT:=}"                           # default 22; a persisted value is reused on re-run
: "${BACKUP_AGE_RECIPIENT:=}"
: "${BACKUP_RSYNC_TARGET:=}"
: "${LIBRECLINICA_BACKUP_FILES:=}"        # true: also archive the file stores (always on in internet mode)
: "${LIBRECLINICA_SESSION_MAX_INACTIVE:=1800}"   # seconds; datainfo maxInactiveInterval in internet mode
: "${SKIP_ADMIN_LOCKOUT_CHECK:=false}"   # true: do not refuse a firewall that would cut this SSH session
# RETINAL_INFERENCE_ADAPTER is read from the environment only when given; the
# env file's value is used otherwise (see the deployment-mode section).
: "${RETINAL_INFERENCE_ADAPTER:=}"

INSTALL_PREFIX=/opt/libreclinica
CONFIG_DIR=/etc/libreclinica
ENV_FILE=${CONFIG_DIR}/env
BACKUP_DIR=/var/backups/libreclinica
PG_DATA_DIR=/var/lib/libreclinica/postgres
E2E_UPLOADS_DIR=/var/lib/libreclinica/e2e-uploads
RETINAL_OUTPUT_DIR=/var/lib/libreclinica/retinal-inference
# The remaining file stores (deploy/compose.production.yaml binds every store
# under /var/lib/libreclinica at the same path in every container that
# touches it). The nightly timer backs up the database; it archives these
# stores too when LIBRECLINICA_BACKUP_FILES=true (always, in internet mode).
# Otherwise they are covered by whatever backs up this VM's disk.
RETINAL_ARTIFACTS_DIR=/var/lib/libreclinica/retinal-artifacts
DICOM_INGEST_DIR=/var/lib/libreclinica/dicom-ingest
INGEST_DIR=/var/lib/libreclinica/ingest
# The cluster monitor's log and state. Its own directory, bound read-only
# into the app container, because the System Status page tails the log from
# inside the container and the root of /var/lib/libreclinica is not bound.
MONITOR_DIR=/var/lib/libreclinica/monitor
# The app's own data directory (filePath: CRF file attachments, dataset
# exports, the regenerated xslt/rules) and Tomcat's logs. Bound from here since
# 2026-09-24; before, they were anonymous volumes that every `compose down` +
# `up` (each restart of the unit) replaced with empty ones.
APP_DATA_DIR=/var/lib/libreclinica/app-data
TOMCAT_LOGS_DIR=/var/lib/libreclinica/tomcat-logs
APP_CONTAINER=libreclinica-muw-libreclinica-1

# ----------------------------- arg parsing ------------------------------------

# How we were invoked, and what this file looked like at startup — both needed
# by the re-exec after the repo checkout replaces this script mid-run.
ORIGINAL_ARGS=("$@")
SELF_SNAPSHOT="$(mktemp /tmp/libreclinica-setup-self.XXXXXX)"
if [[ -r "${BASH_SOURCE[0]}" ]]; then
  cp "${BASH_SOURCE[0]}" "$SELF_SNAPSHOT"
fi

while [[ $# -gt 0 ]]; do
  case "$1" in
    --image-tag)        LIBRECLINICA_IMAGE_TAG="$2"; IMAGE_TAG_GIVEN=1; shift 2 ;;
    --trusted-cidrs)    LIBRECLINICA_TRUSTED_CIDRS="$2"; shift 2 ;;
    --timezone)         LIBRECLINICA_TIMEZONE="$2"; shift 2 ;;
    --host-port)        LIBRECLINICA_HOST_PORT="$2"; shift 2 ;;
    --repo-ref)         LIBRECLINICA_REPO_REF="$2"; shift 2 ;;
    --backup-days)      LIBRECLINICA_BACKUP_RETENTION_DAYS="$2"; shift 2 ;;
    --ghcr-user)        LIBRECLINICA_GHCR_USER="$2"; shift 2 ;;
    --ghcr-token)       LIBRECLINICA_GHCR_TOKEN="$2"; shift 2 ;;
    --dicom)            LIBRECLINICA_DICOM=1; shift ;;
    --internet-facing)    INTERNET_FACING=true; shift ;;
    --no-internet-facing) INTERNET_FACING=false; shift ;;
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
gen_secret() { LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom 2>/dev/null | head -c 32 || true; }

# Read KEY's last value from a KEY=VALUE file (empty when file or key is absent).
get_kv() { [[ -r "$1" ]] && sed -n "s/^${2}=//p" "$1" | tail -1 || true; }

# Set KEY=VALUE in a KEY=VALUE file: replace in place, else append. VALUE must
# not contain '|', '&' or a backslash (every caller passes a path or a token).
set_kv() {
  if grep -q "^${2}=" "$1"; then
    sed -i "s|^${2}=.*|${2}=${3}|" "$1"
  else
    printf '%s=%s\n' "$2" "$3" >>"$1"
  fi
}

# Is every comma-separated entry an IPv4/IPv6 address or CIDR? (Shape check, not
# a full parse: it exists to catch typos before they land in a firewall rule.)
valid_cidr_list() {
  local entry
  [[ -n "$1" ]] || return 1
  IFS=',' read -ra _entries <<<"$1"
  for entry in "${_entries[@]}"; do
    [[ "$entry" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}(/[0-9]{1,2})?$ || "$entry" =~ ^[0-9A-Fa-f:]+:[0-9A-Fa-f:]*(/[0-9]{1,3})?$ ]] || return 1
  done
}

# Does the CIDR list contain this address? Needs python3 (stock on Ubuntu);
# returns 2 when it cannot tell.
cidr_list_contains() {
  command -v python3 >/dev/null || return 2
  python3 - "$1" "$2" <<'PY'
import ipaddress, sys
addr = ipaddress.ip_address(sys.argv[2])
for c in sys.argv[1].split(','):
    try:
        if addr in ipaddress.ip_network(c.strip(), strict=False):
            sys.exit(0)
    except ValueError:
        pass
sys.exit(1)
PY
}

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
  die "No --ghcr-token set (and none in ${ENV_FILE}). The private repo + GHCR pulls require a classic PAT (scopes: repo + read:packages) — see deploy/README.md § 'Provisioning the GHCR token' for the minting recipe."
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
  403) die "PAT validation failed: HTTP 403 Forbidden. Token lacks repo read access. Re-mint a classic PAT with the 'repo' scope." ;;
  404) die "PAT validation failed: HTTP 404. Either the token can't see the repo (missing the 'repo' scope on the classic PAT) or the repo URL is wrong (got ${LIBRECLINICA_REPO_URL})." ;;
  *)   die "PAT validation failed: HTTP ${http_code} from api.github.com. Check network access + token validity." ;;
esac

log "OS:                $(grep PRETTY_NAME /etc/os-release | cut -d= -f2- | tr -d '"')"
log "Image tag:         ghcr.io/luviku/libreclinicamuw:${LIBRECLINICA_IMAGE_TAG}"
log "GHCR user:         ${LIBRECLINICA_GHCR_USER}"
log "Trusted CIDRs (SSO): ${LIBRECLINICA_TRUSTED_CIDRS:-<none>}"
log "Timezone:          ${LIBRECLINICA_TIMEZONE}"

# ----------------------------- deployment mode --------------------------------

section "Deployment mode"

FW_CONF=${CONFIG_DIR}/firewall.conf          # persisted ADMIN_CIDRS / DMZ_PROXY_CIDRS / SSH_PORT (0600)
BACKUP_ENV=${CONFIG_DIR}/backup.env          # persisted backup settings (0600), the backup unit's EnvironmentFile
REALIP_CONF=${CONFIG_DIR}/nginx-realip.conf  # generated nginx real-ip include (internet mode)

# Backup settings persist across re-runs, in either mode, so an operator does
# not have to repeat the age key to change an unrelated setting.
[[ -n "$BACKUP_AGE_RECIPIENT" ]]     || BACKUP_AGE_RECIPIENT="$(get_kv "$BACKUP_ENV" BACKUP_AGE_RECIPIENT)"
[[ -n "$BACKUP_RSYNC_TARGET" ]]      || BACKUP_RSYNC_TARGET="$(get_kv "$BACKUP_ENV" BACKUP_RSYNC_TARGET)"
[[ -n "$LIBRECLINICA_BACKUP_FILES" ]] || LIBRECLINICA_BACKUP_FILES="$(get_kv "$BACKUP_ENV" LIBRECLINICA_BACKUP_FILES)"

# Is this host already in the mode? (Decides what a plain re-run means.)
IFACE_WAS_ON=0
[[ "$(get_kv "$ENV_FILE" LIBRECLINICA_DEPLOYMENT_INTERNET_FACING)" == "true" ]] && IFACE_WAS_ON=1

case "${INTERNET_FACING,,}" in
  true|1|yes|on)  IFACE=1 ;;
  false|0|no|off) IFACE=0 ;;
  "")             IFACE=$IFACE_WAS_ON
                  [[ "$IFACE" == "1" ]] && log "Internet-facing mode inherited from ${ENV_FILE} (pass INTERNET_FACING=false to leave it)" ;;
  *)              die "INTERNET_FACING must be true or false (got '${INTERNET_FACING}')" ;;
esac

RETINAL_ADAPTER_EFFECTIVE="${RETINAL_INFERENCE_ADAPTER:-$(get_kv "$ENV_FILE" RETINAL_INFERENCE_ADAPTER)}"
RETINAL_ADAPTER_EFFECTIVE="${RETINAL_ADAPTER_EFFECTIVE:-placeholder}"

if [[ "$IFACE" == "1" ]]; then
  log "Mode:              INTERNET-FACING (host firewall, 1800 s sessions, encrypted backups, edge rules)"

  # Persisted values from the previous run, unless given now.
  [[ -n "$ADMIN_CIDRS" ]]     || ADMIN_CIDRS="$(get_kv "$FW_CONF" ADMIN_CIDRS)"
  [[ -n "$DMZ_PROXY_CIDRS" ]] || DMZ_PROXY_CIDRS="$(get_kv "$FW_CONF" DMZ_PROXY_CIDRS)"
  [[ -n "$SSH_PORT" ]]        || SSH_PORT="$(get_kv "$FW_CONF" SSH_PORT)"
  : "${SSH_PORT:=22}"
  [[ "$SSH_PORT" =~ ^[0-9]{1,5}$ ]] || die "SSH_PORT must be a port number (got '${SSH_PORT}')"

  # The mode's own preconditions. Everything here is checked BEFORE any change
  # to the host, so a refusal leaves it exactly as it was.
  [[ -n "$ADMIN_CIDRS" ]] \
    || die "INTERNET_FACING needs ADMIN_CIDRS (comma-separated addresses/CIDRs allowed to reach SSH). Without it the firewall would lock everyone out."
  valid_cidr_list "$ADMIN_CIDRS" || die "ADMIN_CIDRS is not a comma-separated list of IPs/CIDRs: '${ADMIN_CIDRS}'"
  if [[ -n "$DMZ_PROXY_CIDRS" ]]; then
    valid_cidr_list "$DMZ_PROXY_CIDRS" || die "DMZ_PROXY_CIDRS is not a comma-separated list of IPs/CIDRs: '${DMZ_PROXY_CIDRS}'"
  else
    warn "DMZ_PROXY_CIDRS is not set: ports 80/443 will be open to every source, and nginx's per-IP"
    warn "  rate limits will see only the DMZ proxy's address. Set it to the proxy's address(es)."
  fi

  [[ -n "$BACKUP_AGE_RECIPIENT" ]] \
    || die "INTERNET_FACING needs BACKUP_AGE_RECIPIENT (an age public key, 'age1...'): backups here are always encrypted. Generate a key pair elsewhere with 'age-keygen' and keep the private key off this host."
  [[ "$BACKUP_AGE_RECIPIENT" =~ ^age1[a-z0-9]{50,}$ ]] \
    || die "BACKUP_AGE_RECIPIENT does not look like an age public key (expected 'age1' + bech32 characters)."
  LIBRECLINICA_BACKUP_FILES=true

  [[ "${LIBRECLINICA_DICOM}" != "1" ]] \
    || die "--dicom cannot be combined with INTERNET_FACING: no DICOM port may be published on an internet-facing host."

  if [[ "${RETINAL_ADAPTER_EFFECTIVE,,}" == "placeholder" ]]; then
    die "INTERNET_FACING refuses RETINAL_INFERENCE_ADAPTER=placeholder: it would return fake segmentation results. Pass RETINAL_INFERENCE_ADAPTER=<real adapter> (e.g. optima)."
  fi

  # Retinal push endpoint: the URL/token that will be in effect once this run
  # has stamped datainfo.properties. The stamp only happens while remotePushUrl
  # is still the dev default, so on a re-run the file's values are the ones in
  # effect; on a first run they are the LIBRECLINICA_RETINAL_REMOTE_PUSH_* ones.
  PRE_CFG="${INSTALL_PREFIX}/config/datainfo.properties"
  eff_url="$(get_kv "$PRE_CFG" core.retinalInference.remotePushUrl)"
  if [[ -z "$eff_url" || "$eff_url" == "http://host.docker.internal:8000" ]]; then
    eff_url="$LIBRECLINICA_RETINAL_REMOTE_PUSH_URL"
    eff_token="$LIBRECLINICA_RETINAL_REMOTE_PUSH_TOKEN"
  else
    eff_token="$(get_kv "$PRE_CFG" core.retinalInference.remotePushToken)"
  fi
  if [[ -n "$eff_url" && "$eff_url" != https://* ]]; then
    die "INTERNET_FACING requires an https:// retinal push URL (got '${eff_url}'). OCT images must not cross the network in clear. Set LIBRECLINICA_RETINAL_REMOTE_PUSH_URL (first run) or core.retinalInference.remotePushUrl in ${PRE_CFG}."
  fi
  if [[ "$eff_token" == "choose-a-long-shared-secret" || -z "$eff_token" ]]; then
    die "INTERNET_FACING refuses the placeholder/empty retinal push token. Set LIBRECLINICA_RETINAL_REMOTE_PUSH_TOKEN (first run) or core.retinalInference.remotePushToken in ${PRE_CFG}, and the same value on the GPU host."
  fi

  # Would the firewall cut the session this script is running in?
  ssh_ip="${SSH_CLIENT:-}"; ssh_ip="${ssh_ip%% *}"
  [[ -n "$ssh_ip" ]] || ssh_ip="$(who -m 2>/dev/null | sed -n 's/.*(\(.*\))$/\1/p')"
  if [[ -n "$ssh_ip" && "$SKIP_ADMIN_LOCKOUT_CHECK" != "true" ]]; then
    set +e; cidr_list_contains "$ADMIN_CIDRS" "$ssh_ip"; rc=$?; set -e
    if [[ $rc -eq 1 ]]; then
      die "You are connected from ${ssh_ip}, which is not in ADMIN_CIDRS (${ADMIN_CIDRS}); the firewall would cut this session. Add it, or set SKIP_ADMIN_LOCKOUT_CHECK=true if you reach SSH another way."
    fi
  fi
else
  log "Mode:              internal (default)"
  if [[ -n "$BACKUP_RSYNC_TARGET" && -z "$BACKUP_AGE_RECIPIENT" ]]; then
    die "BACKUP_RSYNC_TARGET needs BACKUP_AGE_RECIPIENT: nothing leaves this host unencrypted."
  fi
fi
[[ "$BACKUP_RSYNC_TARGET" != *$'\n'* && "$BACKUP_AGE_RECIPIENT" != *$'\n'* ]] || die "Backup settings must be single-line values."

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

# age: encrypts the backups (required in internet mode; used in either mode as
# soon as BACKUP_AGE_RECIPIENT is set). iptables: the internet-facing firewall.
extra_pkgs=()
if [[ "$IFACE" == "1" || -n "$BACKUP_AGE_RECIPIENT" ]]; then extra_pkgs+=(age); fi
if [[ "$IFACE" == "1" ]]; then extra_pkgs+=(iptables); fi
if [[ ${#extra_pkgs[@]} -gt 0 ]]; then
  apt-get install -y -qq "${extra_pkgs[@]}"
  if printf '%s\n' "${extra_pkgs[@]}" | grep -qx age; then
    command -v age >/dev/null || die "age did not install; backups cannot be encrypted."
  fi
fi

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
  die "docker login ghcr.io failed. Check token + 'read:packages' scope (must be a classic PAT — GHCR rejects fine-grained tokens)."
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
install -d -m 0755 -o libreclinica -g libreclinica "$RETINAL_ARTIFACTS_DIR"
install -d -m 0755 -o libreclinica -g libreclinica "$DICOM_INGEST_DIR"
install -d -m 0755 -o libreclinica -g libreclinica "$INGEST_DIR"
install -d -m 0755 -o libreclinica -g libreclinica "$MONITOR_DIR"
install -d -m 0755 -o libreclinica -g libreclinica "$APP_DATA_DIR"
install -d -m 0755 -o libreclinica -g libreclinica "$TOMCAT_LOGS_DIR"

# One-time move off the anonymous volumes. While the running app container
# still has its data directory (or logs) on an anonymous volume and the new
# directory is empty, copy the current contents over, so the restart that
# follows this script finds them bound. Idempotent: once the container runs
# with the bind (mount type "bind"), or the directory has content, nothing
# is copied. Older anonymous volumes left by earlier restarts are not
# touched; `docker volume prune` removes them once this is deployed.
#
# Every outcome is said out loud. The first version returned in silence when
# there was no container to copy from, which is what happens when the stack
# was stopped before the upgrade: `compose down` removes the container and
# leaves its volume behind unused. The production upgrade to beta.12 went
# that way, and nothing in the output showed the copy had not happened.
move_off_anonymous_volume() {
  local container_path="$1" host_dir="$2" mount
  if ! docker inspect "$APP_CONTAINER" >/dev/null 2>&1; then
    # A fresh host has nothing to move and no checkout yet. An existing one
    # with an empty directory may have had files on the removed container's
    # volume, now unused.
    if [[ -d "$INSTALL_PREFIX/.git" && -z "$(ls -A "$host_dir" 2>/dev/null)" ]]; then
      warn "No app container ($APP_CONTAINER) is running, so nothing was copied into ${host_dir}."
      warn "  If the app kept files under ${container_path}, they are in an unused Docker volume:"
      warn "  list them with 'docker volume ls -qf dangling=true' before any prune, and copy what"
      warn "  is needed into ${host_dir} (release notes 1.5.0-beta.12-muw, 'Upgrading the app VM', step 4)."
    fi
    return 0
  fi
  mount="$(docker inspect -f "{{range .Mounts}}{{if eq .Destination \"${container_path}\"}}{{.Type}} {{.Source}}{{end}}{{end}}" "$APP_CONTAINER" 2>/dev/null || true)"
  case "$mount" in
    "bind ${host_dir}")
      log "${container_path} is already bound to ${host_dir}"
      return 0 ;;
    volume*) ;;
    *)
      warn "${container_path} in ${APP_CONTAINER} is mounted as '${mount:-nothing}', not as expected; nothing copied"
      return 0 ;;
  esac
  if [[ -n "$(ls -A "$host_dir" 2>/dev/null)" ]]; then
    warn "${host_dir} is not empty; not copying ${container_path} over it"
    return 0
  fi
  if docker cp "${APP_CONTAINER}:${container_path}/." "$host_dir/"; then
    chown -R libreclinica:libreclinica "$host_dir"
    log "Copied ${container_path} out of its anonymous volume into ${host_dir} ($(du -sh "$host_dir" | cut -f1)); the restart binds it"
  else
    warn "could not copy ${container_path} out of ${APP_CONTAINER}; it starts empty after the restart"
  fi
}
move_off_anonymous_volume /usr/local/tomcat/libreclinica.data "$APP_DATA_DIR"
move_off_anonymous_volume /usr/local/tomcat/logs "$TOMCAT_LOGS_DIR"

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

# ----------------------------- re-exec the updated self ------------------------
# bash reads a script incrementally from its open file descriptor. The block
# above just replaced this very file (the operator runs the copy under
# ${INSTALL_PREFIX}/deploy, which is what the runbook says to use), so from
# here on we would keep executing the OLD text against the NEW tree. On the
# beta.10 deploy that meant a run which appended the release's new config keys
# — the template is read at runtime, so that part was current — while silently
# skipping a whole new section (the cluster-monitor cron) and rejecting the
# release's new flag. Nothing in the output said so.
#
# So: if the file we were started from no longer matches what is running, hand
# over to the new copy with the same arguments. REEXECED guards against a loop
# if the comparison ever misfires.
if [[ -z "${LIBRECLINICA_SETUP_REEXECED:-}" ]]; then
  self="${BASH_SOURCE[0]}"
  if [[ -r "$self" ]] && ! cmp -s "$self" "$SELF_SNAPSHOT"; then
    log "This script was updated by the checkout above — re-running the new copy"
    rm -f "$SELF_SNAPSHOT"
    LIBRECLINICA_SETUP_REEXECED=1 IMAGE_TAG_GIVEN="$IMAGE_TAG_GIVEN" \
      exec bash "$self" ${ORIGINAL_ARGS[@]+"${ORIGINAL_ARGS[@]}"}
  fi
fi
rm -f "$SELF_SNAPSHOT"

# ----------------------------- env file (secrets) -----------------------------

section "Environment file"

if [[ ! -s "$ENV_FILE" ]]; then
  pg_password=$(gen_secret)
  # The first bind address. Internet-facing hosts never publish 8080 beyond
  # loopback (nginx reaches the app over the compose network).
  INITIAL_BIND_ADDR=0.0.0.0
  [[ "$IFACE" == "1" ]] && INITIAL_BIND_ADDR=127.0.0.1
  # Create the file closed before the secrets go in: no window with a wider mode.
  install -m 0600 -o libreclinica -g libreclinica /dev/null "$ENV_FILE"
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
# Host interface the app's port 8080 publishes on. Set to 0.0.0.0 so the
# separate-host institutional reverse proxy (and in-network smoke tests) can
# reach it over the internal network — the base compose.yaml defaults to
# loopback (dev), so production MUST set this for the proxy to connect.
# Narrow to the VM's internal IP if you want to restrict the bind.
# (An INTERNET_FACING=true run writes 127.0.0.1 here and keeps it there.)
LIBRECLINICA_BIND_ADDR=${INITIAL_BIND_ADDR}
POSTGRES_PASSWORD=${pg_password}

# Classic GitHub PAT used for:
#   (a) the private-repo clone of /opt/libreclinica (compose + config seed)
#   (b) docker pulls from ghcr.io for the libreclinica + retinal-inference images
# Required scopes on the classic PAT (GHCR does not accept fine-grained tokens):
#   - repo          — authorises the private-repo clone
#   - read:packages — authorises the ghcr.io image pulls
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

# DICOM sidecar (dicom-scp). COMPOSE_PROFILES=dicom runs it; the token must
# equal core.dicom.ingest.token in datainfo.properties (the setup script pairs
# them when run with --dicom). 11112 is the camera-facing C-STORE port; keep
# it on loopback until a camera is actually being wired.
COMPOSE_PROFILES=
DICOM_SCP_INGEST_TOKEN=
LIBRECLINICA_DICOM_BIND_ADDR=${LIBRECLINICA_DICOM_BIND_ADDR}
EOF
  chown libreclinica:libreclinica "$ENV_FILE"
  # 0640 internal (as before); 0600 internet-facing (the env file holds the DB
  # password, GHCR token and the retinal tokens). Root, which runs the unit and
  # this script, reads it either way.
  if [[ "$IFACE" == "1" ]]; then chmod 0600 "$ENV_FILE"; else chmod 0640 "$ENV_FILE"; fi
  log "Generated $ENV_FILE (Postgres password rolled, 32 chars; GHCR token persisted)"
else
  log "$ENV_FILE already exists; preserving secrets"
  # The image-tag pin: only rewritten when the operator named one.
  #
  # This used to rewrite unconditionally, from this script's own default. A
  # re-run without --image-tag therefore replaced whatever the host was pinned
  # to with `latest` — silently unpinning a clinical system, and undoing the
  # `sed` the runbook tells you to do first (beta.10 deploy, 2026-09-24).
  # Re-running to pick up new config keys must not change which image runs.
  if [[ "$IMAGE_TAG_GIVEN" == "1" ]]; then
    sed -i "s|^LIBRECLINICA_IMAGE_TAG=.*|LIBRECLINICA_IMAGE_TAG=${LIBRECLINICA_IMAGE_TAG}|" "$ENV_FILE"
    log "Image tag pinned to ${LIBRECLINICA_IMAGE_TAG}"
  else
    current_tag="$(sed -n 's/^LIBRECLINICA_IMAGE_TAG=//p' "$ENV_FILE" | tail -1)"
    log "Image tag left at ${current_tag:-<unset>} (pass --image-tag to change it)"
  fi
  # Ensure the host bind is set. The base compose.yaml defaults to loopback,
  # so without an active LIBRECLINICA_BIND_ADDR=0.0.0.0 here the app is
  # unreachable to the separate-host reverse proxy. Add it if missing (older
  # env files, or ones that pre-date this knob); never clobber an operator's
  # explicit value (e.g. a narrowed internal-IP bind).
  for k in COMPOSE_PROFILES DICOM_SCP_INGEST_TOKEN LIBRECLINICA_DICOM_BIND_ADDR; do
    if ! grep -q "^${k}=" "$ENV_FILE"; then
      v=""; [[ "$k" == "LIBRECLINICA_DICOM_BIND_ADDR" ]] && v="${LIBRECLINICA_DICOM_BIND_ADDR}"
      printf '\n# %s (added on setup re-run; see the DICOM sidecar notes in deploy/README.md)\n%s=%s\n' "$k" "$k" "$v" >> "$ENV_FILE"
      log "Added ${k} to $ENV_FILE"
    fi
  done
  if ! grep -q '^LIBRECLINICA_BIND_ADDR=' "$ENV_FILE"; then
    printf '\n# Host interface for port 8080 (added on setup re-run). 0.0.0.0 so the\n# separate-host reverse proxy can reach it; narrow to the VM internal IP.\nLIBRECLINICA_BIND_ADDR=0.0.0.0\n' >> "$ENV_FILE"
    log "Added LIBRECLINICA_BIND_ADDR=0.0.0.0 to $ENV_FILE"
  fi
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

# Internet-facing keys in the env file (read by compose through the unit's
# EnvironmentFile). Re-asserted on every run, so a hand edit cannot quietly
# reopen the bind or turn the app-side switch off.
if [[ "$IFACE" == "1" ]]; then
  set_kv "$ENV_FILE" LIBRECLINICA_BIND_ADDR 127.0.0.1
  set_kv "$ENV_FILE" LIBRECLINICA_DEPLOYMENT_INTERNET_FACING true
  set_kv "$ENV_FILE" LIBRECLINICA_NGINX_EDGE_CONF ./deploy/nginx/internet-facing.conf
  set_kv "$ENV_FILE" LIBRECLINICA_NGINX_EDGE_HTTP_CONF ./deploy/nginx/internet-facing-http.conf
  set_kv "$ENV_FILE" LIBRECLINICA_NGINX_REALIP_CONF "$REALIP_CONF"
  set_kv "$ENV_FILE" LIBRECLINICA_DICOM_BIND_ADDR 127.0.0.1
  set_kv "$ENV_FILE" RETINAL_INFERENCE_ADAPTER "$RETINAL_ADAPTER_EFFECTIVE"
  if grep -qE '^COMPOSE_PROFILES=.*\bdicom\b' "$ENV_FILE"; then
    warn "COMPOSE_PROFILES contained 'dicom'; cleared it: no DICOM sidecar or port on an internet-facing host"
    set_kv "$ENV_FILE" COMPOSE_PROFILES ""
  fi
  chmod 0600 "$ENV_FILE"
  log "Env file: bind 127.0.0.1, LIBRECLINICA_DEPLOYMENT_INTERNET_FACING=true, edge include on, mode 0600"

  # nginx real-IP include: trust the DMZ proxy's X-Forwarded-For so per-IP rate
  # limits see the visitor, not the proxy. Root-owned, mounted read-only.
  {
    echo "# Generated by setup-ubuntu-host.sh from DMZ_PROXY_CIDRS - do not edit."
    if [[ -n "$DMZ_PROXY_CIDRS" ]]; then
      IFS=',' read -ra _dmz <<<"$DMZ_PROXY_CIDRS"
      for c in "${_dmz[@]}"; do echo "set_real_ip_from ${c};"; done
      echo "real_ip_header X-Forwarded-For;"
      echo "real_ip_recursive on;"
    else
      echo "# DMZ_PROXY_CIDRS was not set: client addresses are the connecting peer's."
    fi
  } >"$REALIP_CONF"
  chown root:root "$REALIP_CONF"; chmod 0644 "$REALIP_CONF"

  # Firewall inputs, for libreclinica-firewall (installed below).
  ( umask 077
    {
      echo "# Written by setup-ubuntu-host.sh; read by /usr/local/sbin/libreclinica-firewall."
      echo "ADMIN_CIDRS=${ADMIN_CIDRS}"
      echo "DMZ_PROXY_CIDRS=${DMZ_PROXY_CIDRS}"
      echo "SSH_PORT=${SSH_PORT}"
    } >"$FW_CONF" )
elif [[ "$IFACE_WAS_ON" == "1" ]]; then
  warn "Leaving internet-facing mode: app-side switch off, edge include off, firewall removed."
  warn "  LIBRECLINICA_BIND_ADDR stays 127.0.0.1 and the env file stays 0600; widen them by hand if the"
  warn "  internal deployment needs a routable bind."
  set_kv "$ENV_FILE" LIBRECLINICA_DEPLOYMENT_INTERNET_FACING false
  sed -i '/^LIBRECLINICA_NGINX_\(EDGE\|EDGE_HTTP\|REALIP\)_CONF=/d' "$ENV_FILE"
fi

# Backup settings, one file per host, read by the backup unit. Written in both
# modes (empty values mean: database dump only, unencrypted, local only).
( umask 077
  {
    echo "# Written by setup-ubuntu-host.sh; EnvironmentFile of libreclinica-backup-db.service."
    echo "LIBRECLINICA_BACKUP_FILES=${LIBRECLINICA_BACKUP_FILES:-false}"
    echo "BACKUP_AGE_RECIPIENT=${BACKUP_AGE_RECIPIENT}"
    echo "BACKUP_RSYNC_TARGET=${BACKUP_RSYNC_TARGET}"
  } >"$BACKUP_ENV" )

# --dicom: profile on, and a shared secret minted once. An explicit non-empty
# DICOM_SCP_INGEST_TOKEN already in the env file is kept - it may be the one
# the app is configured with.
if [[ "${LIBRECLINICA_DICOM}" == "1" ]]; then
  sed -i 's|^COMPOSE_PROFILES=.*|COMPOSE_PROFILES=dicom|' "$ENV_FILE"
  if [[ -z "$(sed -n 's/^DICOM_SCP_INGEST_TOKEN=//p' "$ENV_FILE" | tail -1)" ]]; then
    sed -i "s|^DICOM_SCP_INGEST_TOKEN=.*|DICOM_SCP_INGEST_TOKEN=$(gen_secret)|" "$ENV_FILE"
    log "Minted DICOM_SCP_INGEST_TOKEN in $ENV_FILE"
  fi
  log "DICOM sidecar enabled (COMPOSE_PROFILES=dicom)"
  if [[ -z "$(get_kv "$ENV_FILE" DICOM_SCP_ALLOWED_CALLING_AE_TITLES)" ]]; then
    warn "DICOM_SCP_ALLOWED_CALLING_AE_TITLES is not set in $ENV_FILE: the sidecar will accept an"
    warn "  association from any caller that can reach port 11112. Set it to the camera's AE title."
  fi
fi
DICOM_TOKEN="$(sed -n 's/^DICOM_SCP_INGEST_TOKEN=//p' "$ENV_FILE" | tail -1)"
DICOM_PROFILE_ON=0
grep -qE '^COMPOSE_PROFILES=.*\bdicom\b' "$ENV_FILE" && DICOM_PROFILE_ON=1

# The active Postgres password drives both the DB container (POSTGRES_PASSWORD
# above) and the app's datainfo.properties dbPass below — they must match or the
# app can't authenticate. On first run it's the freshly-generated secret; on
# re-run, read it back from the preserved env file.
DB_PASSWORD="${pg_password:-$(grep -E '^POSTGRES_PASSWORD=' "$ENV_FILE" | cut -d= -f2-)}"

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

# Merge NEW keys from the shipped template into an existing config.
#
# The seed above runs once; from then on the operator's file is authoritative
# and we never rewrite a value they may have edited. But that also meant keys
# ADDED to the template by a later release never reached an existing host — the
# app then silently fell back to whatever default the calling code hardcodes,
# which for a feature flag means "off, with nothing in the log to say why".
#
# That is exactly how 1.5.0-beta.7-muw shipped the ELGA medication ingest to a
# production host whose datainfo.properties predated the feature: the operator's
# `sed -i 's|^core.terminology.medication.enabled=.*|...=true|'` matched nothing,
# because the key simply was not in the file.
#
# It matters more than it looks: CoreResources.getPropValues() does
# `prop = new Properties()` before loading the external file, so the
# bind-mounted datainfo.properties REPLACES the WAR's bundled copy outright —
# there is no overlay. A key missing here is a key the app never sees.
#
# So: append template keys the target lacks; never touch one it already has.
# A key the operator COMMENTED OUT counts as missing and is re-appended:
# java.util.Properties ignores "#" lines, so the app was already running on
# the code default anyway. Commenting a key out is therefore not a durable
# way to disable it — set the value explicitly instead.
# Idempotent — a second run finds nothing missing and appends nothing.
merge_properties() {
  local template=$1 target=$2 label=$3
  [[ -f "$template" && -f "$target" ]] || return 0

  # awk compares the key as an exact string, so dots in property names
  # ('mailSmtpStarttls.enable') cannot act as regex wildcards and mask a
  # genuinely-missing key the way a grep "^${key}=" probe would.
  local added
  added=$(awk -F= '
    FNR == NR {
      if ($0 ~ /^[A-Za-z][A-Za-z0-9._]*=/) have[$1] = 1
      next
    }
    /^[A-Za-z][A-Za-z0-9._]*=/ { if (!($1 in have)) print }
  ' "$target" "$template")

  if [[ -z "$added" ]]; then
    log "$label: no new keys in the template"
    return 0
  fi

  local count
  count=$(printf '%s\n' "$added" | wc -l | tr -d ' ')
  {
    printf '\n'
    printf '#############################################################################\n'
    printf '# Added by setup-ubuntu-host.sh on %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    printf '# Keys present in the shipped template but missing here, appended at their\n'
    printf '# template defaults. Existing values above were NOT modified. Review these\n'
    printf '# before relying on them - a new feature flag ships disabled by default.\n'
    printf '#############################################################################\n'
    printf '%s\n' "$added"
  } >>"$target"

  log "$label: appended $count new key(s) from the template"
  printf '%s\n' "$added" | while IFS= read -r kv; do warn "  + ${kv%%=*}"; done
  warn "  Review $target — appended keys carry template defaults, not site values."
}

# Runs BEFORE the guarded sed blocks below. Those use 's|^key=.*|' and no-op
# silently when the key is absent, so merging first means a freshly-appended
# key still gets its production value stamped on this same run.
merge_properties "${INSTALL_PREFIX}/docker/config/datainfo.properties" \
                 "${RUNTIME_CONFIG}/datainfo.properties" "datainfo.properties"
merge_properties "${INSTALL_PREFIX}/docker/config/extract.properties" \
                 "${RUNTIME_CONFIG}/extract.properties" "extract.properties"
chown -R libreclinica:libreclinica "$RUNTIME_CONFIG" 2>/dev/null || true

# Keep the app's dbPass in lockstep with the DB container's POSTGRES_PASSWORD.
# The repo's datainfo.properties ships dbPass=clinica (the dev default), but
# production runs Postgres with the host-generated secret — without this sync
# the app fails to authenticate and the Spring context dies on startup
# (FATAL: password authentication failed for user "clinica"). Re-asserted on
# every run so a rotated secret propagates. gen_secret output is [A-Za-z0-9],
# so it is safe to drop straight into the sed replacement unescaped.
DATAINFO_FILE="${RUNTIME_CONFIG}/datainfo.properties"
if [[ -f "$DATAINFO_FILE" ]]; then
  sed -i "s|^dbPass=.*|dbPass=${DB_PASSWORD}|" "$DATAINFO_FILE"
  chown libreclinica:libreclinica "$DATAINFO_FILE"
  log "Synced datainfo.properties dbPass to the DB password"

  # Configure the SMTP settings ONLY if they haven't been set yet. The repo
  # ships mailHost=smtp (the mailcrab dev catcher), which is unreachable in
  # production and 500s the first-login password change. We treat mailHost
  # still being the placeholder 'smtp' as "not yet configured": on a fresh
  # deploy we set the MUW relay defaults (overridable via LIBRECLINICA_MAIL_*);
  # once mailHost has been moved off the placeholder — by us on an earlier run,
  # or by the operator — re-runs leave the mail config untouched so hand edits
  # survive. Values are simple tokens (host/port/bool), safe in sed unescaped.
  if grep -q '^mailHost=smtp$' "$DATAINFO_FILE"; then
    sed -i \
      -e "s|^mailHost=.*|mailHost=${LIBRECLINICA_MAIL_HOST}|" \
      -e "s|^mailPort=.*|mailPort=${LIBRECLINICA_MAIL_PORT}|" \
      -e "s|^mailSmtpAuth=.*|mailSmtpAuth=${LIBRECLINICA_MAIL_SMTP_AUTH}|" \
      -e "s|^mailSmtpStarttls\.enable=.*|mailSmtpStarttls.enable=${LIBRECLINICA_MAIL_STARTTLS}|" \
      -e "s|^mailUsername=.*|mailUsername=${LIBRECLINICA_MAIL_USERNAME}|" \
      -e "s|^mailPassword=.*|mailPassword=${LIBRECLINICA_MAIL_PASSWORD}|" \
      -e "s|^mailSmtpConnectionTimeout=.*|mailSmtpConnectionTimeout=${LIBRECLINICA_MAIL_CONNECTION_TIMEOUT}|" \
      "$DATAINFO_FILE"
    log "Configured datainfo.properties mail (host=${LIBRECLINICA_MAIL_HOST}:${LIBRECLINICA_MAIL_PORT}, auth=${LIBRECLINICA_MAIL_SMTP_AUTH})"

    # adminEmail (From/reply-to). Only override when given, so we don't blank a
    # value the operator may have set.
    if [[ -n "$LIBRECLINICA_ADMIN_EMAIL" ]]; then
      sed -i "s|^adminEmail=.*|adminEmail=${LIBRECLINICA_ADMIN_EMAIL}|" "$DATAINFO_FILE"
      log "Set datainfo.properties adminEmail=${LIBRECLINICA_ADMIN_EMAIL}"
    elif grep -q '^adminEmail=admin@example.com' "$DATAINFO_FILE"; then
      warn "  adminEmail is still admin@example.com — set LIBRECLINICA_ADMIN_EMAIL"
      warn "  to a real MUW address (or edit datainfo.properties) so system mail"
      warn "  isn't dropped as a bogus sender."
    fi
  else
    log "datainfo.properties mail already configured (mailHost off the 'smtp' default) — leaving as-is"
  fi

  # Retinal remote-inference pipeline (DR-022 + DR-024). The seeded config
  # ships dev defaults — remotePushUrl=host.docker.internal (the Mac/SSH-tunnel
  # pattern) + preprocessUrl=retinal-preprocess (a dev-only sidecar). Production
  # reaches the GPU cluster directly and serves /preprocess from the
  # retinal-inference container itself, so stamp the production values. Guarded
  # on remotePushUrl still being the dev default so operator/site edits survive
  # a re-run (mirrors the mailHost approach). URLs/tokens are simple tokens with
  # no '|', safe in the sed replacement unescaped.
  if grep -q '^core.retinalInference.remotePushUrl=http://host.docker.internal:8000$' "$DATAINFO_FILE"; then
    sed -i \
      -e "s|^core.retinalInference.remotePushUrl=.*|core.retinalInference.remotePushUrl=${LIBRECLINICA_RETINAL_REMOTE_PUSH_URL}|" \
      -e "s|^core.retinalInference.remotePushToken=.*|core.retinalInference.remotePushToken=${LIBRECLINICA_RETINAL_REMOTE_PUSH_TOKEN}|" \
      -e "s|^core.retinalInference.preprocessUrl=.*|core.retinalInference.preprocessUrl=${LIBRECLINICA_RETINAL_PREPROCESS_URL}|" \
      "$DATAINFO_FILE"
    log "Configured datainfo.properties retinal pipeline (remote=${LIBRECLINICA_RETINAL_REMOTE_PUSH_URL}, preprocess=${LIBRECLINICA_RETINAL_PREPROCESS_URL})"
  else
    log "datainfo.properties retinal pipeline already configured (remotePushUrl off the dev default) — leaving as-is"
  fi

  # Retinal preprocess token: one per-host secret shared by the sidecar
  # (RETINAL_INFERENCE_PREPROCESS_TOKEN in the env file, read by compose) and
  # the app (core.retinalInference.preprocessToken). Up to beta.14 both fell
  # back to a value committed in the public repository. Order of preference:
  # LIBRECLINICA_RETINAL_PREPROCESS_TOKEN from the environment,
  # a non-default value already in the env file, a non-default value already
  # in datainfo.properties, else a freshly minted secret.
  PUBLIC_PREPROCESS_DEFAULT=cb2e7d0edd4d54cd8af46bbef78755a7
  env_preprocess="$(sed -n 's/^RETINAL_INFERENCE_PREPROCESS_TOKEN=//p' "$ENV_FILE" | tail -1)"
  cfg_preprocess="$(sed -n 's/^core\.retinalInference\.preprocessToken=//p' "$DATAINFO_FILE" | tail -1)"
  preprocess_token="$LIBRECLINICA_RETINAL_PREPROCESS_TOKEN"
  if [[ -z "$preprocess_token" && -n "$env_preprocess" && "$env_preprocess" != "$PUBLIC_PREPROCESS_DEFAULT" ]]; then
    preprocess_token="$env_preprocess"
  fi
  if [[ -z "$preprocess_token" && -n "$cfg_preprocess" && "$cfg_preprocess" != "$PUBLIC_PREPROCESS_DEFAULT" ]]; then
    preprocess_token="$cfg_preprocess"
  fi
  if [[ -z "$preprocess_token" ]]; then
    preprocess_token="$(gen_secret)"
    log "Minted a per-host retinal preprocess token (replaces the public default)"
  fi
  if grep -q '^RETINAL_INFERENCE_PREPROCESS_TOKEN=' "$ENV_FILE"; then
    sed -i "s|^RETINAL_INFERENCE_PREPROCESS_TOKEN=.*|RETINAL_INFERENCE_PREPROCESS_TOKEN=${preprocess_token}|" "$ENV_FILE"
  else
    printf '\n# Retinal preprocess token (sidecar side); must equal\n# core.retinalInference.preprocessToken in datainfo.properties.\nRETINAL_INFERENCE_PREPROCESS_TOKEN=%s\n' "$preprocess_token" >> "$ENV_FILE"
  fi
  if grep -q '^core\.retinalInference\.preprocessToken=' "$DATAINFO_FILE"; then
    sed -i "s|^core\.retinalInference\.preprocessToken=.*|core.retinalInference.preprocessToken=${preprocess_token}|" "$DATAINFO_FILE"
  else
    printf '\ncore.retinalInference.preprocessToken=%s\n' "$preprocess_token" >> "$DATAINFO_FILE"
  fi
  [[ "$env_preprocess" == "$preprocess_token" && "$cfg_preprocess" == "$preprocess_token" ]] \
    || log "Paired the retinal preprocess token between ${ENV_FILE} and datainfo.properties (takes effect on restart)"

  # The GPU push token must match the GPU host, so it cannot be minted here.
  if grep -q '^core\.retinalInference\.remotePushToken=choose-a-long-shared-secret$' "$DATAINFO_FILE"; then
    warn "core.retinalInference.remotePushToken is still the placeholder from the public repository."
    warn "  Set a long random value there and the same value on the GPU host (see deploy/README.md)."
  fi

  # DEBUG logging writes more than an operator needs, and older releases wrote
  # secrets at DEBUG. Production should run at info.
  if grep -qiE '^logLevel=(debug|trace)$' "$DATAINFO_FILE"; then
    warn "datainfo.properties has $(grep -iE '^logLevel=' "$DATAINFO_FILE"); set logLevel=info for production."
  fi

  # Public base URL (sysURL) — system emails + absolute links. The seeded
  # config ships the dev default (localhost:8080); stamp the public https URL
  # behind the reverse proxy. Guarded on the localhost default so operator
  # edits survive a re-run.
  if grep -q '^sysURL=http://localhost:8080' "$DATAINFO_FILE"; then
    sed -i "s|^sysURL=.*|sysURL=${LIBRECLINICA_SYS_URL}|" "$DATAINFO_FILE"
    log "Configured datainfo.properties sysURL=${LIBRECLINICA_SYS_URL}"
  else
    log "datainfo.properties sysURL already configured (off the localhost default) — leaving as-is"
  fi

  # Session idle timeout (maxInactiveInterval, seconds). The shipped 3600 stays
  # on the internal deployment; the internet-facing one gets 1800, re-asserted
  # each run. Leaving the mode puts the shipped value back, but only if it is
  # still the one this script set.
  if [[ "$IFACE" == "1" ]]; then
    set_kv "$DATAINFO_FILE" maxInactiveInterval "$LIBRECLINICA_SESSION_MAX_INACTIVE"
    log "Set datainfo.properties maxInactiveInterval=${LIBRECLINICA_SESSION_MAX_INACTIVE} (internet-facing)"
  elif [[ "$IFACE_WAS_ON" == "1" && "$(get_kv "$DATAINFO_FILE" maxInactiveInterval)" == "$LIBRECLINICA_SESSION_MAX_INACTIVE" ]]; then
    set_kv "$DATAINFO_FILE" maxInactiveInterval 3600
    log "Restored datainfo.properties maxInactiveInterval=3600"
  fi
else
  warn "  $DATAINFO_FILE not found — could not sync dbPass / mail config"
fi

# ----------------------------- DICOM token pairing ----------------------------

# The app authenticates its /describe calls to the sidecar with
# core.dicom.ingest.token, and the sidecar checks the same header against
# DICOM_SCP_INGEST_TOKEN. One secret, two homes. Stamp the app's half from
# the env file when the app's is blank; never overwrite a value an operator
# set, but say so when the two disagree, because that is a 503 on every
# DICOM upload with nothing in the log to explain it.
if [[ -n "${DICOM_TOKEN}" && -f "${DATAINFO_FILE:-/dev/null}" ]]; then
  app_tok="$(sed -n 's/^core\.dicom\.ingest\.token=//p' "$DATAINFO_FILE" | tail -1)"
  if [[ -z "$app_tok" ]]; then
    sed -i "s|^core.dicom.ingest.token=.*|core.dicom.ingest.token=${DICOM_TOKEN}|" "$DATAINFO_FILE"
    log "datainfo.properties core.dicom.ingest.token paired with DICOM_SCP_INGEST_TOKEN"
  elif [[ "$app_tok" != "$DICOM_TOKEN" ]]; then
    warn "core.dicom.ingest.token differs from DICOM_SCP_INGEST_TOKEN in $ENV_FILE - DICOM uploads will get 503 until they match"
  fi
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
# Services are named explicitly (mailcrab is dev-only and must not start),
# which means a compose profile alone can never add one: on the beta.9 host
# COMPOSE_PROFILES=dicom was set and the sidecar still did not come up. The
# list therefore follows the profile.
COMPOSE_SERVICES="libreclinica db retinal-inference nginx"
if [[ "$DICOM_PROFILE_ON" == "1" ]]; then
  COMPOSE_SERVICES="${COMPOSE_SERVICES} dicom-scp"
  log "systemd unit will also start dicom-scp"
fi
# Internet-facing: the stack (and so the published 80/443) starts only after the
# firewall unit has applied its rules.
FW_UNIT_AFTER=""
[[ "$IFACE" == "1" ]] && FW_UNIT_AFTER=" libreclinica-firewall.service"
cat >/etc/systemd/system/libreclinica.service <<EOF
[Unit]
Description=LibreClinicaMUW compose stack
Documentation=https://github.com/LuViKu/LibreClinicaMUW
After=network-online.target docker.service${FW_UNIT_AFTER}
Requires=docker.service
Wants=network-online.target${FW_UNIT_AFTER}

[Service]
Type=oneshot
RemainAfterExit=yes
WorkingDirectory=${INSTALL_PREFIX}
EnvironmentFile=${ENV_FILE}
# Bring up only the production-relevant services. mailcrab is excluded
# because production SMTP is the institutional MUW relay (configure via
# ${RUNTIME_CONFIG}/datainfo.properties).
ExecStart=/usr/bin/docker compose -f compose.yaml -f deploy/compose.production.yaml up --remove-orphans -d ${COMPOSE_SERVICES}
ExecStop=/usr/bin/docker compose -f compose.yaml -f deploy/compose.production.yaml down
TimeoutStartSec=900

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable libreclinica.service >/dev/null
log "Enabled libreclinica.service (start manually with: systemctl start libreclinica)"

# ----------------------------- backup script + timer --------------------------

section "Backups (database, optionally file stores; optionally encrypted + off-host)"

cat >/usr/local/sbin/libreclinica-backup-db <<'EOF'
#!/usr/bin/env bash
# Nightly backup of LibreClinicaMUW into /var/backups/libreclinica:
#
#   libreclinica-<stamp>.sql.gz[.age]        pg_dump of the database (always)
#   libreclinica-files-<stamp>.tar.gz[.age]  the file stores under
#                                            /var/lib/libreclinica (e2e uploads,
#                                            retinal artifacts, ingest, app-data)
#                                            - only when LIBRECLINICA_BACKUP_FILES=true
#
# Settings arrive as environment from /etc/libreclinica/backup.env (written by
# setup-ubuntu-host.sh):
#   BACKUP_AGE_RECIPIENT   age public key. When set, every artefact is encrypted
#                          to it (suffix .age) and never touches the disk in
#                          clear. The private key must NOT be on this host.
#   BACKUP_RSYNC_TARGET    user@host:/dir. When set, the artefacts of this run
#                          are copied there after the local ones are written.
#                          Needs BACKUP_AGE_RECIPIENT; remote retention is the
#                          target's job.
#   LIBRECLINICA_BACKUP_FILES  true to include the file stores.
#   LIBRECLINICA_BACKUP_RETENTION_DAYS  local retention (find -mtime).
#
# Restore: see deploy/README.md "Restore from backup" (the .age variants are
# decrypted with `age -d -i <private-key>` first).
set -euo pipefail
umask 077

BACKUP_DIR=/var/backups/libreclinica
STORE_ROOT=/var/lib/libreclinica
RETENTION_DAYS=${LIBRECLINICA_BACKUP_RETENTION_DAYS:-30}
AGE_RECIPIENT=${BACKUP_AGE_RECIPIENT:-}
RSYNC_TARGET=${BACKUP_RSYNC_TARGET:-}
WITH_FILES=${LIBRECLINICA_BACKUP_FILES:-false}
# Stores worth keeping. Not postgres (the dump covers it), tomcat-logs, monitor
# or home.
STORES=(e2e-uploads retinal-artifacts retinal-inference ingest dicom-ingest app-data)
STAMP=$(date +%Y%m%dT%H%M%S)
SUFFIX=""
rc=0

mkdir -p "$BACKUP_DIR"

if ! docker inspect libreclinica-muw-db-1 >/dev/null 2>&1; then
  echo "[backup] db container not running — skipping" >&2
  exit 0
fi

if [[ -n "$AGE_RECIPIENT" ]]; then
  command -v age >/dev/null || { echo "[backup] BACKUP_AGE_RECIPIENT is set but age is not installed" >&2; exit 1; }
  SUFFIX=".age"
elif [[ -n "$RSYNC_TARGET" ]]; then
  echo "[backup] BACKUP_RSYNC_TARGET is set without BACKUP_AGE_RECIPIENT; refusing to copy plaintext off-host" >&2
  exit 1
fi

# stdin -> stdout, encrypted when a recipient is configured.
encrypt() { if [[ -n "$AGE_RECIPIENT" ]]; then age -r "$AGE_RECIPIENT"; else cat; fi; }

# Every artefact is written under a .partial name and renamed once complete, so
# an interrupted run never leaves a truncated file under a real name (the old
# script left one, and the next restore would have used it).
DUMP_FILE="${BACKUP_DIR}/libreclinica-${STAMP}.sql.gz${SUFFIX}"
produced=("$DUMP_FILE")

# pg_dump runs inside the container; gzip on the host so the file size
# is what we actually keep on disk.
if docker exec libreclinica-muw-db-1 \
     pg_dump --username=clinica --format=plain --no-owner --no-privileges libreclinica \
   | gzip -9 | encrypt > "${DUMP_FILE}.partial"; then
  mv -f "${DUMP_FILE}.partial" "$DUMP_FILE"
else
  rm -f "${DUMP_FILE}.partial"
  echo "[backup] pg_dump FAILED" >&2
  exit 1
fi

if [[ "$WITH_FILES" == "true" ]]; then
  FILES_FILE="${BACKUP_DIR}/libreclinica-files-${STAMP}.tar.gz${SUFFIX}"
  present=()
  for s in "${STORES[@]}"; do [[ -d "${STORE_ROOT}/${s}" ]] && present+=("$s"); done
  if [[ ${#present[@]} -gt 0 ]]; then
    # tar exits 1 for "a file changed while we read it" (the app is live); that
    # is a warning here, anything higher is a failure.
    if { tar --warning=no-file-changed -C "$STORE_ROOT" -cf - "${present[@]}" || [[ $? -eq 1 ]]; } \
       | gzip -1 | encrypt > "${FILES_FILE}.partial"; then
      mv -f "${FILES_FILE}.partial" "$FILES_FILE"
      produced+=("$FILES_FILE")
    else
      rm -f "${FILES_FILE}.partial"
      echo "[backup] file-store archive FAILED" >&2
      rc=1
    fi
  fi
fi

for f in "${produced[@]}"; do
  chmod 0600 "$f"
  chown libreclinica:libreclinica "$f"
done

# Off-host copy of this run's artefacts. A failure is reported (unit goes red)
# but does not remove the local copy.
if [[ -n "$RSYNC_TARGET" ]]; then
  if rsync -a --partial -e "ssh -o BatchMode=yes" "${produced[@]}" "${RSYNC_TARGET%/}/"; then
    echo "[backup] copied ${#produced[@]} file(s) to ${RSYNC_TARGET}"
  else
    echo "[backup] rsync to ${RSYNC_TARGET} FAILED" >&2
    rc=1
  fi
fi

# Rotate.
#
# The glob is '*.sql.gz*', not '*.sql.gz', on purpose. A logrotate stanza
# used to also manage this directory and renamed each dump to
# `.sql.gz.1`, which the narrower glob stopped matching — so nothing was
# pruned at all. On the MUW host that left every dump back to 2026-06-13
# in place, 100 days into a 30-day window. The logrotate stanza is gone
# (see the Logrotate section below), and the trailing '*' lets this prune
# clean up the already-renamed backlog on the next run instead of leaving
# it stranded forever. The same trailing '*' covers the .age suffix and any
# stale .partial left by a killed run.
find "$BACKUP_DIR" \( -name 'libreclinica-*.sql.gz*' -o -name 'libreclinica-files-*.tar.gz*' \) \
  -mtime +"$RETENTION_DAYS" -delete

# Surface sizes to journald so `journalctl -u libreclinica-backup-db.service`
# tells the operator at a glance whether a dump shrank suspiciously.
for f in "${produced[@]}"; do
  size_human=$(numfmt --to=iec --suffix=B "$(stat -c '%s' "$f")")
  echo "[backup] $(basename "$f") (${size_human})"
done
exit "$rc"
EOF
chmod 0755 /usr/local/sbin/libreclinica-backup-db

cat >/etc/systemd/system/libreclinica-backup-db.service <<EOF
[Unit]
Description=LibreClinicaMUW nightly backup
After=libreclinica.service
Requires=libreclinica.service

[Service]
Type=oneshot
Environment=LIBRECLINICA_BACKUP_RETENTION_DAYS=${LIBRECLINICA_BACKUP_RETENTION_DAYS}
# File-store archiving, age encryption and the off-host copy; written above.
EnvironmentFile=-${BACKUP_ENV}
ExecStart=/usr/local/sbin/libreclinica-backup-db
EOF

cat >/etc/systemd/system/libreclinica-backup-db.timer <<'EOF'
[Unit]
Description=LibreClinicaMUW nightly backup (03:00 local + random 30 min jitter)

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
if [[ -n "$BACKUP_AGE_RECIPIENT" ]]; then
  log "Backups are age-encrypted to ${BACKUP_AGE_RECIPIENT:0:12}...; file stores: ${LIBRECLINICA_BACKUP_FILES:-false}; off-host: ${BACKUP_RSYNC_TARGET:-<none>}"
else
  log "Backups are NOT encrypted (no BACKUP_AGE_RECIPIENT); file stores: ${LIBRECLINICA_BACKUP_FILES:-false}"
fi

# ----------------------------- cluster monitor --------------------------------

section "Retinal cluster monitor (cron)"

# deploy/check-retinal-cluster.sh probes one inference node and prints only
# on a state change, so cron mails it. beta.9 also gave the System Status
# page a panel that tails the monitor's log - but nothing installed the cron,
# the script wrote no log, and the page's default path was outside every
# bind the container has. This wires all three: one cron line per node in
# core.retinalInference.clusterNodes, each teeing into the log the page
# reads (inside the monitor bind) with its own state file so the counters
# do not collide, and cron still gets the output for mail.
MONITOR_CRON=/etc/cron.d/libreclinica-retinal-monitor
DATAINFO="${INSTALL_PREFIX}/config/datainfo.properties"
# The beta.9 host had the probes in root's crontab, by hand, writing to the
# old unreadable path. Those would run alongside the cron.d entries and
# double every probe, so any root-crontab line that runs the monitor is
# dropped here; cron.d is the single owner from now on.
if crontab -l 2>/dev/null | grep -q 'check-retinal-cluster\.sh'; then
  log "Removing legacy root-crontab entries for check-retinal-cluster.sh (cron.d owns the monitor now)"
  crontab -l 2>/dev/null | grep -v 'check-retinal-cluster\.sh' | crontab - || true
fi
nodes="$(sed -n 's/^core\.retinalInference\.clusterNodes=//p' "$DATAINFO" 2>/dev/null | tail -1 | tr -d '[:space:]')"
mlog="$(sed -n 's/^core\.retinalInference\.clusterMonitorLog=//p' "$DATAINFO" 2>/dev/null | tail -1 | tr -d '[:space:]')"
if [[ -z "$nodes" ]]; then
  rm -f "$MONITOR_CRON"
  warn "core.retinalInference.clusterNodes is blank - no cluster monitor installed (set it as name=baseUrl,... and re-run)"
else
  [[ -n "$mlog" ]] || mlog="${MONITOR_DIR}/retinal-cluster-monitor.log"
  case "$mlog" in
    "${MONITOR_DIR}"/*) ;;
    *) warn "clusterMonitorLog=${mlog} is outside ${MONITOR_DIR}; the app container cannot read it there" ;;
  esac
  install -m 0644 -o libreclinica -g libreclinica /dev/null "$mlog" 2>/dev/null || touch "$mlog"
  {
    echo "# Installed by setup-ubuntu-host.sh - one probe per node in core.retinalInference.clusterNodes."
    echo "# Output goes to cron (mail on state change) AND to the log the System Status page tails."
    echo "SHELL=/bin/bash"
    echo "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
    IFS=',' read -ra entries <<<"$nodes"
    for e in "${entries[@]}"; do
      name="${e%%=*}"; url="${e#*=}"
      [[ -n "$name" && -n "$url" && "$name" != "$url" ]] || continue
      url="${url%/}/health"
      echo "*/5 * * * * root RETINAL_CLUSTER_STATE_FILE=${MONITOR_DIR}/retinal-cluster-${name}.state ${INSTALL_PREFIX}/deploy/check-retinal-cluster.sh ${url} 2>&1 | tee -a ${mlog}"
    done
  } > "$MONITOR_CRON"
  chmod 0644 "$MONITOR_CRON"
  log "Cluster monitor: $(grep -c '^\*/5' "$MONITOR_CRON") node(s) probed every 5 min -> ${mlog}"
fi

# ----------------------------- logrotate --------------------------------------

section "Logrotate (backup directory)"

# Docker container logs are already rotated by the json-file driver
# (50MB × 5 files = 250MB max per container — set in daemon.json above).
# We only need logrotate for the human-readable summary log we leave
# in /var/log/libreclinica/setup.log.
#
# The backup directory is deliberately NOT managed here any more. It used
# to be, as "a safety net on the backup directory in case retention via
# find ever misfires" — and the safety net is precisely what broke the
# thing it was guarding. logrotate renamed each dump to `.sql.gz.1`, the
# prune in libreclinica-backup-db matched only `*.sql.gz`, and so nothing
# was ever deleted: on the MUW host, every dump back to 2026-06-13
# survived a 30-day retention window.
#
# logrotate is the wrong tool for this directory regardless. It rotates
# append-mode log files that keep one stable name; these are discrete,
# already-compressed, already-timestamped dumps with their own pruning.
# Two mechanisms owning the same files is the bug. The `find -mtime`
# prune in libreclinica-backup-db is now the single owner, and its glob
# was widened to `*.sql.gz*` so it clears the renamed backlog too.
#
# Remove a stanza left by an earlier run of this script, so an existing
# host stops rotating backups on the next re-run rather than needing a
# manual cleanup.
if [[ -f /etc/logrotate.d/libreclinica ]] \
   && grep -q "${BACKUP_DIR}" /etc/logrotate.d/libreclinica; then
  log "Removing the legacy logrotate stanza for ${BACKUP_DIR} (it defeated backup retention)"
fi
cat >/etc/logrotate.d/libreclinica <<EOF
/var/log/libreclinica/setup.log {
    weekly
    rotate 12
    missingok
    notifempty
    compress
    delaycompress
    su libreclinica libreclinica
}
EOF

# ----------------------------- host firewall (internet-facing) ----------------

section "Host firewall"

if [[ "$IFACE" == "1" ]]; then
  # Two chains, kept apart from Docker's own and from anything the campus admins
  # add, so the whole thing is reversible with `libreclinica-firewall remove`:
  #   LIBRECLINICA-IN   hooked first in INPUT        -> SSH from ADMIN_CIDRS only
  #   LIBRECLINICA-FWD  hooked first in DOCKER-USER  -> from outside, only
  #                     published 80/443 (optionally only from DMZ_PROXY_CIDRS)
  # Docker publishes ports with DNAT, so published-port traffic never reaches
  # INPUT; DOCKER-USER (FORWARD, after DNAT) is the only place a rule can stop
  # it, and matches the ORIGINAL destination port with conntrack.
  cat >/usr/local/sbin/libreclinica-firewall <<'EOF'
#!/usr/bin/env bash
# libreclinica-firewall apply|remove|status  (written by setup-ubuntu-host.sh)
# Inputs: /etc/libreclinica/firewall.conf  (ADMIN_CIDRS, DMZ_PROXY_CIDRS, SSH_PORT)
set -euo pipefail

CONF=/etc/libreclinica/firewall.conf
IN=LIBRECLINICA-IN
FWD=LIBRECLINICA-FWD

[[ -r "$CONF" ]] || { echo "libreclinica-firewall: $CONF missing" >&2; exit 1; }
ADMIN_CIDRS="$(sed -n 's/^ADMIN_CIDRS=//p' "$CONF" | tail -1)"
DMZ_PROXY_CIDRS="$(sed -n 's/^DMZ_PROXY_CIDRS=//p' "$CONF" | tail -1)"
SSH_PORT="$(sed -n 's/^SSH_PORT=//p' "$CONF" | tail -1)"
SSH_PORT="${SSH_PORT:-22}"

# Entries of a comma list that belong to one IP family ("4" or "6").
family_of() { case "$1" in *:*) echo 6 ;; *) echo 4 ;; esac; }
cidrs_for() { # list family
  local c
  [[ -n "$1" ]] || return 0
  IFS=',' read -ra _l <<<"$1"
  for c in "${_l[@]}"; do [[ -n "$c" && "$(family_of "$c")" == "$2" ]] && echo "$c"; done
  return 0
}

unhook() { # cmd chain-name parent
  while "$1" -C "$3" -j "$2" 2>/dev/null; do "$1" -D "$3" -j "$2"; done
}

remove_family() {
  local cmd=$1
  command -v "$cmd" >/dev/null || return 0
  unhook "$cmd" "$IN" INPUT
  "$cmd" -L DOCKER-USER >/dev/null 2>&1 && unhook "$cmd" "$FWD" DOCKER-USER
  for ch in "$IN" "$FWD"; do
    if "$cmd" -L "$ch" >/dev/null 2>&1; then "$cmd" -F "$ch"; "$cmd" -X "$ch"; fi
  done
}

apply_family() {
  local cmd=$1 fam=$2 c
  command -v "$cmd" >/dev/null || { echo "libreclinica-firewall: $cmd not found, skipping IPv$fam" >&2; return 0; }
  local icmp=icmp; [[ "$fam" == "6" ]] && icmp=ipv6-icmp

  # ---- INPUT: the host's own services (SSH) ----
  "$cmd" -N "$IN" 2>/dev/null || "$cmd" -F "$IN"
  "$cmd" -A "$IN" -i lo -j ACCEPT
  "$cmd" -A "$IN" -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT
  "$cmd" -A "$IN" -p "$icmp" -j ACCEPT
  "$cmd" -A "$IN" -i docker0 -j ACCEPT
  "$cmd" -A "$IN" -i 'br-+' -j ACCEPT
  while read -r c; do
    [[ -n "$c" ]] && "$cmd" -A "$IN" -p tcp --dport "$SSH_PORT" -s "$c" -j ACCEPT
  done < <(cidrs_for "$ADMIN_CIDRS" "$fam")
  # 80/443 as seen by INPUT (only matters if a docker-proxy ever terminates them
  # on the host); same source restriction as the forwarded path below.
  if [[ -n "$DMZ_PROXY_CIDRS" ]]; then
    while read -r c; do
      [[ -n "$c" ]] && "$cmd" -A "$IN" -p tcp -m multiport --dports 80,443 -s "$c" -j ACCEPT
    done < <(cidrs_for "$DMZ_PROXY_CIDRS" "$fam")
  else
    "$cmd" -A "$IN" -p tcp -m multiport --dports 80,443 -j ACCEPT
  fi
  if [[ "$fam" == "4" ]]; then "$cmd" -A "$IN" -p udp --dport 68 -j ACCEPT   # DHCP client
  else "$cmd" -A "$IN" -p udp --dport 546 -j ACCEPT; fi
  "$cmd" -A "$IN" -j DROP
  "$cmd" -C INPUT -j "$IN" 2>/dev/null || "$cmd" -I INPUT 1 -j "$IN"

  # ---- DOCKER-USER: traffic forwarded to containers ----
  if ! "$cmd" -L DOCKER-USER >/dev/null 2>&1; then
    echo "libreclinica-firewall: no DOCKER-USER chain for IPv$fam (docker not running / ip6tables off): FORWARD filter skipped" >&2
    return 0
  fi
  "$cmd" -N "$FWD" 2>/dev/null || "$cmd" -F "$FWD"
  "$cmd" -A "$FWD" -i docker0 -j RETURN          # container-originated
  "$cmd" -A "$FWD" -i 'br-+' -j RETURN
  "$cmd" -A "$FWD" -m conntrack --ctstate RELATED,ESTABLISHED -j RETURN
  for port in 443 80; do
    if [[ -n "$DMZ_PROXY_CIDRS" ]]; then
      while read -r c; do
        [[ -n "$c" ]] && "$cmd" -A "$FWD" -p tcp -m conntrack --ctorigdstport "$port" -s "$c" -j RETURN
      done < <(cidrs_for "$DMZ_PROXY_CIDRS" "$fam")
    else
      "$cmd" -A "$FWD" -p tcp -m conntrack --ctorigdstport "$port" -j RETURN
    fi
  done
  "$cmd" -A "$FWD" -j DROP
  "$cmd" -C DOCKER-USER -j "$FWD" 2>/dev/null || "$cmd" -I DOCKER-USER 1 -j "$FWD"
}

case "${1:-}" in
  apply)
    apply_family iptables 4
    apply_family ip6tables 6
    echo "libreclinica-firewall: applied (SSH ${SSH_PORT} from ${ADMIN_CIDRS:-nobody}; 80/443 from ${DMZ_PROXY_CIDRS:-anywhere})"
    ;;
  remove)
    remove_family iptables
    remove_family ip6tables
    echo "libreclinica-firewall: removed"
    ;;
  status)
    for cmd in iptables ip6tables; do
      command -v "$cmd" >/dev/null || continue
      echo "== $cmd"
      for ch in "$IN" "$FWD"; do "$cmd" -S "$ch" 2>/dev/null || echo "(no $ch)"; done
      "$cmd" -S INPUT | grep -- "-j $IN" || echo "(INPUT does not jump to $IN)"
      "$cmd" -S DOCKER-USER 2>/dev/null | grep -- "-j $FWD" || echo "(DOCKER-USER does not jump to $FWD)"
    done
    ;;
  *) echo "usage: libreclinica-firewall apply|remove|status" >&2; exit 2 ;;
esac
EOF
  chmod 0755 /usr/local/sbin/libreclinica-firewall

  cat >/etc/systemd/system/libreclinica-firewall.service <<'EOF'
[Unit]
Description=LibreClinicaMUW host firewall (INPUT + DOCKER-USER)
Documentation=file:///opt/libreclinica/deploy/README.md
After=network-pre.target docker.service
Requires=docker.service
# Re-applied whenever Docker restarts, in case it rebuilt its chains.
PartOf=docker.service

[Service]
Type=oneshot
RemainAfterExit=yes
ExecStart=/usr/local/sbin/libreclinica-firewall apply
ExecStop=/usr/local/sbin/libreclinica-firewall remove

[Install]
WantedBy=multi-user.target
EOF
  systemctl daemon-reload
  systemctl enable libreclinica-firewall.service >/dev/null
  # restart (not start): picks up a changed firewall.conf on a re-run.
  systemctl restart libreclinica-firewall.service
  log "Firewall active: SSH ${SSH_PORT} from ${ADMIN_CIDRS}; 80/443 from ${DMZ_PROXY_CIDRS:-ANYWHERE}; everything else dropped"
  log "  Persisted: libreclinica-firewall.service (re-applied at boot and after a Docker restart)"
  log "  Inspect: libreclinica-firewall status   Remove: systemctl disable --now libreclinica-firewall"
elif [[ -f /etc/systemd/system/libreclinica-firewall.service ]]; then
  # Left the mode (INTERNET_FACING=false on a host that had it): take the rules out.
  systemctl disable --now libreclinica-firewall.service >/dev/null 2>&1 || true
  [[ -x /usr/local/sbin/libreclinica-firewall ]] && /usr/local/sbin/libreclinica-firewall remove || true
  rm -f /etc/systemd/system/libreclinica-firewall.service
  systemctl daemon-reload
  log "Removed the internet-facing firewall (libreclinica-firewall.service)"
else
  log "Internal mode: no host firewall (the campus perimeter is the control)"
fi

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
  5. Put the TLS certificate and key in /etc/libreclinica/tls (see
     deploy/nginx/README.md); the nginx sidecar terminates HTTPS on 80/443.
     From a host that may reach it, confirm:
        curl -Ik https://<fqdn>/login
  6. Verify the backup timer fires tonight:
        systemctl list-timers libreclinica-backup-db.timer
EOF

if [[ "$IFACE" == "1" ]]; then
  cat <<EOF

INTERNET-FACING mode is ON (this host is meant to sit behind the MUW DMZ proxy):
  - Firewall: SSH ${SSH_PORT} from ${ADMIN_CIDRS}; 80/443 from ${DMZ_PROXY_CIDRS:-ANYWHERE (set DMZ_PROXY_CIDRS!)}.
      libreclinica-firewall status | systemctl disable --now libreclinica-firewall
  - App: LIBRECLINICA_DEPLOYMENT_INTERNET_FACING=true, session idle timeout ${LIBRECLINICA_SESSION_MAX_INACTIVE}s,
    port 8080 bound to 127.0.0.1, no DICOM sidecar.
  - nginx: deploy/nginx/internet-facing.conf is included (404 for actuator, Swagger,
    public/internal/device APIs and the public portal pages).
  - Backups: age-encrypted to ${BACKUP_AGE_RECIPIENT:0:12}..., file stores included,
    off-host copy: ${BACKUP_RSYNC_TARGET:-none}.
Before go-live run the checklist in deploy/README.md
(§ "Internet-facing (multicenter) deployment"), including a restore test of an
encrypted backup with the PRIVATE key from its safe place.
EOF
fi
