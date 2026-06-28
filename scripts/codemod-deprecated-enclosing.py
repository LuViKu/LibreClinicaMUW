#!/usr/bin/env python3
"""
2026-06-28 — Suppress JDT's "enclosing type X is deprecated, perhaps this
member should be marked as deprecated, too?" diagnostics by adding
@SuppressWarnings("deprecation") at the class level on every class that
is itself marked @Deprecated.

Rationale: inside a class marked @Deprecated, every member doesn't NEED
to be re-marked individually — that's just noise. Adding the suppression
at the class header acknowledges the deprecation without the per-method
churn.

Idempotent.
"""
import re
import sys
from pathlib import Path


def has_class_level_suppress(lines: list[str], idx: int) -> bool:
    """Walk backwards from the class declaration looking for an existing
    @SuppressWarnings("deprecation") on annotation lines."""
    j = idx - 1
    while j >= 0:
        line = lines[j].strip()
        if line == "":
            j -= 1
            continue
        if line.startswith("@"):
            if "SuppressWarnings" in line and "deprecation" in line:
                return True
            j -= 1
            continue
        if line.startswith("/*") or line.endswith("*/") or line.startswith("*"):
            j -= 1
            continue
        break
    return False


def find_deprecated_classes(text: str) -> list[tuple[int, int]]:
    """Return list of (deprecated_annotation_line_idx, class_decl_line_idx)
    for top-level + nested classes that are @Deprecated.
    """
    lines = text.splitlines()
    results: list[tuple[int, int]] = []
    deprecated_pending: int | None = None
    for i, line in enumerate(lines):
        stripped = line.strip()
        if stripped == "@Deprecated" or stripped.startswith("@Deprecated("):
            deprecated_pending = i
            continue
        if deprecated_pending is not None:
            if stripped.startswith("@") or stripped == "":
                continue
            # First non-annotation line after @Deprecated
            if re.match(r"^(public\s+|private\s+|protected\s+|abstract\s+|final\s+|static\s+|\s)*((class|interface|enum)\s+\w)", stripped):
                results.append((deprecated_pending, i))
            deprecated_pending = None
    return results


def process_file(path: Path) -> int:
    text = path.read_text(encoding="utf-8")
    targets = find_deprecated_classes(text)
    if not targets:
        return 0
    lines = text.splitlines(keepends=True)
    n = 0
    # Process descending so indices stay valid
    for dep_idx, class_idx in sorted(targets, key=lambda t: -t[0]):
        if has_class_level_suppress(lines, class_idx):
            continue
        # Get indentation from the class declaration line
        cls_line = lines[class_idx]
        indent_match = re.match(r"(\s*)", cls_line)
        indent = indent_match.group(1) if indent_match else ""
        annotation = f'{indent}@SuppressWarnings("deprecation")\n'
        lines.insert(dep_idx, annotation)
        n += 1
    if n > 0:
        path.write_text("".join(lines), encoding="utf-8")
    return n


def main():
    if len(sys.argv) != 2:
        print("usage: codemod-deprecated-enclosing.py <root>", file=sys.stderr)
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
    print(f"{total_changes} class-level @SuppressWarnings added in {total_files} files")


if __name__ == "__main__":
    main()
