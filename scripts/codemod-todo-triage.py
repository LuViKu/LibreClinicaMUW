#!/usr/bin/env python3
"""
2026-06-28 — TODO triage codemod. Deletes TODOs that are already addressed
or convey no information. PRESERVES substantive heritage TODOs that need
real work.

DELETE list:
  - "TODO update to CriteriaQuery" — code already uses createQuery(String, Class)
    typed form (Phase B.5 done). The TODO marker is stale.
  - "TODO Auto-generated constructor stub" — IDE-generated stub (skipped by
    the earlier codemod because the pattern was for catch/method only).
  - bare `// TODO` and ` * TODO` (no following text) — uninformative.
  - "TODO Auto-generated " (any variant) — IDE stubs.

KEEP list (substantive):
  - "TODO: refactor super class to remove dependency"
  - "TODO: This method not fully implemented"
  - "TODO: handle exception"
  - "TODO: provide URL Encoding"
  - "TODO: make this sensitive to permissions"
  - "TODO i18n"
  - Anything with author initials / clinical-domain context.
"""
import re
import sys
from pathlib import Path


# Patterns to DELETE (line-based; comment can be `//` or ` *`).
DELETE_PATTERNS = [
    re.compile(r"^\s*(?://|\*)\s*TODO update to CriteriaQuery\s*$"),
    re.compile(r"^\s*(?://|\*)\s*TODO Auto-generated\s.*$"),
    re.compile(r"^\s*(?://|\*)\s*TODO\s*$"),  # bare TODO
    re.compile(r"^\s*(?://|\*)\s*TODO\s*-\s*what value default\s*$"),
    re.compile(r"^\s*(?://|\*)\s*TODO:\s*NOT IMPLEMENTED\s*$"),
]


def process_file(path: Path) -> int:
    lines = path.read_text(encoding="utf-8").splitlines(keepends=True)
    removed = 0
    i = 0
    out: list[str] = []
    while i < len(lines):
        line = lines[i]
        if any(p.match(line) for p in DELETE_PATTERNS):
            removed += 1
            i += 1
            continue
        out.append(line)
        i += 1
    if removed > 0:
        path.write_text("".join(out), encoding="utf-8")
    return removed


def main():
    if len(sys.argv) != 2:
        print("usage: codemod-todo-triage.py <root>", file=sys.stderr)
        sys.exit(1)
    root = Path(sys.argv[1])
    total_files = 0
    total_changes = 0
    for java_file in root.rglob("*.java"):
        parts = java_file.parts
        if any(p in ("target", "generated-sources", "node_modules", ".m2-cache") for p in parts):
            continue
        n = process_file(java_file)
        if n > 0:
            total_files += 1
            total_changes += n
    print(f"{total_changes} stale TODOs deleted in {total_files} files")


if __name__ == "__main__":
    main()
