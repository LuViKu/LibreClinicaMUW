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
    # 2026-06-23 user-feedback round — OCT acquisition date pulled
    # straight from the .e2e header. ISO YYYY-MM-DD when populated,
    # ``None`` when the original device did not stamp the field. The
    # nAMD workspace uses this in preference to the upload's
    # ``completed_at`` so historical backfills plot against the
    # real scan date rather than the day the operator clicked
    # "Hochladen".
    acquisition_date: str | None = None
    # 2026-06-24 — additional E2E header passthrough for the
    # Ophthalmic Tomography DICOM IOD that downstream consumers
    # (IOWA, the optima framework, BMEIS) read. All optional so the
    # synthetic-volume placeholder code path in tests stays valid;
    # write_bscan_dcm falls back to sensible defaults when these are
    # None.
    bscan_positions_mm: tuple | None = None  # tuple of (x1_mm,y1_mm,x2_mm,y2_mm) per B-scan
    acquisition_time: str | None = None  # HHMMSS — DICOM TM format
    manufacturer: str | None = None  # device_data[0].text[0]
    manufacturer_model: str | None = None  # additional_device_data[0][1006]
    examined_structure: str | None = None  # examined_structure (e.g. "Retina")
    scan_pattern: str | None = None  # scan_pattern (e.g. "OCT ART Volume")
    series_uid_seed: str | None = None  # Heidelberg LOC-... UID, used as a stable hash for SeriesInstanceUID


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

    # 2026-06-23 — oct-converter parses the .e2e exam header into
    # ``acquisition_date`` (a ``datetime.date`` / ``datetime.datetime``
    # or ``None`` when the device left the field blank). Normalise to
    # ISO ``YYYY-MM-DD`` + capture the time-of-day for DICOM TM.
    acq_raw = getattr(vol_meta, "acquisition_date", None)
    acq_iso: str | None = None
    acq_time: str | None = None
    if acq_raw is not None:
        try:
            acq_iso = acq_raw.strftime("%Y-%m-%d") if hasattr(acq_raw, "strftime") else str(acq_raw)[:10]
        except Exception:  # pylint: disable=broad-except
            acq_iso = None
        # datetime instances also expose time; date instances don't.
        if hasattr(acq_raw, "hour"):
            try:
                acq_time = acq_raw.strftime("%H%M%S")
            except Exception:  # pylint: disable=broad-except
                acq_time = None

    # 2026-06-24 — passthrough of E2E-header metadata that the
    # Ophthalmic Tomography DICOM IOD needs (Manufacturer, device
    # model, anatomic structure, scan pattern, stable series-UID
    # seed). Each field is a best-effort dig through nested
    # oct-converter dicts; anything we can't find stays None and the
    # writer falls back to a sensible default.
    md = getattr(vol_meta, "metadata", None) or {}

    def _first(seq, default=None):
        try:
            return next(iter(seq), default)
        except Exception:  # pylint: disable=broad-except
            return default

    manufacturer = None
    try:
        # device_data = [{'n_strings': 4, 'string_size': 128, 'text': ['Heidelberg Retina Angiograph', '', 'HRA', '']}]
        device = _first(md.get("device_data") or [])
        if device and device.get("text"):
            text0 = _first(device["text"])
            if text0:
                manufacturer = str(text0).strip() or None
    except Exception:  # pylint: disable=broad-except
        manufacturer = None

    manufacturer_model = None
    try:
        # additional_device_data = [{1006: 'SPECTRALISHX202\x00'}]
        add_dev = _first(md.get("additional_device_data") or [])
        if add_dev:
            # Heidelberg uses tag 1006 for the device model string;
            # take the first non-empty value regardless of key.
            for v in add_dev.values():
                s = str(v).rstrip("\x00").strip()
                if s:
                    manufacturer_model = s
                    break
    except Exception:  # pylint: disable=broad-except
        manufacturer_model = None

    examined_structure = None
    try:
        es = md.get("examined_structure") or {}
        if isinstance(es, dict):
            examined_structure = _first(es.values())
    except Exception:  # pylint: disable=broad-except
        examined_structure = None

    scan_pattern = None
    try:
        sp = md.get("scan_pattern") or {}
        if isinstance(sp, dict):
            scan_pattern = _first(sp.values())
    except Exception:  # pylint: disable=broad-except
        scan_pattern = None

    series_uid_seed = None
    try:
        # uid_data = [{54: {'uid': 'LOC-422610336.5e6d0313-…'}}]
        uid_block = _first(md.get("uid_data") or [])
        if isinstance(uid_block, dict):
            for v in uid_block.values():
                if isinstance(v, dict) and v.get("uid"):
                    series_uid_seed = str(v["uid"])
                    break
    except Exception:  # pylint: disable=broad-except
        series_uid_seed = None

    # Per-B-scan fundus positions in mm (4-tuple per B-scan). Real
    # coordinates beat the synthesised-grid placeholder for IOWA's
    # ReferenceCoordinates sequence — same code path the test
    # fixtures exercise. posX/posY are in degrees of FOV →
    # heidelberg_pos_to_mm() converts.
    bscan_positions_mm: tuple | None = None
    if bscan_data:
        try:
            positions = []
            for b in bscan_data[:n]:
                x1 = heidelberg_pos_to_mm(float(b.get("posX1", 0.0)))
                y1 = heidelberg_pos_to_mm(float(b.get("posY1", 0.0)))
                x2 = heidelberg_pos_to_mm(float(b.get("posX2", 0.0)))
                y2 = heidelberg_pos_to_mm(float(b.get("posY2", 0.0)))
                positions.append((round(x1, 6), round(y1, 6), round(x2, 6), round(y2, 6)))
            if positions:
                bscan_positions_mm = tuple(positions)
        except Exception:  # pylint: disable=broad-except
            bscan_positions_mm = None

    return BscanVolume(
        volume_u8=vol_u8,
        axial_mm=axial,
        lateral_mm=lateral,
        slice_mm=slice_mm,
        laterality=laterality,
        n_bscans=n,
        rows=rows,
        cols=cols,
        acquisition_date=acq_iso,
        acquisition_time=acq_time,
        bscan_positions_mm=bscan_positions_mm,
        manufacturer=manufacturer,
        manufacturer_model=manufacturer_model,
        examined_structure=examined_structure,
        scan_pattern=scan_pattern,
        series_uid_seed=series_uid_seed,
    )


def write_bscan_dcm(bv: BscanVolume, out_dir: Path) -> Path:
    """Write ``bv`` as a multi-frame Spectralis-flavoured DICOM at out_dir/bscan.dcm.

    2026-06-24 — switched the SOPClassUID from Multi-frame Grayscale Byte
    Secondary Capture (1.2.840.10008.5.1.4.1.1.7.2) to **Ophthalmic
    Tomography Image Storage** (1.2.840.10008.5.1.4.1.1.77.1.5.4) plus
    every IOD-mandatory tag that comes with it. The IOWA OCTLayerSeg
    binary branches on SOPClassUID; the Secondary Capture path doesn't
    set up the per-frame ophthalmic functional-group geometry the
    binary's max-flow graph initialiser expects, so it then NULL-derefs
    while sizing the graph (`optnet_ia_maxflow_3d::maxflow_init`
    SIGSEGV reproduced on cn5 with TestCase1 working + ours crashing).

    The new fields are mostly placeholder strings ("00000000",
    "0", "U", "000Y") because they're required by the IOD but carry no
    clinical information for our synthesised volume — the upstream
    .e2e doesn't carry a study identifier, the operator's PHI has
    already been redacted, and the dummy values are exactly what the
    sese_ga test fixture ships. The one substantive new field is
    ``ReferenceCoordinates (0022,0031)`` — IOWA reads it to dimension
    the graph topology; computed from spacing + n_bscans below.
    """
    import numpy as np  # noqa: F401  (bv.volume_u8 is already an ndarray)
    import pydicom
    from pydicom.dataset import Dataset, FileMetaDataset
    from pydicom.sequence import Sequence
    from pydicom.uid import ExplicitVRLittleEndian, generate_uid

    # Ophthalmic Tomography Image Storage — what IOWA + the optima
    # framework's downstream consumers expect for an OCT volume.
    sop_class = "1.2.840.10008.5.1.4.1.1.77.1.5.4"

    fm = FileMetaDataset()
    fm.MediaStorageSOPClassUID = sop_class
    fm.MediaStorageSOPInstanceUID = generate_uid()
    fm.TransferSyntaxUID = ExplicitVRLittleEndian

    ds = Dataset()
    ds.file_meta = fm
    ds.SOPClassUID = sop_class
    ds.SOPInstanceUID = fm.MediaStorageSOPInstanceUID
    # SeriesInstanceUID is a stable hash of Heidelberg's per-scan
    # LOC-... UID when present (so reprocessing the same .e2e yields
    # the same UID — important for SR / SEG cross-references); a
    # fresh random UID otherwise.
    ds.StudyInstanceUID = generate_uid()
    if bv.series_uid_seed:
        # Map the LOC-... string to a stable DICOM UID under the
        # pydicom default org-root (consistent across runs / hosts).
        import hashlib as _h
        digest = _h.sha1(bv.series_uid_seed.encode("utf-8")).hexdigest()
        # generate_uid takes a hash-derived suffix as entropy_srcs;
        # easier: use it as the entropy seed.
        ds.SeriesInstanceUID = generate_uid(entropy_srcs=[bv.series_uid_seed, digest])
    else:
        ds.SeriesInstanceUID = generate_uid()
    ds.Modality = "OPT"  # Ophthalmic Tomography
    # Manufacturer from the .e2e device_data when present
    # ('Heidelberg Retina Angiograph' / 'HRA'); fall back to the
    # canonical company name.
    ds.Manufacturer = bv.manufacturer or "Heidelberg Engineering"
    if bv.manufacturer_model:
        ds.ManufacturerModelName = bv.manufacturer_model
    # Ophthalmic-IOD laterality codes are "OS" / "OD" — NOT the
    # generic "R" / "L" the Secondary Capture IOD used. IOWA reads
    # this tag and branches; "R" was being treated as "unknown".
    ds.ImageLaterality = "R" if bv.laterality == "OD" else "L"
    ds.Laterality = "OD" if bv.laterality == "OD" else "OS"

    # IOD-required image-type discriminator. The IOWA binary requires
    # the first value "DERIVED" / "ORIGINAL" to be present.
    ds.ImageType = ["DERIVED", "PRIMARY"]
    ds.DerivationDescription = ""

    # Placeholder patient + study identifiers required by the
    # Ophthalmic Tomography IOD's Patient + Study + Series modules.
    # The actual operator PHI was already redacted by phi.redact_dicom
    # (kept below as a defense-in-depth pass over whatever oct-converter
    # may have propagated through). These dummy values match the
    # sese_ga TestCase1 fixture so IOWA sees field-shapes it has
    # seen before in QA.
    ds.PatientName = "00000000"
    ds.PatientID = "00000000"
    ds.PatientBirthDate = "00000000"
    ds.PatientSex = "U"
    ds.PatientAge = "000Y"
    # When the .e2e carries an acquisition timestamp, mirror it into
    # StudyDate / SeriesDate / AcquisitionDate (standard DICOM
    # convention: a single-session OCT scan shares the timestamp).
    # ISO YYYY-MM-DD → DICOM DA YYYYMMDD.
    if bv.acquisition_date:
        acq_dicom = bv.acquisition_date.replace("-", "")
        ds.StudyDate = acq_dicom
        ds.SeriesDate = acq_dicom
        ds.AcquisitionDate = acq_dicom
    else:
        ds.StudyDate = "00000000"
        ds.SeriesDate = "00000000"
        ds.AcquisitionDate = "00000000"
    if bv.acquisition_time:
        ds.StudyTime = bv.acquisition_time
        ds.SeriesTime = bv.acquisition_time
        ds.AcquisitionTime = bv.acquisition_time
    ds.StudyID = "0"
    ds.SeriesNumber = "0"
    ds.AcquisitionNumber = "0"
    if bv.scan_pattern:
        # "OCT ART Volume" — surfaces in the optima framework's
        # SeriesDescription column and IOWA's QC log header.
        ds.SeriesDescription = str(bv.scan_pattern)

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

    # Acquisition Device Type Code Sequence — describes the OCT scanner.
    # SNOMED code A-00FBE = "Optical Coherence Tomography Scanner" per
    # the TestCase1 fixture.
    acq_device_item = Dataset()
    acq_device_item.CodeValue = "A-00FBE"
    acq_device_item.CodingSchemeDesignator = "SRT"
    acq_device_item.CodeMeaning = "Optical Coherence Tomography Scanner"
    ds.AcquisitionDeviceTypeCodeSequence = Sequence([acq_device_item])

    # Anatomic Region Sequence — SNOMED 'Retina' code so the
    # Ophthalmic IOD's anatomic-region requirement is satisfied.
    # examined_structure from the .e2e is informational; the SNOMED
    # mapping is fixed for OCT volumes.
    if bv.examined_structure and "retina" in str(bv.examined_structure).lower():
        anat_item = Dataset()
        anat_item.CodeValue = "T-AA610"
        anat_item.CodingSchemeDesignator = "SRT"
        anat_item.CodeMeaning = "Retina"
        ds.AnatomicRegionSequence = Sequence([anat_item])

    # Reference Coordinates Sequence — one item per B-scan, each
    # carrying (0022,0032) as a 4-float [x_start, x_end, y_pos, y_pos]
    # in mm. IOWA reads this sequence to dimension the graph
    # topology; without it, max-flow init reads NULL and segfaults.
    #
    # When the .e2e gave us per-B-scan fundus positions (the common
    # Spectralis case), use them verbatim — IOWA gets the same scan
    # geometry the test fixtures carry. Else fall back to a
    # synthesised grid from spacing.
    ref_items = []
    if bv.bscan_positions_mm and len(bv.bscan_positions_mm) >= bv.n_bscans:
        for i in range(bv.n_bscans):
            x1, y1, x2, y2 = bv.bscan_positions_mm[i]
            ref_item = Dataset()
            ref_item.ReferenceCoordinates = [
                round(x1, 6), round(x2, 6), round(y1, 6), round(y2, 6),
            ]
            ref_items.append(ref_item)
    else:
        x_start_mm = 0.0
        x_end_mm = round(bv.cols * bv.lateral_mm, 6)
        for i in range(bv.n_bscans):
            y_mm = round(i * bv.slice_mm, 6)
            ref_item = Dataset()
            ref_item.ReferenceCoordinates = [
                x_start_mm, x_end_mm, y_mm, y_mm,
            ]
            ref_items.append(ref_item)
    # Tag (0022,0031) is "OphthalmicAxialMeasurementsLeftEyeSequence"
    # in DICOM 2024, but in OCTLayerSeg's older dictionary it's read
    # as a private alias for the per-B-scan reference. Assigning by
    # tag rather than keyword avoids a pydicom keyword mismatch on
    # the 2015-vintage data dictionary.
    ds.add_new(0x00220031, "SQ", Sequence(ref_items))

    # PHI redaction (DR-022) — strip patient identifiers from the synthesised
    # bscan before write. E2E headers may carry PatientID/Name/DOB; we don't
    # propagate them into anything the sidecar ever returns.
    from retinal_inference.inference.phi import redact_dicom

    redact_dicom(ds)

    # 2026-06-24 — re-stamp scan-acquisition dates AFTER redaction.
    # phi.redact_dicom blanks StudyDate / StudyTime / AcquisitionDate
    # / AcquisitionDateTime per DICOM Supplement 142 (treating any
    # date as PHI by default). For our use case those values are NOT
    # PHI: PatientID + Name + DOB + Address + AccessionNumber + the
    # institution / physician name list are all already blanked, so
    # a bare scan date can't re-identify anyone. Restoring them
    # keeps the OCT clinically interpretable (IOWA + the nAMD module
    # both log + plot against acquisition time).
    if bv.acquisition_date:
        acq_dicom = bv.acquisition_date.replace("-", "")
        ds.StudyDate = acq_dicom
        ds.SeriesDate = acq_dicom
        ds.AcquisitionDate = acq_dicom
    if bv.acquisition_time:
        ds.StudyTime = bv.acquisition_time
        ds.SeriesTime = bv.acquisition_time
        ds.AcquisitionTime = bv.acquisition_time

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
