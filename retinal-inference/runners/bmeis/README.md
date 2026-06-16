# retinal-runner-bmeis

sese_pr (photoreceptor / BMEIS boundary) behind the runner `/infer` contract
(see [../README.md](../README.md)). CPU-capable. Uses its own BMEIS layer
(independent of the ONL runner).

## Provisioning (not in git)
**Code** — copy the vendor inference code into `./code/` before building:
```
cp -r <sese_pr>/code/photoreceptors-segmentation/* \
      retinal-inference/runners/bmeis/code/
```
**Weights** — mounted at `/weights` at runtime (compose):
```
<sese_pr>/weights/u2net-cirrus_v3/    (.ini + model.pkl)   # default
<sese_pr>/weights/u2net-cross-entropy/                      # alternative
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
docker build -t retinal-runner-bmeis retinal-inference/runners/bmeis
docker run --rm -p 8003:8000 \
  -v /path/to/pr/weights:/weights:ro \
  -v /var/lib/libreclinica/segmentation-output:/var/lib/libreclinica/segmentation-output \
  retinal-runner-bmeis
```

## Env knobs
| Var | Default | Notes |
|---|---|---|
| `RUNNER_BMEIS_WEIGHTS` | `/weights/u2net-cirrus_v3` | model dir (.ini + .pkl) |
| `RUNNER_BMEIS_SAMPLES` | `10` | Bayesian MC samples |
| `RUNNER_BMEIS_CODE` | `/opt/sese_pr` | vendor code location in the image |
| `RUNNER_BMEIS_MODEL_VERSION` | `sese-pr-1.3` | reported to `/health` |

## To confirm on first real run
- torch version / pickle loading (see caveat) + the CPU `.cuda()` patch.
- The `model_path` layout + whether `--ensemble` is wanted.
- The exact BMEIS CSV filename (`app.py` globs `*BMEIS*`).
- The clinical primary metric — **mean BMEIS depth (µm)** is a placeholder;
  the team may want PR-layer thickness or a disruption/integrity measure.
