"""Parity: ``describe_e2e`` on the shared synthetic fixtures equals the committed JSON.

The same files and JSON are asserted by the browser parser (web/src/spa/src/lib/e2eSeries.ts), so
the server and the browser listing of a file's series cannot drift apart. Regenerate with
``testdata/e2e-synthetic/generate.py``.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from muw_e2e_converter import describe_e2e

FIXTURES = Path(__file__).resolve().parents[2] / "testdata" / "e2e-synthetic"
FILES = sorted(FIXTURES.glob("*.e2e"))


def test_fixtures_exist() -> None:
    assert len(FILES) >= 6
    for f in FILES:
        assert f.with_suffix(".describe.json").is_file(), f.name


@pytest.mark.parametrize("e2e", FILES, ids=lambda p: p.stem)
def test_describe_matches_committed_json(e2e: Path) -> None:
    expected = json.loads(e2e.with_suffix(".describe.json").read_text(encoding="utf-8"))
    assert describe_e2e(e2e) == expected
