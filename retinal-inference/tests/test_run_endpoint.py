"""DR-022 stateless ``POST /run`` endpoint.

The tests stub ``OptimaAdapter.full_volume`` so we don't depend on a real E2E
parser or runner subprocess; the focus is on the endpoint shell:

* auth header gating
* multipart input validation
* idempotency-key caching
* tempdir cleanup
* envelope shape (artifacts + payload rewrite)
* errors surface from the adapter cleanly
"""

from __future__ import annotations

import os
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from retinal_inference import config as _config
from retinal_inference.api import run as run_module
from retinal_inference.inference.adapter import reset_adapter
from retinal_inference.models.responses import FullVolumeResult


# ---------- fixtures ----------------------------------------------------------


@pytest.fixture(autouse=True)
def _enable_endpoint(monkeypatch, tmp_path):
    """Enable /run + set a fake auth token + isolate the shared tempdir.

    Cache state is module-level on ``run_module``; tests reset it between runs
    so the LRU doesn't bleed cached envelopes across cases.
    """
    monkeypatch.setattr(_config.settings, "run_endpoint_enabled", True, raising=False)
    monkeypatch.setattr(_config.settings, "auth_token", "secret-test-token", raising=False)
    monkeypatch.setattr(
        _config.settings, "shared_tmpdir", tmp_path / "shared-tmp", raising=False
    )
    run_module._idempotency_cache.clear()
    reset_adapter()
    yield
    run_module._idempotency_cache.clear()
    reset_adapter()


@pytest.fixture
def client(monkeypatch):
    # Stub the adapter's supports/full_volume so the endpoint doesn't try to
    # parse a real E2E or call an HTTP runner. The stub also writes a fake
    # artifact into the tempdir so the collector exercises a non-empty path.
    from retinal_inference.api import run as run_mod

    def fake_get_adapter():
        class _Fake:
            def supports(self, task: str) -> bool:
                return task in ("fluid", "onl", "pr", "ga")

            def full_volume(self, task, e2e_path, laterality, out_dir_override=None):
                # The endpoint passes the tempdir as out_dir_override; drop a
                # fake CSV inside so the artifact collector picks it up.
                assert out_dir_override is not None
                (out_dir_override / "fake-output.csv").write_text("a,b\n1,2\n")
                # Server returns raw artifacts only; the metric is None.
                return FullVolumeResult(
                    task=task,
                    primary_metric_value=None,
                    primary_metric_unit=None,
                    output_payload={
                        "csv_path": str(out_dir_override / "fake-output.csv"),
                    },
                    en_face_mask_path=None,
                    bscan_masks_dir=str(out_dir_override),
                    pixel_scale_mm=0.011,
                    confidence=0.9,
                    model_version="test-fluid-1.0",
                )

        return _Fake()

    monkeypatch.setattr(run_mod, "get_adapter", fake_get_adapter)
    from retinal_inference.main import app

    with TestClient(app) as c:
        yield c


def _multipart(task: str = "fluid", laterality: str = "OD"):
    return {
        "file": ("input.e2e", b"E2E-FAKE-BINARY-PAYLOAD", "application/octet-stream"),
    }, {
        "task": task,
        "laterality": laterality,
    }


# ---------- tests -------------------------------------------------------------


def test_run_happy_path(client) -> None:
    files, data = _multipart()
    headers = {"X-MUW-Inference-Token": "secret-test-token"}
    r = client.post("/run", files=files, data=data, headers=headers)
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["task"] == "fluid"
    assert body["laterality"] == "OD"
    assert body["primary_metric_value"] is None
    assert body["primary_metric_unit"] is None
    assert body["model_version"] == "test-fluid-1.0"
    # Payload paths must be rewritten to basenames.
    assert body["output_payload"]["csv_path"] == "fake-output.csv"
    # Single artifact carrying the fake CSV.
    assert len(body["artifacts"]) == 1
    assert body["artifacts"][0]["name"] == "fake-output.csv"
    assert body["artifacts"][0]["media_type"] == "text/csv"


def test_run_missing_token_returns_401(client) -> None:
    files, data = _multipart()
    r = client.post("/run", files=files, data=data)
    assert r.status_code == 401


def test_run_wrong_token_returns_401(client) -> None:
    files, data = _multipart()
    headers = {"X-MUW-Inference-Token": "wrong"}
    r = client.post("/run", files=files, data=data, headers=headers)
    assert r.status_code == 401


def test_run_endpoint_disabled_returns_404(client, monkeypatch) -> None:
    monkeypatch.setattr(_config.settings, "run_endpoint_enabled", False, raising=False)
    files, data = _multipart()
    headers = {"X-MUW-Inference-Token": "secret-test-token"}
    r = client.post("/run", files=files, data=data, headers=headers)
    assert r.status_code == 404


def test_run_unsupported_task_returns_400(client) -> None:
    files, data = _multipart(task="not-a-task")
    headers = {"X-MUW-Inference-Token": "secret-test-token"}
    r = client.post("/run", files=files, data=data, headers=headers)
    assert r.status_code == 400


def test_run_bad_laterality_returns_400(client) -> None:
    files, data = _multipart(laterality="left")
    headers = {"X-MUW-Inference-Token": "secret-test-token"}
    r = client.post("/run", files=files, data=data, headers=headers)
    assert r.status_code == 400


def test_run_empty_file_returns_400(client) -> None:
    files = {"file": ("input.e2e", b"", "application/octet-stream")}
    data = {"task": "fluid", "laterality": "OD"}
    headers = {"X-MUW-Inference-Token": "secret-test-token"}
    r = client.post("/run", files=files, data=data, headers=headers)
    assert r.status_code == 400


def test_is_dicom_detection() -> None:
    # by suffix
    assert run_module._is_dicom("bscan.dcm", b"anything") is True
    assert run_module._is_dicom("scan.DICOM", b"anything") is True
    # by Part-10 magic (128-byte preamble + "DICM")
    assert run_module._is_dicom("noext", b"\x00" * 128 + b"DICM" + b"rest") is True
    # neither
    assert run_module._is_dicom("input.e2e", b"E2E-FAKE") is False
    assert run_module._is_dicom(None, b"short") is False


def test_materialize_input_names_by_kind(tmp_path) -> None:
    dcm = run_module._materialize_input(tmp_path, "x.dcm", b"\x00" * 132)
    assert dcm.name == "bscan.dcm" and dcm.read_bytes() == b"\x00" * 132
    e2e = run_module._materialize_input(tmp_path, "x.e2e", b"E2E")
    assert e2e.name == "input.e2e" and e2e.read_bytes() == b"E2E"


def test_run_accepts_dicom_upload(client) -> None:
    # A DICOM upload (Part-10 magic) must be accepted and routed like a .e2e.
    files = {"file": ("bscan.dcm", b"\x00" * 128 + b"DICM" + b"x", "application/dicom")}
    data = {"task": "onl", "laterality": "OD"}
    headers = {"X-MUW-Inference-Token": "secret-test-token"}
    r = client.post("/run", files=files, data=data, headers=headers)
    assert r.status_code == 200, r.text
    assert r.json()["task"] == "onl"


def test_run_tempdir_cleaned_up_after_response(client) -> None:
    files, data = _multipart()
    headers = {"X-MUW-Inference-Token": "secret-test-token"}
    r = client.post("/run", files=files, data=data, headers=headers)
    assert r.status_code == 200
    # The shared_tmpdir must not contain any leftover run_* directories.
    shared = _config.settings.shared_tmpdir
    leftovers = [p for p in shared.iterdir() if p.name.startswith("run_")]
    assert leftovers == [], f"tempdir leak: {leftovers}"


def test_run_idempotency_returns_cached_envelope(client, monkeypatch) -> None:
    # Track how many times the adapter actually runs.
    calls: list[str] = []

    from retinal_inference.api import run as run_mod

    real_get_adapter = run_mod.get_adapter

    def counting_get_adapter():
        adapter = real_get_adapter()
        original_full = adapter.full_volume

        def counted(*args, **kwargs):
            calls.append("called")
            return original_full(*args, **kwargs)

        adapter.full_volume = counted  # type: ignore[method-assign]
        return adapter

    monkeypatch.setattr(run_mod, "get_adapter", counting_get_adapter)

    files, data = _multipart()
    headers = {
        "X-MUW-Inference-Token": "secret-test-token",
        "Idempotency-Key": "job-7-uuid-abc",
    }
    r1 = client.post("/run", files=files, data=data, headers=headers)
    assert r1.status_code == 200
    # Second call with same key skips the adapter.
    files2, data2 = _multipart()
    r2 = client.post("/run", files=files2, data=data2, headers=headers)
    assert r2.status_code == 200
    assert r1.json() == r2.json()
    assert len(calls) == 1


def test_run_without_idempotency_key_runs_twice(client, monkeypatch) -> None:
    calls: list[str] = []

    from retinal_inference.api import run as run_mod

    real_get_adapter = run_mod.get_adapter

    def counting_get_adapter():
        adapter = real_get_adapter()
        original_full = adapter.full_volume

        def counted(*args, **kwargs):
            calls.append("called")
            return original_full(*args, **kwargs)

        adapter.full_volume = counted  # type: ignore[method-assign]
        return adapter

    monkeypatch.setattr(run_mod, "get_adapter", counting_get_adapter)

    files, data = _multipart()
    headers = {"X-MUW-Inference-Token": "secret-test-token"}
    client.post("/run", files=files, data=data, headers=headers)
    files2, data2 = _multipart()
    client.post("/run", files=files2, data=data2, headers=headers)
    assert len(calls) == 2