"""GET /health smoke test."""

from __future__ import annotations


def test_health_ok(app_client) -> None:
    resp = app_client.get("/health")
    assert resp.status_code == 200
    body = resp.json()
    assert body["status"] == "ok"
    assert body["adapter"] == "placeholder"
    assert body["model_version"] == "placeholder-v1"
    assert "ga" in body["supported_tasks"]
