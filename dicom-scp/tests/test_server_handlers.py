"""Handler-level tests for the SCP event callbacks (DR-025).

The existing suite covers the pure helpers (`tags`, `store`, `worklist`); this
module covers the two handlers a real modality actually drives — C-STORE and
Modality Worklist C-FIND — plus the presentation contexts we offer. Both
handlers are called directly with a stand-in event object, so no sockets or
associations are involved.
"""
from __future__ import annotations

import pytest
from pydicom.dataset import Dataset, FileMetaDataset
from pydicom.uid import ExplicitVRLittleEndian, JPEGBaseline8Bit

from dicom_scp import config, server, store, worklist

OPHTHALMIC_PHOTO_8BIT = "1.2.840.10008.5.1.4.1.1.77.1.5.1"
MWL_FIND = "1.2.840.10008.5.1.4.31"


# --- stand-in event objects ------------------------------------------------

class _Requestor:
    def __init__(self, ae_title: str) -> None:
        self.ae_title = ae_title


class _Assoc:
    def __init__(self, ae_title: str) -> None:
        self.requestor = _Requestor(ae_title)


class _StoreEvent:
    def __init__(self, ds: Dataset, ae_title: str = "OPTOMEDLUMO") -> None:
        self.dataset = ds
        self.file_meta = FileMetaDataset()
        self.file_meta.TransferSyntaxUID = ExplicitVRLittleEndian
        self.assoc = _Assoc(ae_title)


class _FindEvent:
    def __init__(self, identifier: Dataset, ae_title: str = "OPTOMEDLUMO",
                 cancelled: bool = False) -> None:
        self.identifier = identifier
        self.assoc = _Assoc(ae_title)
        self.is_cancelled = cancelled


@pytest.fixture
def image_ds() -> Dataset:
    ds = Dataset()
    ds.SOPInstanceUID = "1.2.3.4.5"
    ds.SOPClassUID = OPHTHALMIC_PHOTO_8BIT
    ds.Modality = "OP"
    ds.PatientID = "HAE-001"
    ds.StudyDate = "20260917"
    ds.ImageLaterality = "R"
    ds.AccessionNumber = "LC116"
    return ds


@pytest.fixture
def find_query() -> Dataset:
    q = Dataset()
    q.PatientID = ""
    sps = Dataset()
    sps.Modality = "OP"
    sps.ScheduledStationAETitle = "LUMO"
    sps.ScheduledProcedureStepStartDate = "20260917"
    q.ScheduledProcedureStepSequence = [sps]
    return q


def _entry(study_event_id: int = 116, label: str = "HAE-002") -> dict:
    return {
        "studyEventId": study_event_id,
        "subjectLabel": label,
        "gender": "f",
        "dateOfBirth": "1962-01-01",
        "date": "2026-09-17",
        "time": None,
        "eventLabel": "Baseline",
        "studyName": "HealthAEye",
        "modality": "OP",
    }


# --- C-STORE ---------------------------------------------------------------

def test_store_persists_then_hands_off_and_succeeds(monkeypatch, image_ds):
    posted = {}
    monkeypatch.setattr(store, "persist", lambda *a, **k: ("/store/a.dcm", "/store/a.png"))

    def fake_post(url, token, payload, timeout):
        posted.update(payload)
        return True

    monkeypatch.setattr(server, "post_ingest", fake_post)

    assert server._handle_store(_StoreEvent(image_ds)) == 0x0000
    assert posted["sopInstanceUid"] == "1.2.3.4.5"
    assert posted["accessionNumber"] == "LC116"
    assert posted["laterality"] == "OD"
    assert posted["sourceAeTitle"] == "OPTOMEDLUMO"
    # The shared-volume paths the app records are the ones `store.persist` returned.
    assert posted["dicomPath"] == "/store/a.dcm"
    assert posted["previewPngPath"] == "/store/a.png"


def test_store_ingest_failure_is_reported_so_the_modality_retries(monkeypatch, image_ds):
    monkeypatch.setattr(store, "persist", lambda *a, **k: ("/store/a.dcm", None))
    monkeypatch.setattr(server, "post_ingest", lambda *a, **k: False)
    # 0xA700 = out of resources: the device keeps the object and re-sends later.
    assert server._handle_store(_StoreEvent(image_ds)) == 0xA700


def test_store_persist_failure_refuses_the_object(monkeypatch, image_ds):
    def boom(*a, **k):
        raise OSError("disk full")

    monkeypatch.setattr(store, "persist", boom)
    monkeypatch.setattr(server, "post_ingest", lambda *a, **k: True)
    assert server._handle_store(_StoreEvent(image_ds)) == 0xA700


# --- Modality Worklist C-FIND ---------------------------------------------

def test_find_without_worklist_url_answers_failure(monkeypatch, find_query):
    monkeypatch.setattr(config.settings, "worklist_url", "")
    assert list(server._handle_find(_FindEvent(find_query))) == [(0xC000, None)]


def test_find_fetch_error_answers_failure_instead_of_crashing(monkeypatch, find_query):
    monkeypatch.setattr(config.settings, "worklist_url", "http://app/worklist")

    def boom(*a, **k):
        raise ConnectionError("app is down")

    monkeypatch.setattr(worklist, "fetch_entries", boom)
    assert list(server._handle_find(_FindEvent(find_query))) == [(0xC000, None)]


def test_find_yields_one_pending_response_per_match(monkeypatch, find_query):
    monkeypatch.setattr(config.settings, "worklist_url", "http://app/worklist")
    monkeypatch.setattr(worklist, "fetch_entries",
                        lambda *a, **k: [_entry(116, "HAE-002"), _entry(117, "HAE-003")])

    results = list(server._handle_find(_FindEvent(find_query)))
    assert [status for status, _ in results] == [0xFF00, 0xFF00]
    first = results[0][1]
    assert first.PatientID == "HAE-002"
    assert first.AccessionNumber == "LC116"
    # The station AE echoed back is the requester's own, so a station-filtered
    # query on the device always sees its items.
    assert first.ScheduledProcedureStepSequence[0].ScheduledStationAETitle == "OPTOMEDLUMO"


def test_find_honours_cancel(monkeypatch, find_query):
    monkeypatch.setattr(config.settings, "worklist_url", "http://app/worklist")
    monkeypatch.setattr(worklist, "fetch_entries", lambda *a, **k: [_entry(), _entry(117)])
    assert list(server._handle_find(_FindEvent(find_query, cancelled=True))) == [(0xFE00, None)]


def test_find_passes_the_requested_window_to_the_app(monkeypatch, find_query):
    seen = {}
    monkeypatch.setattr(config.settings, "worklist_url", "http://app/worklist")

    def fake_fetch(url, token, day_from, day_to, timeout):
        seen["range"] = (day_from, day_to)
        return []

    monkeypatch.setattr(worklist, "fetch_entries", fake_fetch)
    list(server._handle_find(_FindEvent(find_query)))
    assert seen["range"] == ("2026-09-17", "2026-09-17")


# --- presentation contexts -------------------------------------------------

def test_storage_contexts_accept_compressed_transfer_syntaxes():
    """Regression guard for the 2026-09-17 live finding: pynetdicom's default
    storage contexts are uncompressed-only, so the Lumo's JPEG Baseline
    C-STORE was rejected with 'Transfer Syntax Not Supported'."""
    ae = server.build_ae()
    ctx = [c for c in ae.supported_contexts if c.abstract_syntax == OPHTHALMIC_PHOTO_8BIT]
    assert ctx, "Ophthalmic Photography 8 Bit Image Storage must be offered"
    assert JPEGBaseline8Bit in ctx[0].transfer_syntax


def test_worklist_and_verification_contexts_are_offered():
    ae = server.build_ae()
    offered = {c.abstract_syntax for c in ae.supported_contexts}
    assert MWL_FIND in offered, "the Lumo pulls a worklist before it stores"
    assert "1.2.840.10008.1.1" in offered, "C-ECHO backs the device's connection test"
