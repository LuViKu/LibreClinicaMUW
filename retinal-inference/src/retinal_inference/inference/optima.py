"""OptimaAdapter — task-dispatching adapter (no ML in-process).

Each task is backed by its own model-runner container (incompatible runtimes:
RetInsight py3.7 wheels, ONL torch, PR torch-1.0, GA Singularity). This adapter
does no inference itself: it converts the uploaded ``.e2e`` to the shared
``bscan.dcm`` and POSTs an ``/infer`` request to the runner whose URL is
configured for the task, then maps the structured response onto the generic
``FullVolumeResult``.

A task is only ``supports()``-ed when its runner URL is set — that is the gate
that keeps ``ga`` off until the IOWA layer segmenter + a GPU host exist.

Runner contract (each runner is a small FastAPI service):
    POST {runner_url}/infer
      { "task", "bscan_dcm_path", "laterality", "output_dir" }
    → { "primary_metric_value", "primary_metric_unit", "output_payload",
        "en_face_mask_path", "bscan_masks_dir", "pixel_scale_mm",
        "confidence", "model_version" }
"""

from __future__ import annotations

import json
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any, Literal

from retinal_inference import config as _config
from retinal_inference.inference.e2e_parser import prepare_bscan_dcm
from retinal_inference.models.responses import FastScreenResult, FullVolumeResult
from retinal_inference.tasks import SUPPORTED_TASKS, TaskName

from .adapter import (
    FastScreenUnavailable,
    RetinalInferenceAdapter,
    UnsupportedTaskError,
)


def _post_json(url: str, payload: dict[str, Any], timeout: float) -> dict[str, Any]:
    """POST ``payload`` as JSON and return the decoded JSON response.

    Stdlib-only (no runtime HTTP dependency in the sidecar image). Unit tests
    monkeypatch this function to avoid real network calls.
    """
    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(  # noqa: S310 — internal compose-network URL
        url,
        data=data,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp:  # noqa: S310
        return json.loads(resp.read().decode("utf-8"))


class OptimaAdapter(RetinalInferenceAdapter):
    """Dispatch each task to its configured per-task model-runner."""

    _model_version: str = "optima-dispatcher-v1"

    def __init__(self) -> None:
        s = _config.settings
        # task → runner base URL (None = task not deployed here)
        self._runner_urls: dict[TaskName, str | None] = {
            "fluid": s.runner_fluid_url,
            "onl": s.runner_onl_url,
            "bmeis": s.runner_bmeis_url,
            "ga": s.runner_ga_url,
        }

    @property
    def model_version(self) -> str:
        # Per-job model version comes from each runner's response; this is the
        # dispatcher's own version (reported by /health).
        return self._model_version

    def supports(self, task: TaskName) -> bool:
        return task in SUPPORTED_TASKS and bool(self._runner_urls.get(task))

    def fast_screen(
        self,
        task: TaskName,
        e2e_path: Path,
        laterality: Literal["OD", "OS"],
    ) -> FastScreenResult:
        # Real models have no cheap synchronous path; the platform enqueues and
        # polls /jobs/{id} for the async full-volume result.
        raise FastScreenUnavailable(
            f"Task '{task}' is async-only; enqueue the job and poll /jobs/{{id}}."
        )

    def full_volume(
        self,
        task: TaskName,
        e2e_path: Path,
        laterality: Literal["OD", "OS"],
    ) -> FullVolumeResult:
        if not self.supports(task):
            raise UnsupportedTaskError(
                f"Task '{task}' has no configured runner in this deployment"
            )
        runner_url = self._runner_urls[task]
        assert runner_url is not None  # guaranteed by supports()

        # Per-job output directory on the shared volume; e2e filenames are UUIDs
        # on the Java side, so (stem, task) is unique.
        out_dir = _config.settings.shared_storage_path / f"{Path(e2e_path).stem}-{task}"

        # Shared ingestion: .e2e → bscan.dcm the runner consumes.
        bscan_dir = prepare_bscan_dcm(Path(e2e_path), out_dir)

        resp = _post_json(
            runner_url.rstrip("/") + "/infer",
            {
                "task": task,
                "bscan_dcm_path": str(bscan_dir / "bscan.dcm"),
                "laterality": laterality,
                "output_dir": str(out_dir),
            },
            timeout=_config.settings.runner_timeout_s,
        )

        return FullVolumeResult(
            task=task,
            primary_metric_value=float(resp["primary_metric_value"]),
            primary_metric_unit=str(resp["primary_metric_unit"]),
            output_payload=resp.get("output_payload", {}),
            en_face_mask_path=resp.get("en_face_mask_path"),
            bscan_masks_dir=resp.get("bscan_masks_dir", str(out_dir)),
            pixel_scale_mm=float(resp["pixel_scale_mm"]),
            confidence=float(resp.get("confidence", 0.85)),
            model_version=str(resp["model_version"]),
        )
