"""Pydantic-settings config — read once at process start.

Every env var uses the ``RETINAL_INFERENCE_`` prefix so it does not collide
with the Java side's ``LIBRECLINICA_*`` vars.
"""

from __future__ import annotations

import re
from pathlib import Path
from typing import Literal

from pydantic_settings import BaseSettings, SettingsConfigDict


_TYPED_GRES = re.compile(r"^gpu:[A-Za-z][\w.\-]*:\d+$")


def is_typed_gres(gres: str) -> bool:
    """True for ``gpu:<type>:<count>`` (e.g. ``gpu:nv2080ti:1``), false for ``gpu`` / ``gpu:1``."""
    return bool(_TYPED_GRES.match((gres or "").strip()))


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
    runner_pr_url: str | None = None
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

    # Host-bind for the per-E2E artifact spine the app-VM /preprocess writes to.
    # When set + writable, /preprocess persists <e2eUuid>/{bscan.dcm,fundus.png,
    # geometry.json} so the Java backend's GET endpoint can serve them later.
    # Leaving this unset is the standalone-sidecar mode (tests / local dev that
    # only need the response body + headers; no bind mount required).
    bscan_store: Path | None = None

    # Root of the app's retinal artifact store as mounted in this container
    # (core.retinalInference.artifactStorePath on the Java side; compose binds
    # it at the same path in the app and the sidecar). POST /derive only acts
    # on job directories below it.
    artifact_store_path: Path = Path("/var/lib/libreclinica/retinal-artifacts")

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
    # The pr task (sese_pr) is torch1.0 / CUDA 9 — no Turing (RTX 2080 Ti, sm_75)
    # kernels; pin to a non-Turing GPU (TITAN Xp/V), or set "" to force CPU.
    apptainer_pr_gpu_device: str | None = None
    # SLURM mode: when true, every GPU call (apptainer .sif tasks AND the
    # host-native bm venv) runs as one blocking ``srun`` job per task, and the
    # dispatcher itself stays off the GPUs (no CUDA_VISIBLE_DEVICES pin).
    # Direct mode (false) runs on the node the server lives on, outside SLURM.
    apptainer_use_slurm: bool = False
    apptainer_slurm_partition: str | None = None  # e.g. "full_optima"; None = SLURM default
    # SLURM --account. REQUIRED in SLURM mode (startup refuses without it): this
    # cluster has no default association.
    apptainer_slurm_account: str | None = None
    apptainer_slurm_time: str = "01:00:00"  # walltime per inference job (<< 2d cap)
    # Recommended: a TYPED request, "gpu:nv2080ti:1". The pr + bm models have no
    # Ampere/Ada kernels, and the partition also contains those nodes, so an
    # untyped "gpu:1" can land on one and fail. Startup refuses an untyped gres
    # unless apptainer_slurm_allow_untyped_gres is set. The default is the
    # recommended value so that setting only the account is enough.
    apptainer_slurm_gres: str = "gpu:nv2080ti:1"
    apptainer_slurm_allow_untyped_gres: bool = False
    apptainer_slurm_mem: str | None = None  # --mem, e.g. "32G"
    apptainer_slurm_cpus_per_task: int | None = None  # --cpus-per-task
    apptainer_slurm_qos: str | None = None  # --qos
    apptainer_slurm_constraint: str | None = None  # --constraint
    # --job-name is "<prefix>-<task>". Never contains patient / scan identifiers:
    # job names are visible to every cluster user via squeue.
    apptainer_slurm_job_name: str = "ri"
    # How many /run requests may execute at once in this process. None = auto:
    # 1 in direct mode (one resident GPU pin, one scan at a time), 4 in SLURM
    # mode (SLURM queues the actual GPU work; the cap bounds dispatcher threads,
    # /scratch pressure and queue footprint).
    max_concurrent_runs: int | None = None

    fluid_sif: str | None = None  # fluid_segmentation.sif (v2.5.0)
    onl_sif: str | None = None
    onl_code: Path | None = None
    onl_weights: Path | None = None
    pr_sif: str | None = None
    pr_code: Path | None = None
    pr_weights: Path | None = None
    # Optional extra site-packages dir bound into the pr .sif and prepended to
    # PYTHONPATH (e.g. a `pip install --target` of scikit-learn). Lets a pure-Python
    # / wheel dep be added without rebaking the heavy .sif; leave unset once the
    # dep is baked into the image.
    pr_pyextra: Path | None = None
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
    # Colon-joined dirs prepended to LD_LIBRARY_PATH when running the IOWA binary
    # + converter (host-native CentOS-6-era builds): a modern libstdc++
    # (GLIBCXX_3.4.20 — e.g. the conda env lib) for the binary, and the framework
    # lib dir (xerces etc.) for the converter. On the OPTIMA host e.g.:
    #   /scratch/$USER/ri-env/lib:/home/optima/octreader/optima-framework/deployment/prod/lib
    ga_iowa_ld_library_path: str | None = None
    ga_threshold: str = "0.5"

    # --- BM (Bruch's membrane) — host-native venv task (DR-022) ----------------
    # Unlike the other models, BM has no .sif: on the cluster it runs from a plain
    # venv + LMOD modules. The adapter execs ``bm_python`` on
    # ``bm_code/application.py <bscan.dcm> <out>`` with ``bm_ld_library_path``
    # (the Python-3.8 + CUDA-11.1 + cuDNN module lib dirs) prepended to
    # LD_LIBRARY_PATH. GPU-required (application.py forces torch.cuda); the model
    # weights are hardcoded in application.py to the cluster path, so referenced
    # in place — nothing to copy or rebake. A task is supported when bm_code (or
    # bm_python) is configured.
    bm_python: str | None = None  # venv interpreter, e.g. <bm>/venv/bin/python3
    bm_code: Path | None = None  # dir with application.py (+ models/, tpstorch.py)
    bm_ld_library_path: str | None = None  # colon-joined LMOD module lib dirs
    bm_gpu_device: str | None = None  # CUDA_VISIBLE_DEVICES (app pins device 0)

    @property
    def effective_max_concurrent_runs(self) -> int:
        if self.max_concurrent_runs is not None:
            return max(1, self.max_concurrent_runs)
        return 4 if self.apptainer_use_slurm else 1

    @property
    def slurm_mode(self) -> bool:
        return self.inference_adapter == "apptainer" and self.apptainer_use_slurm

    def validate_slurm(self) -> None:
        """Raise ``RuntimeError`` if SLURM mode is on but misconfigured.

        Called at adapter construction (i.e. server startup) so a bad config
        fails loudly instead of failing every job at srun time.
        """
        if not self.apptainer_use_slurm:
            return
        if not (self.apptainer_slurm_account or "").strip():
            raise RuntimeError(
                "SLURM mode requires RETINAL_INFERENCE_APPTAINER_SLURM_ACCOUNT "
                "(this cluster has no default association; srun would fail per job)"
            )
        if not self.apptainer_slurm_allow_untyped_gres and not is_typed_gres(
            self.apptainer_slurm_gres
        ):
            raise RuntimeError(
                f"SLURM gres '{self.apptainer_slurm_gres}' is untyped; it could land on an "
                "Ampere/Ada node where the pr and bm tasks fail ('no kernel image'). Use a "
                "typed request such as 'gpu:nv2080ti:1', or set "
                "RETINAL_INFERENCE_APPTAINER_SLURM_ALLOW_UNTYPED_GRES=true to override."
            )


settings = Settings()


def reload_settings() -> Settings:
    """Re-read env vars into the module-level ``settings`` singleton.

    Used by tests that mutate env before constructing the adapter; production
    code should treat ``settings`` as immutable.
    """
    global settings
    settings = Settings()
    return settings
