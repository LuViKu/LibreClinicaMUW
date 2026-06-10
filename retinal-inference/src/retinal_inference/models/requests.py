"""Input shapes for sidecar endpoints."""

from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, Field

from retinal_inference.tasks import TaskName


class ScreenRequest(BaseModel):
    """Sync fast-screen request body.

    The Java side owns the ``retinal_inference_job`` row state machine; the
    sidecar only computes and reports.
    """

    job_id: int = Field(..., ge=0, description="Row id in retinal_inference_job.")
    task: TaskName = Field(
        ..., description="Inference task discriminator. v1 only enables 'ga'."
    )
    e2e_path: str = Field(
        ..., min_length=1, description="Absolute path of the E2E inside the sidecar container."
    )
    laterality: Literal["OD", "OS"] = Field(
        ..., description="Eye laterality — OD=right, OS=left."
    )
