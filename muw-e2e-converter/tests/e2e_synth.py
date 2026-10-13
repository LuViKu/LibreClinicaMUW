"""Build a tiny synthetic Heidelberg ``.E2E`` file with oct-converter's own binary structures.

Used only by the tests: no real export (and nothing derived from one) is committed. The file has
the directory layout and the chunk types that ``muw_e2e_converter.series`` reads; the pixel
payloads are a few bytes.
"""

from __future__ import annotations

import struct
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path

import numpy as np
from oct_converter.readers.binary_structs import e2e_binary as eb

_EPOCH = datetime(1601, 1, 1)


def filetime(when: datetime) -> int:
    return int((when - _EPOCH).total_seconds() * 10_000_000)


@dataclass
class Img:
    ind: int  # 0 en-face (uint8), 1 OCT B-scan (uint16)
    slice_id: int
    height: int = 4
    width: int = 6
    fill: int = 7


@dataclass
class Series:
    ids: tuple[int, int, int]
    scan_pattern: str | None = "Images"
    enface: str | None = "IR"
    laterality: str | None = None  # 'R' | 'L'
    time: datetime | None = None
    images: list[Img] = field(default_factory=list)


def _chunk(series: Series | tuple[int, int, int], ctype: int, body: bytes, *, ind: int = 0,
           slice_id: int = 0) -> bytes:
    ids = series.ids if isinstance(series, Series) else series
    head = eb.chunk_structure.build(
        dict(magic3="MDbData", unknown=0, unknown2=0, pos=0, size=len(body), unknown3=0,
             patient_db_id=ids[0], study_id=ids[1], series_id=ids[2], slice_id=slice_id,
             ind=ind, unknown4=0, type=ctype, unknown5=0)
    )
    assert len(head) == 60
    return head + body


def _text(parts: list[str]) -> bytes:
    return eb.scan_pattern.build(dict(n_strings=len(parts), string_size=64, text=parts))


def build_e2e(path: Path, series: list[Series], surname: str | None = None,
              *, first_name: str = "SYNTHFIRST", patient_id: str = "SYNTHPID",
              more_surnames: tuple[str, ...] = ()) -> Path:
    """Write ``series`` to ``path``; chunks are laid out series by series, in order.

    ``surname`` (the study label slot) adds a patient chunk; ``more_surnames`` adds further
    patient chunks. First name and patient ID are decoys that must never be read.
    """
    chunks: list[tuple[tuple[int, int, int], int, bytes]] = []  # (ids, slice_id, bytes)
    names = ([surname] if surname is not None else []) + list(more_surnames)
    for name in names:
        patient = eb.patient_id_structure.build(
            dict(first_name=first_name, surname=name, title="", birthdate=0, sex="F",
                 patient_id=patient_id)
        )
        chunks.append(((1, 1, 0), -1, _chunk((1, 1, 0), 9, patient, ind=65535)))
    for s in series:
        if s.scan_pattern is not None:
            chunks.append((s.ids, 0, _chunk(s, 9006, _text([s.scan_pattern, ""]))))
        if s.enface is not None:
            chunks.append((s.ids, 0, _chunk(s, 9007, _text(["Long name", s.enface]))))
        if s.laterality is not None:
            chunks.append((s.ids, 0, _chunk(s, 3, struct.pack("<I", 0) + s.laterality.encode()
                                            + b"\x00" * 3)))
        if s.time is not None:
            chunks.append((s.ids, 0, _chunk(s, 5, struct.pack("<IQ", 0, filetime(s.time))
                                            + b"\x00" * 8)))
        for im in s.images:
            n = im.height * im.width
            if im.ind == 0:
                pixels = np.full(n, im.fill, dtype=np.uint8).tobytes()
            else:
                pixels = np.full(n, 0x3C00 + im.fill, dtype=np.uint16).tobytes()
            body = eb.image_structure.build(
                dict(size=len(pixels), type=0, unknown=0, height=im.height, width=im.width)
            ) + pixels
            chunks.append((s.ids, im.slice_id, _chunk(s, 1073741824, body, ind=im.ind,
                                                      slice_id=im.slice_id)))

    header = eb.header_structure.build(dict(magic1="CMDb", version=0, unknown=[0] * 10))
    dir_pos = len(header) + 52  # the directory chunk follows the main directory
    data_start = dir_pos + 52 + 44 * len(chunks)
    entries = b""
    blob = b""
    for ids, slice_id, raw in chunks:
        entries += eb.sub_directory_structure.build(
            dict(pos=0, start=data_start + len(blob), size=len(raw), unknown=0,
                 patient_db_id=ids[0], study_id=ids[1], series_id=ids[2], slice_id=slice_id,
                 unknown2=0, unknown3=0, type=0, unknown4=0)
        )
        blob += raw
    main = eb.main_directory_structure.build(
        dict(magic2="MDbMDir", version=0, unknown=[0] * 10, num_entries=0, current=dir_pos,
             prev=0, unknown3=0)
    )
    chunk_dir = eb.main_directory_structure.build(
        dict(magic2="MDbMDir", version=0, unknown=[0] * 10, num_entries=len(chunks), current=0,
             prev=0, unknown3=0)
    )
    path.write_bytes(header + main + chunk_dir + entries + blob)
    return path
