"""One pass over a multimodal ``.E2E``: every kept series becomes its own DICOM (e2e-multimodal-split,
amendment 2026-10-13 (b)).

``convert_series`` is the server-side split. It walks the chunk directory once (the index
``describe_e2e`` builds, cached per file), then decodes the requested series one at a time:

* **OCT volume / OCT line scan** (``OCT``, ``OCT_LINE``): the same Ophthalmic Tomography DICOM
  ``write_bscan_dcm`` has always written, 16-bit, with the pixel values ``read_e2e_volume`` gives
  today (oct-converter's log-compressed intensity, scaled so the volume's maximum is 65535). A
  volume also gets ``<seriesKey>.geometry.json`` (B-scan positions in SLO pixels, as ``/preprocess``
  has written it).
* **En-face images and time sequences** (``IR``, ``BAF``, ``BR``, ``IRAF``, ``QAF``, ``ICGA``,
  ``FA``, ``OTHER``): an Ophthalmic Photography 8 Bit Image (SOP class
  ``1.2.840.10008.5.1.4.1.1.77.1.5.1``), MONOCHROME2, uncompressed, multi-frame when the series
  holds several images. The E2E stores these 8-bit, so nothing is lost.

What the files carry about the person: ``PatientID`` and ``PatientName`` are the study label
passed in, nothing else (no birth date, sex, age, first name, patient-ID slot or institution; the
first-name and patient-ID slots of the E2E are never read). Laterality, acquisition date/time and
Manufacturer come from the file's own per-series metadata. All UIDs are derived from the file's
SHA-256 and the series key, so converting the same export again gives byte-identical files.

Memory: one series is decoded at a time and released before the next. An OCT volume is held as
its raw uint16 B-scans (about 100 MB for 97 x 496 x 1024) plus one float64 B-scan; the scale
(``65535 / max``) comes from the set of distinct raw values, so the whole volume is never held as
float64 as ``read_oct_volume`` does.
"""

from __future__ import annotations

import gc
import hashlib
import json
import os
import re
from datetime import datetime
from pathlib import Path
from typing import Any

import numpy as np

from .e2e_parser import (
    BscanVolume,
    _derive_spacing_mm,
    heidelberg_pos_to_mm,
    write_bscan_dcm,
)
from .fundus_extract import build_geometry
from .series import _Described, _Image, _Index, _index, _series_time

__all__ = ["OP_8BIT_SOP_CLASS", "OPT_SOP_CLASS", "clean_label", "convert_series"]

OP_8BIT_SOP_CLASS = "1.2.840.10008.5.1.4.1.1.77.1.5.1"  # Ophthalmic Photography 8 Bit Image
OPT_SOP_CLASS = "1.2.840.10008.5.1.4.1.1.77.1.5.4"  # Ophthalmic Tomography Image

_SERIES_KEY = re.compile(r"s[0-9]{1,6}")
_MAX_LABEL = 64
_HASH_CHUNK = 1 << 20


def clean_label(raw: str | None) -> str:
    """The label as a PN/LO value: printable, no component separators, at most 64 characters.

    Same rule as the DICOM sidecar's pseudonym cleaning, so a label survives its rewrite.
    """
    if raw is None:
        return ""
    s = str(raw).strip()[:_MAX_LABEL]
    return "".join(c for c in s if c.isprintable() and c not in "^=\\")


def _file_sha256(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while True:
            block = f.read(_HASH_CHUNK)
            if not block:
                break
            h.update(block)
    return h.hexdigest()


# --------------------------------------------------------------------- OCT decoding


_LUT: np.ndarray | None = None


def _ufloat16_lut() -> np.ndarray:
    """oct-converter's ``uint16_to_ufloat16`` for all 65536 inputs, vectorised.

    The mantissa is the lowest 10 bits *bit-reversed* (that is what the reference does with its
    reversed bit string); the exponent is the top 6 bits minus 63. ``tests`` compare every entry
    with the reference implementation.
    """
    global _LUT
    if _LUT is None:
        u = np.arange(65536, dtype=np.uint32)
        low = u & 0x3FF
        rev = np.zeros_like(low)
        for bit in range(10):
            rev |= ((low >> bit) & 1) << (9 - bit)
        mantissa = 1.0 + rev.astype(np.float64) / 1024.0
        exponent = (u >> 10).astype(np.float64) - 63.0
        _LUT = mantissa * np.float_power(2.0, exponent)
    return _LUT


def _read_raw(f: Any, image: _Image) -> np.ndarray:
    f.seek(image.offset)
    raw = np.fromfile(f, dtype=np.uint16, count=image.height * image.width)
    if raw.size != image.height * image.width:
        raise ValueError("truncated B-scan")
    return raw.reshape(image.height, image.width)


def decode_oct_u16(idx: _Index, images: list[_Image]) -> np.ndarray:
    """The B-scans as one uint16 volume, value for value what ``read_e2e_volume`` produces."""
    first = images[0]
    shape = (first.height, first.width)
    if any((im.height, im.width) != shape for im in images):
        raise ValueError("the B-scans of a series have different sizes")
    lut = _ufloat16_lut()
    raws = np.empty((len(images), *shape), dtype=np.uint16)
    present = np.zeros(65536, dtype=bool)
    with open(idx.path, "rb") as f:
        for i, im in enumerate(images):
            raws[i] = _read_raw(f, im)
            present[raws[i].ravel()] = True
    # The volume maximum of the transformed values equals the maximum over the distinct raw values.
    distinct = np.flatnonzero(present)
    peak_values = idx.e2e.vol_intensity_transform(lut[distinct].copy())
    finite_max = float(np.nanmax(peak_values)) if peak_values.size else 1.0
    scale = 65535.0 / finite_max if finite_max > 0 else 65535.0
    del peak_values, distinct, present
    out = np.empty(raws.shape, dtype=np.uint16)
    for i in range(raws.shape[0]):
        frame = idx.e2e.vol_intensity_transform(lut[raws[i]])
        np.nan_to_num(frame, copy=False)
        frame *= scale
        np.clip(frame, 0, 65535, out=frame)
        out[i] = frame.astype(np.uint16)
    return out


def _acquired(d: _Described) -> datetime | None:
    return _series_time(d)


def _bscan_volume(idx: _Index, d: _Described, volume: np.ndarray) -> BscanVolume:
    s = d.series
    n, rows, cols = volume.shape
    bscan_data = list(s.bscan_meta)
    if bscan_data:
        axial, lateral, slice_mm = _derive_spacing_mm(bscan_data, n)
    else:  # no geometry chunks: oct-converter's default spacing, as read_e2e_volume falls back to
        lateral, axial, slice_mm = 0.0039, 0.0039, 0.05
    positions = None
    if bscan_data:
        try:
            positions = tuple(
                tuple(round(heidelberg_pos_to_mm(float(b[k])), 6)
                      for k in ("posX1", "posY1", "posX2", "posY2"))
                for b in bscan_data[:n]
            )
        except (KeyError, TypeError, ValueError):
            positions = None
    when = _acquired(d)
    return BscanVolume(
        volume_u8=volume,
        axial_mm=axial,
        lateral_mm=lateral,
        slice_mm=slice_mm,
        laterality=s.laterality,
        n_bscans=n,
        rows=rows,
        cols=cols,
        acquisition_date=when.strftime("%Y-%m-%d") if when else None,
        acquisition_time=when.strftime("%H%M%S") if when else None,
        bscan_positions_mm=positions,
        manufacturer=idx.device_name,
        manufacturer_model=idx.device_model,
        examined_structure=s.examined,
        scan_pattern=s.scan_pattern,
        series_uid_seed=None,
        bscan_data=tuple(bscan_data) if bscan_data else None,
    )


# --------------------------------------------------------------------- en-face DICOM


def write_enface_dcm(
    frames: np.ndarray,
    out_path: Path,
    *,
    label: str,
    uid_seed: str,
    study_uid_seed: str,
    laterality: str | None,
    acquired: datetime | None,
    manufacturer: str | None,
    model: str | None,
    series_description: str | None,
) -> None:
    """Write ``frames`` (n, rows, cols) uint8 as an Ophthalmic Photography 8 Bit Image."""
    import pydicom
    from pydicom.dataset import Dataset, FileMetaDataset
    from pydicom.sequence import Sequence
    from pydicom.uid import ExplicitVRLittleEndian, generate_uid

    if frames.dtype != np.uint8 or frames.ndim != 3:
        raise ValueError("en-face frames must be a (n, rows, cols) uint8 array")
    n, rows, cols = frames.shape
    sop_uid = generate_uid(entropy_srcs=[uid_seed, "sop"])

    fm = FileMetaDataset()
    fm.MediaStorageSOPClassUID = OP_8BIT_SOP_CLASS
    fm.MediaStorageSOPInstanceUID = sop_uid
    fm.TransferSyntaxUID = ExplicitVRLittleEndian

    ds = Dataset()
    ds.file_meta = fm
    ds.SOPClassUID = OP_8BIT_SOP_CLASS
    ds.SOPInstanceUID = sop_uid
    ds.StudyInstanceUID = generate_uid(entropy_srcs=[study_uid_seed, "study"])
    ds.SeriesInstanceUID = generate_uid(entropy_srcs=[uid_seed, "series"])
    ds.Modality = "OP"
    ds.Manufacturer = manufacturer or "Heidelberg Engineering"
    if model:
        ds.ManufacturerModelName = model

    # Patient module: the study label and nothing else.
    ds.PatientName = label
    ds.PatientID = label
    ds.PatientBirthDate = ""
    ds.PatientSex = ""

    if acquired is not None:
        day = acquired.strftime("%Y%m%d")
        clock = acquired.strftime("%H%M%S")
        for kw in ("StudyDate", "SeriesDate", "AcquisitionDate", "ContentDate"):
            setattr(ds, kw, day)
        for kw in ("StudyTime", "SeriesTime", "AcquisitionTime", "ContentTime"):
            setattr(ds, kw, clock)
        ds.AcquisitionDateTime = day + clock
    else:
        ds.StudyDate = ""
        ds.StudyTime = ""
    ds.ReferringPhysicianName = ""
    ds.AccessionNumber = ""
    ds.StudyID = "0"
    ds.SeriesNumber = "0"
    ds.InstanceNumber = "1"
    ds.ImageType = ["ORIGINAL", "PRIMARY"]
    if series_description:
        ds.SeriesDescription = series_description[:64]
    if laterality is not None:
        ds.Laterality = laterality
        ds.ImageLaterality = "R" if laterality == "OD" else "L"

    ds.SamplesPerPixel = 1
    ds.PhotometricInterpretation = "MONOCHROME2"
    if n > 1:
        ds.NumberOfFrames = str(n)
    ds.Rows = rows
    ds.Columns = cols
    ds.BitsAllocated = 8
    ds.BitsStored = 8
    ds.HighBit = 7
    ds.PixelRepresentation = 0
    ds.PixelData = np.ascontiguousarray(frames).tobytes()

    device = Dataset()
    device.CodeValue = "A-00FBF"
    device.CodingSchemeDesignator = "SRT"
    device.CodeMeaning = "Ophthalmic Camera"
    ds.AcquisitionDeviceTypeCodeSequence = Sequence([device])
    region = Dataset()
    region.CodeValue = "T-AA610"
    region.CodingSchemeDesignator = "SRT"
    region.CodeMeaning = "Retina"
    ds.AnatomicRegionSequence = Sequence([region])

    out_path.parent.mkdir(parents=True, exist_ok=True)
    pydicom.dcmwrite(str(out_path), ds, enforce_file_format=True)


def _decode_enface(idx: _Index, images: list[_Image]) -> np.ndarray:
    first = images[0]
    shape = (first.height, first.width)
    if any((im.height, im.width) != shape for im in images):
        raise ValueError("the images of a series have different sizes")
    out = np.empty((len(images), *shape), dtype=np.uint8)
    with open(idx.path, "rb") as f:
        for i, im in enumerate(images):
            f.seek(im.offset)
            raw = np.fromfile(f, dtype=np.uint8, count=im.height * im.width)
            if raw.size != im.height * im.width:
                raise ValueError("truncated image")
            out[i] = raw.reshape(shape)
    return out


# --------------------------------------------------------------------- the split


def _verify(path: Path, *, frames: int, rows: int, cols: int, bits: int, pixel_sha256: str,
            sop_class: str, label: str) -> None:
    """Read the written file back: attributes, pixel length and pixel digest must be what we wrote."""
    import pydicom

    ds = pydicom.dcmread(str(path))
    got_frames = int(getattr(ds, "NumberOfFrames", 1) or 1)
    if (got_frames, int(ds.Rows), int(ds.Columns), int(ds.BitsAllocated)) != (frames, rows, cols, bits):
        raise RuntimeError("written DICOM does not match the decoded series (frames/size)")
    if str(ds.SOPClassUID) != sop_class:
        raise RuntimeError("written DICOM has the wrong SOP class")
    raw = ds.PixelData
    if len(raw) != frames * rows * cols * bits // 8:
        raise RuntimeError("written DICOM has the wrong pixel length")
    if hashlib.sha256(raw).hexdigest() != pixel_sha256:
        raise RuntimeError("written DICOM pixel data differs from the decoded series")
    if str(ds.PatientID) != label or str(ds.PatientName) != label:
        raise RuntimeError("written DICOM patient identity is not the label")



def _inside(root: Path, name: str) -> Path:
    """``root/name`` for a bare output name, refusing anything that would leave ``root``."""
    base = os.path.realpath(root)
    target = os.path.normpath(os.path.join(base, name))
    if not target.startswith(base + os.sep):
        raise ValueError("output name leaves the output directory")
    return Path(target)

def convert_series(
    path: str | Path,
    label: str | None,
    series_keys: list[str],
    out_dir: str | Path,
    *,
    source_sha256: str | None = None,
) -> list[dict[str, Any]]:
    """Convert the ``series_keys`` of one ``.E2E`` into ``<seriesKey>.dcm`` files in ``out_dir``.

    Returns one dict per key, in request order: ``seriesKey``, ``file`` (bare name), ``sha256`` and
    ``bytes`` of the DICOM, ``frames``, ``sopClassUid``, ``seriesModality``, and ``geometryFile``
    for an OCT volume. Raises ``KeyError`` for a key the file does not have (before anything is
    written), ``ValueError`` for a malformed or empty key list, ``FileNotFoundError`` for a
    missing file; a decoding or verification failure propagates and leaves a partial ``out_dir``
    for the caller to remove.
    """
    p = Path(path)
    keys: list[str] = []
    for k in series_keys or []:
        if not isinstance(k, str) or not _SERIES_KEY.fullmatch(k):
            raise ValueError("malformed seriesKey")
        if k not in keys:
            keys.append(k)
    if not keys:
        raise ValueError("no series requested")
    idx = _index(p)  # FileNotFoundError propagates
    for k in keys:
        if k not in idx.by_key:
            raise KeyError(k)

    name = clean_label(label)
    sha = (source_sha256 or _file_sha256(p)).lower()
    out = Path(os.path.realpath(out_dir))
    out.mkdir(parents=True, exist_ok=True)
    results: list[dict[str, Any]] = []

    for key in keys:
        d = idx.by_key[key]
        s = d.series
        seed = f"{sha}|{key}"
        dcm = _inside(out, f"{key}.dcm")
        entry: dict[str, Any] = {"seriesKey": key, "file": dcm.name, "seriesModality": d.modality}

        if d.modality in ("OCT", "OCT_LINE"):
            volume = decode_oct_u16(idx, s.oct_images())
            frames, rows, cols = volume.shape
            pixel_sha = hashlib.sha256(volume.reshape(-1).view(np.uint8)).hexdigest()
            bv = _bscan_volume(idx, d, volume)
            write_bscan_dcm(bv, out, patient_label=name, file_name=dcm.name, uid_seed=seed,
                            study_uid_seed=sha)
            sop_class, bits = OPT_SOP_CLASS, 16
            if d.modality == "OCT":
                import pydicom

                ds = pydicom.dcmread(str(dcm), stop_before_pixels=True)
                en = s.en_images[0] if s.en_images else None
                geom = build_geometry(bv, ds, (en.width, en.height) if en else None,
                                      e2e_path=None, scan_index=int(d.oct_index or 0))
                geom["source_format"] = "e2e"
                geom["spacing_order"] = "standard"  # our writer: [axial, lateral]
                geo = _inside(out, f"{key}.geometry.json")
                geo.write_text(json.dumps(geom, indent=2, sort_keys=True), encoding="utf-8")
                entry["geometryFile"] = geo.name
                del ds
            del bv, volume
        else:
            images = _decode_enface(idx, s.en_images)
            frames, rows, cols = images.shape
            pixel_sha = hashlib.sha256(images.reshape(-1)).hexdigest()
            desc = " ".join(x for x in (s.scan_pattern, s.device_modality) if x) or None
            write_enface_dcm(images, dcm, label=name, uid_seed=seed, study_uid_seed=sha, laterality=s.laterality,
                             acquired=_acquired(d), manufacturer=idx.device_name,
                             model=idx.device_model, series_description=desc)
            sop_class, bits = OP_8BIT_SOP_CLASS, 8
            del images

        _verify(dcm, frames=frames, rows=rows, cols=cols, bits=bits, pixel_sha256=pixel_sha,
                sop_class=sop_class, label=name)
        data = dcm.read_bytes()
        entry.update(sha256=hashlib.sha256(data).hexdigest(), bytes=len(data), frames=frames,
                     sopClassUid=sop_class)
        del data
        results.append(entry)
        gc.collect()  # one series at a time: release its arrays before the next
    return results
