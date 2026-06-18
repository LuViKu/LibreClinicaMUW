# retinal-runner-onl

sese_onl (outer nuclear layer thickness) behind the runner `/infer` contract
(see [../README.md](../README.md)). CPU-capable.

## Provisioning (not in git)
**Code** — copy the vendor inference code into `./code/` before building:
```
cp -r <sese_onl>/code/outernuclearlayer-segmentation/* \
      retinal-inference/runners/onl/code/
```
**Weights** — mounted at `/weights` at runtime (compose):
```
<sese_onl>/weights/                 # onl_seg_vanilla_unet.pth (single) or cross_val_ga/ (5-fold)
```

## Build & run (standalone)
```
docker build -t retinal-runner-onl retinal-inference/runners/onl
docker run --rm -p 8002:8000 \
  -v /path/to/onl/weights:/weights:ro \
  -v /var/lib/libreclinica/segmentation-output:/var/lib/libreclinica/segmentation-output \
  retinal-runner-onl
```
Note: in compose the `segmentation-output` volume MUST mount at the same
absolute path the sidecar uses — it passes absolute paths in `/infer`.

## Env knobs
| Var | Default | Notes |
|---|---|---|
| `RUNNER_ONL_WEIGHTS` | `/weights` | path the script's `model_path` arg expects (single `.pth` vs the 5-fold dir) |
| `RUNNER_ONL_CODE` | `/opt/sese_onl` | where the vendor code is copied in the image |
| `RUNNER_ONL_MODEL_VERSION` | `sese-onl-1.2` | reported to `/health` + persisted as model_version |

## To confirm on first real run
- The `model_path` layout (single `.pth` vs 5-fold dir) and the torch version.
- The exact boundary-CSV filenames (`app.py` globs `*OPL-HFL*` / `*BMEIS*`).
- That the script accepts the synthesized `bscan.dcm`.
- Whether the primary metric should be **mean ONL thickness** (current) or a
  central-subfield / per-A-scan map — confirm with the team.
