"""Background queue consumer.

Runs as a sibling Python process under the same container CMD. Polls
``retinal_inference_job`` for ``status='screened'`` rows, dispatches
``adapter.full_volume(task, ...)``, persists the result, marks done, and
writes an audit row. Exits cleanly on SIGTERM / SIGINT.
"""

from __future__ import annotations

import logging
import signal
import sys
import threading
from typing import Any

from psycopg2.extensions import connection as PgConnection

from retinal_inference import config as _config
from retinal_inference.audit import audit_inference_done
from retinal_inference.db.connection import direct_connect
from retinal_inference.db.queue import (
    ClaimedJob,
    claim_next_job,
    mark_done,
    mark_failed,
    persist_result,
)
from retinal_inference.inference.adapter import (
    RetinalInferenceAdapter,
    UnsupportedTaskError,
    get_adapter,
)

log = logging.getLogger(__name__)

shutdown_event = threading.Event()


def _install_signal_handlers() -> None:
    def _handle(signum: int, _frame: Any) -> None:
        log.info("worker: received signal %d, shutting down", signum)
        shutdown_event.set()

    signal.signal(signal.SIGTERM, _handle)
    signal.signal(signal.SIGINT, _handle)


def process_one_job(
    conn: PgConnection,
    adapter: RetinalInferenceAdapter,
) -> ClaimedJob | None:
    """Claim → process → persist → audit one job. Returns the job or None."""
    job = claim_next_job(conn)
    if job is None:
        return None
    try:
        result = adapter.full_volume(job.task, job.e2e_path, job.laterality)
        persist_result(conn, job, result)
        mark_done(conn, job, result.model_version)
        audit_inference_done(conn, job, result)
        log.info(
            "job %d (task=%s) done — total=%s %s",
            job.job_id,
            job.task,
            result.total_area_mm2,
            "mm²",
        )
    except UnsupportedTaskError as e:
        mark_failed(conn, job, f"unsupported task: {e}")
        log.warning("job %d (task=%s): unsupported task — %s", job.job_id, job.task, e)
    except Exception as e:  # noqa: BLE001 — worker must keep running
        mark_failed(conn, job, str(e))
        log.exception("job %d (task=%s) failed", job.job_id, job.task)
        _ = e  # keep `e` used for ruff's eyes — it's bound by `except`
    return job


def main() -> int:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s — %(message)s",
    )
    _install_signal_handlers()
    log.info(
        "worker: starting (adapter=%s, poll=%ss)",
        _config.settings.inference_adapter,
        _config.settings.worker_poll_interval_s,
    )
    adapter = get_adapter()
    conn = direct_connect()
    try:
        while not shutdown_event.is_set():
            job = process_one_job(conn, adapter)
            if job is None:
                shutdown_event.wait(timeout=_config.settings.worker_poll_interval_s)
    finally:
        conn.close()
        log.info("worker: stopped")
    return 0


if __name__ == "__main__":
    sys.exit(main())
