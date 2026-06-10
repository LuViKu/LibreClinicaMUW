"""POST /screen with an unsupported task must be rejected."""

from __future__ import annotations


def test_unknown_task_rejected(app_client, fake_e2e_path) -> None:
    payload = {
        "job_id": 99,
        "task": "fluid",  # not in TaskName literal — Pydantic 422
        "e2e_path": str(fake_e2e_path),
        "laterality": "OD",
    }
    resp = app_client.post("/screen", json=payload)
    assert resp.status_code in (400, 422), resp.text
    body = resp.json()
    # Pydantic 422 returns {"detail": [{"msg": "...not supported..."}]}.
    # The 400 path returns {"detail": "Task '<x>' is not supported..."}.
    text = repr(body).lower()
    assert "not supported" in text or "literal" in text or "input should be" in text


def test_missing_task_rejected(app_client, fake_e2e_path) -> None:
    payload = {
        "job_id": 1,
        "e2e_path": str(fake_e2e_path),
        "laterality": "OD",
    }
    resp = app_client.post("/screen", json=payload)
    assert resp.status_code == 422
