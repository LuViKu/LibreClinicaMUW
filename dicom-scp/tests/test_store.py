from pathlib import Path

import numpy as np
from pydicom.dataset import Dataset, FileMetaDataset
from pydicom.uid import ExplicitVRLittleEndian, SecondaryCaptureImageStorage, generate_uid

from dicom_scp import store


def _rgb_dataset() -> tuple[Dataset, FileMetaDataset]:
    sop = generate_uid()
    ds = Dataset()
    ds.SOPInstanceUID = sop
    ds.SOPClassUID = SecondaryCaptureImageStorage
    ds.PatientID = "HAE-001"
    ds.Modality = "XC"
    ds.Rows = 4
    ds.Columns = 4
    ds.SamplesPerPixel = 3
    ds.PhotometricInterpretation = "RGB"
    ds.PlanarConfiguration = 0
    ds.BitsAllocated = 8
    ds.BitsStored = 8
    ds.HighBit = 7
    ds.PixelRepresentation = 0
    ds.PixelData = (np.arange(4 * 4 * 3) % 256).astype("uint8").tobytes()

    fm = FileMetaDataset()
    fm.MediaStorageSOPClassUID = SecondaryCaptureImageStorage
    fm.MediaStorageSOPInstanceUID = sop
    fm.TransferSyntaxUID = ExplicitVRLittleEndian
    return ds, fm


def test_persist_writes_part10_under_date_partition(tmp_path):
    ds, fm = _rgb_dataset()
    dcm_path, png_path = store.persist(ds, fm, str(tmp_path), "2026-09-09")

    assert Path(dcm_path).exists()
    assert dcm_path.endswith(".dcm")
    assert "2026-09-09" in dcm_path
    # Uncompressed RGB → preview should render (best-effort, but works here).
    assert png_path is not None and Path(png_path).exists()


def test_persist_undated_partition(tmp_path):
    ds, fm = _rgb_dataset()
    dcm_path, _ = store.persist(ds, fm, str(tmp_path), None)
    assert "undated" in dcm_path
