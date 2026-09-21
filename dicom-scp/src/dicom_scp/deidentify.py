"""Pseudonymise a Part-10 file in place (DR-029).

A camera that sits in the clinic fills the patient module from the hospital
system: the real name, the hospital ID, the date of birth, the operator who
took the picture. The Optomed path never had this problem — its header carries
the label *we* put on the worklist — but a Clarus or PlexElite export dropped
on the upload page does, and the file it arrives in is the file the export
bundle later hands to a researcher.

So the stored file is rewritten before the app records it: the identity is
replaced by the study's subject label (or blanked when the upload is not yet
filed), staff and institution are cleared, and the DICOM de-identification
attributes are stamped so a downstream reader can tell. What stays: the UIDs
(dedup and provenance), the dates (the clinical timeline), the device and its
private tags (calibration the research needs — dropped only on request), and
the pixels, which are not decoded, let alone transcoded.

The tag set is the patient-identity subset of PS3.15 E.1 (Basic Application
Level Confidentiality Profile) with the "retain longitudinal temporal
information" and "retain device identity" options; it is deliberately a list
rather than a profile engine, so what leaves and what stays is legible here.
"""
from __future__ import annotations

import logging
import os
from pathlib import Path

import pydicom
from pydicom.datadict import tag_for_keyword
from pydicom.dataset import Dataset

LOG = logging.getLogger("dicom_scp.deidentify")

#: What the file says about who was imaged — replaced by the pseudonym.
IDENTITY = ("PatientName", "PatientID")

#: Kept present but emptied: demographics beyond sex, contact details, the
#: people and the institution involved, and the hospital's own bookkeeping
#: numbers. Keywords a given pydicom does not know are skipped, never guessed.
CLEARED = (
    "PatientBirthDate",
    "PatientBirthTime",
    "PatientAge",
    "OtherPatientIDs",
    "OtherPatientNames",
    "PatientBirthName",
    "PatientMotherBirthName",
    "PatientAddress",
    "PatientTelephoneNumbers",
    "PatientTelecomInformation",
    "PatientComments",
    "IssuerOfPatientID",
    "EthnicGroup",
    "Occupation",
    "PatientInstitutionResidence",
    "MilitaryRank",
    "PatientReligiousPreference",
    "MedicalRecordLocator",
    "AdditionalPatientHistory",
    "ReferringPhysicianName",
    "PerformingPhysicianName",
    "OperatorsName",
    "PhysiciansOfRecord",
    "NameOfPhysiciansReadingStudy",
    "RequestingPhysician",
    "InstitutionName",
    "InstitutionAddress",
    "InstitutionalDepartmentName",
    "AccessionNumber",
    "StudyID",
)

#: Sequences that only ever carry identity — removed outright.
REMOVED = (
    "OtherPatientIDsSequence",
    "RequestAttributesSequence",
    "ReferencedPatientSequence",
)

DEIDENTIFICATION_METHOD = "LibreClinicaMUW ingest pseudonymisation v1"

#: Longest label the app stores; anything beyond it is not a subject label.
MAX_PSEUDONYM = 64


def _clean_pseudonym(raw: str | None) -> str:
    if raw is None:
        return ""
    s = str(raw).strip()
    if len(s) > MAX_PSEUDONYM:
        s = s[:MAX_PSEUDONYM]
    # A PN/LO value must not carry control characters or the component
    # separators; a label never legitimately does.
    return "".join(c for c in s if c.isprintable() and c not in "^=\\")


def pseudonymise(ds: Dataset, pseudonym: str | None, drop_private: bool = False) -> list[str]:
    """Rewrite the identity attributes of ``ds`` in memory.

    Returns the keywords whose value actually changed, so a caller can tell an
    already-clean file from one that carried a patient. The de-identification
    stamp itself is not counted.
    """
    label = _clean_pseudonym(pseudonym)
    changed: list[str] = []

    for kw in IDENTITY:
        if _set(ds, kw, label):
            changed.append(kw)
    for kw in CLEARED:
        tag = tag_for_keyword(kw)
        if tag is None or tag not in ds:
            continue
        if _set(ds, kw, ""):
            changed.append(kw)
    for kw in REMOVED:
        tag = tag_for_keyword(kw)
        if tag is not None and tag in ds:
            del ds[tag]
            changed.append(kw)
    if drop_private:
        before = len(ds)
        ds.remove_private_tags()
        if len(ds) != before:
            changed.append("PrivateTags")

    # PS3.15: say that identity was removed, and how. Type 3 in most IODs, so
    # adding them never invalidates the object.
    ds.PatientIdentityRemoved = "YES"
    ds.DeidentificationMethod = DEIDENTIFICATION_METHOD
    return changed


def _set(ds: Dataset, keyword: str, value: str) -> bool:
    """Assign, reporting whether the stored value differs from the new one."""
    tag = tag_for_keyword(keyword)
    if tag is None:
        return False
    old = str(ds[tag].value).strip() if tag in ds else None
    setattr(ds, keyword, value)
    if old is None:
        # Absent before: adding an identity is a change, adding "" is not.
        return value != ""
    return old != value


def rewrite(path: Path, pseudonym: str | None, drop_private: bool = False) -> tuple[Dataset, list[str]]:
    """Pseudonymise the file at ``path`` in place.

    Reads the object, rewrites the identity attributes, and replaces the file
    atomically — a crash mid-write leaves the original, never a half file.
    Raises ``pydicom.errors.InvalidDicomError`` when the file is not DICOM.
    """
    ds = pydicom.dcmread(str(path))
    changed = pseudonymise(ds, pseudonym, drop_private)

    tmp = path.with_name(path.name + ".deid.tmp")
    try:
        # enforce_file_format=True writes a proper Part-10 (preamble + file
        # meta) with the transfer syntax the file came with: the pixel data is
        # copied through as stored, compressed or not.
        ds.save_as(str(tmp), enforce_file_format=True)
        os.replace(tmp, path)
    finally:
        try:
            tmp.unlink()
        except FileNotFoundError:
            pass
    # Counts only: the values are exactly what must not reach a log.
    LOG.info("pseudonymised an uploaded DICOM (%d attribute(s) changed)", len(changed))
    return ds, changed
