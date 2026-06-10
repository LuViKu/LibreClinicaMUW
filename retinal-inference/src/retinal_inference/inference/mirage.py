"""MIRAGE adapter — stub.

Placeholder for the real MIRAGE encoder + ConvNeXt decoder. Lands when the
GA model is trained and validated against RegionFinder. All methods raise
``NotImplementedError`` so attempts to flip the env var prematurely fail
loudly instead of returning bogus output.
"""

from __future__ import annotations

from pathlib import Path
from typing import Literal

from retinal_inference.models.responses import FastScreenResult, FullVolumeResult
from retinal_inference.tasks import TaskName

from .adapter import RetinalInferenceAdapter


class MirageAdapter(RetinalInferenceAdapter):
    """Real MIRAGE-based adapter — not yet implemented."""

    @property
    def model_version(self) -> str:
        raise NotImplementedError("MirageAdapter is not implemented yet.")

    def supports(self, task: TaskName) -> bool:
        raise NotImplementedError("MirageAdapter is not implemented yet.")

    def fast_screen(
        self,
        task: TaskName,
        e2e_path: Path,
        laterality: Literal["OD", "OS"],
    ) -> FastScreenResult:
        raise NotImplementedError("MirageAdapter is not implemented yet.")

    def full_volume(
        self,
        task: TaskName,
        e2e_path: Path,
        laterality: Literal["OD", "OS"],
    ) -> FullVolumeResult:
        raise NotImplementedError("MirageAdapter is not implemented yet.")
