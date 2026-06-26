"""
Backfill ``retinal_inference_job.acquisition_date`` from the stored .e2e
device-side acquisition stamp.

Symptom that motivated this: ``Aufnahmedatum`` showed ``—`` for every
historical job in the SPA's job list. The shipped fix
(``RetinalInferenceApiController.persistAcquisitionDate``, commit b43cb1dfe)
only writes the date on NEW uploads — every pre-existing row's
``acquisition_date`` was NULL because the auth path used to drop the
value the preprocess sidecar pulled out of the .e2e header. This script
catches the old rows up.

Approach
--------
For every job with ``acquisition_date IS NULL`` AND a readable
``e2e_path`` on disk:

1. Read the .e2e via the same ``muw_e2e_converter.read_e2e_volume``
   call the live preprocess pipeline uses.
2. Extract the ISO ``YYYY-MM-DD`` from ``vol_meta.acquisition_date``.
3. UPDATE ``retinal_inference_job`` for every job sharing the same
   ``e2e_path`` (so the read cost amortises across all tasks that
   spawned from the one upload).

Dedup per ``e2e_path`` because reading a 100+ MB .e2e is expensive
(decode the full volume), and a typical upload spawns 4-6 jobs
(fluid + onl + pr + ga + layers + bm).

How to run
----------
From the host::

    docker exec libreclinica-muw-retinal-preprocess-1 \\
        python /var/lib/libreclinica/scripts/backfill_acquisition_date.py

(The script is mounted into the retinal-preprocess container via the
existing repo bind — see the docker-compose volume mounting.)

Or copy it in and exec on the fly::

    docker cp retinal-inference/scripts/backfill_acquisition_date.py \\
        libreclinica-muw-retinal-preprocess-1:/tmp/backfill.py
    docker exec libreclinica-muw-retinal-preprocess-1 \\
        python /tmp/backfill.py

The script is idempotent — re-running it after the rebuild is safe
(jobs with a populated ``acquisition_date`` are filtered out by the
SELECT and the UPDATE's WHERE clause double-checks).
"""
from __future__ import annotations

import logging
import os
import sys
from pathlib import Path
from typing import Iterable

# Soft dep — the script is meant to run inside the retinal-preprocess
# container which has psycopg2 + muw_e2e_converter both available.
import psycopg2  # type: ignore[import-not-found]
import psycopg2.extras  # type: ignore[import-not-found]

from muw_e2e_converter import read_e2e_volume

LOG = logging.getLogger("backfill_acquisition_date")


def db_dsn() -> str:
    """Build a libpq DSN from compose-network defaults + optional overrides."""
    host = os.environ.get("LIBRECLINICA_DB_HOST", "db")
    port = os.environ.get("LIBRECLINICA_DB_PORT", "5432")
    user = os.environ.get("LIBRECLINICA_DB_USER", "clinica")
    pwd = os.environ.get("LIBRECLINICA_DB_PASSWORD", "clinica")
    name = os.environ.get("LIBRECLINICA_DB_NAME", "libreclinica")
    return f"host={host} port={port} user={user} password={pwd} dbname={name}"


def fetch_targets(conn) -> list[tuple[str, list[int]]]:
    """Return ``[(e2e_path, [job_id, …]), …]`` grouped by unique e2e file.

    Only rows whose ``acquisition_date`` is NULL AND whose ``e2e_path``
    points at an existing file on disk get returned. Missing files are
    skipped + WARN'd so a hand-cleaned uploads dir doesn't error the
    whole batch.
    """
    out: dict[str, list[int]] = {}
    with conn.cursor() as cur:
        cur.execute(
            "SELECT job_id, e2e_path "
            "  FROM retinal_inference_job "
            " WHERE acquisition_date IS NULL "
            "   AND e2e_path IS NOT NULL "
            " ORDER BY e2e_path, job_id"
        )
        for job_id, e2e_path in cur.fetchall():
            if not Path(e2e_path).is_file():
                LOG.warning("Job %s — e2e_path %r not on disk, skipping", job_id, e2e_path)
                continue
            out.setdefault(e2e_path, []).append(job_id)
    return sorted(out.items())


def read_acquisition_date(e2e_path: str) -> str | None:
    """Decode the .e2e and return ISO YYYY-MM-DD, or None when blank.

    Soft-fail on parse errors (the script logs + skips the file rather
    than blowing up the whole batch). The full volume decode is wasteful
    for a metadata-only read but matches what the live preprocess
    sidecar does; a future optimisation could parse just the header.
    """
    try:
        vol = read_e2e_volume(Path(e2e_path), scan_index=0)
    except Exception as e:  # pylint: disable=broad-except
        LOG.warning("Failed to decode %s: %s", e2e_path, e)
        return None
    iso = vol.acquisition_date
    if not iso:
        return None
    # read_e2e_volume returns ISO YYYY-MM-DD already (str | None).
    # Trim to 10 chars defensively in case a future emitter widens it.
    return iso[:10]


def update_jobs(conn, e2e_path: str, iso: str, job_ids: Iterable[int]) -> int:
    """UPDATE the job rows + return the affected count.

    Filters by ``e2e_path`` AND ``acquisition_date IS NULL`` so a
    concurrent UPDATE (e.g. a fresh job for the same .e2e landing
    while the script runs) doesn't get clobbered. The job_id list
    isn't strictly necessary for the UPDATE but it's logged so the
    operator can correlate against the SPA job list.
    """
    with conn.cursor() as cur:
        cur.execute(
            "UPDATE retinal_inference_job "
            "   SET acquisition_date = %s "
            " WHERE e2e_path = %s "
            "   AND acquisition_date IS DISTINCT FROM %s",
            (iso, e2e_path, iso),
        )
        affected = cur.rowcount
    LOG.info(
        "Updated %d row(s) for %s (jobs=%s) → acquisition_date=%s",
        affected,
        Path(e2e_path).name,
        ",".join(str(j) for j in job_ids),
        iso,
    )
    return affected


def main(argv: list[str]) -> int:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
    )
    dry_run = "--dry-run" in argv
    if dry_run:
        LOG.info("DRY-RUN — no UPDATEs will be committed")

    conn = psycopg2.connect(db_dsn())
    conn.autocommit = False
    try:
        targets = fetch_targets(conn)
        LOG.info("Found %d unique .e2e file(s) to backfill", len(targets))
        if not targets:
            return 0

        total_files = 0
        total_rows = 0
        no_date = 0
        for e2e_path, job_ids in targets:
            iso = read_acquisition_date(e2e_path)
            if iso is None:
                no_date += 1
                LOG.info(
                    "Skipped %s (jobs=%s) — header has no acquisition_date",
                    Path(e2e_path).name,
                    ",".join(str(j) for j in job_ids),
                )
                continue
            if dry_run:
                LOG.info(
                    "[dry-run] Would update %d row(s) for %s (jobs=%s) → %s",
                    len(job_ids),
                    Path(e2e_path).name,
                    ",".join(str(j) for j in job_ids),
                    iso,
                )
                total_files += 1
                total_rows += len(job_ids)
                continue
            total_files += 1
            total_rows += update_jobs(conn, e2e_path, iso, job_ids)
        if not dry_run:
            conn.commit()
        LOG.info(
            "Done — files-updated=%d rows-updated=%d files-skipped-no-date=%d",
            total_files,
            total_rows,
            no_date,
        )
        return 0
    finally:
        conn.close()


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
