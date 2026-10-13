"""POST /e2e/describe and GET /e2e/series-image (Contract 1, e2e-multimodal-split).

The converter functions are replaced by fakes: the series logic is tested in
``muw-e2e-converter``; here the HTTP contract is tested (auth, name validation, containment,
status codes, DR-024 503). No real E2E is used.
"""

from __future__ import annotations

from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from retinal_inference import config as _config
from retinal_inference.api import e2e_series as mod

TOKEN = "test-token-123"
HEADERS = {"X-MUW-Inference-Token": TOKEN}
NAME = "3f2b8c1e-0000-4000-8000-000000000001.e2e"
PNG = b"\x89PNG\r\n\x1a\nfake"


def _fake_describe(path, expected_label):
    assert Path(path).is_file()
    return {"headerPatient": "matches" if expected_label == "EIAMD005" else "differs", "series": []}


def _fake_render(path, series_key, frame=0):
    if series_key != "s0":
        raise KeyError(series_key)
    if frame != 0:
        raise IndexError(frame)
    return PNG


@pytest.fixture
def uploads(tmp_path, monkeypatch) -> Path:
    root = tmp_path / "e2e-uploads"
    root.mkdir()
    (root / NAME).write_bytes(b"not a real e2e")
    monkeypatch.setattr(_config.settings, "e2e_uploads_path", root)
    monkeypatch.setattr(_config.settings, "preprocess_endpoint_enabled", True)
    monkeypatch.setattr(_config.settings, "auth_token", TOKEN)
    monkeypatch.setattr(mod, "_CONVERTER_AVAILABLE", True)
    monkeypatch.setattr(mod, "describe_e2e", _fake_describe)
    monkeypatch.setattr(mod, "render_series_png", _fake_render)
    return root


@pytest.fixture
def client(uploads) -> TestClient:
    from retinal_inference.main import app

    return TestClient(app)


def _describe(client, name=NAME, label="EIAMD005", headers=HEADERS):
    return client.post("/e2e/describe", json={"storedName": name, "expectedLabel": label},
                       headers=headers)


def _image(client, name=NAME, key="s0", frame=0, headers=HEADERS):
    return client.get("/e2e/series-image",
                      params={"storedName": name, "seriesKey": key, "frame": frame},
                      headers=headers)


def test_describe_ok(client) -> None:
    r = _describe(client)
    assert r.status_code == 200
    assert r.json() == {"headerPatient": "matches", "series": []}
    assert _describe(client, label="other").json()["headerPatient"] == "differs"


def test_extension_is_case_insensitive(client, uploads) -> None:
    (uploads / "ABC-1.E2E").write_bytes(b"x")
    assert _describe(client, name="ABC-1.E2E").status_code == 200


@pytest.mark.parametrize(
    "name",
    ["../x.e2e", "..%2Fx.e2e", "a/b.e2e", "a\b.e2e", "/etc/passwd", "x.e2e/", "x.e2e\n",
     "x.txt", "x", ".e2e", "a b.e2e", "a.b.e2e", "x.e2e\x00", "..", "", "é.e2e"],
)
def test_bad_names_are_400(client, name) -> None:
    assert _describe(client, name=name).status_code == 400
    assert _image(client, name=name).status_code == 400


def test_symlink_out_of_the_directory_is_refused(client, uploads, tmp_path) -> None:
    outside = tmp_path / "outside.e2e"
    outside.write_bytes(b"x")
    try:
        (uploads / "link.e2e").symlink_to(outside)
    except (OSError, NotImplementedError):
        pytest.skip("symlinks not available")
    assert _describe(client, name="link.e2e").status_code == 400


def test_missing_file_is_404(client) -> None:
    assert _describe(client, name="missing.e2e").status_code == 404
    assert _image(client, name="missing.e2e").status_code == 404


def test_series_image_ok_and_404s(client) -> None:
    r = _image(client)
    assert r.status_code == 200 and r.headers["content-type"] == "image/png"
    assert r.content == PNG
    assert _image(client, key="s9").status_code == 404  # unknown series
    assert _image(client, frame=5).status_code == 404  # frame out of range
    assert _image(client, key="../../x").status_code == 404


def test_auth_is_required(client) -> None:
    assert _describe(client, headers={}).status_code == 401
    assert _describe(client, headers={"X-MUW-Inference-Token": "wrong"}).status_code == 401
    assert _image(client, headers={}).status_code == 401
    assert _image(client, headers={"X-MUW-Inference-Token": "wrong"}).status_code == 401


def test_unconfigured_token_is_503(client, monkeypatch) -> None:
    monkeypatch.setattr(_config.settings, "auth_token", None)
    assert _describe(client).status_code == 503


def test_disabled_endpoint_is_404(client, monkeypatch) -> None:
    monkeypatch.setattr(_config.settings, "preprocess_endpoint_enabled", False)
    assert _describe(client).status_code == 404
    assert _image(client).status_code == 404


def test_converter_absent_is_503(client, monkeypatch) -> None:
    monkeypatch.setattr(mod, "_CONVERTER_AVAILABLE", False)
    for r in (_describe(client), _image(client)):
        assert r.status_code == 503
        assert "DR-024" in r.json()["detail"]


def test_unreadable_file_is_422_and_does_not_echo_the_parser_message(client, monkeypatch) -> None:
    def boom(path, label):
        raise ValueError("SECRET-FROM-FILE")

    monkeypatch.setattr(mod, "describe_e2e", boom)
    r = _describe(client)
    assert r.status_code == 422 and "SECRET-FROM-FILE" not in r.text
