#!/usr/bin/env python3
"""
2026-06-28 — Replace deprecated `new Locale("…")` literals with the
modern static factory or constant equivalents.

Mapping:
  new Locale("en")          → Locale.ENGLISH
  new Locale("en", "US")    → Locale.US
  new Locale("en_US")       → Locale.US
  new Locale("de")          → Locale.GERMAN
  new Locale("de", "DE")    → Locale.GERMANY
  new Locale("de_DE")       → Locale.GERMANY
  new Locale("fr")          → Locale.FRENCH
  new Locale("fr", "FR")    → Locale.FRANCE
  Any other 2-arg literal:  → Locale.of(...)  (Java 19+ factory)
  Any other 1-arg literal:  → Locale.of(...)  (Java 19+ factory)
  Runtime expressions:      → SKIP (manual)

Only string-literal forms are touched; expression forms are too risky
to mass-rewrite.

Idempotent.
"""
import re
import sys
from pathlib import Path

# Single-string-literal form: new Locale("xxx")
SINGLE_LIT = re.compile(r'(?<![A-Za-z0-9_])new\s+Locale\s*\(\s*"([^"]+)"\s*\)')

# Two-string-literal form: new Locale("lang", "country")
DOUBLE_LIT = re.compile(
    r'(?<![A-Za-z0-9_])new\s+Locale\s*\(\s*"([^"]+)"\s*,\s*"([^"]+)"\s*\)'
)

CONSTANTS = {
    "en": "Locale.ENGLISH",
    "de": "Locale.GERMAN",
    "fr": "Locale.FRENCH",
    "it": "Locale.ITALIAN",
    "ja": "Locale.JAPANESE",
    "ko": "Locale.KOREAN",
    "zh": "Locale.CHINESE",
    ("en", "US"): "Locale.US",
    ("en", "GB"): "Locale.UK",
    ("en", "CA"): "Locale.CANADA",
    ("fr", "FR"): "Locale.FRANCE",
    ("fr", "CA"): "Locale.CANADA_FRENCH",
    ("de", "DE"): "Locale.GERMANY",
    ("it", "IT"): "Locale.ITALY",
    ("ja", "JP"): "Locale.JAPAN",
    ("ko", "KR"): "Locale.KOREA",
    ("zh", "CN"): "Locale.SIMPLIFIED_CHINESE",
    ("zh", "TW"): "Locale.TRADITIONAL_CHINESE",
}


def lookup_single(s: str) -> str:
    if "_" in s:
        parts = s.split("_", 1)
        if len(parts) == 2:
            t = (parts[0], parts[1])
            if t in CONSTANTS:
                return CONSTANTS[t]
            return f'Locale.of("{parts[0]}", "{parts[1]}")'
    if s in CONSTANTS:
        return CONSTANTS[s]
    return f'Locale.of("{s}")'


def lookup_double(lang: str, country: str) -> str:
    t = (lang, country)
    if t in CONSTANTS:
        return CONSTANTS[t]
    return f'Locale.of("{lang}", "{country}")'


def process_file(path: Path) -> int:
    text = path.read_text(encoding="utf-8")
    n = 0

    def repl_single(m: re.Match) -> str:
        nonlocal n
        n += 1
        return lookup_single(m.group(1))

    def repl_double(m: re.Match) -> str:
        nonlocal n
        n += 1
        return lookup_double(m.group(1), m.group(2))

    # Two-arg first so it doesn't get partially matched by the single
    new_text = DOUBLE_LIT.sub(repl_double, text)
    new_text = SINGLE_LIT.sub(repl_single, new_text)
    if n > 0:
        path.write_text(new_text, encoding="utf-8")
    return n


def main():
    if len(sys.argv) != 2:
        print("usage: codemod-locale-literals.py <root>", file=sys.stderr)
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
