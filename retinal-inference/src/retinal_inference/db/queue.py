"""Postgres-as-queue helpers.

``FOR UPDATE SKIP LOCKED`` lets multiple workers (or restarts mid-job)
coexist without losing jobs or processing the same row twice.
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Literal

from psycopg2.extensions import connection as PgConnection
from psycopg2.extras import Json

from retinal_inference.models.responses import FullVolumeResult
from retinal_inference.tasks import TaskName


@dataclass(frozen=True)
class ClaimedJob:
    """One row claimed from ``retinal_inference_job`` for processing."""

    job_id: int
    event_crf_id: int
    task: TaskName
    e2e_path: Path
    laterality: Literal["OD", "OS"]


def claim_next_job(conn: PgConnection) -> ClaimedJob | None:
    """Atomically claim the oldest ``screened`` job and flip it to ``segmenting``.

    Returns ``None`` when the queue is empty.
    """
    with conn.cursor() as cur:
        cur.execute(
            """
            UPDATE retinal_inference_job
               SET status = 'segmenting', segmenting_at = NOW()
             WHERE job_id = (
                 SELECT job_id FROM retinal_inference_job
                  WHERE status = 'screened'
                  ORDER BY enqueued_at
                    FOR UPDATE SKIP LOCKED
                  LIMIT 1
             )
         RETURNING job_id, event_crf_id, task, e2e_path, eye_laterality
            """
        )
        row = cur.fetchone()
        conn.commit()
    if row is None:
        return None
    job_id, event_crf_id, task, e2e_path, eye_laterality = row
    return ClaimedJob(
        job_id=int(job_id),
        event_crf_id=int(event_crf_id),
        task=task,  # type: ignore[arg-type]
        e2e_path=Path(e2e_path),
        laterality=eye_laterality,  # type: ignore[arg-type]
    )


def persist_result(
    conn: PgConnection,
    job: ClaimedJob,
    result: FullVolumeResult,
) -> None:
    """Insert one ``retinal_inference_result`` row for ``job``."""
    payload: dict[str, Any] = {
        "total_area_mm2": result.total_area_mm2,
        "per_bscan_areas_mm2": result.per_bscan_areas_mm2,
    }
    with conn.cursor() as cur:
        cur.execute(
            """
            INSERT INTO retinal_inference_result (
                job_id, task, output_payload,
                primary_metric_value, primary_metric_unit,
                en_face_mask_path, pixel_scale_mm, confidence
            )
            VALUES (%s, %s, %s, %s, %s, %s, %s, %s)
            """,
            (
                job.job_id,
                result.task,
                Json(payload),
                result.total_area_mm2,
                "mm²",
                result.en_face_mask_path,
                result.pixel_scale_mm,
                result.confidence,
            ),
        )
        conn.commit()


def mark_done(conn: PgConnection, job: ClaimedJob, model_version: str) -> None:
    """Flip the job row to ``done`` and stamp the model version."""
    with conn.cursor() as cur:
        cur.execute(
            """
            UPDATE retinal_inference_job
               SET status = 'done',
                   completed_at = NOW(),
                   model_version = %s,
                   status_message = NULL
             WHERE job_id = %s
            """,
            (model_version, job.job_id),
        )
        conn.commit()


def mark_failed(conn: PgConnection, job: ClaimedJob, message: str) -> None:
    """Flip the job row to ``failed`` with a human-readable status message."""
    with conn.cursor() as cur:
        cur.execute(
            """
            UPDATE retinal_inference_job
               SET status = 'failed',
                   completed_at = NOW(),
                   status_message = %s
             WHERE job_id = %s
            """,
            (message[:2000], job.job_id),
        )
        conn.commit()


def load_job_status(conn: PgConnection, job_id: int) -> dict[str, Any] | None:
    """LEFT JOIN job + result for the polling endpoint."""
    with conn.cursor() as cur:
        cur.execute(
            """
            SELECT j.job_id, j.task, j.status,
                   j.enqueued_at, j.screened_at, j.completed_at,
                   j.model_version, j.status_message,
                   r.output_payload
              FROM retinal_inference_job j
              LEFT JOIN retinal_inference_result r ON r.job_id = j.job_id
             WHERE j.job_id = %s
            """,
            (job_id,),
        )
        row = cur.fetchone()
    if row is None:
        return None
    (
        job_id_v,
        task,
        status,
        enqueued_at,
        screened_at,
        completed_at,
        model_version,
        status_message,
        output_payload,
    ) = row
    # psycopg2 returns JSONB as a dict directly; if it ever comes back as a
    # str, decode defensively so the response model accepts it.
    result_payload: dict[str, Any] | None
    if output_payload is None:
        result_payload = None
    elif isinstance(output_payload, str):
        result_payload = json.loads(output_payload)
    else:
        result_payload = output_payload
    return {
        "job_id": int(job_id_v),
        "task": task,
        "status": status,
        "enqueued_at": enqueued_at,
        "screened_at": screened_at,
        "completed_at": completed_at,
        "model_version": model_version,
        "status_message": status_message,
        "result": result_payload,
    }
