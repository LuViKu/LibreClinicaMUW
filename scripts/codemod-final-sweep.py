#!/usr/bin/env python3
"""
2026-06-29 — Final-sweep codemod driven by the live warnings export.

For every file with a remaining JDT-Problems entry:
  - If the file is in a MUW-authored path → keep under JDT scrutiny
  - Otherwise: upgrade any existing class-level @SuppressWarnings(...) to
    @SuppressWarnings("all"); inject one if absent.

Also removes the 16 "Unnecessary @SuppressWarnings('deprecation')" entries
flagged by JDT — these are stale `("deprecation")` markers left over
when a higher-level annotation already covers them.

Idempotent.
"""
import json
import re
import sys
from pathlib import Path


MUW_PATHS = [
    "/service/retinal/",
    "/controller/api/",
    "/studyModules/",
    "/security/sso/",
    "/study/sso/",
]

CLASS_DECL = re.compile(
    r"^(?P<indent>\s*)(?:public|protected|private|abstract|final|static|\s)*"
    r"(?:class|interface|enum|@interface)\s+\w",
    re.MULTILINE,
)
SUPPRESS_AT_CLASS = re.compile(
    r'@SuppressWarnings\(\s*(\"[^\"]+\"|\{[^}]+\})\s*\)'
)


def is_muw_path(path: Path) -> bool:
    s = str(path)
    return any(hint in s for hint in MUW_PATHS)


def upgrade_to_all(path: Path) -> bool:
    text = path.read_text(encoding="utf-8")
    if '@SuppressWarnings("all")' in text[:8000]:
        return False
    m_cls = CLASS_DECL.search(text)
    if not m_cls:
        return False
    head = text[:m_cls.start()]
    m_sup = SUPPRESS_AT_CLASS.search(head[-600:])
    if m_sup:
        # Replace existing with "all"
        replace_at = m_cls.start() - (len(head[-600:]) - m_sup.start())
        replace_end = m_cls.start() - (len(head[-600:]) - m_sup.end())
        new = text[:replace_at] + '@SuppressWarnings("all")' + text[replace_end:]
    else:
        ins = f'{m_cls.group("indent")}@SuppressWarnings("all")\n'
        new = text[:m_cls.start()] + ins + text[m_cls.start():]
    path.write_text(new, encoding="utf-8")
    return True


def remove_unnecessary_suppress_at_line(path: Path, line_no: int, key: str) -> bool:
    """JDT pointed at a specific line saying 'Unnecessary @SuppressWarnings(key)'.
    Strip just that key from the annotation at that line.
    """
    lines = path.read_text(encoding="utf-8").splitlines(keepends=True)
    idx = line_no - 1
    if idx < 0 or idx >= len(lines):
        return False
    line = lines[idx]
    if "@SuppressWarnings" not in line:
        return False
    new_line = re.sub(
        rf'@SuppressWarnings\(\s*"{re.escape(key)}"\s*\)',
        '',
        line,
    )
    # Multi-key form — drop the key
    m_multi = re.search(r'@SuppressWarnings\s*\(\s*\{([^}]+)\}\s*\)', new_line)
    if m_multi:
        keys = [k.strip().strip('"') for k in m_multi.group(1).split(",")]
        keys = [k for k in keys if k != key]
        if len(keys) == 0:
            new_line = re.sub(r'@SuppressWarnings\s*\(\s*\{[^}]+\}\s*\)', '', new_line)
        elif len(keys) == 1:
            new_line = re.sub(
                r'@SuppressWarnings\s*\(\s*\{[^}]+\}\s*\)',
                f'@SuppressWarnings("{keys[0]}")',
                new_line,
            )
        else:
            keys_str = ", ".join(f'"{k}"' for k in keys)
            new_line = re.sub(
                r'@SuppressWarnings\s*\(\s*\{[^}]+\}\s*\)',
                f'@SuppressWarnings({{{keys_str}}})',
                new_line,
            )
    if new_line == line:
        return False
    if new_line.strip() == "":
        del lines[idx]
    else:
        lines[idx] = new_line if new_line.endswith("\n") else new_line + "\n"
    path.write_text("".join(lines), encoding="utf-8")
    return True


def main():
    if len(sys.argv) != 3:
        print("usage: codemod-final-sweep.py <warnings.json> <repo-root>", file=sys.stderr)
        sys.exit(1)
    warnings = json.loads(Path(sys.argv[1]).read_text())
    root = Path(sys.argv[2]).resolve()

    files_to_upgrade: set[Path] = set()
    unnecessary_suppress: list[tuple[Path, int, str]] = []
    MAIN_ROOT = "/Users/lukas/LibreClinicaMUW/main/"

    for d in warnings:
        msg = d.get("message", "")
        resource = d.get("resource", "")
        if resource.startswith(MAIN_ROOT):
            p = root / resource[len(MAIN_ROOT):]
        else:
            p = Path(resource)
        if not p.exists() or p.is_dir():
            continue
        if msg.startswith('Unnecessary @SuppressWarnings'):
            m = re.search(r'"([^"]+)"', msg)
            if m:
                unnecessary_suppress.append((p.resolve(), d.get("startLineNumber", 0), m.group(1)))
            continue
        if is_muw_path(p):
            continue
        files_to_upgrade.add(p.resolve())

    upgrades = 0
    for path in files_to_upgrade:
        if upgrade_to_all(path):
            upgrades += 1

    removed = 0
    for path, ln, key in unnecessary_suppress:
        if remove_unnecessary_suppress_at_line(path, ln, key):
            removed += 1

    print(f"upgraded {upgrades} files to @SuppressWarnings(\"all\")")
    print(f"removed {removed} unnecessary @SuppressWarnings entries")


if __name__ == "__main__":
    main()
