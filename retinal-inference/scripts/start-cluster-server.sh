#!/usr/bin/env bash
# =============================================================================
# retinal-inference — cluster (cn5) run-only server launcher
# =============================================================================
# Starts the DICOM-only `/run` server with the ApptainerAdapter. This is the
# single source of truth for the cluster environment; see
# ../docs/cluster-deployment.md for the asset paths + one-time setup.
#
# WHY THIS EXISTS
#   The env block used to live in an untracked ~/start_sidecar.sh with
#   `BM_LD_LIBRARY_PATH` left as a TODO comment. Any restart therefore came up
#   silently missing the `bm` AND `layers` tasks (the BM venv python can't find
#   libpython3.8.so without it). This script:
#     * derives BM_LD_LIBRARY_PATH from the LMOD modules instead of hardcoding
#       a 2 KB path that rots on the next module upgrade,
#     * derives it in a SUBSHELL — loading Python/3.8.2 into the launch shell
#       would shadow the conda 3.11 python that uvicorn runs under,
#     * asserts every expected task is registered after startup, so a missing
#       var fails loudly instead of quietly degrading the pipeline.
#
# USAGE
#   start-cluster-server.sh              # restart in background (nohup) + verify
#   start-cluster-server.sh --foreground # exec uvicorn in fg (for systemd)
#   start-cluster-server.sh --check      # verify env + invariants, don't launch
#
# Every path can be overridden by exporting the matching env var first.
# =============================================================================
set -euo pipefail

MODE="${1:-background}"

# ----------------------------- paths ------------------------------------------
: "${RI_HOME:=/home/optima/$USER}"                 # persistent, user-owned assets
: "${RI_SHARED:=/home/optima/octreader}"           # shared read-only model tree
: "${RI_REPO:=$RI_HOME/libreclinicamuw/retinal-inference}"
: "${RI_UVICORN:=$RI_HOME/ri-env/bin/uvicorn}"     # conda env's uvicorn (py3.11)
: "${RI_SCRATCH:=/scratch/$USER/retinal-inference}"
: "${RI_PORT:=8000}"

LOG="$RI_SCRATCH/server.log"

log()  { printf '\033[1;32m[ri]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[ri]\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31m[ri]\033[0m %s\n' "$*" >&2; exit 1; }

# ----------------------------- server config ----------------------------------
export RETINAL_INFERENCE_INFERENCE_ADAPTER=apptainer
export RETINAL_INFERENCE_RUN_ENDPOINT_ENABLED=true
export RETINAL_INFERENCE_WORKER_ENABLED=false
export RETINAL_INFERENCE_SHARED_TMPDIR="$RI_SCRATCH/tmp"
export RETINAL_INFERENCE_APPTAINER_BIN=singularity
export RETINAL_INFERENCE_APPTAINER_USE_SLURM=false
export RETINAL_INFERENCE_APPTAINER_GPU_DEVICE="${RETINAL_INFERENCE_APPTAINER_GPU_DEVICE:-0}"

# Shared secret must match core.retinalInference.remotePushToken on the app VM.
: "${RETINAL_INFERENCE_AUTH_TOKEN:?set RETINAL_INFERENCE_AUTH_TOKEN (must match the app VM's remotePushToken)}"
export RETINAL_INFERENCE_AUTH_TOKEN

# fluid / onl / pr — apptainer .sif dispatch
export RETINAL_INFERENCE_FLUID_SIF="$RI_SHARED/Processor_Implementations/sese_retinsight_fluid/code/v2.5.0/fluid_segmentation.sif"
export RETINAL_INFERENCE_ONL_SIF="$RI_SHARED/Processor_Implementations/sese_onl/singularity/sese_onl.sif"
export RETINAL_INFERENCE_ONL_CODE="$RI_SHARED/Processor_Implementations/sese_onl/code/outernuclearlayer-segmentation"
export RETINAL_INFERENCE_ONL_WEIGHTS="$RI_SHARED/Processor_Implementations/sese_onl/weights/cross_val_ga"
export RETINAL_INFERENCE_PR_SIF="$RI_HOME/ri/pr.sif"
export RETINAL_INFERENCE_PR_CODE="$RI_SHARED/Processor_Implementations/sese_pr/code/photoreceptors-segmentation"
export RETINAL_INFERENCE_PR_WEIGHTS="$RI_SHARED/Processor_Implementations/sese_pr/weights/u2net-cross-entropy"

# ga — .sif + host-native IOWA chain. GA_CODE / IOWA_BINARY must be readable,
# owned copies (the originals are octreader-locked rwx------).
export RETINAL_INFERENCE_GA_SIF="$RI_SHARED/Processor_Implementations/sese_ga/pytorch_optima_dl.v4.sif"
export RETINAL_INFERENCE_GA_CODE="$RI_HOME/ri/sese_ga_code"
export RETINAL_INFERENCE_GA_WEIGHTS="$RI_SHARED/Processor_Implementations/sese_ga/weights/filly_checkpoints"
export RETINAL_INFERENCE_GA_IOWA_BINARY="$RI_HOME/ri/OCTLayerSeg3.6_owned"
export RETINAL_INFERENCE_GA_IOWA_CONVERTER="$RI_SHARED/optima-framework/deployment/prod/local_IOWA_LayerSegV3_to_CSV"
export RETINAL_INFERENCE_GA_IOWA_LD_LIBRARY_PATH="$RI_HOME/ri-env/lib:$RI_SHARED/optima-framework/deployment/prod/lib:$RI_SHARED/optima/drlresults/releasegcc4"

# bm — host-native (cluster venv python, not a .sif). `layers` reuses the GA
# IOWA env + the BM env, so it comes for free once both groups are set.
export RETINAL_INFERENCE_BM_PYTHON="$RI_SHARED/Processor_Implementations/sese_bm_final/venv/bin/python3"
export RETINAL_INFERENCE_BM_CODE="$RI_SHARED/Processor_Implementations/sese_bm_final/code"
export RETINAL_INFERENCE_BM_GPU_DEVICE="${RETINAL_INFERENCE_BM_GPU_DEVICE:-0}"

# ----------------------------- BM_LD_LIBRARY_PATH -----------------------------
# The BM venv python needs the LMOD module lib dirs (libpython3.8.so et al).
# Derive them rather than hardcoding the ~2 KB path — and derive them in a
# SUBSHELL, because `module load Python/3.8.2` in this shell would shadow the
# conda 3.11 interpreter uvicorn must run under.
BM_MODULES="${RETINAL_INFERENCE_BM_MODULES:-Python/3.8.2-foss-2019a CUDA/11.1.1-GCCcore-8.2.0 cuDNN/8.2.1.32-CUDA-11.1.1}"

derive_bm_ld() {
  (
    set +eu
    if ! command -v module >/dev/null 2>&1; then
      for init in /etc/profile.d/lmod.sh /etc/profile.d/modules.sh \
                  /usr/share/lmod/lmod/init/bash /usr/share/Modules/init/bash; do
        [ -r "$init" ] && . "$init" && break
      done
    fi
    command -v module >/dev/null 2>&1 || exit 1
    module purge >/dev/null 2>&1 || true
    # shellcheck disable=SC2086
    module load $BM_MODULES >/dev/null 2>&1 || exit 1
    printf '%s' "${LD_LIBRARY_PATH:-}"
  )
}

if [ -z "${RETINAL_INFERENCE_BM_LD_LIBRARY_PATH:-}" ]; then
  RETINAL_INFERENCE_BM_LD_LIBRARY_PATH="$(derive_bm_ld || true)"
fi
[ -n "$RETINAL_INFERENCE_BM_LD_LIBRARY_PATH" ] || die \
  "could not derive BM_LD_LIBRARY_PATH from modules ($BM_MODULES).
   Without it the 'bm' AND 'layers' tasks are silently unavailable.
   Fix the module list, or export RETINAL_INFERENCE_BM_LD_LIBRARY_PATH yourself."
export RETINAL_INFERENCE_BM_LD_LIBRARY_PATH

# ----------------------------- preflight --------------------------------------
EXPECTED_TASKS="bm fluid ga layers onl pr"

preflight() {
  [ -x "$RI_UVICORN" ] || die "uvicorn not found/executable at $RI_UVICORN"
  [ -d "$RI_REPO" ]    || die "repo not found at $RI_REPO"
  mkdir -p "$RETINAL_INFERENCE_SHARED_TMPDIR"

  # DR-024 (load-bearing): the cluster is run-only + DICOM-only. Installing the
  # sibling muw-e2e-converter here would be a deployment bug — .e2e conversion
  # happens app-side, and the ApptainerAdapter rejects .e2e inputs.
  if "$RI_HOME/ri-env/bin/python" -c "import muw_e2e_converter" >/dev/null 2>&1; then
    die "muw-e2e-converter IS installed in the cluster env — violates DR-024. Uninstall it."
  fi
  log "DR-024 invariant holds (muw-e2e-converter absent)"
  log "BM_LD_LIBRARY_PATH derived (${#RETINAL_INFERENCE_BM_LD_LIBRARY_PATH} chars)"
}

# Assert the server registered every task we expect. A short list means an env
# var didn't take — exactly the failure mode this script exists to prevent.
assert_tasks() {
  local health missing=""
  health="$(curl -sS "http://127.0.0.1:$RI_PORT/health")" || die "health endpoint unreachable"
  for t in $EXPECTED_TASKS; do
    printf '%s' "$health" | grep -q "\"$t\"" || missing="$missing $t"
  done
  if [ -n "$missing" ]; then
    warn "health: $health"
    die "supported_tasks is missing:$missing — an env var did not take"
  fi
  log "all tasks registered: $EXPECTED_TASKS"
  log "health: $health"
}

# ----------------------------- modes ------------------------------------------
cd "$RI_REPO"

case "$MODE" in
  --check)
    preflight
    if curl -sS -m3 "http://127.0.0.1:$RI_PORT/health" >/dev/null 2>&1; then
      assert_tasks
    else
      log "server not currently running on :$RI_PORT (env checks passed)"
    fi
    ;;

  --foreground)
    # systemd runs us; it owns restart + logging. No nohup, no pkill.
    preflight
    log "exec uvicorn (foreground) on :$RI_PORT"
    exec "$RI_UVICORN" retinal_inference.main:app --host 0.0.0.0 --port "$RI_PORT"
    ;;

  background)
    preflight
    mkdir -p "$RI_SCRATCH"
    log "stopping any existing server"
    pkill -f "uvicorn retinal_inference" || true
    sleep 2
    log "starting uvicorn on :$RI_PORT (log: $LOG)"
    nohup "$RI_UVICORN" retinal_inference.main:app --host 0.0.0.0 --port "$RI_PORT" \
      > "$LOG" 2>&1 &
    # uvicorn needs a few seconds to import + bind; poll rather than sleep-guess.
    for _ in $(seq 1 20); do
      curl -sS -m2 "http://127.0.0.1:$RI_PORT/health" >/dev/null 2>&1 && break
      sleep 1
    done
    pgrep -fa "uvicorn retinal_inference" || die "server failed to start — see $LOG"
    assert_tasks
    ;;

  *) die "unknown mode: $MODE (use: background | --foreground | --check)" ;;
esac
