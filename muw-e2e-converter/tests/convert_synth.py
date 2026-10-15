"""A synthetic multimodal ``.E2E`` with the chunks ``convert_series`` reads (geometry, device, eye).

Builds on ``e2e_synth``: same container, but pixel payloads are random (so a pixel-parity test
means something) and the file carries 10004 (B-scan geometry), 9001 (device name), 1006 (device
model) and 9005 (examined structure) chunks. Nothing here is derived from a real export.
"""

from __future__ import annotations

import struct
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path

import numpy as np
from e2e_synth import _chunk, _text, filetime
from oct_converter.readers.binary_structs import e2e_binary as eb

DECOY_FIRST_NAME = "SYNTHFIRST"
DECOY_PATIENT_ID = "SYNTHPID"


@dataclass
class Item:
    ind: int  # 0 en-face uint8, 1 OCT B-scan uint16
    slice_id: int
    height: int
    width: int
    seed: int = 0


@dataclass
class Ser:
    ids: tuple[int, int, int]
    scan_pattern: str
    modality: str | None
    laterality: str | None
    time: datetime | None
    items: list[Item] = field(default_factory=list)
    geometry: bool = False  # add a 10004 chunk per OCT item


def pixels(item: Item) -> np.ndarray:
    rng = np.random.default_rng(item.seed)
    if item.ind == 0:
        return rng.integers(0, 256, (item.height, item.width), dtype=np.uint8)
    # raw UFloat16 words around "small reflectivity" values, with spread
    return (0x2800 + rng.integers(0, 0x1800, (item.height, item.width))).astype(np.uint16)


def build(path: Path, series: list[Ser], surname: str = "EIAMD005") -> Path:
    chunks: list[tuple[tuple[int, int, int], int, bytes]] = []
    patient = eb.patient_id_structure.build(
        dict(first_name=DECOY_FIRST_NAME, surname=surname, title="", birthdate=0, sex="F",
             patient_id=DECOY_PATIENT_ID))
    chunks.append(((1, 1, 0), -1, _chunk((1, 1, 0), 9, patient, ind=65535)))
    dev = eb.device_name.build(dict(n_strings=3, string_size=256,
                                    text=["Heidelberg Retina Angiograph", "HRA", ""]))
    chunks.append(((1, 1, 0), -1, _chunk((1, 1, 0), 9001, dev)))
    chunks.append(((1, 1, 0), -1, _chunk((1, 1, 0), 1006, b"SPECTRALISHX202\x00")))
    for s in series:
        chunks.append((s.ids, 0, _chunk(s.ids, 9006, _text([s.scan_pattern, ""]))))
        if s.modality is not None:
            chunks.append((s.ids, 0, _chunk(s.ids, 9007, _text(["Long name", s.modality]))))
        chunks.append((s.ids, 0, _chunk(s.ids, 9005, _text(["Retina"]))))
        if s.laterality is not None:
            chunks.append((s.ids, 0, _chunk(s.ids, 3, struct.pack("<I", 0) + s.laterality.encode()
                                            + b"\x00" * 3)))
        if s.time is not None:
            chunks.append((s.ids, 0, _chunk(s.ids, 5, struct.pack("<IQ", 0, filetime(s.time))
                                            + b"\x00" * 8)))
        n_oct = sum(1 for it in s.items if it.ind == 1)
        for i, it in enumerate(s.items):
            if s.geometry and it.ind == 1:
                x = -3.0 * 0.0 + 10.0  # degrees of field of view
                meta = eb.bscan_metadata.build(dict(
                    unknown1=0, imgSizeY=it.height, imgSizeX=it.width,
                    posX1=-x / 2, posY1=-x / 2 + 0.2 * i, posX2=x / 2, posY2=-x / 2 + 0.2 * i,
                    zero1=0, unknown2=0.0, scaley=0.00387, unknown3=0.0, zero2=0,
                    unknown4=[0.0, 0.0], zero3=0, imgSizeWidth=it.width, numImages=n_oct,
                    aktImage=i, scanType=0, centrePosX=0.0, centrePosY=-x / 2 + 0.2 * i,
                    unknown5=0, acquisitionTime=filetime(s.time or datetime(2026, 1, 1)),
                    numAve=1, imgQuality=0.0))
                chunks.append((s.ids, it.slice_id, _chunk(s.ids, 10004, meta, slice_id=it.slice_id)))
            arr = pixels(it)
            body = eb.image_structure.build(
                dict(size=arr.nbytes, type=0, unknown=0, height=it.height, width=it.width)
            ) + arr.tobytes()
            chunks.append((s.ids, it.slice_id, _chunk(s.ids, 1073741824, body, ind=it.ind,
                                                      slice_id=it.slice_id)))

    header = eb.header_structure.build(dict(magic1="CMDb", version=0, unknown=[0] * 10))
    dir_pos = len(header) + 52
    data_start = dir_pos + 52 + 44 * len(chunks)
    entries = b""
    blob = b""
    for ids, slice_id, raw in chunks:
        entries += eb.sub_directory_structure.build(
            dict(pos=0, start=data_start + len(blob), size=len(raw), unknown=0,
                 patient_db_id=ids[0], study_id=ids[1], series_id=ids[2], slice_id=slice_id,
                 unknown2=0, unknown3=0, type=0, unknown4=0))
        blob += raw
    main = eb.main_directory_structure.build(
        dict(magic2="MDbMDir", version=0, unknown=[0] * 10, num_entries=0, current=dir_pos,
             prev=0, unknown3=0))
    chunk_dir = eb.main_directory_structure.build(
        dict(magic2="MDbMDir", version=0, unknown=[0] * 10, num_entries=len(chunks), current=0,
             prev=0, unknown3=0))
    path.write_bytes(header + main + chunk_dir + entries + blob)
    return path
