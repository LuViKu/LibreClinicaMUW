"""psycopg2 connection pool factory.

Single ``ThreadedConnectionPool`` per process — the worker checks out one
long-lived conn and the FastAPI request handlers grab short-lived ones via
``with pool_conn() as conn:``.
"""

from __future__ import annotations

from collections.abc import Iterator
from contextlib import contextmanager

import psycopg2
from psycopg2.extensions import connection as PgConnection
from psycopg2.pool import ThreadedConnectionPool

from retinal_inference import config as _config

_pool: ThreadedConnectionPool | None = None


def open_pool(min_conn: int = 1, max_conn: int = 8) -> ThreadedConnectionPool:
    """Open (or return) the module-level connection pool."""
    global _pool
    if _pool is None:
        _pool = ThreadedConnectionPool(min_conn, max_conn, dsn=_config.settings.db_url)
    return _pool


def close_pool() -> None:
    global _pool
    if _pool is not None:
        _pool.closeall()
        _pool = None


@contextmanager
def pool_conn() -> Iterator[PgConnection]:
    """Check out a connection from the pool, return it on context exit."""
    pool = open_pool()
    conn = pool.getconn()
    try:
        yield conn
    finally:
        pool.putconn(conn)


def direct_connect() -> PgConnection:
    """Single-shot connection — used by the worker which owns one for its lifetime."""
    return psycopg2.connect(_config.settings.db_url)
