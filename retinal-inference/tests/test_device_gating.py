"""Vendor gating: a task runs only on the devices its model was trained on."""

from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from retinal_inference import config as _config
from retinal_inference import devices
from retinal_inference.api import run as run_module
from retinal_inference.inference.adapter import reset_adapter
from retinal_inference.models.responses import FullVolumeResult
from retinal_inference.tasks import SUPPORTED_TASKS
from tests.oct_dicom_factory import make_opt

TOKEN = {"X-MUW-Inference-Token": "secret-test-token"}


@pytest.fixture
def client(monkeypatch, tmp_path):
    monkeypatch.setattr(_config.settings, "run_endpoint_enabled", True, raising=False)
    monkeypatch.setattr(_config.settings, "auth_token", "secret-test-token", raising=False)
    monkeypatch.setattr(_config.settings, "shared_tmpdir", tmp_path / "shared", raising=False)
    run_module._idempotency_cache.clear()
    reset_adapter()
    calls: list[str] = []

    class _Fake:
        def supports(self, task):
            return True

        def full_volume(self, task, path, laterality, out_dir_override=None, scan_index=0):
            calls.append(task)
            return FullVolumeResult(
                task=task, primary_metric_value=None, primary_metric_unit=None,
                output_payload={}, en_face_mask_path=None, bscan_masks_dir=None,
                pixel_scale_mm=0.0039, confidence=0.9, model_version="fake",
            )

    monkeypatch.setattr(run_module, "get_adapter", lambda: _Fake())
    from retinal_inference.main import app

    with TestClient(app) as c:
        c.calls = calls  # type: ignore[attr-defined]
        yield c
    run_module._idempotency_cache.clear()


def _post(client, body: bytes, task: str = "fluid", filename: str = "bscan.dcm"):
    return client.post(
        "/run",
        files={"file": (filename, body, "application/dicom")},
        data={"task": task, "laterality": "OD"},
        headers=TOKEN,
    )


# --- the declaration ----------------------------------------------------------


def test_every_task_declares_its_devices():
    assert set(devices.TASK_DEVICES) == SUPPORTED_TASKS


@pytest.mark.parametrize(
    ("manufacturer", "model", "ok"),
    [
        ("Heidelberg Engineering", "Spectralis", True),
        ("HEIDELBERG ENGINEERING", "SPECTRALIS", True),
        ("Heidelberg Retina Angiograph", "SPECTRALISHX202", True),  # .e2e-derived
        ("Heidelberg Engineering", None, True),  # .e2e without a model string
        ("Heidelberg Retina Angiograph", "HRA2", True),
        ("Heidelberg Engineering", "ANTERION", False),  # anterior segment OCT
        ("Carl Zeiss Meditec", "CIRRUS HD-OCT 5000", False),
        ("Topcon", "Maestro2", False),
        (None, "Spectralis", False),
        ("", None, False),
    ],
)
def test_spectralis_matching(manufacturer, model, ok):
    assert devices.device_supported("fluid", manufacturer, model) is ok


# --- /run ---------------------------------------------------------------------


@pytest.mark.parametrize("task", sorted(SUPPORTED_TASKS))
def test_run_accepts_a_spectralis_dicom_for_every_task(client, task):
    r = _post(client, make_opt(), task=task)
    assert r.status_code == 200, r.text
    assert client.calls == [task]


def test_run_refuses_another_vendor_with_unsupported_device(client):
    r = _post(client, make_opt(manufacturer="Carl Zeiss Meditec", model="CIRRUS HD-OCT 5000"))
    assert r.status_code == 422, r.text
    detail = r.json()["detail"]
    assert detail["error"] == "unsupported_device"
    assert detail["task"] == "fluid"
    assert detail["manufacturer"] == "Carl Zeiss Meditec"
    assert "fluid" in detail["message"] and "Carl Zeiss Meditec" in detail["message"]
    assert client.calls == []  # no numbers produced


def test_run_refuses_a_heidelberg_anterion(client):
    r = _post(client, make_opt(model="ANTERION"), task="sdretinanet")
    assert r.status_code == 422
    assert r.json()["detail"]["error"] == "unsupported_device"


def test_run_refuses_a_dicom_without_manufacturer(client):
    r = _post(client, make_opt(manufacturer=None))
    assert r.status_code == 422
    assert r.json()["detail"]["error"] == "unsupported_device"


def test_run_detects_dicom_by_magic_whatever_the_name(client):
    r = _post(client, make_opt(manufacturer="Topcon"), filename="upload.bin")
    assert r.status_code == 422
    assert r.json()["detail"]["error"] == "unsupported_device"


def test_run_refuses_an_unreadable_dicom(client):
    r = _post(client, b"\x00" * 128 + b"DICM" + b"x")
    assert r.status_code == 422
    assert r.json()["detail"]["error"] == "invalid_dicom"
    assert client.calls == []


def test_run_leaves_e2e_inputs_alone(client):
    # .e2e is always a Heidelberg Spectralis export; there is no header to gate on.
    r = _post(client, b"E2E-FAKE-BINARY", filename="input.e2e")
    assert r.status_code == 200, r.text


# --- /health ------------------------------------------------------------------


def test_health_publishes_the_task_devices(app_client):
    body = app_client.get("/health").json()
    td = body["task_devices"]
    assert set(td) == SUPPORTED_TASKS
    assert td["fluid"] == {
        "label": "Heidelberg Engineering Spectralis",
        "manufacturers": ["heidelberg"],
        "models": ["spectralis", "hra"],
        "model_required": False,
    }
