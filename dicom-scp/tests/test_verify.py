"""The verify route and the strict rewrite: a file is refused BEFORE it is changed,
and never says what it carried (layer 2 and 3 of the de-identification check)."""
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
from pydicom.sequence import Sequence
from pydicom.uid import ExplicitVRLittleEndian, generate_uid

from dicom_scp import config, deidentify, describe, verify

OPHTHALMIC_PHOTO_8BIT = "1.2.840.10008.5.1.4.1.1.77.1.5.1"
TOKEN = "test-token"
LABEL = "HAE-001"


def _clean_dataset(name: str = LABEL, pid: str = LABEL) -> Dataset:
    sop = generate_uid()
    ds = Dataset()
    ds.SOPInstanceUID = sop
    ds.SOPClassUID = OPHTHALMIC_PHOTO_8BIT
    ds.StudyInstanceUID = generate_uid()
    ds.SeriesInstanceUID = generate_uid()
    ds.Modality = "OP"
    ds.PatientName = name
    ds.PatientID = pid
    ds.PatientIdentityRemoved = "YES"
    ds.StudyDate = "20260918"
    ds.Manufacturer = "Carl Zeiss Meditec"
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
    return ds


def _save(ds: Dataset, path: Path) -> Path:
    ds.save_as(str(path), enforce_file_format=True)
    return path


# --- the rules, on a dataset ---------------------------------------------------

def test_a_clean_dataset_passes():
    assert verify.verify(_clean_dataset(), LABEL) == []


def test_an_empty_identity_passes_with_or_without_a_pseudonym():
    ds = _clean_dataset(name="", pid="")
    assert verify.verify(ds, LABEL) == []
    assert verify.verify(ds, None) == []


def test_another_name_or_id_than_the_pseudonym_is_a_violation():
    ds = _clean_dataset(name="Muster^Max", pid="0012345678")
    assert verify.verify(ds, LABEL) == ["PatientName", "PatientID"]


def test_the_label_is_a_violation_when_the_upload_has_no_pseudonym():
    assert verify.verify(_clean_dataset(), None) == ["PatientName", "PatientID"]


@pytest.mark.parametrize("keyword,value", [
    ("PatientBirthDate", "19500101"),
    ("PatientAddress", "Musterstrasse 1"),
    ("ReferringPhysicianName", "Doktor^Doris"),
    ("InstitutionName", "AKH Wien"),
    ("AccessionNumber", "A123"),
    ("StudyID", "S1"),
    ("PatientSex", "M"),
    ("PatientSize", "1.8"),
    ("PatientWeight", "80"),
    ("PatientBirthName", "Geboren^Max"),
    ("StudyDescription", "Max Muster OCT"),
    ("SeriesDescription", "OCT OD"),
    ("ImageComments", "patient says hi"),
    ("PerformedProcedureStepDescription", "x"),
    ("RequestedProcedureDescription", "x"),
    ("ProtocolName", "x"),
    ("DeviceSerialNumber", "1234"),
    ("StationName", "AKH-CLARUS-3"),
    ("InstitutionalDepartmentName", "Augen"),
    ("OperatorsName", "Schwester^Susi"),
])
def test_each_cleared_or_strict_tag_with_a_value_is_a_violation(keyword, value):
    ds = _clean_dataset()
    setattr(ds, keyword, value)
    assert verify.verify(ds, LABEL) == [keyword]


def test_those_tags_empty_or_absent_are_fine():
    ds = _clean_dataset()
    ds.PatientSex = ""
    ds.OperatorsName = ""
    ds.PatientBirthDate = ""
    assert verify.verify(ds, LABEL) == []


def test_an_identity_only_sequence_with_content_is_a_violation():
    ds = _clean_dataset()
    inner = Dataset()
    inner.PatientID = "0012345678"
    ds.OtherPatientIDsSequence = Sequence([inner])
    assert "OtherPatientIDsSequence" in verify.verify(ds, LABEL)


def test_a_private_tag_at_the_top_level_is_a_violation():
    ds = _clean_dataset()
    ds.add_new(0x00090010, "LO", "VENDOR")
    ds.add_new(0x00091001, "LO", "calibration")
    assert verify.verify(ds, LABEL) == [verify.PRIVATE_TAGS]


def test_a_private_tag_inside_a_nested_sequence_is_a_violation():
    ds = _clean_dataset()
    deep = Dataset()
    deep.add_new(0x00110010, "LO", "VENDOR")
    mid = Dataset()
    mid.ReferencedSOPInstanceUID = generate_uid()
    mid.ReferencedImageSequence = Sequence([deep])
    ds.SourceImageSequence = Sequence([mid])
    assert verify.verify(ds, LABEL) == [verify.PRIVATE_TAGS]


def test_burned_in_annotation_yes_is_a_violation():
    ds = _clean_dataset()
    ds.BurnedInAnnotation = "YES"
    assert verify.verify(ds, LABEL) == [verify.BURNED_IN]
    ds.BurnedInAnnotation = "NO"
    assert verify.verify(ds, LABEL) == []


@pytest.mark.parametrize("value", ["NO", ""])
def test_patient_identity_removed_must_be_yes(value):
    ds = _clean_dataset()
    ds.PatientIdentityRemoved = value
    assert verify.verify(ds, LABEL) == [verify.IDENTITY_REMOVED]


def test_missing_patient_identity_removed_is_a_violation():
    ds = _clean_dataset()
    del ds.PatientIdentityRemoved
    assert verify.verify(ds, LABEL) == [verify.IDENTITY_REMOVED]


def test_violations_name_rules_never_values():
    ds = _clean_dataset(name="Muster^Max", pid="0012345678")
    ds.PatientBirthDate = "19500101"
    ds.StudyDescription = "Max Muster"
    blob = json.dumps(verify.verify(ds, LABEL))
    for secret in ("Muster", "0012345678", "19500101", "Max"):
        assert secret not in blob


# --- strict rewrite -----------------------------------------------------------

def test_strict_rewrite_clears_the_extended_list_and_drops_private_tags(tmp_path):
    ds = _clean_dataset(name="Muster^Max", pid="0012345678")
    ds.PatientSex = "M"
    ds.StationName = "AKH-CLARUS-3"
    ds.StudyDescription = "Max Muster"
    ds.add_new(0x00091001, "LO", "calibration")
    path = _save(ds, tmp_path / "a.dcm")

    deidentify.rewrite(path, LABEL, drop_private=False, strict=True)

    back = pydicom.dcmread(str(path))
    assert verify.verify(back, LABEL) == []
    assert back.PatientSex == "" and back.StationName == ""


def test_a_plain_rewrite_keeps_the_extended_list(tmp_path):
    ds = _clean_dataset()
    ds.StationName = "AKH-CLARUS-3"
    path = _save(ds, tmp_path / "a.dcm")
    deidentify.rewrite(path, LABEL)
    assert pydicom.dcmread(str(path)).StationName == "AKH-CLARUS-3"


# --- the HTTP route -----------------------------------------------------------

@pytest.fixture
def served(tmp_path, monkeypatch):
    root = tmp_path / "store"
    root.mkdir()
    monkeypatch.setattr(config.settings, "ingest_token", TOKEN)
    monkeypatch.setattr(config.settings, "describe_roots", str(root))
    monkeypatch.setattr(config.settings, "deidentify_drop_private", False)
    monkeypatch.setattr(config.settings, "deidentify_strict", False)
    server = ThreadingHTTPServer(("127.0.0.1", 0), describe.DescribeHandler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield root, server.server_address[1]
    finally:
        server.shutdown()
        server.server_close()


def _post(port: int, body: dict | str, token: str | None = TOKEN, path: str = "/verify"):
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


def test_verify_answers_ok_for_a_clean_file(served):
    root, port = served
    path = _save(_clean_dataset(), root / "clean.dcm")
    status, payload = _post(port, {"path": str(path), "pseudonym": LABEL})
    assert status == 200
    assert payload == {"ok": True, "violations": []}


def test_verify_lists_rule_names_and_leaves_the_file_untouched(served):
    root, port = served
    ds = _clean_dataset(name="Muster^Max", pid="0012345678")
    ds.PatientBirthDate = "19500101"
    path = _save(ds, root / "dirty.dcm")
    before = path.read_bytes()

    status, payload = _post(port, {"path": str(path), "pseudonym": LABEL})

    assert status == 200
    assert payload["ok"] is False
    assert payload["violations"] == ["PatientName", "PatientID", "PatientBirthDate"]
    assert "Muster" not in json.dumps(payload) and "19500101" not in json.dumps(payload)
    assert path.read_bytes() == before, "verify is read-only: the file is refused before it is modified"


def test_verify_is_token_gated_and_confined(served, tmp_path):
    root, port = served
    path = _save(_clean_dataset(), root / "clean.dcm")
    assert _post(port, {"path": str(path)}, token=None)[0] == 401
    assert _post(port, {"path": str(path)}, token="wrong")[0] == 401
    outside = _save(_clean_dataset(), tmp_path / "outside.dcm")
    assert _post(port, {"path": str(outside)})[0] == 403


def test_verify_says_422_for_a_non_dicom_file(served):
    root, port = served
    path = root / "x.dcm"
    path.write_bytes(b"not dicom at all" * 20)
    assert _post(port, {"path": str(path)})[0] == 422


def test_describe_strict_flag_drops_private_tags_and_clears_the_extended_list(served):
    root, port = served
    ds = _clean_dataset(name="Muster^Max", pid="0012345678")
    ds.StationName = "AKH-CLARUS-3"
    ds.add_new(0x00091001, "LO", "calibration")
    path = _save(ds, root / "up.dcm")

    status, _ = _post(port, {"path": str(path), "pseudonym": LABEL, "strict": True}, path="/describe")

    assert status == 200
    assert verify.verify(pydicom.dcmread(str(path)), LABEL) == []


def test_describe_without_the_flag_keeps_private_tags(served):
    root, port = served
    ds = _clean_dataset()
    ds.add_new(0x00091001, "LO", "calibration")
    path = _save(ds, root / "up.dcm")
    assert _post(port, {"path": str(path), "pseudonym": LABEL}, path="/describe")[0] == 200
    assert verify.PRIVATE_TAGS in verify.verify(pydicom.dcmread(str(path)), LABEL)


def test_the_sidecar_can_be_pinned_strict_by_its_environment(served, monkeypatch):
    root, port = served
    monkeypatch.setattr(config.settings, "deidentify_strict", True)
    ds = _clean_dataset()
    ds.add_new(0x00091001, "LO", "calibration")
    path = _save(ds, root / "up.dcm")
    assert _post(port, {"path": str(path), "pseudonym": LABEL}, path="/describe")[0] == 200
    assert verify.verify(pydicom.dcmread(str(path)), LABEL) == []


def test_strict_must_be_a_boolean(served):
    root, port = served
    path = _save(_clean_dataset(), root / "up.dcm")
    assert _post(port, {"path": str(path), "strict": "yes"}, path="/describe")[0] == 400


def test_verify_logs_counts_only(served, caplog):
    root, port = served
    ds = _clean_dataset(name="Muster^Max", pid="SECRET-4711")
    path = _save(ds, root / "dirty.dcm")
    with caplog.at_level(logging.DEBUG):
        _post(port, {"path": str(path), "pseudonym": LABEL})
    text = caplog.text
    assert "Muster" not in text and "SECRET-4711" not in text and "dirty.dcm" not in text
