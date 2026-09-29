"""POST /derive — local post-processing of cluster-emitted segmentation artifacts.

Architectural intent (2026-06-22): the GPU cluster only ships the raw
segmentation (e.g. ``fluidseg.npz``) — derived presentation artifacts
(projection PNGs, per-slice overlay PNGs, heatmaps) are computed
locally on the app VM so the cluster image stays minimal and the
local artifact-store layout is the single source of truth for what
the SPA renders.

Flow:

  1. Java backend persists the cluster response (``fluidseg.npz``)
     into ``<artifact-store>/<job-uuid>/`` via
     ``RetinalArtifactStorageService.persistInto``.
  2. Java POSTs ``{"job_dir": "<artifact-store>/<job-uuid>"}`` to
     this endpoint.
  3. The endpoint loads the npz, calls
     ``projection_fluid.render_fluid_projection``, and writes the
     RGBA PNGs back into the same job directory.
  4. SPA's existing ``GET /retinal-jobs/{id}/artifacts/{name}``
     serves the freshly-derived PNGs without code change.

Idempotent: if the composite ``projection_fluid.png`` already exists
the call is a no-op (returns ``{"skipped": true}``). Pass
``force=true`` to re-emit.

Auth: same shared-secret pattern as ``/preprocess`` — the
``RETINAL_INFERENCE_AUTH_TOKEN`` header gate, closed when no token is
configured. ``job_dir`` must name a directory below
``RETINAL_INFERENCE_ARTIFACT_STORE_PATH``; error details never repeat it.
"""

from __future__ import annotations

import logging

from fastapi import APIRouter, Header, HTTPException, status
from pydantic import BaseModel

from retinal_inference import config as _config
from retinal_inference.security import resolve_under, token_matches

LOG = logging.getLogger(__name__)

router = APIRouter()


class DeriveRequest(BaseModel):
    """Body for ``POST /derive``."""

    job_dir: str
    task: str = "fluid"
    force: bool = False


class DeriveResponse(BaseModel):
    job_dir: str
    written: list[str]
    skipped: bool = False


def _check_auth(token_header: str | None) -> None:
    expected = _config.settings.auth_token
    if not expected:
        # Closed, like /run and /preprocess: a deployment without the shared
        # secret does not serve /derive at all. (The app sends the same
        # token it uses for /preprocess, so every deployment that preprocesses
        # has one.)
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="Sidecar /derive not configured (RETINAL_INFERENCE_AUTH_TOKEN unset)",
        )
    if not token_matches(token_header, expected):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Missing or invalid X-Auth-Token",
        )


@router.post("/derive", response_model=DeriveResponse)
def derive(
    body: DeriveRequest,
    x_auth_token: str | None = Header(default=None, alias="X-Auth-Token"),
) -> DeriveResponse:
    """Derive presentation artifacts from a job dir's raw segmentation."""
    _check_auth(x_auth_token)
    # The app hands over <artifact-store>/<job-uuid>. Anything that does not
    # resolve below the store is refused with one message, whether or not it
    # exists, so the endpoint cannot be used to probe the filesystem.
    job_dir = resolve_under(_config.settings.artifact_store_path, body.job_dir)
    if job_dir is None or not job_dir.is_dir():
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="job_dir is not a job directory in the artifact store",
        )

    if body.task != "fluid":
        # GA / ONL / PR projections live in their own helpers (TBD); for
        # now derive is fluid-only. The endpoint returns a structured
        # noop so the Java caller can branch on `skipped` without
        # treating it as a failure.
        return DeriveResponse(job_dir=body.job_dir, written=[], skipped=True)

    npz = job_dir / "fluidseg.npz"
    if not npz.is_file():
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="job_dir has no fluidseg.npz",
        )

    composite = job_dir / "projection_fluid.png"
    if composite.is_file() and not body.force:
        return DeriveResponse(job_dir=body.job_dir, written=[], skipped=True)

    # Lazy imports — numpy + the projection helper are heavy but the
    # endpoint is rarely hit at this point so module-import time stays
    # off the cold-start path.
    import numpy as np

    from retinal_inference.projection_fluid import render_fluid_projection

    with np.load(npz) as data:
        if "segmentation" not in data:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="fluidseg.npz has no 'segmentation' array",
            )
        seg = data["segmentation"]

    # Snapshot the dir state pre-write so we can return only the new
    # files (the consumer logs this for traceability).
    before = {p.name for p in job_dir.iterdir() if p.is_file()}
    try:
        render_fluid_projection(seg, job_dir)
    except Exception as exc:  # noqa: BLE001 — surface as 500 with detail
        LOG.exception("derive: render_fluid_projection failed for %s", job_dir)
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"render_fluid_projection failed ({type(exc).__name__})",
        ) from exc
    after = {p.name for p in job_dir.iterdir() if p.is_file()}
    new_files = sorted(after - before)
    LOG.info("derive: wrote %d artifact(s) into %s", len(new_files), job_dir)
    return DeriveResponse(job_dir=body.job_dir, written=new_files, skipped=False)
