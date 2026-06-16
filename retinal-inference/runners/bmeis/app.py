"""BMEIS model-runner (sese_pr) — photoreceptor boundary (BMEIS).

Thin FastAPI wrapper around the sese_pr inference script (vendor ``code/``
copied into this image). sese_pr consumes MetaImage (``bscan.mhd``), so the
runner first converts the shared ``bscan.dcm`` → ``bscan.mhd`` (SimpleITK),
runs the script with ``--export_for_optimus``, and reads back the BMEIS
boundary surface.

sese_pr entry (process_input_for_optimus.py): positional CLI
``<original_database_path> <output_path> <model_path>``
``--export_for_optimus True --samples N`` →
  001-...(BMEIS).csv     — upper photoreceptor interface (the deliverable)
  002-...(OB_OPR).csv    — lower interface
The primary metric here is the mean BMEIS depth (µm) = mean row × axial mm/px.

Confirm on first real build/run:
  * sese_pr is PyTorch 1.0 with full-pickle model.pkl — this image must carry a
    torch the pickle loads (torch 1.0), OR convert the .pkl to a state_dict
    offline and load on modern torch (see README). The forced .cuda() in
    model_factory needs patching for CPU.
  * the model_path layout (.ini + .pkl dir; --ensemble for multi-model);
  * the exact CSV filenames (globbed defensively);
  * the clinical primary metric (mean BMEIS depth is a placeholder).
"""

from __future__ import annotations

import csv
import glob
import os
import subprocess
from pathlib import Path

import numpy as np
import pydicom
import SimpleITK as sitk
import uvicorn
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

MODEL_VERSION = os.environ.get("RUNNER_BMEIS_MODEL_VERSION", "sese-pr-1.3")
PR_CODE = os.environ.get("RUNNER_BMEIS_CODE", "/opt/sese_pr")
# Heidelberg/Spectralis uses the cross-entropy model (sese_pr MODEL_PATH_SPECTRALIS);
# u2net-cirrus_v3 is for Cirrus/Topcon. Our OCT exports are Spectralis.
WEIGHTS = os.environ.get("RUNNER_BMEIS_WEIGHTS", "/weights/u2net-cross-entropy")
SAMPLES = os.environ.get("RUNNER_BMEIS_SAMPLES", "10")

app = FastAPI(title="retinal-runner-bmeis")


class InferRequest(BaseModel):
    task: str
    bscan_dcm_path: str
    laterality: str
    output_dir: str


@app.get("/health")
def health() -> dict:
    return {"status": "ok", "task": "bmeis", "model_version": MODEL_VERSION}


def _read_surface_csv(path: str) -> np.ndarray:
    rows: list[list[float]] = []
    with open(path, newline="") as f:
        reader = csv.reader(f)
        next(reader, None)  # size header
        for line in reader:
            rows.append([float(x) if x not in ("", "nan", "NaN") else np.nan for x in line])
    return np.asarray(rows, dtype=float)


@app.post("/infer")
def infer(req: InferRequest) -> dict:
    dcm = Path(req.bscan_dcm_path)
    out = Path(req.output_dir)
    out.mkdir(parents=True, exist_ok=True)
    if not dcm.is_file():
        raise HTTPException(status_code=400, detail=f"bscan.dcm not found: {dcm}")

    # sese_pr wants MetaImage; convert the shared DICOM → bscan.mhd.
    mhd_dir = out / "mhd_in"
    mhd_dir.mkdir(parents=True, exist_ok=True)
    img = sitk.ReadImage(str(dcm))
    axial = float(img.GetSpacing()[1]) if img.GetDimension() == 3 else float(pydicom.dcmread(str(dcm), stop_before_pixels=True).PixelSpacing[0])
    mhd_path = mhd_dir / "bscan.mhd"
    sitk.WriteImage(img, str(mhd_path))

    # sese_pr branches on mhd_dict['Manufacturer'] when the volume's lateral
    # axis doesn't already match Cirrus (1024×512) or Heidelberg (496×512).
    # SimpleITK's MHD writer doesn't emit a Manufacturer field, so the vendor
    # raises KeyError before reaching the actual segmentation. We use the
    # Spectralis weights (u2net-cross-entropy), so flag the volume as
    # Heidelberg Engineering — the vendor then resizes the lateral 1024→512
    # to match the model's training distribution.
    header = mhd_path.read_text()
    if "Manufacturer" not in header:
        header = header.replace(
            "ElementDataFile",
            "Manufacturer = Heidelberg Engineering\nElementDataFile",
        )
        mhd_path.write_text(header)

    # With --export_for_optimus True, sese_pr's
    # predict_segmentation_on_patient_volume treats original_database_path as
    # the MHD FILE itself (`input_filename = path.join(original_database_path)`
    # → SimpleITK.ReadImage on whatever path is given). Passing the parent dir
    # raises "Unable to determine ImageIO reader". Point at the bscan.mhd file.
    cmd = [
        "python",
        str(Path(PR_CODE) / "process_input_for_optimus.py"),
        str(mhd_path),  # original_database_path (the bscan.mhd file)
        str(out),  # output_path
        WEIGHTS,  # model_path (.ini + .pkl)
        "--export_for_optimus", "True",
        "--export_mhd", "False",
        "--samples", SAMPLES,
    ]
    try:
        subprocess.run(
            cmd, check=True, capture_output=True, text=True, timeout=3600, cwd=PR_CODE
        )
    except subprocess.CalledProcessError as e:
        # `stderr or stdout` hides the real exception when stderr is non-empty
        # but only carries a benign warning. Surface both for diagnostics.
        stderr_t = (e.stderr or "").strip()
        stdout_t = (e.stdout or "").strip()
        if stderr_t and stdout_t:
            detail = f"sese_pr failed (exit {e.returncode}):\n[stderr]\n{stderr_t}\n[stdout]\n{stdout_t}"
        else:
            detail = f"sese_pr failed (exit {e.returncode}): {stderr_t or stdout_t}"
        raise HTTPException(status_code=500, detail=detail) from e

    bmeis = sorted(glob.glob(str(out / "*BMEIS*.csv")))
    if not bmeis:
        raise HTTPException(status_code=500, detail=f"sese_pr produced no BMEIS CSV in {out}")
    surface = _read_surface_csv(bmeis[0])
    depth_um = float(np.nanmean(surface)) * axial * 1000.0

    return {
        "primary_metric_value": round(depth_um, 3),
        "primary_metric_unit": "µm",
        "output_payload": {"mean_bmeis_depth_um": round(depth_um, 3), "bmeis_csv": bmeis[0]},
        "en_face_mask_path": None,
        "bscan_masks_dir": str(out),
        "pixel_scale_mm": axial,
        "model_version": MODEL_VERSION,
        "confidence": 0.85,
    }


if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=8000)
