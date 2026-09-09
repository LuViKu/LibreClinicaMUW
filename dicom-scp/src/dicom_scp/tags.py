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
