"""POST /screen — deterministic placeholder output."""

from __future__ import annotations

import hashlib


def _expected_approx_area_mm2(e2e_path, task: str) -> float:
    h = hashlib.sha256()
    h.update(e2e_path.read_bytes())
    h.update(task.encode("utf-8"))
    seed0 = h.digest()[0]
    return round(2.0 + (seed0 / 255.0) * 5.0, 3)


def test_screen_ga_deterministic(app_client, fake_e2e_path) -> None:
    payload = {
        "job_id": 1,
        "task": "ga",
        "e2e_path": str(fake_e2e_path),
        "laterality": "OD",
    }
    resp = app_client.post("/screen", json=payload)
    assert resp.status_code == 200, resp.text
    body = resp.json()
    expected = _expected_approx_area_mm2(fake_e2e_path, "ga")
    assert body["job_id"] == 1
    assert body["task"] == "ga"
    assert body["model_version"] == "placeholder-v1"
    assert body["foveal_bscan_index"] == 48
    assert abs(body["approx_area_mm2"] - expected) < 0.001
    # GA envelope: 2.000..7.000 mm²
    assert 2.0 <= body["approx_area_mm2"] <= 7.0


def test_screen_same_input_same_output(app_client, fake_e2e_path) -> None:
    payload = {
        "job_id": 7,
        "task": "ga",
        "e2e_path": str(fake_e2e_path),
        "laterality": "OS",
    }
    r1 = app_client.post("/screen", json=payload).json()
    r2 = app_client.post("/screen", json=payload).json()
    assert r1["approx_area_mm2"] == r2["approx_area_mm2"]
    assert r1["confidence"] == r2["confidence"]
