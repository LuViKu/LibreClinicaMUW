"""The model runners' POST /infer acts only on the sidecar's shared directories.

The runners (runners/<task>/app.py) are separate images with their own
interpreters, but their HTTP shell is plain FastAPI, so each app module is
loaded here with its heavy vendor imports stubbed and driven through a
TestClient. Nothing reaches a model: every request either fails the guard or
stops at the runner's own "bscan.dcm not found" check.
"""

from __future__ import annotations

import importlib.util
import sys
import types
import urllib.request
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

RUNNERS_DIR = Path(__file__).resolve().parents[1] / "runners"
RUNNERS = ("fluid", "ga", "onl", "pr")
TOKEN = "runner-test-token"


def _load_runner_app(name: str):
    """Import runners/<name>/app.py (+ its runner_guard) as a fresh module."""
    runner_dir = RUNNERS_DIR / name
    saved_path = list(sys.path)
    saved_guard = sys.modules.pop("runner_guard", None)
    stubs = {}
    if name == "pr" and "SimpleITK" not in sys.modules:
        stubs["SimpleITK"] = types.ModuleType("SimpleITK")
    sys.modules.update(stubs)
    sys.path.insert(0, str(runner_dir))
    try:
        spec = importlib.util.spec_from_file_location(f"_runner_app_{name}", runner_dir / "app.py")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module
    finally:
        sys.path[:] = saved_path
        sys.modules.pop("runner_guard", None)
        if saved_guard is not None:
            sys.modules["runner_guard"] = saved_guard
        for key in stubs:
            sys.modules.pop(key, None)


@pytest.fixture
def roots(tmp_path, monkeypatch):
    shared_tmp = tmp_path / "shared-tmp"
    seg_out = tmp_path / "segmentation-output"
    shared_tmp.mkdir()
    seg_out.mkdir()
    monkeypatch.setenv("RUNNER_ALLOWED_ROOTS", f"{shared_tmp}:{seg_out}")
    monkeypatch.delenv("RUNNER_AUTH_TOKEN", raising=False)
    return shared_tmp, seg_out


@pytest.fixture(params=RUNNERS)
def runner(request):
    module = _load_runner_app(request.param)
    with TestClient(module.app) as client:
        yield client


def _infer(client, dcm, out, headers=None):
    return client.post(
        "/infer",
        json={
            "task": "x",
            "bscan_dcm_path": str(dcm),
            "laterality": "OD",
            "output_dir": str(out),
        },
        headers=headers or {},
    )


def test_the_guard_copies_are_identical() -> None:
    copies = {name: (RUNNERS_DIR / name / "runner_guard.py").read_bytes() for name in RUNNERS}
    assert len(set(copies.values())) == 1, "runners/*/runner_guard.py have drifted apart"


@pytest.mark.parametrize("name", RUNNERS)
def test_every_runner_image_ships_the_guard(name) -> None:
    dockerfile = (RUNNERS_DIR / name / "Dockerfile").read_text()
    assert "COPY runner_guard.py" in dockerfile


def test_paths_under_the_shared_roots_pass_the_guard(runner, roots) -> None:
    shared_tmp, seg_out = roots
    for root in (shared_tmp, seg_out):
        job = root / "run_abc"
        r = _infer(runner, job / "bscan.dcm", job)
        # Past the guard: the runner's own missing-input check answers.
        assert r.status_code == 400, r.text
        assert r.json()["detail"].startswith("bscan.dcm not found")
        assert job.is_dir()


def test_an_output_dir_outside_the_roots_is_refused(runner, roots, tmp_path) -> None:
    shared_tmp, _ = roots
    outside = tmp_path / "elsewhere" / "out"
    r = _infer(runner, shared_tmp / "run_abc" / "bscan.dcm", outside)
    assert r.status_code == 400
    assert r.json()["detail"].startswith("output_dir ")
    assert not outside.exists()


def test_an_input_outside_the_roots_is_refused(runner, roots, tmp_path) -> None:
    shared_tmp, _ = roots
    outside_dcm = tmp_path / "elsewhere.dcm"
    outside_dcm.write_bytes(b"DICM")
    out = shared_tmp / "run_abc"
    r = _infer(runner, outside_dcm, out)
    assert r.status_code == 400
    assert r.json()["detail"].startswith("bscan_dcm_path ")
    assert not out.exists()


@pytest.mark.parametrize(
    "make_path",
    [
        lambda root, tmp: f"{root}/../escaped",  # climbs out
        lambda root, tmp: "relative/run_abc",  # relative to the runner's cwd
        lambda root, tmp: str(root),  # the root itself, not a job dir inside it
    ],
    ids=["traversal", "relative", "root-itself"],
)
def test_escaping_output_dirs_are_refused(runner, roots, tmp_path, monkeypatch, make_path) -> None:
    shared_tmp, _ = roots
    monkeypatch.chdir(tmp_path)  # a relative path must not land anywhere real
    raw = make_path(shared_tmp, tmp_path)
    r = _infer(runner, shared_tmp / "run_abc" / "bscan.dcm", raw)
    assert r.status_code == 400
    assert r.json()["detail"].startswith("output_dir ")
    assert not (tmp_path / "escaped").exists()
    assert not (tmp_path / "relative").exists()


def test_a_symlink_out_of_the_roots_is_refused(runner, roots, tmp_path) -> None:
    shared_tmp, _ = roots
    outside = tmp_path / "outside"
    outside.mkdir()
    (shared_tmp / "link").symlink_to(outside, target_is_directory=True)
    r = _infer(runner, shared_tmp / "run_abc" / "bscan.dcm", shared_tmp / "link" / "out")
    assert r.status_code == 400
    assert r.json()["detail"].startswith("output_dir ")
    assert list(outside.iterdir()) == []


def test_the_token_is_required_when_configured(runner, roots, monkeypatch) -> None:
    shared_tmp, _ = roots
    monkeypatch.setenv("RUNNER_AUTH_TOKEN", TOKEN)
    job = shared_tmp / "run_abc"
    for headers in ({}, {"X-MUW-Inference-Token": "wrong"}):
        r = _infer(runner, job / "bscan.dcm", job, headers)
        assert r.status_code == 401
    assert not job.exists()
    r = _infer(runner, job / "bscan.dcm", job, {"X-MUW-Inference-Token": TOKEN})
    assert r.status_code == 400 and r.json()["detail"].startswith("bscan.dcm not found")


# ---------- the sidecar side: OptimaAdapter forwards its token ---------------


def _captured_headers(monkeypatch) -> dict:
    from retinal_inference.inference import optima as optima_mod

    seen: dict = {}

    class _Resp:
        def __enter__(self):
            return self

        def __exit__(self, *exc):
            return False

        def read(self):
            return b"{}"

    def fake_urlopen(req, timeout=None):
        seen.update({k.lower(): v for k, v in req.header_items()})
        return _Resp()

    monkeypatch.setattr(urllib.request, "urlopen", fake_urlopen)
    optima_mod._post_json("http://runner.invalid/infer", {"task": "fluid"}, timeout=1)
    return seen


def test_optima_adapter_sends_the_sidecar_token_to_runners(monkeypatch) -> None:
    from retinal_inference import config as _config

    monkeypatch.setattr(_config.settings, "auth_token", TOKEN, raising=False)
    assert _captured_headers(monkeypatch).get("x-muw-inference-token") == TOKEN


def test_optima_adapter_sends_no_token_when_none_is_configured(monkeypatch) -> None:
    from retinal_inference import config as _config

    monkeypatch.setattr(_config.settings, "auth_token", None, raising=False)
    assert "x-muw-inference-token" not in _captured_headers(monkeypatch)
