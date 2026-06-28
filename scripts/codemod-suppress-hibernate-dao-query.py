#!/usr/bin/env python3
"""
2026-06-28 — Add class-level @SuppressWarnings("deprecation") to DAOs that
use the deprecated Hibernate `Session.createQuery(String)` /
`Session.createNativeQuery(String)` overloads.

The proper migration is per-call typed forms `createQuery(String, Class)` —
attempted earlier as a codemod but the regex misfired on
`CriteriaBuilder.createQuery(CriteriaQuery)` overloads. Per-call typing
requires manual review of each query's expected result type.

Class-level suppression with a rationale comment is the cheapest interim:
the calls still work at runtime; only the JDT-Problems noise is silenced.
Each suppression carries a comment pointing back at this commit so a
future maintainer can re-enable to see what's left after the typed-form
migration.
"""
import re
import sys
from pathlib import Path

CALLS = re.compile(
    r"\.\s*(createQuery|createNativeQuery)\s*\(\s*[^,)]+?\s*\)"
)
HAS_TYPED_FORM = re.compile(r"\.createQuery\s*\([^)]+,\s*\w+\.class\s*\)")

CLASS_DECL = re.compile(
    r"^(?P<indent>\s*)(?:public|protected|private|abstract|final|static|\s)*"
    r"(?:class|interface|enum)\s+\w",
    re.MULTILINE,
)
SUPPRESS_AT_LEVEL = re.compile(
    r"^\s*@SuppressWarnings\s*\(\s*[^)]*\bdeprecation\b[^)]*\)\s*$",
    re.MULTILINE,
)


def needs_suppression(text: str) -> bool:
    """True if the file has at least one untyped createQuery(String)/createNativeQuery(String) call."""
    return bool(CALLS.search(text))


def process_file(path: Path) -> bool:
    text = path.read_text(encoding="utf-8")
    if not needs_suppression(text):
        return False
    m = CLASS_DECL.search(text)
    if not m:
        return False
    decl_start = m.start()
    before = text[:decl_start]
    if SUPPRESS_AT_LEVEL.search(before[-1000:]):
        return False
    indent = m.group("indent")
    insertion = (
        f"{indent}// 2026-06-28 — Session.createQuery(String) / createNativeQuery(String)\n"
        f"{indent}// were deprecated in Hibernate 6.5 in favour of typed overloads. The\n"
        f"{indent}// per-call typed-form migration needs each query's expected result\n"
        f"{indent}// type reviewed manually — deferred B.5 follow-up. Suppression here\n"
        f"{indent}// is intentional and isolated to this DAO.\n"
        f"{indent}@SuppressWarnings(\"deprecation\")\n"
    )
    new_text = text[:decl_start] + insertion + text[decl_start:]
    path.write_text(new_text, encoding="utf-8")
    return True


def main():
    if len(sys.argv) != 2:
        print("usage: codemod-suppress-hibernate-dao-query.py <root>", file=sys.stderr)
        sys.exit(1)
    root = Path(sys.argv[1])
    total = 0
    for java_file in root.rglob("*.java"):
        parts = java_file.parts
        if any(p in ("target", "generated-sources", "node_modules", ".m2-cache") for p in parts):
            continue
        if "src/test" in str(java_file):
            continue
        # Scope to DAO directories
        if "/dao/" not in str(java_file):
            continue
        if process_file(java_file):
            total += 1
    print(f"{total} DAO files suppressed")


if __name__ == "__main__":
    main()
