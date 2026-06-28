#!/usr/bin/env python3
"""
2026-06-28 — Remove auto-generated TODO comment templates that the Eclipse
JDT flags as "TODO" problems but have zero engineering value.

Targets (exact lines, idempotent):
  // TODO Auto-generated method stub
  // TODO Auto-generated catch block
  // TODO To change the template for this generated type comment go to Window -
    (followed by 2 continuation lines)
  // TODO - what value default
  // TODO: NOT IMPLEMENTED

We KEEP substantive TODOs (`// TODO update to CriteriaQuery`, `// TODO KK FIX HERE`)
— those are real work items. Removed entries are heritage IDE stub-generator
artifacts that no LibreClinica author ever cleaned up.

Usage:
  python3 scripts/codemod-todo-stubs.py <root>
"""
import re
import sys
from pathlib import Path

PATTERNS_LINE = [
    re.compile(r"^\s*//\s*TODO Auto-generated method stub\s*$"),
    re.compile(r"^\s*//\s*TODO Auto-generated catch block\s*$"),
    re.compile(r"^\s*//\s*TODO\s*-\s*what value default\s*$"),
    re.compile(r"^\s*//\s*TODO:\s*NOT IMPLEMENTED\s*$"),
]

# The "change template" comment spans 3 lines:
#   // TODO To change the template for this generated type comment go to Window -
#   // Preferences - Java - Code Style - Code Templates
TEMPLATE_PATTERN_START = re.compile(
    r"^\s*//\s*TODO To change the template for this generated type comment go to"
)
TEMPLATE_PATTERN_NEXT = re.compile(r"^\s*//\s*Preferences\s*-\s*Java")


def clean_text(text: str) -> tuple[str, int]:
    lines = text.splitlines(keepends=True)
    kept: list[str] = []
    removed = 0
    i = 0
    while i < len(lines):
        line = lines[i]
        if any(p.match(line) for p in PATTERNS_LINE):
            removed += 1
            i += 1
            continue
        if TEMPLATE_PATTERN_START.match(line):
            removed += 1
            i += 1
            # Skip the continuation lines
            while i < len(lines) and (
                TEMPLATE_PATTERN_NEXT.match(lines[i])
                or re.match(r"^\s*//\s*Window\s*-\s*Preferences", lines[i])
            ):
                removed += 1
                i += 1
            continue
        kept.append(line)
        i += 1
    return "".join(kept), removed


def process_file(path: Path) -> int:
    text = path.read_text(encoding="utf-8")
    new_text, count = clean_text(text)
    if count > 0:
        path.write_text(new_text, encoding="utf-8")
    return count


def main():
    if len(sys.argv) != 2:
        print("usage: codemod-todo-stubs.py <root>", file=sys.stderr)
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
        n = process_file(java_file)
        if n > 0:
            total_files += 1
            total_changes += n
    print(f"{total_changes} stubs removed across {total_files} files")


if __name__ == "__main__":
    main()
