"""describe_e2e / render_series_png on SYNTHETIC .E2E files (see e2e_synth.py).

No real export is used here. The synthetic file is built with oct-converter's own binary
structures, so the parser under test is the real one.
"""

from __future__ import annotations

import io
import json
from datetime import datetime
from pathlib import Path

import numpy as np
import pytest
from e2e_synth import Img, Series, build_e2e
from oct_converter.readers import E2E
from PIL import Image

from muw_e2e_converter import describe_e2e, render_series_png
from muw_e2e_converter.series import SERIES_MODALITY_ORDER

T = datetime


def _bscans(n: int) -> list[Img]:
    return [Img(1, 2 * i, height=5, width=8, fill=i) for i in range(n)]


def _series() -> list[Series]:
    return [
        # OD OCT volume (3 B-scans + IR localizer)
        Series((1, 1, 1), "OCT ART Volume", "IR", "R", T(2026, 9, 30, 10, 0, 0),
               _bscans(3) + [Img(0, 0, fill=50)]),
        # OD OCT line scan
        Series((1, 1, 2), "OCT B-Scan", "IR", "R", T(2026, 9, 30, 10, 5, 0),
               [Img(1, 0, height=5, width=8), Img(0, 0, fill=60)]),
        # OS qAF time sequence, 3 frames
        Series((1, 1, 3), "Time Sequence", "qAF", "L", T(2026, 9, 30, 10, 20, 0),
               [Img(0, i, fill=100 + i) for i in range(3)]),
        # OD ICGA, later in time but lower id than series 7
        Series((1, 1, 4), "Images", "ICGA", "R", T(2026, 9, 30, 11, 0, 0), [Img(0, 0, fill=70)]),
        # unknown device modality, no laterality, no time
        Series((1, 1, 5), "Images", "XYZ", None, None, [Img(0, 0, fill=80)]),
        # OS OCT volume (second volume in directory order)
        Series((1, 1, 6), "OCT ART Volume", "IR", "L", T(2026, 9, 30, 10, 30, 0),
               _bscans(2) + [Img(0, 0, fill=90)]),
        # OD ICGA that is EARLIER than series 4 but has a higher id
        Series((1, 1, 7), "Images", "ICGA", "R", T(2026, 9, 30, 10, 45, 0), [Img(0, 0, fill=71)]),
        # OD IR en-face, no time
        Series((1, 1, 8), "Images", "IR", "R", None, [Img(0, 0, fill=72)]),
        # series without any image: not reported
        Series((1, 1, 9), "Images", "FA", "R", None, []),
    ]


@pytest.fixture
def e2e(tmp_path: Path) -> Path:
    return build_e2e(tmp_path / "a.e2e", _series(), surname="EIAMD005")


def _by_key(result: dict) -> dict[str, dict]:
    return {s["seriesKey"]: s for s in result["series"]}


def test_response_shape_and_modality_mapping(e2e: Path) -> None:
    result = describe_e2e(e2e, "EIAMD005")
    assert set(result) == {"headerPatient", "headerLabel", "series"}
    s = _by_key(result)
    assert set(s["s0"]) == {
        "seriesKey", "seriesModality", "deviceModality", "scanPattern", "laterality",
        "acquiredAt", "frames", "width", "height", "octVolumeIndex",
    }
    assert (s["s0"]["seriesModality"], s["s0"]["deviceModality"], s["s0"]["scanPattern"]) == (
        "OCT", "IR", "OCT ART Volume")
    assert s["s1"]["seriesModality"] == "OCT_LINE"
    assert (s["s2"]["seriesModality"], s["s2"]["deviceModality"]) == ("QAF", "qAF")
    assert s["s3"]["seriesModality"] == "ICGA"
    assert (s["s4"]["seriesModality"], s["s4"]["deviceModality"]) == ("OTHER", "XYZ")
    assert s["s7"]["seriesModality"] == "IR"
    assert "s8" not in s  # a series without images is not reported


@pytest.mark.parametrize(
    ("token", "expected"),
    [("IR", "IR"), ("BAF", "BAF"), ("BR", "BR"), ("IRAF", "IRAF"), ("qAF", "QAF"),
     ("ICGA", "ICGA"), ("FA", "FA"), ("weird", "OTHER")],
)
def test_enface_device_modality_table(tmp_path: Path, token: str, expected: str) -> None:
    p = build_e2e(tmp_path / "m.e2e", [Series((1, 1, 1), "Images", token, "R", None,
                                              [Img(0, 0)])])
    assert describe_e2e(p, "x")["series"][0]["seriesModality"] == expected


def test_frames_dimensions_laterality_time_and_oct_index(e2e: Path) -> None:
    s = _by_key(describe_e2e(e2e, "EIAMD005"))
    assert (s["s0"]["frames"], s["s0"]["width"], s["s0"]["height"]) == (3, 8, 5)
    assert s["s0"]["laterality"] == "OD" and s["s0"]["acquiredAt"] == "2026-09-30T10:00:00"
    assert (s["s2"]["frames"], s["s2"]["width"], s["s2"]["height"]) == (3, 6, 4)  # time sequence
    assert s["s2"]["laterality"] == "OS"
    assert s["s4"]["laterality"] is None and s["s4"]["acquiredAt"] is None
    assert s["s1"]["frames"] == 1
    # multi-slice volumes come first, in directory order; single-image ones after
    assert (s["s0"]["octVolumeIndex"], s["s5"]["octVolumeIndex"], s["s1"]["octVolumeIndex"]) == (
        0, 1, 2)
    assert all(s[k]["octVolumeIndex"] is None for k in ("s2", "s3", "s4", "s6", "s7"))


def test_oct_volume_index_matches_oct_converter_ordering(e2e: Path) -> None:
    s = _by_key(describe_e2e(e2e, "EIAMD005"))
    volumes = E2E(str(e2e)).read_oct_volume()
    assert len(volumes) == 3
    for key in ("s0", "s5", "s1"):
        vol = volumes[s[key]["octVolumeIndex"]]
        assert vol.volume_id.endswith(f"_{int(key[1:]) + 1}")  # series id = ordinal + 1
        assert len(vol.volume) == s[key]["frames"]
        assert vol.volume[0].shape == (s[key]["height"], s[key]["width"])


def test_order_eye_then_modality_then_time(e2e: Path) -> None:
    series = describe_e2e(e2e, "EIAMD005")["series"]
    # OD: OCT, OCT_LINE, IR, ICGA (earlier s6 before later s3); OS: OCT, QAF; unknown eye: OTHER
    assert [x["seriesKey"] for x in series] == ["s0", "s1", "s7", "s6", "s3", "s5", "s2", "s4"]
    od = [x["seriesModality"] for x in series[:5]]
    assert od == sorted(od, key=SERIES_MODALITY_ORDER.index)


def test_series_keys_are_stable_unique_and_opaque(e2e: Path, tmp_path: Path) -> None:
    first = describe_e2e(e2e, "EIAMD005")
    again = describe_e2e(e2e, "other")
    assert [s["seriesKey"] for s in first["series"]] == [s["seriesKey"] for s in again["series"]]
    copy = tmp_path / "copy.e2e"  # same bytes at another path -> same keys
    copy.write_bytes(e2e.read_bytes())
    assert describe_e2e(copy, "EIAMD005") == first
    keys = [s["seriesKey"] for s in first["series"]]
    assert len(set(keys)) == len(keys) and all(k.startswith("s") and k[1:].isdigit() for k in keys)


@pytest.mark.parametrize(
    ("header", "label", "expected"),
    [(None, "EIAMD005", "empty"), ("", "EIAMD005", "empty"),
     ("EIAMD005", "EIAMD005", "matches"), ("eiamd005", "EIAMD005", "matches"),
     ("OTHER001", "EIAMD005", "differs"), ("EIAMD005", "", "none"),
     ("EIAMD005", None, "none"), (None, None, "none")],
)
def test_header_patient_comparison(tmp_path: Path, header, label, expected) -> None:
    p = build_e2e(tmp_path / "h.e2e", _series(), surname=header)
    assert describe_e2e(p, label)["headerPatient"] == expected


def test_header_patient_ignores_first_name_and_patient_id(tmp_path: Path) -> None:
    p = build_e2e(tmp_path / "f.e2e", _series(), surname="OTHER001",
                  first_name="EIAMD005", patient_id="EIAMD005")
    assert describe_e2e(p, "EIAMD005")["headerPatient"] == "differs"
    q = build_e2e(tmp_path / "g.e2e", _series(), surname="",
                  first_name="EIAMD005", patient_id="EIAMD005")
    assert describe_e2e(q, "EIAMD005")["headerPatient"] == "empty"
    assert describe_e2e(q)["headerLabel"] is None


@pytest.mark.parametrize(
    ("surname", "more", "expected"),
    [("EIAMD005", (), "EIAMD005"),
     ("EIAMD005", ("EIAMD005",), "EIAMD005"),
     ("EIAMD005", ("EIAMD006",), None),  # chunks disagree
     ("EIAMD005", ("",), None),  # one chunk empty
     ("", (), None),
     ("A" * 24, (), "A" * 24),
     ("A" * 25, (), None),  # too long
     ("EIAMD 005", (), None),  # space
     ("Mustermann, Max", (), None),  # not a label
     (None, (), None)],  # no patient chunk
)
def test_header_label(tmp_path: Path, surname, more, expected) -> None:
    p = build_e2e(tmp_path / "l.e2e", _series(), surname=surname, more_surnames=more)
    result = describe_e2e(p)
    assert result["headerLabel"] == expected
    assert result["headerPatient"] == "none"


def test_no_identifying_data_in_response(tmp_path: Path) -> None:
    p = build_e2e(tmp_path / "i.e2e", _series(), surname="EIAMD005",
                  first_name="SYNTHFIRST", patient_id="HDRID-9999")
    blob = json.dumps(describe_e2e(p, "EIAMD005"))
    for secret in ("HDRID-9999", "SYNTHFIRST", "SYNTHPID"):
        assert secret not in blob
    assert "EIAMD005" in blob  # only the vetted surname token (headerLabel)


def test_missing_file(tmp_path: Path) -> None:
    with pytest.raises(FileNotFoundError):
        describe_e2e(tmp_path / "nope.e2e", "x")


# ---- render_series_png ---------------------------------------------------------------


def _png(data: bytes) -> np.ndarray:
    return np.asarray(Image.open(io.BytesIO(data)))


def test_render_enface_frames(e2e: Path) -> None:
    for frame in range(3):
        arr = _png(render_series_png(e2e, "s2", frame))
        assert arr.shape == (4, 6) and (arr == 100 + frame).all()
    assert (_png(render_series_png(e2e, "s3")) == 70).all()  # frame defaults to 0


def test_render_oct_volume_gives_localizer_and_line_scan_gives_bscan(e2e: Path) -> None:
    assert (_png(render_series_png(e2e, "s0", 0)) == 50).all()  # IR localizer, not a B-scan
    bscan = _png(render_series_png(e2e, "s1", 0))
    assert bscan.shape == (5, 8) and bscan.dtype == np.uint8


def test_render_errors(e2e: Path) -> None:
    with pytest.raises(KeyError):
        render_series_png(e2e, "s99")
    with pytest.raises(KeyError):
        render_series_png(e2e, "../etc/passwd")
    for key, frame in (("s2", 3), ("s2", -1), ("s0", 1), ("s1", 1)):
        with pytest.raises(IndexError):
            render_series_png(e2e, key, frame)


def test_png_carries_no_metadata(e2e: Path) -> None:
    for key in ("s0", "s1", "s2"):
        data = render_series_png(e2e, key)
        assert data[:8] == b"\x89PNG\r\n\x1a\n"
        chunks = []
        pos = 8
        while pos < len(data):
            length = int.from_bytes(data[pos:pos + 4], "big")
            chunks.append(data[pos + 4:pos + 8])
            pos += 12 + length
        assert set(chunks) <= {b"IHDR", b"IDAT", b"IEND"}, chunks
        assert Image.open(io.BytesIO(data)).info == {}
