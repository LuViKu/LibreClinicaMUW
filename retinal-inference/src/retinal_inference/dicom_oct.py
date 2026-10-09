"""DICOM OCT volume -> normalised, de-identified ``bscan.dcm`` (app-VM preprocess).

The ``.e2e`` path turns a Heidelberg export into the ``bscan.dcm`` the cluster
expects with ``muw_e2e_converter.write_bscan_dcm``. A vendor DICOM OCT volume
(Ophthalmic Tomography, a multi-frame enhanced object) goes through the SAME
writer: this module validates the file, reads its geometry and pixels into a
``BscanVolume`` and hands that over. One writer for both sources means the
cluster always gets one shape: top-level PixelSpacing / SpacingBetweenSlices,
NumberOfFrames, 16-bit MONOCHROME2, Ophthalmic Tomography SOP class, and the
(0022,0031) per-B-scan ReferenceCoordinates IOWA needs.

De-identification is structural: ``write_bscan_dcm`` builds a fresh dataset
and copies over only what this module passes in (pixels, spacing, laterality,
acquisition date/time, Manufacturer + model name, and a hash of the series
UID). Nothing else from the source file can reach the output: no patient,
institution, physician, description or private element, and no source UID.
``assert_deidentified`` then re-reads the written file and fails closed if
any planted identifier or a private tag is found anyway.

Limitations (documented, deliberate):

* No fundus / SLO. A DICOM OPT object references its SLO as a separate SOP
  instance; it is not read here, so ``geometry.json`` has no fundus block and
  no B-scan positions on a fundus.
* The (0022,0031) ReferenceCoordinates written are the writer's synthesised
  grid (x 0..cols*lateral mm, y = i*slice mm). The source's own Ophthalmic
  Frame Location coordinates are in pixels of the (absent) SLO, not mm, so
  they cannot be carried over.
* Frames are taken in stored order (the order of the frames in Pixel Data).
"""

from __future__ import annotations

import datetime as _dt
import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from retinal_inference.dicom_geometry import (
    SpacingAmbiguous,
    SpacingUnavailable,
    frame_count,
    pixel_spacing_mm,
    plausibility,
    slice_spacing_mm,
)

OPT_SOP_CLASS_UID = "1.2.840.10008.5.1.4.1.1.77.1.5.4"

# DICOM codes R / L / B, plus the ophthalmic OD / OS / OU that some converters
# write into Laterality (0020,0060) instead (seen on a third-party
# e2e -> DICOM conversion: Laterality = "OD", no ImageLaterality).
_LATERALITY = {"R": "OD", "L": "OS", "B": "OU", "OD": "OD", "OS": "OS", "OU": "OU"}

# Source attributes whose values must never appear in the output. "Strong"
# identifiers are searched as substrings of every text value; the free-text and
# site attributes only as whole values (a description such as "Macula" would
# otherwise collide with the writer's own coded meanings).
_STRONG_IDENTIFIERS = (
    "PatientName",
    "PatientID",
    "PatientBirthName",
    "PatientMotherBirthName",
    "PatientAddress",
    "PatientTelephoneNumbers",
    "OtherPatientIDs",
    "OtherPatientNames",
    "MedicalRecordLocator",
    "AccessionNumber",
    "DeviceSerialNumber",
    "StudyInstanceUID",
    "SeriesInstanceUID",
    "SOPInstanceUID",
    "FrameOfReferenceUID",
)
_WEAK_IDENTIFIERS = (
    "IssuerOfPatientID",
    "InstitutionName",
    "InstitutionAddress",
    "InstitutionalDepartmentName",
    "ReferringPhysicianName",
    "PerformingPhysicianName",
    "NameOfPhysiciansReadingStudy",
    "OperatorsName",
    "RequestingPhysician",
    "StudyDescription",
    "SeriesDescription",
    "PerformedProcedureStepDescription",
    "RequestedProcedureDescription",
    "ImageComments",
    "PatientComments",
    "StudyID",
    "StationName",
)
# Value representations scanned in the output (dates, times and numbers are
# not: a birth date is blanked by name, and digits collide with real values).
_TEXT_VRS = frozenset({"PN", "LO", "SH", "LT", "ST", "UT", "UC", "CS", "AE"})

# Output attributes that must be empty after writing.
_MUST_BE_BLANK = ("PatientName", "PatientID", "PatientBirthDate", "OtherPatientIDs",
                  "OtherPatientNames", "AccessionNumber", "InstitutionName",
                  "ReferringPhysicianName", "PerformingPhysicianName", "OperatorsName")


class DicomOctRejected(ValueError):
    """The upload is not a DICOM OCT volume this sidecar will normalise (HTTP 422)."""

    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code
        self.message = message


@dataclass
class DicomOctResult:
    volume: Any  # muw_e2e_converter.BscanVolume
    manufacturer: str
    model: str | None
    laterality_source: str
    spacing_sources: dict[str, str] = field(default_factory=dict)
    spacing_order: str = "standard"  # standard | swapped | standard-assumed
    plausibility: dict = field(default_factory=dict)
    # (value, strong) pairs that assert_deidentified must not find in the output.
    forbidden_values: tuple[tuple[str, bool], ...] = ()


def is_dicom_part10(head: bytes) -> bool:
    """DICOM Part-10: a 128-byte preamble followed by ``DICM``. The name is ignored."""
    return len(head) >= 132 and head[128:132] == b"DICM"


def _clean(value: Any, limit: int = 64) -> str:
    """A device string safe for a DICOM LO and an HTTP header (printable ASCII)."""
    s = re.sub(r"[^\x20-\x7e]", "", str(value or "")).strip()
    return s[:limit]


def _first(ds: Any, keyword: str) -> Any | None:
    seq = getattr(ds, keyword, None)
    try:
        return seq[0] if seq is not None and len(seq) > 0 else None
    except (TypeError, IndexError):
        return None


def _str(ds: Any, keyword: str) -> str:
    try:
        return str(getattr(ds, keyword, "") or "").strip()
    except (TypeError, ValueError):
        return ""


# --- laterality ----------------------------------------------------------------


def _dicom_lateralities(ds: Any) -> set[str]:
    found: set[str] = set()
    for kw in ("ImageLaterality", "Laterality"):
        v = _str(ds, kw).upper()
        if v in _LATERALITY:
            found.add(_LATERALITY[v])
    groups = []
    shared = _first(ds, "SharedFunctionalGroupsSequence")
    if shared is not None:
        groups.append(shared)
    per_frame = getattr(ds, "PerFrameFunctionalGroupsSequence", None)
    if per_frame is not None:
        groups.extend(list(per_frame))
    for g in groups:
        fa = _first(g, "FrameAnatomySequence")
        v = _str(fa, "FrameLaterality").upper() if fa is not None else ""
        if v in _LATERALITY:
            found.add(_LATERALITY[v])
    return found


def resolve_laterality(ds: Any, form: str | None) -> tuple[str, str]:
    """(OD|OS, source). OU / unknown needs the form value; a conflict is refused."""
    found = _dicom_lateralities(ds)
    eyes = found & {"OD", "OS"}
    if len(eyes) > 1:
        raise DicomOctRejected(
            "laterality_conflict",
            "The DICOM names both eyes (OD and OS) in its laterality attributes.",
        )
    if eyes:
        eye = next(iter(eyes))
        if form and form != eye:
            raise DicomOctRejected(
                "laterality_conflict",
                f"The form says {form} but the DICOM says {eye}.",
            )
        return eye, "dicom"
    if form in ("OD", "OS"):
        return form, "form"
    what = "both eyes (OU)" if "OU" in found else "no eye"
    raise DicomOctRejected(
        "laterality_missing",
        f"The DICOM names {what}; send laterality=OD or OS with the upload.",
    )


# --- acquisition date ------------------------------------------------------------


def _iso_date(raw: str) -> str | None:
    digits = raw[:8]
    if len(digits) != 8 or not digits.isdigit():
        return None
    try:
        return _dt.datetime.strptime(digits, "%Y%m%d").strftime("%Y-%m-%d")
    except ValueError:
        return None


def _hhmmss(raw: str) -> str | None:
    m = re.match(r"(\d{2})(\d{2})?(\d{2})?", raw or "")
    if not m:
        return None
    hh, mm, ss = m.group(1), m.group(2) or "00", m.group(3) or "00"
    if int(hh) > 23 or int(mm) > 59 or int(ss) > 60:
        return None
    return f"{hh}{mm}{ss}"


def acquisition_date_time(ds: Any) -> tuple[str | None, str | None]:
    """(ISO date, HHMMSS) — AcquisitionDateTime, FrameAcquisitionDateTime,
    AcquisitionDate, ContentDate, StudyDate, in that order."""
    candidates: list[str] = []
    candidates.append(_str(ds, "AcquisitionDateTime"))
    for g in (_first(ds, "SharedFunctionalGroupsSequence"),
              _first(ds, "PerFrameFunctionalGroupsSequence")):
        fc = _first(g, "FrameContentSequence") if g is not None else None
        if fc is not None:
            candidates.append(_str(fc, "FrameAcquisitionDateTime"))
    for dt in candidates:
        iso = _iso_date(dt)
        if iso:
            return iso, _hhmmss(dt[8:])
    for date_kw, time_kw in (("AcquisitionDate", "AcquisitionTime"),
                             ("ContentDate", "ContentTime"),
                             ("StudyDate", "StudyTime")):
        iso = _iso_date(_str(ds, date_kw))
        if iso:
            return iso, _hhmmss(_str(ds, time_kw))
    return None, None


# --- pixels ------------------------------------------------------------------------


def _pixels_u16(ds: Any, n: int) -> Any:
    """The volume as uint16 (n, rows, cols), scaled like the .e2e path (max -> 65535)."""
    import numpy as np

    ts = getattr(getattr(ds, "file_meta", None), "TransferSyntaxUID", None)
    if ts is not None and getattr(ts, "is_compressed", False):
        try:
            from pydicom.pixels import get_decoder

            available = get_decoder(ts).is_available
        except (NotImplementedError, ValueError, ImportError):
            available = False
        if not available:
            raise DicomOctRejected(
                "unsupported_transfer_syntax",
                f"The pixel data is compressed as {getattr(ts, 'name', ts)} ({ts}); "
                "no decoder for it is installed in this deployment. Export the "
                "volume uncompressed (or as JPEG baseline / JPEG 2000 / RLE).",
            )
    try:
        arr = ds.pixel_array
    except Exception as e:  # noqa: BLE001 — any codec / malformed-pixel failure
        raise DicomOctRejected(
            "pixel_decode_failed",
            f"The pixel data cannot be decoded ({type(e).__name__}).",
        ) from e
    rows, cols = int(ds.Rows), int(ds.Columns)
    if arr.ndim != 3 or arr.shape != (n, rows, cols):
        raise DicomOctRejected(
            "pixel_decode_failed",
            f"Decoded pixel data has shape {tuple(arr.shape)}, expected ({n}, {rows}, {cols}).",
        )
    vol = arr.astype(np.float32)
    del arr
    lo = float(vol.min()) if vol.size else 0.0
    if lo < 0:  # signed pixel representation
        vol -= lo
    if _str(ds, "PhotometricInterpretation").upper() == "MONOCHROME1":
        vol = float(vol.max()) - vol  # MONOCHROME1: low = white; output is MONOCHROME2
    finite_max = float(np.nanmax(vol)) if vol.size else 0.0
    scale = 65535.0 / finite_max if finite_max > 0 else 1.0
    np.nan_to_num(vol, copy=False)
    vol *= scale
    np.clip(vol, 0, 65535, out=vol)
    return vol.astype(np.uint16)


# --- the whole read ------------------------------------------------------------------


def read_dicom_oct(path: Path, form_laterality: str | None) -> DicomOctResult:
    """Validate ``path`` as a DICOM OCT volume and read it into a ``BscanVolume``.

    Raises :class:`DicomOctRejected` (-> 422) for anything this sidecar will
    not turn into a cluster input.
    """
    import pydicom
    from muw_e2e_converter import BscanVolume
    from pydicom.errors import InvalidDicomError

    try:
        ds = pydicom.dcmread(str(path))
    except (InvalidDicomError, ValueError, TypeError, EOFError, OSError, KeyError) as e:
        raise DicomOctRejected(
            "invalid_dicom", f"The upload cannot be read as DICOM ({type(e).__name__})."
        ) from e

    sop = _str(ds, "SOPClassUID")
    modality = _str(ds, "Modality").upper()
    if sop != OPT_SOP_CLASS_UID and modality != "OPT":
        raise DicomOctRejected(
            "not_oct_volume",
            f"Not an OCT volume: SOP Class {sop or '(none)'}, Modality {modality or '(none)'}; "
            "expected Ophthalmic Tomography Image Storage "
            f"({OPT_SOP_CLASS_UID}) or Modality OPT.",
        )
    try:
        n = frame_count(ds)
    except (TypeError, ValueError) as e:
        raise DicomOctRejected("invalid_dicom", "NumberOfFrames is malformed.") from e
    if n <= 1:
        raise DicomOctRejected(
            "not_oct_volume",
            f"The OCT object has {n} frame; a volume needs more than one B-scan.",
        )
    photometric = _str(ds, "PhotometricInterpretation").upper()
    samples = int(getattr(ds, "SamplesPerPixel", 1) or 1)
    if samples != 1 or photometric not in ("MONOCHROME1", "MONOCHROME2"):
        raise DicomOctRejected(
            "not_monochrome",
            f"OCT B-scans must be monochrome (got {photometric or '?'}, "
            f"{samples} samples per pixel).",
        )
    if _str(ds, "BurnedInAnnotation").upper() == "YES":
        raise DicomOctRejected(
            "burned_in_annotation",
            "The image carries burned-in annotation (BurnedInAnnotation = YES), which "
            "may show patient identifiers in the pixels; it cannot be de-identified.",
        )
    manufacturer = _clean(_str(ds, "Manufacturer"))
    if not manufacturer:
        raise DicomOctRejected(
            "missing_manufacturer",
            "The DICOM has no Manufacturer; the device cannot be checked against "
            "the devices the analyses were validated for.",
        )
    model = _clean(_str(ds, "ManufacturerModelName")) or None

    laterality, lat_source = resolve_laterality(ds, form_laterality)

    try:
        axial, lateral, px_src, order = pixel_spacing_mm(ds)
        slice_mm, sl_src = slice_spacing_mm(ds, n)
    except SpacingAmbiguous as e:
        raise DicomOctRejected(
            "spacing_ambiguous",
            f"Which PixelSpacing value is axial cannot be decided: {e}. Every "
            "thickness depends on it, so it is not guessed.",
        ) from e
    except SpacingUnavailable as e:
        raise DicomOctRejected(
            "spacing_unavailable",
            f"The voxel spacing cannot be determined: {e}. Every metric in mm depends "
            "on it, so it is not guessed.",
        ) from e

    acq_date, acq_time = acquisition_date_time(ds)

    device_strings = {manufacturer.lower(), (model or "").lower()}
    forbidden: list[tuple[str, bool]] = []
    for kws, strong in ((_STRONG_IDENTIFIERS, True), (_WEAK_IDENTIFIERS, False)):
        for kw in kws:
            v = _str(ds, kw)
            if len(v) >= 3 and v.lower() not in device_strings:
                forbidden.append((v, strong))
    meta_uid = _str(getattr(ds, "file_meta", None), "MediaStorageSOPInstanceUID")
    if meta_uid:
        forbidden.append((meta_uid, True))
    series_uid = _str(ds, "SeriesInstanceUID") or None

    vol = _pixels_u16(ds, n)
    rows, cols = int(ds.Rows), int(ds.Columns)
    del ds

    bv = BscanVolume(
        volume_u8=vol,
        axial_mm=axial,
        lateral_mm=lateral,
        slice_mm=slice_mm,
        laterality=laterality,
        n_bscans=n,
        rows=rows,
        cols=cols,
        acquisition_date=acq_date,
        acquisition_time=acq_time,
        bscan_positions_mm=None,  # -> writer synthesises the mm grid (see module doc)
        manufacturer=manufacturer,
        manufacturer_model=model,
        examined_structure=None,
        scan_pattern=None,  # SeriesDescription is free text: never copied
        series_uid_seed=series_uid,  # hashed into a new UID by the writer
    )
    return DicomOctResult(
        volume=bv,
        manufacturer=manufacturer,
        model=model,
        laterality_source=lat_source,
        spacing_sources={"pixel": px_src, "slice": sl_src},
        spacing_order=order,
        plausibility=plausibility(
            rows=rows, cols=cols, n_frames=n, axial=axial, lateral=lateral,
            slice_mm=slice_mm, manufacturer=manufacturer,
        ),
        forbidden_values=tuple(dict.fromkeys(forbidden)),
    )


def assert_deidentified(
    dcm_path: Path, forbidden_values: tuple[tuple[str, bool], ...]
) -> None:
    """Fail closed (RuntimeError) if the written file still identifies anyone.

    Checks: no private element anywhere; the patient / institution attributes
    are blank and PatientIdentityRemoved is YES; no UID equals a source UID;
    no text value contains a strong source identifier or equals any other
    identifying source value. The error never quotes the offending value.
    """
    import pydicom

    ds = pydicom.dcmread(str(dcm_path), stop_before_pixels=True)
    for elem in ds.iterall():
        if elem.tag.is_private:
            raise RuntimeError(f"de-identification check: private element {elem.tag} present")
    for kw in _MUST_BE_BLANK:
        if _str(ds, kw):
            raise RuntimeError(f"de-identification check: {kw} is not blank")
    if str(getattr(ds, "PatientIdentityRemoved", "")).upper() != "YES":
        raise RuntimeError("de-identification check: PatientIdentityRemoved is not YES")

    strong = [v.lower() for v, is_strong in forbidden_values if is_strong]
    every = {v.lower() for v, _ in forbidden_values}
    meta = getattr(ds, "file_meta", None)
    for src in [ds] + ([meta] if meta is not None else []):
        for elem in src.iterall():
            if elem.VR != "UI" and elem.VR not in _TEXT_VRS:
                continue
            values = elem.value if isinstance(elem.value, (list, tuple)) else [elem.value]
            for v in values:
                text = str(v or "").strip().lower()
                if not text:
                    continue
                hit = text in every or (
                    elem.VR != "UI" and any(n in text for n in strong)
                )
                if hit:
                    raise RuntimeError(
                        f"de-identification check: element {elem.tag} carries a "
                        "source identifier"
                    )


def geometry_for_dicom(result: DicomOctResult) -> dict:
    """``geometry.json`` for a DICOM source: the ``bscan`` block is filled; the
    fundus-registration fields are null (or empty) because no SLO was read."""
    bv = result.volume
    return {
        "scan_index": 0,
        "source_format": "dicom",
        "fundus": None,
        "bscan": {
            "dim_x_ascans": int(bv.cols),
            "dim_y_rows": int(bv.rows),
            "dim_z_bscans": int(bv.n_bscans),
            "pixel_axial_mm": float(bv.axial_mm),
            "pixel_lateral_mm": float(bv.lateral_mm),
            "pixel_slice_mm": float(bv.slice_mm),
        },
        "bscan_positions_fundus_px": [],
        "scan_bbox_fundus_px": None,
        "fovea_estimate_fundus_px": None,
        "spacing_source": dict(result.spacing_sources),
        # How the stored PixelSpacing pair was read: standard ([axial,
        # lateral]), swapped ([lateral, axial], resolved physically) or
        # standard-assumed (non-Heidelberg, DICOM order taken).
        "spacing_order": result.spacing_order,
        "plausibility": dict(result.plausibility),
        "laterality_source": result.laterality_source,
        "device": {"manufacturer": result.manufacturer, "model": result.model},
    }
