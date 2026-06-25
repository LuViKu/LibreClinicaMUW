"""PHI redaction helper for the bscan DICOM the sidecar synthesises.

The bscan we write inside ``write_bscan_dcm`` is derived from a Heidelberg E2E
that carries patient identifiers in its header. We deliberately strip them at
write time so the DCM that ever leaves the sidecar (whether returned in the
``/run`` envelope or persisted alongside a future DICOM SEG) is already
deidentified per DICOM Supplement 142.

The blank-and-stamp pattern (rather than tag deletion) keeps downstream readers
happy — viewers that expect ``PatientName`` to exist as an empty string handle
this more gracefully than tag-absent records, and the explicit
``DeidentificationMethod`` line documents what happened.
"""

from __future__ import annotations

from typing import Iterable

# DICOM tag list to blank. Covers the Basic Application-Level Confidentiality
# Profile (DICOM SUP 142, Table E.1-1) restricted to the tags the E2E pipeline
# may actually populate today plus a defensive buffer.
_TAGS_TO_BLANK: tuple[str, ...] = (
    "PatientName",
    "PatientID",
    "PatientBirthDate",
    "PatientSex",
    "PatientAge",
    "PatientWeight",
    "PatientSize",
    "PatientAddress",
    "PatientTelephoneNumbers",
    "PatientMotherBirthName",
    "OtherPatientIDs",
    "OtherPatientNames",
    "StudyDate",
    "StudyTime",
    "AcquisitionDate",
    "AcquisitionDateTime",
    "AccessionNumber",
    "InstitutionName",
    "InstitutionAddress",
    "InstitutionalDepartmentName",
    "ReferringPhysicianName",
    "ReferringPhysicianAddress",
    "PerformingPhysicianName",
    "OperatorsName",
    "RequestingPhysician",
    "ResponsiblePerson",
    "ResponsibleOrganization",
)

DEIDENTIFICATION_METHOD = "LibreClinicaMUW-sidecar-v1"


def redact_dicom(ds, extra_tags: Iterable[str] = ()) -> None:
    """Blank PHI tags + stamp deidentification metadata on ``ds`` in place.

    Parameters
    ----------
    ds:
        A :class:`pydicom.Dataset` (or anything with the same ``setattr``-style
        tag interface). Modified in place.
    extra_tags:
        Additional tag keywords to blank beyond the built-in list. Useful when
        a vendor pipeline ends up populating a private institution-specific
        tag we want gone too.
    """
    for tag in (*_TAGS_TO_BLANK, *extra_tags):
        if hasattr(ds, tag):
            setattr(ds, tag, "")
    # SUP 142 attestation
    ds.DeidentificationMethod = DEIDENTIFICATION_METHOD
    ds.PatientIdentityRemoved = "YES"
    # Defence in depth — clear private (group-odd) tags that may carry
    # vendor-supplied PHI that the keyword list above can't catch by name.
    if hasattr(ds, "remove_private_tags"):
        ds.remove_private_tags()
