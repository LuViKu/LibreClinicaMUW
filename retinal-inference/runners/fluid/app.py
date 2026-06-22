"""Fluid model-runner (RetInsight fluidseg 1.3.0) — IRF / SRF / PED.

Thin FastAPI wrapper around the RetInsight ``fluidseg`` CLI (1.3.0 wheels +
weights baked into the image at /workdir/model-weights-1-3-0, exactly like the
vendor code_for_1_3 Docker image). On ``/infer`` it runs ``fluidseg`` on the
shared ``bscan.dcm`` from /workdir (so the default weights resolve — no
``--weights`` needed), reads back the label volume, and derives per-biomarker
volumes in mm³ from the DICOM voxel size.

fluidseg label convention: 1=IRF (cyst), 2=SRF, 3=PED.

NB: v2.5.0 (a Singularity .sif using run_inference.py) is newer than 1.3.0 but
is GPU/x86-only; 1.3.0 is the newest CPU-runnable (pip-wheel) build, so it backs
this runner. Confirm on first real build: that fluidseg finds its baked weights
with no --weights (mirrors the vendor Dockerfile) and the output artifact name
(fluidseg.npy vs fluidseg.npz['segmentation'] — handled defensively below).
"""

from __future__ import annotations

import os
import subprocess
from pathlib import Path

import numpy as np
import pydicom
import uvicorn
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

MODEL_VERSION = os.environ.get("RUNNER_FLUID_MODEL_VERSION", "retinsight-fluid-1.3.0")
# Run fluidseg from here so its default (baked) weights resolve, per the vendor
# code_for_1_3 Dockerfile (ADD model-weights-1-3-0 /workdir; no --weights arg).
WORKDIR = os.environ.get("RUNNER_FLUID_WORKDIR", "/workdir")
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

    cmd = ["fluidseg", "--input", str(dcm), "--output", str(out)]
    if USE_CPU:
        cmd.append("--cpu")
    try:
        subprocess.run(
            cmd, check=True, capture_output=True, text=True, timeout=3600, cwd=WORKDIR
        )
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
    np.save(out / "fluid_labels.npy", seg.astype(np.uint8))

    # 2026-06-22 — per-B-scan biomarker totals (mm²). The cluster
    # runner stays presentation-free per architecture directive:
    # projection PNGs + per-slice overlays are derived app-VM-side
    # via the local /derive endpoint. The cheap numeric aggregate
    # (one np.any + .sum() per biomarker per slice) stays on the
    # runner so the wire payload still carries the data the SPA's
    # per-B-scan trace chart consumes — no PNG cost.
    en_face_mask_path = None  # legacy field — local /derive owns the PNG path now
    bscan_area_mm2 = voxel_mm3 / axial if axial > 0 else voxel_mm3
    per_bscan_mm2 = {
        "irf": [
            round(int(np.any(seg[z] == 1, axis=0).sum()) * bscan_area_mm2, 6)
            for z in range(seg.shape[0])
        ],
        "srf": [
            round(int(np.any(seg[z] == 2, axis=0).sum()) * bscan_area_mm2, 6)
            for z in range(seg.shape[0])
        ],
        "ped": [
            round(int(np.any(seg[z] == 3, axis=0).sum()) * bscan_area_mm2, 6)
            for z in range(seg.shape[0])
        ],
    }

    return {
        "primary_metric_value": total,
        "primary_metric_unit": "mm³",
        "output_payload": {
            "total_fluid_volume_mm3": total,
            "per_bscan_mm2": per_bscan_mm2,
            **payload,
        },
        "en_face_mask_path": en_face_mask_path,
        "bscan_masks_dir": str(out),
        "pixel_scale_mm": axial,
        "model_version": MODEL_VERSION,
        "confidence": 0.9,
    }


if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=8000)
