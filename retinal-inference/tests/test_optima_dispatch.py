"""OptimaAdapter — runner gating + HTTP dispatch (no real network)."""

from __future__ import annotations

import pytest

from retinal_inference import config as _config
from retinal_inference.inference import optima as optima_mod
from retinal_inference.inference.adapter import (
    FastScreenUnavailable,
    UnsupportedTaskError,
)
from retinal_inference.inference.optima import OptimaAdapter


def _set_runner_urls(monkeypatch, **urls) -> None:
    for task in ("fluid", "onl", "pr", "ga"):
        monkeypatch.setattr(
            _config.settings, f"runner_{task}_url", urls.get(task), raising=False
        )


def test_supports_gated_by_runner_url(monkeypatch) -> None:
    _set_runner_urls(monkeypatch, fluid="http://runner-fluid:8000")
    adapter = OptimaAdapter()
    assert adapter.supports("fluid") is True
    assert adapter.supports("onl") is False  # no URL configured
    assert adapter.supports("ga") is False  # gated (no URL)


def test_fast_screen_is_async_only(monkeypatch, fake_e2e_path) -> None:
    _set_runner_urls(monkeypatch, fluid="http://runner-fluid:8000")
    adapter = OptimaAdapter()
    with pytest.raises(FastScreenUnavailable):
        adapter.fast_screen("fluid", fake_e2e_path, "OD")


def test_full_volume_unsupported_task(monkeypatch, fake_e2e_path) -> None:
    _set_runner_urls(monkeypatch)  # nothing configured
    adapter = OptimaAdapter()
    with pytest.raises(UnsupportedTaskError):
        adapter.full_volume("ga", fake_e2e_path, "OD")


def test_full_volume_dispatch(monkeypatch, fake_e2e_path, tmp_path) -> None:
    _set_runner_urls(monkeypatch, fluid="http://runner-fluid:8000/")
    monkeypatch.setattr(_config.settings, "shared_storage_path", tmp_path, raising=False)

    bscan_dir = tmp_path / "bscan"
    bscan_dir.mkdir()
    captured_prep: dict = {}

    def fake_prep(e2e, out, scan_index=0):
        captured_prep["scan_index"] = scan_index
        return bscan_dir

    monkeypatch.setattr(optima_mod, "prepare_bscan_dcm", fake_prep)

    captured: dict = {}

    def fake_post(url, payload, timeout):
        captured["url"] = url
        captured["payload"] = payload
        captured["timeout"] = timeout
        # Server returns raw artifacts only; the metric is None (Java computes it).
        return {
            "primary_metric_value": None,
            "primary_metric_unit": None,
            "output_payload": {"segmentation_file": "fluidseg.npz"},
            "en_face_mask_path": str(tmp_path / "mask.png"),
            "bscan_masks_dir": str(tmp_path / "masks"),
            "pixel_scale_mm": 0.011,
            "confidence": 0.9,
            "model_version": "retinsight-fluid-1.3.0",
        }

    monkeypatch.setattr(optima_mod, "_post_json", fake_post)

    adapter = OptimaAdapter()
    res = adapter.full_volume("fluid", fake_e2e_path, "OD")

    assert res.task == "fluid"
    assert res.primary_metric_value is None
    assert res.primary_metric_unit is None
    assert res.output_payload["segmentation_file"] == "fluidseg.npz"
    assert res.model_version == "retinsight-fluid-1.3.0"
    assert res.confidence == 0.9

    # Dispatch wiring: trailing slash normalised, payload shape, dcm path.
    assert captured["url"] == "http://runner-fluid:8000/infer"
    assert captured["payload"]["task"] == "fluid"
    assert captured["payload"]["laterality"] == "OD"
    assert captured["payload"]["bscan_dcm_path"].endswith("bscan.dcm")
    assert captured["timeout"] == _config.settings.runner_timeout_s
    # Default scan_index plumbed to the preprocess step.
    assert captured_prep["scan_index"] == 0


def test_full_volume_threads_scan_index_to_prepare(monkeypatch, fake_e2e_path, tmp_path) -> None:
    """OptimaAdapter forwards scan_index to prepare_bscan_dcm so the right
    volume from a multi-acquisition .e2e gets ingested."""
    _set_runner_urls(monkeypatch, fluid="http://runner-fluid:8000/")
    monkeypatch.setattr(_config.settings, "shared_storage_path", tmp_path, raising=False)

    bscan_dir = tmp_path / "bscan"
    bscan_dir.mkdir()
    captured_prep: dict = {}

    def fake_prep(e2e, out, scan_index=0):
        captured_prep["scan_index"] = scan_index
        return bscan_dir

    monkeypatch.setattr(optima_mod, "prepare_bscan_dcm", fake_prep)
    monkeypatch.setattr(
        optima_mod, "_post_json",
        lambda url, payload, timeout: {
            "primary_metric_value": None,
            "primary_metric_unit": None,
            "output_payload": {},
            "en_face_mask_path": None,
            "bscan_masks_dir": str(tmp_path),
            "pixel_scale_mm": 0.011,
            "confidence": 0.9,
            "model_version": "v1",
        },
    )

    adapter = OptimaAdapter()
    adapter.full_volume("fluid", fake_e2e_path, "OD", scan_index=2)
    assert captured_prep["scan_index"] == 2
