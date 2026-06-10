"""Audit logging — one row per completed retinal inference job.

The clinical audit table on the Java side is ``audit_log_event``; its column
layout has shifted a few times across LibreClinica versions, so this module
is *defensive*: it tries the Phase E.6 layout first, then falls back to a
sidecar-local ``retinal_inference_audit`` placeholder, and finally swallows
the failure with a warning. Audit logging must never kill a job.
"""

from __future__ import annotations

import logging
from typing import Any

from psycopg2.extensions import connection as PgConnection

from retinal_inference.db.queue import ClaimedJob
from retinal_inference.models.responses import FullVolumeResult

log = logging.getLogger(__name__)


def audit_inference_done(
    conn: PgConnection,
    job: ClaimedJob,
    result: FullVolumeResult,
) -> None:
    """Write one audit row for the completed job.

    Defensive: any DB failure is logged and swallowed — the row already
    flipped to ``done`` and the result row is persisted, so failing the audit
    write would unfairly punish the clinical pipeline.

    TODO: align with the canonical ``audit_log_event`` shape (audit_id,
    audit_table, entity_id, action_message, user_account_id, date_updated,
    old_value, new_value) once the Phase E.6 EventCrfsApiController.
    writeAuditEvent helper is exposed to non-Java services.
    """
    action_message = (
        f"Retinal inference completed — task={job.task} "
        f"model={result.model_version} total={result.total_area_mm2} mm²"
    )

    # 1) Try the canonical Java-side audit_log_event shape.
    try:
        with conn.cursor() as cur:
            cur.execute(
                """
                INSERT INTO audit_log_event (
                    audit_log_event_type_id, audit_table, entity_id,
                    entity_name, audit_date, user_id, action_message
                ) VALUES (%s, %s, %s, %s, NOW(), %s, %s)
                """,
                (
                    3,  # informational event type
                    "retinal_inference_job",
                    job.event_crf_id,
                    "retinal_inference_job",
                    None,  # sidecar has no user_id; Java fills it for user-driven actions
                    action_message,
                ),
            )
            conn.commit()
        return
    except Exception as e:  # noqa: BLE001 — defensive on purpose
        conn.rollback()
        log.debug("audit_log_event insert failed (%s); falling back to placeholder", e)

    # 2) Fall back to a sidecar-local placeholder table (best-effort).
    try:
        with conn.cursor() as cur:
            cur.execute(
                """
                CREATE TABLE IF NOT EXISTS retinal_inference_audit (
                    audit_id BIGSERIAL PRIMARY KEY,
                    job_id BIGINT NOT NULL,
                    event_crf_id INT NOT NULL,
                    task VARCHAR(32) NOT NULL,
                    model_version VARCHAR(50),
                    action_message TEXT NOT NULL,
                    audit_date TIMESTAMP NOT NULL DEFAULT NOW()
                )
                """
            )
            cur.execute(
                """
                INSERT INTO retinal_inference_audit (
                    job_id, event_crf_id, task, model_version, action_message
                ) VALUES (%s, %s, %s, %s, %s)
                """,
                (
                    job.job_id,
                    job.event_crf_id,
                    job.task,
                    result.model_version,
                    action_message,
                ),
            )
            conn.commit()
    except Exception as e:  # noqa: BLE001 — defensive on purpose
        conn.rollback()
        log.warning(
            "audit write failed for job %d (task=%s): %s — clinical job stays 'done'",
            job.job_id,
            job.task,
            e,
        )


# Re-exported types for callers that don't want to import from db.queue.
__all__ = ["audit_inference_done", "ClaimedJob", "FullVolumeResult", "Any"]
