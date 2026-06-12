# retinal-inference

LibreClinicaMUW task-agnostic retinal inference sidecar.

A separate FastAPI service that performs OCT/SLO retinal inference for the
LibreClinica platform. Ships with a deterministic placeholder adapter for
end-to-end development; the real `MirageAdapter` (MIRAGE encoder + per-task
decoders) lands in a follow-up slice.

v1 enables `task='ga'` (geographic atrophy / cRORA segmentation) only. The
HTTP contract, queue schema, and adapter abstraction carry a `task` field
from day one so adding fluid, layer, or classification tasks later requires
only a new decoder checkpoint.

## Layout

- `src/retinal_inference/main.py` — FastAPI app entry.
- `src/retinal_inference/api/` — `/health`, `/screen`, `/jobs/{id}` routers.
- `src/retinal_inference/inference/` — `RetinalInferenceAdapter` ABC,
  `PlaceholderAdapter`, stubbed `MirageAdapter`.
- `src/retinal_inference/db/` — psycopg2 pool + Postgres-as-queue helpers
  (`FOR UPDATE SKIP LOCKED`).
- `src/retinal_inference/worker.py` — background queue consumer.

## Local dev (without Docker)

Requires Python 3.11 and a reachable Postgres with the
`retinal_inference_job` + `retinal_inference_result` tables (created by the
Liquibase changeset on the Java side).

```sh
cd retinal-inference
python -m venv .venv && source .venv/bin/activate
pip install -e ".[dev]"
export RETINAL_INFERENCE_DB_URL=postgresql://clinica:clinica@localhost:5432/libreclinica
export RETINAL_INFERENCE_INFERENCE_ADAPTER=placeholder
uvicorn retinal_inference.main:app --reload
```

Then in another shell:

```sh
python -m retinal_inference.worker
```

## Tests

```sh
pip install -e ".[dev]"
pytest -v
```

DB-backed tests use `testcontainers` to spin up a Postgres image; they apply
the table DDL directly (mirroring the Liquibase changeset) so the suite is
self-contained.

## Environment

All env vars use the `RETINAL_INFERENCE_` prefix.

| Var | Default | Purpose |
|---|---|---|
| `RETINAL_INFERENCE_INFERENCE_ADAPTER` | `placeholder` | `placeholder` or `mirage`. |
| `RETINAL_INFERENCE_DB_URL` | (required) | Postgres URL. |
| `RETINAL_INFERENCE_SHARED_STORAGE_PATH` | `/var/lib/libreclinica/segmentation-output` | Where masks land. |
| `RETINAL_INFERENCE_E2E_UPLOADS_PATH` | `/var/lib/libreclinica/e2e-uploads` | Where the Java side drops uploaded E2Es. |
| `RETINAL_INFERENCE_WORKER_POLL_INTERVAL_S` | `2.0` | Worker poll interval. |
| `RETINAL_INFERENCE_FAST_SCREEN_TIMEOUT_S` | `8.0` | Soft sync-screen budget. |
| `RETINAL_INFERENCE_FAST_SCREEN_SLEEP_S` | `2.0` | Placeholder fast-screen sleep (set to `0` in tests). |
| `RETINAL_INFERENCE_FULL_VOLUME_SLEEP_S` | `30.0` | Placeholder full-volume sleep (set to `0` in tests). |
