#!/usr/bin/env python3
"""
2026-06-28 — Remove bare `// TODO` and ` * TODO` lines that JDT flags with
message exactly "TODO". These are heritage uninformative markers (no actual
task described). Substantive TODOs ("TODO update to CriteriaQuery", "TODO KK
FIX HERE", "TODO: refactor super class") are preserved by exact-match.

Driven by the VSCode warnings export.
"""
import json
import re
import sys
from collections import defaultdict
from pathlib import Path


def main():
    if len(sys.argv) != 3:
        print("usage: codemod-remove-bare-todos.py <warnings.json> <repo-root>", file=sys.stderr)
        sys.exit(1)
    warnings = json.loads(Path(sys.argv[1]).read_text())
    root = Path(sys.argv[2]).resolve()

    targets: dict[Path, set[int]] = defaultdict(set)
    for diag in warnings:
        if diag.get("message", "") != "TODO":
            continue
        resource = diag.get("resource", "")
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
        if isinstance(ln, int):
            targets[p.resolve()].add(ln)

    BARE_RE = re.compile(r"^\s*(?://|\*)\s*TODO\s*$")
    total = 0
    files = 0
    for path, line_numbers in targets.items():
        if not path.exists():
            continue
        lines = path.read_text(encoding="utf-8").splitlines(keepends=True)
        changed_this = 0
        for ln in sorted(line_numbers, reverse=True):
            idx = ln - 1
            if idx < 0 or idx >= len(lines):
                continue
            if BARE_RE.match(lines[idx]):
                del lines[idx]
                changed_this += 1
        if changed_this > 0:
            path.write_text("".join(lines), encoding="utf-8")
            total += changed_this
            files += 1
    print(f"{total} bare TODO markers removed in {files} files")


if __name__ == "__main__":
    main()
