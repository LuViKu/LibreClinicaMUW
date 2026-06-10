"""FastAPI app entry — sync ``/screen`` + status polling ``/jobs/{id}``.

The background worker (see ``retinal_inference.worker``) runs as a sibling
process under the same container CMD and shares the in-memory adapter
singleton via Python module-level state.
"""

from __future__ import annotations

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI

from retinal_inference.api import health, jobs, screen
from retinal_inference.inference.adapter import get_adapter


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    # Eager adapter warmup; worker imports the same singleton.
    get_adapter()
    yield


app = FastAPI(
    title="LibreClinicaMUW Retinal Inference",
    version="0.1.0",
    description=(
        "Task-agnostic retinal inference sidecar — sync /screen + background "
        "queue worker. v1 enables task=ga."
    ),
    lifespan=lifespan,
)

app.include_router(health.router)
app.include_router(screen.router)
app.include_router(jobs.router)
