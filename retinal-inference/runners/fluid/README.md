# retinal-runner-fluid

RetInsight `fluidseg` **1.3.0** (IRF / SRF / PED) behind the runner `/infer`
contract (see [../README.md](../README.md)). CPU-capable. Weights are **baked
into the image** (vendor-faithful), so no `--weights` arg and no weights mount.

## Versions
- **1.3.0 (this runner)** — newest CPU-runnable wheel build; production-grade.
- 2.5.0 — newer, but a ~2.9 GB Singularity `.sif` (run_inference.py), GPU/x86
  only → use it on a GPU host, not here. 0.6.2 was test-only.

## Provisioning the build context (not in git)
```
# 1.3.0 wheels:
cp <sese_retinsight_fluid>/code/code_for_1_3/dlfw-packages/*.whl \
   retinal-inference/runners/fluid/wheels/
# 1.3.0 weights (whole dir, incl twostage-brunet/):
cp -r <sese_retinsight_fluid>/code/code_for_1_3/model-weights-1-3-0 \
   retinal-inference/runners/fluid/
```

## Build & run
```
docker build -t retinal-runner-fluid retinal-inference/runners/fluid
docker run --rm -p 8001:8000 \
  -v /var/lib/libreclinica/segmentation-output:/var/lib/libreclinica/segmentation-output \
  retinal-runner-fluid
```

## Env knobs
| Var | Default | Notes |
|---|---|---|
| `RUNNER_FLUID_CPU` | `1` | drop to `0` only on a CUDA host |
| `RUNNER_FLUID_WORKDIR` | `/workdir` | cwd for fluidseg so baked weights resolve |
| `RUNNER_FLUID_MODEL_VERSION` | `retinsight-fluid-1.3.0` | reported to `/health` |

## To confirm on first real run
- That fluidseg 1.3.0 finds its baked weights with **no `--weights`** (matches
  the vendor `code_for_1_3/Dockerfile`); if not, pass them explicitly.
- The output artifact (`fluidseg.npy` vs `fluidseg.npz['segmentation']`) —
  `app.py:_load_segmentation` handles both.
- That `fluidseg` accepts the synthesized `bscan.dcm` (Heidelberg private tags).
