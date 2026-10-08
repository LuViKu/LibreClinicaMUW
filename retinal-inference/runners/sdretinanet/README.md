# sdretinanet runner

SD-RetinaNet (Fazekas et al., [arXiv 2509.20864](https://arxiv.org/abs/2509.20864)) segments 12 retinal layer boundaries and 7 lesion classes per OCT B-scan. The cluster runs it from the standalone image the OPTIMA group built:

```
/home/optima/bfazekas03/singularity-images/lesions-layerseg-standalone/retinanet-spectralis_main.sif
```

Copy it to `$RI_HOME/ri/retinanet-spectralis_main.sif`; the launcher looks there (override with `RI_SDRETINANET_SIF`). Code, AOT-compiled models and `fold_0.json` are baked in, so nothing else is needed. The image is built for Heidelberg Spectralis volumes.

**GPU.** The models are AOT-compiled for **sm_80**, according to the `e_flags` of the cubins inside `model0.pt2`. They run on Ampere and Ada: an A6000 run succeeded on 2026-10-08. On the Turing 2080 Ti nodes they fail with `CUDA driver error: device kernel image is invalid`. That is the opposite of `pr` and `bm`, which need Turing or older. So the task runs only in SLURM mode, with its own request `RI_SLURM_SDRETINANET_GRES` (default `gpu:nva6000:1`; `gpu:nv3080ti:1` and `gpu:nva6000ada:1` also fit). The global node list and constraint do not apply to it.

## What the image does

Its runscript is `main.py`:

```
main.py <input_path|glob> <output_folder> [--tta_level 2] [--output_formats csv npy]
        [--threshold 0.5] [--output_formatter file.py] [--output_probabilities]
```

It reads DICOM, NIfTI, MHA/MHD, `.npy` or PNG/JPG. It opens `fold_0.json` and `aot_models_spectralis/` relative to the working directory. Both are baked into `/app`, so the image must run with `--pwd /app`, which the handler passes.

The image's `/app/aot_models_spectralis` cannot be read by other users (checked 2026-10-08). Copy `model0.pt2` … `model4.pt2` from the readable host folder `lesions-layerseg-standalone/aot_models_spectralis` to `$RI_HOME/ri/sdretinanet_aot_models/` (`RI_SDRETINANET_MODELS`). The handler binds that copy over the image's folder. The task is offered only when the `.sif`, the models and the formatter are all present. By default it writes layer positions and standard deviations as CSV/npy and the lesion maps as npy.

## What the eCRF stores

The eCRF keeps the model's output in the group's **native** formats, the files the SWITCHER study (`namd_switcher_study`) and iamd-ws read:

| File | Format |
|---|---|
| `layers/NNN.yml` | layerlib: `info` (width, height, idx), then per layer `values` (0-based depth row) and `confid` (0 = invalid). Layers in order: ILM, RNFL-GCL, GCL-IPL, IPL-INL, INL-OPL, OPL-HFL, OB_ELM, BMEIS, IB_RPE, OB_RPE, BM, HL-S |
| `lesions/NNN.png` | lesionlib: 8-bit indexed PNG. The pixel index is the main-lesion id (Cyst, SRF, PED, SHRM, Pseudodrusen, ORT; mutually exclusive) in the low 6 bits, and HRF in bit 7. A zTXt chunk `Lesions` holds the bit split and names |

`retinal_inference.inference.sdretinanet_native` writes both formats (`write_volume`) and packs them into the `sdretinanet.zip` artifact (`pack_archive`). The round trip through the real `layerlib`/`lesionlib` readers was verified on 2026-10-07.

After the run, the handler (`ApptainerAdapter._sdretinanet`) requires `layers/` and `lesions/` with one file per DICOM frame, and fails the job otherwise.

## The formatter

`main.py` writes CSV/npy by default. `format_output.py` here is the file for its `--output_formatter` hook. `main.py` calls `format_output(**kwargs)` once per volume with keyword arguments:

| Argument | Shape | Content |
|---|---|---|
| `layer_positions` | (B, 12, W) int | depth row per A-scan |
| `std_deviations` | (B, 12, W) float | rounded to 0.01 |
| `final_lesions` | (B, 7, H, W) float | per-class probability |
| `thresholded_lesions` | (B, 7, H, W) uint8 | `final_lesions > --threshold` |
| `final_layers`, `original_path`, `output_folder` | | |

The handler bind-mounts the formatter at `/opt/ri/sdretinanet_formatter.py`, with `sdretinanet_native.py` beside it.

The formatter makes two conversions:

- **Validity.** A boundary A-scan counts as valid where its standard deviation is below 10, the rule `main.py`'s own `save_default_outputs` uses.
- **Lesions.** The model's channels are alphabetical: Cyst, HRF, ORT, PED, Pseudodrusen, SHRM, SRF. The files keep the SWITCHER order: six main classes, mutually exclusive, then the HRF overlay. Thresholded classes can overlap, so an overlapped pixel goes to the main class with the highest probability. HRF is kept wherever it is above the threshold.

`main.py`'s own default output saves the lesions as `lesions.npz` and does not resolve overlaps. So the SWITCHER PNGs came from a different step, and **whether this overlap rule matches it is the first thing to check** in the validation run below.

The launcher enables the task only when both the `.sif` and this file are present.

## Validation before a study enables the task

Run one volume that the SWITCHER study also segmented through the cluster, then compare:

- the `layers/*.yml` values and confidence;
- the `lesions/*.png` indices, which is where a different overlap rule would show;
- the eCRF's ETDRS numbers against `etdrs_long.csv` for that FileSetId.

### Result of the first comparison (2026-10-08)

One EyleaHD volume (49 B-scans) was run through this image on an A6000 and compared with its SWITCHER segmentation (`VSC_EyleaHD_SDRetinaNet`), quantified with `namd_switcher` in both cases.

- **Boundaries:**
  - Valid/invalid agrees on 99.98% of A-scans.
  - Positions are 0.35 px apart on average and identical on 68% of A-scans.
  - CRT is −0.5% (−2.5 µm), total retina within 1 µm in the 3 and 6 mm discs, the choroid about 2–3% thinner.
- **Lesions:**
  - Classes almost never swap (27 px), so the overlap rule is not the cause.
  - Every disagreeing pixel lies on a mask outline, and SWITCHER's masks are slightly wider.
  - At threshold 0.5, 6 mm volumes are PED +2%, IRF −13%, SRF −24%, SHRM −38%, SDD −35%.
- **Neither setting explains it.**
  - TTA levels 0–3 change almost nothing (3 equals 2).
  - Thresholds 0.2–0.4 move each class toward SWITCHER at a different value, and none reaches a Dice near 1.
  - The boundaries, which the threshold does not affect, differ in every run.

**Conclusion:** the SWITCHER segmentations were made with a different build or pipeline of SD-RetinaNet than this image's AOT models. The defaults stay at the model author's values (`--tta_level 2 --threshold 0.5`), untuned. Thickness metrics agree within about 0.5%, lesion volumes do not, so **do not use this task for lesion endpoints that must match SWITCHER** until the pipeline that produced `VSC_*_SDRetinaNet` is known.
