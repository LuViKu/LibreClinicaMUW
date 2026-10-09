"""SLURM mode: srun construction, startup validation, cancellation, concurrency, /health."""

from __future__ import annotations

import asyncio
import subprocess
import threading
import time

import httpx
import pytest

from retinal_inference import config as _config
from retinal_inference.api import run as run_module
from retinal_inference.inference import apptainer as ap
from retinal_inference.inference.adapter import cancel_event, reset_adapter
from retinal_inference.models.responses import FullVolumeResult


@pytest.fixture
def slurm(monkeypatch):
    """SLURM mode on with a valid minimal config (account + typed gres)."""
    s = _config.settings
    monkeypatch.setattr(s, "inference_adapter", "apptainer", raising=False)
    monkeypatch.setattr(s, "apptainer_use_slurm", True, raising=False)
    monkeypatch.setattr(s, "apptainer_slurm_account", "ACCOUNT", raising=False)
    monkeypatch.setattr(s, "apptainer_slurm_gres", "gpu:nv2080ti:1", raising=False)
    monkeypatch.setattr(s, "apptainer_slurm_partition", "full_optima", raising=False)
    for name in ("mem", "cpus_per_task", "qos", "constraint"):
        monkeypatch.setattr(s, f"apptainer_slurm_{name}", None, raising=False)
    monkeypatch.setattr(s, "apptainer_slurm_allow_untyped_gres", False, raising=False)
    monkeypatch.setattr(s, "apptainer_slurm_job_name", "ri", raising=False)
    monkeypatch.setattr(s, "max_concurrent_runs", None, raising=False)
    monkeypatch.setattr(s, "fluid_sif", "/sif/fluid.sif", raising=False)
    return s


# ---------- srun construction --------------------------------------------------


def test_srun_typed_gres_and_account(slurm) -> None:
    cmd = ap.ApptainerAdapter._srun("fluid")
    assert cmd[0] == "srun"
    assert "--gres=gpu:nv2080ti:1" in cmd
    assert "--account=ACCOUNT" in cmd
    assert "--partition=full_optima" in cmd
    assert "--time=01:00:00" in cmd
    # optional flags absent unless configured
    assert not any(a.startswith(("--mem", "--cpus", "--qos", "--constraint")) for a in cmd)


def test_srun_optional_flags(slurm, monkeypatch) -> None:
    monkeypatch.setattr(slurm, "apptainer_slurm_mem", "32G", raising=False)
    monkeypatch.setattr(slurm, "apptainer_slurm_cpus_per_task", 8, raising=False)
    monkeypatch.setattr(slurm, "apptainer_slurm_qos", "normal", raising=False)
    monkeypatch.setattr(slurm, "apptainer_slurm_constraint", "turing", raising=False)
    cmd = ap.ApptainerAdapter._srun("onl")
    for flag in ("--mem=32G", "--cpus-per-task=8", "--qos=normal", "--constraint=turing"):
        assert flag in cmd


def test_job_name_has_prefix_and_task_only(slurm, monkeypatch, tmp_path) -> None:
    monkeypatch.setattr(slurm, "apptainer_slurm_job_name", "muw", raising=False)
    adapter = ap.ApptainerAdapter()
    cmd = adapter._apptainer(
        "run", "/sif/fluid.sif", [f"{tmp_path}/PATIENT-123:/in"], ["x"], task="fluid"
    )
    names = [a for a in cmd if a.startswith("--job-name=")]
    assert names == ["--job-name=muw-fluid"]


def test_account_required(slurm, monkeypatch) -> None:
    monkeypatch.setattr(slurm, "apptainer_slurm_account", None, raising=False)
    with pytest.raises(RuntimeError, match="SLURM_ACCOUNT"):
        ap.ApptainerAdapter()
    monkeypatch.setattr(slurm, "apptainer_slurm_account", "  ", raising=False)
    with pytest.raises(RuntimeError, match="SLURM_ACCOUNT"):
        ap.ApptainerAdapter()


@pytest.mark.parametrize("gres", ["gpu:1", "gpu", "gpu:nv2080ti", ""])
def test_untyped_gres_refused(slurm, monkeypatch, gres) -> None:
    monkeypatch.setattr(slurm, "apptainer_slurm_gres", gres, raising=False)
    with pytest.raises(RuntimeError, match="untyped"):
        ap.ApptainerAdapter()


def test_untyped_gres_allowed_with_override(slurm, monkeypatch) -> None:
    monkeypatch.setattr(slurm, "apptainer_slurm_gres", "gpu:1", raising=False)
    monkeypatch.setattr(slurm, "apptainer_slurm_allow_untyped_gres", True, raising=False)
    ap.ApptainerAdapter()  # no error
    assert "--gres=gpu:1" in ap.ApptainerAdapter._srun("fluid")


def test_direct_mode_needs_no_account(monkeypatch) -> None:
    monkeypatch.setattr(_config.settings, "apptainer_use_slurm", False, raising=False)
    monkeypatch.setattr(_config.settings, "apptainer_slurm_account", None, raising=False)
    ap.ApptainerAdapter()


def test_bm_runs_under_srun_without_gpu_pin(slurm, monkeypatch, tmp_path) -> None:
    monkeypatch.setattr(slurm, "bm_python", "/bm/venv/bin/python3", raising=False)
    monkeypatch.setattr(slurm, "bm_code", "/bm/code", raising=False)
    monkeypatch.setattr(slurm, "bm_ld_library_path", "/mods/lib", raising=False)
    monkeypatch.setattr(slurm, "bm_gpu_device", "0", raising=False)
    monkeypatch.setattr(slurm, "apptainer_gpu_device", "0", raising=False)
    monkeypatch.setattr(ap, "_spacing_mm", lambda p: (0.004, 0.02, 0.2))
    captured: dict = {}

    def fake_exec(cmd, env=None):
        captured["cmd"], captured["env"] = cmd, env
        out = tmp_path / "work" / "out"
        out.mkdir(parents=True, exist_ok=True)
        (out / "001-Bruch's membrane (BM).csv").write_text("size\n1\n")
        return ""

    monkeypatch.setattr(ap, "_exec", fake_exec)
    d = tmp_path / "in"
    d.mkdir()
    ap.ApptainerAdapter().full_volume("bm", d, "OD", out_dir_override=tmp_path / "work")
    assert captured["cmd"][0] == "srun"
    assert "--job-name=ri-bm" in captured["cmd"]
    assert "CUDA_VISIBLE_DEVICES" not in captured["env"]
    assert captured["env"]["LD_LIBRARY_PATH"].startswith("/mods/lib")


# ---------- cancellation terminates srun --------------------------------------


class _FakeProc:
    """Popen stand-in: communicate() never finishes until terminate()/kill()."""

    def __init__(self, ignore_term: bool = False):
        self.ignore_term = ignore_term
        self.terminated = False
        self.killed = False
        self.returncode: int | None = None

    def poll(self):
        return self.returncode

    def communicate(self, timeout=None):
        if self.returncode is not None:
            return ("", "")
        time.sleep(min(timeout or 0.01, 0.01))
        raise subprocess.TimeoutExpired("srun", timeout)

    def terminate(self):
        self.terminated = True
        if not self.ignore_term:
            self.returncode = -15

    def kill(self):
        self.killed = True
        self.returncode = -9

    def wait(self, timeout=None):
        if self.returncode is None:
            raise subprocess.TimeoutExpired("srun", timeout)
        return self.returncode


def _patch_popen(monkeypatch, proc):
    monkeypatch.setattr(ap.subprocess, "Popen", lambda *a, **k: proc)
    monkeypatch.setattr(ap, "_POLL_S", 0.01)


def test_cancel_event_terminates_srun(monkeypatch) -> None:
    proc = _FakeProc()
    _patch_popen(monkeypatch, proc)
    ev = threading.Event()
    token = cancel_event.set(ev)
    try:
        threading.Timer(0.05, ev.set).start()
        with pytest.raises(RuntimeError, match="cancelled"):
            ap._exec(["srun", "--job-name=ri-fluid", "true"])
    finally:
        cancel_event.reset(token)
    assert proc.terminated and not proc.killed  # SIGTERM -> srun cancels the job


def test_timeout_terminates_srun(monkeypatch) -> None:
    proc = _FakeProc()
    _patch_popen(monkeypatch, proc)
    monkeypatch.setattr(ap, "_TIMEOUT_S", 0.05)
    with pytest.raises(subprocess.TimeoutExpired):
        ap._exec(["srun", "true"])
    assert proc.terminated and not proc.killed


def test_sigkill_after_grace_if_term_ignored(monkeypatch) -> None:
    proc = _FakeProc(ignore_term=True)
    _patch_popen(monkeypatch, proc)
    monkeypatch.setattr(ap, "_TIMEOUT_S", 0.02)
    monkeypatch.setattr(ap, "_TERM_GRACE_S", 0.02)
    with pytest.raises(subprocess.TimeoutExpired):
        ap._exec(["srun", "true"])
    assert proc.terminated and proc.killed


def test_exec_nonzero_rc_raises_with_stderr(monkeypatch) -> None:
    class _Done(_FakeProc):
        def communicate(self, timeout=None):
            self.returncode = 3
            return ("", "boom")

    _patch_popen(monkeypatch, _Done())
    with pytest.raises(RuntimeError, match="rc=3.*boom"):
        ap._exec(["srun", "true"])


# ---------- /run concurrency ---------------------------------------------------


class _SlowAdapter:
    """Records peak overlap of full_volume calls."""

    def __init__(self):
        self.active = 0
        self.peak = 0
        self._lock = threading.Lock()

    def supports(self, task):
        return True

    def full_volume(self, task, path, laterality, out_dir_override=None, scan_index=0):
        with self._lock:
            self.active += 1
            self.peak = max(self.peak, self.active)
        time.sleep(0.3)
        with self._lock:
            self.active -= 1
        (out_dir_override / "o.csv").write_text("a\n1\n")
        return FullVolumeResult(
            task=task, primary_metric_value=None, primary_metric_unit=None,
            output_payload={"csv": "o.csv"}, en_face_mask_path=None,
            bscan_masks_dir=None, pixel_scale_mm=0.004, confidence=0.9,
            model_version="t", artifact_names=["o.csv"],
        )


def _two_runs(monkeypatch, tmp_path, limit, slurm_on) -> int:
    s = _config.settings
    monkeypatch.setattr(s, "run_endpoint_enabled", True, raising=False)
    monkeypatch.setattr(s, "auth_token", "tok", raising=False)
    monkeypatch.setattr(s, "shared_tmpdir", tmp_path / "tmp", raising=False)
    monkeypatch.setattr(s, "apptainer_use_slurm", slurm_on, raising=False)
    monkeypatch.setattr(s, "max_concurrent_runs", limit, raising=False)
    adapter = _SlowAdapter()
    monkeypatch.setattr(run_module, "get_adapter", lambda: adapter)
    run_module._idempotency_cache.clear()

    from tests.oct_dicom_factory import make_opt

    async def go():
        from retinal_inference.main import app

        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url="http://t") as c:
            async def one(i):
                return await c.post(
                    "/run",
                    # A readable Spectralis DICOM: /run gates DICOM inputs by device.
                    files={"file": ("bscan.dcm", make_opt())},
                    data={"task": "fluid", "laterality": "OD"},
                    headers={"X-MUW-Inference-Token": "tok", "Idempotency-Key": f"k{i}"},
                )
            return await asyncio.gather(one(1), one(2))

    rs = asyncio.run(go())
    assert [r.status_code for r in rs] == [200, 200]
    return adapter.peak


def test_two_runs_overlap_with_limit_two(monkeypatch, tmp_path) -> None:
    assert _two_runs(monkeypatch, tmp_path, limit=2, slurm_on=True) == 2


def test_two_runs_serialise_with_limit_one(monkeypatch, tmp_path) -> None:
    assert _two_runs(monkeypatch, tmp_path, limit=1, slurm_on=True) == 1


def test_default_limit_per_mode(monkeypatch) -> None:
    s = _config.settings
    monkeypatch.setattr(s, "max_concurrent_runs", None, raising=False)
    monkeypatch.setattr(s, "apptainer_use_slurm", False, raising=False)
    assert s.effective_max_concurrent_runs == 1
    monkeypatch.setattr(s, "apptainer_use_slurm", True, raising=False)
    assert s.effective_max_concurrent_runs == 4
    monkeypatch.setattr(s, "max_concurrent_runs", 2, raising=False)
    assert s.effective_max_concurrent_runs == 2


# ---------- /health ------------------------------------------------------------


def test_health_direct_mode(app_client) -> None:
    body = app_client.get("/health").json()
    assert body["mode"] == "direct"
    assert body["max_concurrent_runs"] == 1
    assert body["slurm_partition"] is None and body["slurm_gres"] is None


def test_health_slurm_mode_reports_partition_gres_no_account(
    app_client, monkeypatch
) -> None:
    # app_client reloads settings (placeholder adapter); set SLURM fields after.
    s = _config.settings
    monkeypatch.setattr(s, "inference_adapter", "apptainer", raising=False)
    monkeypatch.setattr(s, "apptainer_use_slurm", True, raising=False)
    monkeypatch.setattr(s, "apptainer_slurm_account", "ACCOUNT", raising=False)
    monkeypatch.setattr(s, "apptainer_slurm_partition", "full_optima", raising=False)
    monkeypatch.setattr(s, "apptainer_slurm_gres", "gpu:nv2080ti:1", raising=False)
    monkeypatch.setattr(s, "apptainer_gpu_device", "3", raising=False)
    monkeypatch.setattr(s, "max_concurrent_runs", 3, raising=False)
    resp = app_client.get("/health")  # adapter singleton stays placeholder
    body = resp.json()
    assert body["mode"] == "slurm"
    assert body["slurm_partition"] == "full_optima"
    assert body["slurm_gres"] == "gpu:nv2080ti:1"
    assert body["max_concurrent_runs"] == 3
    assert body["gpu_device"] is None  # no pin in SLURM mode
    assert "ACCOUNT" not in resp.text


# ---------- exclude / nodelist / IOWA under srun --------------------------------


def test_srun_exclude_and_nodelist(slurm, monkeypatch) -> None:
    monkeypatch.setattr(slurm, "apptainer_slurm_exclude", "vn1,vn2,cn5", raising=False)
    monkeypatch.setattr(slurm, "apptainer_slurm_nodelist", "on3,cn6", raising=False)
    cmd = ap.ApptainerAdapter._srun("fluid")
    assert "--exclude=vn1,vn2,cn5" in cmd
    assert "--nodelist=on3,cn6" in cmd


def test_srun_exclude_absent_by_default(slurm) -> None:
    cmd = ap.ApptainerAdapter._srun("fluid")
    assert not any(a.startswith(("--exclude", "--nodelist")) for a in cmd)


def test_cpu_srun_has_no_gres_and_own_sizing(slurm, monkeypatch) -> None:
    monkeypatch.setattr(slurm, "apptainer_slurm_mem", "32G", raising=False)
    monkeypatch.setattr(slurm, "apptainer_slurm_cpus_per_task", 8, raising=False)
    monkeypatch.setattr(slurm, "apptainer_slurm_iowa_mem", "4G", raising=False)
    monkeypatch.setattr(slurm, "apptainer_slurm_iowa_cpus_per_task", 2, raising=False)
    monkeypatch.setattr(slurm, "apptainer_slurm_exclude", "vn1", raising=False)
    monkeypatch.setattr(slurm, "apptainer_slurm_nodelist", "on3", raising=False)
    cmd = ap.ApptainerAdapter._srun("iowa", gpu=False)
    assert not any(a.startswith("--gres") for a in cmd)
    assert not any(a.startswith("--nodelist") for a in cmd)
    assert "--mem=4G" in cmd and "--cpus-per-task=2" in cmd
    assert "--account=ACCOUNT" in cmd and "--partition=full_optima" in cmd
    assert "--time=01:00:00" in cmd and "--exclude=vn1" in cmd
    assert "--job-name=ri-iowa" in cmd


def test_iowa_runs_under_cpu_srun_in_slurm_mode(slurm, monkeypatch, tmp_path) -> None:
    monkeypatch.setattr(slurm, "ga_iowa_binary", "/opt/OCTLayerSeg3.6", raising=False)
    monkeypatch.setattr(slurm, "ga_iowa_converter", "/opt/conv", raising=False)
    monkeypatch.setattr(slurm, "ga_iowa_ld_library_path", "/ioawa/lib", raising=False)
    calls: list = []

    def fake_exec(cmd, env=None):
        calls.append((cmd, env))
        return ""

    monkeypatch.setattr(ap, "_exec", fake_exec)
    work = tmp_path / "work"
    work.mkdir()
    dcm = tmp_path / "in" / "bscan.dcm"
    dcm.parent.mkdir()
    dcm.write_bytes(b"x")
    out = ap.ApptainerAdapter()._iowa_layers(dcm, work)
    assert out == work / "layers_csv"
    assert len(calls) == 1  # whole chain = one job
    cmd, env = calls[0]
    assert cmd[0] == "srun" and "--job-name=ri-iowa" in cmd
    assert not any(a.startswith("--gres") for a in cmd)
    script = cmd[-1]
    assert cmd[-3:-1] == ["bash", "-c"]
    # stages on the compute node's own /tmp, runs both binaries, copies back to work
    assert "mktemp -d /tmp/iowa_" in script
    assert "/opt/OCTLayerSeg3.6" in script and "/opt/conv" in script
    assert str(work / "layers_csv") in script and str(work / "layerseg") in script
    assert env["LD_LIBRARY_PATH"].startswith("/ioawa/lib")
