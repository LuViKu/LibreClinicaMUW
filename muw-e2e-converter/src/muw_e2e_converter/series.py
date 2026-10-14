"""Series-level view of a multimodal Spectralis ``.E2E`` export (e2e-multimodal-split, Contract 1).

``describe_e2e`` lists every series of one file (OCT volume, OCT line scan and the en-face
modalities IR, BAF, BR, IRAF, qAF, ICGA, FA) without decoding any pixel data;
``render_series_png`` renders one frame of one series on demand.

Design notes:

* **One streaming pass.** ``oct_converter.readers.E2E`` only opens the directory; the chunk
  headers are walked here with oct-converter's own ``e2e_binary`` structures and the pixel
  payloads are skipped (only their file offsets are kept). A 600 MB export therefore costs a
  few MB of memory, not the several GB that ``read_oct_volume()`` needs.
* **octVolumeIndex** reproduces the ordering of ``E2E.read_oct_volume()`` (volumes that have a
  multi-slice entry in the directory first, in directory order; then the single-image ones in
  the order their first image appears), so it can be passed straight to ``read_e2e_volume`` /
  ``/preprocess`` as ``scan_index``. ``tests`` pin the rule; ``describe_e2e`` is validated against
  real exports.
* **The subject is the surname slot.** The study label sits in the surname slot of the patient
  chunk (type 9, offset 31, 51 bytes). The first-name and patient-ID slots can hold a clinic
  number: they, and the birth date, are never read. ``describe_e2e`` returns the surname as
  ``headerLabel`` only when it is a plain label token (see ``_label_token``) and otherwise
  ``null``; ``headerPatient`` compares ``expected_label`` with the surname only.
"""

from __future__ import annotations

import io
import logging
import os
import re
import struct
import threading
from collections import OrderedDict
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from pathlib import Path
from typing import Any

import numpy as np
from oct_converter.readers import E2E
from oct_converter.readers.binary_structs import e2e_binary as _eb
from PIL import Image

LOG = logging.getLogger(__name__)

__all__ = ["SERIES_MODALITY_ORDER", "describe_e2e", "render_series_png"]

# Order of the doc table (Series kinds); also the sort order of the describe response.
SERIES_MODALITY_ORDER = ("OCT", "OCT_LINE", "IR", "BAF", "BR", "IRAF", "QAF", "ICGA", "FA", "OTHER")
_ENFACE_TOKENS = frozenset({"IR", "BAF", "BR", "IRAF", "QAF", "ICGA", "FA"})
_LATERALITY_ORDER = {"OD": 0, "OS": 1, None: 2}

_CHUNK_PATIENT = 9
_CHUNK_PRE = 3
_CHUNK_FRAME_TIME = 5
_CHUNK_LATERALITY = 11
_CHUNK_BSCAN_META = 10004
_CHUNK_SCAN_PATTERN = 9006
_CHUNK_ENFACE_MODALITY = 9007
_CHUNK_DEVICE_NAME = 9001
_CHUNK_EXAMINED = 9005
_CHUNK_DEVICE_STRINGS = (1005, 1006)
_BSCAN_META_KEYS = ("imgSizeY", "imgSizeX", "imgSizeWidth", "posX1", "posY1", "posX2", "posY2",
                    "scaley", "centrePosX", "centrePosY")
_CHUNK_IMAGE = 1073741824
_IMAGE_HEADER_BYTES = 60 + 20  # chunk header + image_structure
_TEXT_CHUNK_CAP = 8192
_PATIENT_CHUNK_BYTES = 127
_SURNAME_OFFSET = 31
_SURNAME_BYTES = 51
_LABEL_TOKEN = re.compile(r"[!-~]{1,24}")  # printable ASCII, no space, 1-24 chars


@dataclass
class _Image:
    offset: int  # absolute file offset of the first pixel byte
    height: int
    width: int
    slice_id: int = 0


@dataclass
class _Series:
    ids: tuple[int, int, int]
    scan_pattern: str | None = None
    device_modality: str | None = None
    laterality: str | None = None
    times: list[datetime] = field(default_factory=list)
    bscan_time: datetime | None = None
    # OCT B-scans (chunk ind == 1). ``slots`` mirrors oct-converter's per-slice array.
    oct_slots: dict[int, _Image] = field(default_factory=dict)
    oct_extra: list[_Image] = field(default_factory=list)
    # en-face frames (chunk ind == 0)
    en_images: list[_Image] = field(default_factory=list)
    # per-B-scan geometry records (chunk 10004) of THIS series, in chunk order; used by convert
    bscan_meta: list[dict[str, float]] = field(default_factory=list)
    examined: str | None = None  # chunk 9005, e.g. "Retina"

    def oct_images(self) -> list[_Image]:
        if self.oct_slots:
            return [self.oct_slots[k] for k in sorted(self.oct_slots)]
        return list(self.oct_extra)


@dataclass
class _Described:
    key: str
    series: _Series
    modality: str
    oct_index: int | None


@dataclass
class _Index:
    path: Path
    byte_skip: int
    header_surnames: list[str]  # one entry per patient chunk; returned only as a vetted token
    described: list[_Described]  # sorted by the response order
    by_key: dict[str, _Described]
    e2e: E2E
    device_name: str | None = None  # first device-name chunk (9001), e.g. "Heidelberg Retina Angiograph"
    device_model: str | None = None  # first additional-device string (1005/1006), e.g. "SPECTRALISHX202"


def _filetime(value: int) -> datetime | None:
    """Windows FILETIME (100 ns ticks since 1601) -> naive datetime, None outside 2000..2044."""
    if not (1.26e17 < value < 1.40e17):
        return None
    return datetime(1601, 1, 1) + timedelta(microseconds=value // 10)


def _text(parsed: Any, idx: int) -> str | None:
    try:
        value = str(parsed.text[idx]).strip().strip("\x00")
    except (IndexError, AttributeError):
        return None
    return value or None


def _scan(path: Path) -> _Index:
    e2e = E2E(str(path))  # opens the directory only
    skip = e2e.byte_skip
    file_size = path.stat().st_size

    with open(path, "rb") as f:
        chunk_stack: list[int] = []
        volume_max_slice: dict[tuple[int, int, int], float] = {}
        for position in e2e.directory_stack:
            f.seek(position + skip)
            directory = _eb.main_directory_structure.parse(f.read(52))
            for _ in range(directory.num_entries):
                entry = _eb.sub_directory_structure.parse(f.read(44))
                ids = (entry.patient_db_id, entry.study_id, entry.series_id)
                half = entry.slice_id / 2
                if ids not in volume_max_slice or half > volume_max_slice[ids]:
                    volume_max_slice[ids] = half
                if entry.start > entry.pos:
                    chunk_stack.append(entry.start)

        array_volumes = {ids for ids, n in volume_max_slice.items() if n > 0}
        series_map: dict[tuple[int, int, int], _Series] = {}
        header_surnames: list[str] = []
        device_name: str | None = None
        device_model: str | None = None
        device_model_seen = False
        oct_stop = False

        for start in chunk_stack:
            f.seek(start + skip)
            try:
                chunk = _eb.chunk_structure.parse(f.read(60))
            except Exception:  # anonymised / damaged chunk: oct-converter skips these too
                continue
            ids = (chunk.patient_db_id, chunk.study_id, chunk.series_id)
            ctype = chunk.type

            if ctype == _CHUNK_PATIENT:
                # Only the surname slot is looked at; the other slots are never decoded.
                body = f.read(_PATIENT_CHUNK_BYTES)
                if len(body) >= _SURNAME_OFFSET + _SURNAME_BYTES:
                    raw = body[_SURNAME_OFFSET:_SURNAME_OFFSET + _SURNAME_BYTES]
                    header_surnames.append(raw.split(b"\x00", 1)[0].decode("latin-1").strip())
                else:
                    header_surnames.append("")
                continue

            series = series_map.get(ids)
            if series is None:
                series = series_map[ids] = _Series(ids=ids)

            if ctype == _CHUNK_IMAGE:
                try:
                    image = _eb.image_structure.parse(f.read(20))
                except Exception:
                    continue
                count = image.height * image.width
                entry_img = _Image(start + skip + _IMAGE_HEADER_BYTES, image.height, image.width,
                                   chunk.slice_id)
                if chunk.ind == 1:  # OCT B-scan; mirrors read_oct_volume incl. its early stop
                    if oct_stop:
                        continue
                    if count == 0:
                        oct_stop = True
                        continue
                    if entry_img.offset + 2 * count > file_size:
                        continue  # truncated payload: oct-converter drops it
                    slot = int(chunk.slice_id / 2)
                    if ids in array_volumes:
                        series.oct_slots[slot] = entry_img
                    else:
                        series.oct_extra.append(entry_img)
                elif chunk.ind == 0 and count > 0:
                    if entry_img.offset + count <= file_size:
                        series.en_images.append(entry_img)
            elif ctype == _CHUNK_PRE:
                try:
                    lat = _eb.pre_data.parse(f.read(8)).laterality
                except Exception:
                    lat = None
                if series.laterality is None and lat in ("R", "L"):
                    series.laterality = "OD" if lat == "R" else "OS"
            elif ctype == _CHUNK_LATERALITY:
                try:
                    lat = _eb.lat_structure.parse(f.read(20)).laterality
                except Exception:
                    lat = None
                if series.laterality is None and lat in ("R", "L"):
                    series.laterality = "OD" if lat == "R" else "OS"
            elif ctype == _CHUNK_FRAME_TIME:
                body = f.read(12)
                if len(body) >= 12:
                    when = _filetime(struct.unpack_from("<Q", body, 4)[0])
                    if when is not None:
                        series.times.append(when)
            elif ctype == _CHUNK_BSCAN_META:
                try:
                    meta = _eb.bscan_metadata.parse(f.read(104))
                    when = _filetime(int(meta.acquisitionTime))
                    series.bscan_meta.append({k: float(meta[k]) for k in _BSCAN_META_KEYS})
                except Exception:
                    when = None
                if when is not None and series.bscan_time is None:
                    series.bscan_time = when
            elif ctype == _CHUNK_DEVICE_NAME:
                if device_name is None:
                    try:
                        parsed = _eb.device_name.parse(f.read(min(chunk.size, _TEXT_CHUNK_CAP)))
                        device_name = _text(parsed, 0)
                    except Exception:  # an unreadable device-name chunk only leaves the name unset
                        LOG.debug("device-name chunk not readable", exc_info=True)
            elif ctype == _CHUNK_EXAMINED:
                if series.examined is None:
                    try:
                        parsed = _eb.examined_structure.parse(
                            f.read(min(chunk.size, _TEXT_CHUNK_CAP)))
                        series.examined = _text(parsed, 0)
                    except Exception:  # an unreadable examined-structure chunk only leaves it unset
                        LOG.debug("examined-structure chunk not readable", exc_info=True)
            elif ctype in _CHUNK_DEVICE_STRINGS:
                # oct-converter takes the FIRST such chunk's value, even when it is empty
                if not device_model_seen:
                    device_model_seen = True
                    raw = f.read(min(chunk.size, _TEXT_CHUNK_CAP))
                    text = "".join(c for c in raw.decode("latin-1") if " " <= c <= "~").strip()
                    device_model = text[:64] or None
            elif ctype in (_CHUNK_SCAN_PATTERN, _CHUNK_ENFACE_MODALITY):
                try:
                    parser = (_eb.scan_pattern if ctype == _CHUNK_SCAN_PATTERN
                              else _eb.enface_modality)
                    parsed = parser.parse(f.read(min(chunk.size, _TEXT_CHUNK_CAP)))
                except Exception:
                    continue
                if ctype == _CHUNK_SCAN_PATTERN:
                    if series.scan_pattern is None:
                        series.scan_pattern = _text(parsed, 0)
                elif series.device_modality is None:
                    series.device_modality = _text(parsed, 1)

    # --- read_oct_volume() ordering -> octVolumeIndex -------------------------------------
    oct_order: list[tuple[int, int, int]] = [
        ids for ids in volume_max_slice
        if ids in array_volumes and ids in series_map and series_map[ids].oct_slots
    ]
    in_order = set(oct_order)
    oct_order += [ids for ids, s in series_map.items() if ids not in in_order and s.oct_extra]
    oct_index = {ids: i for i, ids in enumerate(oct_order)}

    # --- classify, key, sort ------------------------------------------------------------
    present = sorted(ids for ids, s in series_map.items() if ids in oct_index or s.en_images)
    described: list[_Described] = []
    for ordinal, ids in enumerate(present):
        s = series_map[ids]
        if ids in oct_index:
            modality = "OCT" if "volume" in (s.scan_pattern or "").lower() else "OCT_LINE"
        else:
            token = (s.device_modality or "").strip().upper()
            modality = token if token in _ENFACE_TOKENS else "OTHER"
        described.append(_Described(f"s{ordinal}", s, modality, oct_index.get(ids)))

    def sort_key(d: _Described) -> tuple[Any, ...]:
        when = _series_time(d)
        return (
            _LATERALITY_ORDER[d.series.laterality],
            SERIES_MODALITY_ORDER.index(d.modality),
            when is None,
            when or datetime.min,
            d.series.ids,
        )

    described.sort(key=sort_key)
    return _Index(path, skip, header_surnames, described, {d.key: d for d in described}, e2e,
                  device_name, device_model)


def _series_time(d: _Described) -> datetime | None:
    s = d.series
    if s.times:
        return min(s.times)
    return s.bscan_time


# Small cache so describe -> series-image calls do not re-walk a 600 MB file each time.
_CACHE: OrderedDict[tuple[str, int, int], _Index] = OrderedDict()
_CACHE_LOCK = threading.Lock()
_CACHE_SIZE = 4


def _index(path: str | os.PathLike[str]) -> _Index:
    p = Path(path)
    st = p.stat()  # FileNotFoundError propagates
    cache_key = (str(p), st.st_size, st.st_mtime_ns)
    with _CACHE_LOCK:
        hit = _CACHE.get(cache_key)
        if hit is not None:
            _CACHE.move_to_end(cache_key)
            return hit
    built = _scan(p)
    with _CACHE_LOCK:
        _CACHE[cache_key] = built
        while len(_CACHE) > _CACHE_SIZE:
            _CACHE.popitem(last=False)
    return built


def _header_label(surnames: list[str]) -> str | None:
    """The surname if every patient chunk carries the same non-empty, label-shaped one."""
    if not surnames or len(set(surnames)) != 1:
        return None
    value = surnames[0]
    return value if _LABEL_TOKEN.fullmatch(value) else None


def _header_patient(surnames: list[str], expected_label: str | None) -> str:
    expected = (expected_label or "").strip()
    if not expected:
        return "none"
    present = {v for v in surnames if v}
    if not present:
        return "empty"
    return "matches" if all(v.casefold() == expected.casefold() for v in present) else "differs"


def describe_e2e(path: str | os.PathLike[str], expected_label: str | None = None) -> dict[str, Any]:
    """Describe every series of an ``.E2E`` file (Contract 1 response shape).

    ``headerLabel`` is the surname slot when every patient chunk carries the same non-empty
    printable-ASCII token of 1-24 characters without spaces, else ``None``. ``headerPatient``
    compares ``expected_label`` (optional) with the surname slot only: ``none`` without an
    expected label, else ``empty`` (no surname), ``matches`` (case-insensitive) or ``differs``.
    First name, patient ID and birth date are never read. Raises ``FileNotFoundError`` for a
    missing file.
    """
    idx = _index(path)
    series_out: list[dict[str, Any]] = []
    for d in idx.described:
        s = d.series
        when = _series_time(d)
        if d.oct_index is not None:
            first = s.oct_images()
            frames, height, width = len(first), first[0].height, first[0].width
        else:
            frames, height, width = len(s.en_images), s.en_images[0].height, s.en_images[0].width
        series_out.append(
            {
                "seriesKey": d.key,
                "seriesModality": d.modality,
                "deviceModality": s.device_modality,
                "scanPattern": s.scan_pattern,
                "laterality": s.laterality,
                "acquiredAt": when.isoformat() if when else None,
                "frames": frames,
                "width": width,
                "height": height,
                "octVolumeIndex": d.oct_index,
            }
        )
    return {
        "headerPatient": _header_patient(idx.header_surnames, expected_label),
        "headerLabel": _header_label(idx.header_surnames),
        "series": series_out,
    }


def _read_bscan(idx: _Index, image: _Image) -> np.ndarray:
    """One B-scan as uint8, with the same intensity transform as ``read_oct_volume``."""
    with open(idx.path, "rb") as f:
        f.seek(image.offset)
        raw = np.fromfile(f, dtype=np.uint16, count=image.height * image.width)
    unique, inverse = np.unique(raw, return_inverse=True)
    lut = np.array([idx.e2e.uint16_to_ufloat16(int(v)) for v in unique])
    data = idx.e2e.vol_intensity_transform(lut[inverse].reshape(image.height, image.width))
    return (np.clip(data, 0.0, 1.0) * 255.0).round().astype(np.uint8)


def _read_enface(idx: _Index, image: _Image) -> np.ndarray:
    with open(idx.path, "rb") as f:
        f.seek(image.offset)
        raw = np.fromfile(f, dtype=np.uint8, count=image.height * image.width)
    return raw.reshape(image.height, image.width)


def render_series_png(path: str | os.PathLike[str], series_key: str, frame: int = 0) -> bytes:
    """PNG bytes of one frame of a series; the PNG carries no text or other ancillary chunks.

    * en-face series: the ``frame``-th image;
    * OCT line scan: the ``frame``-th B-scan;
    * OCT volume: its IR localizer (the series' en-face image); a volume without one falls
      back to its ``frame``-th B-scan.

    Raises ``KeyError`` for an unknown ``series_key`` and ``IndexError`` for a frame out of range.
    """
    idx = _index(path)
    d = idx.by_key.get(series_key)
    if d is None:
        raise KeyError(series_key)
    s = d.series
    if frame < 0:
        raise IndexError(frame)
    if d.modality == "OCT_LINE" or (d.modality == "OCT" and not s.en_images):
        images = s.oct_images()
        if frame >= len(images):
            raise IndexError(frame)
        pixels = _read_bscan(idx, images[frame])
    else:
        if frame >= len(s.en_images):
            raise IndexError(frame)
        pixels = _read_enface(idx, s.en_images[frame])
    buf = io.BytesIO()
    Image.fromarray(pixels).save(buf, format="PNG", optimize=False)
    return buf.getvalue()
