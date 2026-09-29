"""Request-hardening helpers shared by the sidecar's HTTP routes.

Kept in one place so every route applies them the same way.
"""

from __future__ import annotations

import hmac
import os
from pathlib import Path


def token_matches(presented: str | None, expected: str | None) -> bool:
    """True when ``presented`` equals the configured shared secret.

    The comparison runs in constant time (``hmac.compare_digest``) so the
    response time does not reveal how many leading characters of a guess were
    right. Both sides are compared as UTF-8 bytes: header values may carry
    non-ASCII characters, which ``compare_digest`` refuses for ``str``.
    An unset secret never matches — callers decide separately whether an
    unconfigured deployment is a 503 or a 401.
    """
    if not expected or presented is None:
        return False
    return hmac.compare_digest(presented.encode("utf-8"), expected.encode("utf-8"))


def resolve_under(root: str | os.PathLike[str], candidate: str) -> Path | None:
    """``candidate`` resolved against ``root``, or None when it leaves ``root``.

    A relative ``candidate`` is taken relative to ``root``; an absolute one is
    used as given. Symlinks are resolved on both sides before the check, so a
    link inside the root that points elsewhere does not pass. The root itself
    is not accepted — every caller wants an entry below it.
    """
    try:
        real_root = os.path.realpath(root)
        real = os.path.realpath(os.path.join(real_root, candidate))
    except (TypeError, ValueError, OSError):  # e.g. an embedded NUL byte
        return None
    prefix = real_root if real_root.endswith(os.sep) else real_root + os.sep
    if real.startswith(prefix):
        return Path(real)
    return None
