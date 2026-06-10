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
    """Result of ``RetinalInferenceAdapter.full_volume``."""

    task: TaskName
    total_area_mm2: float
    per_bscan_areas_mm2: dict[str, float]
    en_face_mask_path: str
    pixel_scale_mm: float
    confidence: float = 0.85
    model_version: str


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
