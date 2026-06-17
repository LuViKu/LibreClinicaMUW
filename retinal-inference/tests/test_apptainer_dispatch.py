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


def test_rejects_e2e_input(monkeypatch, tmp_path) -> None:
    # DICOM-only: the backend converts .e2e -> .dcm; an .e2e must be rejected.
    _set_sifs(monkeypatch, fluid="/sif/fluid.sif")
    e2e = tmp_path / "scan.e2e"
    e2e.write_bytes(b"x")
    with pytest.raises(ValueError):
        ap.ApptainerAdapter().full_volume("fluid", e2e, "OD")


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


def test_onl_passes_dcm_file_not_dir(monkeypatch, tmp_path) -> None:
    # Regression: process_input_for_optimus.py dcmread()s its first arg, so it must
    # be the bscan.dcm FILE; passing the dir raises IsADirectoryError in-container.
    _set_sifs(monkeypatch, onl="/sif/sese_onl.sif")
    monkeypatch.setattr(_config.settings, "onl_code", "/code/onl", raising=False)
    monkeypatch.setattr(_config.settings, "onl_weights", "/weights/cross_val_ga", raising=False)
    monkeypatch.setattr(ap, "_spacing_mm", lambda p: (0.004, 0.02, 0.2))

    captured: dict = {}

    def fake_exec(cmd, env=None):
        captured["cmd"] = cmd
        out = tmp_path / "work" / "out"
        out.mkdir(parents=True, exist_ok=True)
        (out / "001-OPL-HFL.csv").write_text("size\n10\n20\n")
        (out / "002-BMEIS.csv").write_text("size\n15\n25\n")
        return ""

    monkeypatch.setattr(ap, "_exec", fake_exec)

    dcm_dir = tmp_path / "in"
    dcm_dir.mkdir()
    res = ap.ApptainerAdapter().full_volume(
        "onl", dcm_dir, "OD", out_dir_override=tmp_path / "work"
    )

    assert res.task == "onl"
    assert res.primary_metric_unit == "µm"
    cmd = captured["cmd"]
    script_idx = next(i for i, c in enumerate(cmd) if c.endswith("process_input_for_optimus.py"))
    # The first positional after the script must be the .dcm FILE, not the dir.
    assert cmd[script_idx + 1].endswith("bscan.dcm")
    assert cmd[script_idx + 1] != str(dcm_dir)


def test_bmeis_converts_dicom_in_container(monkeypatch, tmp_path) -> None:
    # The DICOM->MHD conversion must run INSIDE the .sif (host dispatcher has no
    # SimpleITK); the model run follows in the same exec via bash -c.
    _set_sifs(monkeypatch, bmeis="/sif/bmeis.sif")
    monkeypatch.setattr(_config.settings, "bmeis_code", "/code/pr", raising=False)
    monkeypatch.setattr(_config.settings, "bmeis_weights", "/weights/u2net-cross-entropy", raising=False)
    monkeypatch.setattr(ap, "_spacing_mm", lambda p: (0.004, 0.02, 0.2))

    captured: dict = {}

    def fake_exec(cmd, env=None):
        captured["cmd"] = cmd
        out = tmp_path / "work" / "out"
        out.mkdir(parents=True, exist_ok=True)
        (out / "001-BMEIS.csv").write_text("size\n30\n40\n")
        return ""

    monkeypatch.setattr(ap, "_exec", fake_exec)

    dcm_dir = tmp_path / "in"
    dcm_dir.mkdir()
    res = ap.ApptainerAdapter().full_volume(
        "bmeis", dcm_dir, "OD", out_dir_override=tmp_path / "work"
    )

    assert res.task == "bmeis"
    assert res.primary_metric_unit == "µm"
    cmd = captured["cmd"]
    assert "bash" in cmd and "-c" in cmd
    payload = cmd[-1]  # the bash -c script
    assert "SimpleITK" in payload and "bscan.mhd" in payload
    assert "process_input_for_optimus.py" in payload
    assert "--export_for_optimus True" in payload


def test_bmeis_pyextra_binds_and_sets_pythonpath(monkeypatch, tmp_path) -> None:
    _set_sifs(monkeypatch, bmeis="/sif/bmeis.sif")
    monkeypatch.setattr(_config.settings, "bmeis_code", "/code/pr", raising=False)
    monkeypatch.setattr(_config.settings, "bmeis_weights", "/weights/u2net", raising=False)
    monkeypatch.setattr(_config.settings, "bmeis_pyextra", "/scratch/ri/pyextra", raising=False)
    monkeypatch.setattr(ap, "_spacing_mm", lambda p: (0.004, 0.02, 0.2))

    captured: dict = {}

    def fake_exec(cmd, env=None):
        captured["cmd"] = cmd
        out = tmp_path / "work" / "out"
        out.mkdir(parents=True, exist_ok=True)
        (out / "001-BMEIS.csv").write_text("size\n30\n40\n")
        return ""

    monkeypatch.setattr(ap, "_exec", fake_exec)

    dcm_dir = tmp_path / "in"
    dcm_dir.mkdir()
    ap.ApptainerAdapter().full_volume(
        "bmeis", dcm_dir, "OD", out_dir_override=tmp_path / "work"
    )

    cmd = captured["cmd"]
    assert any("/scratch/ri/pyextra" in c for c in cmd)  # bound
    assert "PYTHONPATH='/scratch/ri/pyextra'" in cmd[-1]  # on the run cmd


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
