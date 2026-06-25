"""POST /run — stateless full-volume inference (DR-022).

Accepts a scan inline (multipart), runs the configured adapter against it in a
fresh tempdir, base64-encodes the runner-produced artifacts into a JSON
envelope, deletes the tempdir, and returns. Nothing about the scan volume
persists past the response.

The upload may be either a synthesized ``bscan.dcm`` (DICOM — the cluster
``ApptainerAdapter`` is DICOM-only; the Java backend converts ``.e2e -> .dcm``
app-side, DR-022) or a Heidelberg ``.e2e`` (the single-host dev ``OptimaAdapter``
ingests the E2E itself). The input kind is auto-detected from the filename
suffix and the DICOM Part-10 magic, and materialised under the right name so the
adapter picks it up.

Used when the Java side has ``core.retinalInference.remotePushUrl`` set — the
sidecar runs on a remote GPU host and the institutional Tomcat reaches it
over HTTP. The DB-poll worker + ``/screen`` path remain untouched for the
single-host dev compose flow.
"""

from __future__ import annotations

import asyncio
import logging
import tempfile
import urllib.error
from collections import OrderedDict
from pathlib import Path
from typing import Literal

from fastapi import APIRouter, File, Form, Header, HTTPException, UploadFile, status

from retinal_inference import config as _config
from retinal_inference.inference.adapter import (
    FastScreenUnavailable,
    UnsupportedTaskError,
    get_adapter,
)
from retinal_inference.inference.artifact_collector import (
    collect_artifacts,
    rewrite_payload_paths,
)
from retinal_inference.models.run import RunEnvelope
from retinal_inference.tasks import SUPPORTED_TASKS, TaskName

LOG = logging.getLogger(__name__)

router = APIRouter()

# Single-request-at-a-time per sidecar process — keeps the shared tempdir's
# disk pressure bounded (see plan § Open items + risks). uvicorn workers are
# the unit of parallelism if the operator wants concurrency.
_run_lock = asyncio.Lock()

# Idempotency LRU. Maps caller-supplied Idempotency-Key → envelope so a
# retry (network blip on the Java side) returns the cached result without
# re-running inference. Cap of 64 keys is plenty for a sidecar that processes
# one job at a time.
_idempotency_cache: "OrderedDict[str, RunEnvelope]" = OrderedDict()
_IDEMPOTENCY_CACHE_MAX = 64

_LATERALITIES: frozenset[str] = frozenset({"OD", "OS"})


def _is_dicom(filename: str | None, body: bytes) -> bool:
    """True if the upload is a DICOM (by suffix or Part-10 ``DICM`` magic)."""
    if filename and filename.lower().endswith((".dcm", ".dicom")):
        return True
    # DICOM Part-10: 128-byte preamble followed by the "DICM" magic.
    return len(body) >= 132 and body[128:132] == b"DICM"


def _materialize_input(tempdir: Path, filename: str | None, body: bytes) -> Path:
    """Write the upload under a name the adapter recognises and return its path.

    DICOM -> ``bscan.dcm`` (ApptainerAdapter / cluster, DICOM-only);
    otherwise ``input.e2e`` (OptimaAdapter / dev, ingests the E2E).
    """
    name = "bscan.dcm" if _is_dicom(filename, body) else "input.e2e"
    path = tempdir / name
    path.write_bytes(body)
    return path


def _check_auth(token_header: str | None) -> None:
    expected = _config.settings.auth_token
    if not expected:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail=(
                "Sidecar /run not configured (RETINAL_INFERENCE_AUTH_TOKEN unset)"
            ),
        )
    if token_header != expected:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid or missing X-MUW-Inference-Token",
        )


def _check_endpoint_enabled() -> None:
    if not _config.settings.run_endpoint_enabled:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Sidecar /run is disabled in this deployment",
        )


def _record_idempotent(key: str, envelope: RunEnvelope) -> None:
    _idempotency_cache[key] = envelope
    while len(_idempotency_cache) > _IDEMPOTENCY_CACHE_MAX:
        _idempotency_cache.popitem(last=False)


@router.post("/run", response_model=RunEnvelope, status_code=200)
async def run(
    file: UploadFile = File(..., description="bscan.dcm (DICOM) or Heidelberg .e2e"),
    task: str = Form(..., description="One of fluid / onl / pr / ga"),
    laterality: str = Form(..., description="OD or OS"),
    scan_index: int = Form(
        default=0,
        description="Volume index in a multi-acquisition .e2e (default 0); ignored for DICOM",
    ),
    x_muw_inference_token: str | None = Header(default=None),
    idempotency_key: str | None = Header(default=None),
) -> RunEnvelope:
    _check_endpoint_enabled()
    _check_auth(x_muw_inference_token)

    if task not in SUPPORTED_TASKS:
        raise HTTPException(
            status_code=400,
            detail=f"Unsupported task '{task}' — v1 enables: {sorted(SUPPORTED_TASKS)}",
        )
    if laterality not in _LATERALITIES:
        raise HTTPException(
            status_code=400,
            detail=f"laterality must be OD or OS (got '{laterality}')",
        )
    if scan_index < 0:
        raise HTTPException(
            status_code=400,
            detail=f"scan_index must be >= 0 (got {scan_index})",
        )

    if idempotency_key and idempotency_key in _idempotency_cache:
        # Move to end (LRU touch).
        cached = _idempotency_cache.pop(idempotency_key)
        _idempotency_cache[idempotency_key] = cached
        return cached

    typed_task: TaskName = task  # type: ignore[assignment]
    typed_laterality: Literal["OD", "OS"] = laterality  # type: ignore[assignment]

    async with _run_lock:
        return await _run_locked(
            file=file,
            task=typed_task,
            laterality=typed_laterality,
            scan_index=scan_index,
            idempotency_key=idempotency_key,
        )


async def _run_locked(
    *,
    file: UploadFile,
    task: TaskName,
    laterality: Literal["OD", "OS"],
    scan_index: int,
    idempotency_key: str | None,
) -> RunEnvelope:
    shared_tmpdir = _config.settings.shared_tmpdir
    shared_tmpdir.mkdir(parents=True, exist_ok=True)

    body = await file.read()
    if not body:
        raise HTTPException(status_code=400, detail="file part is empty")

    # 2026-06-24 — preserve the tempdir on FAILURE so post-mortem can run
    # the (host-native) IOWA binary by hand against the prepared bscan.dcm.
    # On success the tempdir is removed as before. Manual mkdtemp + try/
    # except replaces the TemporaryDirectory context manager which would
    # auto-clean even on exception.
    #
    # Periodically prune stale failed-job dirs:
    #   find /scratch/$USER/retinal-inference/tmp -maxdepth 1 -type d \
    #        -name 'run_*' -mtime +1 -exec rm -rf {} +
    td = tempfile.mkdtemp(prefix="run_", dir=str(shared_tmpdir))
    _cleanup_on_success = True
    try:
        tempdir = Path(td)
        # DICOM -> bscan.dcm (cluster ApptainerAdapter); .e2e -> input.e2e (dev
        # OptimaAdapter). The adapter resolves the path from its name.
        input_path = _materialize_input(tempdir, file.filename, body)

        adapter = get_adapter()
        if not adapter.supports(task):
            raise HTTPException(
                status_code=400,
                detail=f"Task '{task}' is not supported by the current adapter",
            )

        try:
            # Both adapters write their artifacts back into the same tempdir
            # (host-bind shared) via out_dir_override.
            result = adapter.full_volume(
                task,
                input_path,
                laterality,
                out_dir_override=tempdir,
                scan_index=scan_index,
            )
        except FastScreenUnavailable as e:
            # full_volume shouldn't raise this, but be defensive.
            raise HTTPException(status_code=500, detail=str(e)) from e
        except UnsupportedTaskError as e:
            raise HTTPException(status_code=400, detail=str(e)) from e
        except FileNotFoundError as e:
            raise HTTPException(status_code=400, detail=str(e)) from e
        except IndexError as e:
            # scan_index out of range — bubble as 400 so the SPA can show the
            # operator a clean "this file has N volumes" message.
            raise HTTPException(status_code=400, detail=str(e)) from e
        except urllib.error.HTTPError as e:
            # OptimaAdapter._post_json re-raises with the runner's detail in
            # the reason — surface it.
            raise HTTPException(
                status_code=502,
                detail=f"runner returned {e.code}: {e.reason}",
            ) from e
        except Exception as e:
            # 2026-06-24 — anything else (subprocess failures, missing
            # binaries, OOM kills, …) was previously escaping to
            # Starlette's default 500 handler which returns the bare
            # "Internal Server Error" body — useless for debugging. Log
            # the full traceback locally for the operator-with-shell
            # path, but ALSO surface the class + message in the HTTP
            # response so the Java side's RemoteRetinalInferenceClient
            # body capture (commit 0755bbc66) lands something actionable
            # in the application log.
            import logging
            import traceback as _tb
            logging.getLogger(__name__).exception(
                "/run unhandled exception for task=%s scan_index=%s",
                task, scan_index,
            )
            tb_short = _tb.format_exc(limit=4)
            # 2026-06-24 — also keep the tempdir on disk so the operator
            # can rerun the binary by hand against the same bscan.dcm
            # (no race to grab artifacts before cleanup).
            _cleanup_on_success = False
            raise HTTPException(
                status_code=500,
                detail=(
                    f"{type(e).__name__}: {e}\n"
                    f"--- traceback (most-recent 4 frames) ---\n{tb_short}"
                    f"--- preserved tempdir ---\n{tempdir}\n"
                ),
            ) from e

        # When the adapter declared an explicit returned-artifact list, collect
        # only those (e.g. GA returns RPEL but not the IOWA layers it consumed);
        # otherwise fall back to the permissive allowlist walk (dev OptimaAdapter).
        artifacts = collect_artifacts(tempdir, only=result.artifact_names)
        rewritten_payload = rewrite_payload_paths(
            dict(result.output_payload), tempdir
        )

        envelope = RunEnvelope(
            model_version=result.model_version,
            primary_metric_value=result.primary_metric_value,
            primary_metric_unit=result.primary_metric_unit,
            output_payload=rewritten_payload,
            confidence=result.confidence,
            artifacts=artifacts,
            task=task,
            laterality=laterality,
        )

        if idempotency_key:
            _record_idempotent(idempotency_key, envelope)

        LOG.info(
            "POST /run task=%s laterality=%s artifacts=%d metric=%s %s",
            task,
            laterality,
            len(artifacts),
            envelope.primary_metric_value,
            envelope.primary_metric_unit or "",
        )
        return envelope
    finally:
        if _cleanup_on_success:
            import shutil as _shutil
            try:
                _shutil.rmtree(td, ignore_errors=True)
            except Exception:
                pass