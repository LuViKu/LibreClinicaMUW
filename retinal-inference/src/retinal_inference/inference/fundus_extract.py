"""Fundus (SLO en-face) extraction + B-scan-to-fundus registration metadata.

The app-VM ``/preprocess`` endpoint persists three companion files per .e2e:

* ``bscan.dcm``   — PHI-redacted multi-frame OCT (already written by
  ``e2e_parser.write_bscan_dcm``).
* ``fundus.png``  — the Heidelberg SLO en-face image, PNG-encoded.
* ``geometry.json`` — fundus dimensions, per-B-scan endpoint coords in the
  SLO pixel frame, and an MVP fovea estimate.

This module owns the second + third items. The fundus PNG lets the SPA
overlay the B-scan scan pattern on the en-face; ``geometry.json`` carries the
registration metadata the SPA needs to draw the overlay (one B-scan = one
line segment ``(x1,y1)-(x2,y2)`` in the SLO frame).

The B-scan endpoint coordinates come from oct-converter's ``bscan_metadata``
struct (see ``oct_converter/readers/binary_structs/e2e_binary.py`` —
``posX1/posY1/posX2/posY2`` are Float32 fields stored in the fundus image's
pixel frame for Spectralis exports). The existing ``_derive_spacing_mm``
helper in ``e2e_parser.py`` already treats them as fundus-frame pixel coords
(``math.hypot`` to recover per-line lengths); we propagate that contract.

The SLO scale (mm/pixel) is **not** directly exposed by oct-converter — the
``image_structure`` chunk that holds the fundus image only carries width +
height. We fall back to the B-scan's lateral mm/px as a degraded
approximation and label it ``scale_source: bscan_fallback`` so callers know
the geometry is volume-derived rather than SLO-native.
"""

from __future__ import annotations

import io
import logging
from pathlib import Path
from typing import Any

LOG = logging.getLogger(__name__)


def extract_fundus_png(e2e_path: Path) -> tuple[bytes, tuple[int, int] | None]:
    """Read the SLO en-face image from ``e2e_path`` and PNG-encode it.

    Returns
    -------
    (png_bytes, (width_px, height_px)) on success, or ``(b"", None)`` when
    the .e2e carries no fundus image. The caller skips the file write in the
    empty case (a missing SLO is rare but possible for non-volume exports).
    """
    try:
        from oct_converter.readers import E2E
        from PIL import Image
    except ImportError as e:  # pragma: no cover — surfaced at runtime only
        LOG.warning("Fundus extraction unavailable (missing ingest deps): %s", e)
        return b"", None

    try:
        fundi = E2E(str(e2e_path)).read_fundus_image()
    except Exception as e:  # noqa: BLE001
        LOG.warning("Fundus read failed on %s: %s", e2e_path, e)
        return b"", None

    if not fundi:
        LOG.warning("No fundus image in %s", e2e_path)
        return b"", None

    # When multiple fundi exist, pick the largest by area (most likely the
    # SLO en-face of the volume scan; OCT-only re-acquisitions tend to be
    # smaller line/AF scans).
    fundus = max(
        fundi,
        key=lambda f: int(getattr(f.image, "size", 0) or 0),
    )
    arr = fundus.image
    if arr is None or getattr(arr, "size", 0) == 0:
        LOG.warning("Fundus chunk in %s decoded to an empty array", e2e_path)
        return b"", None

    # oct-converter returns the fundus as uint8 (H, W) — feed straight to PIL.
    pil_img = Image.fromarray(arr)
    buf = io.BytesIO()
    pil_img.save(buf, format="PNG", optimize=True)
    h, w = arr.shape[0], arr.shape[1]
    return buf.getvalue(), (int(w), int(h))


def build_geometry(
    bv: Any,  # BscanVolume — kept loose to avoid the e2e_parser import cycle
    ds: Any,  # pydicom.Dataset
    fundus_dims: tuple[int, int] | None,
    e2e_path: Path | None = None,
) -> dict:
    """Assemble the registration JSON the SPA will use to align B-scans on the SLO.

    Parameters
    ----------
    bv:
        ``BscanVolume`` returned by ``read_e2e_volume`` — carries the spacing
        + dimensions + laterality.
    ds:
        The DICOM dataset (post-PHI-redact) — we copy a handful of fields from
        it for the bscan block so downstream consumers can read either spine.
    fundus_dims:
        ``(width_px, height_px)`` of the SLO en-face image, or ``None`` when no
        fundus was extracted (the bscan-positions block is then degenerate).
    e2e_path:
        Optional .e2e path. When provided, re-reads ``bscan_data`` from the
        .e2e to recover the per-B-scan endpoint coords; when None, falls back
        to ``getattr(bv, 'bscan_data', None)`` (set by the caller if it kept
        the raw list around).

    The output shape matches the spec at
    `docs/development/modernization/retinal-preprocess-geometry.md` — see the
    module docstring for the source of truth.
    """
    fundus_width = int(fundus_dims[0]) if fundus_dims else 0
    fundus_height = int(fundus_dims[1]) if fundus_dims else 0

    bscan_data = _load_bscan_data(bv, e2e_path)

    positions: list[dict[str, float | int]] = []
    if bscan_data:
        for idx, b in enumerate(bscan_data):
            if not all(k in b for k in ("posX1", "posY1", "posX2", "posY2")):
                continue
            positions.append(
                {
                    "z": idx,
                    "x1": float(b["posX1"]),
                    "y1": float(b["posY1"]),
                    "x2": float(b["posX2"]),
                    "y2": float(b["posY2"]),
                }
            )

    bbox = _aabb(positions)

    fovea = _fovea_volume_center(positions, bv)

    # SLO scale (mm/px). Not directly exposed by oct-converter; fall back to
    # the B-scan lateral spacing and label the source so callers can act on it.
    fundus_lateral_mm_per_px = float(bv.lateral_mm)
    fundus_slice_mm_per_px = float(bv.slice_mm)
    scale_source = "bscan_fallback"
    LOG.info(
        "Geometry built: fundus=%dx%d, n_bscans=%d, scale_source=%s",
        fundus_width,
        fundus_height,
        bv.n_bscans,
        scale_source,
    )

    return {
        "fundus": {
            "width_px": fundus_width,
            "height_px": fundus_height,
            "lateral_mm_per_px": fundus_lateral_mm_per_px,
            "slice_mm_per_px": fundus_slice_mm_per_px,
            "scale_source": scale_source,
        },
        "bscan": {
            "dim_x_ascans": int(getattr(ds, "Columns", bv.cols)),
            "dim_y_rows": int(getattr(ds, "Rows", bv.rows)),
            "dim_z_bscans": int(bv.n_bscans),
            "pixel_axial_mm": float(bv.axial_mm),
            "pixel_lateral_mm": float(bv.lateral_mm),
            "pixel_slice_mm": float(bv.slice_mm),
        },
        "bscan_positions_fundus_px": positions,
        "scan_bbox_fundus_px": bbox,
        "fovea_estimate_fundus_px": fovea,
    }


def _load_bscan_data(bv: Any, e2e_path: Path | None) -> list[dict] | None:
    """Best-effort recovery of the per-B-scan metadata list from the .e2e.

    The preprocess endpoint re-reads the .e2e here when ``bv`` doesn't already
    carry it — keeps ``read_e2e_volume`` backwards-compatible (callers that
    only want the spacing don't pay the second-pass cost).
    """
    raw = getattr(bv, "bscan_data", None)
    if raw:
        return list(raw)
    if e2e_path is None:
        return None
    try:
        from oct_converter.readers import E2E
    except ImportError:  # pragma: no cover
        return None
    try:
        reader = E2E(str(e2e_path))
        volumes = reader.read_oct_volume()
        if not volumes:
            return None
        vol_meta = max(volumes, key=lambda v: int(getattr(v, "num_slices", 0) or 0))
        md = getattr(vol_meta, "metadata", None) or {}
        return list(md.get("bscan_data", []) or [])
    except Exception as e:  # noqa: BLE001
        LOG.warning("Failed to re-read bscan_data from %s: %s", e2e_path, e)
        return None


def _aabb(positions: list[dict[str, float | int]]) -> dict[str, float]:
    if not positions:
        return {"x": 0.0, "y": 0.0, "width": 0.0, "height": 0.0}
    xs = [p["x1"] for p in positions] + [p["x2"] for p in positions]
    ys = [p["y1"] for p in positions] + [p["y2"] for p in positions]
    x0, x1 = float(min(xs)), float(max(xs))
    y0, y1 = float(min(ys)), float(max(ys))
    return {"x": x0, "y": y0, "width": x1 - x0, "height": y1 - y0}


def _fovea_volume_center(
    positions: list[dict[str, float | int]], bv: Any
) -> dict[str, float | int | str]:
    """MVP fovea estimate: centre A-scan of the centre B-scan.

    Real fovea detection is a model-shaped problem; for now we fall back to
    the geometric centre of the volume and label the source so a downstream
    consumer can replace it later without breaking the schema.
    """
    n_bscans = int(bv.n_bscans)
    n_ascans = int(bv.cols)
    bscan_z = n_bscans // 2
    ascan_x = n_ascans // 2

    if positions and 0 <= bscan_z < len(positions):
        # Linear interpolation along the centre B-scan's endpoint segment.
        p = positions[bscan_z]
        denom = max(1, n_ascans - 1)
        t = ascan_x / denom
        x = float(p["x1"]) + t * (float(p["x2"]) - float(p["x1"]))
        y = float(p["y1"]) + t * (float(p["y2"]) - float(p["y1"]))
    else:
        x, y = 0.0, 0.0

    return {
        "x": x,
        "y": y,
        "bscan_z": bscan_z,
        "ascan_x": ascan_x,
        "source": "volume-center-mvp",
    }
