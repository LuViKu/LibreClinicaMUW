"""Fluid model-runner (RetInsight ``fluidseg``) — IRF / SRF / PED.

Thin FastAPI wrapper around the RetInsight ``fluidseg`` CLI (installed from the
vendor wheels in this image). On ``/infer`` it runs ``fluidseg`` on the shared
``bscan.dcm``, reads back the label volume, and derives per-biomarker volumes
in mm³ from the DICOM voxel size.

fluidseg label convention (RetInsight): 1=IRF (cyst), 2=SRF, 3=PED.

Two things to confirm on the first real build/run (vendor-version dependent,
not verifiable without the wheels + weights):
  * the exact ``--weights`` set + filenames (see RUNNER_FLUID_WEIGHTS);
  * the output artifact name/shape (``fluidseg.npy`` vs ``fluidseg.npz``
    with a ``segmentation`` key) — handled defensively below.
"""

from __future__ import annotations

import os
import shlex
import subprocess
from pathlib import Path

import numpy as np
import pydicom
import uvicorn
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

MODEL_VERSION = os.environ.get("RUNNER_FLUID_MODEL_VERSION", "retinsight-fluid-1.3.0")
# Space-separated weight paths inside the image/mount, in the order fluidseg's
# --weights expects. Default is the 0.6.2 ensemble; override per release.
WEIGHTS = os.environ.get(
    "RUNNER_FLUID_WEIGHTS",
    "/weights/weights_fluidbrunet.pt /weights/weights_fluidbrunet_3class.pt "
    "/weights/weights_2d.pt /weights/weights_25d.pt",
).split()
USE_CPU = os.environ.get("RUNNER_FLUID_CPU", "1") == "1"

LABELS = {"irf_mm3": 1, "srf_mm3": 2, "ped_mm3": 3}

app = FastAPI(title="retinal-runner-fluid")


class InferRequest(BaseModel):
    task: str
    bscan_dcm_path: str
    laterality: str
    output_dir: str


@app.get("/health")
def health() -> dict:
    return {"status": "ok", "task": "fluid", "model_version": MODEL_VERSION}


def _voxel_mm3(dcm_path: Path) -> tuple[float, float]:
    ds = pydicom.dcmread(str(dcm_path), stop_before_pixels=True)
    axial, lateral = (float(ds.PixelSpacing[0]), float(ds.PixelSpacing[1]))
    slice_mm = float(getattr(ds, "SpacingBetweenSlices", lateral))
    return axial * lateral * slice_mm, axial


def _load_segmentation(output_dir: Path) -> np.ndarray:
    npy = output_dir / "fluidseg.npy"
    npz = output_dir / "fluidseg.npz"
    if npy.is_file():
        return np.load(npy)
    if npz.is_file():
        return np.load(npz)["segmentation"]
    raise FileNotFoundError(f"fluidseg produced no fluidseg.npy/.npz in {output_dir}")


@app.post("/infer")
def infer(req: InferRequest) -> dict:
    dcm = Path(req.bscan_dcm_path)
    out = Path(req.output_dir)
    out.mkdir(parents=True, exist_ok=True)
    if not dcm.is_file():
        raise HTTPException(status_code=400, detail=f"bscan.dcm not found: {dcm}")

    cmd = ["fluidseg", "--input", str(dcm), "--output", str(out), "--weights", *WEIGHTS]
    if USE_CPU:
        cmd.append("--cpu")
    try:
        subprocess.run(cmd, check=True, capture_output=True, text=True, timeout=3600)
    except subprocess.CalledProcessError as e:  # surface the model's stderr
        raise HTTPException(
            status_code=500, detail=f"fluidseg failed: {e.stderr or e.stdout}"
        ) from e

    seg = _load_segmentation(out)
    voxel_mm3, axial = _voxel_mm3(dcm)
    payload = {
        name: round(float((seg == label).sum()) * voxel_mm3, 6)
        for name, label in LABELS.items()
    }
    total = round(sum(payload.values()), 6)

    # Persist the label volume as the per-bscan artifact.
    np.save(out / "fluid_labels.npy", seg.astype(np.uint8))

    return {
        "primary_metric_value": total,
        "primary_metric_unit": "mm³",
        "output_payload": {"total_fluid_volume_mm3": total, **payload},
        "en_face_mask_path": None,
        "bscan_masks_dir": str(out),
        "pixel_scale_mm": axial,
        "model_version": MODEL_VERSION,
        "confidence": 0.9,
    }


if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=8000)
