"""Which PixelSpacing value is axial.

DICOM defines PixelSpacing as row spacing first, which for an OPT B-scan is
the axial (depth) spacing; original Heyex exports are expected to follow it
(per the user). A DICOM produced by a third-party .e2e -> DICOM converter
(another MUW-internal platform) was observed with [lateral, axial]. Taking it
at its word makes every thickness ~1.5x too large, silently, so the axial
value is resolved physically for Heidelberg devices; the rule handles both.
"""

from __future__ import annotations

import io
import json

import pydicom
import pytest
from fastapi.testclient import TestClient

from retinal_inference import config as _config
from retinal_inference import devices
from retinal_inference import dicom_geometry as geo
from retinal_inference.api import run as run_module
from retinal_inference.inference.apptainer import _spacing_mm
from tests.oct_dicom_factory import (
    AXIAL,
    CONVERTED_AXIAL,
    CONVERTED_LATERAL,
    CONVERTED_SLICE,
    LATERAL,
    SLICE,
    make_opt,
    make_third_party_e2e_conversion,
    read,
)

pytest.importorskip("muw_e2e_converter")

TOKEN = {"X-MUW-Inference-Token": "secret-test-token"}
UUID = "6f1c2a3b-4d5e-4f60-8a7b-9c0d1e2f3a4b"


@pytest.fixture
def client(monkeypatch, tmp_path):
    monkeypatch.setattr(_config.settings, "preprocess_endpoint_enabled", True, raising=False)
    monkeypatch.setattr(_config.settings, "auth_token", "secret-test-token", raising=False)
    monkeypatch.setattr(_config.settings, "shared_tmpdir", tmp_path / "shared", raising=False)
    monkeypatch.setattr(_config.settings, "bscan_store", str(tmp_path / "store"), raising=False)
    from retinal_inference.main import app

    with TestClient(app) as c:
        c.store = tmp_path / "store"  # type: ignore[attr-defined]
        yield c


def _post(client, body: bytes, **data):
    return client.post(
        "/preprocess",
        files={"file": ("scan.dcm", body, "application/dicom")},
        data={"e2e_uuid": UUID, **data},
        headers=TOKEN,
    )


# --- the rule ----------------------------------------------------------------------


def test_third_party_conversion_resolves_as_swapped():
    ds = read(make_third_party_e2e_conversion())
    axial, lateral, _src, order = geo.pixel_spacing_mm(ds)
    assert (axial, lateral, order) == (
        pytest.approx(CONVERTED_AXIAL), pytest.approx(CONVERTED_LATERAL), "swapped"
    )


def test_standard_order_heidelberg_resolves_as_standard():
    ds = read(make_opt(pixel_spacing=(CONVERTED_AXIAL, CONVERTED_LATERAL)))
    axial, lateral, _src, order = geo.pixel_spacing_mm(ds)
    assert (axial, lateral, order) == (
        pytest.approx(CONVERTED_AXIAL), pytest.approx(CONVERTED_LATERAL), "standard"
    )


def test_both_values_axial_like_is_ambiguous():
    ds = read(make_opt(pixel_spacing=(0.00387, 0.0039)))
    with pytest.raises(geo.SpacingAmbiguous, match="axial"):
        geo.pixel_spacing_mm(ds)


def test_neither_value_axial_like_is_ambiguous():
    ds = read(make_opt(pixel_spacing=(0.0058, 0.0114)))
    with pytest.raises(geo.SpacingAmbiguous):
        geo.pixel_spacing_mm(ds)


def test_other_manufacturers_keep_the_standard_order_as_an_assumption():
    ds = read(make_opt(manufacturer="Carl Zeiss Meditec", pixel_spacing=(0.0058, 0.0020)))
    axial, lateral, _src, order = geo.pixel_spacing_mm(ds)
    assert (axial, lateral, order) == (pytest.approx(0.0058), pytest.approx(0.0020),
                                       "standard-assumed")


def test_plausibility_of_the_converted_6mm_cube():
    p = geo.plausibility(
        rows=496, cols=1024, n_frames=97, axial=CONVERTED_AXIAL, lateral=CONVERTED_LATERAL,
        slice_mm=CONVERTED_SLICE, manufacturer="Heidelberg Engineering",
    )
    assert p["depth_mm"] == pytest.approx(1.920512)
    assert p["width_mm"] == pytest.approx(5.9648)
    assert p["volume_depth_mm"] == pytest.approx(5.965248)
    assert p["depth_plausible"] is True
    assert p["warnings"] == []


def test_plausibility_flags_an_implausible_depth_without_refusing():
    p = geo.plausibility(
        rows=496, cols=1024, n_frames=97, axial=0.005825, lateral=0.003872,
        slice_mm=CONVERTED_SLICE, manufacturer="Heidelberg Engineering",
    )
    assert p["depth_plausible"] is False
    assert p["warnings"] and "depth" in p["warnings"][0]


def test_plausibility_is_not_judged_for_other_vendors():
    p = geo.plausibility(rows=1024, cols=512, n_frames=128, axial=0.002, lateral=0.0117,
                         slice_mm=0.047, manufacturer="Carl Zeiss Meditec")
    assert p["depth_plausible"] is None


# --- preprocess --------------------------------------------------------------------


def test_preprocess_writes_a_third_party_conversion_in_standard_order(client):
    r = _post(client, make_third_party_e2e_conversion())
    assert r.status_code == 200, r.text
    out = pydicom.dcmread(io.BytesIO(r.content))
    assert [float(v) for v in out.PixelSpacing] == pytest.approx([CONVERTED_AXIAL, CONVERTED_LATERAL])
    assert float(out.SpacingBetweenSlices) == pytest.approx(CONVERTED_SLICE)
    assert float(r.headers["X-MUW-Pixel-Axial-Mm"]) == pytest.approx(CONVERTED_AXIAL)
    assert float(r.headers["X-MUW-Pixel-Lateral-Mm"]) == pytest.approx(CONVERTED_LATERAL)
    assert r.headers["X-MUW-Spacing-Order"] == "swapped"
    assert "X-MUW-Spacing-Order" in r.headers["Access-Control-Expose-Headers"]
    geom = json.loads((client.store / UUID / "geometry.json").read_text())
    assert geom["spacing_order"] == "swapped"
    assert geom["bscan"]["pixel_axial_mm"] == pytest.approx(CONVERTED_AXIAL)
    assert geom["plausibility"]["depth_mm"] == pytest.approx(496 * CONVERTED_AXIAL)
    assert geom["plausibility"]["width_mm"] == pytest.approx(1024 * CONVERTED_LATERAL)
    assert geom["plausibility"]["depth_plausible"] is True


def test_third_party_conversion_without_model_or_image_laterality_passes_laterality_and_gating(client):
    r = _post(client, make_third_party_e2e_conversion())
    assert r.status_code == 200, r.text
    assert r.headers["X-MUW-Laterality"] == "OD"  # from Laterality (0020,0060)
    assert "X-MUW-Manufacturer-Model" not in r.headers
    tasks = r.headers["X-MUW-Device-Tasks"].split(",")
    assert set(tasks) == set(devices.TASK_DEVICES)
    # And the cluster accepts it, both raw and normalised.
    run_module._check_device("sdretinanet", make_third_party_e2e_conversion())
    run_module._check_device("sdretinanet", r.content)


def test_preprocess_standard_heidelberg_reports_standard(client):
    r = _post(client, make_opt())
    assert r.status_code == 200, r.text
    assert r.headers["X-MUW-Spacing-Order"] == "standard"


@pytest.mark.parametrize("px", [(0.00387, 0.0039), (0.0058, 0.0114)])
def test_preprocess_refuses_an_ambiguous_heidelberg_spacing(client, px):
    r = _post(client, make_opt(pixel_spacing=px))
    assert r.status_code == 422
    d = r.json()["detail"]
    assert d["error"] == "spacing_ambiguous"
    assert "axial" in d["message"]


def test_preprocess_other_vendor_reports_standard_assumed(client):
    r = _post(client, make_opt(manufacturer="Topcon", model="Maestro2",
                               pixel_spacing=(0.0026, 0.0117)))
    assert r.status_code == 200, r.text
    assert r.headers["X-MUW-Spacing-Order"] == "standard-assumed"


# --- cluster --------------------------------------------------------------------------


def test_cluster_spacing_resolves_a_raw_third_party_conversion(tmp_path):
    f = tmp_path / "bscan.dcm"
    f.write_bytes(make_third_party_e2e_conversion())
    assert _spacing_mm(f) == pytest.approx((CONVERTED_AXIAL, CONVERTED_LATERAL, CONVERTED_SLICE))


def test_cluster_spacing_refuses_an_ambiguous_raw_file(tmp_path):
    f = tmp_path / "bscan.dcm"
    f.write_bytes(make_opt(pixel_spacing=(0.00387, 0.0039)))
    with pytest.raises(ValueError, match="axial"):
        _spacing_mm(f)


def test_cluster_trusts_the_order_of_our_own_normalised_file(client, tmp_path):
    # A Spectralis scan whose lateral spacing is also ~3.9 µm (e.g. 1536
    # A-scans over 6 mm) is ambiguous by the value rule; our own writer always
    # stores [axial, lateral], so a file it wrote resolves as standard.
    from muw_e2e_converter import BscanVolume, write_bscan_dcm

    from tests.oct_dicom_factory import volume

    bv = BscanVolume(volume_u8=volume(3, 8, 6), axial_mm=0.00387, lateral_mm=0.0039,
                     slice_mm=SLICE, laterality="OD", n_bscans=3, rows=8, cols=6,
                     manufacturer="Heidelberg Engineering")
    write_bscan_dcm(bv, tmp_path)
    assert _spacing_mm(tmp_path / "bscan.dcm") == pytest.approx((0.00387, 0.0039, SLICE))
    ds = pydicom.dcmread(str(tmp_path / "bscan.dcm"), stop_before_pixels=True)
    assert geo.pixel_spacing_mm(ds)[3] == "standard"


def test_unchanged_fixture_still_standard():
    ds = read(make_opt())
    assert geo.pixel_spacing_mm(ds)[:2] == (pytest.approx(AXIAL), pytest.approx(LATERAL))
