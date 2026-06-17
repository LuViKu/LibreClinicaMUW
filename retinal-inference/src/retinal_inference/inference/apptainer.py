"""ApptainerAdapter — GPU-cluster dispatch via ``apptainer exec`` (DR-022).

On the OPTIMA Apptainer cluster there is no Docker/compose. This adapter runs
each model's ``.sif`` as a subprocess (``apptainer exec --nv`` / ``apptainer
run``), the same pattern the original OPTIMA ``vrcbin`` wrappers used. The
LibreClinica backend preprocesses the ``.e2e`` and posts a ``bscan.dcm`` to the
sidecar's ``/run`` endpoint, so this adapter's input is already a DICOM — it
does no oct-converter ingestion.

A task is only ``supports()``-ed when its ``.sif`` is configured (gates GA, and
any task whose image isn't present). Per-task ``.sif``/code/weights come from
config; outputs are parsed into the generic ``FullVolumeResult`` (fluid mm³,
ONL/BMEIS µm, GA mm²).

Cluster caveats encoded here:
  * BMEIS (sese_pr, torch1.0/CUDA9) has no Turing kernels → pin to a non-Turing
    GPU via ``apptainer_bmeis_gpu_device`` (or "" for CPU).
  * ``apptainer_use_slurm`` will wrap calls in ``sbatch`` once the hosting model
    is fixed; for now it runs apptainer directly.

Every command + parse below mirrors the vendor wrapper invocations and is
flagged "confirm on the cluster" — not runnable without the .sif images + GPUs.
"""

from __future__ import annotations

import csv
import os
import subprocess
import tempfile
from pathlib import Path
from typing import Any, Literal

from retinal_inference import config as _config
from retinal_inference.models.responses import FastScreenResult, FullVolumeResult
from retinal_inference.tasks import SUPPORTED_TASKS, TaskName

from .adapter import (
    FastScreenUnavailable,
    RetinalInferenceAdapter,
    UnsupportedTaskError,
)

_TIMEOUT_S = 3600


def _exec(cmd: list[str], env: dict[str, str] | None = None) -> str:
    """Run a subprocess, raising RuntimeError with stderr on failure.

    Single choke-point so unit tests can monkeypatch all dispatch.
    """
    full_env = {**os.environ, **(env or {})}
    proc = subprocess.run(
        cmd, capture_output=True, text=True, timeout=_TIMEOUT_S, env=full_env
    )
    if proc.returncode != 0:
        raise RuntimeError(
            f"{cmd[0]} failed (rc={proc.returncode}): {proc.stderr or proc.stdout}"
        )
    return proc.stdout


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


def _read_surface_csv(path: str):
    import numpy as np

    rows: list[list[float]] = []
    with open(path, newline="") as f:
        reader = csv.reader(f)
        next(reader, None)  # size header
        for line in reader:
            rows.append([float(x) if x not in ("", "nan", "NaN") else np.nan for x in line])
    return np.asarray(rows, dtype=float)


class ApptainerAdapter(RetinalInferenceAdapter):
    """Dispatch each task to its model ``.sif`` via apptainer on the GPU cluster."""

    _model_version = "optima-apptainer-v1"

    def __init__(self) -> None:
        s = _config.settings
        self._sif: dict[TaskName, str | None] = {
            "fluid": s.fluid_sif,
            "onl": s.onl_sif,
            "bmeis": s.bmeis_sif,
            "ga": s.ga_sif,
        }

    @property
    def model_version(self) -> str:
        return self._model_version

    def supports(self, task: TaskName) -> bool:
        return task in SUPPORTED_TASKS and bool(self._sif.get(task))

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
        dev = s.apptainer_bmeis_gpu_device if task == "bmeis" else s.apptainer_gpu_device
        if dev is None:
            return {}
        # Apptainer forwards APPTAINERENV_* into the container.
        return {"CUDA_VISIBLE_DEVICES": dev, "APPTAINERENV_CUDA_VISIBLE_DEVICES": dev}

    def _apptainer(self, verb: str, sif: str, binds: list[str], args: list[str]) -> list[str]:
        s = _config.settings
        cmd = [s.apptainer_bin, verb, "-e", "--nv", "--no-home"]
        if binds:
            cmd += ["--bind", ",".join(binds)]
        cmd += [sif, *args]
        if s.apptainer_use_slurm:
            # One blocking SLURM job per scan (cluster caps walltime at 2d, so no
            # persistent GPU service). srun allocates a GPU and runs to completion.
            srun = ["srun", f"--time={s.apptainer_slurm_time}", f"--gres={s.apptainer_slurm_gres}"]
            if s.apptainer_slurm_partition:
                srun.append(f"--partition={s.apptainer_slurm_partition}")
            if s.apptainer_slurm_account:
                # This cluster has no default association — srun fails without it.
                srun.append(f"--account={s.apptainer_slurm_account}")
            cmd = srun + cmd
        return cmd

    # --- per-task handlers (return the generic result fields) ----------------

    def _fluid(self, dcm_dir: Path, work: Path) -> dict[str, Any]:
        import numpy as np

        out = work / "out"
        out.mkdir(parents=True, exist_ok=True)
        # v2.5.0 .sif: run_inference.py reads /workdir/input-data, writes fluidseg.npz.
        cmd = self._apptainer(
            "run",
            _config.settings.fluid_sif or "",
            [f"{dcm_dir}:/workdir/input-data", f"{out}:/workdir/output"],
            ["/workdir/AWS/run_inference.py", "--input_folder", "/workdir/input-data",
             "--optima_spacing", "--run_local"],
        )
        _exec(cmd, self._gpu_env("fluid"))
        npz, npy = out / "fluidseg.npz", out / "fluidseg.npy"
        seg = np.load(npz)["segmentation"] if npz.is_file() else np.load(npy)
        axial, lateral, slice_mm = _spacing_mm(dcm_dir / "bscan.dcm")
        vox = axial * lateral * slice_mm
        payload = {n: round(float((seg == lbl).sum()) * vox, 6)
                   for n, lbl in {"irf_mm3": 1, "srf_mm3": 2, "ped_mm3": 3}.items()}
        total = round(sum(payload.values()), 6)
        return {"primary_metric_value": total, "primary_metric_unit": "mm³",
                "output_payload": {"total_fluid_volume_mm3": total, **payload},
                "pixel_scale_mm": axial}

    def _onl(self, dcm_dir: Path, work: Path) -> dict[str, Any]:
        import glob

        import numpy as np

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
        )
        _exec(cmd, self._gpu_env("onl"))
        upper = sorted(glob.glob(str(out / "*OPL-HFL*.csv")))
        lower = sorted(glob.glob(str(out / "*BMEIS*.csv")))
        if not upper or not lower:
            raise RuntimeError(f"sese_onl produced no boundary CSVs in {out}")
        axial = _spacing_mm(dcm_dir / "bscan.dcm")[0]
        thick_um = float(np.nanmean(_read_surface_csv(lower[0]) - _read_surface_csv(upper[0]))) * axial * 1000.0
        return {"primary_metric_value": round(thick_um, 3), "primary_metric_unit": "µm",
                "output_payload": {"mean_onl_thickness_um": round(thick_um, 3)},
                "pixel_scale_mm": axial}

    def _bmeis(self, dcm_dir: Path, work: Path) -> dict[str, Any]:
        import glob

        import numpy as np

        s = _config.settings
        out = work / "out"
        mhd = work / "mhd_in"
        out.mkdir(parents=True, exist_ok=True)
        mhd.mkdir(parents=True, exist_ok=True)
        code = Path(s.bmeis_code or "/opt/sese_pr")
        weights = Path(s.bmeis_weights or "/weights/u2net-cross-entropy")
        dcm = dcm_dir / "bscan.dcm"
        mhd_file = mhd / "bscan.mhd"
        # process_input_for_optimus.py --export_for_optimus reads an .mhd and pulls
        # ElementSpacing from its header, so convert the DICOM -> MHD first. Do it
        # INSIDE the .sif (it ships SimpleITK; the host dispatcher stays thin), in
        # the same exec as the model run via bash -c. Paths are tmpdirs under
        # /scratch (no spaces), so the inline quoting is safe.
        convert = (
            f"python -c 'import SimpleITK as sitk; "
            f'sitk.WriteImage(sitk.ReadImage("{dcm}"), "{mhd_file}")\''
        )
        run_model = (
            f"python '{code / 'process_input_for_optimus.py'}' "
            f"'{mhd_file}' '{out}' '{weights}' "
            f"--export_for_optimus True --export_mhd False --samples 10"
        )
        cmd = self._apptainer(
            "exec", s.bmeis_sif or "",
            [str(code), str(weights), str(dcm_dir), str(work)],
            ["bash", "-c", f"{convert} && {run_model}"],
        )
        _exec(cmd, self._gpu_env("bmeis"))
        bmeis = sorted(glob.glob(str(out / "*BMEIS*.csv")))
        if not bmeis:
            raise RuntimeError(f"sese_pr produced no BMEIS CSV in {out}")
        axial = _spacing_mm(dcm_dir / "bscan.dcm")[0]
        depth_um = float(np.nanmean(_read_surface_csv(bmeis[0]))) * axial * 1000.0
        return {"primary_metric_value": round(depth_um, 3), "primary_metric_unit": "µm",
                "output_payload": {"mean_bmeis_depth_um": round(depth_um, 3)},
                "pixel_scale_mm": axial}

    def _ga(self, dcm_dir: Path, work: Path) -> dict[str, Any]:
        import glob

        import numpy as np

        s = _config.settings
        out = work / "out"
        layerseg = work / "layerseg"
        out.mkdir(parents=True, exist_ok=True)
        layerseg.mkdir(parents=True, exist_ok=True)
        dcm = dcm_dir / "bscan.dcm"
        # Step 1: IOWA layer segmentation — native host binary OCTLayerSeg3.6
        # (licensed, not a .sif), emits lres.xml. Matches sese_iowa_layer_vrcbin.py.
        _exec([s.ga_iowa_binary or "OCTLayerSeg3.6", "-oM", str(dcm),
               str(layerseg / "lres.xml"), str(layerseg / "t1.xml"),
               str(layerseg / "t2.tif"), str(layerseg / "t3.xml")])
        # Step 1b: convert the IOWA XML -> a folder of 11 layer CSVs, which is what
        # infer_sample_filly.py's --LayerSegPath consumes (it reads them via
        # prepare_filly.resample_oct, and rpe = layers[:, 10]). The raw lres.xml is
        # NOT directly consumable. Converter = local_IOWA_LayerSegV3_to_CSV.
        # NOTE: the XML->CSV direction/flags are reconstructed from the vendor's
        # csv->vrcbin usage (sese_ga_vrcbin.py:166) — validate on the first GA run.
        layers_csv = work / "layers_csv"
        layers_csv.mkdir(parents=True, exist_ok=True)
        _exec([s.ga_iowa_converter or "local_IOWA_LayerSegV3_to_CSV",
               "--in", str(layerseg / "lres.xml"), "--intype", "iowaxml_ls",
               "--out", str(layers_csv), "--outtype", "csv", "--aScanMode", "1"])
        # Step 2: GA model in the .sif.
        code = Path(s.ga_code or "/opt/sese_ga/code")
        weights = Path(s.ga_weights or "/weights/filly_checkpoints")
        wlist = [str(weights / str(i) / "w.ckpt") for i in range(5)]
        cmd = self._apptainer(
            "exec", s.ga_sif or "",
            [str(code), str(weights), str(dcm_dir), str(out), str(layers_csv)],
            ["python", str(code / "infer_sample_filly.py"), "--PathToWeights", *wlist,
             "--BscanPath", str(dcm), "--LayerSegPath", str(layers_csv),
             "--OutputGA", str(out), "--threshold", s.ga_threshold],
        )
        _exec(cmd, self._gpu_env("ga"))
        rpel = sorted(glob.glob(str(out / "*RPEL*.csv")))
        if not rpel:
            raise RuntimeError(f"sese_ga produced no RPEL CSV in {out}")
        arr = _read_surface_csv(rpel[0])
        _, lateral, slice_mm = _spacing_mm(dcm)
        area_mm2 = float(np.count_nonzero(arr > 0)) * lateral * slice_mm
        return {"primary_metric_value": round(area_mm2, 4), "primary_metric_unit": "mm²",
                "output_payload": {"total_area_mm2": round(area_mm2, 4)},
                "pixel_scale_mm": lateral}

    def full_volume(
        self,
        task: TaskName,
        e2e_path: Path,
        laterality: Literal["OD", "OS"],
        out_dir_override: Path | None = None,
    ) -> FullVolumeResult:
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
                   "bmeis": self._bmeis, "ga": self._ga}[task]

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
            primary_metric_value=res["primary_metric_value"],
            primary_metric_unit=res["primary_metric_unit"],
            output_payload=res["output_payload"],
            en_face_mask_path=None,
            bscan_masks_dir=bscan_masks_dir,
            pixel_scale_mm=res["pixel_scale_mm"],
            confidence=0.85,
            model_version=self._model_version,
        )
