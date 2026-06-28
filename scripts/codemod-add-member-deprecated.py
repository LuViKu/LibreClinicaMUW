#!/usr/bin/env python3
"""
2026-06-28 — Driven by the VSCode warnings export. For each
"The enclosing type X is deprecated, perhaps this member should be marked
as deprecated, too?" JDT diagnostic, add `@Deprecated` directly above the
flagged member declaration.

The class-level `@SuppressWarnings("deprecation")` from the audit's
deprecated-enclosing codemod does NOT silence this warning — it's the
JDT-specific `MissingDeprecatedAnnotation` check that wants each member
explicitly marked.

Idempotent — skips lines that already start with `@Deprecated`.
"""
import json
import re
import sys
from collections import defaultdict
from pathlib import Path


def main():
    if len(sys.argv) != 3:
        print("usage: codemod-add-member-deprecated.py <warnings.json> <repo-root>", file=sys.stderr)
        sys.exit(1)
    warnings = json.loads(Path(sys.argv[1]).read_text())
    root = Path(sys.argv[2]).resolve()

    targets: dict[Path, list[int]] = defaultdict(list)
    for diag in warnings:
        msg = diag.get("message", "")
        if not msg.startswith("The enclosing type") or "is deprecated" not in msg:
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
            targets[p.resolve()].append(ln)

    total = 0
    files = 0
    for path, line_numbers in targets.items():
        if not path.exists():
            continue
        lines = path.read_text(encoding="utf-8").splitlines(keepends=True)
        changed = False
        # Process descending so insertions don't shift other indices
        for ln in sorted(set(line_numbers), reverse=True):
            idx = ln - 1
            if idx < 0 or idx >= len(lines):
                continue
            line = lines[idx]
            # Skip if the line itself already starts with @Deprecated or
            # the line above is @Deprecated.
            indent_match = re.match(r"(\s*)", line)
            indent = indent_match.group(1) if indent_match else ""
            prev = lines[idx - 1] if idx > 0 else ""
            if prev.strip() == "@Deprecated" or line.strip().startswith("@Deprecated"):
                continue
            # Insert @Deprecated annotation on its own line above the member.
            lines.insert(idx, f"{indent}@Deprecated\n")
            total += 1
            changed = True
        if changed:
            path.write_text("".join(lines), encoding="utf-8")
            files += 1
    print(f"{total} @Deprecated annotations added in {files} files")


if __name__ == "__main__":
    main()
