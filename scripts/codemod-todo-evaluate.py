#!/usr/bin/env python3
"""
2026-06-28 — Per-TODO evaluation codemod. Deletes specifically-identified
heritage musings + IDE-stub remnants. KEEPS substantive TODOs that point
at real work (security, null-safety, refactor, i18n, Hibernate migration).

For trailing `// TODO` on otherwise-functional code lines (like
`throw new InsufficientPermissionException(...);// TODO`), strips ONLY
the comment — the code is preserved.

Each delete decision is annotated next to the pattern.
"""
import re
import sys
from pathlib import Path


# ─── Patterns that delete the WHOLE LINE ───────────────────────────────
# Each entry: (description, regex matching the full line).
DELETE_WHOLE_LINE = [
    # Javadoc @param with no description (author left "TODO" placeholder)
    ("javadoc @param TODO", re.compile(r"^\s*\*\s*@param\s+\w+\s+TODO\s*$")),
    # Author "tbh" musings — heritage uncertainty markers, no actionable task
    ("tbh musing — jobs unique", re.compile(r"^\s*//\s*TODO job names will have to be unique, tbh\s*$")),
    ("tbh musing — bug 1689", re.compile(r"^\s*//\s*TODO possible relation to \d+ here, tbh\s*$")),
    ("tbh musing — similar refactor", re.compile(r"^\s*//\s*TODO need to refactor since this is similar to other code, tbh\s*$")),
    ("tbh musing — copied", re.compile(r"^\s*//\s*TODO copied from \w+ - DRY\?\s*tbh\s*$")),
    ("tbh musing — placeholder", re.compile(r"^\s*//\s*TODO place holder for returning here, tbh\s*$")),
    ("tbh musing — eliciting crfs", re.compile(r"^\s*//\s*TODO make sure this other statement for eliciting crfs works, tbh\s*$")),
    # Vague question-mark TODOs that the author never decided
    ("vague — pick datasets/date", re.compile(r"^\s*//\s*TODO pick out the datasets and the date\s*$")),
    ("vague — sites then datasets", re.compile(r"^\s*//\s*TODO will have to dress this up to allow for sites then datasets\s*$")),
    ("vague — limit to owner", re.compile(r"^\s*(?:return;)?\s*//\s*TODO limit to owner only\?\s*$")),
    ("vague — owner can edit", re.compile(r"^\s*//\s*TODO add a limit so that the owner can edit, no one else\?\s*$")),
    ("vague — primary keys", re.compile(r"^\s*//\s*TODO figure out the error with current primary keys\?\s*$")),
    ("vague — make dynamic", re.compile(r"^\s*//\s*TODO make dynamic\?\s*$")),
    ("vague — verify order", re.compile(r"^\s*//\s*TODO - verify that the order is the same\s*$")),
    ("vague — could be private", re.compile(r"^\s*//\s*TODO - could be made private and then get/set\s*$")),
    # Author "wondering" musings
    ("wondering — date constraint", re.compile(r"^\s*\*\s*TODO: why date constraint has been hard-coded \?\?\?\s*$")),
    ("wondering — filename one entry", re.compile(r"^\s*\*\s*TODO It looks like the fileName should contain at most one entry that maps a file name \(String\) to\s*$")),
    # Author admits ignorance
    ("author ignorance — comparison", re.compile(r"^\s*\*\s*TODO do not know what this comparison should look like exactly since comparing with the result of getFileName\(\)\s*$")),
    # Trivial Javadoc TODOs
    ("trivial — work on this line", re.compile(r"^\s*//\s*TODO work on this line\s*$")),
    ("trivial — report something useful", re.compile(r"^\s*//\s*TODO: report something useful\s*$")),
]

# ─── Patterns that strip ONLY the trailing TODO (keeping the code) ────
# Each: (description, regex matching the full line with TODO at end).
# Replacement: the captured prefix (without the trailing comment).
STRIP_TRAILING = [
    # `throw ... ;// TODO` — exception-throw lines with trailing TODO marker
    ("trailing TODO on InsufficientPermissionException",
     re.compile(r"^(.*throw new InsufficientPermissionException\(.*\);)\s*//\s*TODO\s*$")),
    # `something; // TODO blah` where the TODO content is just "blah" with no actionable description
    ("trailing TODO on return statement",
     re.compile(r"^(\s*return\s*;\s*)//\s*TODO limit to owner only\?\s*$")),
]


def process_file(path: Path) -> tuple[int, int]:
    lines = path.read_text(encoding="utf-8").splitlines(keepends=True)
    out: list[str] = []
    deleted = 0
    stripped = 0
    for line in lines:
        # Whole-line delete?
        if any(rx.match(line) for _, rx in DELETE_WHOLE_LINE):
            deleted += 1
            continue
        # Trailing-strip?
        new_line = line
        for _, rx in STRIP_TRAILING:
            m = rx.match(line)
            if m:
                new_line = m.group(1).rstrip() + ("\n" if line.endswith("\n") else "")
                stripped += 1
                break
        out.append(new_line)
    if deleted + stripped > 0:
        path.write_text("".join(out), encoding="utf-8")
    return deleted, stripped


def main():
    if len(sys.argv) != 2:
        print("usage: codemod-todo-evaluate.py <root>", file=sys.stderr)
        sys.exit(1)
    root = Path(sys.argv[1])
    total_files = 0
    total_deleted = 0
    total_stripped = 0
    for java_file in root.rglob("*.java"):
        parts = java_file.parts
        if any(p in ("target", "generated-sources", "node_modules", ".m2-cache") for p in parts):
            continue
        d, s = process_file(java_file)
        if d + s > 0:
            total_files += 1
            total_deleted += d
            total_stripped += s
    print(f"deleted={total_deleted} stripped={total_stripped} files={total_files}")


if __name__ == "__main__":
    main()
