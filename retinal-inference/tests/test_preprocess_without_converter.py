"""DR-024 — /preprocess returns 503 when muw-e2e-converter is absent.

The cluster's bare-metal cn5 deployment intentionally does NOT install
the muw-e2e-converter package. This test simulates that posture by
monkeypatching ``preprocess._CONVERTER_AVAILABLE = False`` and asserting
the endpoint surfaces a clear 503 instead of a 500 / AttributeError /
silent fallthrough. See DR-024 for the split rationale.
"""

from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from retinal_inference import config as _config
from retinal_inference.api import preprocess as preprocess_module


@pytest.fixture
def client(monkeypatch) -> TestClient:
    """Simulate the cluster posture: muw-e2e-converter absent."""
    monkeypatch.setattr(
        _config.settings,
        "preprocess_endpoint_enabled",
        True,
    )
    monkeypatch.setattr(
        _config.settings,
        "auth_token",
        "test-token-123",
    )
    # The key bit — flip the import-time flag back to False as if pip
    # never installed muw-e2e-converter.
    monkeypatch.setattr(preprocess_module, "_CONVERTER_AVAILABLE", False)
    from retinal_inference.main import app

    return TestClient(app)


def test_preprocess_returns_503_when_converter_missing(client: TestClient) -> None:
    resp = client.post(
        "/preprocess",
        files={"file": ("input.e2e", b"E2E-FAKE-BINARY", "application/octet-stream")},
        data={"scan_index": "0"},
        headers={"X-MUW-Inference-Token": "test-token-123"},
    )
    assert resp.status_code == 503, (
        f"expected 503 (converter missing) but got {resp.status_code}: {resp.text}"
    )
    detail = resp.json()["detail"]
    # Operator should immediately see WHY the endpoint is unavailable and
    # which DR to consult.
    assert "muw-e2e-converter" in detail
    assert "DR-024" in detail
