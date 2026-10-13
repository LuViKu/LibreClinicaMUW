"""POST /e2e/describe and GET /e2e/series-image — series view of a stored ``.E2E`` (Contract 1).

See ``docs/development/e2e-multimodal-split.md``. The app stores a whole multimodal Spectralis
export once as ``<uuid>.e2e`` under ``RETINAL_INFERENCE_E2E_UPLOADS_PATH`` and asks this
service which series it holds (``/e2e/describe``) and for a preview of one of them
(``/e2e/series-image``). Nothing is uploaded again and nothing is written.

* Same gate as ``/preprocess``: ``RETINAL_INFERENCE_PREPROCESS_ENDPOINT_ENABLED`` (404 when off),
  the ``X-MUW-Inference-Token`` header, and the converter package (503 when
  ``muw-e2e-converter`` is not installed, DR-024).
* ``storedName`` is a bare file name (``^[A-Za-z0-9-]+[.]e2e$``, extension case-insensitive) that
  resolves only below the upload directory. Anything else is a 400.
* The response never carries the header patient ID, names or birth date: the converter reduces
  the ID to ``empty | matches | differs`` against ``expectedLabel``.
"""

from __future__ import annotations

import logging
import re
from pathlib import Path

from fastapi import APIRouter, Header, HTTPException, Query, Response, status
from pydantic import BaseModel, Field

from retinal_inference import config as _config
from retinal_inference.api.preprocess import _check_auth
from retinal_inference.security import resolve_under

# DR-024 — absent in the cluster posture; the endpoints then answer 503.
try:
    from muw_e2e_converter import describe_e2e, render_series_png

    _CONVERTER_AVAILABLE = True
except ModuleNotFoundError:  # cluster posture — converter not installed
    _CONVERTER_AVAILABLE = False
    describe_e2e = render_series_png = None  # type: ignore[assignment]

LOG = logging.getLogger(__name__)

router = APIRouter()

_STORED_NAME_RE = re.compile(r"[A-Za-z0-9-]+\.e2e", re.IGNORECASE)
_SERIES_KEY_RE = re.compile(r"s[0-9]{1,6}")


class DescribeRequest(BaseModel):
    storedName: str = Field(..., max_length=255)  # noqa: N815 — wire name
    expectedLabel: str = Field(default="", max_length=128)  # noqa: N815 — wire name


def _check_enabled() -> None:
    if not _config.settings.preprocess_endpoint_enabled:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Sidecar /e2e endpoints are disabled in this deployment",
        )
    if not _CONVERTER_AVAILABLE:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail=(
                "muw-e2e-converter is not installed in this deployment posture; "
                "this endpoint is inactive (see DR-024)."
            ),
        )


def _stored_file(stored_name: str) -> Path:
    """The stored file below the upload directory; 400 for a bad name, 404 when absent."""
    if not _STORED_NAME_RE.fullmatch(stored_name or ""):
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="storedName must be a bare file name like <uuid>.e2e",
        )
    resolved = resolve_under(_config.settings.e2e_uploads_path, stored_name)
    if resolved is None:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="storedName resolves outside the E2E upload directory",
        )
    if not resolved.is_file():
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="E2E file not found")
    return resolved


def _unreadable(e: Exception) -> HTTPException:
    # Never echo parser messages: they can quote file content.
    LOG.warning("E2E series read failed: %s", type(e).__name__)
    return HTTPException(
        status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
        detail=f"The E2E file cannot be read ({type(e).__name__}).",
    )


@router.post("/e2e/describe")
def e2e_describe(
    body: DescribeRequest,
    x_muw_inference_token: str | None = Header(default=None),
) -> dict:
    _check_enabled()
    _check_auth(x_muw_inference_token)
    path = _stored_file(body.storedName)
    try:
        return describe_e2e(path, body.expectedLabel)
    except FileNotFoundError as e:
        raise HTTPException(status_code=404, detail="E2E file not found") from e
    except Exception as e:  # noqa: BLE001
        raise _unreadable(e) from e


@router.get("/e2e/series-image")
def e2e_series_image(
    storedName: str = Query(...),  # noqa: N803 — wire name
    seriesKey: str = Query(...),  # noqa: N803 — wire name
    frame: int = Query(default=0),
    x_muw_inference_token: str | None = Header(default=None),
) -> Response:
    _check_enabled()
    _check_auth(x_muw_inference_token)
    path = _stored_file(storedName)
    if not _SERIES_KEY_RE.fullmatch(seriesKey):
        raise HTTPException(status_code=404, detail="Unknown series")
    try:
        png = render_series_png(path, seriesKey, frame)
    except FileNotFoundError as e:
        raise HTTPException(status_code=404, detail="E2E file not found") from e
    except KeyError as e:
        raise HTTPException(status_code=404, detail="Unknown series") from e
    except IndexError as e:
        raise HTTPException(status_code=404, detail="Frame out of range") from e
    except Exception as e:  # noqa: BLE001
        raise _unreadable(e) from e
    return Response(content=png, media_type="image/png", headers={"Cache-Control": "no-store"})
