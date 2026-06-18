# retinal-runner-pr

sese_pr (photoreceptor / PR layer, bounded by the BMEIS and OB-OPR surfaces)
behind the runner `/infer` contract (see [../README.md](../README.md)).
CPU-capable. Uses its own BMEIS layer (independent of the ONL runner).

The server returns the raw surface CSVs only — the Java backend computes the
clinical metric (PR depth/thickness in µm). The runner leaves
`primary_metric_value`/`primary_metric_unit` as `null`.

## Provisioning (not in git)
**Code** — copy the vendor inference code into `./code/` before building:
```
cp -r <sese_pr>/code/photoreceptors-segmentation/* \
      retinal-inference/runners/pr/code/
```
**Weights** — mounted at `/weights` at runtime (compose). sese_pr selects the
model by manufacturer: **Heidelberg/Spectralis → `u2net-cross-entropy`**
(our OCT exports), Cirrus/Topcon → `u2net-cirrus_v3`.
```
<sese_pr>/weights/u2net-cross-entropy/   (.ini + model.pkl)   # default (Spectralis)
<sese_pr>/weights/u2net-cirrus_v3/                             # Cirrus/Topcon only
```

## torch / weights caveat (PyTorch 1.0 full-pickle)
`model.pkl` is a full pickled model from torch 1.0, so it only unpickles under a
matching torch. On the build host either:
- `pip install torch==1.0.1` in the Dockerfile (matches production), or
- convert `.pkl` → `state_dict` once in a throwaway torch-1.0 env, load into
  `segmentation/architectures.py:BayesianUnet` on modern torch, and adjust.
Also patch the unconditional `.cuda()` in `segmentation/model_factory.py` so CPU
inference works.

## Build & run (standalone)
```
docker build -t retinal-runner-pr retinal-inference/runners/pr
docker run --rm -p 8003:8000 \
  -v /path/to/pr/weights:/weights:ro \
  -v /var/lib/libreclinica/segmentation-output:/var/lib/libreclinica/segmentation-output \
  retinal-runner-pr
```

## Env knobs
| Var | Default | Notes |
|---|---|---|
| `RUNNER_PR_WEIGHTS` | `/weights/u2net-cross-entropy` | model dir (.ini + .pkl); Spectralis |
| `RUNNER_PR_SAMPLES` | `10` | Bayesian MC samples |
| `RUNNER_PR_CODE` | `/opt/sese_pr` | vendor code location in the image |
| `RUNNER_PR_MODEL_VERSION` | `sese-pr-1.3` | reported to `/health` |

## To confirm on first real run
- torch version / pickle loading (see caveat) + the CPU `.cuda()` patch.
- The `model_path` layout + whether `--ensemble` is wanted.
- The exact BMEIS / OB-OPR CSV filenames (`app.py` globs `*BMEIS*` / `*OB?OPR*`).
- The clinical metric is computed Java-side from the returned surface CSVs.
