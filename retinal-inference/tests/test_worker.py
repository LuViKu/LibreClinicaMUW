"""worker.process_one_job — claim → process → persist → audit round-trip."""

from __future__ import annotations

import pytest

testcontainers = pytest.importorskip("testcontainers.postgres")


def test_worker_processes_screened_job(db_conn, fake_e2e_path) -> None:
    from retinal_inference.config import reload_settings
    from retinal_inference.inference.adapter import get_adapter, reset_adapter
    from retinal_inference.worker import process_one_job

    reload_settings()
    reset_adapter()
    adapter = get_adapter()

    with db_conn.cursor() as cur:
        cur.execute("INSERT INTO event_crf DEFAULT VALUES RETURNING event_crf_id")
        (event_crf_id,) = cur.fetchone()
        cur.execute(
            """
            INSERT INTO retinal_inference_job (
                event_crf_id, task, e2e_path, eye_laterality, status, screened_at
            ) VALUES (%s, 'ga', %s, 'OD', 'screened', NOW())
            RETURNING job_id
            """,
            (event_crf_id, str(fake_e2e_path)),
        )
        (job_id,) = cur.fetchone()
    db_conn.commit()

    job = process_one_job(db_conn, adapter)
    assert job is not None
    assert job.job_id == job_id

    # Job row should now be 'done' with model_version stamped.
    with db_conn.cursor() as cur:
        cur.execute(
            "SELECT status, model_version, completed_at FROM retinal_inference_job WHERE job_id = %s",
            (job_id,),
        )
        status, model_version, completed_at = cur.fetchone()
    assert status == "done"
    assert model_version == "placeholder-v1"
    assert completed_at is not None

    # Result row should exist with the GA-task fields.
    with db_conn.cursor() as cur:
        cur.execute(
            """
            SELECT task, primary_metric_unit, primary_metric_value,
                   output_payload, pixel_scale_mm
              FROM retinal_inference_result
             WHERE job_id = %s
            """,
            (job_id,),
        )
        task, unit, value, payload, pixel_scale = cur.fetchone()
    assert task == "ga"
    assert unit == "mm²"
    assert float(value) > 0
    assert "total_area_mm2" in payload
    assert "per_bscan_areas_mm2" in payload
    assert float(pixel_scale) == pytest.approx(0.011, abs=1e-6)


def test_worker_returns_none_on_empty_queue(db_conn) -> None:
    from retinal_inference.inference.adapter import get_adapter, reset_adapter
    from retinal_inference.worker import process_one_job

    reset_adapter()
    adapter = get_adapter()
    result = process_one_job(db_conn, adapter)
    assert result is None
