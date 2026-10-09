"""Spacing of an OPT DICOM (``dicom_geometry``) + the cluster's ``_spacing_mm``."""

from __future__ import annotations

import pytest

from retinal_inference import dicom_geometry as geo
from retinal_inference.inference.apptainer import _spacing_mm
from tests.oct_dicom_factory import AXIAL, LATERAL, SLICE, make_opt, read


@pytest.mark.parametrize(
    ("spacing", "px_source", "slice_source"),
    [
        ("top", "top-level PixelSpacing", "top-level SpacingBetweenSlices"),
        ("shared", "SharedFunctionalGroups PixelMeasures PixelSpacing",
         "SharedFunctionalGroups PixelMeasures SpacingBetweenSlices"),
        ("perframe", "PerFrameFunctionalGroups PixelMeasures PixelSpacing",
         "PerFrameFunctionalGroups PixelMeasures SpacingBetweenSlices"),
        ("positions", "SharedFunctionalGroups PixelMeasures PixelSpacing",
         "derived from PerFrameFunctionalGroups PlanePosition ImagePositionPatient"),
    ],
)
def test_volume_spacing_from_every_place_the_standard_allows(spacing, px_source, slice_source):
    ds = read(make_opt(spacing=spacing, n=4))
    axial, lateral, slice_mm, sources = geo.volume_spacing_mm(ds)
    assert axial == pytest.approx(AXIAL)
    assert lateral == pytest.approx(LATERAL)
    assert slice_mm == pytest.approx(SLICE, rel=1e-6)
    assert sources == {"pixel": px_source, "slice": slice_source, "order": "standard"}


def test_no_slice_spacing_anywhere_is_refused():
    ds = read(make_opt(spacing="none"))
    with pytest.raises(geo.SpacingUnavailable, match="SpacingBetweenSlices"):
        geo.volume_spacing_mm(ds)


def test_slice_thickness_is_not_taken_for_the_b_scan_spacing():
    ds = read(make_opt(spacing="none", slice_thickness_only=True))
    with pytest.raises(geo.SpacingUnavailable):
        geo.slice_spacing_mm(ds)


def test_reference_coordinates_are_pixels_of_the_slo_and_not_a_spacing():
    ds = read(make_opt(spacing="none", reference_coordinates_only=True))
    with pytest.raises(geo.SpacingUnavailable):
        geo.slice_spacing_mm(ds)


def test_irregular_plane_positions_are_refused():
    ds = read(make_opt(spacing="positions", n=4, position_steps=[0.1, 0.1, 0.4]))
    with pytest.raises(geo.SpacingUnavailable, match="uniform"):
        geo.slice_spacing_mm(ds)


def test_identical_plane_positions_are_refused():
    ds = read(make_opt(spacing="positions", n=3, position_steps=[0.0, 0.0]))
    with pytest.raises(geo.SpacingUnavailable):
        geo.slice_spacing_mm(ds)


def test_no_pixel_spacing_is_refused():
    ds = read(make_opt(spacing="top"))
    del ds.PixelSpacing
    with pytest.raises(geo.SpacingUnavailable, match="PixelSpacing"):
        geo.pixel_spacing_mm(ds)


def test_implausible_spacing_is_refused():
    ds = read(make_opt(spacing="top"))
    ds.PixelSpacing = [38.7, 11.45]  # µm written as mm
    with pytest.raises(geo.SpacingUnavailable, match="plausible"):
        geo.pixel_spacing_mm(ds)


# --- cluster /run: _spacing_mm -------------------------------------------------


def test_cluster_spacing_reads_top_level(tmp_path):
    f = tmp_path / "bscan.dcm"
    f.write_bytes(make_opt(spacing="top"))
    assert _spacing_mm(f) == pytest.approx((AXIAL, LATERAL, SLICE))


def test_cluster_spacing_falls_back_to_the_functional_groups(tmp_path):
    # A real multi-frame OPT export keeps the spacing in the Shared Functional
    # Groups only; before the fallback every task crashed on ds.PixelSpacing.
    f = tmp_path / "bscan.dcm"
    f.write_bytes(make_opt(spacing="shared"))
    assert _spacing_mm(f) == pytest.approx((AXIAL, LATERAL, SLICE))


def test_cluster_spacing_keeps_the_lateral_fallback_for_a_missing_slice_spacing(tmp_path):
    # Every task handler reads only the axial / lateral value; the historic
    # slice = lateral fallback stays so a stored bscan.dcm keeps working.
    f = tmp_path / "bscan.dcm"
    f.write_bytes(make_opt(spacing="none"))
    assert _spacing_mm(f) == pytest.approx((AXIAL, LATERAL, LATERAL))


def test_cluster_spacing_without_pixel_spacing_names_the_problem(tmp_path):
    ds = read(make_opt(spacing="top"))
    del ds.PixelSpacing
    f = tmp_path / "bscan.dcm"
    ds.save_as(str(f), enforce_file_format=True)
    with pytest.raises(ValueError, match="PixelSpacing"):
        _spacing_mm(f)
