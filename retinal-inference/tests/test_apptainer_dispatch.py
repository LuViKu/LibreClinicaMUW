"""ApptainerAdapter — .sif gating + apptainer-exec dispatch (no cluster needed)."""

from __future__ import annotations

import numpy as np
import pytest

from retinal_inference import config as _config
from retinal_inference.inference import apptainer as ap
from retinal_inference.inference.adapter import (
    FastScreenUnavailable,
    UnsupportedTaskError,
)


def _set_sifs(monkeypatch, **sifs) -> None:
    for task in ("fluid", "onl", "bmeis", "ga"):
        monkeypatch.setattr(_config.settings, f"{task}_sif", sifs.get(task), raising=False)


def test_supports_gated_by_sif(monkeypatch) -> None:
    _set_sifs(monkeypatch, fluid="/sif/fluid.sif")
    adapter = ap.ApptainerAdapter()
    assert adapter.supports("fluid") is True
    assert adapter.supports("onl") is False
    assert adapter.supports("ga") is False


def test_fast_screen_async_only(monkeypatch, tmp_path) -> None:
    _set_sifs(monkeypatch, fluid="/sif/fluid.sif")
    with pytest.raises(FastScreenUnavailable):
        ap.ApptainerAdapter().fast_screen("fluid", tmp_path, "OD")


def test_full_volume_unsupported(monkeypatch, tmp_path) -> None:
    _set_sifs(monkeypatch)  # nothing configured
    with pytest.raises(UnsupportedTaskError):
        ap.ApptainerAdapter().full_volume("ga", tmp_path, "OD")


def test_fluid_dispatch_v25(monkeypatch, tmp_path) -> None:
    _set_sifs(monkeypatch, fluid="/sif/fluid_segmentation.sif")
    monkeypatch.setattr(_config.settings, "apptainer_gpu_device", "0", raising=False)
    monkeypatch.setattr(ap, "_spacing_mm", lambda p: (0.004, 0.02, 0.2))

    captured: dict = {}

    def fake_exec(cmd, env=None):
        captured["cmd"] = cmd
        captured["env"] = env
        out = tmp_path / "work" / "out"
        out.mkdir(parents=True, exist_ok=True)
        seg = np.zeros((4, 8, 8), dtype=np.uint8)
        seg[0] = 1  # IRF
        seg[1] = 2  # SRF
        seg[2] = 3  # PED
        np.savez(out / "fluidseg.npz", segmentation=seg)
        return ""

    monkeypatch.setattr(ap, "_exec", fake_exec)

    dcm_dir = tmp_path / "in"
    dcm_dir.mkdir()
    res = ap.ApptainerAdapter().full_volume(
        "fluid", dcm_dir, "OD", out_dir_override=tmp_path / "work"
    )

    assert res.task == "fluid"
    assert res.primary_metric_unit == "mm³"
    assert res.primary_metric_value > 0
    assert set(res.output_payload) >= {"total_fluid_volume_mm3", "irf_mm3", "srf_mm3", "ped_mm3"}
    # v2.5.0 invocation: apptainer run … run_inference.py, GPU env forwarded.
    assert "run" in captured["cmd"]
    assert any("fluid_segmentation.sif" in c for c in captured["cmd"])
    assert any("run_inference.py" in c for c in captured["cmd"])
    assert captured["env"]["CUDA_VISIBLE_DEVICES"] == "0"


def test_slurm_wraps_in_srun(monkeypatch, tmp_path) -> None:
    _set_sifs(monkeypatch, fluid="/sif/fluid_segmentation.sif")
    monkeypatch.setattr(_config.settings, "apptainer_use_slurm", True, raising=False)
    monkeypatch.setattr(_config.settings, "apptainer_slurm_partition", "full_optima", raising=False)
    monkeypatch.setattr(_config.settings, "apptainer_slurm_account", "optima", raising=False)
    monkeypatch.setattr(_config.settings, "apptainer_gpu_device", "0", raising=False)  # ignored under SLURM
    monkeypatch.setattr(ap, "_spacing_mm", lambda p: (0.004, 0.02, 0.2))

    captured: dict = {}

    def fake_exec(cmd, env=None):
        captured["cmd"] = cmd
        captured["env"] = env
        out = tmp_path / "work" / "out"
        out.mkdir(parents=True, exist_ok=True)
        seg = np.zeros((2, 4, 4), dtype=np.uint8)
        seg[0] = 1
        np.savez(out / "fluidseg.npz", segmentation=seg)
        return ""

    monkeypatch.setattr(ap, "_exec", fake_exec)

    dcm_dir = tmp_path / "in"
    dcm_dir.mkdir()
    ap.ApptainerAdapter().full_volume("fluid", dcm_dir, "OD", out_dir_override=tmp_path / "work")

    cmd = captured["cmd"]
    assert cmd[0] == "srun"
    assert "--gres=gpu:1" in cmd
    assert "--time=01:00:00" in cmd
    assert "--partition=full_optima" in cmd
    assert "--account=optima" in cmd
    assert "apptainer" in cmd  # the apptainer invocation follows srun
    # SLURM owns GPU assignment — the dispatcher must NOT force CUDA_VISIBLE_DEVICES.
    assert "CUDA_VISIBLE_DEVICES" not in (captured["env"] or {})
