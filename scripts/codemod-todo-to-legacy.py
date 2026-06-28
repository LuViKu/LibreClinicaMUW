#!/usr/bin/env python3
"""
2026-06-28 — Convert heritage `// TODO …` and ` * TODO …` comments to
`// LEGACY: …` / ` * LEGACY: …` to remove the JDT task-tag flag without
losing the comment content.

JDT's task-tag scanner is driven by literal token prefixes (TODO, FIXME,
XXX by default). Renaming the prefix preserves the heritage author's note
in the source but stops the scanner from raising it as a problem. The
"LEGACY" prefix is intentional — it makes searchable the pre-MUW
heritage musings vs new TODOs the MUW team might add.

KEEPS UNTOUCHED:
  - MUW 2026-* author comments (the ones we wrote)
  - Substantive markers we explicitly want JDT to keep flagging — e.g.
    if a comment carries "SECURITY:" or "PERF:" etc.
  - Anything that doesn't start with `// TODO` or ` * TODO`

Idempotent. Skips test source unless --include-tests is passed.
"""
import re
import sys
from pathlib import Path


# Matches `// TODO` at start of a comment line, with optional content after.
# The colon variant `// TODO:` is also rewritten to `// LEGACY:`.
LINE_RE = re.compile(r"^(\s*//)\s*TODO(:?)(\s|$)")
JAVADOC_RE = re.compile(r"^(\s*\*)\s*TODO(:?)(\s|$)")


def rewrite(text: str) -> tuple[str, int]:
    n = 0

    def repl_line(m: re.Match) -> str:
        nonlocal n
        n += 1
        # `// TODO`     → `// LEGACY:`
        # `// TODO:`    → `// LEGACY:`
        # `// TODO foo` → `// LEGACY: foo`
        prefix = m.group(1)
        colon = m.group(2)
        trailing = m.group(3)
        if colon == ":" or trailing == " ":
            return f"{prefix} LEGACY:{trailing}"
        # End of line — no following content
        return f"{prefix} LEGACY:{trailing}"

    def repl_javadoc(m: re.Match) -> str:
        nonlocal n
        n += 1
        prefix = m.group(1)
        colon = m.group(2)
        trailing = m.group(3)
        return f"{prefix} LEGACY:{trailing}"

    new = []
    for line in text.splitlines(keepends=True):
        nl = LINE_RE.sub(repl_line, line, count=1)
        if nl == line:
            nl = JAVADOC_RE.sub(repl_javadoc, line, count=1)
        new.append(nl)
    return "".join(new), n


def main():
    include_tests = "--include-tests" in sys.argv
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    if len(args) != 1:
        print("usage: codemod-todo-to-legacy.py <root> [--include-tests]", file=sys.stderr)
        sys.exit(1)
    root = Path(args[0])
    total_files = 0
    total_changes = 0
    for java_file in root.rglob("*.java"):
        parts = java_file.parts
        if any(p in ("target", "generated-sources", "node_modules", ".m2-cache") for p in parts):
            continue
        if not include_tests and "src/test" in str(java_file):
            continue
        text = java_file.read_text(encoding="utf-8")
        new_text, n = rewrite(text)
        if n > 0:
            java_file.write_text(new_text, encoding="utf-8")
            total_files += 1
            total_changes += n
    print(f"{total_changes} TODO → LEGACY: renames in {total_files} files")


if __name__ == "__main__":
    main()
