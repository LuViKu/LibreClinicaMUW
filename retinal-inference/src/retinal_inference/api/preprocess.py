"""POST /preprocess — app-VM-side ``.e2e`` -> ``bscan.dcm`` (PHI-redacted) (DR-022).

The cluster ``ApptainerAdapter`` is DICOM-only, and the raw Heidelberg ``.e2e``
carries patient identifiers that must never leave the app VM. This endpoint runs
the shared E2E->DICOM ingestion (``prepare_bscan_dcm``, which strips PHI) so the
Java backend can convert app-side and forward only the redacted ``bscan.dcm`` to
the remote ``/run``.

Deployed as a small preprocess-only sidecar co-located with Tomcat on the app VM
(``RETINAL_INFERENCE_PREPROCESS_ENDPOINT_ENABLED=true`` + an auth token; no GPU,
no models, no adapter). Stateless: the tempdir is deleted before the response.

In addition to streaming the DCM back inline, this endpoint:

* Stamps 7 response headers carrying pixel geometry + the e2e UUID — the Java
  adapter parses these into a ``PixelGeometry`` carried on ``RemoteRunResult``.
* When ``RETINAL_INFERENCE_BSCAN_STORE`` is set + writable, persists three
  companion files under ``<store>/<e2eUuid>/`` — ``bscan.dcm`` (atomic move),
  ``fundus.png`` (PNG-encoded SLO), and ``geometry.json`` (registration
  metadata). The bind mount lives under the artifact-store root so the GET
  endpoint (Wave 3) can serve the files back to the SPA.
* Dedups at the e2e-uuid level — a second call with the same .e2e finds the
  bscan.dcm already there and skips the rewrite.
"""

from __future__ import annotations

import gc
import hashlib
import json
import logging
import os
import tempfile
from pathlib import Path

import pydicom
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

# DR-024 — the .e2e -> bscan.dcm conversion lives in a separate package
# (``muw-e2e-converter``) installed only in the LOCAL app-VM
# ``retinal-preprocess`` posture. The cluster's inference sidecar does
# NOT install it (cluster uses ApptainerAdapter which rejects .e2e). When
# the package is absent, ``/preprocess`` returns 503 so an operator who
# mistakenly enables this endpoint on the cluster sees a loud, clear
# error instead of a silent failure. See DR-024 for the split rationale.
try:
    from muw_e2e_converter import (
        build_geometry,
        extract_fundus_png,
        prepare_bscan_dcm,
        read_e2e_volume,
    )
    _CONVERTER_AVAILABLE = True
except ModuleNotFoundError:  # cluster posture — converter not installed
    _CONVERTER_AVAILABLE = False
    prepare_bscan_dcm = read_e2e_volume = build_geometry = extract_fundus_png = None  # type: ignore[assignment]

LOG = logging.getLogger(__name__)

router = APIRouter()

_EXPOSED_HEADERS = (
    "X-MUW-Pixel-Axial-Mm, X-MUW-Pixel-Lateral-Mm, X-MUW-Pixel-Slice-Mm, "
    "X-MUW-Bscan-Dim-Z, X-MUW-Bscan-Dim-Y, X-MUW-Bscan-Dim-X, X-MUW-E2E-Uuid, "
    "X-MUW-Acquisition-Date"
)


def _check_endpoint_enabled() -> None:
    if not _config.settings.preprocess_endpoint_enabled:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Sidecar /preprocess is disabled in this deployment",
        )
    if not _CONVERTER_AVAILABLE:
        # DR-024 — the converter is the muw-e2e-converter package, deliberately
        # not installed in the cluster posture. If somebody enables /preprocess
        # on the cluster, this guard converts the resulting AttributeError into
        # a clear 503 so the deployment misconfiguration is obvious.
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail=(
                "muw-e2e-converter is not installed in this deployment posture; "
                "this endpoint is inactive (see DR-024). The cluster runs "
                "ApptainerAdapter which expects a pre-converted bscan.dcm; "
                "E2E conversion belongs in the app-VM retinal-preprocess container."
            ),
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


def _derive_uuid(body: bytes) -> str:
    """Deterministic e2e UUID = first 36 chars of sha256(body) shaped as a UUID.

    Same .e2e bytes -> same UUID across re-uploads, which is the whole point
    of the dedup contract.
    """
    digest = hashlib.sha256(body).hexdigest()  # 64 hex chars
    return _format_uuid_from_digest(digest)


def _format_uuid_from_digest(digest: str) -> str:
    """Shape a hex sha256 digest into the 8-4-4-4-12 UUID layout."""
    return f"{digest[0:8]}-{digest[8:12]}-{digest[12:16]}-{digest[16:20]}-{digest[20:32]}"


def _bscan_store_dir() -> Path | None:
    """Resolve the per-e2e store root; return None when unconfigured / non-writable."""
    raw = _config.settings.bscan_store
    if raw is None:
        return None
    try:
        store = Path(raw)
        store.mkdir(parents=True, exist_ok=True)
        # Probe writability with a sentinel; cleaner than catching mid-write.
        if not os.access(store, os.W_OK):
            LOG.warning("RETINAL_INFERENCE_BSCAN_STORE=%s is not writable; skipping persistence", store)
            return None
        return store
    except OSError as e:  # noqa: BLE001
        LOG.warning("RETINAL_INFERENCE_BSCAN_STORE=%s unusable (%s); skipping persistence", raw, e)
        return None


def _atomic_write(target: Path, payload: bytes) -> None:
    """Write ``payload`` to ``target`` via a temp file + os.replace.

    ``os.replace`` is atomic on POSIX when both paths are on the same FS,
    which they are (both inside ``target.parent``).
    """
    target.parent.mkdir(parents=True, exist_ok=True)
    tmp = target.parent / f"{target.name}.tmp.{os.getpid()}"
    tmp.write_bytes(payload)
    os.replace(tmp, target)


def _persist_bscan_dcm(target: Path, dcm_bytes: bytes) -> bool:
    """Write ``dcm_bytes`` to ``target`` unless an existing file matches the size.

    Returns True when a write happened, False on a dedup-skip. Size match is a
    cheap idempotency check — full hashing on every upload would cost more
    than the rare race it'd catch (same e2e UUID + different bytes shouldn't
    happen by construction).
    """
    if target.is_file() and target.stat().st_size == len(dcm_bytes):
        LOG.info("dedup-skip bscan.dcm at %s (size match, %d bytes)", target, len(dcm_bytes))
        return False
    _atomic_write(target, dcm_bytes)
    return True


def _persist_fundus_png(target: Path, png_bytes: bytes) -> None:
    if not png_bytes:
        return
    if target.is_file() and target.stat().st_size > 0:
        LOG.info("dedup-skip fundus.png at %s (already non-empty)", target)
        return
    _atomic_write(target, png_bytes)


def _persist_geometry(target: Path, geometry: dict) -> None:
    payload = json.dumps(geometry, indent=2, sort_keys=True).encode("utf-8")
    _atomic_write(target, payload)


@router.post("/preprocess")
async def preprocess(
    file: UploadFile = File(..., description="Heidelberg .e2e binary"),
    laterality: str | None = Form(default=None, description="OD or OS (informational; derived from the E2E)"),
    e2e_uuid: str | None = Form(default=None, description="Optional e2e UUID; defaults to sha256(body)-derived"),
    scan_index: int = Form(
        default=0,
        description="Volume index in a multi-acquisition .e2e (default 0)",
    ),
    x_muw_inference_token: str | None = Header(default=None),
) -> Response:
    _check_endpoint_enabled()
    _check_auth(x_muw_inference_token)

    if scan_index < 0:
        raise HTTPException(
            status_code=400,
            detail=f"scan_index must be >= 0 (got {scan_index})",
        )

    shared_tmpdir = _config.settings.shared_tmpdir
    shared_tmpdir.mkdir(parents=True, exist_ok=True)

    # 2026-06-19 — stream the upload to a tempfile in 64 KiB chunks
    # while computing sha256 incrementally. Previously `body =
    # await file.read()` materialised the full 200 MB upload twice
    # (once in uvicorn's multipart parser, once in the handler) and
    # then again as `e2e_path.write_bytes(body)`. Streaming drops
    # ~200 MB of peak RSS on a 197 MB .e2e — enough to keep the
    # sidecar inside the 1.5 GiB compose mem_limit on a 3.8 GiB
    # Docker VM.

    with tempfile.TemporaryDirectory(prefix="prep_", dir=str(shared_tmpdir)) as td:
        tempdir = Path(td)
        e2e_path = tempdir / "input.e2e"
        total = 0
        hasher = hashlib.sha256()
        with e2e_path.open("wb") as out:
            while True:
                chunk = await file.read(65536)
                if not chunk:
                    break
                out.write(chunk)
                hasher.update(chunk)
                total += len(chunk)
        if total == 0:
            raise HTTPException(status_code=400, detail="file part is empty")

        uuid = (
            e2e_uuid.strip()
            if e2e_uuid and e2e_uuid.strip()
            else _format_uuid_from_digest(hasher.hexdigest())
        )
        try:
            out_dir = prepare_bscan_dcm(e2e_path, tempdir, scan_index=scan_index)
        except FileNotFoundError as e:
            raise HTTPException(status_code=400, detail=str(e)) from e
        except IndexError as e:
            # scan_index out of range — surface as 400 so the SPA can show
            # the operator a clean "this file has N volumes" message.
            raise HTTPException(status_code=400, detail=str(e)) from e
        except Exception as e:  # oct-converter / pydicom failure on a bad E2E
            raise HTTPException(
                status_code=422, detail=f"E2E -> DICOM conversion failed: {e}"
            ) from e
        dcm_bytes = (Path(out_dir) / "bscan.dcm").read_bytes()

        # Pixel geometry must come back to the Java client every call (the
        # SPA doesn't care about the bind-mount but the runner does); we read
        # it back out of the DCM the same conversion just wrote so it stays
        # bit-identical to the headers.
        ds = pydicom.dcmread(str(Path(out_dir) / "bscan.dcm"))
        try:
            bv = read_e2e_volume(e2e_path, scan_index=scan_index)
        except Exception as e:  # noqa: BLE001 — already converted once, this is best-effort metadata
            LOG.warning("Geometry read failed on %s: %s", e2e_path, e)
            bv = None

        # Companion file writes (best-effort; skipped when no store configured).
        # For scan_index > 0 we use a per-volume subdir so different volumes
        # from the same .e2e don't overwrite each other's bscan.dcm / fundus.
        store = _bscan_store_dir()
        if store is not None and bv is not None:
            if scan_index > 0:
                target_dir = store / uuid / f"scan-{scan_index}"
            else:
                target_dir = store / uuid
            try:
                _persist_bscan_dcm(target_dir / "bscan.dcm", dcm_bytes)
                fundus_png, fundus_dims = extract_fundus_png(e2e_path, scan_index=scan_index)
                _persist_fundus_png(target_dir / "fundus.png", fundus_png)
                geom = build_geometry(
                    bv, ds, fundus_dims, e2e_path=e2e_path, scan_index=scan_index
                )
                _persist_geometry(target_dir / "geometry.json", geom)
            except Exception as e:  # noqa: BLE001 — never let companion-write failures break the request
                LOG.warning("Companion persistence failed for %s: %s", uuid, e)
        elif store is None:
            LOG.info("bscan_store unset; skipping companion-file persistence for e2e %s", uuid)

        # 2026-06-19 — release the large per-request allocations before
        # we exit the TemporaryDirectory context. `ds` (a pydicom
        # Dataset) carries the full PixelData buffer (~192 MB for a
        # 97×496×512 volume); `bv.volume_u8` carries the quantised
        # volume (~48 MB). Neither is needed past this point — the
        # response only needs the response body (`dcm_bytes`, already
        # read from disk) and a handful of geometry scalars, which we
        # copy out of `bv` into `geom_headers` before dropping it.
        geom_headers: dict[str, str] = {}
        if bv is not None:
            geom_headers = {
                "X-MUW-Pixel-Axial-Mm": f"{bv.axial_mm:.8f}",
                "X-MUW-Pixel-Lateral-Mm": f"{bv.lateral_mm:.8f}",
                "X-MUW-Pixel-Slice-Mm": f"{bv.slice_mm:.8f}",
                "X-MUW-Bscan-Dim-Z": str(int(bv.n_bscans)),
                "X-MUW-Bscan-Dim-Y": str(int(bv.rows)),
                "X-MUW-Bscan-Dim-X": str(int(bv.cols)),
            }
            # 2026-06-23 user-feedback round — surface the .e2e
            # acquisition date so the Java client can persist it on
            # the retinal_inference_job row and the nAMD workspace
            # can plot the trend chart against the real scan date
            # rather than the upload completion time.
            if bv.acquisition_date:
                geom_headers["X-MUW-Acquisition-Date"] = bv.acquisition_date
        del ds
        del bv
        gc.collect()

    LOG.info(
        "POST /preprocess -> bscan.dcm (%d bytes, laterality=%s, e2e_uuid=%s)",
        len(dcm_bytes),
        laterality,
        uuid,
    )

    headers = {"Content-Disposition": 'attachment; filename="bscan.dcm"'}
    headers.update(geom_headers)
    headers["X-MUW-E2E-Uuid"] = uuid
    headers["Access-Control-Expose-Headers"] = _EXPOSED_HEADERS

    return Response(
        content=dcm_bytes,
        media_type="application/dicom",
        headers=headers,
    )
