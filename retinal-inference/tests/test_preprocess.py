"""DR-022 app-VM-side ``POST /preprocess`` endpoint.

Stubs ``prepare_bscan_dcm`` so the test doesn't need a real Heidelberg ``.e2e``
or ``oct-converter``; the focus is the endpoint shell — gating, auth, that the
synthesized ``bscan.dcm`` bytes are streamed back as ``application/dicom``, the
7 geometry headers, and (when ``bscan_store`` is set) the companion-file
persistence + dedup contract.
"""

from __future__ import annotations

import json
import time
from pathlib import Path

import pydicom
import pytest
from fastapi.testclient import TestClient

from retinal_inference import config as _config
from retinal_inference.api import preprocess as preprocess_module


def _fake_dcm_bytes() -> bytes:
    """Synthesise a minimal valid Part-10 DICOM matching ``bscan_metadata`` shape."""
    from pydicom.dataset import Dataset, FileMetaDataset
    from pydicom.uid import ExplicitVRLittleEndian, generate_uid

    fm = FileMetaDataset()
    fm.MediaStorageSOPClassUID = "1.2.840.10008.5.1.4.1.1.7.2"
    fm.MediaStorageSOPInstanceUID = generate_uid()
    fm.TransferSyntaxUID = ExplicitVRLittleEndian
    ds = Dataset()
    ds.file_meta = fm
    ds.SOPClassUID = "1.2.840.10008.5.1.4.1.1.7.2"
    ds.SOPInstanceUID = fm.MediaStorageSOPInstanceUID
    ds.Modality = "OPT"
    ds.SamplesPerPixel = 1
    ds.PhotometricInterpretation = "MONOCHROME2"
    ds.NumberOfFrames = "3"
    ds.Rows = 4
    ds.Columns = 5
    ds.BitsAllocated = 8
    ds.BitsStored = 8
    ds.HighBit = 7
    ds.PixelRepresentation = 0
    ds.PixelSpacing = [0.00387, 0.01148]
    ds.SpacingBetweenSlices = 0.05
    ds.PixelData = (b"\x00" * (3 * 4 * 5))

    import io as _io

    buf = _io.BytesIO()
    pydicom.dcmwrite(buf, ds, enforce_file_format=True)
    return buf.getvalue()


class _FakeBscanVolume:
    axial_mm = 0.00387
    lateral_mm = 0.01148
    slice_mm = 0.05
    laterality = "OD"
    n_bscans = 3
    rows = 4
    cols = 5
    # 2026-06-25 — preprocess.py grew an optional ``acquisition_date``
    # surface (header + companion field); the fixture has to set None
    # explicitly so attribute access doesn't AttributeError.
    acquisition_date = None
    bscan_data = [
        {"posX1": 100.0, "posY1": 200.0, "posX2": 500.0, "posY2": 200.0,
         "imgSizeX": 5, "scaley": 0.00387, "centrePosY": 195.0},
        {"posX1": 100.0, "posY1": 250.0, "posX2": 500.0, "posY2": 250.0,
         "imgSizeX": 5, "scaley": 0.00387, "centrePosY": 250.0},
        {"posX1": 100.0, "posY1": 300.0, "posX2": 500.0, "posY2": 300.0,
         "imgSizeX": 5, "scaley": 0.00387, "centrePosY": 305.0},
    ]


@pytest.fixture(autouse=True)
def _enable_endpoint(monkeypatch, tmp_path):
    monkeypatch.setattr(_config.settings, "preprocess_endpoint_enabled", True, raising=False)
    monkeypatch.setattr(_config.settings, "auth_token", "secret-test-token", raising=False)
    monkeypatch.setattr(_config.settings, "shared_tmpdir", tmp_path / "shared-tmp", raising=False)
    # Default: no store. Tests opt in.
    monkeypatch.setattr(_config.settings, "bscan_store", None, raising=False)
    yield


@pytest.fixture
def fake_dcm_payload():
    return _fake_dcm_bytes()


@pytest.fixture
def client(monkeypatch, fake_dcm_payload):
    captured: dict = {"prepare": [], "read_volume": [], "extract_fundus": []}

    def fake_prepare(e2e_path: Path, out_dir: Path, scan_index: int = 0) -> Path:
        captured["prepare"].append(scan_index)
        out_dir = Path(out_dir)
        out_dir.mkdir(parents=True, exist_ok=True)
        (out_dir / "bscan.dcm").write_bytes(fake_dcm_payload)
        return out_dir

    def fake_read_volume(e2e_path: Path, scan_index: int = 0):  # noqa: ARG001
        captured["read_volume"].append(scan_index)
        return _FakeBscanVolume()

    def fake_extract_fundus(e2e_path: Path, scan_index: int = 0):  # noqa: ARG001
        captured["extract_fundus"].append(scan_index)
        # Synthesise a 1-byte PNG-ish placeholder + size; the endpoint only cares
        # about non-empty bytes + dims for the geometry block.
        return b"\x89PNG\r\n\x1a\nFAKE", (768, 768)

    monkeypatch.setattr(preprocess_module, "prepare_bscan_dcm", fake_prepare)
    monkeypatch.setattr(preprocess_module, "read_e2e_volume", fake_read_volume)
    monkeypatch.setattr(preprocess_module, "extract_fundus_png", fake_extract_fundus)
    from retinal_inference.main import app

    with TestClient(app) as c:
        c.captured = captured  # type: ignore[attr-defined]
        yield c


def _multipart():
    return (
        {"file": ("input.e2e", b"E2E-FAKE-BINARY", "application/octet-stream")},
        {"laterality": "OD"},
    )


def test_preprocess_happy_path_returns_dicom(client) -> None:
    files, data = _multipart()
    headers = {"X-MUW-Inference-Token": "secret-test-token"}
    r = client.post("/preprocess", files=files, data=data, headers=headers)
    assert r.status_code == 200, r.text
    assert r.headers["content-type"] == "application/dicom"
    # The body is the synthesized bscan.dcm (carries the DICOM Part-10 magic).
    assert r.content[128:132] == b"DICM"


def test_preprocess_missing_token_returns_401(client) -> None:
    files, data = _multipart()
    r = client.post("/preprocess", files=files, data=data)
    assert r.status_code == 401


def test_preprocess_wrong_token_returns_401(client) -> None:
    files, data = _multipart()
    headers = {"X-MUW-Inference-Token": "nope"}
    r = client.post("/preprocess", files=files, data=data, headers=headers)
    assert r.status_code == 401


def test_preprocess_disabled_returns_404(client, monkeypatch) -> None:
    monkeypatch.setattr(_config.settings, "preprocess_endpoint_enabled", False, raising=False)
    files, data = _multipart()
    headers = {"X-MUW-Inference-Token": "secret-test-token"}
    r = client.post("/preprocess", files=files, data=data, headers=headers)
    assert r.status_code == 404


def test_preprocess_empty_file_returns_400(client) -> None:
    files = {"file": ("input.e2e", b"", "application/octet-stream")}
    headers = {"X-MUW-Inference-Token": "secret-test-token"}
    r = client.post("/preprocess", files=files, data={"laterality": "OD"}, headers=headers)
    assert r.status_code == 400


# --- new: geometry headers + companion-file persistence ----------------------


def test_preprocess_emits_geometry_headers(client) -> None:
    files, data = _multipart()
    headers = {"X-MUW-Inference-Token": "secret-test-token"}
    r = client.post("/preprocess", files=files, data=data, headers=headers)
    assert r.status_code == 200, r.text
    # All 7 X-MUW headers must be present with sensible values.
    axial = float(r.headers["X-MUW-Pixel-Axial-Mm"])
    lateral = float(r.headers["X-MUW-Pixel-Lateral-Mm"])
    slice_mm = float(r.headers["X-MUW-Pixel-Slice-Mm"])
    assert 0.001 <= axial <= 0.01, axial
    assert 0.005 <= lateral <= 0.02, lateral
    assert 0.01 <= slice_mm <= 0.2, slice_mm
    assert int(r.headers["X-MUW-Bscan-Dim-Z"]) == 3
    assert int(r.headers["X-MUW-Bscan-Dim-Y"]) == 4
    assert int(r.headers["X-MUW-Bscan-Dim-X"]) == 5
    assert r.headers["X-MUW-E2E-Uuid"]  # any non-empty string
    # CORS expose header lists at least one X-MUW header.
    exposed = r.headers["Access-Control-Expose-Headers"]
    assert "X-MUW-Pixel-Axial-Mm" in exposed
    assert "X-MUW-E2E-Uuid" in exposed


def test_preprocess_persists_companion_files(client, monkeypatch, tmp_path) -> None:
    store = tmp_path / "bscan-store"
    monkeypatch.setattr(_config.settings, "bscan_store", store, raising=False)

    files, data = _multipart()
    headers = {"X-MUW-Inference-Token": "secret-test-token"}
    r = client.post("/preprocess", files=files, data=data, headers=headers)
    assert r.status_code == 200, r.text

    uuid = r.headers["X-MUW-E2E-Uuid"]
    bdir = store / uuid
    assert (bdir / "bscan.dcm").is_file()
    assert (bdir / "fundus.png").is_file()
    geom_path = bdir / "geometry.json"
    assert geom_path.is_file()

    geom = json.loads(geom_path.read_text(encoding="utf-8"))
    # Shape contract — keep tight enough to catch drift.
    assert set(geom.keys()) >= {
        "fundus", "bscan", "bscan_positions_fundus_px",
        "scan_bbox_fundus_px", "fovea_estimate_fundus_px",
    }
    assert geom["fundus"]["width_px"] == 768
    assert geom["fundus"]["height_px"] == 768
    assert geom["bscan"]["dim_z_bscans"] == 3
    assert geom["bscan"]["dim_x_ascans"] == 5
    assert len(geom["bscan_positions_fundus_px"]) == 3
    p0 = geom["bscan_positions_fundus_px"][0]
    assert {"z", "x1", "y1", "x2", "y2"} <= set(p0.keys())
    bbox = geom["scan_bbox_fundus_px"]
    assert {"x", "y", "width", "height"} <= set(bbox.keys())
    fovea = geom["fovea_estimate_fundus_px"]
    assert fovea["source"] == "volume-center-mvp"
    assert fovea["bscan_z"] == 1
    assert fovea["ascan_x"] == 2
    assert geom["source_format"] == "e2e"


def test_preprocess_e2e_reports_source_format_and_device(client, monkeypatch, fake_dcm_payload) -> None:
    import io as _io

    ds = pydicom.dcmread(_io.BytesIO(fake_dcm_payload))
    ds.Manufacturer = "Heidelberg Retina Angiograph"
    ds.ManufacturerModelName = "SPECTRALISHX202"
    buf = _io.BytesIO()
    ds.save_as(buf, enforce_file_format=True)
    payload = buf.getvalue()

    def fake_prepare(e2e_path, out_dir, scan_index=0):
        (Path(out_dir) / "bscan.dcm").write_bytes(payload)
        return Path(out_dir)

    monkeypatch.setattr(preprocess_module, "prepare_bscan_dcm", fake_prepare)
    files, data = _multipart()
    r = client.post("/preprocess", files=files, data=data,
                    headers={"X-MUW-Inference-Token": "secret-test-token"})
    assert r.status_code == 200, r.text
    assert r.headers["X-MUW-Source-Format"] == "e2e"
    assert r.headers["X-MUW-Manufacturer"] == "Heidelberg Retina Angiograph"
    assert r.headers["X-MUW-Manufacturer-Model"] == "SPECTRALISHX202"
    assert "fluid" in r.headers["X-MUW-Device-Tasks"].split(",")


def test_preprocess_dedups_on_resubmit(client, monkeypatch, tmp_path) -> None:
    store = tmp_path / "bscan-store"
    monkeypatch.setattr(_config.settings, "bscan_store", store, raising=False)

    files, data = _multipart()
    headers = {"X-MUW-Inference-Token": "secret-test-token"}
    r1 = client.post("/preprocess", files=files, data=data, headers=headers)
    assert r1.status_code == 200

    uuid = r1.headers["X-MUW-E2E-Uuid"]
    dcm_path = store / uuid / "bscan.dcm"
    first_mtime = dcm_path.stat().st_mtime_ns

    # mtime nanosecond resolution differs by OS; sleep then re-POST.
    time.sleep(0.05)
    files2, data2 = _multipart()
    r2 = client.post("/preprocess", files=files2, data=data2, headers=headers)
    assert r2.status_code == 200
    second_mtime = dcm_path.stat().st_mtime_ns

    assert first_mtime == second_mtime, (
        "bscan.dcm mtime must not change on dedup-skip "
        f"(first={first_mtime} second={second_mtime})"
    )


def test_preprocess_skips_store_when_unset(client, tmp_path) -> None:
    # Default fixture leaves bscan_store=None; no store dir should be created.
    files, data = _multipart()
    headers = {"X-MUW-Inference-Token": "secret-test-token"}
    r = client.post("/preprocess", files=files, data=data, headers=headers)
    assert r.status_code == 200, r.text
    assert r.headers["content-type"] == "application/dicom"
    # Headers still present.
    assert r.headers["X-MUW-E2E-Uuid"]
    assert r.headers["X-MUW-Pixel-Axial-Mm"]


def test_preprocess_scan_index_defaults_to_zero(client) -> None:
    files, data = _multipart()
    headers = {"X-MUW-Inference-Token": "secret-test-token"}
    r = client.post("/preprocess", files=files, data=data, headers=headers)
    assert r.status_code == 200, r.text
    # All three downstream helpers receive scan_index=0 by default.
    assert client.captured["prepare"][-1] == 0  # type: ignore[attr-defined]
    assert client.captured["read_volume"][-1] == 0  # type: ignore[attr-defined]


def test_preprocess_scan_index_threaded(client, monkeypatch, tmp_path) -> None:
    # Need a store so extract_fundus_png gets called too.
    store = tmp_path / "bscan-store"
    monkeypatch.setattr(_config.settings, "bscan_store", store, raising=False)

    files, data = _multipart()
    data["scan_index"] = 2
    headers = {"X-MUW-Inference-Token": "secret-test-token"}
    r = client.post("/preprocess", files=files, data=data, headers=headers)
    assert r.status_code == 200, r.text
    # All three downstream helpers receive scan_index=2.
    assert client.captured["prepare"][-1] == 2  # type: ignore[attr-defined]
    assert client.captured["read_volume"][-1] == 2  # type: ignore[attr-defined]
    assert client.captured["extract_fundus"][-1] == 2  # type: ignore[attr-defined]


def test_preprocess_scan_index_uses_subdir_for_nonzero(client, monkeypatch, tmp_path) -> None:
    """scan_index > 0 writes to <e2eUuid>/scan-<n>/ so volumes don't overwrite."""
    store = tmp_path / "bscan-store"
    monkeypatch.setattr(_config.settings, "bscan_store", store, raising=False)

    files, data = _multipart()
    data["scan_index"] = 1
    headers = {"X-MUW-Inference-Token": "secret-test-token"}
    r = client.post("/preprocess", files=files, data=data, headers=headers)
    assert r.status_code == 200, r.text

    uuid = r.headers["X-MUW-E2E-Uuid"]
    # The per-scan subdir holds the companion files; the root <uuid>/bscan.dcm
    # is NOT written so a later scan-0 upload still dedups cleanly.
    assert (store / uuid / "scan-1" / "bscan.dcm").is_file()
    assert (store / uuid / "scan-1" / "fundus.png").is_file()
    assert not (store / uuid / "bscan.dcm").exists()


def test_preprocess_negative_scan_index_returns_400(client) -> None:
    files, data = _multipart()
    data["scan_index"] = -1
    headers = {"X-MUW-Inference-Token": "secret-test-token"}
    r = client.post("/preprocess", files=files, data=data, headers=headers)
    assert r.status_code == 400


def test_preprocess_out_of_range_scan_index_returns_400(client, monkeypatch) -> None:
    def raising_prepare(e2e_path, out_dir, scan_index=0):
        raise IndexError(f"scan_index {scan_index} out of range; file has 2 volumes")

    monkeypatch.setattr(preprocess_module, "prepare_bscan_dcm", raising_prepare)

    files, data = _multipart()
    data["scan_index"] = 5
    headers = {"X-MUW-Inference-Token": "secret-test-token"}
    r = client.post("/preprocess", files=files, data=data, headers=headers)
    assert r.status_code == 400
    assert "out of range" in r.json()["detail"]


# ---------- e2e_uuid / laterality validation ----------------------------------
#
# e2e_uuid names the companion directory under the B-scan store and both fields
# reach the log. The app only ever sends its own lower-case UUID and OD/OS.

_APP_UUID = "3f2c9a4e-1b7d-4c2e-9a51-0e8f6d4b2a17"
_TOKEN_HEADERS = {"X-MUW-Inference-Token": "secret-test-token"}


def _files():
    return {"file": ("input.e2e", b"E2E-FAKE-BINARY", "application/octet-stream")}


@pytest.mark.parametrize("app_uuid", [_APP_UUID, "00000000-0000-4000-8000-00000000000a"])
def test_preprocess_uses_the_apps_uuid_for_the_companion_dir(
    client, monkeypatch, tmp_path, app_uuid
) -> None:
    store = tmp_path / "bscan-store"
    monkeypatch.setattr(_config.settings, "bscan_store", store, raising=False)

    r = client.post(
        "/preprocess",
        files=_files(),
        data={"laterality": "OD", "e2e_uuid": f" {app_uuid} "},
        headers=_TOKEN_HEADERS,
    )
    assert r.status_code == 200, r.text
    # Exactly the app's UUID (leading zeros kept), so the app finds the files.
    assert r.headers["X-MUW-E2E-Uuid"] == app_uuid
    assert (store / app_uuid / "bscan.dcm").is_file()
    assert (store / app_uuid / "geometry.json").is_file()


@pytest.mark.parametrize(
    "bad_uuid",
    [
        "../escaped",
        "../../escaped",
        "sub/dir",
        "/tmp/absolute",
        "3F2C9A4E-1B7D-4C2E-9A51-0E8F6D4B2A17",  # the app never sends upper case
        "3f2c9a4e1b7d4c2e9a510e8f6d4b2a17",  # no hyphens
        _APP_UUID + "\nforged log line",
        "not-a-uuid",
    ],
)
def test_preprocess_refuses_an_e2e_uuid_that_is_not_a_uuid(
    client, monkeypatch, tmp_path, bad_uuid
) -> None:
    store = tmp_path / "store-root" / "bscans"
    monkeypatch.setattr(_config.settings, "bscan_store", store, raising=False)

    r = client.post(
        "/preprocess",
        files=_files(),
        data={"laterality": "OD", "e2e_uuid": bad_uuid},
        headers=_TOKEN_HEADERS,
    )
    assert r.status_code == 400, r.text
    assert "e2e_uuid" in r.json()["detail"]
    # Refused before any work: no conversion ran, nothing was written anywhere.
    assert client.captured["prepare"] == []
    assert not (tmp_path / "store-root" / "escaped").exists()
    assert not (tmp_path / "escaped").exists()
    assert not store.exists() or not any(store.rglob("bscan.dcm"))


def test_preprocess_does_not_follow_a_symlinked_entry_out_of_the_store(
    client, monkeypatch, tmp_path
) -> None:
    store = tmp_path / "bscan-store"
    store.mkdir()
    outside = tmp_path / "outside"
    outside.mkdir()
    (store / _APP_UUID).symlink_to(outside, target_is_directory=True)
    monkeypatch.setattr(_config.settings, "bscan_store", store, raising=False)

    r = client.post(
        "/preprocess",
        files=_files(),
        data={"laterality": "OD", "e2e_uuid": _APP_UUID},
        headers=_TOKEN_HEADERS,
    )
    # The conversion result still comes back; only persistence is skipped.
    assert r.status_code == 200, r.text
    assert list(outside.iterdir()) == []


@pytest.mark.parametrize("bad_laterality", ["OU", "XX", "OD\r\nforged log line", "left"])
def test_preprocess_refuses_an_unknown_laterality(client, bad_laterality) -> None:
    r = client.post(
        "/preprocess",
        files=_files(),
        data={"laterality": bad_laterality},
        headers=_TOKEN_HEADERS,
    )
    assert r.status_code == 400, r.text
    assert "laterality" in r.json()["detail"]
    assert client.captured["prepare"] == []


@pytest.mark.parametrize("laterality", ["OD", "OS", "od", " OS "])
def test_preprocess_accepts_od_and_os(client, laterality) -> None:
    r = client.post(
        "/preprocess", files=_files(), data={"laterality": laterality}, headers=_TOKEN_HEADERS
    )
    assert r.status_code == 200, r.text


def test_preprocess_laterality_and_uuid_stay_optional(client) -> None:
    r = client.post("/preprocess", files=_files(), headers=_TOKEN_HEADERS)
    assert r.status_code == 200, r.text
    # Without an app UUID the digest-derived one is used, as before.
    assert len(r.headers["X-MUW-E2E-Uuid"]) == 36
