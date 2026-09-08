#!/usr/bin/env bash
# =============================================================================
# App-VM monitor for the remote retinal-inference cluster server
# =============================================================================
# OCT segmentation depends on a server we don't control, on a shared,
# memory-pressured GPU node. On 2026-07-10 it died and the first anyone knew
# was a clinician's scan failing with "Remote /run returned null". This turns a
# silent outage into a notification.
#
# Checks, in order:
#   1. /health is reachable
#   2. it reports {"status":"ok"}
#   3. it registers ALL expected tasks — a server that comes back missing `bm`
#      and `layers` (a missing env var) is *degraded*, not healthy, and would
#      otherwise fail only later, per-job.
#
# Output discipline: prints NOTHING while healthy, so a cron entry mails only
# on a state change (down / recovered) or a periodic "still down" reminder.
# That keeps a multi-hour outage from generating a mail every 5 minutes.
#
# INSTALL (root crontab on the app VM):
#   */5 * * * * /opt/libreclinica/deploy/check-retinal-cluster.sh
#
# Exit: 0 healthy, 1 down/degraded, 2 usage/state error.
# =============================================================================
set -uo pipefail

URL="${1:-${RETINAL_CLUSTER_HEALTH_URL:-http://cn5.cir.meduniwien.ac.at:8000/health}}"
STATE="${RETINAL_CLUSTER_STATE_FILE:-/var/lib/libreclinica/retinal-cluster-monitor.state}"
# With a */5 cron this re-mails roughly hourly while the outage persists.
REMIND_EVERY="${RETINAL_CLUSTER_REMIND_EVERY:-12}"
TIMEOUT="${RETINAL_CLUSTER_TIMEOUT:-10}"
EXPECTED_TASKS="bm fluid ga layers onl pr"

fail=""
body="$(curl -sS -m "$TIMEOUT" "$URL" 2>&1)" || fail="unreachable — $body"

if [ -z "$fail" ] && ! printf '%s' "$body" | grep -q '"status":"ok"'; then
  fail="unexpected health body — $body"
fi

if [ -z "$fail" ]; then
  missing=""
  for t in $EXPECTED_TASKS; do
    printf '%s' "$body" | grep -q "\"$t\"" || missing="$missing $t"
  done
  [ -z "$missing" ] || fail="DEGRADED — supported_tasks missing:$missing"
fi

# ---- state (consecutive-failure counter) ------------------------------------
mkdir -p "$(dirname "$STATE")" 2>/dev/null || true
count=0
if [ -r "$STATE" ]; then
  count="$(cat "$STATE" 2>/dev/null || printf '0')"
  case "$count" in ''|*[!0-9]*) count=0 ;; esac
fi

if [ -n "$fail" ]; then
  count=$((count + 1))
  printf '%s' "$count" > "$STATE" 2>/dev/null || true
  # First failure, then only every Nth — don't spam during a long outage.
  if [ "$count" -eq 1 ] || [ $((count % REMIND_EVERY)) -eq 0 ]; then
    echo "RETINAL CLUSTER PROBLEM ($URL), failed check #$count"
    echo "  $fail"
    echo "  OCT jobs will fail with 'Remote /run returned null' until this is fixed."
    echo "  Recovery: ssh cn5 && retinal-inference/scripts/start-cluster-server.sh"
  fi
  exit 1
fi

# Healthy. Announce recovery once, then stay quiet.
if [ "$count" -gt 0 ]; then
  echo "RETINAL CLUSTER RECOVERED ($URL) after $count failed check(s)."
fi
printf '0' > "$STATE" 2>/dev/null || true
exit 0
