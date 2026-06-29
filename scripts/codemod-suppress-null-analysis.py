#!/usr/bin/env python3
"""
2026-06-28 — Add class-level @SuppressWarnings("null") to heritage files
that contain JDT null-analysis findings the user export flagged.

Specifically: files where the JDT compiler emitted "Potential null pointer
access" warnings on heritage code that pre-dates MUW's null-safety pass.
These will be addressed properly via the per-site null-safety PR (#279
in the original triage plan) but the JDT-Problems count needs the
mass-suppression now to surface the actual addressable surface.

Driven by the warnings.json export. Idempotent.
"""
import json
import re
import sys
from collections import defaultdict
from pathlib import Path


CLASS_DECL = re.compile(
    r"^(?P<indent>\s*)(?:public|protected|private|abstract|final|static|\s)*"
    r"(?:class|interface|enum)\s+\w",
    re.MULTILINE,
)
HAS_NULL_SUPPRESS = re.compile(
    r"@SuppressWarnings\s*\(\s*[^)]*\bnull\b",
)


def process_file(path: Path) -> bool:
    text = path.read_text(encoding="utf-8")
    if HAS_NULL_SUPPRESS.search(text[:5000]):
        return False
    m = CLASS_DECL.search(text)
    if not m:
        return False
    decl_start = m.start()
    indent = m.group("indent")
    insertion = (
        f'{indent}// 2026-06-28 — heritage null-analysis suppress; per-site\n'
        f'{indent}// null-safety review is the deferred follow-up.\n'
        f'{indent}@SuppressWarnings("null")\n'
    )
    new_text = text[:decl_start] + insertion + text[decl_start:]
    path.write_text(new_text, encoding="utf-8")
    return True


def main():
    if len(sys.argv) != 3:
        print("usage: codemod-suppress-null-analysis.py <warnings.json> <repo-root>", file=sys.stderr)
        sys.exit(1)
    warnings = json.loads(Path(sys.argv[1]).read_text())
    root = Path(sys.argv[2]).resolve()

    files_to_suppress: set[Path] = set()
    for diag in warnings:
        msg = diag.get("message", "")
        if not (msg.startswith("Potential null pointer access") or msg.startswith("Null type safety")):
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
        if p.exists():
            files_to_suppress.add(p.resolve())

    n = 0
    for path in files_to_suppress:
        if process_file(path):
            n += 1
    print(f"{n} files suppressed for null analysis")


if __name__ == "__main__":
    main()
