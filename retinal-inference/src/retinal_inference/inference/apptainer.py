"""ApptainerAdapter — GPU-cluster dispatch via ``apptainer exec`` (DR-022).

On the OPTIMA Apptainer cluster there is no Docker/compose. This adapter runs
each model's ``.sif`` as a subprocess (``apptainer exec --nv`` / ``apptainer
run``), the same pattern the original OPTIMA ``vrcbin`` wrappers used. The
LibreClinica backend preprocesses the ``.e2e`` and posts a ``bscan.dcm`` to the
sidecar's ``/run`` endpoint, so this adapter's input is already a DICOM — it
does no oct-converter ingestion.

A task is only ``supports()``-ed when its ``.sif`` is configured (gates GA, and
any task whose image isn't present). Per-task ``.sif``/code/weights come from
config.

The server returns *raw segmentation artifacts only* — the Java backend computes
every clinical metric. So each handler runs the model, confirms the expected
output files exist, and returns those file basenames as the ``output_payload``
with ``primary_metric_value``/``primary_metric_unit`` left ``None``. No fluid
mm³ / ONL-PR µm / GA mm² is computed here.

Cluster caveats encoded here:
  * the pr task (sese_pr, torch1.0/CUDA9) has no Turing kernels → pin to a
    non-Turing GPU via ``apptainer_pr_gpu_device`` (or "" for CPU).
  * ``apptainer_use_slurm`` wraps every GPU call (the .sif tasks and the
    host-native bm venv) in a blocking ``srun`` -- one SLURM job per task, with a
    typed ``--gres`` so it only lands on Turing nodes. The dispatcher pins no GPU
    in that mode. The host-native IOWA step (ga / layers) is CPU-only and still
    runs on the dispatcher host. See docs/cluster-deployment.md section 4.

Every command + parse below mirrors the vendor wrapper invocations and is
flagged "confirm on the cluster" — not runnable without the .sif images + GPUs.
"""

from __future__ import annotations

import os
import subprocess
import tempfile
import threading
import time
from pathlib import Path
from typing import Any, Literal

from retinal_inference import config as _config
from retinal_inference.models.responses import FastScreenResult, FullVolumeResult
from retinal_inference.tasks import SUPPORTED_TASKS, TaskName

from .adapter import cancel_event as _cancel_event
from .adapter import (
    FastScreenUnavailable,
    RetinalInferenceAdapter,
    UnsupportedTaskError,
)

_TIMEOUT_S = 3600
# Seconds a terminated child (typically ``srun``) gets to forward the signal and
# cancel its SLURM job before it is SIGKILLed.
_TERM_GRACE_S = 15
_POLL_S = 1.0

def _terminate(proc: subprocess.Popen) -> None:
    """SIGTERM first, SIGKILL after a grace period.

    SIGTERM matters for ``srun``: it forwards the signal and cancels the job
    step, so SLURM frees the GPU. A bare SIGKILL gives srun no chance to do so
    and can leave the job running until the controller notices the dead step.
    """
    if proc.poll() is not None:
        return
    proc.terminate()
    try:
        proc.wait(timeout=_TERM_GRACE_S)
    except subprocess.TimeoutExpired:
        proc.kill()
        proc.wait()


def _exec(cmd: list[str], env: dict[str, str] | None = None) -> str:
    """Run a subprocess, raising RuntimeError with stderr on failure.

    Single choke-point so unit tests can monkeypatch all dispatch. On timeout,
    on cancellation (see ``_cancel_event``) or on any other exception the child
    is terminated (SIGTERM, then SIGKILL) -- under SLURM that cancels the job.
    """
    full_env = {**os.environ, **(env or {})}
    cancel = _cancel_event.get()
    proc = subprocess.Popen(
        cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, env=full_env
    )
    deadline = time.monotonic() + _TIMEOUT_S
    try:
        while True:
            try:
                stdout, stderr = proc.communicate(timeout=_POLL_S)
                break
            except subprocess.TimeoutExpired:
                if cancel is not None and cancel.is_set():
                    _terminate(proc)
                    raise RuntimeError(f"{cmd[0]} cancelled (request abandoned)") from None
                if time.monotonic() >= deadline:
                    _terminate(proc)
                    raise subprocess.TimeoutExpired(cmd, _TIMEOUT_S) from None
    except BaseException:
        _terminate(proc)
        raise
    if proc.returncode != 0:
        raise RuntimeError(
            f"{cmd[0]} failed (rc={proc.returncode}): {stderr or stdout}"
        )
    return stdout


def _resolve_dcm(path: Path) -> tuple[Path, Path]:
    """Return (dir, bscan.dcm) for a path that is either the dir or the file."""
    path = Path(path)
    if path.is_dir():
        return path, path / "bscan.dcm"
    return path.parent, path


def _spacing_mm(dcm_file: Path) -> tuple[float, float, float]:
    """(axial, lateral, slice) mm from the bscan.dcm."""
    import pydicom

    ds = pydicom.dcmread(str(dcm_file), stop_before_pixels=True)
    axial, lateral = float(ds.PixelSpacing[0]), float(ds.PixelSpacing[1])
    slice_mm = float(getattr(ds, "SpacingBetweenSlices", lateral))
    return axial, lateral, slice_mm


class ApptainerAdapter(RetinalInferenceAdapter):
    """Dispatch each task to its model ``.sif`` via apptainer on the GPU cluster."""

    _model_version = "optima-apptainer-v1"

    def __init__(self) -> None:
        s = _config.settings
        s.validate_slurm()  # SLURM mode: account required, typed gres (startup error)
        self._sif: dict[TaskName, str | None] = {
            "fluid": s.fluid_sif,
            "onl": s.onl_sif,
            "pr": s.pr_sif,
            "ga": s.ga_sif,
        }

    @property
    def model_version(self) -> str:
        return self._model_version

    def supports(self, task: TaskName) -> bool:
        if task not in SUPPORTED_TASKS:
            return False
        # BM is host-native (venv, no .sif) — gate it on its code/python instead.
        if task == "bm":
            s = _config.settings
            return bool(s.bm_code or s.bm_python)
        # layers = IOWA 11-layer stack + BM: needs the IOWA binary+converter and BM.
        if task == "layers":
            s = _config.settings
            return bool(s.ga_iowa_binary and s.ga_iowa_converter and (s.bm_code or s.bm_python))
        return bool(self._sif.get(task))

    def fast_screen(
        self, task: TaskName, e2e_path: Path, laterality: Literal["OD", "OS"]
    ) -> FastScreenResult:
        raise FastScreenUnavailable(
            f"Task '{task}' is async-only; enqueue the job and poll /jobs/{{id}}."
        )

    # --- dispatch helpers ----------------------------------------------------

    def _gpu_env(self, task: TaskName) -> dict[str, str]:
        s = _config.settings
        if s.apptainer_use_slurm:
            # SLURM assigns the GPU (via --gres) and sets CUDA_VISIBLE_DEVICES in
            # the job env; don't override it from the dispatcher.
            return {}
        dev = s.apptainer_pr_gpu_device if task == "pr" else s.apptainer_gpu_device
        if dev is None:
            return {}
        # Apptainer forwards APPTAINERENV_* into the container.
        return {"CUDA_VISIBLE_DEVICES": dev, "APPTAINERENV_CUDA_VISIBLE_DEVICES": dev}

    @staticmethod
    def _srun(task: str, gpu: bool = True) -> list[str]:
        """The ``srun`` prefix for one task (SLURM mode only).

        One blocking job per task. Job name is ``<prefix>-<task>`` -- never a
        patient / scan identifier (job names are world-visible in squeue).
        ``gpu=False`` builds the CPU-only variant (no gres, no nodelist, IOWA's
        own cpus/mem) used for the host-native IOWA step.
        """
        s = _config.settings
        s.validate_slurm()
        srun = [
            "srun",
            f"--job-name={s.apptainer_slurm_job_name}-{task}",
            f"--time={s.apptainer_slurm_time}",
            f"--account={s.apptainer_slurm_account}",
        ]
        if gpu:
            srun.append(f"--gres={s.apptainer_slurm_gres}")
        if s.apptainer_slurm_partition:
            srun.append(f"--partition={s.apptainer_slurm_partition}")
        mem = s.apptainer_slurm_mem if gpu else s.apptainer_slurm_iowa_mem
        cpus = s.apptainer_slurm_cpus_per_task if gpu else s.apptainer_slurm_iowa_cpus_per_task
        if mem:
            srun.append(f"--mem={mem}")
        if cpus:
            srun.append(f"--cpus-per-task={cpus}")
        if s.apptainer_slurm_qos:
            srun.append(f"--qos={s.apptainer_slurm_qos}")
        if gpu and s.apptainer_slurm_constraint:
            srun.append(f"--constraint={s.apptainer_slurm_constraint}")
        if s.apptainer_slurm_exclude:
            srun.append(f"--exclude={s.apptainer_slurm_exclude}")
        if gpu and s.apptainer_slurm_nodelist:
            srun.append(f"--nodelist={s.apptainer_slurm_nodelist}")
        return srun

    def _apptainer(
        self, verb: str, sif: str, binds: list[str], args: list[str], task: str = "task"
    ) -> list[str]:
        s = _config.settings
        cmd = [s.apptainer_bin, verb, "-e", "--nv", "--no-home"]
        if binds:
            cmd += ["--bind", ",".join(binds)]
        cmd += [sif, *args]
        if s.apptainer_use_slurm:
            # One blocking SLURM job per scan (cluster caps walltime at 2d, so no
            # persistent GPU service). srun allocates a GPU and runs to completion.
            cmd = self._srun(task) + cmd
        return cmd

    # --- per-task handlers (return the generic result fields) ----------------

    def _fluid(self, dcm_dir: Path, work: Path) -> dict[str, Any]:
        out = work / "out"
        out.mkdir(parents=True, exist_ok=True)
        # v2.5.0 .sif: run_inference.py reads /workdir/input-data, writes fluidseg.npz.
        cmd = self._apptainer(
            "run",
            _config.settings.fluid_sif or "",
            [f"{dcm_dir}:/workdir/input-data", f"{out}:/workdir/output"],
            ["/workdir/AWS/run_inference.py", "--input_folder", "/workdir/input-data",
             "--optima_spacing", "--run_local"],
            task="fluid",
        )
        _exec(cmd, self._gpu_env("fluid"))
        # Server returns the raw fluid mask only; Java computes the IRF/SRF/PED mm³.
        npz, npy = out / "fluidseg.npz", out / "fluidseg.npy"
        if npz.is_file():
            seg_file = npz.name
        elif npy.is_file():
            seg_file = npy.name
        else:
            raise RuntimeError(f"fluid model produced no fluidseg.npz/.npy in {out}")
        axial = _spacing_mm(dcm_dir / "bscan.dcm")[0]
        return {"primary_metric_value": None, "primary_metric_unit": None,
                "output_payload": {"segmentation_file": seg_file},
                "pixel_scale_mm": axial, "artifact_names": [seg_file]}

    def _onl(self, dcm_dir: Path, work: Path) -> dict[str, Any]:
        import glob

        s = _config.settings
        out = work / "out"
        out.mkdir(parents=True, exist_ok=True)
        code = Path(s.onl_code or "/opt/sese_onl")
        weights = Path(s.onl_weights or "/weights")
        # process_input_for_optimus.py's first arg is the bscan.dcm FILE (it calls
        # pydicom.dcmread on it directly), not the containing dir — passing the dir
        # raises IsADirectoryError. Bind the dir so the file resolves inside.
        cmd = self._apptainer(
            "exec", s.onl_sif or "",
            [str(code), str(weights), str(dcm_dir), str(out)],
            ["python", str(code / "process_input_for_optimus.py"),
             str(dcm_dir / "bscan.dcm"), str(out), str(weights)],
            task="onl",
        )
        _exec(cmd, self._gpu_env("onl"))
        # ONL is bounded by the OPL-HFL and BMEIS surfaces; the server returns
        # both surface CSVs and Java computes the ONL thickness (µm) from them.
        upper = sorted(glob.glob(str(out / "*OPL-HFL*.csv")))
        lower = sorted(glob.glob(str(out / "*BMEIS*.csv")))
        if not upper or not lower:
            raise RuntimeError(f"sese_onl produced no boundary CSVs in {out}")
        axial = _spacing_mm(dcm_dir / "bscan.dcm")[0]
        names = [Path(upper[0]).name, Path(lower[0]).name]
        return {"primary_metric_value": None, "primary_metric_unit": None,
                "output_payload": {"surface_csvs": names},
                "pixel_scale_mm": axial, "artifact_names": names}

    def _pr(self, dcm_dir: Path, work: Path) -> dict[str, Any]:
        import glob

        s = _config.settings
        out = work / "out"
        mhd = work / "mhd_in"
        out.mkdir(parents=True, exist_ok=True)
        mhd.mkdir(parents=True, exist_ok=True)
        code = Path(s.pr_code or "/opt/sese_pr")
        weights = Path(s.pr_weights or "/weights/u2net-cross-entropy")
        dcm = dcm_dir / "bscan.dcm"
        mhd_file = mhd / "bscan.mhd"
        binds = [str(code), str(weights), str(dcm_dir), str(work)]
        # Optional extra site-packages (e.g. scikit-learn) bound + on PYTHONPATH,
        # so a wheel dep can be added without rebaking the .sif (see config).
        pythonpath = ""
        if s.pr_pyextra:
            binds.append(str(s.pr_pyextra))
            pythonpath = f"PYTHONPATH='{s.pr_pyextra}' "
        # process_input_for_optimus.py --export_for_optimus reads an .mhd and pulls
        # ElementSpacing from its header, so convert the DICOM -> MHD first. Do it
        # INSIDE the .sif (it ships SimpleITK; the host dispatcher stays thin), in
        # the same exec as the model run via bash -c. Paths are tmpdirs under
        # /scratch (no spaces), so the inline quoting is safe.
        # Also append a `Manufacturer = Heidelberg Engineering` line: the vendor
        # code reads it from the .mhd header to pick the Spectralis resize path
        # (496x512); SimpleITK doesn't carry the DICOM Manufacturer tag across, and
        # its absence raises KeyError for any off-spec volume dimensions.
        pycode = (
            "import SimpleITK as sitk; "
            f'sitk.WriteImage(sitk.ReadImage("{dcm}"), "{mhd_file}"); '
            f'open("{mhd_file}", "a").write("Manufacturer = Heidelberg Engineering\\n")'
        )
        convert = f"python -c '{pycode}'"
        run_model = (
            f"{pythonpath}python '{code / 'process_input_for_optimus.py'}' "
            f"'{mhd_file}' '{out}' '{weights}' "
            f"--export_for_optimus True --export_mhd False --samples 10"
        )
        cmd = self._apptainer(
            "exec", s.pr_sif or "",
            binds,
            ["bash", "-c", f"{convert} && {run_model}"],
            task="pr",
        )
        _exec(cmd, self._gpu_env("pr"))
        # The PR (photoreceptor) layer is bounded by the BMEIS and OB-OPR
        # surfaces; the server returns both surface CSVs and Java computes the
        # PR depth (µm) from them.
        bmeis = sorted(glob.glob(str(out / "*BMEIS*.csv")))
        ob_opr = sorted(glob.glob(str(out / "*OB?OPR*.csv"))) or sorted(glob.glob(str(out / "*OPR*.csv")))
        if not bmeis or not ob_opr:
            raise RuntimeError(f"sese_pr produced no BMEIS / OB-OPR CSVs in {out}")
        axial = _spacing_mm(dcm_dir / "bscan.dcm")[0]
        names = [Path(bmeis[0]).name, Path(ob_opr[0]).name]
        return {"primary_metric_value": None, "primary_metric_unit": None,
                "output_payload": {"surface_csvs": names},
                "pixel_scale_mm": axial, "artifact_names": names}

    # --- shared host-native steps (IOWA layer stack + BM), reused by ga/bm/layers

    def _iowa_layers_slurm(self, dcm: Path, work: Path, s, env: dict[str, str]) -> Path:
        """SLURM mode: the whole IOWA chain as ONE CPU-only srun job.

        The /tmp staging workaround (IOWA SIGSEGVs on /scratch inputs) uses
        node-local /tmp, which is not visible across nodes, so staging cannot be
        done by the dispatcher. Instead the job itself stages onto the compute
        node's own /tmp, runs binary + converter there, and copies the results
        back to ``work`` (shared /scratch). Same behaviour as the direct path,
        just on the node SLURM picked. Terminated via the usual SIGTERM path.
        """
        import shlex

        q = shlex.quote
        layers_csv = work / "layers_csv"
        layerseg = work / "layerseg"
        script = "\n".join([
            "set -e",
            'T="$(mktemp -d /tmp/iowa_XXXXXX)"',
            "trap 'rm -rf \"$T\"' EXIT",
            f'cp {q(str(dcm))} "$T/bscan.dcm"',
            'mkdir "$T/layerseg"',
            f'{q(s.ga_iowa_binary or "OCTLayerSeg3.6")} -oM "$T/bscan.dcm" '
            '"$T/layerseg/lres.xml" "$T/layerseg/t1.xml" "$T/layerseg/t2.tif" "$T/layerseg/t3.xml"',
            f'{q(s.ga_iowa_converter or "local_IOWA_LayerSegV3_to_CSV")} '
            '--in "$T/layerseg/lres.xml" --intype iowaxml_ls '
            '--out "$T/layers_csv" --outtype csv --rmdir_out 1',
            f"rm -rf {q(str(layers_csv))} {q(str(layerseg))}",
            f'cp -r "$T/layers_csv" {q(str(layers_csv))}',
            f'cp -r "$T/layerseg" {q(str(layerseg))}',
        ])
        _exec(self._srun("iowa", gpu=False) + ["bash", "-c", script], env or None)
        return layers_csv

    def _iowa_layers(self, dcm: Path, work: Path) -> Path:
        """Run the host-native IOWA chain -> a folder of 11 layer CSVs.

        Shared by ``ga`` (consumes them as ``--LayerSegPath`` input) and
        ``layers`` (returns them). The IOWA binary + converter are CentOS-6-era
        host builds needing extra libs (modern libstdc++ for the binary, the
        framework lib dir for the converter) — both supplied via
        ``GA_IOWA_LD_LIBRARY_PATH``.
        """
        s = _config.settings
        env: dict[str, str] = {}
        if s.ga_iowa_ld_library_path:
            existing = os.environ.get("LD_LIBRARY_PATH", "")
            env["LD_LIBRARY_PATH"] = (
                f"{s.ga_iowa_ld_library_path}:{existing}"
                if existing else s.ga_iowa_ld_library_path
            )
        # 2026-06-24 — IOWA OCTLayerSeg 3.6 SIGSEGVs unconditionally on
        # files staged on cn5's /scratch volume (dev 0x44 — XFS, likely
        # BeeGFS or Lustre underneath given the suspiciously-low device
        # number). Every copy method we tried that lands the file back
        # on /scratch crashes IOWA (cp, dd, cat, Python write_bytes,
        # shutil.copy — all -11). The same content on /tmp (ext4 /
        # tmpfs, dev 0xfd00) IOWA accepts without issue. We could not
        # identify the exact filesystem property that triggers the
        # crash; the empirical fix is to stage IOWA's input + output
        # to /tmp, then copy the result CSVs back to ``work`` (which
        # lives on /scratch and is the persistent artifact path).
        #
        # See PR #255 for the full diagnostic trail.
        if s.apptainer_use_slurm:
            return self._iowa_layers_slurm(dcm, work, s, env)
        import shutil as _shutil
        import tempfile as _tempfile
        with _tempfile.TemporaryDirectory(prefix="iowa_", dir="/tmp") as _tmp:
            iowa_root = Path(_tmp)
            iowa_dcm = iowa_root / "bscan.dcm"
            iowa_layerseg = iowa_root / "layerseg"
            iowa_layerseg.mkdir()
            # Stage the DICOM onto /tmp so IOWA accepts it.
            iowa_dcm.write_bytes(dcm.read_bytes())
            # OCTLayerSeg3.6 (licensed host binary) -> lres.xml on /tmp.
            _exec([s.ga_iowa_binary or "OCTLayerSeg3.6", "-oM", str(iowa_dcm),
                   str(iowa_layerseg / "lres.xml"), str(iowa_layerseg / "t1.xml"),
                   str(iowa_layerseg / "t2.tif"), str(iowa_layerseg / "t3.xml")],
                  env or None)
            # Convert lres.xml -> 11 layer CSVs on /tmp too. The converter
            # REFUSES a pre-existing --out dir so don't create it; pass
            # --rmdir_out 1 to allow it to manage its own directory.
            iowa_layers_csv = iowa_root / "layers_csv"
            _exec([s.ga_iowa_converter or "local_IOWA_LayerSegV3_to_CSV",
                   "--in", str(iowa_layerseg / "lres.xml"), "--intype", "iowaxml_ls",
                   "--out", str(iowa_layers_csv), "--outtype", "csv",
                   "--rmdir_out", "1"], env or None)
            # Copy results back to the persistent ``work`` dir on /scratch.
            layers_csv = work / "layers_csv"
            if layers_csv.exists():
                _shutil.rmtree(str(layers_csv))
            _shutil.copytree(str(iowa_layers_csv), str(layers_csv))
            # Also persist the IOWA intermediates so downstream model
            # runs (ga consumes lres.xml as --LayerSegPath) can reach
            # them via the existing ``work / 'layerseg'`` path.
            layerseg = work / "layerseg"
            if layerseg.exists():
                _shutil.rmtree(str(layerseg))
            _shutil.copytree(str(iowa_layerseg), str(layerseg))
        return layers_csv

    def _run_bm(self, dcm: Path, out: Path) -> Path:
        """Run the host-native BM venv on application.py -> the BM surface CSV.

        Shared by ``bm`` and ``layers``. BM has no .sif: exec the cluster venv
        python with the Python-3.8 + CUDA-11.1 + cuDNN module libs on
        LD_LIBRARY_PATH (without it the venv python can't find libpython3.8) and a
        GPU (application.py forces torch.cuda + pins device 0). Weights are
        hardcoded in application.py to the cluster path (referenced in place).
        """
        import glob

        s = _config.settings
        out.mkdir(parents=True, exist_ok=True)
        code = Path(s.bm_code or "/opt/sese_bm/code")
        python = s.bm_python or "python3"
        env: dict[str, str] = {}
        if s.bm_ld_library_path:
            existing = os.environ.get("LD_LIBRARY_PATH", "")
            env["LD_LIBRARY_PATH"] = (
                f"{s.bm_ld_library_path}:{existing}" if existing else s.bm_ld_library_path
            )
        cmd = [python, str(code / "application.py"), str(dcm), str(out)]
        if s.apptainer_use_slurm:
            # SLURM mode: BM needs a GPU, so it too runs as an srun job (typed
            # gres -> Turing) and SLURM assigns the device; no CUDA pin. srun
            # propagates this env (LD_LIBRARY_PATH) to the job by default.
            cmd = self._srun("bm") + cmd
        else:
            dev = s.bm_gpu_device if s.bm_gpu_device is not None else s.apptainer_gpu_device
            if dev is not None:
                env["CUDA_VISIBLE_DEVICES"] = dev
        _exec(cmd, env or None)
        bm = sorted(glob.glob(str(out / "*BM*.csv"))) or sorted(glob.glob(str(out / "*Bruch*.csv")))
        if not bm:
            raise RuntimeError(f"sese_bm produced no BM CSV in {out}")
        return Path(bm[0])

    def _ga(self, dcm_dir: Path, work: Path) -> dict[str, Any]:
        import glob

        s = _config.settings
        out = work / "out"
        out.mkdir(parents=True, exist_ok=True)
        dcm = dcm_dir / "bscan.dcm"
        # IOWA 11-layer stack is the GA model's --LayerSegPath input (consumed,
        # NOT returned). GA returns ONLY the RPEL surface.
        layers_csv = self._iowa_layers(dcm, work)
        code = Path(s.ga_code or "/opt/sese_ga/code")
        weights = Path(s.ga_weights or "/weights/filly_checkpoints")
        wlist = [str(weights / str(i) / "w.ckpt") for i in range(5)]
        cmd = self._apptainer(
            "exec", s.ga_sif or "",
            [str(code), str(weights), str(dcm_dir), str(out), str(layers_csv)],
            ["python", str(code / "infer_sample_filly.py"), "--PathToWeights", *wlist,
             "--BscanPath", str(dcm), "--LayerSegPath", str(layers_csv),
             "--OutputGA", str(out), "--threshold", s.ga_threshold],
            task="ga",
        )
        _exec(cmd, self._gpu_env("ga"))
        # infer_sample_filly.py writes RPEL + EZL + ELM; return ONLY RPEL (the
        # IOWA layers + EZL/ELM stay in the tempdir, uncollected via artifact_names).
        rpel = sorted(glob.glob(str(out / "*RPEL*.csv")))
        if not rpel:
            raise RuntimeError(f"sese_ga produced no RPEL CSV in {out}")
        lateral = _spacing_mm(dcm)[1]
        return {"primary_metric_value": None, "primary_metric_unit": None,
                "output_payload": {"rpel_csv": Path(rpel[0]).name},
                "pixel_scale_mm": lateral,
                "artifact_names": [Path(rpel[0]).name]}

    def _bm(self, dcm_dir: Path, work: Path) -> dict[str, Any]:
        dcm = dcm_dir / "bscan.dcm"
        bm = self._run_bm(dcm, work / "out")
        axial = _spacing_mm(dcm)[0]
        return {"primary_metric_value": None, "primary_metric_unit": None,
                "output_payload": {"surface_csvs": [bm.name]},
                "pixel_scale_mm": axial, "artifact_names": [bm.name]}

    def _layers(self, dcm_dir: Path, work: Path) -> dict[str, Any]:
        import glob

        dcm = dcm_dir / "bscan.dcm"
        # The full layer stack: 11 IOWA reference layers + the BM layer. Both are
        # returned (unlike ga, which consumes the IOWA layers internally).
        layers_csv = self._iowa_layers(dcm, work)
        iowa = sorted(glob.glob(str(layers_csv / "*.csv")))
        if not iowa:
            raise RuntimeError(f"IOWA converter produced no layer CSVs in {layers_csv}")
        bm = self._run_bm(dcm, work / "bm_out")
        names = [Path(p).name for p in iowa] + [bm.name]
        axial = _spacing_mm(dcm)[0]
        return {"primary_metric_value": None, "primary_metric_unit": None,
                "output_payload": {"surface_csvs": names},
                "pixel_scale_mm": axial, "artifact_names": names}

    def full_volume(
        self,
        task: TaskName,
        e2e_path: Path,
        laterality: Literal["OD", "OS"],
        out_dir_override: Path | None = None,
        scan_index: int = 0,
    ) -> FullVolumeResult:
        # scan_index is ignored: the cluster adapter is DICOM-only and the
        # volume-selection already happened app-side during preprocess.
        if not self.supports(task):
            raise UnsupportedTaskError(
                f"Task '{task}' has no configured .sif in this deployment"
            )
        # DICOM-only: the LibreClinica backend converts .e2e -> bscan.dcm and
        # posts the DICOM, so this adapter never ingests E2E (no oct-converter
        # on the cluster). Reject an .e2e loudly rather than mis-handle it.
        src = Path(e2e_path)
        if src.suffix.lower() == ".e2e":
            raise ValueError(
                "ApptainerAdapter accepts a bscan.dcm only; the LibreClinica "
                "backend must convert .e2e -> .dcm before posting to /run"
            )
        dcm_dir, _dcm = _resolve_dcm(src)

        handler = {"fluid": self._fluid, "onl": self._onl,
                   "pr": self._pr, "ga": self._ga, "bm": self._bm,
                   "layers": self._layers}[task]

        if out_dir_override is not None:
            work = Path(out_dir_override)
            work.mkdir(parents=True, exist_ok=True)
            res = handler(dcm_dir, work)
            bscan_masks_dir = str(work)
        else:
            with tempfile.TemporaryDirectory(
                dir=str(_config.settings.shared_tmpdir)
            ) as tmp:
                res = handler(dcm_dir, Path(tmp))
                bscan_masks_dir = None  # stateless: outputs not persisted

        return FullVolumeResult(
            task=task,
            primary_metric_value=res.get("primary_metric_value"),
            primary_metric_unit=res.get("primary_metric_unit"),
            output_payload=res["output_payload"],
            en_face_mask_path=None,
            bscan_masks_dir=bscan_masks_dir,
            pixel_scale_mm=res["pixel_scale_mm"],
            confidence=0.85,
            model_version=self._model_version,
            artifact_names=res.get("artifact_names"),
        )
