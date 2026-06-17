# Retinal-inference on the OPTIMA GPU cluster (DR-022)

Runbook for running the inference server on the OPTIMA Apptainer/SLURM cluster
(`cn5.cir.meduniwien.ac.at`), reached from the LibreClinica app VM over the
internal MUW network.

## Topology

```
LibreClinica (app VM, 149.148.170.183)
   │  POST /run   (multipart .e2e + task + laterality, header X-MUW-Inference-Token)
   ▼
inference server  (bare conda/venv Python 3.11 on the cluster; ADAPTER=apptainer)
   │  per scan: ingest .e2e→bscan.dcm (PHI-redacted) in a tempdir on /scratch,
   │            then run the model's .sif
   ▼
   {apptainer|singularity} exec --nv <model>.sif …   (direct now; srun-per-scan once a SLURM account exists)
   → parse output → JSON envelope → tempdir deleted (stateless)
```

Confirmed cluster facts (June 2026): same internal network (cn5↔app VM route, HTTP
302); `/scratch` is shared NFS (3.4 T free); SLURM caps every partition at 2 days;
**the user has no SLURM association yet** (so `srun` is blocked — direct mode until
an admin grants an account, likely `optima`); the container runtime is
**`singularity 3.8.7` at `/usr/bin/singularity`** (no `apptainer`, no module).

## 0. Container runtime
Confirmed: `singularity 3.8.7-1.el7` on PATH. Set:
```
RETINAL_INFERENCE_APPTAINER_BIN=singularity
```
Singularity 3.8 supports every flag the adapter emits (`exec`/`run`/`--nv`/
`--bind`/`--no-home`/`-e`), so no code change vs Apptainer.

## 1. Provision images + code + weights (shared path, not git)
Place under e.g. `/scratch/$USER/ri/` (or `/home/optima/...`):
| Task | .sif | code dir | weights |
|---|---|---|---|
| fluid | `fluid_segmentation.sif` (v2.5.0) | — (baked) | — (baked) |
| onl | `sese_onl.sif` | `sese_onl/code/outernuclearlayer-segmentation` | `cross_val_ga/` |
| bmeis | build from `runners/bmeis/apptainer.def` | `sese_pr/code/photoreceptors-segmentation` | `u2net-cross-entropy/` |
| ga (gated) | `pytorch_optima_dl.v4.sif` | `sese_ga/code` | `filly_checkpoints/` + IOWA `OCTLayerSeg` |

```sh
singularity build --fakeroot /scratch/$USER/ri/bmeis.sif \
  <repo>/retinal-inference/runners/bmeis/apptainer.def
```
If `--fakeroot` isn't enabled for your user (common on shared clusters), build
the `.sif` on a box where you have sudo/root and copy it over, or use a remote
builder — the `.def` itself is unchanged.

## 2. The server (bare Python, NOT in a container — it launches the .sif's)
```sh
cd <repo>/retinal-inference
python3.11 -m venv .venv && . .venv/bin/activate && pip install -e .
mkdir -p /scratch/$USER/retinal-inference/tmp
```

## 3. Run — direct mode (works now, no SLURM account needed)
```sh
RETINAL_INFERENCE_INFERENCE_ADAPTER=apptainer \
RETINAL_INFERENCE_RUN_ENDPOINT_ENABLED=true \
RETINAL_INFERENCE_WORKER_ENABLED=false \
RETINAL_INFERENCE_AUTH_TOKEN=<shared-secret> \
RETINAL_INFERENCE_SHARED_TMPDIR=/scratch/$USER/retinal-inference/tmp \
RETINAL_INFERENCE_APPTAINER_BIN=singularity \
RETINAL_INFERENCE_APPTAINER_USE_SLURM=false \
RETINAL_INFERENCE_APPTAINER_GPU_DEVICE=0 \
RETINAL_INFERENCE_FLUID_SIF=/scratch/$USER/ri/fluid_segmentation.sif \
RETINAL_INFERENCE_ONL_SIF=/scratch/$USER/ri/sese_onl.sif \
RETINAL_INFERENCE_ONL_CODE=/scratch/$USER/ri/sese_onl/code/outernuclearlayer-segmentation \
RETINAL_INFERENCE_ONL_WEIGHTS=/scratch/$USER/ri/cross_val_ga \
RETINAL_INFERENCE_BMEIS_SIF=/scratch/$USER/ri/bmeis.sif \
RETINAL_INFERENCE_BMEIS_CODE=/scratch/$USER/ri/sese_pr/code/photoreceptors-segmentation \
RETINAL_INFERENCE_BMEIS_WEIGHTS=/scratch/$USER/ri/u2net-cross-entropy \
  nohup uvicorn retinal_inference.main:app --host 0.0.0.0 --port 8000 \
  > /scratch/$USER/retinal-inference/server.log 2>&1 &
```
- `GPU_DEVICE=0` — an idle card (GPU 3 was busy). For BMEIS the CUDA-10 `.sif`
  runs on any of the 6, so no Turing pin needed.
- `ga` is omitted (gated): set `RETINAL_INFERENCE_GA_SIF/_CODE/_WEIGHTS/_IOWA_BINARY`
  only once the IOWA binary is in place.
- Persistence: `nohup`/`tmux` to start; a `systemd --user` unit if your admin
  permits a long-lived service on the node.

## 4. Flip to SLURM (production, after an account is granted)
```sh
RETINAL_INFERENCE_APPTAINER_USE_SLURM=true \
RETINAL_INFERENCE_APPTAINER_SLURM_PARTITION=full_optima \
RETINAL_INFERENCE_APPTAINER_SLURM_ACCOUNT=<account> \
RETINAL_INFERENCE_APPTAINER_SLURM_TIME=01:00:00 \
RETINAL_INFERENCE_APPTAINER_SLURM_GRES=gpu:1
```
Each scan then runs as one blocking `srun … apptainer exec --nv …` job (no GPU
pin — SLURM assigns it). `shared_tmpdir` MUST stay on the shared FS so the
compute node sees the `bscan.dcm`.

## 5. Wire the app VM
Set `core.retinalInference.remotePushUrl=http://149.148.108.173:8000/run` and the
matching token on the Java side. Reachability test from the app VM:
```sh
curl -sS -m5 -o /dev/null -w "%{http_code}\n" http://149.148.108.173:8000/health
```

## 6. Per-model validation (first real run)
For each task: POST a real `.e2e`, confirm the model **accepts the synthesized
`bscan.dcm`**, the output artifact name/parse matches (`fluidseg.npz` labels;
ONL/BMEIS surface CSVs; GA RPEL), and the metric is sane. See each
`runners/<task>/README.md` "confirm on first run" list.

## Open decisions
- **Payload**: `/run` currently takes `.e2e` and ingests on the cluster (stateless
  + PHI-redacted). If you prefer the earlier `.dcm`-from-Java approach, the
  adapter already passes a `.dcm` through unchanged — only `/run` + the Java
  client need to switch to sending DICOM.
- **ONL/BMEIS primary metric**: mean thickness / mean depth are placeholders —
  confirm the clinical metric.
- **Dispatcher host / persistence policy**: confirm a long-lived service is
  allowed on a node, else submit from the app VM over SSH.
