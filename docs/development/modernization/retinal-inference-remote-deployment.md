# Remote GPU sidecar deployment — runbook

DR-022 architecture (decision record:
[decision-record.md § DR-022](decision-record.md#dr-022--remote-stateless-gpu-sidecar-for-retinal-inference)).
Sidecar + per-task runners live on a Linux VM with NVIDIA TITAN + RTX 2080;
the institutional Tomcat reaches it via `POST /run` over the same institutional
network. This runbook covers the GPU host setup, the institutional Tomcat
config, the smoke test, and the operational gotchas.

## Topology

```
   ┌──────────────────────────────┐         ┌──────────────────────────────┐
   │  Institutional Tomcat host   │         │   GPU VM (TITAN / RTX 2080)  │
   │                              │         │                              │
   │  ┌────────────────────────┐  │ HTTPS   │  ┌────────────────────────┐  │
   │  │ libreclinica (web war) │──┼─────────┼─▶│ retinal-inference      │  │
   │  │   - RetinalInference   │  │ POST    │  │   /run (multipart)     │  │
   │  │     ApiController      │  │  /run   │  │   /screen (legacy)     │  │
   │  │   - RemoteRetinal      │  │         │  │                        │  │
   │  │     InferenceClient    │  │         │  │  ┌──────────────────┐  │  │
   │  │   - RetinalArtifact    │◀─┼─────────┼──│  │ /var/lib/retinal │  │  │
   │  │     StorageService     │  │ envelope │  │  │ -inference/tmp  │  │  │
   │  └────────────────────────┘  │         │  │  │ (TemporaryDir)  │  │  │
   │                              │         │  │  └────────┬─────────┘  │  │
   │  /var/lib/libreclinica/      │         │  │           │ shared bind │  │
   │    retinal-artifacts/        │         │  │  ┌────────┴────────┐    │  │
   │    └── <jobUuid>/...csv      │         │  │  │ retinal-runner- │    │  │
   │                              │         │  │  │   fluid/onl/    │    │  │
   │                              │         │  │  │   bmeis/ga      │    │  │
   │                              │         │  │  └─────────────────┘    │  │
   │                              │         │  └────────────────────────┘  │
   └──────────────────────────────┘         └──────────────────────────────┘
```

## GPU host bring-up

### 1. Prerequisites

- Linux with NVIDIA driver + `nvidia-container-toolkit`.
- Docker + Docker Compose v2.
- The same `compose.yaml`, runner Dockerfiles, and vendor blobs the dev compose uses.
  Vendor weights live under `./docker/retinal-weights/`; the runners pick them up via
  the `RETINAL_*_WEIGHTS_DIR` env vars.
- The shared host bind directory exists and is writable by the docker daemon:

  ```sh
  sudo mkdir -p /srv/retinal-inference/tmp
  sudo chmod 1777 /srv/retinal-inference/tmp   # any container UID can mkdir its run-XXX subdir
  ```

  You can also keep the default `./docker/retinal-tmp` in-repo if the compose
  project lives on a fast SSD.

### 2. Compose env

Set these on the GPU host before `docker compose up`. Drop them into
`/srv/retinal-inference/.env` and source it once:

```sh
# Enable the /run endpoint (off by default so dev compose doesn't expose it)
export RETINAL_INFERENCE_RUN_ENDPOINT_ENABLED=true

# Shared secret the institutional Tomcat sends as X-MUW-Inference-Token.
# Generate one with: openssl rand -hex 32
export RETINAL_INFERENCE_AUTH_TOKEN=<32-byte-hex>

# Real adapter (the placeholder is only for the institutional dev compose)
export RETINAL_INFERENCE_ADAPTER=optima

# Real GPU runs in seconds — drop the dev-default 60 min to fail fast
export RETINAL_INFERENCE_RUNNER_TIMEOUT_S=300

# DR-022 split: no Postgres on the GPU host. Disable the DB-poll worker so
# the entrypoint runs uvicorn only.
export RETINAL_INFERENCE_WORKER_ENABLED=false
```

### 3. Start ONLY the inference services

Per DR-022 the GPU VM runs the inference server + the per-task runners and
NOTHING ELSE — no libreclinica, no Postgres, no SMTP. Name the services
explicitly on the compose-up so the dependency graph doesn't pull anything
else in:

```sh
docker compose --profile optima up -d \
    retinal-inference \
    retinal-runner-fluid \
    retinal-runner-onl \
    retinal-runner-pr
```

For the GA runner (gated by its own profile + needs the IOWA binary +
`.sif`):

```sh
docker compose --profile optima --profile ga up -d \
    retinal-inference \
    retinal-runner-fluid retinal-runner-onl retinal-runner-pr \
    retinal-runner-ga
```

Verify nothing extra is running:

```sh
docker compose ps
# Should list only the retinal-inference + retinal-runner-* services.
# No libreclinica-muw-db-1, no libreclinica-muw-libreclinica-1.
```

### 4. TLS termination

Put a reverse proxy in front of the sidecar's 8000 — `nginx` or `caddy` both
work; the sidecar speaks plain HTTP internally. The reverse proxy should:

- Terminate TLS (institutional cert).
- Forward `X-MUW-Inference-Token` untouched.
- Set a body-size limit ≥ 200 MB (Heidelberg cubes hit ~100 MB).
- Set a read timeout matching the Java-side `remotePushTimeoutSecs` (default 3600).
- Restrict ingress to the institutional Tomcat host's IP.

## Institutional Tomcat config

Add (or override via `/etc/libreclinica/env`) the four DR-022 config keys:

```properties
# datainfo.properties (or env-injected at Tomcat startup — preferred for the token)
core.retinalInference.remotePushUrl=https://gpu-host.med.local:8443
core.retinalInference.remotePushToken=<same value as RETINAL_INFERENCE_AUTH_TOKEN>
core.retinalInference.remotePushTimeoutSecs=300
core.retinalInference.artifactStorePath=/var/lib/libreclinica/retinal-artifacts
```

Make sure `/var/lib/libreclinica/retinal-artifacts/` exists + is writable by the
Tomcat user.

When `remotePushUrl` is blank, the controller skips the remote branch entirely
and runs the legacy `/screen` + DB-poll path — single-host dev compose keeps
working unchanged.

## Smoke test

### Sidecar standalone (GPU host)

```sh
# Health
curl -fsS https://gpu-host.med.local:8443/health | jq

# /run with a sample E2E
curl -fsS \
  -H "X-MUW-Inference-Token: $RETINAL_INFERENCE_AUTH_TOKEN" \
  -F "file=@sample.e2e" \
  -F "task=onl" \
  -F "laterality=OD" \
  https://gpu-host.med.local:8443/run | jq
```

You should get a JSON envelope with `model_version`, `primary_metric_value`,
`output_payload`, and an `artifacts` array. The tempdir on the GPU host should
be gone:

```sh
ls /srv/retinal-inference/tmp   # empty after the response completes
```

### End-to-end through Java

Upload an E2E via the SPA (or directly to `POST /pages/api/v1/event-crfs/{id}/oct-upload`).
Verify on the institutional host:

- `retinal_inference_job` row reaches `status='done'` with a non-null
  `completed_at` + `model_version`.
- `retinal_inference_result` row carries `bscan_masks_dir` pointing at a
  freshly-populated UUID directory under `artifactStorePath`.
- The directory contains the runner's CSVs (e.g. `001-OPL-HFL.csv`,
  `002-BMEIS.csv` for ONL).

## Operational gotchas

- **Tempdir leak on crash.** If the sidecar process dies mid-request, its
  `TemporaryDirectory` isn't cleaned. The sidecar's startup sweeper task
  removes `tmp_*` entries older than `2 × runner_timeout_s`; if you trip over
  stale dirs after a hard kill, restart the sidecar.
- **Idempotency LRU clears on restart.** A retry POST after a sidecar restart
  re-runs inference instead of returning the cached envelope. Acceptable for
  v1; revisit if operators report duplicated billing or audit events.
- **Disk pressure on the shared tempdir.** Each request writes the bscan DCM
  (~50 MB) + per-bscan masks (~50 MB) into the tempdir. The sidecar runs
  one request at a time (`asyncio.Lock` in `api/run.py`); higher concurrency
  needs additional uvicorn workers and a roomier disk.
- **Auth token rotation.** Both ends read the token at runtime; rotate by
  redeploying the GPU host with a new `RETINAL_INFERENCE_AUTH_TOKEN` and
  updating `core.retinalInference.remotePushToken` on the institutional side
  (env override + Tomcat restart).
- **Network reachability.** Same institutional network is assumed — no
  ndjson heartbeats. If MUW's network team ever introduces a buffering egress
  proxy between Tomcat and the GPU host, switch the transport per
  DR-022 § "ndjson heartbeats" out-of-scope row.