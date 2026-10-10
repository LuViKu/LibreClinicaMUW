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


def _mono_volume(frames: int, rows: int, cols: int) -> Dataset:
    """A greyscale multi-frame object shaped like a Spectralis OCT volume."""
    ds = Dataset()
    ds.Rows = rows
    ds.Columns = cols
    ds.NumberOfFrames = frames
    ds.SamplesPerPixel = 1
    ds.PhotometricInterpretation = "MONOCHROME2"
    ds.BitsAllocated = 8
    ds.BitsStored = 8
    ds.HighBit = 7
    ds.PixelRepresentation = 0
    vol = np.zeros((frames, rows, cols), dtype="uint8")
    for f in range(frames):
        vol[f] = f * 10  # each frame its own grey level
    ds.PixelData = vol.tobytes()
    fm = FileMetaDataset()
    fm.TransferSyntaxUID = ExplicitVRLittleEndian
    ds.file_meta = fm
    return ds


def test_preview_of_a_multiframe_oct_volume_is_its_middle_bscan(tmp_path):
    from PIL import Image

    ds = _mono_volume(frames=7, rows=40, cols=60)
    png = store.render_preview(ds, tmp_path / "vol.png")
    assert png is not None
    img = Image.open(png)
    # One B-scan (60 wide x 40 high), not a frames x rows strip read as RGB.
    assert img.size == (60, 40)
    assert img.mode == "L"


def test_preview_of_a_multiframe_colour_object_is_one_frame(tmp_path):
    from PIL import Image

    ds = Dataset()
    ds.Rows, ds.Columns, ds.NumberOfFrames = 8, 12, 3
    ds.SamplesPerPixel = 3
    ds.PhotometricInterpretation = "RGB"
    ds.PlanarConfiguration = 0
    ds.BitsAllocated, ds.BitsStored, ds.HighBit, ds.PixelRepresentation = 8, 8, 7, 0
    ds.PixelData = (np.arange(3 * 8 * 12 * 3) % 256).astype("uint8").tobytes()
    fm = FileMetaDataset()
    fm.TransferSyntaxUID = ExplicitVRLittleEndian
    ds.file_meta = fm
    img = Image.open(store.render_preview(ds, tmp_path / "rgb.png"))
    assert img.size == (12, 8)
    assert img.mode == "RGB"
