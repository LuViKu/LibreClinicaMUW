#!/usr/bin/env python3
"""Restore .java files from the .bak siblings left by strip_deprecation_suppressions.py.

Companion to strip_deprecation_suppressions.py. After running the strip + a
Maven compile, the manifest captures fresh deprecation warnings; this restore
puts the @SuppressWarnings annotations back so the DAO surface stays
compile-clean until the actual Hibernate 6 surgery starts.
"""
from __future__ import annotations

import shutil
import sys
from pathlib import Path


def main(argv):
    if len(argv) != 2:
        print(f"usage: {argv[0]} <directory>", file=sys.stderr)
        return 2
    root = Path(argv[1])
    if not root.is_dir():
        print(f"not a directory: {root}", file=sys.stderr)
        return 2
    restored = 0
    for bak in sorted(root.rglob("*.java.bak")):
        target = bak.with_suffix("")  # strip .bak -> .java
        shutil.move(str(bak), str(target))
        restored += 1
        print(f"  restored  {target}")
    print(f"restored {restored} file(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
