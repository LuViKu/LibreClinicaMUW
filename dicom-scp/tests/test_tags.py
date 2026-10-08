from pydicom.dataset import Dataset, FileMetaDataset
from pydicom.uid import ExplicitVRLittleEndian

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


def _image(pixels: bytes, **kw) -> Dataset:
    """A 2x2 8-bit greyscale object with the given pixels and whatever tags."""
    ds = _ds(Rows=2, Columns=2, SamplesPerPixel=1, PhotometricInterpretation="MONOCHROME2",
             BitsAllocated=8, BitsStored=8, HighBit=7, PixelRepresentation=0,
             PixelData=pixels, **kw)
    fm = FileMetaDataset()
    fm.TransferSyntaxUID = ExplicitVRLittleEndian
    ds.file_meta = fm
    return ds


def test_pixel_sha256_ignores_the_tags_and_follows_the_pixels():
    same = tags.pixel_sha256(_image(bytes([1, 2, 3, 4]), PatientID="HAE-001", PatientName="Muster^Max"))
    relabelled = tags.pixel_sha256(_image(bytes([1, 2, 3, 4]), PatientID="HAE-002", StudyDate="20260918"))
    other = tags.pixel_sha256(_image(bytes([1, 2, 3, 5]), PatientID="HAE-001"))
    assert same is not None and len(same) == 64
    assert same == relabelled
    assert same != other


def test_pixel_sha256_is_none_without_pixels():
    assert tags.pixel_sha256(_ds(PatientID="HAE-001")) is None


def test_the_digest_travels_in_both_payloads():
    ds = _image(bytes([9, 9, 9, 9]), SOPInstanceUID="1.2.3")
    assert tags.extract(ds, "AE")["pixelSha256"] == tags.pixel_sha256(ds)
    assert tags.describe(ds)["pixelSha256"] == tags.pixel_sha256(ds)
    assert tags.extract(_ds(SOPInstanceUID="1.2.3"), "")["pixelSha256"] is None


# --- OCT volume classification (describe numberOfFrames / octVolume) ---------

_OPT_SOP = "1.2.840.10008.5.1.4.1.1.77.1.5.4"


def test_describe_reports_number_of_frames_and_oct_volume_for_an_opt_volume():
    d = tags.describe(_ds(SOPClassUID=_OPT_SOP, Modality="OPT", NumberOfFrames="49"))
    assert d["numberOfFrames"] == 49
    assert d["octVolume"] is True


def test_describe_counts_modality_opt_without_the_sop_class():
    d = tags.describe(_ds(SOPClassUID="1.2.840.10008.5.1.4.1.1.7.2", Modality="OPT",
                          NumberOfFrames=25))
    assert d["octVolume"] is True


def test_describe_a_single_oct_frame_is_not_a_volume():
    d = tags.describe(_ds(SOPClassUID=_OPT_SOP, Modality="OPT", NumberOfFrames="1"))
    assert d["numberOfFrames"] == 1
    assert d["octVolume"] is False


def test_describe_a_fundus_photo_is_not_an_oct_volume():
    d = tags.describe(_ds(SOPClassUID="1.2.840.10008.5.1.4.1.1.77.1.5.1", Modality="OP"))
    assert d["numberOfFrames"] is None
    assert d["octVolume"] is False


def test_describe_a_multi_frame_non_oct_object_is_not_an_oct_volume():
    d = tags.describe(_ds(SOPClassUID="1.2.840.10008.5.1.4.1.1.77.1.5.1", Modality="OP",
                          NumberOfFrames="3"))
    assert d["numberOfFrames"] == 3
    assert d["octVolume"] is False


def test_describe_a_malformed_frame_count_is_none_and_not_a_volume():
    # As read from a file: the raw element only fails to convert on access.
    from pydicom.dataelem import RawDataElement
    from pydicom.tag import Tag

    ds = _ds(SOPClassUID=_OPT_SOP, Modality="OPT")
    ds[0x00280008] = RawDataElement(Tag(0x00280008), "IS", 4, b"abc ", 0, False, True)
    d = tags.describe(ds)
    assert d["numberOfFrames"] is None
    assert d["octVolume"] is False
