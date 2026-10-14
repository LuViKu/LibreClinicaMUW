"""An OCT DICOM written by ``convert_series`` goes through the DICOM path of ``/preprocess`` and
comes out the way the ``.e2e`` path would have given it (frames, spacing, eye, date, device).

Synthetic E2E only (the converter package's own test builder); no stubbing of the conversion.
"""

from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import pydicom
import pytest
from fastapi.testclient import TestClient

pytest.importorskip("muw_e2e_converter")

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "muw-e2e-converter" / "tests"))
from datetime import datetime  # noqa: E402

from convert_synth import Item, Ser, build  # noqa: E402

from muw_e2e_converter import convert_series, describe_e2e, prepare_bscan_dcm  # noqa: E402
from retinal_inference import config as _config  # noqa: E402

TOKEN = {"X-MUW-Inference-Token": "secret-test-token"}
UUID = "0f8fad5b-d9cb-469f-a165-70867728950e"
LABEL = "EIAMD005"
T = datetime


@pytest.fixture(autouse=True)
def _enable(monkeypatch, tmp_path):
    monkeypatch.setattr(_config.settings, "preprocess_endpoint_enabled", True, raising=False)
    monkeypatch.setattr(_config.settings, "auth_token", "secret-test-token", raising=False)
    monkeypatch.setattr(_config.settings, "shared_tmpdir", tmp_path / "shared", raising=False)
    monkeypatch.setattr(_config.settings, "bscan_store", str(tmp_path / "store"), raising=False)


@pytest.fixture
def client():
    from retinal_inference.main import app

    with TestClient(app) as c:
        yield c


def _e2e(tmp_path: Path) -> Path:
    return build(tmp_path / "x.e2e", [
        Ser((1, 1, 1), "OCT ART Volume", "IR", "R", T(2026, 9, 30, 10, 0, 0),
            [Item(1, 2 * i, 12, 20, 10 + i) for i in range(5)] + [Item(0, 0, 16, 16, 1)],
            geometry=True),
        Ser((1, 1, 6), "OCT ART Volume", "IR", "L", T(2026, 9, 30, 10, 30, 0),
            [Item(1, 2 * i, 12, 20, 50 + i) for i in range(4)] + [Item(0, 0, 16, 16, 3)],
            geometry=True),
    ])


def _post(client, data: bytes, **form):
    return client.post("/preprocess", headers=TOKEN, data={"e2e_uuid": UUID, **form},
                       files={"file": ("scan.dcm", data, "application/dicom")})


def test_converted_oct_dicom_preprocesses_like_the_e2e_path(client, tmp_path) -> None:
    e2e = _e2e(tmp_path)
    series = [s for s in describe_e2e(e2e)["series"] if s["seriesModality"] == "OCT"]
    assert len(series) == 2
    out = tmp_path / "convert"
    convert_series(e2e, LABEL, [s["seriesKey"] for s in series], out)

    for s in series:
        # the DICOM path
        dcm = (out / f"{s['seriesKey']}.dcm").read_bytes()
        r = _post(client, dcm)
        assert r.status_code == 200, r.text
        # the .e2e path, as the app has used it until now
        ref_dir = tmp_path / f"ref{s['octVolumeIndex']}"
        prepare_bscan_dcm(e2e, ref_dir, scan_index=s["octVolumeIndex"])
        ref = pydicom.dcmread(str(ref_dir / "bscan.dcm"))
        got = pydicom.dcmread(__import__("io").BytesIO(r.content))

        assert r.headers["X-MUW-Source-Format"] == "dicom"
        assert r.headers["X-MUW-Laterality"] == s["laterality"]
        assert r.headers["X-MUW-Bscan-Dim-Z"] == str(s["frames"]) == str(int(ref.NumberOfFrames))
        assert (r.headers["X-MUW-Bscan-Dim-Y"], r.headers["X-MUW-Bscan-Dim-X"]) == (
            str(s["height"]), str(s["width"]))
        assert r.headers["X-MUW-Spacing-Order"] == "standard"
        assert r.headers["X-MUW-Acquisition-Date"] == s["acquiredAt"][:10]
        assert float(r.headers["X-MUW-Pixel-Axial-Mm"]) == pytest.approx(ref.PixelSpacing[0], rel=1e-6)
        assert float(r.headers["X-MUW-Pixel-Lateral-Mm"]) == pytest.approx(ref.PixelSpacing[1], rel=1e-6)
        assert "heidelberg" in r.headers["X-MUW-Manufacturer"].lower()
        assert r.headers["X-MUW-Device-Tasks"]  # a Heidelberg volume is analysable
        # same pixels and same de-identified shape on the way out
        assert np.array_equal(np.frombuffer(got.PixelData, np.uint16),
                              np.frombuffer(ref.PixelData, np.uint16))
        assert str(got.PatientID) == "" and str(got.PatientName) == ""
        assert str(got.Laterality) == str(ref.Laterality)


def test_geometry_json_of_the_dicom_path_has_the_volume_geometry(client, tmp_path) -> None:
    import json

    e2e = _e2e(tmp_path)
    s = next(x for x in describe_e2e(e2e)["series"] if x["seriesModality"] == "OCT")
    out = tmp_path / "convert"
    [res] = convert_series(e2e, LABEL, [s["seriesKey"]], out)
    assert _post(client, (out / res["file"]).read_bytes()).status_code == 200
    geo = json.loads((tmp_path / "store" / UUID / "geometry.json").read_text())
    companion = json.loads((out / res["geometryFile"]).read_text())
    assert geo["source_format"] == "dicom"
    for k in ("dim_x_ascans", "dim_y_rows", "dim_z_bscans"):
        assert geo["bscan"][k] == companion["bscan"][k]
    assert geo["bscan"]["pixel_axial_mm"] == pytest.approx(companion["bscan"]["pixel_axial_mm"], rel=1e-6)
    assert geo["bscan"]["pixel_lateral_mm"] == pytest.approx(
        companion["bscan"]["pixel_lateral_mm"], rel=1e-6)


def test_enface_and_line_scan_dicoms_are_not_oct_volumes_for_preprocess(client, tmp_path) -> None:
    e2e = build(tmp_path / "y.e2e", [
        Ser((1, 1, 1), "Images", "IR", "R", T(2026, 9, 30, 10, 0, 0), [Item(0, 0, 16, 16, 1)]),
        Ser((1, 1, 2), "OCT B-Scan", "IR", "R", T(2026, 9, 30, 10, 5, 0),
            [Item(1, 0, 12, 20, 9)], geometry=True),
    ])
    keys = [s["seriesKey"] for s in describe_e2e(e2e)["series"]]
    out = tmp_path / "convert"
    for res in convert_series(e2e, LABEL, keys, out):
        r = _post(client, (out / res["file"]).read_bytes())
        assert r.status_code == 422
        assert r.json()["detail"]["error"] == "not_oct_volume"
