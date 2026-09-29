"""POST /screen — synchronous fast-screen path.

The Java side owns the ``retinal_inference_job`` row state machine; the
sidecar only computes and reports back the deterministic placeholder result
(real model in a follow-up slice).

``e2e_path`` must name a file below ``RETINAL_INFERENCE_E2E_UPLOADS_PATH`` —
the store the app writes uploads to — and error details never repeat it.

Auth: when ``RETINAL_INFERENCE_AUTH_TOKEN`` is configured the request must
carry it in ``X-MUW-Inference-Token``, as for /run and /preprocess. Unlike
those, /screen stays usable when no token is configured at all: its caller
(the app's ``RetinalInferenceClient``) predates the token, and the single-host
development compose runs this service without one.
"""

from __future__ import annotations

from fastapi import APIRouter, Header, HTTPException, status

from retinal_inference import config as _config
from retinal_inference.inference.adapter import (
    FastScreenUnavailable,
    UnsupportedTaskError,
    get_adapter,
)
from retinal_inference.models.requests import ScreenRequest
from retinal_inference.models.responses import ScreenResponse
from retinal_inference.security import resolve_under, token_matches

router = APIRouter()

_NOT_AN_UPLOAD = "e2e_path is not a file in the E2E uploads store"


def _check_auth(token_header: str | None) -> None:
    expected = _config.settings.auth_token
    if not expected:
        return
    if not token_matches(token_header, expected):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid or missing X-MUW-Inference-Token",
        )


@router.post("/screen", response_model=ScreenResponse, status_code=200)
def screen(
    req: ScreenRequest,
    x_muw_inference_token: str | None = Header(default=None),
) -> ScreenResponse:
    _check_auth(x_muw_inference_token)
    # Same answer whether the path is outside the store or simply missing, so
    # the endpoint cannot be used to probe the filesystem.
    e2e_path = resolve_under(_config.settings.e2e_uploads_path, req.e2e_path)
    if e2e_path is None or not e2e_path.is_file():
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=_NOT_AN_UPLOAD)

    adapter = get_adapter()
    if not adapter.supports(req.task):
        raise HTTPException(
            status_code=400,
            detail=f"Task '{req.task}' is not supported by the current adapter",
        )
    try:
        result = adapter.fast_screen(req.task, e2e_path, req.laterality)
    except FastScreenUnavailable as e:
        # Async-only task: tell the caller to enqueue + poll rather than fail.
        raise HTTPException(status_code=422, detail=str(e)) from e
    except UnsupportedTaskError as e:
        raise HTTPException(status_code=400, detail=str(e)) from e
    except FileNotFoundError as e:
        raise HTTPException(status_code=400, detail=_NOT_AN_UPLOAD) from e
    return ScreenResponse(
        job_id=req.job_id,
        task=req.task,
        approx_area_mm2=result.approx_area_mm2,
        foveal_bscan_index=result.foveal_bscan_index,
        confidence=result.confidence,
        model_version=result.model_version,
    )
