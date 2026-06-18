"""fundus_extract.build_geometry — SLO scale source resolution.

Covers the institutional override path
(``RETINAL_INFERENCE_SLO_SPECTRALIS_MM_PER_PX``) plus the existing
bscan-fallback behaviour. The other branches in build_geometry (positions,
fovea, bbox) get exercised by the preprocess endpoint tests.
"""

from __future__ import annotations

import os

import pydicom
import pytest

from retinal_inference.inference.fundus_extract import build_geometry


class _FakeBscanVolume:
    axial_mm = 0.00387
    lateral_mm = 0.01148
    slice_mm = 0.05
    laterality = "OD"
    n_bscans = 3
    rows = 4
    cols = 5
    bscan_data = None


def _fake_ds() -> pydicom.Dataset:
    ds = pydicom.Dataset()
    ds.Columns = 5
    ds.Rows = 4
    return ds


def test_build_geometry_uses_bscan_fallback_by_default(monkeypatch) -> None:
    monkeypatch.delenv("RETINAL_INFERENCE_SLO_SPECTRALIS_MM_PER_PX", raising=False)
    geom = build_geometry(_FakeBscanVolume(), _fake_ds(), (768, 768))
    assert geom["fundus"]["scale_source"] == "bscan_fallback"
    assert geom["fundus"]["lateral_mm_per_px"] == pytest.approx(0.01148)
    assert geom["fundus"]["slice_mm_per_px"] == pytest.approx(0.05)


def test_build_geometry_uses_env_override(monkeypatch) -> None:
    monkeypatch.setenv("RETINAL_INFERENCE_SLO_SPECTRALIS_MM_PER_PX", "0.0058")
    geom = build_geometry(_FakeBscanVolume(), _fake_ds(), (768, 768))
    assert geom["fundus"]["scale_source"] == "env_override"
    # Both axes get the override — Spectralis SLO is square-pixel.
    assert geom["fundus"]["lateral_mm_per_px"] == pytest.approx(0.0058)
    assert geom["fundus"]["slice_mm_per_px"] == pytest.approx(0.0058)


def test_build_geometry_invalid_env_falls_back(monkeypatch) -> None:
    monkeypatch.setenv("RETINAL_INFERENCE_SLO_SPECTRALIS_MM_PER_PX", "not-a-number")
    geom = build_geometry(_FakeBscanVolume(), _fake_ds(), (768, 768))
    assert geom["fundus"]["scale_source"] == "bscan_fallback"
    assert geom["fundus"]["lateral_mm_per_px"] == pytest.approx(0.01148)


def test_build_geometry_emits_scan_index(monkeypatch) -> None:
    monkeypatch.delenv("RETINAL_INFERENCE_SLO_SPECTRALIS_MM_PER_PX", raising=False)
    geom = build_geometry(_FakeBscanVolume(), _fake_ds(), (768, 768), scan_index=3)
    assert geom["scan_index"] == 3


def test_build_geometry_scan_index_defaults_to_zero(monkeypatch) -> None:
    monkeypatch.delenv("RETINAL_INFERENCE_SLO_SPECTRALIS_MM_PER_PX", raising=False)
    geom = build_geometry(_FakeBscanVolume(), _fake_ds(), (768, 768))
    assert geom["scan_index"] == 0
