"""Shared pytest fixtures for the retinal-inference sidecar.

The DB-backed fixtures use ``testcontainers`` to spin a Postgres image and
apply the table DDL directly (mirroring the Liquibase changeset so this
test suite is self-contained — the Liquibase XML lives on the Java side).

If ``testcontainers`` (or its Docker socket dependency) isn't available the
fixtures are marked as skipped; the pure-Python tests (health, screen,
determinism, unknown-task) still run.
"""

from __future__ import annotations

import os
from collections.abc import Iterator
from pathlib import Path

import pytest

# Make sleeps zero before any retinal_inference module reads settings.
os.environ.setdefault("RETINAL_INFERENCE_FAST_SCREEN_SLEEP_S", "0")
os.environ.setdefault("RETINAL_INFERENCE_FULL_VOLUME_SLEEP_S", "0")
os.environ.setdefault("RETINAL_INFERENCE_INFERENCE_ADAPTER", "placeholder")
# Tests that don't need a real DB still import config; provide a benign default.
os.environ.setdefault(
    "RETINAL_INFERENCE_DB_URL",
    "postgresql://clinica:clinica@localhost:5432/libreclinica",
)


FIXTURES_DIR = Path(__file__).parent / "fixtures"
FAKE_E2E = FIXTURES_DIR / "fake.e2e"


# ---------- DDL (mirrors lc-muw-2026-06-10-retinal-inference-tables.xml) -------

_SCHEMA_SQL = """
CREATE TABLE IF NOT EXISTS event_crf (
    event_crf_id SERIAL PRIMARY KEY,
    placeholder TEXT
);

CREATE TABLE IF NOT EXISTS retinal_inference_job (
    job_id BIGSERIAL PRIMARY KEY,
    event_crf_id INT NOT NULL REFERENCES event_crf(event_crf_id),
    task VARCHAR(32) NOT NULL,
    e2e_path VARCHAR(500) NOT NULL,
    eye_laterality VARCHAR(2) NOT NULL,
    status VARCHAR(20) NOT NULL,
    status_message TEXT,
    enqueued_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    screened_at TIMESTAMP,
    segmenting_at TIMESTAMP,
    completed_at TIMESTAMP,
    model_version VARCHAR(50),
    model_git_sha VARCHAR(40)
);

CREATE INDEX IF NOT EXISTS idx_retinal_inference_job_status
    ON retinal_inference_job(status);
CREATE INDEX IF NOT EXISTS idx_retinal_inference_job_task
    ON retinal_inference_job(task);

CREATE TABLE IF NOT EXISTS retinal_inference_result (
    result_id BIGSERIAL PRIMARY KEY,
    job_id BIGINT NOT NULL UNIQUE REFERENCES retinal_inference_job(job_id)
        ON DELETE CASCADE,
    task VARCHAR(32) NOT NULL,
    output_payload JSONB NOT NULL,
    primary_metric_value NUMERIC(12,4),
    primary_metric_unit VARCHAR(16),
    en_face_mask_path VARCHAR(500),
    bscan_masks_dir VARCHAR(500),
    pixel_scale_mm NUMERIC(10,8),
    confidence NUMERIC(5,4),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
"""


# ---------- pure-Python fixtures ----------------------------------------------

@pytest.fixture
def fake_e2e_path() -> Path:
    """Path to the 16-byte fixture E2E."""
    assert FAKE_E2E.is_file(), f"missing test fixture {FAKE_E2E}"
    return FAKE_E2E


@pytest.fixture
def app_client():
    """FastAPI TestClient with the placeholder adapter forced + sleeps zeroed."""
    from fastapi.testclient import TestClient

    from retinal_inference.config import reload_settings
    from retinal_inference.inference.adapter import reset_adapter
    from retinal_inference.main import app

    reload_settings()
    reset_adapter()
    with TestClient(app) as client:
        yield client
    reset_adapter()


# ---------- DB-backed fixtures (best-effort, skipped if unavailable) ----------

def _has_testcontainers() -> bool:
    try:
        import testcontainers.postgres  # noqa: F401
    except Exception:  # noqa: BLE001
        return False
    # testcontainers needs a reachable Docker daemon.
    sock = Path("/var/run/docker.sock")
    if not sock.exists():
        return False
    return True


@pytest.fixture(scope="session")
def pg_container():
    if not _has_testcontainers():
        pytest.skip("testcontainers or docker socket unavailable")
    from testcontainers.postgres import PostgresContainer

    with PostgresContainer("postgres:14-alpine") as pg:
        yield pg


@pytest.fixture(scope="session")
def db_url(pg_container) -> str:
    # testcontainers may return a SQLAlchemy-style URL; normalise to psycopg2.
    raw = pg_container.get_connection_url()
    if raw.startswith("postgresql+psycopg2://"):
        raw = raw.replace("postgresql+psycopg2://", "postgresql://", 1)
    os.environ["RETINAL_INFERENCE_DB_URL"] = raw
    return raw


@pytest.fixture(scope="session")
def _schema_applied(db_url: str) -> str:
    import psycopg2

    conn = psycopg2.connect(db_url)
    try:
        with conn.cursor() as cur:
            cur.execute(_SCHEMA_SQL)
        conn.commit()
    finally:
        conn.close()
    return db_url


@pytest.fixture
def db_conn(_schema_applied: str) -> Iterator:
    """Per-test psycopg2 connection. Truncates the queue tables on entry."""
    import psycopg2

    conn = psycopg2.connect(_schema_applied)
    try:
        with conn.cursor() as cur:
            cur.execute(
                "TRUNCATE retinal_inference_result, retinal_inference_job, event_crf "
                "RESTART IDENTITY CASCADE"
            )
        conn.commit()
        yield conn
    finally:
        conn.close()


@pytest.fixture
def db_app_client(db_url, _schema_applied):
    """TestClient wired to the real (testcontainer) DB."""
    from fastapi.testclient import TestClient

    from retinal_inference.config import reload_settings
    from retinal_inference.db.connection import close_pool
    from retinal_inference.inference.adapter import reset_adapter
    from retinal_inference.main import app

    reload_settings()
    reset_adapter()
    close_pool()
    with TestClient(app) as client:
        yield client
    close_pool()
    reset_adapter()
