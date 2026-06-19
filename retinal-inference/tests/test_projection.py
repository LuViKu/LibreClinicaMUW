"""Unit tests for the en-face biomarker projection module."""

from __future__ import annotations

import struct
import zlib
from pathlib import Path

import numpy as np
import pytest

from retinal_inference.inference.projection import (
    render_fluid_projection,
    render_ga_projection,
    render_thickness_projection,
)


def _read_png_dimensions(path: Path) -> tuple[int, int]:
    """Tiny PNG parser to read width/height without pulling in Pillow."""
    raw = path.read_bytes()
    assert raw.startswith(b"\x89PNG\r\n\x1a\n")
    # IHDR chunk starts at offset 8: 4 bytes length + 4 bytes "IHDR" + width(4) + height(4)
    width = struct.unpack(">I", raw[16:20])[0]
    height = struct.unpack(">I", raw[20:24])[0]
    return width, height


def _read_png_pixels(path: Path) -> np.ndarray:
    """Minimal PNG decoder for the un-filtered, un-interlaced 8-bit RGBA we write."""
    raw = path.read_bytes()
    width = struct.unpack(">I", raw[16:20])[0]
    height = struct.unpack(">I", raw[20:24])[0]

    # Pull IDAT payloads (may be multiple) — there's only one in our writer.
    pos = 8
    idat = bytearray()
    while pos < len(raw):
        length = struct.unpack(">I", raw[pos:pos + 4])[0]
        tag = raw[pos + 4:pos + 8]
        payload = raw[pos + 8:pos + 8 + length]
        pos += 8 + length + 4
        if tag == b"IDAT":
            idat.extend(payload)
        elif tag == b"IEND":
            break
    decompressed = zlib.decompress(bytes(idat))
    # Each row prefixed by a filter byte (None=0); strip + reshape.
    stride = width * 4
    rows = []
    for y in range(height):
        rstart = y * (stride + 1)
        assert decompressed[rstart] == 0
        rows.append(np.frombuffer(decompressed[rstart + 1:rstart + 1 + stride], dtype=np.uint8))
    return np.stack(rows).reshape(height, width, 4)


def test_render_fluid_projection_shapes(tmp_path: Path) -> None:
    # 5 B-scans × 4 rows × 7 A-scans. Plant one IRF pixel + one PED pixel.
    seg = np.zeros((5, 4, 7), dtype=np.uint8)
    seg[1, 2, 3] = 1  # IRF on B-scan 1, A-scan 3
    seg[4, 0, 6] = 3  # PED on B-scan 4, A-scan 6

    filename, per_bscan = render_fluid_projection(seg, tmp_path)
    assert filename == "projection_fluid.png"
    assert (tmp_path / filename).is_file()

    w, h = _read_png_dimensions(tmp_path / filename)
    assert (h, w) == (5, 7)  # PNG is (n_bscans rows × n_ascans cols)

    pixels = _read_png_pixels(tmp_path / filename)
    # IRF pixel: green channel non-zero, alpha set.
    assert pixels[1, 3, 1] > 0
    assert pixels[1, 3, 3] > 0
    # PED pixel: blue channel non-zero, alpha set.
    assert pixels[4, 6, 2] > 0
    assert pixels[4, 6, 3] > 0
    # Untouched pixel: fully transparent.
    assert pixels[0, 0, 3] == 0

    # Per-bscan A-scan counts (raw, mm² conversion is the runner's job).
    assert per_bscan["irf"][1] == 1
    assert per_bscan["irf"][0] == 0
    assert per_bscan["ped"][4] == 1
    assert per_bscan["srf"] == [0, 0, 0, 0, 0]


def test_render_ga_projection_from_csv(tmp_path: Path) -> None:
    # 3 B-scans × 5 A-scans; row 0 has loss at col 2, row 2 at cols 3 + 4.
    csv = tmp_path / "001-RPEL.csv"
    csv.write_text("0,0,1,0,0\n0,0,0,0,0\n0,0,0,1,1\n")

    filename, per_bscan = render_ga_projection(csv, tmp_path)
    assert filename == "projection_ga.png"
    pixels = _read_png_pixels(tmp_path / filename)
    assert pixels.shape == (3, 5, 4)
    # Loss pixel paints GA colour.
    assert pixels[0, 2, 3] > 0  # alpha set
    assert pixels[2, 3, 3] > 0
    # Empty row stays transparent.
    assert pixels[1, :, 3].sum() == 0
    assert per_bscan == [1, 0, 2]


def test_render_thickness_projection_clamps_negative_to_zero(tmp_path: Path) -> None:
    # Two B-scans × four A-scans. upper above lower → positive thickness.
    upper = tmp_path / "OPL-HFL.csv"
    lower = tmp_path / "BMEIS.csv"
    # row 0: thickness 10 px everywhere -> 10 * 0.004 * 1000 = 40 µm
    # row 1: upper=lower (=0 thickness)
    upper.write_text("100,100,100,100\n200,200,200,200\n")
    lower.write_text("110,110,110,110\n200,200,200,200\n")

    filename, per_bscan_um = render_thickness_projection(
        upper, lower, tmp_path, "onl", axial_mm_per_px=0.004,
    )
    assert filename == "projection_onl.png"
    pixels = _read_png_pixels(tmp_path / filename)
    # row 0 should be coloured; row 1 zero thickness → still valid, viridis stop 0
    assert pixels[0, 0, 3] > 0
    # Mean thickness on row 0 ≈ 40 µm.
    assert per_bscan_um[0] == pytest.approx(40.0, abs=0.5)
    assert per_bscan_um[1] == pytest.approx(0.0, abs=0.5)


def test_render_thickness_projection_handles_empty_csv(tmp_path: Path) -> None:
    upper = tmp_path / "u.csv"
    lower = tmp_path / "l.csv"
    upper.write_text("")
    lower.write_text("")
    filename, per_bscan_um = render_thickness_projection(
        upper, lower, tmp_path, "onl", axial_mm_per_px=0.004,
    )
    # Empty input → 1×1 transparent placeholder PNG so the artifact check stays uniform.
    assert (tmp_path / filename).is_file()
    assert per_bscan_um == []
