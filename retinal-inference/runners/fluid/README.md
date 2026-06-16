# retinal-runner-fluid

RetInsight `fluidseg` (IRF / SRF / PED) behind the runner `/infer` contract
(see [../README.md](../README.md)). CPU-capable.

## Provisioning (not in git)

**Wheels** — copy the RetInsight wheels into `./wheels/` before building:
```
cp <sese_retinsight_fluid>/code/code_for_1_3/dlfw-packages/*.whl \
   retinal-inference/runners/fluid/wheels/
```
**Weights** — the `.pt` files are mounted at `/weights` at runtime (compose):
```
<sese_retinsight_fluid>/weights/model-weights-0-6-2/   (weights_*.pt)
# or model-weights-1-3-0/ — then set RUNNER_FLUID_WEIGHTS to match.
```

## Build & run (standalone)
```
docker build -t retinal-runner-fluid retinal-inference/runners/fluid
docker run --rm -p 8001:8000 \
  -v /path/to/weights:/weights:ro \
  -v /var/lib/libreclinica/segmentation-output:/data \
  retinal-runner-fluid
```

## Env knobs
| Var | Default | Notes |
|---|---|---|
| `RUNNER_FLUID_WEIGHTS` | the 0.6.2 4-file ensemble under `/weights` | space-separated, in fluidseg's `--weights` order; match the weights you mount |
| `RUNNER_FLUID_CPU` | `1` | drop to `0` only on a CUDA host |
| `RUNNER_FLUID_MODEL_VERSION` | `retinsight-fluid-1.3.0` | reported to `/health` + persisted as the job's model_version |

## To confirm on first real run
- The exact `--weights` filenames/order for the wheel version you install
  (0.6.2 = 4 files; 1.3.0 = `patch_brunet.pt` + `spacing_patch_brunet.pt`).
- The output artifact name/shape (`fluidseg.npy` vs `fluidseg.npz['segmentation']`)
  — `app.py:_load_segmentation` handles both; adjust if the layout differs.
- That `fluidseg` accepts the synthesized `bscan.dcm` (Heidelberg private tags).
