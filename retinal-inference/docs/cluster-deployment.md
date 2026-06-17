# Retinal-inference on the OPTIMA GPU cluster (DR-022)

Runbook for running the inference server on the OPTIMA Apptainer/SLURM cluster
(`cn5.cir.meduniwien.ac.at`), reached from the LibreClinica app VM over the
internal MUW network.

## Topology

```
LibreClinica (app VM, 149.148.170.183) — converts .e2e → bscan.dcm (PHI-redacted) app-side
   │  POST /run   (multipart bscan.dcm + task + laterality, header X-MUW-Inference-Token)
   ▼
inference server  (bare venv Python 3.11 on the cluster; ADAPTER=apptainer; DICOM-only)
   │  per scan: write bscan.dcm into a tempdir on /scratch, run the model's .sif
   ▼
   singularity exec --nv <model>.sif …   (direct now; srun-per-scan once a SLURM account exists)
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
The assets already live on shared storage under
`$PI = /home/optima/octreader/Processor_Implementations` (confirmed June 2026 by a
full recursive listing). The `.sif`s are large (fluid 2.9 G, onl 6.0 G, ga 10.1 G);
**bind them in place — no copy needed.** Only the PR `.sif` must be built (none
ships for `sese_pr`). Resolved canonical paths:

| Task | .sif | code dir | weights |
|---|---|---|---|
| fluid | `$PI/sese_retinsight_fluid/code/v2.5.0/fluid_segmentation.sif` | — (baked) | — (baked) |
| onl | `$PI/sese_onl/singularity/sese_onl.sif` | `$PI/sese_onl/code/outernuclearlayer-segmentation` | `$PI/sese_onl/weights/cross_val_ga` (5-fold ensemble; single-model alt `…/weights/onl_seg_vanilla_unet.pth`) |
| pr | **build** from `runners/pr/apptainer.def` → `/scratch/$USER/ri/pr.sif` | `$PI/sese_pr/code/photoreceptors-segmentation` | `$PI/sese_pr/weights/u2net-cross-entropy` |
| ga (gated) | `$PI/sese_ga/pytorch_optima_dl.v4.sif` | `$PI/sese_ga/code` (`common/`+`prepare_data/` are empty on disk — the real ones live inside the `.sif`) | `$PI/sese_ga/weights/filly_checkpoints` (5× `w.ckpt`) |

> **GA's IOWA step (binary located).** GA needs an 11-layer IOWA segmentation as
> `--LayerSegPath` input — a *folder of 11 layer CSVs* (not raw XML; see
> `infer_sample_filly.py:84,105`). The chain the adapter's `_ga` runs:
> 1. `OCTLayerSeg3.6 -oM bscan.dcm lres.xml …` — the licensed IOWA native binary
>    (host, **not** a `.sif`) at `/home/optima/octreader/OCTLayerSeg3.6` → `GA_IOWA_BINARY`.
> 2. `local_IOWA_LayerSegV3_to_CSV --in lres.xml --intype iowaxml_ls --out <dir>
>    --outtype csv` at `/home/optima/octreader/optima-framework/deployment/prod/local_IOWA_LayerSegV3_to_CSV`
>    → `GA_IOWA_CONVERTER`. (XML→CSV flags reconstructed from the vendor's csv→vrcbin
>    usage — **validate on the first GA run.**)
> 3. `infer_sample_filly.py` in the GA `.sif` consumes those CSVs → RPEL.
>
> GA also rejects non-Spectralis scans and expects 49 or 97 B-scans. fluid/onl/pr
> do not depend on any of this.

```sh
singularity build --fakeroot /scratch/$USER/ri/pr.sif \
  <repo>/retinal-inference/runners/pr/apptainer.def
```
If `--fakeroot` isn't enabled for your user (common on shared clusters), build
the `.sif` on a box where you have sudo/root and copy it over, or use a remote
builder — the `.def` itself is unchanged.

## 2. The server (bare Python, NOT in a container — it launches the .sif's)
CentOS 7 caveats: the module system tops out at Python 3.9 (we need ≥3.11), so use
a Miniconda env, and a pre-2024 installer (the latest needs glibc ≥2.28; cn7 has
2.17):
```sh
cd /scratch/$USER
wget -q https://repo.anaconda.com/miniconda/Miniconda3-py311_23.11.0-2-Linux-x86_64.sh
bash Miniconda3-py311_23.11.0-2-Linux-x86_64.sh -b -p /scratch/$USER/miniconda3
/scratch/$USER/miniconda3/bin/conda create -y -p /scratch/$USER/ri-env python=3.11
source /scratch/$USER/miniconda3/bin/activate /scratch/$USER/ri-env
```
Install the **run-only** server (no `ingest` extra — the cluster never converts
E2E; that's app-side). Pin `numpy<2.1` so pip uses a glibc-2.17 wheel instead of
building numpy ≥2.1 from source (cn7's GCC 4.8.5 is too old):
```sh
cd <repo>/retinal-inference
pip install -e . "numpy<2.1"
mkdir -p /scratch/$USER/retinal-inference/tmp
```
(The app-VM preprocess sidecar — §5b — installs `pip install -e ".[ingest]"` to
pull `oct-converter`; on a modern-glibc app VM no numpy pin is needed.)

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
RETINAL_INFERENCE_FLUID_SIF=/home/optima/octreader/Processor_Implementations/sese_retinsight_fluid/code/v2.5.0/fluid_segmentation.sif \
RETINAL_INFERENCE_ONL_SIF=/home/optima/octreader/Processor_Implementations/sese_onl/singularity/sese_onl.sif \
RETINAL_INFERENCE_ONL_CODE=/home/optima/octreader/Processor_Implementations/sese_onl/code/outernuclearlayer-segmentation \
RETINAL_INFERENCE_ONL_WEIGHTS=/home/optima/octreader/Processor_Implementations/sese_onl/weights/cross_val_ga \
RETINAL_INFERENCE_PR_SIF=/scratch/$USER/ri/pr.sif \
RETINAL_INFERENCE_PR_CODE=/home/optima/octreader/Processor_Implementations/sese_pr/code/photoreceptors-segmentation \
RETINAL_INFERENCE_PR_WEIGHTS=/home/optima/octreader/Processor_Implementations/sese_pr/weights/u2net-cross-entropy \
  nohup uvicorn retinal_inference.main:app --host 0.0.0.0 --port 8000 \
  > /scratch/$USER/retinal-inference/server.log 2>&1 &
```
- `GPU_DEVICE=0` — an idle card (GPU 3 was busy). For the pr task the CUDA-10 `.sif`
  runs on any of the 6, so no Turing pin needed.
- `ga` is omitted above (validate-on-first-run, not blocked). All paths are known;
  to enable it add:
  ```
  RETINAL_INFERENCE_GA_SIF=/home/optima/octreader/Processor_Implementations/sese_ga/pytorch_optima_dl.v4.sif
  RETINAL_INFERENCE_GA_CODE=/home/optima/octreader/Processor_Implementations/sese_ga/code
  RETINAL_INFERENCE_GA_WEIGHTS=/home/optima/octreader/Processor_Implementations/sese_ga/weights/filly_checkpoints
  RETINAL_INFERENCE_GA_IOWA_BINARY=/home/optima/octreader/OCTLayerSeg3.6
  RETINAL_INFERENCE_GA_IOWA_CONVERTER=/home/optima/octreader/optima-framework/deployment/prod/local_IOWA_LayerSegV3_to_CSV
  ```
- These `$PI` paths must be visible from the GPU compute node (they are — same NFS
  as `/scratch`); the adapter bind-mounts the code/weights dirs into the `.sif`.
- PR extra dep: sese_pr imports `scikit-learn`, baked into `pr.sif` via the
  `.def`. To avoid rebaking the `.sif` you can instead bind a `pip --target` dir:
  ```sh
  mkdir -p /scratch/$USER/ri/pyextra
  singularity exec --bind /scratch/$USER/ri/pyextra:/scratch/$USER/ri/pyextra \
    /scratch/$USER/ri/pr.sif pip install --no-cache-dir --no-deps \
    --target=/scratch/$USER/ri/pyextra scikit-learn==0.24.2 joblib threadpoolctl
  ```
  then set `RETINAL_INFERENCE_PR_PYEXTRA=/scratch/$USER/ri/pyextra` (use
  `--no-deps` so it holds only sklearn, not a numpy that would shadow the `.sif`'s).
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

### 5b. App-VM preprocess sidecar (DICOM-only cluster)
The cluster `ApptainerAdapter` rejects `.e2e` — the `.e2e → bscan.dcm` conversion
(PHI redaction included) happens **app-side** so the PHI-bearing E2E never leaves
the app VM. Run a tiny preprocess-only sidecar next to Tomcat (no GPU, no models).
Install **with** the `ingest` extra (this side does need `oct-converter`):
```sh
pip install -e ".[ingest]"
```
```sh
RETINAL_INFERENCE_PREPROCESS_ENDPOINT_ENABLED=true \
RETINAL_INFERENCE_AUTH_TOKEN=<app-vm secret> \
RETINAL_INFERENCE_SHARED_TMPDIR=/var/lib/retinal-inference/tmp \
  uvicorn retinal_inference.main:app --host 127.0.0.1 --port 8001
```
Then point the Java client at it:
```
core.retinalInference.preprocessUrl=http://127.0.0.1:8001
core.retinalInference.preprocessToken=<app-vm secret>   # falls back to remotePushToken if unset
```
With `preprocessUrl` set, `RemoteRetinalInferenceClient` POSTs the `.e2e` to
`/preprocess`, gets the redacted `bscan.dcm` back, and forwards only that to the
cluster `/run`. Leave `preprocessUrl` **blank** for single-host dev — there the
`OptimaAdapter` ingests the `.e2e` itself. (The cluster `/run` accepts either a
DICOM or an `.e2e`, auto-detected, so a misconfig degrades rather than corrupts.)

## 6. Per-model validation (first real run)
For each task: POST a real `.e2e`, confirm the model **accepts the synthesized
`bscan.dcm`** and the expected output artifacts are produced (`fluidseg.npz`
labels; ONL OPL-HFL + BMEIS surface CSVs; PR BMEIS + OB-OPR surface CSVs; GA
RPEL CSV). The server returns these artifacts only — the Java backend computes
the clinical metric — so there is no server-side metric to eyeball. See each
`runners/<task>/README.md` "confirm on first run" list.

## Open decisions
- **Payload (RESOLVED → DICOM, implemented):** the backend converts
  `.e2e → bscan.dcm` (PHI-redacted) **app-side** via the `/preprocess` sidecar
  (§5b), the Java client forwards only the DICOM when `preprocessUrl` is set, and
  the cluster `/run` + `ApptainerAdapter` are DICOM-first (auto-detect; the adapter
  rejects raw `.e2e`). `prepare_bscan_dcm` now runs on the app-VM preprocess sidecar.
- **ONL/PR clinical metric**: the server returns the raw surface CSVs only; the
  Java backend computes the ONL thickness / PR depth (µm). Confirm the exact
  clinical definition Java-side.
- **Dispatcher host / persistence policy**: confirm a long-lived service is
  allowed on a node, else submit from the app VM over SSH.
