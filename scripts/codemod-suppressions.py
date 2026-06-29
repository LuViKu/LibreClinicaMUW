#!/usr/bin/env python3
"""
2026-06-28 — Remove Eclipse-JDT-flagged unnecessary @SuppressWarnings.

Driven by /Users/lukas/Downloads/warnings — the live VSCode Java diagnostics
export. We process exactly the lines JDT has marked as
  "Unnecessary @SuppressWarnings(...)"
nothing more, nothing less.

For each flagged line, we either:
  - Remove the @SuppressWarnings entirely if it was the only annotation on the line.
  - Drop just the offending key from a multi-key @SuppressWarnings({...}).

Idempotent + safe — runs only against the JDT-supplied list.

Usage:
  python3 scripts/codemod-suppressions.py <warnings.json> <repo-root>
"""
import json
import re
import sys
from collections import defaultdict
from pathlib import Path


SUPPRESS_RE = re.compile(
    r"@SuppressWarnings\s*\(\s*(?:\"(?P<single>[^\"]+)\"|\{\s*(?P<multi>[^}]+?)\s*\})\s*\)"
)


def parse_target_key(message: str) -> str | None:
    """Extract the single suppression key from JDT's message:
    'Unnecessary @SuppressWarnings("deprecation")' → 'deprecation'.
    """
    m = re.search(r'"([^"]+)"', message)
    return m.group(1) if m else None


def fix_line(line: str, target_key: str) -> tuple[str, bool]:
    """Return (new_line, changed)."""
    m = SUPPRESS_RE.search(line)
    if not m:
        return line, False

    if m.group("single") is not None:
        if m.group("single") != target_key:
            return line, False
        # Remove the entire annotation. If it was the only thing on the
        # line (just whitespace + annotation), drop the line entirely
        # (return empty string sentinel).
        new = SUPPRESS_RE.sub("", line)
        if new.strip() == "":
            return "", True
        return new, True

    # Multi-key form: @SuppressWarnings({"a", "b", ...})
    raw_keys = [k.strip().strip('"') for k in m.group("multi").split(",")]
    if target_key not in raw_keys:
        return line, False
    remaining = [k for k in raw_keys if k != target_key]
    if len(remaining) == 0:
        new = SUPPRESS_RE.sub("", line)
        if new.strip() == "":
            return "", True
        return new, True
    elif len(remaining) == 1:
        replacement = f'@SuppressWarnings("{remaining[0]}")'
    else:
        keys_str = ", ".join(f'"{k}"' for k in remaining)
        replacement = f"@SuppressWarnings({{{keys_str}}})"
    return SUPPRESS_RE.sub(replacement, line), True


def main():
    if len(sys.argv) != 3:
        print("usage: codemod-suppressions.py <warnings.json> <repo-root>", file=sys.stderr)
        sys.exit(1)
    warnings = json.loads(Path(sys.argv[1]).read_text())
    root = Path(sys.argv[2]).resolve()

    # Bucket by file → list of (line_number_1_based, key_to_drop)
    todo: dict[Path, list[tuple[int, str]]] = defaultdict(list)
    for diag in warnings:
        msg = diag.get("message", "")
        if not msg.startswith("Unnecessary @SuppressWarnings"):
            continue
        key = parse_target_key(msg)
        if not key:
            continue
        resource = diag.get("resource", "")
        # The diagnostics file lives in /main; this codemod runs in a sibling
        # worktree. Translate the project-anchor prefix to wherever this
        # script is operating.
        SOURCE_ROOT_HINT = "/Users/lukas/LibreClinicaMUW/main/"
        if resource.startswith(SOURCE_ROOT_HINT):
            resource = str(root) + "/" + resource[len(SOURCE_ROOT_HINT):]
        p = Path(resource)
        if not p.is_absolute():
            p = root / p
        try:
            p.resolve().relative_to(root)
        except ValueError:
            continue
        ln = diag.get("startLineNumber")
        if not isinstance(ln, int):
            continue
        todo[p.resolve()].append((ln, key))

    total_changes = 0
    total_lines_removed = 0
    for path, items in todo.items():
        if not path.exists():
            continue
        lines = path.read_text(encoding="utf-8").splitlines(keepends=True)
        changed = False
        # Process by descending line number so deletions don't shift indices
        for ln, key in sorted(items, key=lambda x: -x[0]):
            idx = ln - 1
            if idx < 0 or idx >= len(lines):
                continue
            new_line, did = fix_line(lines[idx], key)
            if did:
                if new_line == "":
                    lines[idx:idx + 1] = []
                    total_lines_removed += 1
                else:
                    lines[idx] = new_line if new_line.endswith("\n") else new_line + "\n"
                total_changes += 1
                changed = True
        if changed:
            path.write_text("".join(lines), encoding="utf-8")
    print(f"{total_changes} suppressions cleaned (line-deletes: {total_lines_removed}) in {sum(1 for items in todo.values() if items)} files")


if __name__ == "__main__":
    main()
