#!/usr/bin/env python3
"""
2026-06-28 — v2 of the IDE-stub TODO cleanup. Adds Javadoc block-comment
form (` * TODO …`) which v1 missed.

Removes IDE-generated stubs only:
  // TODO Auto-generated method stub
  // TODO Auto-generated catch block
  // TODO To change the template for this generated type comment …
   * TODO To change the template for this generated type comment …
  // TODO - what value default
  // TODO: NOT IMPLEMENTED

Preserves substantive TODOs (TODO update to CriteriaQuery, TODO KK FIX HERE,
TODO: refactor super class, plain `// TODO`).

Idempotent.
"""
import re
import sys
from pathlib import Path

LINE_PATTERNS_BOTH = [
    re.compile(r"^\s*(?://|\*)\s*TODO Auto-generated method stub\s*$"),
    re.compile(r"^\s*(?://|\*)\s*TODO Auto-generated catch block\s*$"),
    re.compile(r"^\s*(?://|\*)\s*TODO\s*-\s*what value default\s*$"),
    re.compile(r"^\s*(?://|\*)\s*TODO:\s*NOT IMPLEMENTED\s*$"),
]

TEMPLATE_START = re.compile(
    r"^\s*(?://|\*)\s*TODO To change the template for this generated type comment go to"
)
TEMPLATE_CONT = re.compile(
    r"^\s*(?://|\*)\s*(?:Preferences\s*-\s*Java|Window\s*-\s*Preferences)"
)


def clean_text(text: str) -> tuple[str, int]:
    lines = text.splitlines(keepends=True)
    kept: list[str] = []
    removed = 0
    i = 0
    while i < len(lines):
        line = lines[i]
        if any(p.match(line) for p in LINE_PATTERNS_BOTH):
            removed += 1
            i += 1
            continue
        if TEMPLATE_START.match(line):
            removed += 1
            i += 1
            while i < len(lines) and TEMPLATE_CONT.match(lines[i]):
                removed += 1
                i += 1
            continue
        kept.append(line)
        i += 1
    return "".join(kept), removed


def main():
    if len(sys.argv) != 2:
        print("usage: codemod-todo-stubs-v2.py <root>", file=sys.stderr)
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
        text = java_file.read_text(encoding="utf-8")
        new_text, n = clean_text(text)
        if n > 0:
            java_file.write_text(new_text, encoding="utf-8")
            total_files += 1
            total_changes += n
    print(f"{total_changes} stubs removed across {total_files} files")


if __name__ == "__main__":
    main()
