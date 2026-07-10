#!/usr/bin/env bash
# =============================================================================
# eCRF TLS certificate expiry monitor
# =============================================================================
# The MUW-CA certificate is renewed MANUALLY (no ACME on an internal-only host),
# so an expired cert is an easy-to-miss clinical outage. Run this daily and
# alert on a non-zero exit, e.g. via cron:
#
#   0 8 * * *  /opt/libreclinica/deploy/nginx/cert-expiry-check.sh \
#                || echo "eCRF TLS cert needs renewal" | mail -s "eCRF cert" you@meduniwien.ac.at
#
# Usage: cert-expiry-check.sh [cert-path] [warn-days]
#   cert-path  default /etc/libreclinica/tls/ecrf-augen.crt
#   warn-days  default 30
#
# Exit codes: 0 = OK, 1 = expiring/expired (alert), 2 = cannot read cert.
# =============================================================================
set -euo pipefail

CERT="${1:-/etc/libreclinica/tls/ecrf-augen.crt}"
WARN_DAYS="${2:-30}"

if [[ ! -r "$CERT" ]]; then
  echo "cert-expiry-check: cannot read $CERT" >&2
  exit 2
fi

end_date=$(openssl x509 -in "$CERT" -noout -enddate | cut -d= -f2)
end_epoch=$(date -d "$end_date" +%s)
now_epoch=$(date +%s)
days_left=$(( (end_epoch - now_epoch) / 86400 ))

if (( days_left < 0 )); then
  echo "CRITICAL: TLS cert $CERT EXPIRED $(( -days_left )) day(s) ago ($end_date)" >&2
  exit 1
elif (( days_left <= WARN_DAYS )); then
  echo "WARNING: TLS cert $CERT expires in $days_left day(s) ($end_date) — renew it" >&2
  exit 1
fi

echo "OK: TLS cert $CERT valid for $days_left more day(s) ($end_date)"
