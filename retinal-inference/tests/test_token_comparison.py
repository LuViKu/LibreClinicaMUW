"""Shared-secret checks compare in constant time on every token-gated route.

A plain ``!=`` returns as soon as the first byte differs, so the response time
leaks how much of a guessed token was right. The routes go through
``security.token_matches``, which uses ``hmac.compare_digest``; these tests pin
that by watching ``compare_digest`` while each route is called.
"""

from __future__ import annotations

import hmac

import pytest
from fastapi.testclient import TestClient

from retinal_inference import config as _config
from retinal_inference.security import token_matches

TOKEN = "secret-test-token"


@pytest.fixture
def digest_calls(monkeypatch):
    """Record every hmac.compare_digest call while delegating to the real one."""
    calls: list[tuple[bytes, bytes]] = []
    real = hmac.compare_digest

    def spy(a, b):
        calls.append((a, b))
        return real(a, b)

    monkeypatch.setattr(hmac, "compare_digest", spy)
    return calls


@pytest.fixture
def client(monkeypatch, tmp_path):
    monkeypatch.setattr(_config.settings, "auth_token", TOKEN, raising=False)
    monkeypatch.setattr(_config.settings, "run_endpoint_enabled", True, raising=False)
    monkeypatch.setattr(_config.settings, "preprocess_endpoint_enabled", True, raising=False)
    monkeypatch.setattr(_config.settings, "shared_tmpdir", tmp_path / "shared-tmp", raising=False)
    from retinal_inference.main import app

    with TestClient(app) as c:
        yield c


def _run(client, token):
    return client.post(
        "/run",
        files={"file": ("bscan.dcm", b"x", "application/dicom")},
        data={"task": "not-a-task", "laterality": "OD"},
        headers={"X-MUW-Inference-Token": token},
    )


def _preprocess(client, token):
    return client.post(
        "/preprocess",
        files={"file": ("input.e2e", b"", "application/octet-stream")},
        headers={"X-MUW-Inference-Token": token},
    )


def _derive(client, token):
    return client.post(
        "/derive", json={"job_dir": "/nonexistent-job-dir"}, headers={"X-Auth-Token": token}
    )


@pytest.mark.parametrize("call", [_run, _preprocess, _derive], ids=["run", "preprocess", "derive"])
def test_route_compares_the_token_in_constant_time(client, digest_calls, call) -> None:
    ok = call(client, TOKEN)
    assert ok.status_code != 401, ok.text
    assert (TOKEN.encode(), TOKEN.encode()) in digest_calls

    digest_calls.clear()
    wrong = call(client, "secret-test-tokeX")
    assert wrong.status_code == 401
    assert digest_calls, "the wrong token was not checked with hmac.compare_digest"


@pytest.mark.parametrize("call", [_run, _preprocess, _derive], ids=["run", "preprocess", "derive"])
def test_a_non_ascii_token_is_refused_not_crashed(client, call) -> None:
    # Header bytes arrive latin-1 decoded; compare_digest refuses non-ASCII str.
    r = call(client, "sécret".encode("latin-1"))
    assert r.status_code == 401


def test_token_matches_edge_cases() -> None:
    assert token_matches(TOKEN, TOKEN)
    assert not token_matches(None, TOKEN)
    assert not token_matches("", TOKEN)
    assert not token_matches(TOKEN, None)
    assert not token_matches(TOKEN, "")
    assert not token_matches("", "")
    assert not token_matches(TOKEN + "x", TOKEN)
    assert not token_matches("sécret", TOKEN)
