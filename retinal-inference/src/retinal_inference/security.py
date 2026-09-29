"""Request-hardening helpers shared by the sidecar's HTTP routes.

Kept in one place so every route applies them the same way.
"""

from __future__ import annotations

import hmac


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

