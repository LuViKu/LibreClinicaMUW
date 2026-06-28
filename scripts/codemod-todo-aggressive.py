#!/usr/bin/env python3
"""
2026-06-28 — Aggressive TODO triage. Keeps a small whitelist of substantive
markers + deletes the rest of the heritage `// TODO` / ` * TODO` comments.

KEEP whitelist (regex match against the line):
  - Security TODOs (URL encoding, permission gating)
  - Null-safety TODOs (handle exception, should-be-NULL, valid? check)
  - i18n / localisation TODOs
  - Concrete refactor markers ("refactor super class")
  - Concrete unimplemented features ("not fully implemented", "support YYYY-")
  - Specific helper-method asks ("Add getRequestURLMinusServletPath")
  - Hibernate-bean migration trackers ("Hibernated", "Pending conversion")

Everything else gets DELETED on the assumption it's heritage author
musing with no actionable target. The KEEP whitelist is intentionally
narrow — the user can re-add a comment in a follow-up PR if a deleted
TODO turns out to have been load-bearing.

Skip block-comment openers (`/* TODO ...` that starts a multi-line `/* */`)
to avoid breaking the comment structure.
"""
import re
import sys
from pathlib import Path


# Keep these — regex against the full original line
KEEP_PATTERNS = [
    re.compile(r"refactor super class to remove dependency", re.IGNORECASE),
    re.compile(r"URL Encoding", re.IGNORECASE),
    re.compile(r"make this sensitive to permissions", re.IGNORECASE),
    re.compile(r"handle exception", re.IGNORECASE),
    re.compile(r"i18n|internationali[sz]ation|localised name", re.IGNORECASE),
    re.compile(r"not fully implemented", re.IGNORECASE),
    re.compile(r"Add getRequestURLMinusServletPath", re.IGNORECASE),
    re.compile(r"should always be different than NULL", re.IGNORECASE),
    re.compile(r"phase out the use of these Once the above beans become Hibernated", re.IGNORECASE),
    re.compile(r"Pending conversion of the objects below to use Hibernate", re.IGNORECASE),
    re.compile(r"check Null Value logic", re.IGNORECASE),
    re.compile(r"this method not fully", re.IGNORECASE),
    re.compile(r"support YYYY-MM-DD", re.IGNORECASE),
    re.compile(r"eventcrfBean is not valid", re.IGNORECASE),
    # MUW 2026-* author annotations — never delete
    re.compile(r"2026-\d{2}-\d{2}"),
]

# Line-level TODO markers (single-line) — delete unless whitelisted
TODO_LINE = re.compile(r"^\s*//\s*TODO\b")
TODO_JAVADOC = re.compile(r"^\s*\*\s*TODO\b")
# Block-comment opener `/* TODO ...` — these may span multiple lines;
# we leave alone to avoid breaking comment structure.
TODO_BLOCK_OPEN = re.compile(r"^\s*/\*+\s*TODO\b")


def should_delete(line: str) -> bool:
    if TODO_BLOCK_OPEN.match(line):
        return False
    if not (TODO_LINE.match(line) or TODO_JAVADOC.match(line)):
        return False
    for keep in KEEP_PATTERNS:
        if keep.search(line):
            return False
    return True


def process_file(path: Path) -> int:
    lines = path.read_text(encoding="utf-8").splitlines(keepends=True)
    out = [l for l in lines if not should_delete(l)]
    n = len(lines) - len(out)
    if n > 0:
        path.write_text("".join(out), encoding="utf-8")
    return n


def main():
    if len(sys.argv) != 2:
        print("usage: codemod-todo-aggressive.py <root>", file=sys.stderr)
        sys.exit(1)
    root = Path(sys.argv[1])
    total_files = 0
    total = 0
    for java_file in root.rglob("*.java"):
        parts = java_file.parts
        if any(p in ("target", "generated-sources", "node_modules", ".m2-cache") for p in parts):
            continue
        if "src/test" in str(java_file):
            continue
        n = process_file(java_file)
        if n > 0:
            total_files += 1
            total += n
    print(f"{total} TODO lines deleted in {total_files} files")


if __name__ == "__main__":
    main()
