"""GA model-runner (sese_ga, RPEL → geographic-atrophy area) — GATED.

This runner is the most prerequisite-heavy and is GPU-gated; it is only reached
when RETINAL_INFERENCE_RUNNER_GA_URL is set (left empty by default). On /infer
it runs two steps and derives GA area (mm²) from the RPEL channel:

  1. IOWA layer segmentation (the proprietary Univ-of-Iowa OCTLayerSeg binary,
     NOT in git — must be sourced from the OPTIMA HPC) on the shared bscan.dcm,
     producing the layer segmentation the GA model needs as input.
  2. The sese_ga model (code/infer_sample_filly.py) inside the vendor
     Singularity image pytorch_optima_dl.v4.sif (or a reimplemented torch path),
     with the 5 checkpoints + the layer seg, producing per-channel CSVs:
       001-RPEL.csv (RPE loss — the one we use), 002-EZL.csv, 003-ELM.csv.

GA area = (# of RPE-loss A-scan positions in 001-RPEL.csv) × lateral × slice mm.
Backs task='ga' (mm²).

PREREQUISITES (see README) — none verifiable here:
  * OCTLayerSeg3.6 native binary  (IOWA_BINARY)
  * pytorch_optima_dl.v4.sif (~10 GB) + Singularity/Apptainer, OR a reimplemented
    model path (extract common/ + prepare_data/ from the .sif)
  * a GPU (the .sif is invoked with --nv)
Confirm on first real run: the LayerSegPath format the model expects, the
RPEL CSV semantics (binary loss map vs boundary), and the area definition.
"""

from __future__ import annotations

import csv
import glob
import os
import subprocess
from pathlib import Path

import numpy as np
import pydicom
import uvicorn
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

MODEL_VERSION = os.environ.get("RUNNER_GA_MODEL_VERSION", "sese-ga-1.0")
IOWA_BINARY = os.environ.get("RUNNER_GA_IOWA_BINARY", "/opt/iowa/OCTLayerSeg3.6")
GA_SIF = os.environ.get("RUNNER_GA_SIF", "/opt/sese_ga/pytorch_optima_dl.v4.sif")
GA_CODE = os.environ.get("RUNNER_GA_CODE", "/opt/sese_ga/code/infer_sample_filly.py")
GA_WEIGHTS_DIR = os.environ.get("RUNNER_GA_WEIGHTS", "/weights/filly_checkpoints")
GA_THRESHOLD = os.environ.get("RUNNER_GA_THRESHOLD", "0.5")
USE_SINGULARITY = os.environ.get("RUNNER_GA_USE_SINGULARITY", "1") == "1"

app = FastAPI(title="retinal-runner-ga")


class InferRequest(BaseModel):
    task: str
    bscan_dcm_path: str
    laterality: str
    output_dir: str


@app.get("/health")
def health() -> dict:
    return {"status": "ok", "task": "ga", "model_version": MODEL_VERSION}


def _spacing_mm(dcm_path: str) -> tuple[float, float]:
    ds = pydicom.dcmread(dcm_path, stop_before_pixels=True)
    lateral = float(ds.PixelSpacing[1])
    slice_mm = float(getattr(ds, "SpacingBetweenSlices", lateral))
    return lateral, slice_mm


def _run_iowa_layer_seg(dcm: Path, work: Path) -> Path:
    """Step 1 — proprietary IOWA OCTLayerSeg on bscan.dcm → layer segmentation.

    Mirrors the vendor invocation (OCTLayerSeg<ver> -oM bscan.dcm lres.xml ...).
    The exact LayerSegPath format the GA model expects is a confirm-on-build-host
    item; here we hand it the produced layer-seg directory.
    """
    work.mkdir(parents=True, exist_ok=True)
    lres = work / "lres.xml"
    cmd = [
        IOWA_BINARY, "-oM", str(dcm), str(lres),
        str(work / "t1.xml"), str(work / "t2.tif"), str(work / "t3.xml"),
    ]
    subprocess.run(cmd, check=True, capture_output=True, text=True, timeout=1800)
    return work


def _run_ga_model(dcm: Path, layerseg: Path, out: Path) -> None:
    """Step 2 — sese_ga model (inside the .sif) → 001-RPEL.csv etc."""
    weights = [str(Path(GA_WEIGHTS_DIR) / str(i) / "w.ckpt") for i in range(5)]
    model_cmd = [
        "python", GA_CODE,
        "--PathToWeights", *weights,
        "--BscanPath", str(dcm),
        "--LayerSegPath", str(layerseg),
        "--OutputGA", str(out),
        "--threshold", GA_THRESHOLD,
    ]
    cmd = (["singularity", "exec", "--nv", GA_SIF] + model_cmd) if USE_SINGULARITY else model_cmd
    subprocess.run(cmd, check=True, capture_output=True, text=True, timeout=3600)


@app.post("/infer")
def infer(req: InferRequest) -> dict:
    dcm = Path(req.bscan_dcm_path)
    out = Path(req.output_dir)
    out.mkdir(parents=True, exist_ok=True)
    if not dcm.is_file():
        raise HTTPException(status_code=400, detail=f"bscan.dcm not found: {dcm}")

    try:
        layerseg = _run_iowa_layer_seg(dcm, out / "layerseg")
        _run_ga_model(dcm, layerseg, out)
    except subprocess.CalledProcessError as e:
        raise HTTPException(status_code=500, detail=f"sese_ga failed: {e.stderr or e.stdout}") from e

    rpel = sorted(glob.glob(str(out / "*RPEL*.csv")))
    if not rpel:
        raise HTTPException(status_code=500, detail=f"sese_ga produced no RPEL CSV in {out}")

    # RPEL = RPE loss. With --threshold the CSV is binarised; GA area = count of
    # loss positions × lateral × slice. (Confirm CSV semantics on the build host.)
    rows: list[list[float]] = []
    with open(rpel[0], newline="") as f:
        reader = csv.reader(f)
        next(reader, None)  # size header
        for line in reader:
            rows.append([float(x) if x not in ("", "nan", "NaN") else 0.0 for x in line])
    arr = np.asarray(rows, dtype=float)
    lateral, slice_mm = _spacing_mm(str(dcm))
    area_mm2 = float(np.count_nonzero(arr > 0)) * lateral * slice_mm

    return {
        "primary_metric_value": round(area_mm2, 4),
        "primary_metric_unit": "mm²",
        "output_payload": {"total_area_mm2": round(area_mm2, 4), "rpel_csv": rpel[0]},
        "en_face_mask_path": None,
        "bscan_masks_dir": str(out),
        "pixel_scale_mm": lateral,
        "model_version": MODEL_VERSION,
        "confidence": 0.85,
    }


if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=8000)
