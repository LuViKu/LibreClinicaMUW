"""POST /screen — deterministic placeholder output, confined to the E2E uploads store."""

from __future__ import annotations

import hashlib

import pytest

from retinal_inference import config as _config

TOKEN = "secret-test-token"


@pytest.fixture
def screen_client(app_client, fake_e2e_path, monkeypatch):
    """The app client with the fixture directory standing in for the uploads store."""
    monkeypatch.setattr(
        _config.settings, "e2e_uploads_path", fake_e2e_path.parent, raising=False
    )
    monkeypatch.setattr(_config.settings, "auth_token", None, raising=False)
    return app_client


def _expected_approx_area_mm2(e2e_path, task: str) -> float:
    h = hashlib.sha256()
    h.update(e2e_path.read_bytes())
    h.update(task.encode("utf-8"))
    seed0 = h.digest()[0]
    return round(2.0 + (seed0 / 255.0) * 5.0, 3)


def _payload(e2e_path, job_id: int = 1, laterality: str = "OD") -> dict:
    return {"job_id": job_id, "task": "ga", "e2e_path": str(e2e_path), "laterality": laterality}


def test_screen_ga_deterministic(screen_client, fake_e2e_path) -> None:
    resp = screen_client.post("/screen", json=_payload(fake_e2e_path))
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


def test_screen_same_input_same_output(screen_client, fake_e2e_path) -> None:
    payload = _payload(fake_e2e_path, job_id=7, laterality="OS")
    r1 = screen_client.post("/screen", json=payload).json()
    r2 = screen_client.post("/screen", json=payload).json()
    assert r1["approx_area_mm2"] == r2["approx_area_mm2"]
    assert r1["confidence"] == r2["confidence"]


# ---------- confinement to the uploads store ----------------------------------


def test_screen_refuses_a_file_outside_the_uploads_store(screen_client, fake_e2e_path, tmp_path) -> None:
    outside = tmp_path / "not-an-upload.e2e"
    outside.write_bytes(fake_e2e_path.read_bytes())
    r = screen_client.post("/screen", json=_payload(outside))
    assert r.status_code == 400
    assert "not-an-upload" not in r.text


def test_screen_refuses_traversal_out_of_the_uploads_store(screen_client, fake_e2e_path, tmp_path) -> None:
    outside = tmp_path / "secret.bin"
    outside.write_bytes(b"not for you")
    # Enough ".." to climb to "/" from the uploads store, then down to the file.
    sneaky = str(fake_e2e_path.parent) + "/.." * 40 + str(outside)
    r = screen_client.post("/screen", json=_payload(sneaky))
    assert r.status_code == 400


def test_screen_refuses_a_symlink_out_of_the_uploads_store(app_client, monkeypatch, tmp_path) -> None:
    uploads = tmp_path / "uploads"
    uploads.mkdir()
    outside = tmp_path / "secret.bin"
    outside.write_bytes(b"not for you")
    (uploads / "link.e2e").symlink_to(outside)
    monkeypatch.setattr(_config.settings, "e2e_uploads_path", uploads, raising=False)
    monkeypatch.setattr(_config.settings, "auth_token", None, raising=False)
    r = app_client.post("/screen", json=_payload(uploads / "link.e2e"))
    assert r.status_code == 400


def test_screen_answers_the_same_for_missing_and_outside_paths(screen_client, fake_e2e_path) -> None:
    missing = fake_e2e_path.parent / "missing.e2e"
    replies = [
        screen_client.post("/screen", json=_payload(p))
        for p in (missing, "/etc/hostname", "/nonexistent/probe.e2e")
    ]
    assert {r.status_code for r in replies} == {400}
    assert len({r.text for r in replies}) == 1
    assert "probe" not in replies[0].text and "missing.e2e" not in replies[0].text


# ---------- shared token ------------------------------------------------------


@pytest.mark.parametrize(
    "headers", [{}, {"X-MUW-Inference-Token": "wrong"}], ids=["missing", "wrong"]
)
def test_screen_refuses_a_bad_token_when_one_is_configured(
    screen_client, fake_e2e_path, monkeypatch, headers
) -> None:
    monkeypatch.setattr(_config.settings, "auth_token", TOKEN, raising=False)
    r = screen_client.post("/screen", json=_payload(fake_e2e_path), headers=headers)
    assert r.status_code == 401


def test_screen_accepts_the_configured_token(screen_client, fake_e2e_path, monkeypatch) -> None:
    monkeypatch.setattr(_config.settings, "auth_token", TOKEN, raising=False)
    r = screen_client.post(
        "/screen", json=_payload(fake_e2e_path), headers={"X-MUW-Inference-Token": TOKEN}
    )
    assert r.status_code == 200, r.text
