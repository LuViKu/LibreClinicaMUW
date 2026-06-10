"""GET /jobs/{job_id} — round-trip via testcontainers Postgres."""

from __future__ import annotations

import json

import pytest

testcontainers = pytest.importorskip("testcontainers.postgres")


def test_get_job_returns_shape(db_conn, db_app_client) -> None:
    with db_conn.cursor() as cur:
        cur.execute("INSERT INTO event_crf DEFAULT VALUES RETURNING event_crf_id")
        (event_crf_id,) = cur.fetchone()
        cur.execute(
            """
            INSERT INTO retinal_inference_job (
                event_crf_id, task, e2e_path, eye_laterality, status,
                enqueued_at, screened_at, completed_at, model_version
            ) VALUES (%s, 'ga', '/tmp/fake.e2e', 'OD', 'done',
                      NOW() - INTERVAL '5 min',
                      NOW() - INTERVAL '4 min',
                      NOW(),
                      'placeholder-v1')
            RETURNING job_id
            """,
            (event_crf_id,),
        )
        (job_id,) = cur.fetchone()
        payload = {"total_area_mm2": 4.123, "per_bscan_areas_mm2": {"bscan_0": 0.1}}
        cur.execute(
            """
            INSERT INTO retinal_inference_result (
                job_id, task, output_payload, primary_metric_value,
                primary_metric_unit, en_face_mask_path, pixel_scale_mm, confidence
            ) VALUES (%s, 'ga', %s::jsonb, 4.123, 'mm²', '/tmp/mask.png', 0.011, 0.85)
            """,
            (job_id, json.dumps(payload)),
        )
    db_conn.commit()

    resp = db_app_client.get(f"/jobs/{job_id}")
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["job_id"] == job_id
    assert body["task"] == "ga"
    assert body["status"] == "done"
    assert body["model_version"] == "placeholder-v1"
    assert body["result"] == payload


def test_get_job_404_when_missing(db_app_client) -> None:
    resp = db_app_client.get("/jobs/99999999")
    assert resp.status_code == 404
