"""GET /health — readiness + adapter introspection."""

from __future__ import annotations

from fastapi import APIRouter

from retinal_inference.inference.adapter import get_adapter
from retinal_inference.models.responses import HealthResponse
from retinal_inference.tasks import SUPPORTED_TASKS

router = APIRouter()


@router.get("/health", response_model=HealthResponse)
def health() -> HealthResponse:
    adapter = get_adapter()
    return HealthResponse(
        status="ok",
        adapter=type(adapter).__name__.removesuffix("Adapter").lower(),
        model_version=adapter.model_version,
        supported_tasks=sorted(SUPPORTED_TASKS),
    )
