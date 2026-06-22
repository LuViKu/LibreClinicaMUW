"""En-face biomarker projections — 2D fundus overlays per task.

Each runner emits its primary spatial artifact in volume / per-A-scan
coordinates (3D label volumes for fluid/GA, 2D surface CSVs for layer
thicknesses). The SPA viewer renders the fundus + bbox + ETDRS rings on
2D screen coordinates and needs a 2D translucent overlay aligned to the
scan rectangle to surface where each biomarker is located.

This module converts the runner's native spatial output into a small
RGBA PNG per task, sized ``(n_ascans, n_bscans)`` — one pixel per
(B-scan slice, A-scan column). The SPA reads it through the existing
``GET /retinal-jobs/{id}/artifacts/{name}`` endpoint and stretches it
to the ``scan_bbox_fundus_px`` rectangle in the FundusOverlay SVG.

Per-task semantics:

* **fluid** — IRF / SRF / PED present per (z, x); each biomarker gets
  its own RGB channel + the pixel alpha is the union. Operators can
  read overlap visually from the additive blend (RGB → magenta etc.).
* **GA**   — RPEL CSV is already binary (z, x); one purple channel.
* **onl/pr** — layer thickness in µm per (z, x) → viridis-flavoured
  heatmap (no explicit transparency; thinner-than-threshold pixels go
  black). MVP keeps a 4-stop ramp inline rather than pulling in
  matplotlib for the dev compose.

All functions take the runner's native artifact + the output directory
they should write into, return the relative PNG filename so the runner
can include it in its response payload. The PNG is small (typically
under 4 KB after zlib compression for binary masks; under 30 KB for
the thickness heatmap).
"""

from __future__ import annotations

import logging
import struct
import zlib
from pathlib import Path
from typing import Any

LOG = logging.getLogger(__name__)


# RGBA tuples (0-255) — match the SPA's BIOMARKER_COLORS in
# `web/src/spa/src/components/FundusOverlay.vue`. Keep these in sync.
_COLOR_IRF = (56, 189, 248)   # sky-400 — fluid IRF
_COLOR_SRF = (251, 146, 60)   # orange-400 — fluid SRF
_COLOR_PED = (217, 70, 239)   # fuchsia-500 — fluid PED
_COLOR_GA  = (192, 38, 211)   # fuchsia-700 — GA (RPEL)


def _write_rgba_png(rgba: Any, out_path: Path) -> None:
    """Write a 2D numpy RGBA uint8 array as a minimal PNG.

    Standalone implementation so the runners (which already depend on
    numpy) don't need to pull in Pillow for a single write call. Uses
    PNG's default filter (None) per row + zlib compression — well below
    100 KB for any realistic biomarker mask.
    """
    import numpy as np

    if rgba.ndim != 3 or rgba.shape[2] != 4:
        raise ValueError(f"Expected (H, W, 4) RGBA array, got {rgba.shape}")
    if rgba.dtype != np.uint8:
        rgba = rgba.astype(np.uint8)

    height, width = rgba.shape[:2]

    def chunk(tag: bytes, payload: bytes) -> bytes:
        crc = zlib.crc32(tag + payload) & 0xFFFFFFFF
        return struct.pack(">I", len(payload)) + tag + payload + struct.pack(">I", crc)

    signature = b"\x89PNG\r\n\x1a\n"
    # IHDR — 8-bit RGBA, no interlace
    ihdr = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)

    # IDAT — each row prefixed with filter byte 0 (None), then zlib-deflated
    rows = bytearray()
    raw = rgba.tobytes()
    stride = width * 4
    for y in range(height):
        rows.append(0)
        rows.extend(raw[y * stride:(y + 1) * stride])
    idat = zlib.compress(bytes(rows), 9)

    out_path.write_bytes(signature + chunk(b"IHDR", ihdr) + chunk(b"IDAT", idat) + chunk(b"IEND", b""))


def _render_per_bscan_slice_pngs(seg: Any, out_dir: Path) -> int:
    """Emit one RGBA PNG per B-scan slice with the per-pixel
    segmentation mask coloured by biomarker.

    ``seg`` shape is ``(n_bscans, rows, cols)`` with labels
    1=IRF / 2=SRF / 3=PED. The output PNG is ``(rows, cols)`` —
    same lateral × axial plane as the DICOM frame the cornerstone
    viewer renders — so the SPA can absolutely-position the PNG on
    top of the canvas without resampling.

    Skips slices that have no positive voxels across all three
    biomarkers (most casebooks have biomarkers on a small subset
    of slices) so the artifact directory + the SPA's per-frame
    fetch stays light. Returns the count of slices written for
    logging.
    """
    import numpy as np

    n_bscans = seg.shape[0]
    written = 0
    for z in range(n_bscans):
        slice_seg = seg[z]
        if not np.any(slice_seg):
            continue
        h, w = slice_seg.shape
        rgba = np.zeros((h, w, 4), dtype=np.uint8)
        for label, color in (
            (1, _COLOR_IRF),
            (2, _COLOR_SRF),
            (3, _COLOR_PED),
        ):
            mask = slice_seg == label
            rgba[mask, 0] = color[0]
            rgba[mask, 1] = color[1]
            rgba[mask, 2] = color[2]
            rgba[mask, 3] = 160  # ~63% opacity — readable over the B-scan
        _write_rgba_png(rgba, out_dir / f"seg_bscan_{z:04d}.png")
        written += 1
    return written


def _render_single_biomarker_png(
    mask: Any, color: tuple[int, int, int], out_path: Path
) -> None:
    """Encode a single (z, x) boolean mask as an RGBA PNG.

    Pure-numpy helper used by ``render_fluid_projection`` to emit one
    PNG per biomarker so the SPA can toggle IRF / SRF / PED visibility
    independently. Pixel colour = the biomarker's RGB; alpha = 180 (~70%)
    where present, 0 elsewhere.
    """
    import numpy as np

    h, w = mask.shape
    rgba = np.zeros((h, w, 4), dtype=np.uint8)
    rgba[mask, 0] = color[0]
    rgba[mask, 1] = color[1]
    rgba[mask, 2] = color[2]
    rgba[mask, 3] = 180
    _write_rgba_png(rgba, out_path)


def render_fluid_projection(seg: Any, out_dir: Path) -> tuple[str, dict[str, list[float]]]:
    """Project the 3D fluidseg label volume onto the fundus plane.

    ``seg`` shape is ``(n_bscans, rows, cols)``; labels per the fluid
    runner are 1=IRF, 2=SRF, 3=PED. We collapse along the depth (rows)
    axis with ``np.any``, producing a per-(z, x) presence mask per
    biomarker.

    Writes four PNG artifacts into ``out_dir``:

    * ``projection_fluid.png`` — combined composite (all three
      biomarkers blended), back-compat for older SPA bundles.
    * ``projection_fluid_irf.png`` — IRF-only, sky-400 palette.
    * ``projection_fluid_srf.png`` — SRF-only, orange-400 palette.
    * ``projection_fluid_ped.png`` — PED-only, fuchsia-500 palette.

    The SPA loads the three single-biomarker PNGs when present and
    layers them with independent toggle visibility; the composite is
    the fallback when an older runner image hasn't been rebuilt yet.

    Returns ``(filename, per_bscan_mm2)`` — the second element is the
    ``payload['per_bscan_mm2'] = {irf: [...], srf: [...], ped: [...]}``
    contract so the per-B-scan stripe renderer also picks up real data.
    The returned ``filename`` stays the composite for back-compat with
    callers that hard-coded one filename.
    """
    import numpy as np

    n_bscans, rows, cols = seg.shape
    irf = np.any(seg == 1, axis=1)  # (z, x)
    srf = np.any(seg == 2, axis=1)
    ped = np.any(seg == 3, axis=1)

    # Composite PNG — all three biomarkers blended into one image, kept
    # for back-compat with the initial PR #223 SPA bundle.
    rgba = np.zeros((n_bscans, cols, 4), dtype=np.uint8)
    for mask, color in ((irf, _COLOR_IRF), (srf, _COLOR_SRF), (ped, _COLOR_PED)):
        rgba[mask, 0] = np.maximum(rgba[mask, 0], color[0])
        rgba[mask, 1] = np.maximum(rgba[mask, 1], color[1])
        rgba[mask, 2] = np.maximum(rgba[mask, 2], color[2])
    union = irf | srf | ped
    rgba[union, 3] = 180
    composite_filename = "projection_fluid.png"
    _write_rgba_png(rgba, out_dir / composite_filename)

    # Per-biomarker PNGs — drive the SPA's IRF / SRF / PED toggle layer.
    _render_single_biomarker_png(irf, _COLOR_IRF, out_dir / "projection_fluid_irf.png")
    _render_single_biomarker_png(srf, _COLOR_SRF, out_dir / "projection_fluid_srf.png")
    _render_single_biomarker_png(ped, _COLOR_PED, out_dir / "projection_fluid_ped.png")

    # 2026-06-22 — per-B-scan overlay PNGs. Each slice gets one RGBA
    # PNG whose pixels are coloured per-biomarker exactly where the
    # segmenter detected presence. The SPA's BscanViewer absolutely-
    # positions this PNG on top of the cornerstone canvas at the
    # current frame, so the operator sees the segmentation on the
    # B-scan it actually applies to. Slices without any positive
    # voxels skip the write so the artifact list stays compact.
    _render_per_bscan_slice_pngs(seg, out_dir)

    # Per-B-scan totals for the stripe renderer.
    per_bscan = {
        "irf": [int(c) for c in irf.sum(axis=1)],
        "srf": [int(c) for c in srf.sum(axis=1)],
        "ped": [int(c) for c in ped.sum(axis=1)],
    }
    return composite_filename, per_bscan


def render_ga_projection(rpel_csv_path: Path, out_dir: Path) -> tuple[str, list[int]]:
    """Project the GA RPEL CSV onto the fundus plane.

    The CSV is already 2D — one row per B-scan, one column per A-scan,
    integer values where RPE-loss is positive. We binarise + emit a
    single-colour RGBA PNG.

    Returns ``(filename, per_bscan_ascan_counts)`` so the runner can
    convert counts to mm² with its lateral / slice spacing.
    """
    import numpy as np

    rows = []
    with rpel_csv_path.open() as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            rows.append([int(float(v)) for v in line.split(",") if v.strip() != ""])
    if not rows:
        # Still write an empty 1×1 transparent PNG so the SPA's
        # artifact-presence check stays uniform.
        rgba = np.zeros((1, 1, 4), dtype=np.uint8)
        filename = "projection_ga.png"
        _write_rgba_png(rgba, out_dir / filename)
        return filename, []

    max_cols = max(len(r) for r in rows)
    arr = np.zeros((len(rows), max_cols), dtype=np.uint8)
    for i, r in enumerate(rows):
        arr[i, :len(r)] = [1 if v > 0 else 0 for v in r]

    rgba = np.zeros((arr.shape[0], arr.shape[1], 4), dtype=np.uint8)
    where = arr > 0
    rgba[where, 0] = _COLOR_GA[0]
    rgba[where, 1] = _COLOR_GA[1]
    rgba[where, 2] = _COLOR_GA[2]
    rgba[where, 3] = 180

    filename = "projection_ga.png"
    _write_rgba_png(rgba, out_dir / filename)
    return filename, [int(c) for c in arr.sum(axis=1)]


def _viridis_4stop(t: float) -> tuple[int, int, int]:
    """Tiny 4-stop approximation of the viridis colormap."""
    stops = [
        (0.00, (68, 1, 84)),
        (0.33, (59, 82, 139)),
        (0.66, (33, 145, 140)),
        (1.00, (253, 231, 37)),
    ]
    if t <= stops[0][0]:
        return stops[0][1]
    if t >= stops[-1][0]:
        return stops[-1][1]
    for i in range(len(stops) - 1):
        lo, hi = stops[i], stops[i + 1]
        if lo[0] <= t <= hi[0]:
            f = (t - lo[0]) / (hi[0] - lo[0])
            return (
                int(lo[1][0] + f * (hi[1][0] - lo[1][0])),
                int(lo[1][1] + f * (hi[1][1] - lo[1][1])),
                int(lo[1][2] + f * (hi[1][2] - lo[1][2])),
            )
    return stops[-1][1]


def render_thickness_projection(
    upper_csv_path: Path,
    lower_csv_path: Path,
    out_dir: Path,
    label: str,
    axial_mm_per_px: float,
) -> tuple[str, list[float]]:
    """Project a layer-thickness map onto the fundus plane.

    Both CSVs are shaped ``(n_bscans, n_ascans)`` with integer row
    positions of the upper/lower layer surfaces. Thickness per A-scan
    is ``(lower - upper) * axial_mm_per_px``. We render as a viridis
    heatmap clamped to a sensible 0–150 µm range for retinal layers.

    Returns ``(filename, mean_thickness_per_bscan_um)``.
    """
    import numpy as np

    def _read(path: Path) -> Any:
        rows = []
        with path.open() as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                rows.append([float(v) for v in line.split(",") if v.strip() != ""])
        if not rows:
            return np.zeros((0, 0), dtype=float)
        max_cols = max(len(r) for r in rows)
        arr = np.full((len(rows), max_cols), np.nan, dtype=float)
        for i, r in enumerate(rows):
            arr[i, :len(r)] = r
        return arr

    upper = _read(upper_csv_path)
    lower = _read(lower_csv_path)
    if upper.shape != lower.shape or upper.size == 0:
        # Write transparent placeholder + empty per-bscan list.
        rgba = np.zeros((1, 1, 4), dtype=np.uint8)
        filename = f"projection_{label}.png"
        _write_rgba_png(rgba, out_dir / filename)
        return filename, []

    thickness_um = (lower - upper) * float(axial_mm_per_px) * 1000.0
    # Negative or NaN → no data; clamp the visible range to [0, 150] µm
    # (retinal layers are typically 30–100 µm; cap at 150 for headroom).
    valid = np.isfinite(thickness_um) & (thickness_um >= 0)
    clamped = np.where(valid, np.clip(thickness_um, 0, 150), 0.0)
    norm = clamped / 150.0  # 0..1

    h, w = norm.shape
    rgba = np.zeros((h, w, 4), dtype=np.uint8)
    # Vectorised would be faster but the arrays are tiny (typ. 200×1024).
    for y in range(h):
        for x in range(w):
            if not valid[y, x]:
                continue
            r, g, b = _viridis_4stop(float(norm[y, x]))
            rgba[y, x] = (r, g, b, 200)

    filename = f"projection_{label}.png"
    _write_rgba_png(rgba, out_dir / filename)

    per_bscan_um = [
        float(np.nanmean(thickness_um[y, valid[y]])) if valid[y].any() else 0.0
        for y in range(h)
    ]
    return filename, per_bscan_um
