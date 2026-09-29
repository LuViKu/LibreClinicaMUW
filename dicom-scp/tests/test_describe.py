"""The describe endpoint: token-gated, path-confined, and it never says who was imaged (DR-029)."""
from __future__ import annotations

import http.client
import json
import logging
import threading
from http.server import ThreadingHTTPServer
from pathlib import Path

import numpy as np
import pydicom
import pytest
from pydicom.dataset import Dataset, FileMetaDataset
from pydicom.uid import ExplicitVRLittleEndian, generate_uid

from dicom_scp import config, describe

OPHTHALMIC_PHOTO_8BIT = "1.2.840.10008.5.1.4.1.1.77.1.5.1"
TOKEN = "test-token"


def _write_export(path: Path, laterality: str = "R") -> str:
    sop = generate_uid()
    ds = Dataset()
    ds.SOPInstanceUID = sop
    ds.SOPClassUID = OPHTHALMIC_PHOTO_8BIT
    ds.StudyInstanceUID = generate_uid()
    ds.SeriesInstanceUID = generate_uid()
    ds.Modality = "OP"
    ds.PatientName = "Muster^Max"
    ds.PatientID = "0012345678"
    ds.PatientBirthDate = "19500101"
    ds.StudyDate = "20260918"
    ds.ContentDate = "20260918"
    ds.ImageLaterality = laterality
    ds.Manufacturer = "Carl Zeiss Meditec"
    ds.ManufacturerModelName = "CLARUS 700"
    ds.Rows = 4
    ds.Columns = 4
    ds.SamplesPerPixel = 3
    ds.PhotometricInterpretation = "RGB"
    ds.PlanarConfiguration = 0
    ds.BitsAllocated = 8
    ds.BitsStored = 8
    ds.HighBit = 7
    ds.PixelRepresentation = 0
    ds.PixelData = (np.arange(4 * 4 * 3) % 256).astype("uint8").tobytes()
    fm = FileMetaDataset()
    fm.MediaStorageSOPClassUID = OPHTHALMIC_PHOTO_8BIT
    fm.MediaStorageSOPInstanceUID = sop
    fm.TransferSyntaxUID = ExplicitVRLittleEndian
    ds.file_meta = fm
    ds.save_as(str(path), enforce_file_format=True)
    return sop


@pytest.fixture
def served(tmp_path, monkeypatch):
    """A describe server on an ephemeral port, confined to ``tmp_path/store``."""
    root = tmp_path / "store"
    root.mkdir()
    monkeypatch.setattr(config.settings, "ingest_token", TOKEN)
    monkeypatch.setattr(config.settings, "describe_roots", str(root))
    monkeypatch.setattr(config.settings, "deidentify_drop_private", False)
    server = ThreadingHTTPServer(("127.0.0.1", 0), describe.DescribeHandler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield root, server.server_address[1]
    finally:
        server.shutdown()
        server.server_close()


def _post(port: int, body: dict | str, token: str | None = TOKEN, path: str = "/describe"):
    conn = http.client.HTTPConnection("127.0.0.1", port, timeout=10)
    headers = {"Content-Type": "application/json"}
    if token is not None:
        headers[describe.TOKEN_HEADER] = token
    raw = body if isinstance(body, str) else json.dumps(body)
    conn.request("POST", path, body=raw, headers=headers)
    resp = conn.getresponse()
    payload = json.loads(resp.read().decode("utf-8"))
    conn.close()
    return resp.status, payload


def test_a_described_file_comes_back_pseudonymised_with_its_exam_tags(served):
    root, port = served
    path = root / "upload.dcm"
    sop = _write_export(path)

    status, payload = _post(port, {"path": str(path), "pseudonym": "HAE-001"})

    assert status == 200
    assert payload["sopInstanceUid"] == sop
    assert payload["modality"] == "OP"
    assert payload["laterality"] == "OD"
    assert payload["acquisitionDate"] == "2026-09-18"
    assert payload["manufacturerModelName"] == "CLARUS 700"
    assert payload["identityRemoved"] is True
    assert payload["changedTags"] >= 3
    # Nothing about the patient travels back to the app.
    assert "patientName" not in payload and "patientId" not in payload
    assert "Muster" not in json.dumps(payload)
    # A preview was written beside the file, and the file itself is clean.
    assert Path(payload["previewPngPath"]).is_file()
    back = pydicom.dcmread(str(path))
    assert str(back.PatientName) == "HAE-001"
    assert back.PatientBirthDate == ""


def test_the_fingerprint_route_digests_the_picture_without_touching_the_file(served):
    root, port = served
    path = root / "old.dcm"
    _write_export(path)

    status, payload = _post(port, {"path": str(path)}, path="/fingerprint")

    assert status == 200
    assert isinstance(payload["pixelSha256"], str) and len(payload["pixelSha256"]) == 64
    assert list(payload) == ["pixelSha256"], "nothing else about the file travels"
    assert str(pydicom.dcmread(str(path)).PatientName) == "Muster^Max", "read-only"
    # The same digest the describe route reports, before and after pseudonymisation.
    status, described = _post(port, {"path": str(path), "pseudonym": "HAE-001"})
    assert status == 200
    assert described["pixelSha256"] == payload["pixelSha256"]
    assert _post(port, {"path": str(path)}, path="/fingerprint")[1]["pixelSha256"] == payload["pixelSha256"]
    # Same picture, another label, another SOP instance: same digest.
    other = root / "again.dcm"
    _write_export(other)
    assert _post(port, {"path": str(other)}, path="/fingerprint")[1]["pixelSha256"] == payload["pixelSha256"]


def test_the_fingerprint_route_is_gated_and_confined_like_describe(served, tmp_path):
    root, port = served
    path = root / "old.dcm"
    _write_export(path)
    assert _post(port, {"path": str(path)}, token=None, path="/fingerprint")[0] == 401
    outside = tmp_path / "elsewhere.dcm"
    _write_export(outside)
    assert _post(port, {"path": str(outside)}, path="/fingerprint")[0] == 403
    not_dicom = root / "photo.dcm"
    not_dicom.write_bytes(b"\xff\xd8\xff\xe0" + b"\x00" * 300)
    assert _post(port, {"path": str(not_dicom)}, path="/fingerprint")[0] == 422


def test_the_token_is_required(served):
    root, port = served
    path = root / "upload.dcm"
    _write_export(path)

    assert _post(port, {"path": str(path)}, token=None)[0] == 401
    assert _post(port, {"path": str(path)}, token="wrong")[0] == 401
    # And the file was not touched.
    assert str(pydicom.dcmread(str(path)).PatientName) == "Muster^Max"


def test_a_path_outside_the_store_is_refused(served, tmp_path):
    root, port = served
    outside = tmp_path / "elsewhere.dcm"
    _write_export(outside)

    status, payload = _post(port, {"path": str(outside), "pseudonym": "HAE-001"})

    assert status == 403
    assert str(pydicom.dcmread(str(outside)).PatientName) == "Muster^Max"
    # A traversal through the root does not reach it either.
    status, _ = _post(port, {"path": str(root / ".." / "elsewhere.dcm")})
    assert status == 403


def test_a_missing_or_non_dicom_file_is_reported_not_crashed(served):
    root, port = served
    assert _post(port, {"path": str(root / "nope.dcm")})[0] == 403
    jpeg = root / "photo.dcm"
    jpeg.write_bytes(b"\xff\xd8\xff\xe0" + b"\x00" * 300)
    assert _post(port, {"path": str(jpeg)})[0] == 422


def test_malformed_requests_are_400s(served):
    _, port = served
    assert _post(port, "not json")[0] == 400
    assert _post(port, [1, 2])[0] == 400
    assert _post(port, {"path": "x", "pseudonym": 5})[0] in (400, 403)
    assert _post(port, {}, path="/other")[0] == 404


def test_the_patient_never_reaches_the_log(served, caplog):
    root, port = served
    path = root / "upload.dcm"
    _write_export(path)
    with caplog.at_level(logging.DEBUG):
        _post(port, {"path": str(path), "pseudonym": "HAE-001"})
    assert "Muster" not in caplog.text
    assert "0012345678" not in caplog.text
    assert "upload.dcm" not in caplog.text


def test_confine_accepts_only_regular_files_under_a_root(tmp_path):
    root = tmp_path / "r"
    (root / "sub").mkdir(parents=True)
    inside = root / "sub" / "a.dcm"
    inside.write_bytes(b"x")
    assert describe.confine(str(inside), [root]) == inside.resolve()
    assert describe.confine(str(root / "sub"), [root]) is None
    assert describe.confine(str(tmp_path / "b.dcm"), [root]) is None
    assert describe.confine(None, [root]) is None
    assert describe.confine(123, [root]) is None  # type: ignore[arg-type]


def test_the_token_is_compared_in_constant_time(served, monkeypatch):
    # A plain != stops at the first differing byte, so response time leaks how
    # much of a guess was right; the handler must go through compare_digest.
    import hmac

    calls = []
    real = hmac.compare_digest

    def spy(a, b):
        calls.append((a, b))
        return real(a, b)

    monkeypatch.setattr(hmac, "compare_digest", spy)
    root, port = served
    path = root / "a.dcm"
    _write_export(path)
    assert _post(port, {"path": str(path)}, token="wrong")[0] == 401
    assert calls, "the token was not checked with hmac.compare_digest"
    calls.clear()
    assert _post(port, {"path": str(path)})[0] == 200
    assert (TOKEN.encode(), TOKEN.encode()) in calls


def test_a_non_ascii_token_is_refused_not_crashed(served):
    root, port = served
    path = root / "a.dcm"
    _write_export(path)
    conn = http.client.HTTPConnection("127.0.0.1", port, timeout=10)
    raw = json.dumps({"path": str(path)}).encode("utf-8")
    conn.putrequest("POST", "/describe")
    conn.putheader("Content-Type", "application/json")
    conn.putheader("Content-Length", str(len(raw)))
    conn.putheader(describe.TOKEN_HEADER, "s\xe9cret".encode("latin-1"))
    conn.endheaders(raw)
    resp = conn.getresponse()
    assert resp.status == 401
    conn.close()


def test_token_ok_edge_cases():
    assert describe.token_ok(TOKEN, TOKEN)
    assert not describe.token_ok(None, TOKEN)
    assert not describe.token_ok("", TOKEN)
    assert not describe.token_ok(TOKEN, "")
    assert not describe.token_ok("", "")
    assert not describe.token_ok("s\xe9cret", TOKEN)
