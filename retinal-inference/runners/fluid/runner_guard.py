"""Request guard for a model runner's POST /infer.

Each runner image is built from its own directory, so this module is copied
verbatim into fluid/, ga/, onl/ and pr/ (tests/test_runner_guard.py keeps the
copies identical). Written for the oldest runner interpreter, Python 3.7: no
PEP 604 unions at runtime, no walrus, no ``Path.is_relative_to``.

The dispatching sidecar only ever sends absolute paths it created itself — a
``bscan.dcm`` and an output directory inside the shared tmpdir (POST /run) or
inside the segmentation-output volume (the DB-poll worker). /infer accepts
paths below those roots only, symlinks resolved.

Environment:
  RUNNER_ALLOWED_ROOTS  os.pathsep-separated roots; defaults to the two shared
                        mounts every runner has in compose.
  RUNNER_AUTH_TOKEN     when set, /infer requires the sidecar's shared token
                        (RETINAL_INFERENCE_AUTH_TOKEN) in X-MUW-Inference-Token.
"""

import hmac
import os
from pathlib import Path
from typing import List, Optional

from fastapi import HTTPException

TOKEN_HEADER = "X-MUW-Inference-Token"
DEFAULT_ALLOWED_ROOTS = os.pathsep.join(
    ["/var/lib/retinal-inference/tmp", "/var/lib/libreclinica/segmentation-output"]
)


def allowed_roots() -> List[str]:
    raw = os.environ.get("RUNNER_ALLOWED_ROOTS", DEFAULT_ALLOWED_ROOTS)
    return [os.path.realpath(r.strip()) for r in raw.split(os.pathsep) if r.strip()]


def confined(raw: str, field: str) -> Path:
    """``raw`` resolved, provided it is absolute and lies below an allowed root."""
    real = None
    if isinstance(raw, str) and os.path.isabs(raw):
        try:
            real = os.path.realpath(raw)
        except (ValueError, OSError):  # e.g. an embedded NUL byte
            real = None
    if real is not None:
        for root in allowed_roots():
            prefix = root if root.endswith(os.sep) else root + os.sep
            if real.startswith(prefix):
                return Path(real)
    raise HTTPException(
        status_code=400, detail=field + " is not below the runner's shared directories"
    )


def check_token(presented: Optional[str]) -> None:
    """401 unless ``presented`` is RUNNER_AUTH_TOKEN; a no-op when that is unset."""
    expected = os.environ.get("RUNNER_AUTH_TOKEN", "")
    if not expected:
        return
    if presented is None or not hmac.compare_digest(
        presented.encode("utf-8"), expected.encode("utf-8")
    ):
        raise HTTPException(status_code=401, detail="Invalid or missing " + TOKEN_HEADER)
