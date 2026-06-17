"""DR-022 artifact collector — per-task output aggregation against fixture dirs."""

from __future__ import annotations

import base64
from pathlib import Path

from retinal_inference.inference.artifact_collector import (
    collect_artifacts,
    filenames,
    rewrite_payload_paths,
)


def _seed_fluid_outputs(tmpdir: Path) -> None:
    # Mirrors fluid runner output: bscan.dcm (skipped) + fluid_labels.npy.
    (tmpdir / "bscan.dcm").write_bytes(b"DICM-FAKE")
    (tmpdir / "fluid_labels.npy").write_bytes(b"\x93NUMPY-FAKE")


def _seed_onl_outputs(tmpdir: Path) -> None:
    # Mirrors ONL runner: upper + lower boundary CSVs.
    (tmpdir / "bscan.dcm").write_bytes(b"DICM-FAKE")
    (tmpdir / "001-OPL-HFL.csv").write_text("size_header\n1,2,3\n")
    (tmpdir / "002-BMEIS.csv").write_text("size_header\n4,5,6\n")


def _seed_pr_outputs(tmpdir: Path) -> None:
    # The PR runner emits the BMEIS + OB-OPR surface CSVs.
    (tmpdir / "bscan.dcm").write_bytes(b"DICM-FAKE")
    (tmpdir / "001-BMEIS.csv").write_text("size_header\n7,8,9\n")
    (tmpdir / "002-OB-OPR.csv").write_text("size_header\n1,2,3\n")
    # mhd_in is internal; should be skipped.
    nested = tmpdir / "mhd_in"
    nested.mkdir()
    (nested / "bscan.mhd").write_text("header")
    (nested / "bscan.raw").write_bytes(b"raw")


def _seed_ga_outputs(tmpdir: Path) -> None:
    (tmpdir / "bscan.dcm").write_bytes(b"DICM-FAKE")
    (tmpdir / "rpel.csv").write_text("a,b,c\n")


def test_fluid_artifacts(tmp_path: Path) -> None:
    _seed_fluid_outputs(tmp_path)
    artifacts = collect_artifacts(tmp_path)
    assert filenames(artifacts) == ["fluid_labels.npy"]
    assert artifacts[0].media_type == "application/octet-stream"
    assert base64.b64decode(artifacts[0].content_base64) == b"\x93NUMPY-FAKE"


def test_onl_artifacts(tmp_path: Path) -> None:
    _seed_onl_outputs(tmp_path)
    artifacts = collect_artifacts(tmp_path)
    assert filenames(artifacts) == ["001-OPL-HFL.csv", "002-BMEIS.csv"]
    for a in artifacts:
        assert a.media_type == "text/csv"


def test_pr_artifacts_skip_mhd_in(tmp_path: Path) -> None:
    _seed_pr_outputs(tmp_path)
    artifacts = collect_artifacts(tmp_path)
    assert filenames(artifacts) == ["001-BMEIS.csv", "002-OB-OPR.csv"]


def test_ga_artifacts(tmp_path: Path) -> None:
    _seed_ga_outputs(tmp_path)
    artifacts = collect_artifacts(tmp_path)
    assert filenames(artifacts) == ["rpel.csv"]


def test_rewrite_payload_strips_tempdir_paths(tmp_path: Path) -> None:
    payload = {
        "total_fluid_volume_mm3": 6.84,
        "opl_hfl_csv": str(tmp_path / "001-OPL-HFL.csv"),
        "bmeis_csv": str(tmp_path / "002-BMEIS.csv"),
        "unrelated_string": "not-a-path",
    }
    rewritten = rewrite_payload_paths(payload, tmp_path)
    assert rewritten["total_fluid_volume_mm3"] == 6.84
    assert rewritten["opl_hfl_csv"] == "001-OPL-HFL.csv"
    assert rewritten["bmeis_csv"] == "002-BMEIS.csv"
    assert rewritten["unrelated_string"] == "not-a-path"


def test_skip_unknown_extensions(tmp_path: Path) -> None:
    (tmp_path / "vendor_log.txt").write_text("not interesting")
    (tmp_path / "001-OPL-HFL.csv").write_text("size\n1,2\n")
    artifacts = collect_artifacts(tmp_path)
    assert filenames(artifacts) == ["001-OPL-HFL.csv"]