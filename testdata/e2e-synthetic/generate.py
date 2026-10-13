"""Regenerate the synthetic .E2E fixtures and their expected describe output.

    pip install -e muw-e2e-converter[dev]      # oct-converter 0.7.0, numpy, pillow
    python testdata/e2e-synthetic/generate.py

Writes ``<name>.e2e`` (built by muw-e2e-converter/tests/e2e_synth.py) and ``<name>.describe.json``
(the real ``describe_e2e(file)`` output, no expected label) next to this script. Everything is
synthetic: constant pixel values, made-up labels, no real data. The Python parity test and the SPA
parity test both read these files, so regenerate only when the fixtures are meant to change.
"""

from __future__ import annotations

import json
import sys
from datetime import datetime
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parents[1] / "muw-e2e-converter" / "tests"))

from e2e_synth import Img, Series, build_e2e  # noqa: E402

from muw_e2e_converter import describe_e2e  # noqa: E402

T = datetime


def bscans(n: int, h: int = 6, w: int = 10, base: int = 0) -> list[Img]:
    return [Img(1, 2 * i, height=h, width=w, fill=base + i) for i in range(n)]


FIXTURES: dict[str, dict] = {
    # OD OCT volume with its IR localizer; surname is a plain label token.
    "oct-volume-localizer": dict(
        surname="SYNTH001",
        series=[
            Series((7, 1, 1), "OCT ART Volume", "IR", "R", T(2026, 9, 30, 10, 0, 0),
                   bscans(5) + [Img(0, 0, height=8, width=8, fill=120)]),
        ],
    ),
    # OCT line scans of both eyes; no patient chunk at all (no header label).
    "oct-line-scan": dict(
        surname=None,
        series=[
            Series((7, 1, 1), "OCT B-Scan", "IR", "L", T(2026, 9, 30, 10, 5, 0),
                   [Img(1, 0, height=6, width=10, fill=9), Img(0, 0, height=8, width=8, fill=60)]),
            Series((7, 1, 2), "OCT B-Scan", "IR", "R", T(2026, 9, 30, 10, 3, 30),
                   [Img(1, 0, height=6, width=10, fill=3)]),
        ],
    ),
    # Several en-face series with several images each; surname is not a label token.
    "enface-multi-image": dict(
        surname="Mustermann, Max",
        series=[
            Series((7, 1, 1), "Images", "BAF", "R", T(2026, 9, 30, 11, 0, 0),
                   [Img(0, i, height=8, width=8, fill=40 + i) for i in range(3)]),
            Series((7, 1, 2), "Images", "IRAF", "R", T(2026, 9, 30, 11, 5, 0),
                   [Img(0, i, height=8, width=8, fill=80 + i) for i in range(2)]),
            Series((7, 1, 3), "Images", "FA", "R", T(2026, 9, 30, 11, 20, 0),
                   [Img(0, 0, height=8, width=8, fill=200)]),
            Series((7, 1, 4), "Images", "BR", "L", T(2026, 9, 30, 11, 30, 0),
                   [Img(0, 0, height=8, width=8, fill=30)]),
        ],
    ),
    # Time sequences; two patient chunks that agree on the surname (still a token).
    "time-sequence": dict(
        surname="SYNTH004",
        more_surnames=("SYNTH004",),
        series=[
            Series((7, 1, 1), "Time Sequence", "qAF", "L", T(2026, 9, 30, 12, 0, 0, 123456),
                   [Img(0, i, height=8, width=8, fill=100 + 10 * i) for i in range(4)]),
            Series((7, 1, 2), "Time Sequence", "ICGA", "L", T(2026, 9, 30, 12, 10, 0),
                   [Img(0, i, height=8, width=8, fill=20 + i) for i in range(6)]),
        ],
    ),
    # Both eyes, mixed order: ids out of eye/modality/time order, an unknown modality, no
    # laterality, no time, a multi-slice volume that follows single-image series in the file.
    "both-eyes-mixed": dict(
        surname="SYNTH005",
        series=[
            Series((7, 1, 1), "Images", "ICGA", "R", T(2026, 9, 30, 11, 0, 0),
                   [Img(0, 0, height=8, width=8, fill=70)]),
            Series((7, 1, 2), "OCT B-Scan", "IR", "L", T(2026, 9, 30, 10, 40, 0),
                   [Img(1, 0, height=6, width=10, fill=5), Img(0, 0, height=8, width=8, fill=61)]),
            Series((7, 1, 3), "Images", "XYZ", None, None,
                   [Img(0, 0, height=8, width=8, fill=80)]),
            Series((7, 1, 4), "OCT ART Volume", "IR", "L", T(2026, 9, 30, 10, 30, 0),
                   bscans(3, base=2) + [Img(0, 0, height=8, width=8, fill=90)]),
            Series((7, 1, 5), "Images", "ICGA", "R", T(2026, 9, 30, 10, 45, 0),
                   [Img(0, 0, height=8, width=8, fill=71)]),
            Series((7, 1, 6), "Images", "IR", "R", None, [Img(0, 0, height=8, width=8, fill=72)]),
            Series((7, 1, 7), "OCT ART Volume", "IR", "R", T(2026, 9, 30, 10, 0, 0),
                   bscans(2, base=7) + [Img(0, 0, height=8, width=8, fill=50)]),
            Series((7, 1, 8), "Images", "FA", "R", T(2026, 9, 30, 10, 45, 0),  # same time as s5
                   [Img(0, 0, height=8, width=8, fill=201)]),
            Series((7, 2, 1), "Images", "IR", "L", T(2026, 10, 1, 9, 0, 0),
                   [Img(0, 0, height=8, width=8, fill=73)]),
            Series((7, 1, 9), "Images", "FA", "R", None, []),  # no image: not reported
        ],
    ),
    # No OCT at all, an empty surname slot.
    "no-oct": dict(
        surname="",
        series=[
            Series((7, 1, 1), "Images", "ICGA", "R", T(2026, 9, 30, 13, 0, 0),
                   [Img(0, 0, height=8, width=8, fill=11), Img(0, 1, height=8, width=8, fill=12)]),
            Series((7, 1, 2), "Images", "IRAF", "L", T(2026, 9, 30, 13, 5, 0),
                   [Img(0, 0, height=8, width=8, fill=13)]),
        ],
    ),
    # Large pixel chunks (about 300 KB): the browser parser must read a few KB of it.
    "large-pixels": dict(
        surname="SYNTH007",
        series=[
            Series((7, 1, 1), "OCT ART Volume", "IR", "R", T(2026, 9, 30, 14, 0, 0),
                   bscans(3, h=64, w=128, base=1) + [Img(0, 0, height=160, width=160, fill=90)]),
            Series((7, 1, 2), "Images", "BAF", "R", T(2026, 9, 30, 14, 10, 0),
                   [Img(0, i, height=160, width=160, fill=50 + i) for i in range(4)]),
        ],
    ),
}


def main() -> None:
    for name, spec in FIXTURES.items():
        path = build_e2e(HERE / f"{name}.e2e", spec["series"], surname=spec["surname"],
                         more_surnames=spec.get("more_surnames", ()))
        expected = describe_e2e(path)
        (HERE / f"{name}.describe.json").write_text(
            json.dumps(expected, indent=2) + "\n", encoding="utf-8", newline="\n")
        print(f"{name}: {path.stat().st_size} bytes, {len(expected['series'])} series")


if __name__ == "__main__":
    main()
