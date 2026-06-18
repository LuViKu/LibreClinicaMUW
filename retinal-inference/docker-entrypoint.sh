#!/bin/sh
# Sidecar / standalone inference-server entrypoint.
#
# The DB-poll worker is opt-in via RETINAL_INFERENCE_WORKER_ENABLED:
#   - true  (default) → start the worker alongside uvicorn. Matches the
#                       single-host dev compose where Postgres is reachable
#                       and the worker drains queued jobs.
#   - false           → uvicorn only. Used on the GPU VM where there is no
#                       Postgres; the institutional Tomcat reaches the
#                       server via POST /run (DR-022 stateless flow).
set -e

WORKER_ENABLED="${RETINAL_INFERENCE_WORKER_ENABLED:-true}"

if [ "$WORKER_ENABLED" = "true" ]; then
    python -m retinal_inference.worker &
    WORKER_PID=$!
fi

uvicorn retinal_inference.main:app --host 0.0.0.0 --port 8000 &
SERVER_PID=$!

if [ -n "${WORKER_PID:-}" ]; then
    trap "kill $WORKER_PID $SERVER_PID 2>/dev/null" TERM INT
else
    trap "kill $SERVER_PID 2>/dev/null" TERM INT
fi

wait $SERVER_PID