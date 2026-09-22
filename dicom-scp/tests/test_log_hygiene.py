"""The sidecar must never write patient-identifying DICOM values to the log.

The store is on the app VM and its logs are not PHI-classified storage, so a
single `LOG.info("... %s", ds.PatientName)` would turn every received image
into a disclosure. AE titles are exempt by design (DICOM-constrained, not
patient data) and are the only camera-provided string we do log.
"""
from __future__ import annotations

import logging

from pydicom.dataset import Dataset, FileMetaDataset
from pydicom.uid import ExplicitVRLittleEndian

from dicom_scp import config, server, store, worklist

SECRET_NAME = "Musterfrau^Erika"
SECRET_ID = "PID-SECRET-4711"


class _Requestor:
    def __init__(self, ae_title):
        self.ae_title = ae_title


class _Assoc:
    def __init__(self, ae_title):
        self.requestor = _Requestor(ae_title)


class _StoreEvent:
    def __init__(self, ds):
        self.dataset = ds
        self.file_meta = FileMetaDataset()
        self.file_meta.TransferSyntaxUID = ExplicitVRLittleEndian
        self.assoc = _Assoc("OPTOMEDLUMO")


class _FindEvent:
    def __init__(self, identifier):
        self.identifier = identifier
        self.assoc = _Assoc("OPTOMEDLUMO")
        self.is_cancelled = False


def _identifying_ds() -> Dataset:
    ds = Dataset()
    ds.SOPInstanceUID = "1.2.3.4.5"
    ds.SOPClassUID = "1.2.840.10008.5.1.4.1.1.77.1.5.1"
    ds.Modality = "OP"
    ds.PatientName = SECRET_NAME
    ds.PatientID = SECRET_ID
    ds.StudyDate = "20260917"
    ds.AccessionNumber = "LC116"
    return ds


def test_successful_store_does_not_log_patient_identity(monkeypatch, caplog):
    monkeypatch.setattr(store, "persist", lambda *a, **k: ("/store/a.dcm", "/store/a.png"))
    monkeypatch.setattr(server, "post_ingest", lambda *a, **k: True)
    with caplog.at_level(logging.INFO, logger="dicom_scp"):
        assert server._handle_store(_StoreEvent(_identifying_ds())) == 0x0000
    assert SECRET_NAME not in caplog.text
    assert SECRET_ID not in caplog.text
    # The AE title is deliberately logged — it is how an operator identifies the device.
    assert "OPTOMEDLUMO" in caplog.text


def test_failed_store_does_not_log_patient_identity(monkeypatch, caplog):
    def boom(*a, **k):
        raise OSError(f"cannot write file for {SECRET_ID}")

    monkeypatch.setattr(store, "persist", boom)
    with caplog.at_level(logging.DEBUG, logger="dicom_scp"):
        assert server._handle_store(_StoreEvent(_identifying_ds())) == 0xA700
    # The exception message itself carries the id — only its TYPE may be logged.
    assert SECRET_ID not in caplog.text
    assert "OSError" in caplog.text


def test_worklist_find_does_not_log_subject_labels(monkeypatch, caplog):
    monkeypatch.setattr(config.settings, "worklist_url", "http://app/worklist")
    monkeypatch.setattr(worklist, "fetch_entries", lambda *a, **k: [{
        "studyEventId": 116, "subjectLabel": SECRET_ID, "gender": "f",
        "dateOfBirth": "1962-01-01", "date": "2026-09-17", "time": None,
        "eventLabel": "Baseline", "studyName": "HealthAEye", "modality": "OP",
    }])
    q = Dataset()
    sps = Dataset()
    sps.ScheduledProcedureStepStartDate = "20260917"
    q.ScheduledProcedureStepSequence = [sps]

    with caplog.at_level(logging.INFO, logger="dicom_scp"):
        results = list(server._handle_find(_FindEvent(q)))

    assert len(results) == 1 and results[0][0] == 0xFF00
    assert SECRET_ID not in caplog.text, "worklist logging must report counts, not labels"


def test_worklist_fetch_failure_logs_only_the_error_type(monkeypatch, caplog):
    monkeypatch.setattr(config.settings, "worklist_url", "http://app/worklist")

    def boom(*a, **k):
        raise ConnectionError(f"failed resolving host for {SECRET_ID}")

    monkeypatch.setattr(worklist, "fetch_entries", boom)
    q = Dataset()
    with caplog.at_level(logging.DEBUG, logger="dicom_scp"):
        list(server._handle_find(_FindEvent(q)))
    assert SECRET_ID not in caplog.text
    assert "ConnectionError" in caplog.text
