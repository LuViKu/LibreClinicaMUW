#!/usr/bin/env python3
"""
2026-06-29 — Address JDT "unused" warnings by actually removing the dead
code rather than suppressing the warning.

Safe transformations:
  1. UNUSED IMPORTS — delete the `import foo.bar.Symbol;` line.
  2. UNUSED LOCAL VARIABLES — JDT says "the VALUE is not used", so the
     assignment can be dropped. BUT the RHS may have side effects (e.g.
     `seedItemData(...)` in tests). Two cases:
       a) RHS is a literal / cheap expression with no side effects (number,
          string, identifier, `null`, simple field access) → DELETE the
          declaration entirely.
       b) RHS appears to be a method call or `new` expression → REPLACE
          `Type ident = rhs;` with `rhs;` so the side effect runs.

  3. UNUSED FIELDS + PRIVATE METHODS — SKIPPED. JDT can't see Jackson /
     JDBC POJO reads through reflection.
"""
import json
import re
import sys
from collections import defaultdict
from pathlib import Path


MAIN_ROOT = "/Users/lukas/LibreClinicaMUW/main/"


def map_path(resource: str, root: Path) -> Path:
    if resource.startswith(MAIN_ROOT):
        return root / resource[len(MAIN_ROOT):]
    return Path(resource)


def process_unused_import(lines: list[str], ln: int, symbol: str) -> bool:
    idx = ln - 1
    if 0 <= idx < len(lines) and "import" in lines[idx] and symbol in lines[idx]:
        del lines[idx]
        return True
    return False


# Cheap RHS expressions — definitely no side effects:
#   numeric literal, string literal, identifier, true/false/null,
#   simple field access (a.b), `new` of a known-pure type … (we conservatively
#   treat `new` as having side effects)
PURE_RHS = re.compile(
    r"^\s*(?:"
    r"-?\d+(?:\.\d+)?[fFlLdD]?"          # number
    r"|\"[^\"]*\""                        # string
    r"|true|false|null"                   # keywords
    r"|[A-Za-z_][A-Za-z0-9_.]*"           # identifier or field access
    r")\s*;?\s*$"
)


def process_unused_local(lines: list[str], ln: int, var_name: str) -> bool:
    idx = ln - 1
    if not (0 <= idx < len(lines)):
        return False
    line = lines[idx]
    # Match `Type ident = expr;`. Type may include generics / arrays.
    m = re.match(
        r"^(?P<indent>\s*)(?:final\s+)?[\w.<>\[\],?\s]+\s+"
        + re.escape(var_name)
        + r"\s*=\s*(?P<rhs>.+?)\s*;\s*$",
        line,
    )
    if not m:
        return False
    rhs = m.group("rhs")
    if PURE_RHS.match(rhs):
        # Safe to drop the line entirely
        del lines[idx]
    else:
        # Keep the side effect — replace declaration with `rhs;`
        lines[idx] = f"{m.group('indent')}{rhs};\n"
    return True


def main():
    if len(sys.argv) != 3:
        print("usage: codemod-delete-unused.py <warnings.json> <repo-root>", file=sys.stderr)
        sys.exit(1)
    warnings = json.loads(Path(sys.argv[1]).read_text())
    root = Path(sys.argv[2]).resolve()

    todo: dict[Path, list[tuple[int, str, str]]] = defaultdict(list)
    for d in warnings:
        msg = d.get("message", "")
        ln = d.get("startLineNumber", 0)
        resource = d.get("resource", "")
        p = map_path(resource, root)
        if not p.exists() or p.is_dir():
            continue
        if m := re.match(r"The import (\S+) is never used", msg):
            todo[p.resolve()].append((ln, "import", m.group(1).rsplit(".", 1)[-1]))
        elif m := re.match(r"The value of the local variable (\w+) is not used", msg):
            todo[p.resolve()].append((ln, "local", m.group(1)))

    total = 0
    files = 0
    for path, items in todo.items():
        if not path.exists():
            continue
        lines = path.read_text(encoding="utf-8").splitlines(keepends=True)
        changed = 0
        for ln, kind, symbol in sorted(items, key=lambda t: -t[0]):
            if kind == "import":
                if process_unused_import(lines, ln, symbol):
                    changed += 1
            elif kind == "local":
                if process_unused_local(lines, ln, symbol):
                    changed += 1
        if changed > 0:
            path.write_text("".join(lines), encoding="utf-8")
            total += changed
            files += 1
    print(f"deleted/rewritten {total} dead-code entries in {files} files")


if __name__ == "__main__":
    main()
