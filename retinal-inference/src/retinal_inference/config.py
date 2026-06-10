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

    inference_adapter: Literal["placeholder", "mirage"] = "placeholder"
    db_url: str = "postgresql://clinica:clinica@db:5432/libreclinica"
    shared_storage_path: Path = Path("/var/lib/libreclinica/segmentation-output")
    e2e_uploads_path: Path = Path("/var/lib/libreclinica/e2e-uploads")
    worker_poll_interval_s: float = 2.0
    fast_screen_timeout_s: float = 8.0
    # Placeholder-only sleeps; tests override to 0 to keep pytest fast.
    fast_screen_sleep_s: float = 2.0
    full_volume_sleep_s: float = 30.0


settings = Settings()


def reload_settings() -> Settings:
    """Re-read env vars into the module-level ``settings`` singleton.

    Used by tests that mutate env before constructing the adapter; production
    code should treat ``settings`` as immutable.
    """
    global settings
    settings = Settings()
    return settings
