#!/usr/bin/env python3
"""
2026-06-28 — Migrate deprecated Hibernate Query API calls to the modern equivalents.

  query.list()         → query.getResultList()
  query.uniqueResult() → query.getSingleResultOrNull()

Both are direct one-to-one replacements per Hibernate 6 docs (the deprecation
removes the old names but the semantics are preserved).

Skip cases:
  - Identifiers that don't look like Hibernate Query handles (e.g. ArrayList.list)
    We catch this by requiring the receiver name to contain "query" or "q" or
    be one of the conventional names. Conservative scope.
"""
import re
import sys
from pathlib import Path

# Receiver pattern: an identifier that likely names a Hibernate Query.
# Common names: query, q, q1, namedQuery, nativeQuery, hqlQuery, sqlQuery, qry, criteria
RECEIVER_RE = (
    r"(?:query|Query|q|q[0-9]+|namedQuery|nativeQuery|hqlQuery|sqlQuery|qry"
    r"|criteria|crit|hbmQuery|tq|nq|sq)"
)

# .list() — preceded by a Query-ish receiver; followed by () with no args.
LIST_RE = re.compile(r"(\b" + RECEIVER_RE + r")\s*\.\s*list\s*\(\s*\)")

# .uniqueResult() — same shape.
UNIQUE_RE = re.compile(r"(\b" + RECEIVER_RE + r")\s*\.\s*uniqueResult\s*\(\s*\)")


def process_file(path: Path) -> int:
    text = path.read_text(encoding="utf-8")
    n = 0

    def repl_list(m: re.Match) -> str:
        nonlocal n
        n += 1
        return f"{m.group(1)}.getResultList()"

    def repl_unique(m: re.Match) -> str:
        nonlocal n
        n += 1
        return f"{m.group(1)}.getSingleResultOrNull()"

    new_text = LIST_RE.sub(repl_list, text)
    new_text = UNIQUE_RE.sub(repl_unique, new_text)
    if n > 0:
        path.write_text(new_text, encoding="utf-8")
    return n


def main():
    if len(sys.argv) != 2:
        print("usage: codemod-hibernate-query-methods.py <root>", file=sys.stderr)
        sys.exit(1)
    root = Path(sys.argv[1])
    total_files = 0
    total_changes = 0
    for java_file in root.rglob("*.java"):
        parts = java_file.parts
        if any(p in ("target", "generated-sources", "node_modules", ".m2-cache") for p in parts):
            continue
        if "src/test" in str(java_file):
            continue
        # Hibernate calls live in DAOs; restrict to be safe.
        if "/dao/" not in str(java_file) and "/repository/" not in str(java_file):
            continue
        n = process_file(java_file)
        if n > 0:
            total_files += 1
            total_changes += n
    print(f"{total_changes} replacements across {total_files} files")


if __name__ == "__main__":
    main()
