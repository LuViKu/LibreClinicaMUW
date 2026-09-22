#!/usr/bin/env python3
"""DR-025 — the camera path, over a real DICOM association, in CI.

The sidecar's unit tests cover its handlers and the application's integration
tests cover the worklist and ingest endpoints. Neither exercises the wire: an
association negotiated between two processes, a presentation context accepted,
a dataset encoded and parsed. That is precisely where the Optomed Lumo failed
in the field — the sidecar advertised only uncompressed transfer syntaxes and
rejected every image the camera sent, and nothing in the test suite could have
noticed.

Runs inside the sidecar's own container, so it needs no DICOM library on the
runner and talks to the service over the compose network.

Sequence, in the order a camera performs it:

  1. C-ECHO            the device's "test connection"
  2. MWL C-FIND        the worklist the operator picks a patient from
  3. C-STORE           the image, carrying the accession the worklist gave

Then the database is checked: the stored image must be bound to the visit.

Environment:
  DICOM_HOST / DICOM_PORT   where the sidecar listens (default 127.0.0.1:11112)
  CALLING_AE                AE title to present (must be on the allow-list)
  STATION_AE                scheduled station AE for the C-FIND
  ACCESSION                 the accession to store; "LC<study_event_id>" binds

Exit: 0 every exchange succeeded, 1 otherwise.
"""
from __future__ import annotations

import datetime as dt
import os
import sys

from pydicom.dataset import Dataset, FileMetaDataset
from pydicom.uid import ExplicitVRLittleEndian, generate_uid
from pynetdicom import AE
from pynetdicom.sop_class import (
    ModalityWorklistInformationFind,
    OphthalmicPhotography8BitImageStorage,
    Verification,
)

HOST = os.environ.get("DICOM_HOST", "127.0.0.1")
PORT = int(os.environ.get("DICOM_PORT", "11112"))
CALLING_AE = os.environ.get("CALLING_AE", "OPTOMEDLUMO")
STATION_AE = os.environ.get("STATION_AE", "LUMO")
ACCESSION = os.environ.get("ACCESSION", "")

failures: list[str] = []


def ok(msg: str) -> None:
    print(f"  ok    {msg}", flush=True)


def bad(msg: str) -> None:
    print(f"  FAIL  {msg}", flush=True)
    failures.append(msg)


def c_echo() -> None:
    ae = AE(ae_title=CALLING_AE)
    ae.add_requested_context(Verification)
    assoc = ae.associate(HOST, PORT)
    if not assoc.is_established:
        bad("C-ECHO: no association (is the sidecar up, and is our AE allowed?)")
        return
    try:
        status = assoc.send_c_echo()
        if status and status.Status == 0x0000:
            ok("C-ECHO answered")
        else:
            bad(f"C-ECHO returned {status}")
    finally:
        assoc.release()


def c_find(today: str) -> list[str]:
    """Returns the accessions the worklist offered."""
    ae = AE(ae_title=CALLING_AE)
    ae.add_requested_context(ModalityWorklistInformationFind)
    assoc = ae.associate(HOST, PORT)
    if not assoc.is_established:
        bad("MWL C-FIND: no association")
        return []

    query = Dataset()
    query.PatientName = ""
    query.PatientID = ""
    query.AccessionNumber = ""
    step = Dataset()
    step.ScheduledStationAETitle = STATION_AE
    step.ScheduledProcedureStepStartDate = today
    step.Modality = ""
    query.ScheduledProcedureStepSequence = [step]

    accessions: list[str] = []
    try:
        for status, identifier in assoc.send_c_find(query, ModalityWorklistInformationFind):
            if status and status.Status in (0xFF00, 0xFF01) and identifier is not None:
                accessions.append(getattr(identifier, "AccessionNumber", "") or "")
    except Exception as exc:  # noqa: BLE001 - the failure itself is the result
        bad(f"MWL C-FIND raised {type(exc).__name__}: {exc}")
        return []
    finally:
        assoc.release()

    ok(f"MWL C-FIND answered with {len(accessions)} item(s)")
    return accessions


def c_store(today: str) -> str:
    """Stores a tiny synthetic image. Returns its SOP Instance UID, or ''."""
    ds = Dataset()
    ds.file_meta = FileMetaDataset()
    ds.file_meta.MediaStorageSOPClassUID = OphthalmicPhotography8BitImageStorage
    ds.file_meta.MediaStorageSOPInstanceUID = generate_uid()
    ds.file_meta.TransferSyntaxUID = ExplicitVRLittleEndian
    ds.SOPClassUID = OphthalmicPhotography8BitImageStorage
    ds.SOPInstanceUID = ds.file_meta.MediaStorageSOPInstanceUID
    ds.StudyInstanceUID = generate_uid()
    ds.SeriesInstanceUID = generate_uid()
    # Synthetic identity: this image describes nobody.
    ds.PatientName = "CISMOKE^DICOM"
    ds.PatientID = "CISMOKE"
    ds.AccessionNumber = ACCESSION or "LC0"
    ds.Modality = "OP"
    ds.StudyDate = today
    ds.Laterality = "R"
    ds.SamplesPerPixel = 1
    ds.PhotometricInterpretation = "MONOCHROME2"
    ds.Rows = 2
    ds.Columns = 2
    ds.BitsAllocated = 8
    ds.BitsStored = 8
    ds.HighBit = 7
    ds.PixelRepresentation = 0
    ds.PixelData = b"\x40\x80\x40\x80"
    ds.is_little_endian = True
    ds.is_implicit_VR = False

    ae = AE(ae_title=CALLING_AE)
    ae.add_requested_context(OphthalmicPhotography8BitImageStorage, ExplicitVRLittleEndian)
    assoc = ae.associate(HOST, PORT)
    if not assoc.is_established:
        bad("C-STORE: no association")
        return ""
    try:
        status = assoc.send_c_store(ds)
    finally:
        assoc.release()

    if status and status.Status == 0x0000:
        ok("C-STORE accepted")
        return str(ds.SOPInstanceUID)
    bad(f"C-STORE returned {status}")
    return ""


def main() -> int:
    today = dt.date.today().strftime("%Y%m%d")
    print(f"DICOM smoke — {CALLING_AE} → {HOST}:{PORT}", flush=True)

    c_echo()
    accessions = c_find(today)
    if ACCESSION:
        if ACCESSION in accessions:
            ok(f"the worklist offered {ACCESSION}")
        else:
            bad(f"the worklist did not offer {ACCESSION} (got {accessions})")

    sop_uid = c_store(today)
    # The database assertion runs on the runner, which has psql; print the UID
    # so the workflow step can look the row up.
    if sop_uid:
        print(f"SOP_INSTANCE_UID={sop_uid}", flush=True)

    print(f"\n{3 + (1 if ACCESSION else 0) - len(failures)} passed, {len(failures)} failed",
          flush=True)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
