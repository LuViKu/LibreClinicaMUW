"""Extract the identity/exam tags the reconciliation inbox + (later) auto-match
need, in the shape DicomIngestApiController.DicomIngestRequest expects."""
from __future__ import annotations

import hashlib

import numpy as np
from pydicom.dataset import Dataset

# DICOM Laterality (0020,0060) / Image Laterality (0020,0062) → ophthalmic OD/OS/OU.
# OD/OS/OU are accepted as written too: a third-party .e2e -> DICOM converter
# puts Laterality = "OD" (not the DICOM code R) and no ImageLaterality.
_LATERALITY = {"R": "OD", "L": "OS", "B": "OU", "OD": "OD", "OS": "OS", "OU": "OU"}


def _s(ds: Dataset, name: str) -> str | None:
    v = getattr(ds, name, None)
    if v is None:
        return None
    v = str(v).strip()
    return v or None


def study_date_iso(ds: Dataset) -> str | None:
    """DICOM DA (YYYYMMDD) → ISO yyyy-MM-dd; None if absent/malformed."""
    d = _s(ds, "StudyDate")
    if d and len(d) == 8 and d.isdigit():
        return f"{d[0:4]}-{d[4:6]}-{d[6:8]}"
    return None


def laterality(ds: Dataset) -> str | None:
    lat = _s(ds, "ImageLaterality") or _s(ds, "Laterality")
    return _LATERALITY.get(lat.upper()) if lat else None


def _da_iso(raw: str | None) -> str | None:
    if raw and len(raw) == 8 and raw.isdigit():
        return f"{raw[0:4]}-{raw[4:6]}-{raw[6:8]}"
    return None


def acquisition_date_iso(ds: Dataset) -> str | None:
    """When the picture was taken: AcquisitionDate, else ContentDate, else
    StudyDate — a file export keeps all three, a worklist-driven capture
    sometimes only the last."""
    for kw in ("AcquisitionDate", "ContentDate", "StudyDate"):
        iso = _da_iso(_s(ds, kw))
        if iso:
            return iso
    return None


def pixel_sha256(ds: Dataset) -> str | None:
    """SHA-256 of the decoded pixel array — the picture without its tags (DR-036).

    The app uses it to recognise a capture that comes back under another
    patient label: a re-export rewrites the tags, so the file's own digest
    moves while the picture does not. Decoded rather than the raw PixelData
    bytes, so a file re-encoded on the way (explicit VR, another transfer
    syntax) digests the same when its pixels are the same; the raw bytes are
    the fallback when no codec here can decode it. None without pixels.
    """
    if "PixelData" not in ds:
        return None
    h = hashlib.sha256()
    try:
        arr = np.ascontiguousarray(ds.pixel_array)
        h.update(f"{arr.dtype.str}:{arr.shape}:".encode("ascii"))
        h.update(arr.tobytes())
    except Exception:  # noqa: BLE001 — codec missing, malformed pixel data
        raw = ds.PixelData
        if not isinstance(raw, (bytes, bytearray)):
            return None
        h.update(b"raw:")
        h.update(bytes(raw))
    return h.hexdigest()


def extract(ds: Dataset, source_ae: str) -> dict:
    """Build the sidecar → app ingest payload (identity/exam tags only; the
    caller adds dicomPath + previewPngPath after persisting)."""
    return {
        "sopInstanceUid": _s(ds, "SOPInstanceUID"),
        "sopClassUid": _s(ds, "SOPClassUID"),
        "studyInstanceUid": _s(ds, "StudyInstanceUID"),
        "seriesInstanceUid": _s(ds, "SeriesInstanceUID"),
        "modality": _s(ds, "Modality"),
        "patientId": _s(ds, "PatientID"),
        "patientName": _s(ds, "PatientName"),
        "accessionNumber": _s(ds, "AccessionNumber"),
        "studyDate": study_date_iso(ds),
        "laterality": laterality(ds),
        "sourceAeTitle": source_ae or None,
        "pixelSha256": pixel_sha256(ds),
    }


# Ophthalmic Tomography Image Storage (PS3.4 B.5) — the OCT volume SOP class.
OPT_SOP_CLASS_UID = "1.2.840.10008.5.1.4.1.1.77.1.5.4"


def number_of_frames(ds: Dataset) -> int | None:
    """NumberOfFrames (0028,0008) as an int; None when absent or malformed."""
    try:
        raw = _s(ds, "NumberOfFrames")
        if raw is None:
            return None
        n = int(float(raw))
    except (TypeError, ValueError):  # a malformed IS fails on access
        return None
    return n if n >= 1 else None


def is_oct_volume(ds: Dataset) -> bool:
    """An OCT volume: the OPT SOP class or Modality OPT, with more than one frame.

    A single OPT frame (a line scan) is not a volume: the app's volume
    analyses need a stack of B-scans.
    """
    is_opt = _s(ds, "SOPClassUID") == OPT_SOP_CLASS_UID or (_s(ds, "Modality") or "").upper() == "OPT"
    n = number_of_frames(ds)
    return bool(is_opt and n is not None and n > 1)


def describe(ds: Dataset) -> dict:
    """What the describe endpoint (DR-029) answers for an uploaded file.

    Exam and device tags only. The patient tags are deliberately absent: the
    file has just been pseudonymised, and the app must never see what it
    carried before — the upload page's typed label is the identity it records.
    """
    file_meta = getattr(ds, "file_meta", None)
    transfer_syntax = str(getattr(file_meta, "TransferSyntaxUID", "") or "") if file_meta else ""
    return {
        "sopInstanceUid": _s(ds, "SOPInstanceUID"),
        "sopClassUid": _s(ds, "SOPClassUID"),
        "studyInstanceUid": _s(ds, "StudyInstanceUID"),
        "seriesInstanceUid": _s(ds, "SeriesInstanceUID"),
        "modality": _s(ds, "Modality"),
        "studyDate": study_date_iso(ds),
        "acquisitionDate": acquisition_date_iso(ds),
        "laterality": laterality(ds),
        "manufacturer": _s(ds, "Manufacturer"),
        "manufacturerModelName": _s(ds, "ManufacturerModelName"),
        "transferSyntaxUid": transfer_syntax or None,
        "rows": getattr(ds, "Rows", None),
        "columns": getattr(ds, "Columns", None),
        "numberOfFrames": number_of_frames(ds),
        "octVolume": is_oct_volume(ds),
        "pixelSha256": pixel_sha256(ds),
    }
