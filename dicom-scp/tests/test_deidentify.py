"""The stored file must not carry the patient a clinic camera wrote into it (DR-029)."""
from __future__ import annotations

from pathlib import Path

import numpy as np
import pydicom
import pytest
from pydicom.dataset import Dataset, FileMetaDataset
from pydicom.errors import InvalidDicomError
from pydicom.uid import ExplicitVRLittleEndian, generate_uid

from dicom_scp import deidentify

OPHTHALMIC_PHOTO_8BIT = "1.2.840.10008.5.1.4.1.1.77.1.5.1"


def _clinic_export(path: Path) -> tuple[Dataset, bytes]:
    """What a Clarus in a clinic exports: the hospital's patient, staff, institution."""
    sop = generate_uid()
    ds = Dataset()
    ds.SOPInstanceUID = sop
    ds.SOPClassUID = OPHTHALMIC_PHOTO_8BIT
    ds.StudyInstanceUID = generate_uid()
    ds.SeriesInstanceUID = generate_uid()
    ds.Modality = "OP"
    ds.PatientName = "Muster^Max"
    ds.PatientID = "0012345678"
    ds.PatientBirthDate = "19500101"
    ds.PatientSex = "M"
    ds.OtherPatientIDs = "SV-1234"
    ds.OperatorsName = "Fotograf^Fritz"
    ds.ReferringPhysicianName = "Arzt^Anna"
    ds.InstitutionName = "AKH Wien"
    ds.AccessionNumber = "ACC-77"
    ds.StudyID = "S-9"
    ds.StudyDate = "20260918"
    ds.AcquisitionDate = "20260918"
    ds.ImageLaterality = "R"
    ds.Manufacturer = "Carl Zeiss Meditec"
    ds.ManufacturerModelName = "CLARUS 700"
    seq_item = Dataset()
    seq_item.PatientID = "OLD-1"
    ds.OtherPatientIDsSequence = [seq_item]
    # A vendor private block, the kind that carries calibration.
    block = ds.private_block(0x0009, "CZM PRIVATE", create=True)
    block.add_new(0x01, "LO", "fov=133")
    ds.Rows = 4
    ds.Columns = 4
    ds.SamplesPerPixel = 3
    ds.PhotometricInterpretation = "RGB"
    ds.PlanarConfiguration = 0
    ds.BitsAllocated = 8
    ds.BitsStored = 8
    ds.HighBit = 7
    ds.PixelRepresentation = 0
    pixels = (np.arange(4 * 4 * 3) % 256).astype("uint8").tobytes()
    ds.PixelData = pixels

    fm = FileMetaDataset()
    fm.MediaStorageSOPClassUID = OPHTHALMIC_PHOTO_8BIT
    fm.MediaStorageSOPInstanceUID = sop
    fm.TransferSyntaxUID = ExplicitVRLittleEndian
    ds.file_meta = fm
    ds.save_as(str(path), enforce_file_format=True)
    return ds, pixels


def test_identity_is_replaced_by_the_subject_label(tmp_path):
    path = tmp_path / "clarus.dcm"
    original, pixels = _clinic_export(path)

    _, changed = deidentify.rewrite(path, "HAE-001")
    back = pydicom.dcmread(str(path))

    assert str(back.PatientName) == "HAE-001"
    assert back.PatientID == "HAE-001"
    assert back.PatientBirthDate == ""
    assert back.OtherPatientIDs == ""
    assert str(back.OperatorsName) == ""
    assert str(back.ReferringPhysicianName) == ""
    assert back.InstitutionName == ""
    assert back.AccessionNumber == ""
    assert back.StudyID == ""
    assert "OtherPatientIDsSequence" not in back
    assert back.PatientIdentityRemoved == "YES"
    assert back.DeidentificationMethod == deidentify.DEIDENTIFICATION_METHOD
    for kw in ("PatientName", "PatientID", "PatientBirthDate", "OperatorsName",
               "InstitutionName", "OtherPatientIDsSequence"):
        assert kw in changed


def test_what_the_research_needs_stays(tmp_path):
    path = tmp_path / "clarus.dcm"
    original, pixels = _clinic_export(path)

    deidentify.rewrite(path, "HAE-001")
    back = pydicom.dcmread(str(path))

    # Provenance and timeline.
    assert back.SOPInstanceUID == original.SOPInstanceUID
    assert back.StudyInstanceUID == original.StudyInstanceUID
    assert back.StudyDate == "20260918"
    assert back.AcquisitionDate == "20260918"
    assert back.ImageLaterality == "R"
    assert back.PatientSex == "M"
    # The device, and what it wrote about itself.
    assert back.ManufacturerModelName == "CLARUS 700"
    assert back.private_block(0x0009, "CZM PRIVATE")[0x01].value == "fov=133"
    # The pixels, byte for byte, in the same transfer syntax.
    assert back.PixelData == pixels
    assert back.file_meta.TransferSyntaxUID == ExplicitVRLittleEndian


def test_private_tags_go_only_on_request(tmp_path):
    path = tmp_path / "clarus.dcm"
    _clinic_export(path)

    _, changed = deidentify.rewrite(path, "HAE-001", drop_private=True)
    back = pydicom.dcmread(str(path))

    assert "PrivateTags" in changed
    assert not [e for e in back if e.tag.is_private]


def test_an_unfiled_upload_is_blanked_not_labelled(tmp_path):
    path = tmp_path / "clarus.dcm"
    _clinic_export(path)

    deidentify.rewrite(path, None)
    back = pydicom.dcmread(str(path))

    assert str(back.PatientName) == ""
    assert back.PatientID == ""


def test_a_clean_file_reports_nothing_changed(tmp_path):
    path = tmp_path / "clarus.dcm"
    _clinic_export(path)
    deidentify.rewrite(path, "HAE-001")

    _, changed = deidentify.rewrite(path, "HAE-001")

    assert changed == []


def test_a_pseudonym_is_bounded_and_cannot_carry_separators():
    assert deidentify._clean_pseudonym("  HAE-001 ") == "HAE-001"
    assert deidentify._clean_pseudonym("A^B=C\\D") == "ABCD"
    assert len(deidentify._clean_pseudonym("x" * 200)) == deidentify.MAX_PSEUDONYM
    assert deidentify._clean_pseudonym(None) == ""


def test_not_a_dicom_file_is_refused_and_left_alone(tmp_path):
    path = tmp_path / "photo.jpg"
    path.write_bytes(b"\xff\xd8\xff\xe0" + b"\x00" * 200)

    with pytest.raises(InvalidDicomError):
        deidentify.rewrite(path, "HAE-001")
    assert path.read_bytes()[:4] == b"\xff\xd8\xff\xe0"
    assert not list(tmp_path.glob("*.tmp"))
