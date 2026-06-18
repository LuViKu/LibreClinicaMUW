"""DR-022 PHI redaction — `redact_dicom` blanks identifying tags + stamps SUP 142."""

from __future__ import annotations

import pydicom

from retinal_inference.inference.phi import DEIDENTIFICATION_METHOD, redact_dicom


def _make_ds_with_phi() -> pydicom.Dataset:
    ds = pydicom.Dataset()
    ds.PatientName = "DOE^JANE"
    ds.PatientID = "MRN-12345"
    ds.PatientBirthDate = "19850101"
    ds.PatientSex = "F"
    ds.StudyDate = "20260101"
    ds.AcquisitionDate = "20260101"
    ds.AccessionNumber = "ACC-0001"
    ds.InstitutionName = "Med Uni Wien"
    ds.ReferringPhysicianName = "SCHMIDT^HANS"
    return ds


def test_redact_blanks_identifying_tags() -> None:
    ds = _make_ds_with_phi()
    redact_dicom(ds)
    assert ds.PatientName == ""
    assert ds.PatientID == ""
    assert ds.PatientBirthDate == ""
    assert ds.PatientSex == ""
    assert ds.StudyDate == ""
    assert ds.AcquisitionDate == ""
    assert ds.AccessionNumber == ""
    assert ds.InstitutionName == ""
    assert ds.ReferringPhysicianName == ""


def test_redact_stamps_sup_142_attestation() -> None:
    ds = _make_ds_with_phi()
    redact_dicom(ds)
    assert ds.PatientIdentityRemoved == "YES"
    assert ds.DeidentificationMethod == DEIDENTIFICATION_METHOD


def test_redact_extra_tags() -> None:
    ds = _make_ds_with_phi()
    ds.SeriesDescription = "Heidelberg cube"
    redact_dicom(ds, extra_tags=("SeriesDescription",))
    assert ds.SeriesDescription == ""


def test_redact_idempotent() -> None:
    ds = _make_ds_with_phi()
    redact_dicom(ds)
    # Second call must not regress anything that was already blanked.
    redact_dicom(ds)
    assert ds.PatientName == ""
    assert ds.PatientIdentityRemoved == "YES"