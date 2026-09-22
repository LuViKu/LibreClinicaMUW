from pydicom.dataset import Dataset

from dicom_scp import tags


def _ds(**kw) -> Dataset:
    ds = Dataset()
    for k, v in kw.items():
        setattr(ds, k, v)
    return ds


def test_study_date_iso():
    assert tags.study_date_iso(_ds(StudyDate="20260909")) == "2026-09-09"
    assert tags.study_date_iso(_ds(StudyDate="badvalue")) is None
    assert tags.study_date_iso(_ds()) is None


def test_laterality_maps_to_ophthalmic():
    assert tags.laterality(_ds(ImageLaterality="R")) == "OD"
    assert tags.laterality(_ds(Laterality="L")) == "OS"
    assert tags.laterality(_ds(ImageLaterality="B")) == "OU"
    assert tags.laterality(_ds()) is None


def test_extract_payload_shape():
    ds = _ds(SOPInstanceUID="1.2.3", SOPClassUID="1.2.840.10008.5.1.4.1.1.77.1.5.1",
             Modality="OP", PatientID="HAE-001", StudyDate="20260909",
             ImageLaterality="R", AccessionNumber="ACC-9")
    p = tags.extract(ds, "OPTOMED_AE")
    assert p["sopInstanceUid"] == "1.2.3"
    assert p["modality"] == "OP"
    assert p["patientId"] == "HAE-001"
    assert p["accessionNumber"] == "ACC-9"
    assert p["studyDate"] == "2026-09-09"
    assert p["laterality"] == "OD"
    assert p["sourceAeTitle"] == "OPTOMED_AE"


def test_extract_absent_tags_are_none():
    p = tags.extract(_ds(SOPInstanceUID="1.2.3"), "")
    assert p["sopInstanceUid"] == "1.2.3"
    assert p["patientId"] is None
    assert p["studyDate"] is None
    assert p["laterality"] is None
    assert p["sourceAeTitle"] is None
