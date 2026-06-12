#!/bin/sh
set -e
python -m retinal_inference.worker &
WORKER_PID=$!
uvicorn retinal_inference.main:app --host 0.0.0.0 --port 8000 &
SERVER_PID=$!
trap "kill $WORKER_PID $SERVER_PID 2>/dev/null" TERM INT
wait $SERVER_PID
