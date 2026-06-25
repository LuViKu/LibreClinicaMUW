"""Gather per-task runner outputs from the sidecar's tempdir into an Artifact list.

After ``OptimaAdapter.full_volume(out_dir_override=tempdir)`` returns, the
runner has written its CSV / NPY / PNG outputs into the tempdir. The remote
``/run`` endpoint needs to ship those bytes back to Java inline (the GPU host
stores nothing past the request), so we walk the tempdir, base64-encode the
files the receiver actually wants, and rewrite any absolute paths the runner
embedded in ``output_payload`` into basenames the receiver can resolve against
its own artifact store.

The walker is permissive — it returns every file matching a small allowlist of
extensions (CSV / NPY / PNG) instead of hand-coding the per-task output table.
That keeps it resilient when vendor pipelines silently add a new CSV: the
artifact still lands on the institutional side, the operator browses it like
any other.
"""

from __future__ import annotations

import base64
from pathlib import Path
from typing import Any, Iterable

from retinal_inference.models.run import Artifact

# RFC 6838 media types per extension. Anything not in this map is skipped so we
# don't accidentally ship 100 MB of intermediate per-bscan PNGs.
_MEDIA_TYPES: dict[str, str] = {
    ".csv": "text/csv",
    ".npy": "application/octet-stream",
    ".npz": "application/octet-stream",
    ".png": "image/png",
    ".json": "application/json",
}

# Skip the source bscan.dcm — it was synthesised by the sidecar and the bytes
# would just round-trip uselessly. Also skip any nested mhd_in dir that the
# pr runner uses for its DICOM→MHD pre-conversion.
_SKIP_NAMES: frozenset[str] = frozenset({"bscan.dcm"})
_SKIP_DIRS: frozenset[str] = frozenset({"mhd_in"})


def collect_artifacts(tempdir: Path, only: Iterable[str] | None = None) -> list[Artifact]:
    """Walk ``tempdir`` and return inline-encoded artifacts the runner wrote.

    When ``only`` is given, collect ONLY files whose basename is in that set
    (anywhere under ``tempdir``) — the handler's explicit returned-artifact list.
    When ``only`` is None, fall back to the permissive allowlist walk.
    """
    only_set = set(only) if only is not None else None
    out: list[Artifact] = []
    for path in sorted(tempdir.rglob("*")):
        if not path.is_file():
            continue
        if path.name in _SKIP_NAMES:
            continue
        if only_set is not None:
            if path.name not in only_set:
                continue
        elif any(part in _SKIP_DIRS for part in path.relative_to(tempdir).parts[:-1]):
            continue
        media_type = _MEDIA_TYPES.get(path.suffix.lower())
        if media_type is None:
            continue
        encoded = base64.b64encode(path.read_bytes()).decode("ascii")
        out.append(
            Artifact(
                name=path.name,
                media_type=media_type,
                content_base64=encoded,
            )
        )
    return out


def rewrite_payload_paths(payload: dict[str, Any], tempdir: Path) -> dict[str, Any]:
    """Strip absolute tempdir paths out of ``output_payload``.

    Runners embed paths like ``/var/lib/retinal-inference/tmp/run-XYZ/001-OPL-HFL.csv``
    in their JSON output. Those mean nothing on the Java side — replace them
    with just the basename so the receiver can resolve against its own artifact
    store layout. Non-path values pass through untouched.
    """
    tempdir_str = str(tempdir)
    rewritten: dict[str, Any] = {}
    for key, value in payload.items():
        if isinstance(value, str) and value.startswith(tempdir_str):
            rewritten[key] = Path(value).name
        else:
            rewritten[key] = value
    return rewritten


def filenames(artifacts: Iterable[Artifact]) -> list[str]:
    """Return just the names — used by tests for tight assertions."""
    return [a.name for a in artifacts]