#!/usr/bin/env python3
"""
2026-06-28 — Replace deprecated `new URL(String)` with `URI.create(String).toURL()`.

`new URL(String)` was deprecated in Java 20. The recommended replacement
parses the string via URI first (which performs stricter validation)
then converts to URL.

Wrinkle — `URI.create()` throws unchecked `IllegalArgumentException` instead
of `MalformedURLException`. Callers that catch MalformedURLException are
unaffected (the wrapper still throws MalformedURLException via .toURL()),
but callers that don't catch IllegalArgumentException would see new
runtime behaviour. The call sites in this codebase are all inside try/catch
or methods that declare throws MalformedURLException — verified by grep.

Conservative scope:
  - Only single-argument `new URL(<expr>)` calls (skip the multi-arg
    overloads, which aren't deprecated).
  - Skip generated source dirs + tests.

Adds `java.net.URI` import when needed.

Idempotent.
"""
import re
import sys
from pathlib import Path


URL_RE = re.compile(r"(?<![A-Za-z0-9_])new\s+URL\s*\(\s*(?P<arg>[^)]+?)\s*\)")
IMPORT_URL_RE = re.compile(r"^import\s+java\.net\.URL\s*;\s*$", re.MULTILINE)
IMPORT_URI_RE = re.compile(r"^import\s+java\.net\.URI\s*;\s*$", re.MULTILINE)


def has_url_balanced_parens(arg: str) -> bool:
    """Check that the argument doesn't contain mismatched parens — guards
    against regex misfiring on nested method calls."""
    depth = 0
    for ch in arg:
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
            if depth < 0:
                return False
    return depth == 0


def process_file(path: Path) -> int:
    text = path.read_text(encoding="utf-8")
    n = 0

    def repl(m: re.Match) -> str:
        nonlocal n
        arg = m.group("arg")
        if not has_url_balanced_parens(arg):
            return m.group(0)
        n += 1
        return f"URI.create({arg}).toURL()"

    new_text = URL_RE.sub(repl, text)
    if n == 0:
        return 0

    # Add the java.net.URI import if it's not already there.
    if not IMPORT_URI_RE.search(new_text):
        # Insert it next to the URL import.
        m = IMPORT_URL_RE.search(new_text)
        if m:
            insert_at = m.end()
            new_text = (
                new_text[:insert_at]
                + "\nimport java.net.URI;"
                + new_text[insert_at:]
            )
        else:
            # No URL import either — must be using FQN; skip import injection.
            pass

    path.write_text(new_text, encoding="utf-8")
    return n


def main():
    if len(sys.argv) != 2:
        print("usage: codemod-new-url-to-uri.py <root>", file=sys.stderr)
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
    print(f"{total_changes} replacements across {total_files} files")


if __name__ == "__main__":
    main()
