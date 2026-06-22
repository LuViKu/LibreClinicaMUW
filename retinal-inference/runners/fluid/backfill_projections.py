#!/usr/bin/env python3
"""Backfill projection_fluid*.png + seg_bscan_*.png from fluidseg.npz.

The runner emits the projection artifacts during each /infer call by
calling ``projection.render_fluid_projection``. Jobs that completed
before the projection code shipped (or on a runner host that hadn't
been restarted to pick up the new code) only have the bare
``fluidseg.npz`` in their artifact directory. Re-running inference is
expensive (~minutes per scan on CPU) and the segmentation labels in
fluidseg.npz are deterministic + identical to what the runner would
have produced — so we can just feed each existing npz back through
the production projection helper and get the same PNGs without
touching the inference pipeline.

Usage:
    # walk every per-job dir under the artifact store, emit anything
    # missing in place:
    python backfill_projections.py /var/lib/libreclinica/retinal-artifacts

    # dry-run — list what would be written, write nothing:
    python backfill_projections.py --dry-run /var/lib/libreclinica/retinal-artifacts

The script is idempotent: every per-job dir that already has the
PNG set is skipped (presence check on ``projection_fluid.png``).
Pass ``--force`` to re-emit unconditionally.
"""

from __future__ import annotations

import argparse
import logging
import sys
from pathlib import Path

import numpy as np

# Import the production helper. The script lives next to projection.py
# so the relative import works whether you run it via
# `python backfill_projections.py …` or
# `python -m runners.fluid.backfill_projections …`.
sys.path.insert(0, str(Path(__file__).resolve().parent))
from projection import render_fluid_projection  # noqa: E402

LOG = logging.getLogger("backfill_projections")


def _load_segmentation(npz_path: Path):
    """Load the 3D label volume the runner stored as ``fluidseg.npz``.

    The runner writes the volume via ``np.savez(.., segmentation=arr)``
    so it lives under the ``segmentation`` key. Older runner versions
    sometimes wrote a bare ``.npy`` instead — fall back to that when
    present.
    """
    with np.load(npz_path) as data:
        if "segmentation" not in data:
            raise KeyError(
                f"{npz_path}: expected key 'segmentation' inside npz; "
                f"got {list(data.keys())}"
            )
        return data["segmentation"]


def _job_dir_iter(root: Path):
    """Yield each per-job directory under ``root`` that has fluidseg.npz."""
    for child in sorted(root.iterdir()):
        if not child.is_dir():
            continue
        if (child / "fluidseg.npz").is_file():
            yield child


def _needs_backfill(job_dir: Path) -> bool:
    """True when ``job_dir`` has the npz but not the composite PNG."""
    if not (job_dir / "fluidseg.npz").is_file():
        return False
    if (job_dir / "projection_fluid.png").is_file():
        return False
    return True


def backfill_one(job_dir: Path, *, force: bool, dry_run: bool) -> bool:
    """Re-emit projection + per-slice PNGs for one job directory.

    Returns True when the directory was processed (i.e. work was
    needed or `--force` was set), False when skipped.
    """
    if not force and not _needs_backfill(job_dir):
        LOG.debug("skip %s — already has projection_fluid.png", job_dir)
        return False
    if dry_run:
        LOG.info("[dry-run] would backfill %s", job_dir)
        return True
    seg = _load_segmentation(job_dir / "fluidseg.npz")
    proj_name, per_bscan = render_fluid_projection(seg, job_dir)
    # render_fluid_projection writes the composite + per-biomarker +
    # per-slice PNGs into job_dir as a side effect; the return tuple
    # is just metadata the runner uses for the wire payload.
    LOG.info(
        "wrote %s (+ per-biomarker + per-slice PNGs) into %s — "
        "irf/srf/ped per-bscan counts: %d/%d/%d",
        proj_name,
        job_dir,
        len(per_bscan.get("irf", [])),
        len(per_bscan.get("srf", [])),
        len(per_bscan.get("ped", [])),
    )
    return True


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "store_path",
        type=Path,
        help="Path to the per-job artifact store root "
        "(e.g. /var/lib/libreclinica/retinal-artifacts).",
    )
    parser.add_argument(
        "--force",
        action="store_true",
        help="Re-emit PNGs even when projection_fluid.png is already present.",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="List directories that would be backfilled; write nothing.",
    )
    parser.add_argument(
        "--verbose",
        action="store_true",
        help="Print debug-level logs (includes skipped dirs).",
    )
    args = parser.parse_args()

    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(levelname)s %(message)s",
    )

    root: Path = args.store_path
    if not root.is_dir():
        LOG.error("artifact-store root %s does not exist or is not a directory", root)
        return 2

    seen = 0
    written = 0
    errored = 0
    for job_dir in _job_dir_iter(root):
        seen += 1
        try:
            if backfill_one(job_dir, force=args.force, dry_run=args.dry_run):
                written += 1
        except Exception as exc:  # noqa: BLE001 — surface every failure
            errored += 1
            LOG.error("failed to backfill %s: %s", job_dir, exc)
    LOG.info(
        "summary: scanned=%d backfilled=%d errored=%d (dry_run=%s, force=%s)",
        seen,
        written,
        errored,
        args.dry_run,
        args.force,
    )
    return 0 if errored == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
