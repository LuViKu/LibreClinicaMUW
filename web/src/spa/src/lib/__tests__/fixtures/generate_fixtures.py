#!/usr/bin/env python3
"""Generate the tiny `.e2e` fixtures used by e2eParser.spec.ts.

These are NOT real Heidelberg exports — they are minimal, hand-crafted
binary blobs that satisfy the chunk layout the TypeScript parser expects.
The format is documented in `oct_converter/readers/binary_structs/e2e_binary.py`
and the public `parseE2e` doc-comment.

Run from this directory:

    python3 generate_fixtures.py

Produces:
    single-scan.e2e        ONE volume, PatientId="TEST-001",
                           scanDate=2024-01-15T00:00:00Z, OD, 49 B-scans
    multi-scan-OD-OS.e2e   TWO volumes, both PatientId="TEST-002",
                           both scanDate=2024-03-20T00:00:00Z,
                           Volume 0 OD/49, Volume 1 OS/49

Byte layout the parser walks:

    Offset 0           : 12-byte ASCII magic "CMDb        "
                       : u32 version
                       : 10 × u16 unknown                  (file header = 36 B)
    Offset 36          : 12-byte ASCII magic for main directory
                       : u32 version
                       : 10 × u16 unknown
                       : u32 num_entries
                       : u32 current  (offset of next main dir, 0 if none)
                       : u32 prev     (offset of prev main dir, 0 if none)
                       : u32 unknown3                     (main directory = 52 B)
    Offset 88          : sub-directory entries (44 B each)
                         pos, start, size, unknown, patient_db_id, study_id,
                         series_id, slice_id (i32), 2 × u16 unknown, type, unknown4
                         IMPORTANT: parser keeps entries where start > pos AND start > 0
    ...                : chunks (60-byte header + payload of `size` bytes)

The simplest valid file therefore has:
    1) One main_directory at offset 36, with num_entries = N pointing
       to all the chunks
    2) main_directory.current == offset_of_main_directory (so directory
       traversal pushes ONE position to the stack)
    3) main_directory.prev == 0 (end of linked list)
    4) Sub-directory entries describing each chunk with start > pos
    5) Chunks themselves with the right `type`, plus payload
"""
from __future__ import annotations

import struct
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

HEADER_MAGIC = b"CMDb        "  # 12 bytes; only first 4 chars matter to the parser
DIR_MAGIC = b"MDbDir      "  # 12 bytes
CHUNK_MAGIC = b"MDbChunk    "  # 12 bytes

FILE_HEADER_SIZE = 36
MAIN_DIRECTORY_SIZE = 52
SUB_DIRECTORY_ENTRY_SIZE = 44
CHUNK_HEADER_SIZE = 60

# Chunk type integers (must match oct_converter / e2eParser.ts).
TYPE_PRE_DATA = 3
TYPE_PATIENT_DATA = 9
TYPE_BSCAN_METADATA = 10004

# Per-chunk payload offsets (must match e2eParser.ts).
PATIENT_DATA_ID_OFFSET = 31 + 51 + 15 + 4 + 1  # = 102
PATIENT_DATA_TOTAL_SIZE = 127

PRE_DATA_TOTAL_SIZE = 5  # u32 unknown + 1-byte laterality

BSCAN_METADATA_TOTAL_SIZE = 104
BSCAN_NUM_IMAGES_OFFSET = 64
BSCAN_ACQUISITION_TIME_OFFSET = 88

# Windows tick conversion mirror.
WINDOWS_TICKS_PER_SECOND = 10_000_000
WINDOWS_EPOCH_TO_UNIX_SECONDS = 11_644_473_600


def datetime_to_windows_ticks(dt: datetime) -> int:
    """Convert a tz-aware datetime → Windows file-time ticks (100-ns since 1601)."""
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    unix_seconds = int(dt.timestamp())
    return (unix_seconds + WINDOWS_EPOCH_TO_UNIX_SECONDS) * WINDOWS_TICKS_PER_SECOND


def pad(b: bytes, size: int) -> bytes:
    """Right-pad with NULs to exactly `size` bytes (or truncate)."""
    return b[:size].ljust(size, b"\x00")


def build_file_header() -> bytes:
    """Minimal 36-byte file header with the right magic + zeros elsewhere."""
    return pad(HEADER_MAGIC, 12) + struct.pack("<I", 1) + b"\x00" * 20


def build_main_directory(num_entries: int, current: int, prev: int) -> bytes:
    """52-byte main directory chunk. `current` is read by the parser to seed
    the directory-traversal walk; `prev` continues / terminates it."""
    return (
        pad(DIR_MAGIC, 12)
        + struct.pack("<I", 1)
        + b"\x00" * 20
        + struct.pack("<III", num_entries, current, prev)
        + b"\x00" * 4
    )


def build_sub_directory_entry(
    *,
    pos: int,
    start: int,
    size: int,
    patient_db_id: int,
    study_id: int,
    series_id: int,
    slice_id: int,
    chunk_type: int,
) -> bytes:
    """44-byte sub-directory entry. Parser keeps entries where start > pos AND start > 0."""
    return struct.pack(
        "<IIIIIIIihhII",
        pos,
        start,
        size,
        0,            # unknown
        patient_db_id,
        study_id,
        series_id,
        slice_id,     # i32
        0,            # unknown2 (i16 — parser treats as u16, sign-irrelevant for 0)
        0,            # unknown3
        chunk_type,
        0,            # unknown4
    )


def build_chunk_header(
    *,
    pos: int,
    size: int,
    patient_db_id: int,
    study_id: int,
    series_id: int,
    slice_id: int,
    chunk_type: int,
) -> bytes:
    """60-byte chunk header. magic + 3 × u32 unknown + pos + size + ... + type + u32 unknown."""
    return (
        pad(CHUNK_MAGIC, 12)
        + struct.pack("<III", 0, 0, pos)
        + struct.pack("<I", size)
        + struct.pack("<I", 0)
        + struct.pack(
            "<IIIihhII",
            patient_db_id,
            study_id,
            series_id,
            slice_id,
            0,            # ind (u16; 0 = fundus, 1 = OCT in real exports)
            0,            # unknown4
            chunk_type,
            0,            # unknown5
        )
    )


def build_patient_chunk_payload(patient_id: str) -> bytes:
    """127-byte patient data chunk payload with patient_id at offset 102."""
    payload = bytearray(PATIENT_DATA_TOTAL_SIZE)
    encoded = patient_id.encode("latin-1")
    assert len(encoded) <= 25, "patient_id must fit in 25 latin-1 bytes"
    payload[PATIENT_DATA_ID_OFFSET : PATIENT_DATA_ID_OFFSET + len(encoded)] = encoded
    return bytes(payload)


def build_pre_data_payload(laterality_char: str) -> bytes:
    """5-byte pre_data payload: u32 unknown + 1-byte laterality."""
    assert laterality_char in {"R", "L"}, f"unexpected laterality {laterality_char!r}"
    return struct.pack("<Ic", 0, laterality_char.encode("ascii"))


def build_bscan_metadata_payload(num_images: int, acquisition_ticks: int) -> bytes:
    """104-byte bscan_metadata payload with numImages at offset 64 and
    acquisitionTime (u64 ticks) at offset 88."""
    payload = bytearray(BSCAN_METADATA_TOTAL_SIZE)
    payload[BSCAN_NUM_IMAGES_OFFSET : BSCAN_NUM_IMAGES_OFFSET + 4] = struct.pack(
        "<I", num_images
    )
    payload[BSCAN_ACQUISITION_TIME_OFFSET : BSCAN_ACQUISITION_TIME_OFFSET + 8] = struct.pack(
        "<Q", acquisition_ticks
    )
    return bytes(payload)


@dataclass
class ChunkPlan:
    chunk_type: int
    patient_db_id: int
    study_id: int
    series_id: int
    slice_id: int
    payload: bytes


def assemble_file(chunks: list[ChunkPlan]) -> bytes:
    """Lay out a minimal `.e2e` file containing `chunks`.

    Layout strategy:
      [0..36)        file header
      [36..88)       main directory (current pointer → itself; prev = 0)
      [88..)         sub-directory entries (one per chunk)
      [after sub-dirs..) chunk headers + payloads, contiguously

    The parser's directory walk goes:
      1. read main_directory at offset 36 → current = 36, prev = 0
      2. push 36 to stack
      3. follow prev = 0 → exit loop
      4. for each directory in stack:
           seek to dir, read 52 B main_directory, read num_entries × 44 B entries
    """
    main_dir_offset = FILE_HEADER_SIZE  # = 36
    sub_dirs_start = main_dir_offset + MAIN_DIRECTORY_SIZE  # = 88
    sub_dirs_size = SUB_DIRECTORY_ENTRY_SIZE * len(chunks)
    chunks_start = sub_dirs_start + sub_dirs_size

    # Pre-compute (start, size) of each chunk's full segment (header + payload).
    chunk_starts: list[int] = []
    cursor = chunks_start
    for c in chunks:
        chunk_starts.append(cursor)
        cursor += CHUNK_HEADER_SIZE + len(c.payload)

    # Build sub-directory entries. `pos` is the chunk's logical id (any
    # value < start works — using `0` makes start > pos trivially true).
    sub_dir_bytes = b""
    for c, start in zip(chunks, chunk_starts):
        sub_dir_bytes += build_sub_directory_entry(
            pos=1,                       # keep pos > 0 but < start
            start=start,
            size=CHUNK_HEADER_SIZE + len(c.payload),
            patient_db_id=c.patient_db_id,
            study_id=c.study_id,
            series_id=c.series_id,
            slice_id=c.slice_id,
            chunk_type=c.chunk_type,
        )

    # Build chunk header + payload for each.
    chunk_bytes = b""
    for c, start in zip(chunks, chunk_starts):
        chunk_bytes += build_chunk_header(
            pos=1,
            size=CHUNK_HEADER_SIZE + len(c.payload),
            patient_db_id=c.patient_db_id,
            study_id=c.study_id,
            series_id=c.series_id,
            slice_id=c.slice_id,
            chunk_type=c.chunk_type,
        )
        chunk_bytes += c.payload

    return b"".join(
        [
            build_file_header(),
            build_main_directory(
                num_entries=len(chunks),
                current=main_dir_offset,   # → ourselves: directory_stack = [main_dir_offset]
                prev=0,                    # end of linked list
            ),
            sub_dir_bytes,
            chunk_bytes,
        ]
    )


def make_single_scan() -> bytes:
    ticks = datetime_to_windows_ticks(datetime(2024, 1, 15, tzinfo=timezone.utc))
    chunks = [
        ChunkPlan(
            chunk_type=TYPE_PATIENT_DATA,
            patient_db_id=1,
            study_id=0,
            series_id=0,
            slice_id=-1,
            payload=build_patient_chunk_payload("TEST-001"),
        ),
        ChunkPlan(
            chunk_type=TYPE_PRE_DATA,
            patient_db_id=1,
            study_id=10,
            series_id=100,
            slice_id=-1,
            payload=build_pre_data_payload("R"),
        ),
        ChunkPlan(
            chunk_type=TYPE_BSCAN_METADATA,
            patient_db_id=1,
            study_id=10,
            series_id=100,
            slice_id=0,
            payload=build_bscan_metadata_payload(num_images=49, acquisition_ticks=ticks),
        ),
    ]
    return assemble_file(chunks)


def make_multi_scan() -> bytes:
    ticks = datetime_to_windows_ticks(datetime(2024, 3, 20, tzinfo=timezone.utc))
    chunks = [
        ChunkPlan(
            chunk_type=TYPE_PATIENT_DATA,
            patient_db_id=2,
            study_id=0,
            series_id=0,
            slice_id=-1,
            payload=build_patient_chunk_payload("TEST-002"),
        ),
        # Volume A: OD
        ChunkPlan(
            chunk_type=TYPE_PRE_DATA,
            patient_db_id=2,
            study_id=20,
            series_id=200,
            slice_id=-1,
            payload=build_pre_data_payload("R"),
        ),
        ChunkPlan(
            chunk_type=TYPE_BSCAN_METADATA,
            patient_db_id=2,
            study_id=20,
            series_id=200,
            slice_id=0,
            payload=build_bscan_metadata_payload(num_images=49, acquisition_ticks=ticks),
        ),
        # Volume B: OS
        ChunkPlan(
            chunk_type=TYPE_PRE_DATA,
            patient_db_id=2,
            study_id=20,
            series_id=201,
            slice_id=-1,
            payload=build_pre_data_payload("L"),
        ),
        ChunkPlan(
            chunk_type=TYPE_BSCAN_METADATA,
            patient_db_id=2,
            study_id=20,
            series_id=201,
            slice_id=0,
            payload=build_bscan_metadata_payload(num_images=49, acquisition_ticks=ticks),
        ),
    ]
    return assemble_file(chunks)


def main() -> None:
    here = Path(__file__).parent
    (here / "single-scan.e2e").write_bytes(make_single_scan())
    (here / "multi-scan-OD-OS.e2e").write_bytes(make_multi_scan())
    print(f"Wrote fixtures to {here}")


if __name__ == "__main__":
    main()
