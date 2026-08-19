#!/usr/bin/env bash
# =============================================================================
# retinal-inference — cluster server watchdog (cron)
# =============================================================================
# Restarts the cn5 inference server when its process has gone away.
#
# WHY PROCESS PRESENCE, NOT /health
#   A health-probe watchdog is tempting but dangerous here: `/run` drives a
#   multi-minute GPU `.sif` job, and if `/health` ever became slow under load
#   the watchdog would `pkill` the server *mid-segmentation* and destroy a
#   clinician's job. `pgrep` can only fire when the process is genuinely gone.
#
# WHY THIS IS NEEDED
#   cn5 is a shared, memory-pressured compute node (other users' jobs are
#   routinely OOM-killed on it). A resident server there can be OOM-killed or
#   cleaned up at any time — as happened on 2026-07-10, when every OCT job
#   failed with "Remote /run returned null". `systemd --user` is unavailable on
#   the node (no D-Bus instance) and lingering isn't granted, so cron is the
#   only self-healing mechanism available to an unprivileged user.
#
#   The structural fix is SLURM mode (§4 of the runbook): the resident process
#   becomes a thin dispatcher and the GPU work runs as per-scan `srun` jobs.
#   This watchdog is the stopgap until a SLURM account is granted.
#
# INSTALL (crontab -e):
#   */5 * * * * $HOME/libreclinicamuw/retinal-inference/scripts/cluster-watchdog.sh
#   @reboot sleep 60; $HOME/libreclinicamuw/retinal-inference/scripts/cluster-watchdog.sh
#
# The auth token is read from ~/.config/retinal-inference/env (chmod 600), so
# it never appears in `ps` or the crontab.
# =============================================================================
set -uo pipefail

REPO="${RI_REPO_ROOT:-$HOME/libreclinicamuw}"
ENV_FILE="${RI_ENV_FILE:-$HOME/.config/retinal-inference/env}"
LOG="${RI_WATCHDOG_LOG:-$HOME/ri/watchdog.log}"

# Already running — nothing to do. This is the common path, and it must be
# silent so cron doesn't mail on every tick.
if pgrep -f "uvicorn retinal_inference" >/dev/null 2>&1; then
  exit 0
fi

mkdir -p "$(dirname "$LOG")" 2>/dev/null || true

{
  echo "=== $(date -Is) — server process absent, restarting ==="
  if [ ! -r "$ENV_FILE" ]; then
    echo "FATAL: $ENV_FILE missing/unreadable (needs RETINAL_INFERENCE_AUTH_TOKEN)"
    exit 1
  fi
  # shellcheck disable=SC1090
  set -a; . "$ENV_FILE"; set +a

  # start-cluster-server.sh derives BM_LD_LIBRARY_PATH, asserts the DR-024
  # invariant, and fails non-zero if any task is missing — so a degraded
  # restart lands in this log rather than silently losing bm + layers.
  "$REPO/retinal-inference/scripts/start-cluster-server.sh"
} >> "$LOG" 2>&1
