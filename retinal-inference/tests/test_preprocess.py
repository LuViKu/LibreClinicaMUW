"""DR-022 app-VM-side ``POST /preprocess`` endpoint.

Stubs ``prepare_bscan_dcm`` so the test doesn't need a real Heidelberg ``.e2e``
or ``oct-converter``; the focus is the endpoint shell — gating, auth, and that
the synthesized ``bscan.dcm`` bytes are streamed back as ``application/dicom``.
"""

from __future__ import annotations

from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from retinal_inference import config as _config
from retinal_inference.api import preprocess as preprocess_module


@pytest.fixture(autouse=True)
def _enable_endpoint(monkeypatch, tmp_path):
    monkeypatch.setattr(_config.settings, "preprocess_endpoint_enabled", True, raising=False)
    monkeypatch.setattr(_config.settings, "auth_token", "secret-test-token", raising=False)
    monkeypatch.setattr(_config.settings, "shared_tmpdir", tmp_path / "shared-tmp", raising=False)
    yield


@pytest.fixture
def client(monkeypatch):
    # Fake the E2E->DICOM conversion: write a DICOM-looking blob (Part-10 magic)
    # to out_dir/bscan.dcm and return out_dir, matching prepare_bscan_dcm's shape.
    def fake_prepare(e2e_path: Path, out_dir: Path) -> Path:
        out_dir = Path(out_dir)
        out_dir.mkdir(parents=True, exist_ok=True)
        (out_dir / "bscan.dcm").write_bytes(b"\x00" * 128 + b"DICM" + b"FAKE-DICOM")
        return out_dir

    monkeypatch.setattr(preprocess_module, "prepare_bscan_dcm", fake_prepare)
    from retinal_inference.main import app

    with TestClient(app) as c:
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
