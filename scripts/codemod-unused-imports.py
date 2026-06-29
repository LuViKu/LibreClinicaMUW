#!/usr/bin/env python3
"""
2026-06-28 — Remove unused imports detected via a simple token-presence check.

For each `import foo.bar.Symbol;` line, scan the rest of the file's TEXT
(excluding the imports block) for `Symbol` as a word boundary. If not
found, the import is unused — delete it.

Caveats — DOES NOT handle:
  - Annotation imports that appear only in comments or annotation literals
    (we're conservative — annotation values often look like `@Deprecated`
    which contains the symbol).
  - Star imports (`import foo.*;`) — left alone.
  - Static imports — handled identically (the symbol after the last `.`
    is what we search for).

Skips test sources.
"""
import re
import sys
from pathlib import Path


IMPORT_RE = re.compile(r"^\s*import\s+(static\s+)?([\w.]+)\s*;\s*$")


def process_file(path: Path) -> int:
    lines = path.read_text(encoding="utf-8").splitlines(keepends=True)
    # Find import region
    imports: list[tuple[int, str]] = []  # (line index, symbol)
    for i, line in enumerate(lines):
        m = IMPORT_RE.match(line)
        if not m:
            continue
        full = m.group(2)
        if full.endswith(".*"):
            continue
        symbol = full.rsplit(".", 1)[-1]
        imports.append((i, symbol))
    if not imports:
        return 0

    # Build the "rest of file" content excluding the import lines themselves
    import_idxs = {idx for idx, _ in imports}
    body = "".join(l for i, l in enumerate(lines) if i not in import_idxs)

    to_remove: set[int] = set()
    for idx, symbol in imports:
        # Word-boundary search
        if not re.search(r"\b" + re.escape(symbol) + r"\b", body):
            to_remove.add(idx)
    if not to_remove:
        return 0
    new_lines = [l for i, l in enumerate(lines) if i not in to_remove]
    path.write_text("".join(new_lines), encoding="utf-8")
    return len(to_remove)


def main():
    if len(sys.argv) != 2:
        print("usage: codemod-unused-imports.py <root>", file=sys.stderr)
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
    print(f"{total} unused imports deleted in {total_files} files")


if __name__ == "__main__":
    main()
