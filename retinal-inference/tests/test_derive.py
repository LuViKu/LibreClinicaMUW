"""POST /derive — token-gated (closed when unset) and confined to the artifact store.

The app posts ``{"job_dir": "<artifact-store>/<job-uuid>"}`` after persisting
the cluster's ``fluidseg.npz``; the sidecar renders projection PNGs into that
directory. It must not render into, or report on, any other directory.
"""

from __future__ import annotations

from pathlib import Path

import numpy as np
import pytest
from fastapi.testclient import TestClient

from retinal_inference import config as _config

TOKEN = "secret-test-token"
HEADERS = {"X-Auth-Token": TOKEN}


def _job_dir_with_segmentation(parent: Path, name: str = "0b6f1c2e-5d4a-4f3b-9e8d-7c6b5a493827") -> Path:
    job = parent / name
    job.mkdir(parents=True)
    seg = np.zeros((3, 4, 5), dtype=np.uint8)
    seg[1, 2, 3] = 1  # one IRF voxel
    np.savez(job / "fluidseg.npz", segmentation=seg)
    return job


def _pngs(directory: Path) -> list[str]:
    return sorted(p.name for p in directory.glob("*.png"))


@pytest.fixture
def store(monkeypatch, tmp_path) -> Path:
    root = tmp_path / "retinal-artifacts"
    root.mkdir()
    monkeypatch.setattr(_config.settings, "auth_token", TOKEN, raising=False)
    monkeypatch.setattr(_config.settings, "artifact_store_path", root, raising=False)
    return root


@pytest.fixture
def client(store):
    from retinal_inference.main import app

    with TestClient(app) as c:
        yield c


def test_derive_renders_into_a_job_dir_in_the_store(client, store) -> None:
    job = _job_dir_with_segmentation(store)
    r = client.post("/derive", json={"job_dir": str(job)}, headers=HEADERS)
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["skipped"] is False
    assert "projection_fluid.png" in body["written"]
    assert "projection_fluid.png" in _pngs(job)

    again = client.post("/derive", json={"job_dir": str(job)}, headers=HEADERS)
    assert again.status_code == 200
    assert again.json()["skipped"] is True


def test_derive_is_closed_when_no_token_is_configured(client, store, monkeypatch) -> None:
    job = _job_dir_with_segmentation(store)
    monkeypatch.setattr(_config.settings, "auth_token", None, raising=False)
    r = client.post("/derive", json={"job_dir": str(job)})
    assert r.status_code == 503
    assert _pngs(job) == []


@pytest.mark.parametrize("headers", [{}, {"X-Auth-Token": "wrong"}], ids=["missing", "wrong"])
def test_derive_refuses_a_missing_or_wrong_token(client, store, headers) -> None:
    job = _job_dir_with_segmentation(store)
    r = client.post("/derive", json={"job_dir": str(job)}, headers=headers)
    assert r.status_code == 401
    assert _pngs(job) == []


def test_derive_does_not_render_outside_the_store(client, store, tmp_path) -> None:
    outside = _job_dir_with_segmentation(tmp_path / "elsewhere")
    for job_dir in (str(outside), f"{store}/../elsewhere/{outside.name}"):
        r = client.post("/derive", json={"job_dir": job_dir}, headers=HEADERS)
        assert r.status_code == 400, r.text
    assert _pngs(outside) == []


def test_derive_does_not_follow_a_symlink_out_of_the_store(client, store, tmp_path) -> None:
    outside = _job_dir_with_segmentation(tmp_path / "elsewhere")
    link = store / "linked-job"
    link.symlink_to(outside, target_is_directory=True)
    r = client.post("/derive", json={"job_dir": str(link)}, headers=HEADERS)
    assert r.status_code == 400
    assert _pngs(outside) == []


def test_derive_answers_the_same_for_any_path_outside_the_store(client, store, tmp_path) -> None:
    # An existing directory and a missing one outside the store must be
    # indistinguishable, and the reply must not repeat the path.
    existing = tmp_path / "probe-existing"
    existing.mkdir()
    replies = [
        client.post("/derive", json={"job_dir": str(p)}, headers=HEADERS)
        for p in (existing, tmp_path / "probe-missing", Path("/etc"))
    ]
    assert {r.status_code for r in replies} == {400}
    assert len({r.text for r in replies}) == 1
    assert "probe" not in replies[0].text and "/etc" not in replies[0].text


def test_derive_errors_inside_the_store_do_not_echo_the_path(client, store) -> None:
    empty_job = store / "job-without-npz"
    empty_job.mkdir()
    r = client.post("/derive", json={"job_dir": str(empty_job)}, headers=HEADERS)
    assert r.status_code == 400
    assert "job-without-npz" not in r.text
    assert str(store) not in r.text

    bad_npz_job = store / "job-with-bad-npz"
    bad_npz_job.mkdir()
    np.savez(bad_npz_job / "fluidseg.npz", other=np.zeros(1))
    r = client.post("/derive", json={"job_dir": str(bad_npz_job)}, headers=HEADERS)
    assert r.status_code == 400
    assert "job-with-bad-npz" not in r.text
    assert str(store) not in r.text


def test_derive_refuses_the_store_root_itself(client, store) -> None:
    np.savez(store / "fluidseg.npz", segmentation=np.zeros((1, 1, 1), dtype=np.uint8))
    r = client.post("/derive", json={"job_dir": str(store)}, headers=HEADERS)
    assert r.status_code == 400
    assert _pngs(store) == []
