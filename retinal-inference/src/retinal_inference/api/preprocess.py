"""POST /preprocess — app-VM-side ``.e2e`` -> ``bscan.dcm`` (PHI-redacted) (DR-022).

The cluster ``ApptainerAdapter`` is DICOM-only, and the raw Heidelberg ``.e2e``
carries patient identifiers that must never leave the app VM. This endpoint runs
the shared E2E->DICOM ingestion (``prepare_bscan_dcm``, which strips PHI) so the
Java backend can convert app-side and forward only the redacted ``bscan.dcm`` to
the remote ``/run``.

Deployed as a small preprocess-only sidecar co-located with Tomcat on the app VM
(``RETINAL_INFERENCE_PREPROCESS_ENDPOINT_ENABLED=true`` + an auth token; no GPU,
no models, no adapter). Stateless: the tempdir is deleted before the response.
"""

from __future__ import annotations

import logging
import tempfile
from pathlib import Path

from fastapi import (
    APIRouter,
    File,
    Form,
    Header,
    HTTPException,
    Response,
    UploadFile,
    status,
)

from retinal_inference import config as _config
from retinal_inference.inference.e2e_parser import prepare_bscan_dcm

LOG = logging.getLogger(__name__)

router = APIRouter()


def _check_endpoint_enabled() -> None:
    if not _config.settings.preprocess_endpoint_enabled:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Sidecar /preprocess is disabled in this deployment",
        )


def _check_auth(token_header: str | None) -> None:
    expected = _config.settings.auth_token
    if not expected:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="Sidecar /preprocess not configured (RETINAL_INFERENCE_AUTH_TOKEN unset)",
        )
    if token_header != expected:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid or missing X-MUW-Inference-Token",
        )


@router.post("/preprocess")
async def preprocess(
    file: UploadFile = File(..., description="Heidelberg .e2e binary"),
    laterality: str | None = Form(default=None, description="OD or OS (informational; derived from the E2E)"),
    x_muw_inference_token: str | None = Header(default=None),
) -> Response:
    _check_endpoint_enabled()
    _check_auth(x_muw_inference_token)

    body = await file.read()
    if not body:
        raise HTTPException(status_code=400, detail="file part is empty")

    shared_tmpdir = _config.settings.shared_tmpdir
    shared_tmpdir.mkdir(parents=True, exist_ok=True)

    with tempfile.TemporaryDirectory(prefix="prep_", dir=str(shared_tmpdir)) as td:
        tempdir = Path(td)
        e2e_path = tempdir / "input.e2e"
        e2e_path.write_bytes(body)
        try:
            out_dir = prepare_bscan_dcm(e2e_path, tempdir)
        except FileNotFoundError as e:
            raise HTTPException(status_code=400, detail=str(e)) from e
        except Exception as e:  # oct-converter / pydicom failure on a bad E2E
            raise HTTPException(
                status_code=422, detail=f"E2E -> DICOM conversion failed: {e}"
            ) from e
        dcm_bytes = (Path(out_dir) / "bscan.dcm").read_bytes()

    LOG.info("POST /preprocess -> bscan.dcm (%d bytes, laterality=%s)", len(dcm_bytes), laterality)
    return Response(
        content=dcm_bytes,
        media_type="application/dicom",
        headers={"Content-Disposition": 'attachment; filename="bscan.dcm"'},
    )
