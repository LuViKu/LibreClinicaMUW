"""POST /screen — synchronous fast-screen path.

The Java side owns the ``retinal_inference_job`` row state machine; the
sidecar only computes and reports back the deterministic placeholder result
(real model in a follow-up slice).
"""

from __future__ import annotations

from pathlib import Path

from fastapi import APIRouter, HTTPException

from retinal_inference.inference.adapter import (
    UnsupportedTaskError,
    get_adapter,
)
from retinal_inference.models.requests import ScreenRequest
from retinal_inference.models.responses import ScreenResponse

router = APIRouter()


@router.post("/screen", response_model=ScreenResponse, status_code=200)
def screen(req: ScreenRequest) -> ScreenResponse:
    adapter = get_adapter()
    if not adapter.supports(req.task):
        raise HTTPException(
            status_code=400,
            detail=f"Task '{req.task}' is not supported by the current adapter",
        )
    try:
        result = adapter.fast_screen(req.task, Path(req.e2e_path), req.laterality)
    except UnsupportedTaskError as e:
        raise HTTPException(status_code=400, detail=str(e)) from e
    except FileNotFoundError as e:
        raise HTTPException(
            status_code=400,
            detail=f"E2E file not found: {req.e2e_path}",
        ) from e
    return ScreenResponse(
        job_id=req.job_id,
        task=req.task,
        approx_area_mm2=result.approx_area_mm2,
        foveal_bscan_index=result.foveal_bscan_index,
        confidence=result.confidence,
        model_version=result.model_version,
    )
