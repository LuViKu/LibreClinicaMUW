"""E2E binary → metadata + the shared ``bscan.dcm`` ingestion seam.

The OPTIMA/RetInsight model-runners all consume a Spectralis DICOM volume
(``<dir>/bscan.dcm`` with ``Manufacturer='Heidelberg Engineering'``,
``PixelSpacing`` + ``SpacingBetweenSlices``, ``NumberOfFrames``). The
``OptimaAdapter`` calls ``prepare_bscan_dcm`` once per job to turn the uploaded
Heidelberg ``.e2e`` into that DICOM, reading the volume + geometry with
``oct-converter`` and writing a multi-frame DICOM with ``pydicom``.

Spacing is derived from the E2E B-scan header geometry (``scaley`` for the
axial axis, the on-fundus B-scan endpoints for the lateral axis, and the
B-scan centre spread for the slice axis) rather than from oct-converter's
``pixel_spacing``, which disagreed with the header on real exports.
"""

from __future__ import annotations

import math
import os
from dataclasses import dataclass
from pathlib import Path
from typing import Any


# 2026-06-19 — Heidelberg posX/posY/centrePosY in the E2E bscan_data
# are **degrees of field-of-view**, NOT millimetres. The previous code
# treated them as mm directly, so a 20° × 20° macula scan was reported
# as 20 × 20 mm (3.4× the real ~6 × 6 mm physical extent) — bbox bbox
# was self-consistent (both source and conversion used the same wrong
# unit), but the ETDRS rings rendered at one-third of clinical size.
# Spectralis' canonical factor is 0.288 mm per degree (24.46 mm
# emmetropic eye axial length); operators with biometry data can
# override via the env var.
_HEIDELBERG_MM_PER_DEGREE_ENV = "RETINAL_INFERENCE_HEIDELBERG_MM_PER_DEGREE"
_HEIDELBERG_MM_PER_DEGREE_DEFAULT = 0.288


def _mm_per_degree() -> float:
    raw = os.environ.get(_HEIDELBERG_MM_PER_DEGREE_ENV)
    if raw:
        try:
            return float(raw)
        except ValueError:
            pass
    return _HEIDELBERG_MM_PER_DEGREE_DEFAULT


def heidelberg_pos_to_mm(pos_deg: float) -> float:
    """Convert a Heidelberg E2E posX/posY/centrePosY (degrees of FOV) to mm."""
    return float(pos_deg) * _mm_per_degree()


@dataclass(frozen=True)
class E2EMetadata:
    n_bscans: int
    bscan_width_px: int
    bscan_height_px: int
    pixel_scale_mm: float


def parse_e2e_metadata(e2e_path: Path) -> E2EMetadata:
    """Return placeholder metadata for an E2E file.

    Real implementation will use ``oct_converter.readers.E2E`` to read the
    Heidelberg header. For the placeholder period we return constants that
    match the MUW 41-eye / 97-B-scan cohort.
    """
    # Touch the file so callers get a FileNotFoundError early if the path is wrong.
    if not Path(e2e_path).is_file():
        raise FileNotFoundError(f"E2E file not found: {e2e_path}")
    return E2EMetadata(
        n_bscans=97,
        bscan_width_px=512,
        bscan_height_px=496,
        pixel_scale_mm=0.011,
    )


@dataclass(frozen=True)
class BscanVolume:
    """Decoded OCT volume + geometry, ready to write as a DICOM."""

    volume_u8: Any  # np.ndarray (n_bscans, rows, cols), uint8
    axial_mm: float  # row spacing (depth)
    lateral_mm: float  # column spacing
    slice_mm: float  # spacing between B-scans
    laterality: str  # 'OD' | 'OS'
    n_bscans: int
    rows: int
    cols: int


def _derive_spacing_mm(bscan_data: list[dict], n_bscans: int) -> tuple[float, float, float]:
    """Authoritative spacing (axial, lateral, slice) in mm from E2E geometry.

    ``scaley`` is already in mm/px. ``posX/posY/centrePosY`` are in
    DEGREES of field-of-view — converted to mm via
    :func:`heidelberg_pos_to_mm` before any distance math.
    """
    import numpy as np

    axial = float(bscan_data[0]["scaley"])  # depth mm/px (Spectralis ~0.00387)
    mm_per_deg = _mm_per_degree()

    lengths = []
    centres_y_mm = []
    for b in bscan_data:
        cols = float(b.get("imgSizeX") or b.get("imgSizeWidth") or 0)
        if cols >= 2 and all(k in b for k in ("posX1", "posY1", "posX2", "posY2")):
            # posX/posY are in degrees — convert before computing the
            # Euclidean distance so `lateral` lands in mm/A-scan-pixel.
            dx_mm = (float(b["posX2"]) - float(b["posX1"])) * mm_per_deg
            dy_mm = (float(b["posY2"]) - float(b["posY1"])) * mm_per_deg
            length_mm = math.hypot(dx_mm, dy_mm)
            lengths.append(length_mm / cols)
        if "centrePosY" in b:
            centres_y_mm.append(float(b["centrePosY"]) * mm_per_deg)

    lateral = float(np.median(lengths)) if lengths else axial
    if len(centres_y_mm) >= 2 and n_bscans > 1:
        slice_mm = (max(centres_y_mm) - min(centres_y_mm)) / (n_bscans - 1)
    else:
        slice_mm = lateral
    # Guard against degenerate single-B-scan / parsing gaps.
    slice_mm = slice_mm if slice_mm > 1e-6 else lateral
    return axial, lateral, slice_mm


def read_e2e_volume(e2e_path: Path, scan_index: int = 0) -> BscanVolume:
    """Decode the OCT volume + geometry from a Heidelberg ``.e2e``.

    Requires ``oct-converter`` + ``numpy`` (sidecar runtime deps). Picks the
    OCT series at ``scan_index`` (multi-acquisition .e2e files carry several
    volumes; the SPA portal lets the user pick which one to ingest).
    Raises ``IndexError`` when out of range so callers can map to HTTP 400.
    """
    import numpy as np
    from oct_converter.readers import E2E

    volumes = E2E(str(e2e_path)).read_oct_volume()
    if not volumes:
        raise ValueError(f"No OCT volume found in {e2e_path}")
    if scan_index < 0 or scan_index >= len(volumes):
        raise IndexError(
            f"scan_index {scan_index} out of range; file has {len(volumes)} volumes"
        )
    vol_meta = volumes[scan_index]

    arr = np.asarray(vol_meta.volume, dtype=np.float64)  # (n, rows, cols), 0..1
    # 2026-06-19 — drop the oct-converter-side volume reference so the
    # E2E parser's internal copy isn't pinned alongside `arr` during
    # quantisation. oct-converter holds the same ndarray on
    # `vol_meta.volume` until vol_meta is garbage-collected; clearing
    # the attribute frees ~192 MB of float64 immediately.
    try:
        vol_meta.volume = None  # type: ignore[assignment]
    except Exception:  # pylint: disable=broad-except
        pass
    # oct-converter normalises to [0, 1]; map to 8-bit. The models min/max- and
    # per-image-normalise downstream, so absolute scaling is not critical.
    finite_max = float(np.nanmax(arr)) if arr.size else 1.0
    scale = 255.0 / finite_max if finite_max > 0 else 255.0
    # 2026-06-19 — in-place float64 ops. The original chain
    # `np.clip(np.nan_to_num(arr) * scale, 0, 255).astype(np.uint8)`
    # allocates THREE transient float64 copies of the volume (one per
    # operation), each ~192 MB for a typical 97×496×512 scan, peaking
    # at ~770 MB of float64 heap before the uint8 cast. The in-place
    # path keeps only the source `arr` and the final uint8 copy
    # (~240 MB combined), then drops `arr` before downstream
    # allocates.
    np.nan_to_num(arr, copy=False)
    arr *= scale
    np.clip(arr, 0, 255, out=arr)
    vol_u8 = arr.astype(np.uint8)
    del arr

    n, rows, cols = vol_u8.shape
    bscan_data = (vol_meta.metadata or {}).get("bscan_data", []) if hasattr(vol_meta, "metadata") else []
    if bscan_data:
        axial, lateral, slice_mm = _derive_spacing_mm(bscan_data, n)
    else:  # fallback to oct-converter's (less reliable) spacing
        ps = list(getattr(vol_meta, "pixel_spacing", []) or [0.0039, 0.0039, 0.05])
        lateral, axial, slice_mm = (ps + [0.05, 0.05, 0.05])[:3]

    lat = str(getattr(vol_meta, "laterality", "") or "").upper()
    laterality = "OD" if lat.startswith("R") else "OS" if lat.startswith("L") else "OD"

    return BscanVolume(
        volume_u8=vol_u8,
        axial_mm=axial,
        lateral_mm=lateral,
        slice_mm=slice_mm,
        laterality=laterality,
        n_bscans=n,
        rows=rows,
        cols=cols,
    )


def write_bscan_dcm(bv: BscanVolume, out_dir: Path) -> Path:
    """Write ``bv`` as a multi-frame Spectralis-flavoured DICOM at out_dir/bscan.dcm."""
    import numpy as np  # noqa: F401  (bv.volume_u8 is already an ndarray)
    import pydicom
    from pydicom.dataset import Dataset, FileMetaDataset
    from pydicom.uid import ExplicitVRLittleEndian, generate_uid

    # Multi-frame Grayscale Byte Secondary Capture — SimpleITK/pydicom read it
    # back as a 3-D volume, and it carries every tag the runners inspect.
    sop_class = "1.2.840.10008.5.1.4.1.1.7.2"

    fm = FileMetaDataset()
    fm.MediaStorageSOPClassUID = sop_class
    fm.MediaStorageSOPInstanceUID = generate_uid()
    fm.TransferSyntaxUID = ExplicitVRLittleEndian

    ds = Dataset()
    ds.file_meta = fm
    ds.SOPClassUID = sop_class
    ds.SOPInstanceUID = fm.MediaStorageSOPInstanceUID
    # Generate Study + Series UIDs so a future DICOM SEG can reference this
    # bscan by its triple (Study, Series, SOP Instance) the way Supplement 111
    # expects. Cheap to add now; expensive to back-fill once a SEG is in flight.
    ds.StudyInstanceUID = generate_uid()
    ds.SeriesInstanceUID = generate_uid()
    ds.Modality = "OPT"  # Ophthalmic Tomography
    ds.Manufacturer = "Heidelberg Engineering"
    ds.ImageLaterality = "R" if bv.laterality == "OD" else "L"
    ds.Laterality = ds.ImageLaterality

    ds.SamplesPerPixel = 1
    ds.PhotometricInterpretation = "MONOCHROME2"
    ds.NumberOfFrames = str(bv.n_bscans)
    ds.Rows = bv.rows
    ds.Columns = bv.cols
    ds.BitsAllocated = 8
    ds.BitsStored = 8
    ds.HighBit = 7
    ds.PixelRepresentation = 0
    # PixelSpacing = [row spacing (axial), col spacing (lateral)]; slice spacing
    # in SpacingBetweenSlices — what the optima/retinsight code reads for mm³.
    ds.PixelSpacing = [round(bv.axial_mm, 8), round(bv.lateral_mm, 8)]
    ds.SpacingBetweenSlices = round(bv.slice_mm, 8)
    ds.PixelData = bv.volume_u8.tobytes()

    # PHI redaction (DR-022) — strip patient identifiers from the synthesised
    # bscan before write. E2E headers may carry PatientID/Name/DOB; we don't
    # propagate them into anything the sidecar ever returns.
    from retinal_inference.inference.phi import redact_dicom

    redact_dicom(ds)

    out_dir.mkdir(parents=True, exist_ok=True)
    dcm_path = out_dir / "bscan.dcm"
    pydicom.dcmwrite(str(dcm_path), ds, enforce_file_format=True)
    return out_dir


def prepare_bscan_dcm(e2e_path: Path, out_dir: Path, scan_index: int = 0) -> Path:
    """Convert a Heidelberg ``.e2e`` → ``out_dir/bscan.dcm`` and return ``out_dir``.

    The single shared ingestion seam the ``OptimaAdapter`` calls before
    dispatching to a model-runner. ``scan_index`` selects which volume from
    a multi-acquisition .e2e to ingest; defaults to 0 (first volume).
    Unit tests monkeypatch this function.
    """
    if not Path(e2e_path).is_file():
        raise FileNotFoundError(f"E2E file not found: {e2e_path}")
    bv = read_e2e_volume(Path(e2e_path), scan_index=scan_index)
    return write_bscan_dcm(bv, Path(out_dir))
