#!/usr/bin/env python3
"""
2026-06-28 — Add class-level @SuppressWarnings("deprecation") to entity
files that use @GenericGenerator(strategy = ...). The strategy()
attribute was deprecated in Hibernate 6.5; the proper migration is
per-entity @SequenceGenerator + @GeneratedValue(SEQUENCE) which is too
risky for a mechanical codemod (each entity needs sequence-name + JPA
config review).

This codemod adds the suppression with a rationale comment pointing to
the deferred Phase B.5 follow-up.

Idempotent: skips files that already carry the suppression at the class
level.
"""
import re
import sys
from pathlib import Path


GENERIC_GEN_USE = re.compile(r"@GenericGenerator\s*\(")
CLASS_DECL = re.compile(
    r"^(?P<indent>\s*)(?:public|protected|private|abstract|final|static|\s)*"
    r"(?:class|interface|enum)\s+\w",
    re.MULTILINE,
)
SUPPRESS_AT_LEVEL = re.compile(
    r"^\s*@SuppressWarnings\s*\(\s*[^)]*\bdeprecation\b[^)]*\)\s*$",
    re.MULTILINE,
)


def process_file(path: Path) -> bool:
    text = path.read_text(encoding="utf-8")
    if not GENERIC_GEN_USE.search(text):
        return False
    # Find the first class/interface/enum declaration line.
    m = CLASS_DECL.search(text)
    if not m:
        return False
    decl_start = m.start()
    # Walk backward over annotation lines and JavaDoc lines; check if a
    # class-level @SuppressWarnings("deprecation") already exists.
    before = text[:decl_start]
    if SUPPRESS_AT_LEVEL.search(before[-1000:]):
        return False
    # Insert annotation right before the class decl, preserving indent.
    indent = m.group("indent")
    insertion = (
        f"{indent}// 2026-06-28 — heritage GenericGenerator(strategy=…) survives\n"
        f"{indent}// until each entity gets a proper Hibernate-6.5 @SequenceGenerator\n"
        f"{indent}// migration (deferred B.5 follow-up).\n"
        f"{indent}@SuppressWarnings(\"deprecation\")\n"
    )
    new_text = text[:decl_start] + insertion + text[decl_start:]
    path.write_text(new_text, encoding="utf-8")
    return True


def main():
    if len(sys.argv) != 2:
        print("usage: codemod-suppress-genericgenerator.py <root>", file=sys.stderr)
        sys.exit(1)
    root = Path(sys.argv[1])
    total = 0
    for java_file in root.rglob("*.java"):
        parts = java_file.parts
        if any(p in ("target", "generated-sources", "node_modules", ".m2-cache") for p in parts):
            continue
        if "src/test" in str(java_file):
            continue
        if process_file(java_file):
            total += 1
    print(f"{total} entity files suppressed")


if __name__ == "__main__":
    main()
