"""Deterministic placeholder adapter.

Used during platform development before the real MIRAGE-based model lands.
Same upload + same task → same numbers, so the SPA / Java side can rely on
reproducible output for end-to-end tests.
"""

from __future__ import annotations

import hashlib
import time
from pathlib import Path
from typing import Literal

from retinal_inference import config as _config
from retinal_inference.models.responses import FastScreenResult, FullVolumeResult
from retinal_inference.tasks import SUPPORTED_TASKS, TaskName

from .adapter import RetinalInferenceAdapter, UnsupportedTaskError


class PlaceholderAdapter(RetinalInferenceAdapter):
    """Deterministic, task-aware mock adapter."""

    _model_version: str = "placeholder-v1"

    @property
    def model_version(self) -> str:
        return self._model_version

    def supports(self, task: TaskName) -> bool:
        return task in SUPPORTED_TASKS

    def fast_screen(
        self,
        task: TaskName,
        e2e_path: Path,
        laterality: Literal["OD", "OS"],
    ) -> FastScreenResult:
        if not self.supports(task):
            raise UnsupportedTaskError(
                f"Task '{task}' not enabled in placeholder adapter"
            )
        time.sleep(_config.settings.fast_screen_sleep_s)
        seed = self._seed(e2e_path, task)
        # 2.000–7.000 mm² envelope for GA
        approx = round(2.0 + (seed[0] / 255.0) * 5.0, 3)
        return FastScreenResult(
            task=task,
            approx_area_mm2=approx,
            foveal_bscan_index=48,
            confidence=0.65,
            model_version=self.model_version,
        )

    def full_volume(
        self,
        task: TaskName,
        e2e_path: Path,
        laterality: Literal["OD", "OS"],
    ) -> FullVolumeResult:
        if not self.supports(task):
            raise UnsupportedTaskError(
                f"Task '{task}' not enabled in placeholder adapter"
            )
        time.sleep(_config.settings.full_volume_sleep_s)
        seed = self._seed(e2e_path, task)
        total = round(2.0 + (seed[0] / 255.0) * 5.0, 3)
        per_bscan = {
            f"bscan_{i}": round((seed[i % 32] / 255.0) * 0.2, 3) for i in range(97)
        }
        mask_dir = _config.settings.shared_storage_path
        en_face_mask_path = str(mask_dir / f"placeholder-{task}.png")
        return FullVolumeResult(
            task=task,
            total_area_mm2=total,
            per_bscan_areas_mm2=per_bscan,
            en_face_mask_path=en_face_mask_path,
            pixel_scale_mm=0.011,
            confidence=0.85,
            model_version=self.model_version,
        )

    @staticmethod
    def _seed(e2e_path: Path, task: TaskName) -> bytes:
        """SHA256 of (E2E contents || task) — same upload + task → same numbers."""
        h = hashlib.sha256()
        h.update(Path(e2e_path).read_bytes())
        h.update(task.encode("utf-8"))
        return h.digest()
