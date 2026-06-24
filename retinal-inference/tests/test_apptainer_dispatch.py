"""ApptainerAdapter — .sif gating + apptainer-exec dispatch (no cluster needed)."""

from __future__ import annotations

from pathlib import Path

import numpy as np
import pytest

from retinal_inference import config as _config
from retinal_inference.inference import apptainer as ap
from retinal_inference.inference.adapter import (
    FastScreenUnavailable,
    UnsupportedTaskError,
)


def _set_sifs(monkeypatch, **sifs) -> None:
    for task in ("fluid", "onl", "pr", "ga"):
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
    # Server returns the raw mask only; Java computes the mm³ volumes.
    assert res.primary_metric_value is None
    assert res.primary_metric_unit is None
    assert res.output_payload["segmentation_file"] == "fluidseg.npz"
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
    # Server returns the raw OPL-HFL + BMEIS surface CSVs; Java computes the µm.
    assert res.primary_metric_value is None
    assert res.primary_metric_unit is None
    assert set(res.output_payload["surface_csvs"]) == {"001-OPL-HFL.csv", "002-BMEIS.csv"}
    cmd = captured["cmd"]
    script_idx = next(i for i, c in enumerate(cmd) if c.endswith("process_input_for_optimus.py"))
    # The first positional after the script must be the .dcm FILE, not the dir.
    assert cmd[script_idx + 1].endswith("bscan.dcm")
    assert cmd[script_idx + 1] != str(dcm_dir)


def test_pr_converts_dicom_in_container(monkeypatch, tmp_path) -> None:
    # The DICOM->MHD conversion must run INSIDE the .sif (host dispatcher has no
    # SimpleITK); the model run follows in the same exec via bash -c.
    _set_sifs(monkeypatch, pr="/sif/pr.sif")
    monkeypatch.setattr(_config.settings, "pr_code", "/code/pr", raising=False)
    monkeypatch.setattr(_config.settings, "pr_weights", "/weights/u2net-cross-entropy", raising=False)
    monkeypatch.setattr(ap, "_spacing_mm", lambda p: (0.004, 0.02, 0.2))

    captured: dict = {}

    def fake_exec(cmd, env=None):
        captured["cmd"] = cmd
        out = tmp_path / "work" / "out"
        out.mkdir(parents=True, exist_ok=True)
        # The PR layer is bounded by the BMEIS and OB-OPR surfaces.
        (out / "001-BMEIS.csv").write_text("size\n30\n40\n")
        (out / "002-OB-OPR.csv").write_text("size\n50\n60\n")
        return ""

    monkeypatch.setattr(ap, "_exec", fake_exec)

    dcm_dir = tmp_path / "in"
    dcm_dir.mkdir()
    res = ap.ApptainerAdapter().full_volume(
        "pr", dcm_dir, "OD", out_dir_override=tmp_path / "work"
    )

    assert res.task == "pr"
    # Server returns the raw BMEIS + OB-OPR surface CSVs; Java computes the µm.
    assert res.primary_metric_value is None
    assert res.primary_metric_unit is None
    assert set(res.output_payload["surface_csvs"]) == {"001-BMEIS.csv", "002-OB-OPR.csv"}
    cmd = captured["cmd"]
    assert "bash" in cmd and "-c" in cmd
    payload = cmd[-1]  # the bash -c script
    assert "SimpleITK" in payload and "bscan.mhd" in payload
    assert "process_input_for_optimus.py" in payload
    assert "--export_for_optimus True" in payload
    # Manufacturer must be injected into the .mhd (vendor reads it for the resize path)
    assert "Manufacturer = Heidelberg Engineering" in payload


def test_pr_pyextra_binds_and_sets_pythonpath(monkeypatch, tmp_path) -> None:
    _set_sifs(monkeypatch, pr="/sif/pr.sif")
    monkeypatch.setattr(_config.settings, "pr_code", "/code/pr", raising=False)
    monkeypatch.setattr(_config.settings, "pr_weights", "/weights/u2net", raising=False)
    monkeypatch.setattr(_config.settings, "pr_pyextra", "/scratch/ri/pyextra", raising=False)
    monkeypatch.setattr(ap, "_spacing_mm", lambda p: (0.004, 0.02, 0.2))

    captured: dict = {}

    def fake_exec(cmd, env=None):
        captured["cmd"] = cmd
        out = tmp_path / "work" / "out"
        out.mkdir(parents=True, exist_ok=True)
        (out / "001-BMEIS.csv").write_text("size\n30\n40\n")
        (out / "002-OB-OPR.csv").write_text("size\n50\n60\n")
        return ""

    monkeypatch.setattr(ap, "_exec", fake_exec)

    dcm_dir = tmp_path / "in"
    dcm_dir.mkdir()
    ap.ApptainerAdapter().full_volume(
        "pr", dcm_dir, "OD", out_dir_override=tmp_path / "work"
    )

    cmd = captured["cmd"]
    assert any("/scratch/ri/pyextra" in c for c in cmd)  # bound
    assert "PYTHONPATH='/scratch/ri/pyextra'" in cmd[-1]  # on the run cmd


def test_ga_iowa_chain_libs_and_rmdir(monkeypatch, tmp_path) -> None:
    # GA host chain: IOWA binary + converter run with GA_IOWA_LD_LIBRARY_PATH on
    # LD_LIBRARY_PATH; the converter gets --rmdir_out 1 and its out dir is NOT
    # pre-created. Server returns the RPEL CSV (metric None).
    _set_sifs(monkeypatch, ga="/sif/ga.sif")
    monkeypatch.setattr(_config.settings, "ga_code", "/code/ga", raising=False)
    monkeypatch.setattr(_config.settings, "ga_weights", "/weights/filly", raising=False)
    monkeypatch.setattr(_config.settings, "ga_iowa_binary", "/scratch/ri/OCTLayerSeg3.6_owned", raising=False)
    monkeypatch.setattr(_config.settings, "ga_iowa_converter", "/fw/local_IOWA_LayerSegV3_to_CSV", raising=False)
    monkeypatch.setattr(_config.settings, "ga_iowa_ld_library_path", "/conda/lib:/fw/lib", raising=False)
    monkeypatch.setattr(ap, "_spacing_mm", lambda p: (0.004, 0.02, 0.2))

    calls: list = []

    def fake_exec(cmd, env=None):
        calls.append((cmd, env))
        if any("infer_sample_filly.py" in c for c in cmd):
            out = tmp_path / "work" / "out"
            out.mkdir(parents=True, exist_ok=True)
            (out / "001-RPEL.csv").write_text("size\n1\n2\n")
        return ""

    monkeypatch.setattr(ap, "_exec", fake_exec)

    dcm_dir = tmp_path / "in"
    dcm_dir.mkdir()
    res = ap.ApptainerAdapter().full_volume(
        "ga", dcm_dir, "OD", out_dir_override=tmp_path / "work"
    )

    assert res.task == "ga"
    assert res.primary_metric_value is None
    assert res.output_payload["rpel_csv"] == "001-RPEL.csv"
    # GA returns ONLY RPEL — not EZL/ELM, not the IOWA layers it consumed.
    assert res.artifact_names == ["001-RPEL.csv"]
    # IOWA binary call carries LD_LIBRARY_PATH with the configured dirs.
    iowa = next((c, e) for c, e in calls if c[0].endswith("OCTLayerSeg3.6_owned"))
    assert iowa[1] and "/conda/lib:/fw/lib" in iowa[1]["LD_LIBRARY_PATH"]
    # Converter call uses --rmdir_out 1 and the same LD env.
    conv = next((c, e) for c, e in calls if any("local_IOWA_LayerSegV3_to_CSV" in x for x in c))
    assert "--rmdir_out" in conv[0] and conv[0][conv[0].index("--rmdir_out") + 1] == "1"
    assert conv[1] and "LD_LIBRARY_PATH" in conv[1]


def test_bm_host_native_dispatch(monkeypatch, tmp_path) -> None:
    # BM has no .sif — supported via bm_code; runs the venv python on
    # application.py with bm_ld_library_path on LD_LIBRARY_PATH + CUDA dev.
    monkeypatch.setattr(_config.settings, "bm_python", "/bm/venv/bin/python3", raising=False)
    monkeypatch.setattr(_config.settings, "bm_code", "/bm/code", raising=False)
    monkeypatch.setattr(_config.settings, "bm_ld_library_path", "/mods/cuda/lib:/mods/py38/lib", raising=False)
    monkeypatch.setattr(_config.settings, "bm_gpu_device", "0", raising=False)
    monkeypatch.setattr(ap, "_spacing_mm", lambda p: (0.004, 0.02, 0.2))

    adapter = ap.ApptainerAdapter()
    assert adapter.supports("bm") is True

    captured: dict = {}

    def fake_exec(cmd, env=None):
        captured["cmd"] = cmd
        captured["env"] = env
        out = tmp_path / "work" / "out"
        out.mkdir(parents=True, exist_ok=True)
        (out / "001-Bruch's membrane (BM).csv").write_text("size\n1\n2\n")
        return ""

    monkeypatch.setattr(ap, "_exec", fake_exec)

    dcm_dir = tmp_path / "in"
    dcm_dir.mkdir()
    res = adapter.full_volume("bm", dcm_dir, "OD", out_dir_override=tmp_path / "work")

    assert res.task == "bm"
    assert res.primary_metric_value is None
    assert res.output_payload["surface_csvs"][0].endswith("(BM).csv")
    cmd = captured["cmd"]
    assert cmd[0] == "/bm/venv/bin/python3"
    assert any(c.endswith("application.py") for c in cmd)
    # host-native: no apptainer/singularity, no srun wrapping
    assert "singularity" not in cmd and "apptainer" not in cmd and cmd[0] != "srun"
    assert captured["env"]["LD_LIBRARY_PATH"].startswith("/mods/cuda/lib")
    assert captured["env"]["CUDA_VISIBLE_DEVICES"] == "0"


def test_layers_returns_iowa_stack_plus_bm(monkeypatch, tmp_path) -> None:
    # layers = 11 IOWA reference layers (binary+converter) + BM (venv). All 12
    # returned; supported when IOWA binary+converter and BM are configured.
    monkeypatch.setattr(_config.settings, "ga_iowa_binary", "/ri/OCTLayerSeg3.6_owned", raising=False)
    monkeypatch.setattr(_config.settings, "ga_iowa_converter", "/fw/local_IOWA_LayerSegV3_to_CSV", raising=False)
    monkeypatch.setattr(_config.settings, "ga_iowa_ld_library_path", "/conda/lib:/fw/lib", raising=False)
    monkeypatch.setattr(_config.settings, "bm_python", "/bm/venv/bin/python3", raising=False)
    monkeypatch.setattr(_config.settings, "bm_code", "/bm/code", raising=False)
    monkeypatch.setattr(_config.settings, "bm_ld_library_path", "/mods/lib", raising=False)
    monkeypatch.setattr(ap, "_spacing_mm", lambda p: (0.004, 0.02, 0.2))

    iowa_names = [f"{i:03d}-layer.csv" for i in range(1, 12)]  # 11 IOWA layers

    def fake_exec(cmd, env=None):
        if any("local_IOWA_LayerSegV3_to_CSV" in c for c in cmd):
            # converter --out <dir> is the element after --out
            out = Path(cmd[cmd.index("--out") + 1])
            out.mkdir(parents=True, exist_ok=True)
            for n in iowa_names:
                (out / n).write_text("size\n1\n2\n")
        elif any("application.py" in c for c in cmd):
            out = tmp_path / "work" / "bm_out"
            out.mkdir(parents=True, exist_ok=True)
            (out / "001-Bruch's membrane (BM).csv").write_text("size\n3\n4\n")
        return ""

    monkeypatch.setattr(ap, "_exec", fake_exec)

    adapter = ap.ApptainerAdapter()
    assert adapter.supports("layers") is True

    dcm_dir = tmp_path / "in"
    dcm_dir.mkdir()
    res = adapter.full_volume("layers", dcm_dir, "OD", out_dir_override=tmp_path / "work")

    assert res.task == "layers"
    assert res.primary_metric_value is None
    assert res.artifact_names is not None
    assert len(res.artifact_names) == 12  # 11 IOWA + 1 BM
    assert "001-Bruch's membrane (BM).csv" in res.artifact_names
    assert set(iowa_names) <= set(res.artifact_names)


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
