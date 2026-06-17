"""Pydantic-settings config — read once at process start.

Every env var uses the ``RETINAL_INFERENCE_`` prefix so it does not collide
with the Java side's ``LIBRECLINICA_*`` vars.
"""

from __future__ import annotations

from pathlib import Path
from typing import Literal

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_prefix="RETINAL_INFERENCE_",
        env_file=None,
        extra="ignore",
    )

    inference_adapter: Literal["placeholder", "mirage", "optima", "apptainer"] = "placeholder"
    db_url: str = "postgresql://clinica:clinica@db:5432/libreclinica"
    shared_storage_path: Path = Path("/var/lib/libreclinica/segmentation-output")
    e2e_uploads_path: Path = Path("/var/lib/libreclinica/e2e-uploads")
    worker_poll_interval_s: float = 2.0
    # DR-022 split topology: institutional dev compose runs the DB-poll worker
    # (default true). Production GPU host runs the /run endpoint only — set
    # RETINAL_INFERENCE_WORKER_ENABLED=false there so the worker process isn't
    # spawned. (Read by docker-entrypoint.sh, not by Python code directly, but
    # surfaced here so the env var is documented in one place.)
    worker_enabled: bool = True
    fast_screen_timeout_s: float = 8.0
    # Placeholder-only sleeps; tests override to 0 to keep pytest fast.
    fast_screen_sleep_s: float = 2.0
    full_volume_sleep_s: float = 30.0

    # --- OptimaAdapter: per-task model-runner endpoints -----------------------
    # Each task is dispatched to its own runner container over the internal
    # network. A task is only ``supports()``-ed when its URL is set, so leaving
    # one empty cleanly gates that task (e.g. ``ga`` until the IOWA segmenter +
    # a GPU host exist). Set e.g. RETINAL_INFERENCE_RUNNER_FLUID_URL.
    runner_fluid_url: str | None = None
    runner_onl_url: str | None = None
    runner_bmeis_url: str | None = None
    runner_ga_url: str | None = None
    # Generous per-job ceiling — CPU full-volume inference is slow.
    runner_timeout_s: float = 900.0

    # --- Remote /run endpoint (DR-022) ---------------------------------------
    # When the sidecar is deployed on a separate GPU host, the institutional
    # Tomcat reaches it via POST /run. The endpoint is opt-in so dev compose
    # (single-host) and the DB-poll worker keep their existing surface.
    #
    # The auth token gates every /run request — sidecar refuses to start with
    # the endpoint enabled if the token is unset.
    #
    # shared_tmpdir is the host-bind that the sidecar AND every runner mount at
    # the same absolute path; the sidecar's TemporaryDirectory lives inside
    # it so runners see the bscan.dcm path natively.
    run_endpoint_enabled: bool = False
    auth_token: str | None = None
    shared_tmpdir: Path = Path("/var/lib/retinal-inference/tmp")

    # --- /preprocess endpoint (DR-022, app-VM side) --------------------------
    # The cluster ApptainerAdapter is DICOM-only, and the PHI-bearing .e2e must
    # not leave the app VM. A preprocess-only sidecar runs on the app VM with
    # this enabled; the Java backend POSTs the .e2e here, gets the PHI-redacted
    # bscan.dcm back, and forwards only that to the remote /run. Gated by the
    # same auth_token. No adapter/models needed for this mode.
    preprocess_endpoint_enabled: bool = False

    # --- ApptainerAdapter (GPU cluster dispatch, DR-022) ----------------------
    # On the Apptainer cluster there is no Docker/compose: the adapter runs each
    # model's .sif via ``apptainer exec --nv`` as a subprocess (the OPTIMA
    # pattern). A task is only ``supports()``-ed when its .sif is configured.
    # code/weights dirs are bind-mounted into the .sif at their host paths.
    # Input is a bscan.dcm (the Java side preprocesses the .e2e — DR-022).
    apptainer_bin: str = "apptainer"
    # CUDA_VISIBLE_DEVICES for most tasks (torch>=1.9 / CUDA 10.2 — any GPU).
    apptainer_gpu_device: str | None = None
    # BMEIS (sese_pr) is torch1.0 / CUDA 9 — no Turing (RTX 2080 Ti, sm_75)
    # kernels; pin to a non-Turing GPU (TITAN Xp/V), or set "" to force CPU.
    apptainer_bmeis_gpu_device: str | None = None
    # When true, wrap each apptainer call in ``srun`` (one blocking SLURM job per
    # scan) instead of running it directly. Required on the OPTIMA cluster: all
    # partitions cap at MaxTime=2d, so no persistent GPU service — the /run
    # dispatcher stays CPU-only off-GPU and submits a short GPU job per scan.
    apptainer_use_slurm: bool = False
    apptainer_slurm_partition: str | None = None  # e.g. "full_optima"; None = SLURM default
    apptainer_slurm_account: str | None = None  # SLURM --account (this cluster requires one)
    apptainer_slurm_time: str = "01:00:00"  # walltime per inference job (<< 2d cap)
    apptainer_slurm_gres: str = "gpu:1"  # or typed, e.g. "gpu:nvtitanxp:1"

    fluid_sif: str | None = None  # fluid_segmentation.sif (v2.5.0)
    onl_sif: str | None = None
    onl_code: Path | None = None
    onl_weights: Path | None = None
    bmeis_sif: str | None = None
    bmeis_code: Path | None = None
    bmeis_weights: Path | None = None
    # Optional extra site-packages dir bound into the bmeis .sif and prepended to
    # PYTHONPATH (e.g. a `pip install --target` of scikit-learn). Lets a pure-Python
    # / wheel dep be added without rebaking the heavy .sif; leave unset once the
    # dep is baked into the image.
    bmeis_pyextra: Path | None = None
    ga_sif: str | None = None
    ga_code: Path | None = None
    ga_weights: Path | None = None
    # IOWA OCTLayerSeg native binary (host, not a .sif) — produces the 11-layer
    # segmentation GA needs as input. On the OPTIMA host:
    #   /home/optima/octreader/OCTLayerSeg3.6
    ga_iowa_binary: str | None = None
    # Converts the IOWA XML (lres.xml) -> a folder of 11 layer CSVs that
    # infer_sample_filly.py's --LayerSegPath expects. On the OPTIMA host:
    #   /home/optima/octreader/optima-framework/deployment/prod/local_IOWA_LayerSegV3_to_CSV
    ga_iowa_converter: str | None = None
    ga_threshold: str = "0.5"


settings = Settings()


def reload_settings() -> Settings:
    """Re-read env vars into the module-level ``settings`` singleton.

    Used by tests that mutate env before constructing the adapter; production
    code should treat ``settings`` as immutable.
    """
    global settings
    settings = Settings()
    return settings
