"""Synthetic Ophthalmic Tomography DICOMs for the DICOM-OCT tests.

Builds a small multi-frame OPT object with the spacing in whichever place a
test needs (top level, Shared or Per-frame Pixel Measures, only derivable from
per-frame plane positions, or nowhere) plus a full set of patient
identifiers, free-text fields and a private block, so the de-identification
tests have something to strip.
"""

from __future__ import annotations

import io

import numpy as np
import pydicom
from pydicom.dataset import Dataset, FileMetaDataset
from pydicom.sequence import Sequence
from pydicom.uid import ExplicitVRLittleEndian, generate_uid

OPT_SOP = "1.2.840.10008.5.1.4.1.1.77.1.5.4"
OP_SOP = "1.2.840.10008.5.1.4.1.1.77.1.5.1"

AXIAL = 0.0038716698
LATERAL = 0.011454128
SLICE = 0.1206

# Identifiers planted in every synthetic file; none may survive preprocess.
PHI_VALUES = (
    "Doe^Jane",
    "MRN-4711",
    "19700101",
    "ACC-0815",
    "Allgemeines Krankenhaus",
    "Dr^Who",
    "Operator^Olga",
    "Jane eye study",
    "SN-DEVICE-123",
    "STATION-7",
    "OTHER-ID-9",
    "1.2.3.4.5.6.7.8.9.111",  # source StudyInstanceUID
    "1.2.3.4.5.6.7.8.9.222",  # source SeriesInstanceUID
    "1.2.3.4.5.6.7.8.9.333",  # source SOPInstanceUID
    "PRIVATE-PHI-PAYLOAD",
)


def volume(n: int = 3, rows: int = 8, cols: int = 6, bits: int = 16) -> np.ndarray:
    dtype = np.uint16 if bits == 16 else np.uint8
    top = 4000 if bits == 16 else 250
    arr = np.arange(n * rows * cols, dtype=np.float64).reshape(n, rows, cols)
    arr = arr / arr.max() * top
    return arr.astype(dtype)


def make_opt(
    *,
    n: int = 3,
    rows: int = 8,
    cols: int = 6,
    bits: int = 16,
    spacing: str = "top",
    manufacturer: str | None = "Heidelberg Engineering",
    model: str | None = "Spectralis",
    image_laterality: str | None = "R",
    laterality: str | None = None,
    burned_in: str | None = "NO",
    sop_class: str = OPT_SOP,
    modality: str = "OPT",
    photometric: str = "MONOCHROME2",
    samples_per_pixel: int = 1,
    acquisition_datetime: str | None = "20260915103045",
    acquisition_date: str | None = None,
    study_date: str | None = "20260914",
    position_steps: list[float] | None = None,
    slice_thickness_only: bool = False,
    reference_coordinates_only: bool = False,
    pixels: np.ndarray | None = None,
    compress_rle: bool = False,
    transfer_syntax: str | None = None,
    pixel_spacing: tuple[float, float] | None = None,
    slice_spacing: float | None = None,
) -> bytes:
    """Part-10 bytes of a synthetic OPT volume.

    ``spacing``: ``top`` | ``shared`` | ``perframe`` | ``positions`` | ``none``.
    ``pixel_spacing`` / ``slice_spacing`` override the stored values as given
    (in stored order), e.g. the [lateral, axial] of a third-party conversion.
    """
    px = list(pixel_spacing) if pixel_spacing is not None else [AXIAL, LATERAL]
    sl = slice_spacing if slice_spacing is not None else SLICE
    fm = FileMetaDataset()
    fm.MediaStorageSOPClassUID = sop_class
    fm.MediaStorageSOPInstanceUID = "1.2.3.4.5.6.7.8.9.333"
    fm.TransferSyntaxUID = ExplicitVRLittleEndian

    ds = Dataset()
    ds.file_meta = fm
    ds.SOPClassUID = sop_class
    ds.SOPInstanceUID = "1.2.3.4.5.6.7.8.9.333"
    ds.StudyInstanceUID = "1.2.3.4.5.6.7.8.9.111"
    ds.SeriesInstanceUID = "1.2.3.4.5.6.7.8.9.222"
    ds.FrameOfReferenceUID = generate_uid()
    ds.Modality = modality
    if manufacturer is not None:
        ds.Manufacturer = manufacturer
    if model is not None:
        ds.ManufacturerModelName = model
    ds.DeviceSerialNumber = "SN-DEVICE-123"
    ds.StationName = "STATION-7"

    # Patient / study identifiers and free text.
    ds.PatientName = "Doe^Jane"
    ds.PatientID = "MRN-4711"
    ds.PatientBirthDate = "19700101"
    ds.PatientSex = "F"
    ds.OtherPatientIDs = "OTHER-ID-9"
    ds.AccessionNumber = "ACC-0815"
    ds.InstitutionName = "Allgemeines Krankenhaus"
    ds.ReferringPhysicianName = "Dr^Who"
    ds.OperatorsName = "Operator^Olga"
    ds.StudyDescription = "Jane eye study"
    ds.SeriesDescription = "Jane eye study"
    if study_date:
        ds.StudyDate = study_date
    if acquisition_datetime:
        ds.AcquisitionDateTime = acquisition_datetime
    if acquisition_date:
        ds.AcquisitionDate = acquisition_date

    if image_laterality is not None:
        ds.ImageLaterality = image_laterality
    if laterality is not None:
        ds.Laterality = laterality
    if burned_in is not None:
        ds.BurnedInAnnotation = burned_in

    block = ds.private_block(0x0029, "ACME OCT", create=True)
    block.add_new(0x01, "LO", "PRIVATE-PHI-PAYLOAD")

    ds.SamplesPerPixel = samples_per_pixel
    ds.PhotometricInterpretation = photometric
    ds.NumberOfFrames = str(n)
    ds.Rows = rows
    ds.Columns = cols
    ds.BitsAllocated = bits
    ds.BitsStored = bits
    ds.HighBit = bits - 1
    ds.PixelRepresentation = 0

    def _pm(with_slice: bool = True) -> Dataset:
        pm = Dataset()
        pm.PixelSpacing = px
        if with_slice:
            pm.SpacingBetweenSlices = sl
        return pm

    if spacing == "top":
        ds.PixelSpacing = px
        ds.SpacingBetweenSlices = sl
    elif spacing == "shared":
        shared = Dataset()
        shared.PixelMeasuresSequence = Sequence([_pm()])
        ds.SharedFunctionalGroupsSequence = Sequence([shared])
    elif spacing == "perframe":
        ds.PerFrameFunctionalGroupsSequence = Sequence(
            [_frame_group(i, _pm()) for i in range(n)]
        )
    elif spacing == "positions":
        shared = Dataset()
        pm = _pm(with_slice=False)
        if slice_thickness_only:
            pm.SliceThickness = 0.5
        shared.PixelMeasuresSequence = Sequence([pm])
        ds.SharedFunctionalGroupsSequence = Sequence([shared])
        steps = position_steps or [sl] * (n - 1)
        ys = [0.0]
        for s in steps:
            ys.append(ys[-1] + s)
        groups = []
        for i in range(n):
            g = _frame_group(i, None)
            pp = Dataset()
            pp.ImagePositionPatient = [1.5, round(ys[i], 6), -2.0]
            g.PlanePositionSequence = Sequence([pp])
            groups.append(g)
        ds.PerFrameFunctionalGroupsSequence = Sequence(groups)
    elif spacing == "none":
        shared = Dataset()
        pm = _pm(with_slice=False)
        if slice_thickness_only:
            pm.SliceThickness = 0.5
        shared.PixelMeasuresSequence = Sequence([pm])
        ds.SharedFunctionalGroupsSequence = Sequence([shared])
        if reference_coordinates_only:
            groups = []
            for i in range(n):
                g = _frame_group(i, None)
                loc = Dataset()
                loc.ReferenceCoordinates = [100.0 + 10 * i, 20.0, 100.0 + 10 * i, 600.0]
                g.OphthalmicFrameLocationSequence = Sequence([loc])
                groups.append(g)
            ds.PerFrameFunctionalGroupsSequence = Sequence(groups)
    else:  # pragma: no cover
        raise ValueError(spacing)

    arr = pixels if pixels is not None else volume(n, rows, cols, bits)
    ds.PixelData = arr.tobytes()

    if compress_rle:
        from pydicom.uid import RLELossless

        ds.compress(RLELossless)
    if transfer_syntax is not None:
        from pydicom.encaps import encapsulate

        ds.file_meta.TransferSyntaxUID = transfer_syntax
        ds.PixelData = encapsulate([b"\xff\xd8not-a-real-codestream\xff\xd9"] * n)
        ds["PixelData"].VR = "OB"

    buf = io.BytesIO()
    pydicom.dcmwrite(buf, ds, enforce_file_format=True)
    return buf.getvalue()


def _frame_group(i: int, pm: Dataset | None) -> Dataset:
    g = Dataset()
    fc = Dataset()
    fc.InStackPositionNumber = i + 1
    g.FrameContentSequence = Sequence([fc])
    if pm is not None:
        g.PixelMeasuresSequence = Sequence([pm])
    return g


def read(body: bytes) -> pydicom.Dataset:
    return pydicom.dcmread(io.BytesIO(body))


# A DICOM produced by a third-party .e2e -> DICOM converter (another
# MUW-internal platform; tags only, observed 2026-10-09). It stores
# PixelSpacing as [lateral, axial], against the DICOM definition, has
# Laterality = "OD" (not R/L) and no ImageLaterality, model name or
# BurnedInAnnotation. 1024 x 0.005825 = 5.96 mm scan width, 496 x 0.003872 =
# 1.92 mm depth, 96 x 0.062138 = 5.97 mm volume: a 6 x 6 mm macula cube.
# Original Heyex exports are expected in standard order (per the user). The
# original has 97 frames; the tests use fewer (the rule does not use the count).
CONVERTED_LATERAL = 0.005825
CONVERTED_AXIAL = 0.003872
CONVERTED_SLICE = 0.062138


def make_third_party_e2e_conversion(n: int = 3) -> bytes:
    return make_opt(
        n=n,
        rows=496,
        cols=1024,
        spacing="top",
        pixel_spacing=(CONVERTED_LATERAL, CONVERTED_AXIAL),
        slice_spacing=CONVERTED_SLICE,
        manufacturer="Heidelberg Engineering",
        model=None,
        image_laterality=None,
        laterality="OD",
        burned_in=None,
    )
