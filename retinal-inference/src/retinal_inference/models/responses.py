"""Output shapes — both internal (adapter contract) and external (HTTP)."""

from __future__ import annotations

from datetime import datetime
from typing import Any

from pydantic import BaseModel, Field

from retinal_inference.tasks import TaskName

# ----- internal adapter results -------------------------------------------------

class FastScreenResult(BaseModel):
    """Result of ``RetinalInferenceAdapter.fast_screen``."""

    task: TaskName
    approx_area_mm2: float
    foveal_bscan_index: int
    confidence: float
    model_version: str


class FullVolumeResult(BaseModel):
    """Result of ``RetinalInferenceAdapter.full_volume``.

    Task-agnostic: ``primary_metric_value``/``primary_metric_unit`` and the
    free-form ``output_payload`` map straight onto the generic
    ``retinal_inference_result`` columns, so area (GA), volume (fluid), and
    layer-thickness (ONL/BMEIS) tasks all fit without a schema change.
    """

    task: TaskName
    primary_metric_value: float
    primary_metric_unit: str
    output_payload: dict[str, Any] = Field(
        default_factory=dict,
        description="Full per-task result, persisted verbatim to output_payload JSONB.",
    )
    en_face_mask_path: str | None = None
    bscan_masks_dir: str | None = None
    pixel_scale_mm: float
    confidence: float = 0.85
    model_version: str

    # --- back-compat: the GA area task + the deterministic placeholder still
    # populate these; the worker log and older callers read them when present.
    total_area_mm2: float | None = None
    per_bscan_areas_mm2: dict[str, float] | None = None


# ----- HTTP response shapes -----------------------------------------------------

class ScreenResponse(BaseModel):
    """200 response from POST /screen."""

    job_id: int
    task: TaskName
    approx_area_mm2: float
    foveal_bscan_index: int
    confidence: float
    model_version: str


class HealthResponse(BaseModel):
    """200 response from GET /health."""

    status: str
    adapter: str
    model_version: str
    supported_tasks: list[TaskName]


class JobStatusResponse(BaseModel):
    """200 response from GET /jobs/{job_id}."""

    job_id: int
    task: TaskName
    status: str
    enqueued_at: datetime
    screened_at: datetime | None = None
    completed_at: datetime | None = None
    model_version: str | None = None
    status_message: str | None = None
    result: dict[str, Any] | None = Field(
        default=None,
        description="Structured per-task output payload from retinal_inference_result.",
    )
