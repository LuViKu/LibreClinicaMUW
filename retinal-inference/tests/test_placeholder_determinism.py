"""PlaceholderAdapter determinism — direct adapter calls (no HTTP)."""

from __future__ import annotations

from pathlib import Path


def _make_adapter():
    from retinal_inference.config import reload_settings
    from retinal_inference.inference.adapter import reset_adapter
    from retinal_inference.inference.placeholder import PlaceholderAdapter

    reload_settings()
    reset_adapter()
    return PlaceholderAdapter()


def test_fast_screen_same_input_same_output(fake_e2e_path: Path) -> None:
    a = _make_adapter()
    r1 = a.fast_screen("ga", fake_e2e_path, "OD")
    r2 = a.fast_screen("ga", fake_e2e_path, "OD")
    assert r1.approx_area_mm2 == r2.approx_area_mm2
    assert r1.confidence == r2.confidence
    assert r1.model_version == r2.model_version == "placeholder-v1"


def test_full_volume_same_input_same_output(fake_e2e_path: Path) -> None:
    a = _make_adapter()
    r1 = a.full_volume("ga", fake_e2e_path, "OD")
    r2 = a.full_volume("ga", fake_e2e_path, "OD")
    assert r1.total_area_mm2 == r2.total_area_mm2
    assert r1.per_bscan_areas_mm2 == r2.per_bscan_areas_mm2
    assert r1.pixel_scale_mm == r2.pixel_scale_mm


def test_different_task_different_numbers(fake_e2e_path: Path) -> None:
    """Seed mixes task into sha256 — different task strings must yield different seeds.

    We can't actually exercise ``fast_screen`` with a non-GA task because the
    Pydantic ``TaskName`` Literal in the response model would reject it. The
    underlying contract — ``_seed(e2e, task)`` mixes ``task`` into the
    sha256 — is the load-bearing piece; verify it directly so a future task
    addition is guaranteed to return numerically different output.
    """
    from retinal_inference.inference.placeholder import PlaceholderAdapter

    seed_ga = PlaceholderAdapter._seed(fake_e2e_path, "ga")
    seed_fluid = PlaceholderAdapter._seed(fake_e2e_path, "fluid")  # type: ignore[arg-type]
    assert seed_ga != seed_fluid
    # The 32-byte seeds for our fixture happen to differ in the first byte,
    # so end-to-end fast_screen output would diverge too. Pin that with the
    # known sha256 values for the fixture so a future fixture swap doesn't
    # silently land in the (statistically unlikely) collision window.
    import hashlib

    expected_ga = hashlib.sha256(fake_e2e_path.read_bytes() + b"ga").digest()
    expected_fluid = hashlib.sha256(fake_e2e_path.read_bytes() + b"fluid").digest()
    assert seed_ga == expected_ga
    assert seed_fluid == expected_fluid
    assert expected_ga[0] != expected_fluid[0]
