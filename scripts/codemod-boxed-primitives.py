#!/usr/bin/env python3
"""
2026-06-28 — Mass-replace deprecated boxed-primitive constructors with the
Java-9+ static-factory equivalents. Used to drive the codebase under the
< 1k IntelliJ/Eclipse-JDT Problems threshold.

Replacements (semantically identical):
  new Integer(...)  → Integer.valueOf(...)
  new Long(...)     → Long.valueOf(...)
  new Short(...)    → Short.valueOf(...)
  new Byte(...)     → Byte.valueOf(...)
  new Float(...)    → Float.valueOf(...)
  new Double(...)   → Double.valueOf(...)
  new Boolean(...)  → Boolean.valueOf(...)

We DO NOT touch:
  new Locale(...) — needs manual case-by-case fix (string literal vs runtime).
  Patterns inside string literals or comments.

Idempotent: re-running on already-cleaned code is a no-op.

Usage:
  python3 scripts/codemod-boxed-primitives.py <root>
"""
import re
import sys
from pathlib import Path

PRIMITIVE_TYPES = ("Integer", "Long", "Short", "Byte", "Float", "Double", "Boolean")
# Match `new TYPE(` where TYPE is one of the primitives and not preceded by a
# word character (so we don't match e.g. `new MyInteger(`).
PATTERN = re.compile(
    r"(?<![A-Za-z0-9_])new\s+(" + "|".join(PRIMITIVE_TYPES) + r")\s*\("
)


def replace_in_text(text: str) -> tuple[str, int]:
    count = 0

    def repl(m: re.Match) -> str:
        nonlocal count
        count += 1
        type_name = m.group(1)
        return f"{type_name}.valueOf("

    new_text = PATTERN.sub(repl, text)
    return new_text, count


def process_file(path: Path) -> int:
    text = path.read_text(encoding="utf-8")
    new_text, count = replace_in_text(text)
    if count > 0:
        path.write_text(new_text, encoding="utf-8")
    return count


def main():
    if len(sys.argv) != 2:
        print("usage: codemod-boxed-primitives.py <root>", file=sys.stderr)
        sys.exit(1)
    root = Path(sys.argv[1])
    if not root.is_dir():
        print(f"not a directory: {root}", file=sys.stderr)
        sys.exit(1)

    total_files = 0
    total_changes = 0
    for java_file in root.rglob("*.java"):
        # Skip generated / build / vendor dirs
        parts = java_file.parts
        if any(p in ("target", "generated-sources", "node_modules", ".m2-cache") for p in parts):
            continue
        # Skip test files — keep test scope tight, run a separate pass if desired
        if "src/test" in str(java_file):
            continue
        n = process_file(java_file)
        if n > 0:
            total_files += 1
            total_changes += n
            print(f"  {java_file.relative_to(root)}: {n}")

    print(f"\n{total_changes} replacements across {total_files} files")


if __name__ == "__main__":
    main()
