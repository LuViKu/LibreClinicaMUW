"""ONL model-runner (sese_onl) — outer nuclear layer thickness.

Thin FastAPI wrapper around the sese_onl inference script (vendor ``code/``
copied into this image). On ``/infer`` it runs the script on the shared
``bscan.dcm`` directory, reads the two boundary CSVs it writes, and derives a
mean ONL thickness (µm) from the gap between them.

sese_onl entry (process_input_for_optimus.py): positional CLI
``<original_database_path> <output_path> <model_path>`` →
  001-...(OPL-HFL).csv  — upper ONL interface (rows per B-scan, Y per A-scan)
  002-...(BMEIS).csv     — lower ONL interface
ONL thickness per A-scan = (lower − upper) px × axial mm/px.

Confirm on first real build/run (vendor-version dependent, not verifiable here):
  * the model_path layout the script expects (single .pth vs the 5-fold dir);
  * the exact CSV filenames (globbed defensively below);
  * that the script accepts the synthesized bscan.dcm.
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

MODEL_VERSION = os.environ.get("RUNNER_ONL_MODEL_VERSION", "sese-onl-1.2")
ONL_CODE = os.environ.get("RUNNER_ONL_CODE", "/opt/sese_onl")  # dir with the script
WEIGHTS = os.environ.get("RUNNER_ONL_WEIGHTS", "/weights")  # .pth or model dir

app = FastAPI(title="retinal-runner-onl")


class InferRequest(BaseModel):
    task: str
    bscan_dcm_path: str
    laterality: str
    output_dir: str


@app.get("/health")
def health() -> dict:
    return {"status": "ok", "task": "onl", "model_version": MODEL_VERSION}


def _read_surface_csv(path: str) -> np.ndarray:
    """Per-A-scan Y rows (one row per B-scan); first CSV row is a size header."""
    rows: list[list[float]] = []
    with open(path, newline="") as f:
        reader = csv.reader(f)
        next(reader, None)  # size header [width, depth, height]
        for line in reader:
            rows.append([float(x) if x not in ("", "nan", "NaN") else np.nan for x in line])
    return np.asarray(rows, dtype=float)


def _axial_mm(dcm_path: str) -> float:
    ds = pydicom.dcmread(dcm_path, stop_before_pixels=True)
    return float(ds.PixelSpacing[0])


@app.post("/infer")
def infer(req: InferRequest) -> dict:
    dcm = Path(req.bscan_dcm_path)
    out = Path(req.output_dir)
    out.mkdir(parents=True, exist_ok=True)
    if not dcm.is_file():
        raise HTTPException(status_code=400, detail=f"bscan.dcm not found: {dcm}")

    # original_database_path is a single .dcm FILE despite the vendor
    # CLI help calling it "the original database with the dcm files":
    # the vendor's load_dcm_volume(image_filename) calls
    # pydicom.dcmread(image_filename) directly. Passing dcm.parent (a
    # dir) reaches that line as a directory and raises IsADirectoryError.
    cmd = [
        "python",
        str(Path(ONL_CODE) / "process_input_for_optimus.py"),
        str(dcm),  # original_database_path (bscan.dcm file)
        str(out),  # output_path
        WEIGHTS,  # model_path
    ]
    try:
        subprocess.run(
            cmd, check=True, capture_output=True, text=True, timeout=3600, cwd=ONL_CODE
        )
    except subprocess.CalledProcessError as e:
        # `stderr or stdout` hides the real exception when stderr is non-empty
        # but only carries a benign warning — sese_onl prints
        # `[W616] NNPACK Unsupported hardware` under linux/amd64 emulation
        # alongside a real failure that lands in stdout. Surface both.
        stderr_t = (e.stderr or "").strip()
        stdout_t = (e.stdout or "").strip()
        if stderr_t and stdout_t:
            detail = f"sese_onl failed (exit {e.returncode}):\n[stderr]\n{stderr_t}\n[stdout]\n{stdout_t}"
        else:
            detail = f"sese_onl failed (exit {e.returncode}): {stderr_t or stdout_t}"
        raise HTTPException(status_code=500, detail=detail) from e

    upper = sorted(glob.glob(str(out / "*OPL-HFL*.csv")))
    lower = sorted(glob.glob(str(out / "*BMEIS*.csv")))
    if not upper or not lower:
        raise HTTPException(status_code=500, detail=f"sese_onl produced no boundary CSVs in {out}")

    up = _read_surface_csv(upper[0])
    lo = _read_surface_csv(lower[0])
    axial = _axial_mm(str(dcm))
    thickness_um = float(np.nanmean(lo - up)) * axial * 1000.0

    return {
        "primary_metric_value": round(thickness_um, 3),
        "primary_metric_unit": "µm",
        "output_payload": {
            "mean_onl_thickness_um": round(thickness_um, 3),
            "opl_hfl_csv": upper[0],
            "bmeis_csv": lower[0],
        },
        "en_face_mask_path": None,
        "bscan_masks_dir": str(out),
        "pixel_scale_mm": axial,
        "model_version": MODEL_VERSION,
        "confidence": 0.85,
    }


if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=8000)
