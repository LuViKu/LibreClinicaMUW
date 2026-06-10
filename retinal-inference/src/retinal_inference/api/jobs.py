"""GET /jobs/{job_id} — status polling for the SPA.

LEFT JOIN of ``retinal_inference_job`` and ``retinal_inference_result`` so
the response carries the structured payload as soon as the worker has
persisted it.
"""

from __future__ import annotations

from fastapi import APIRouter, HTTPException

from retinal_inference.db.connection import pool_conn
from retinal_inference.db.queue import load_job_status
from retinal_inference.models.responses import JobStatusResponse

router = APIRouter()


@router.get("/jobs/{job_id}", response_model=JobStatusResponse)
def get_job(job_id: int) -> JobStatusResponse:
    with pool_conn() as conn:
        row = load_job_status(conn, job_id)
    if row is None:
        raise HTTPException(status_code=404, detail=f"Job {job_id} not found")
    return JobStatusResponse(**row)
