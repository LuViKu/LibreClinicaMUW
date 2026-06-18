# retinal-runner-ga (GATED)

sese_ga RPEL → geographic-atrophy area (mm²), backing `task='ga'`. The most
prerequisite-heavy runner — **GPU-gated and off by default** (the sidecar's
`RETINAL_INFERENCE_RUNNER_GA_URL` is empty until you set it). Two internal
steps: IOWA layer segmentation → sese_ga model → RPEL → area.

## Prerequisites (none in git)
| Need | Where from | Mounted/placed at |
|---|---|---|
| **OCTLayerSeg3.6** native binary | OPTIMA HPC `/home/optima/octreader/` (licensed Univ-of-Iowa) | `/opt/iowa/OCTLayerSeg3.6` |
| `local_IOWA_LayerSegV3_to_CSV` | already in `sese_ga/` | (only if converting layer-seg to CSV) |
| sese_ga `code/` | `<sese_ga>/code/` → `runners/ga/code/` | `/opt/sese_ga/code` |
| `pytorch_optima_dl.v4.sif` (~10 GB) | `<sese_ga>/` | mounted; `RUNNER_GA_SIF` |
| `weights/filly_checkpoints/{0..4}/w.ckpt` | `<sese_ga>/weights/` | `/weights/filly_checkpoints` |
| CUDA GPU | — | host (`--nv`) |

## Two ways to run the model (decide on the build host)
1. **Run the vendor `.sif` via Singularity/Apptainer** (matches production):
   set `RUNNER_GA_USE_SINGULARITY=1`. Running Singularity *inside* Docker needs
   a privileged/special setup — often easier to run this runner directly on a
   GPU host (apptainer installed) rather than nested in Docker.
2. **Reimplement on modern torch** (`RUNNER_GA_USE_SINGULARITY=0`): first
   extract the missing `common/` (`Residual_bottleneck_bn`, `Trilinear`),
   `prepare_data/` (`resample_oct`, `layer_transform`) and `optima.io.csvLayer`
   from the `.sif` (the on-disk `code/common`, `code/prepare_data` are empty),
   load the 5 `w.ckpt` into `CustomNet` (channel 0 = RPEL), drop Singularity+GPU.

## Env knobs
| Var | Default |
|---|---|
| `RUNNER_GA_IOWA_BINARY` | `/opt/iowa/OCTLayerSeg3.6` |
| `RUNNER_GA_SIF` | `/opt/sese_ga/pytorch_optima_dl.v4.sif` |
| `RUNNER_GA_CODE` | `/opt/sese_ga/code/infer_sample_filly.py` |
| `RUNNER_GA_WEIGHTS` | `/weights/filly_checkpoints` |
| `RUNNER_GA_THRESHOLD` | `0.5` |
| `RUNNER_GA_USE_SINGULARITY` | `1` |

## Enable it
Set `RETINAL_INFERENCE_RUNNER_GA_URL` on the sidecar (compose: start with
`--profile ga`). Until then `OptimaAdapter.supports('ga')` is False and `ga`
uploads are rejected upstream.

## To confirm on first real run
- The **LayerSegPath format** the GA model expects (raw IOWA xml vs vrcbin vs CSV).
- The **RPEL CSV semantics** (binary loss map vs boundary) and the exact GA-area
  definition — `app.py` currently counts non-zero RPEL positions × lateral × slice.
- Whether to keep the `.sif` path or reimplement.
