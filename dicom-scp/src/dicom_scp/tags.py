"""Extract the identity/exam tags the reconciliation inbox + (later) auto-match
need, in the shape DicomIngestApiController.DicomIngestRequest expects."""
from __future__ import annotations

from pydicom.dataset import Dataset

# DICOM Laterality (0020,0060) / Image Laterality (0020,0062) → ophthalmic OD/OS/OU.
_LATERALITY = {"R": "OD", "L": "OS", "B": "OU"}


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
    }


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
    }
