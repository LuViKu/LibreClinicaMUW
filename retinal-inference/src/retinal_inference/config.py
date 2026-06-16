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

    inference_adapter: Literal["placeholder", "mirage", "optima"] = "placeholder"
    db_url: str = "postgresql://clinica:clinica@db:5432/libreclinica"
    shared_storage_path: Path = Path("/var/lib/libreclinica/segmentation-output")
    e2e_uploads_path: Path = Path("/var/lib/libreclinica/e2e-uploads")
    worker_poll_interval_s: float = 2.0
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


settings = Settings()


def reload_settings() -> Settings:
    """Re-read env vars into the module-level ``settings`` singleton.

    Used by tests that mutate env before constructing the adapter; production
    code should treat ``settings`` as immutable.
    """
    global settings
    settings = Settings()
    return settings
