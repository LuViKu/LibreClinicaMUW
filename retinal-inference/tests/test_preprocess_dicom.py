"""``POST /preprocess`` with a DICOM OCT volume instead of a Heidelberg ``.e2e``.

Runs the real normaliser (``dicom_oct`` + ``muw_e2e_converter.write_bscan_dcm``)
on synthetic Ophthalmic Tomography objects: no stubbing of the conversion.
"""

from __future__ import annotations

import io
import json

import numpy as np
import pydicom
import pytest
from fastapi.testclient import TestClient

from retinal_inference import config as _config
from retinal_inference.api import run as run_module
from retinal_inference.inference.apptainer import _spacing_mm
from tests.oct_dicom_factory import (
    AXIAL,
    LATERAL,
    OP_SOP,
    PHI_VALUES,
    SLICE,
    make_opt,
    volume,
)

pytest.importorskip("muw_e2e_converter")

TOKEN = {"X-MUW-Inference-Token": "secret-test-token"}
UUID = "0f8fad5b-d9cb-469f-a165-70867728950e"


@pytest.fixture(autouse=True)
def _enable(monkeypatch, tmp_path):
    monkeypatch.setattr(_config.settings, "preprocess_endpoint_enabled", True, raising=False)
    monkeypatch.setattr(_config.settings, "auth_token", "secret-test-token", raising=False)
    monkeypatch.setattr(_config.settings, "shared_tmpdir", tmp_path / "shared", raising=False)
    monkeypatch.setattr(_config.settings, "bscan_store", None, raising=False)


@pytest.fixture
def client():
    from retinal_inference.main import app

    with TestClient(app) as c:
        yield c


@pytest.fixture
def store(monkeypatch, tmp_path):
    s = tmp_path / "store"
    monkeypatch.setattr(_config.settings, "bscan_store", str(s), raising=False)
    return s


def _post(client, body: bytes, filename: str = "scan.dcm", **data):
    form = {"e2e_uuid": UUID, **data}
    return client.post(
        "/preprocess",
        files={"file": (filename, body, "application/octet-stream")},
        data=form,
        headers=TOKEN,
    )


def _detail(r):
    d = r.json()["detail"]
    assert isinstance(d, dict), d
    return d


# --- happy paths ----------------------------------------------------------------


@pytest.mark.parametrize("spacing", ["top", "shared", "perframe", "positions"])
def test_dicom_volume_is_normalised_with_spacing_at_the_top_level(client, spacing):
    r = _post(client, make_opt(spacing=spacing, n=4))
    assert r.status_code == 200, r.text
    assert r.headers["content-type"] == "application/dicom"
    out = pydicom.dcmread(io.BytesIO(r.content))
    assert out.SOPClassUID == "1.2.840.10008.5.1.4.1.1.77.1.5.4"
    assert out.Modality == "OPT"
    assert int(out.NumberOfFrames) == 4
    assert float(out.PixelSpacing[0]) == pytest.approx(AXIAL, rel=1e-6)
    assert float(out.PixelSpacing[1]) == pytest.approx(LATERAL, rel=1e-6)
    assert float(out.SpacingBetweenSlices) == pytest.approx(SLICE, rel=1e-6)
    assert "SharedFunctionalGroupsSequence" not in out
    assert "PerFrameFunctionalGroupsSequence" not in out
    # One (0022,0031) item per B-scan with 4 mm coordinates, for IOWA.
    ref = out[0x00220031].value
    assert len(ref) == 4
    assert all(len(item.ReferenceCoordinates) == 4 for item in ref)
    assert out.BitsAllocated == 16 and out.PhotometricInterpretation == "MONOCHROME2"


def test_geometry_headers_equal_the_input_spacing(client):
    r = _post(client, make_opt(spacing="shared", n=5, rows=8, cols=6))
    assert r.status_code == 200, r.text
    h = r.headers
    assert float(h["X-MUW-Pixel-Axial-Mm"]) == pytest.approx(AXIAL, abs=1e-8)
    assert float(h["X-MUW-Pixel-Lateral-Mm"]) == pytest.approx(LATERAL, abs=1e-8)
    assert float(h["X-MUW-Pixel-Slice-Mm"]) == pytest.approx(SLICE, abs=1e-8)
    assert (h["X-MUW-Bscan-Dim-Z"], h["X-MUW-Bscan-Dim-Y"], h["X-MUW-Bscan-Dim-X"]) == ("5", "8", "6")
    assert h["X-MUW-E2E-Uuid"] == UUID
    assert h["X-MUW-Source-Format"] == "dicom"
    assert h["X-MUW-Manufacturer"] == "Heidelberg Engineering"
    assert h["X-MUW-Manufacturer-Model"] == "Spectralis"
    assert h["X-MUW-Laterality"] == "OD"
    assert h["X-MUW-Acquisition-Date"] == "2026-09-15"
    assert set(h["X-MUW-Device-Tasks"].split(",")) >= {"fluid", "sdretinanet"}
    for name in ("X-MUW-Source-Format", "X-MUW-Manufacturer", "X-MUW-Device-Tasks"):
        assert name in h["Access-Control-Expose-Headers"]


def test_another_vendor_is_normalised_but_reports_no_device_tasks(client):
    r = _post(client, make_opt(manufacturer="Carl Zeiss Meditec", model="CIRRUS HD-OCT 5000"))
    assert r.status_code == 200, r.text
    assert r.headers["X-MUW-Manufacturer"] == "Carl Zeiss Meditec"
    assert r.headers["X-MUW-Device-Tasks"] == ""
    out = pydicom.dcmread(io.BytesIO(r.content))
    assert out.Manufacturer == "Carl Zeiss Meditec"  # never rewritten to Heidelberg


def test_dicom_is_detected_by_content_not_name(client):
    r = _post(client, make_opt(), filename="input.e2e")
    assert r.status_code == 200, r.text
    assert r.headers["X-MUW-Source-Format"] == "dicom"


def test_pixels_survive_normalisation_in_order(client):
    src = volume(3, 8, 6, 16)
    r = _post(client, make_opt(pixels=src))
    out = pydicom.dcmread(io.BytesIO(r.content)).pixel_array
    assert out.shape == (3, 8, 6) and out.dtype == np.uint16
    assert out.max() == 65535  # scaled like the .e2e path
    expected = np.clip(src.astype(np.float32) * (65535.0 / src.max()), 0, 65535).astype(np.uint16)
    assert np.array_equal(out, expected)


def test_eight_bit_monochrome1_is_inverted_to_monochrome2(client):
    src = volume(3, 8, 6, 8)
    r = _post(client, make_opt(bits=8, photometric="MONOCHROME1", pixels=src))
    assert r.status_code == 200, r.text
    out = pydicom.dcmread(io.BytesIO(r.content))
    assert out.PhotometricInterpretation == "MONOCHROME2"
    arr = out.pixel_array
    # The brightest source pixel (white in MONOCHROME1 = lowest value) is the
    # lowest output value and vice versa.
    assert arr.flat[int(np.argmin(src))] == 65535
    assert arr.flat[int(np.argmax(src))] == 0


def test_rle_compressed_volume_is_decoded(client):
    r = _post(client, make_opt(compress_rle=True))
    assert r.status_code == 200, r.text
    out = pydicom.dcmread(io.BytesIO(r.content))
    assert out.file_meta.TransferSyntaxUID == "1.2.840.10008.1.2.1"


# --- de-identification -------------------------------------------------------------


def test_output_carries_no_identifier_and_no_private_tag(client):
    body = make_opt()
    # The planted identifiers really are in the input.
    for v in PHI_VALUES:
        assert v.encode() in body
    r = _post(client, body)
    assert r.status_code == 200, r.text
    for v in PHI_VALUES:
        assert v.encode() not in r.content, v
    out = pydicom.dcmread(io.BytesIO(r.content))
    assert not any(e.tag.is_private for e in out.iterall())
    for kw in ("PatientName", "PatientID", "PatientBirthDate", "AccessionNumber",
               "InstitutionName", "ReferringPhysicianName", "OperatorsName"):
        assert str(getattr(out, kw, "") or "") == "", kw
    for kw in ("StudyDescription", "SeriesDescription", "DeviceSerialNumber",
               "StationName", "OtherPatientIDs"):
        assert kw not in out, kw
    assert out.PatientIdentityRemoved == "YES"
    assert out.DeidentificationMethod
    # Kept for vendor gating.
    assert out.Manufacturer == "Heidelberg Engineering"
    assert out.ManufacturerModelName == "Spectralis"
    # Restamped UIDs.
    assert out.StudyInstanceUID != "1.2.3.4.5.6.7.8.9.111"
    assert out.SeriesInstanceUID != "1.2.3.4.5.6.7.8.9.222"
    assert out.SOPInstanceUID != "1.2.3.4.5.6.7.8.9.333"


def test_output_passes_the_dicom_scp_style_verification(client):
    """The same checks dicom-scp's verify.py applies to a pseudonymised file."""
    r = _post(client, make_opt())
    out = pydicom.dcmread(io.BytesIO(r.content))
    for elem in out.iterall():
        assert not elem.tag.is_private
        if elem.VR == "PN":
            assert str(elem.value or "") == ""


def test_store_gets_bscan_and_geometry_without_fundus(client, store):
    r = _post(client, make_opt(spacing="positions", n=4))
    assert r.status_code == 200, r.text
    d = store / UUID
    assert (d / "bscan.dcm").read_bytes() == r.content
    assert not (d / "fundus.png").exists()
    geo = json.loads((d / "geometry.json").read_text())
    assert geo["source_format"] == "dicom"
    assert geo["scan_index"] == 0
    assert geo["bscan"] == {
        "dim_x_ascans": 6,
        "dim_y_rows": 8,
        "dim_z_bscans": 4,
        "pixel_axial_mm": pytest.approx(AXIAL),
        "pixel_lateral_mm": pytest.approx(LATERAL),
        "pixel_slice_mm": pytest.approx(SLICE),
    }
    assert geo["fundus"] is None
    assert geo["bscan_positions_fundus_px"] == []
    assert geo["scan_bbox_fundus_px"] is None
    assert geo["fovea_estimate_fundus_px"] is None
    assert geo["spacing_source"]["slice"].startswith("derived from")
    assert geo["device"] == {"manufacturer": "Heidelberg Engineering", "model": "Spectralis"}
    text = (d / "geometry.json").read_text()
    for v in PHI_VALUES:
        assert v not in text
    # Nothing else in the store (the source file never lands there).
    assert sorted(p.name for p in d.iterdir()) == ["bscan.dcm", "geometry.json"]


# --- round trip into the cluster's /run --------------------------------------------


def test_output_is_accepted_by_run_detection_and_spacing(client, tmp_path):
    r = _post(client, make_opt(spacing="perframe", n=4))
    assert r.status_code == 200, r.text
    assert run_module._is_dicom(None, r.content)
    path = run_module._materialize_input(tmp_path, None, r.content)
    assert path.name == "bscan.dcm"
    assert _spacing_mm(path) == pytest.approx((AXIAL, LATERAL, SLICE), rel=1e-6)
    run_module._check_device("fluid", r.content)  # does not raise


# --- refusals ------------------------------------------------------------------------


def test_missing_spacing_is_refused(client):
    r = _post(client, make_opt(spacing="none", slice_thickness_only=True))
    assert r.status_code == 422
    d = _detail(r)
    assert d["error"] == "spacing_unavailable"
    assert "SpacingBetweenSlices" in d["message"]


def test_spacing_only_in_reference_coordinates_is_refused(client):
    r = _post(client, make_opt(spacing="none", reference_coordinates_only=True))
    assert r.status_code == 422
    assert _detail(r)["error"] == "spacing_unavailable"


def test_ou_laterality_without_a_form_value_is_refused(client):
    r = _post(client, make_opt(image_laterality="B"))
    assert r.status_code == 422
    d = _detail(r)
    assert d["error"] == "laterality_missing"
    assert "OU" in d["message"]


def test_ou_laterality_with_a_form_value_uses_the_form(client):
    r = _post(client, make_opt(image_laterality="B"), laterality="OS")
    assert r.status_code == 200, r.text
    assert r.headers["X-MUW-Laterality"] == "OS"
    assert pydicom.dcmread(io.BytesIO(r.content)).ImageLaterality == "L"


def test_missing_laterality_without_a_form_value_is_refused(client):
    r = _post(client, make_opt(image_laterality=None))
    assert r.status_code == 422
    assert _detail(r)["error"] == "laterality_missing"


def test_series_laterality_is_read_when_image_laterality_is_absent(client):
    r = _post(client, make_opt(image_laterality=None, laterality="L"))
    assert r.status_code == 200, r.text
    assert r.headers["X-MUW-Laterality"] == "OS"


def test_a_form_laterality_that_contradicts_the_file_is_refused(client):
    r = _post(client, make_opt(image_laterality="R"), laterality="OS")
    assert r.status_code == 422
    assert _detail(r)["error"] == "laterality_conflict"


def test_burned_in_annotation_is_refused(client, store):
    r = _post(client, make_opt(burned_in="YES"))
    assert r.status_code == 422
    assert _detail(r)["error"] == "burned_in_annotation"
    assert not (store / UUID).exists()


@pytest.mark.parametrize(
    "kwargs",
    [
        {"sop_class": OP_SOP, "modality": "OP"},  # a fundus photograph
        {"n": 1},  # a single B-scan
    ],
)
def test_non_volumes_are_refused(client, kwargs):
    r = _post(client, make_opt(**kwargs))
    assert r.status_code == 422
    assert _detail(r)["error"] == "not_oct_volume"


def test_modality_opt_without_the_sop_class_is_accepted(client):
    r = _post(client, make_opt(sop_class="1.2.840.10008.5.1.4.1.1.7.3", modality="OPT"))
    assert r.status_code == 200, r.text


def test_colour_data_is_refused(client):
    r = _post(client, make_opt(photometric="RGB", samples_per_pixel=1))
    assert r.status_code == 422
    assert _detail(r)["error"] == "not_monochrome"


def test_missing_manufacturer_is_refused(client):
    # The writer would otherwise stamp its Heidelberg default and defeat gating.
    r = _post(client, make_opt(manufacturer=None))
    assert r.status_code == 422
    assert _detail(r)["error"] == "missing_manufacturer"


def test_a_compression_without_a_decoder_is_refused(client):
    r = _post(client, make_opt(transfer_syntax="1.2.840.10008.1.2.4.70"))  # JPEG Lossless SV1
    assert r.status_code == 422
    d = _detail(r)
    assert d["error"] == "unsupported_transfer_syntax"
    assert "1.2.840.10008.1.2.4.70" in d["message"]


def test_a_dicom_with_scan_index_is_refused(client):
    r = _post(client, make_opt(), scan_index="1")
    assert r.status_code == 400


def test_refusal_messages_never_echo_identifiers(client):
    for kwargs in ({"burned_in": "YES"}, {"spacing": "none"}, {"image_laterality": "B"}):
        r = _post(client, make_opt(**kwargs))
        assert r.status_code == 422
        for v in PHI_VALUES:
            assert v not in r.text
