#!/usr/bin/env python3
"""Strip the "deprecation" key from @SuppressWarnings annotations in DAO files.

Mirror of the surgery the Phase B.5 manifest regeneration relies on.
Backs up each modified file alongside the original (.bak) so the script's
companion (regenerate-phase-b5-manifest.sh) can restore them after the build.

Run from the repo root:

    python3 scripts/strip_deprecation_suppressions.py \
        core/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/dao/hibernate

The script touches files in-place and writes <file>.bak siblings.
Idempotent: a second run is a no-op once deprecation tokens are gone.
"""
from __future__ import annotations

import re
import shutil
import sys
from pathlib import Path

# Capture one of these on each line:
#   @SuppressWarnings("deprecation")            -> drop the whole annotation
#   @SuppressWarnings({ "deprecation", "rawtypes" })  -> rewrite without "deprecation"
SINGLE_RE = re.compile(r'^\s*@SuppressWarnings\("deprecation"\)\s*$')
MULTI_RE = re.compile(r'@SuppressWarnings\(\{\s*([^}]+?)\s*\}\)')


def strip_file(path: Path) -> int:
    """Return the number of suppressions stripped in `path`."""
    src = path.read_text()
    out_lines = []
    stripped = 0
    for line in src.splitlines(keepends=True):
        # 1) Whole-line single-key form -> delete the line outright.
        if SINGLE_RE.match(line.rstrip("\n")):
            stripped += 1
            continue
        # 2) Multi-key form -> drop the "deprecation" token.
        m = MULTI_RE.search(line)
        if m:
            keys = [k.strip() for k in m.group(1).split(",")]
            kept = [k for k in keys if k.strip('"') != "deprecation"]
            if len(kept) != len(keys):
                stripped += 1
                if not kept:
                    # Shouldn't happen for our DAO surface, but be safe.
                    continue
                new_annotation = (
                    "@SuppressWarnings({ " + ", ".join(kept) + " })"
                )
                line = line[: m.start()] + new_annotation + line[m.end():]
        out_lines.append(line)

    if stripped:
        backup = path.with_suffix(path.suffix + ".bak")
        shutil.copy2(path, backup)
        path.write_text("".join(out_lines))
    return stripped


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print(__doc__, file=sys.stderr)
        return 2
    root = Path(argv[1])
    if not root.is_dir():
        print(f"not a directory: {root}", file=sys.stderr)
        return 2

    total = 0
    touched = 0
    for java in sorted(root.rglob("*.java")):
        n = strip_file(java)
        if n:
            touched += 1
            total += n
            print(f"  {n:3d}  {java}")
    print(f"stripped {total} deprecation suppression(s) across {touched} file(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
