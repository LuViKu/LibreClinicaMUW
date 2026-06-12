"""Adapter port + ``get_adapter()`` singleton selection.

Both ``PlaceholderAdapter`` and the future ``MirageAdapter`` implement this
ABC. ``get_adapter()`` reads ``RETINAL_INFERENCE_INFERENCE_ADAPTER`` and
returns a process-wide singleton; the FastAPI app and the worker share it.
"""

from __future__ import annotations

from abc import ABC, abstractmethod
from pathlib import Path
from typing import Literal

from retinal_inference.models.responses import FastScreenResult, FullVolumeResult
from retinal_inference.tasks import TaskName


class UnsupportedTaskError(ValueError):
    """Raised when an adapter is asked to handle a task it does not implement."""


class RetinalInferenceAdapter(ABC):
    @abstractmethod
    def supports(self, task: TaskName) -> bool: ...

    @abstractmethod
    def fast_screen(
        self,
        task: TaskName,
        e2e_path: Path,
        laterality: Literal["OD", "OS"],
    ) -> FastScreenResult: ...

    @abstractmethod
    def full_volume(
        self,
        task: TaskName,
        e2e_path: Path,
        laterality: Literal["OD", "OS"],
    ) -> FullVolumeResult: ...

    @property
    @abstractmethod
    def model_version(self) -> str:
        """e.g. 'mirage-base-ga-convnext-v1.2' — pin per (encoder, task, decoder)."""


_singleton: RetinalInferenceAdapter | None = None


def get_adapter() -> RetinalInferenceAdapter:
    """Return the process-wide adapter singleton."""
    global _singleton
    if _singleton is None:
        from retinal_inference import config as _config

        if _config.settings.inference_adapter == "placeholder":
            from .placeholder import PlaceholderAdapter

            _singleton = PlaceholderAdapter()
        elif _config.settings.inference_adapter == "mirage":
            from .mirage import MirageAdapter

            _singleton = MirageAdapter()
        else:
            raise RuntimeError(f"Unknown adapter: {_config.settings.inference_adapter}")
    return _singleton


def reset_adapter() -> None:
    """Test-only: drop the singleton so the next ``get_adapter()`` re-reads settings."""
    global _singleton
    _singleton = None
