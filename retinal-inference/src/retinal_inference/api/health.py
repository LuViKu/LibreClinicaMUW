"""GET /health — readiness + adapter introspection."""

from __future__ import annotations

import socket
import subprocess
from functools import lru_cache

from fastapi import APIRouter

from retinal_inference import config as _config
from retinal_inference.inference.adapter import get_adapter
from retinal_inference.models.responses import HealthResponse
from retinal_inference.tasks import SUPPORTED_TASKS

router = APIRouter()


def _query_gpu_names() -> str:
    """Raw ``index,name`` lines from nvidia-smi, or ``""`` when there is none.

    Separate from the parser so tests can feed it text without a GPU.
    """
    try:
        return subprocess.run(
            ["nvidia-smi", "--query-gpu=index,name", "--format=csv,noheader"],
            capture_output=True,
            text=True,
            timeout=3,
            check=False,
        ).stdout
    except (OSError, subprocess.SubprocessError):
        return ""


def parse_gpu_name(device: str | None, listing: str) -> str | None:
    """Name of the card at ``device`` in an nvidia-smi ``index,name`` listing.

    ``None`` when the device is unset, not a single index, or absent from the
    listing — a multi-device pin like ``"0,1"`` has no single name to report.
    """
    if not device or "," in device:
        return None
    for line in listing.splitlines():
        idx, _, name = line.partition(",")
        if idx.strip() == device.strip():
            return name.strip() or None
    return None


@lru_cache(maxsize=8)
def _gpu_name(device: str | None) -> str | None:
    # Cached per index: the card behind an index does not change while this
    # process lives, and /health is polled every few minutes from two places.
    return parse_gpu_name(device, _query_gpu_names())


@router.get("/health", response_model=HealthResponse)
def health() -> HealthResponse:
    adapter = get_adapter()
    # Read through the module so reload_settings() (tests) is honoured.
    device = _config.settings.apptainer_gpu_device
    return HealthResponse(
        status="ok",
        adapter=type(adapter).__name__.removesuffix("Adapter").lower(),
        model_version=adapter.model_version,
        supported_tasks=sorted(SUPPORTED_TASKS),
        node=socket.gethostname().split(".", 1)[0] or None,
        gpu_device=device,
        gpu_name=_gpu_name(device),
    )
