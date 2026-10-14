"""POST /e2e/describe, POST /e2e/convert and GET /e2e/series-image — series view of a stored ``.E2E``.

See ``docs/development/e2e-multimodal-split.md``. The app stores a whole multimodal Spectralis
export once as ``<uuid>.e2e`` under ``RETINAL_INFERENCE_E2E_UPLOADS_PATH`` and asks this
service which series it holds (``/e2e/describe``) and for a preview of one of them
(``/e2e/series-image``). Nothing is uploaded again and nothing is written.

* Same gate as ``/preprocess``: ``RETINAL_INFERENCE_PREPROCESS_ENDPOINT_ENABLED`` (404 when off),
  the ``X-MUW-Inference-Token`` header, and the converter package (503 when
  ``muw-e2e-converter`` is not installed, DR-024).
* ``storedName`` is a bare file name (``^[A-Za-z0-9-]+[.]e2e$``, extension case-insensitive) that
  resolves only below the upload directory. Anything else is a 400.
* ``POST /e2e/convert`` (amendment 2026-10-13 (b)) converts the kept series of the file in one pass
  into ``<seriesKey>.dcm`` (plus ``<seriesKey>.geometry.json`` for an OCT volume) below
  ``RETINAL_INFERENCE_E2E_CONVERT_PATH/<storedName stem>/``, a directory created fresh and removed
  again when the conversion fails. The app mounts the same directory read-write and removes it after
  importing the files. Request ``{storedName, label, seriesKeys}``; an unknown or malformed key is a
  400. ``label`` is the only identity written into the DICOMs (PatientID and PatientName).
* ``expectedLabel`` is optional. The response carries ``headerLabel`` (the surname slot of the
  patient chunk, only when it is a plain label token, else null) and ``headerPatient``
  (``none | empty | matches | differs``, the surname compared with ``expectedLabel``). It never
  carries the first name, the patient ID or the birth date, and ``headerLabel`` is never logged.
"""

from __future__ import annotations

import logging
import re
import shutil
from pathlib import Path

from fastapi import APIRouter, Header, HTTPException, Query, Response, status
from pydantic import BaseModel, Field

from retinal_inference import config as _config
from retinal_inference.api.preprocess import _check_auth
from retinal_inference.security import resolve_under

# DR-024 — absent in the cluster posture; the endpoints then answer 503.
try:
    from muw_e2e_converter import convert_series, describe_e2e, render_series_png

    _CONVERTER_AVAILABLE = True
except ModuleNotFoundError:  # cluster posture — converter not installed
    _CONVERTER_AVAILABLE = False
    describe_e2e = render_series_png = convert_series = None  # type: ignore[assignment]

LOG = logging.getLogger(__name__)

router = APIRouter()

_STORED_NAME_CHARS = frozenset("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-")


def _is_stored_name(name: str | None) -> bool:
    """A bare ``<letters, digits, hyphens>.e2e`` name; checked without a regex and length-capped."""
    if not name or len(name) > 128 or not name.lower().endswith(".e2e"):
        return False
    stem = name[:-4]
    return bool(stem) and all(c in _STORED_NAME_CHARS for c in stem)
_SERIES_KEY_RE = re.compile(r"s[0-9]{1,6}")


class DescribeRequest(BaseModel):
    storedName: str = Field(..., max_length=255)  # noqa: N815 — wire name
    expectedLabel: str | None = Field(default=None, max_length=128)  # noqa: N815 — wire name


class ConvertRequest(BaseModel):
    storedName: str = Field(..., max_length=255)  # noqa: N815 — wire name
    label: str | None = Field(default=None, max_length=128)
    seriesKeys: list[str] = Field(..., min_length=1, max_length=2000)  # noqa: N815 — wire name


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
    if not _is_stored_name(stored_name):
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


def _convert_dir(stored_name: str) -> Path:
    """``<convert root>/<stem>`` for a validated stored name; 400 if it would leave the root."""
    target = resolve_under(_config.settings.e2e_convert_path, Path(stored_name).stem)
    if target is None:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="storedName resolves outside the E2E convert directory",
        )
    return target


@router.post("/e2e/convert")
def e2e_convert(
    body: ConvertRequest,
    x_muw_inference_token: str | None = Header(default=None),
) -> dict:
    _check_enabled()
    _check_auth(x_muw_inference_token)
    path = _stored_file(body.storedName)
    keys = body.seriesKeys
    if any(not _SERIES_KEY_RE.fullmatch(k) for k in keys):
        raise HTTPException(status_code=400, detail="seriesKeys must be series keys like s3")
    out_dir = _convert_dir(body.storedName)
    # A fresh directory: whatever an earlier attempt left behind is not trusted.
    shutil.rmtree(out_dir, ignore_errors=True)
    try:
        out_dir.parent.mkdir(parents=True, exist_ok=True)
        results = convert_series(path, body.label, keys, out_dir)
    except KeyError as e:
        shutil.rmtree(out_dir, ignore_errors=True)
        raise HTTPException(status_code=400, detail="Unknown seriesKey") from e
    except ValueError as e:
        shutil.rmtree(out_dir, ignore_errors=True)
        raise HTTPException(status_code=400, detail="Invalid seriesKeys") from e
    except FileNotFoundError as e:
        shutil.rmtree(out_dir, ignore_errors=True)
        raise HTTPException(status_code=404, detail="E2E file not found") from e
    except Exception as e:  # noqa: BLE001 — a failed conversion leaves nothing behind
        shutil.rmtree(out_dir, ignore_errors=True)
        raise _unreadable(e) from e
    files = [
        {
            "seriesKey": r["seriesKey"],
            "file": r["file"],
            "sha256": r["sha256"],
            "bytes": r["bytes"],
            "frames": r["frames"],
            "sopClassUid": r["sopClassUid"],
            **({"geometryFile": r["geometryFile"]} if "geometryFile" in r else {}),
        }
        for r in results
    ]
    return {"files": files}


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
