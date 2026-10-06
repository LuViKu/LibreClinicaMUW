"""Check that a Part-10 file is already de-identified, without touching it.

``deidentify.rewrite`` fixes a file; this refuses one. On a deployment that
requires de-identification (``libreclinica.ingest.deidentification.required``)
the app must not rely on the browser having stripped the file, and must not
rely on its own in-place rewrite either: it asks here, BEFORE anything is
modified, and rejects the upload when the answer is not a clean bill. The
in-place rewrite then still runs, as a second line.

What counts as clean:

* ``PatientName`` / ``PatientID`` are empty or equal the pseudonym (the study's
  subject label) — never anything else;
* every tag ``deidentify.CLEARED`` clears, every tag in ``STRICT_CLEARED``, and
  the identity-only sequences ``deidentify.REMOVED`` are empty or absent;
* no private tag (odd group) anywhere, nested sequences included;
* ``BurnedInAnnotation`` is not ``YES`` — pixels showing a name cannot be
  repaired by rewriting tags;
* ``PatientIdentityRemoved`` is ``YES``.

A violation is a DICOM keyword or a rule name. Values are what must never reach
a log or a response, so none is ever returned.
"""
from __future__ import annotations

import logging
from pathlib import Path

import pydicom
from pydicom.datadict import tag_for_keyword
from pydicom.dataset import Dataset
from pydicom.multival import MultiValue

from . import deidentify

LOG = logging.getLogger("dicom_scp.verify")

#: Beyond ``deidentify.CLEARED``; must be empty or absent in an upload.
STRICT_CLEARED = deidentify.STRICT_CLEARED

PRIVATE_TAGS = "PrivateTags"
BURNED_IN = "BurnedInAnnotation"
IDENTITY_REMOVED = "PatientIdentityRemoved"


def _is_empty(elem) -> bool:
    value = elem.value
    if value is None:
        return True
    if elem.VR == "SQ":
        return len(value) == 0
    if isinstance(value, (bytes, bytearray)):
        return all(b in (0, 0x20) for b in value)
    if isinstance(value, MultiValue):
        return all(_blank(v) for v in value)
    return _blank(value)


def _blank(v) -> bool:
    # "^^^^" is an empty person name; nothing else is let through as empty.
    return str(v).replace("^", "").strip() == ""


def _text(elem) -> str:
    return str(elem.value).strip() if elem.value is not None else ""


def _has_private(ds: Dataset) -> bool:
    for elem in ds:
        if elem.tag.group % 2 == 1:
            return True
        if elem.VR == "SQ":
            for item in elem.value or []:
                if _has_private(item):
                    return True
    return False


def verify(ds: Dataset, pseudonym: str | None) -> list[str]:
    """The names of the rules ``ds`` breaks; empty when it is clean."""
    label = deidentify._clean_pseudonym(pseudonym)
    violations: list[str] = []

    for kw in deidentify.IDENTITY:
        tag = tag_for_keyword(kw)
        if tag is None or tag not in ds:
            continue
        text = _text(ds[tag])
        if text != "" and text != label:
            violations.append(kw)

    for kw in deidentify.CLEARED + STRICT_CLEARED:
        tag = tag_for_keyword(kw)
        if tag is None or tag not in ds:
            continue
        if not _is_empty(ds[tag]):
            violations.append(kw)

    for kw in deidentify.REMOVED:
        tag = tag_for_keyword(kw)
        if tag is not None and tag in ds and not _is_empty(ds[tag]):
            violations.append(kw)

    if _has_private(ds):
        violations.append(PRIVATE_TAGS)

    burned = tag_for_keyword(BURNED_IN)
    if burned is not None and burned in ds and _text(ds[burned]).upper() == "YES":
        violations.append(BURNED_IN)

    removed = tag_for_keyword(IDENTITY_REMOVED)
    if removed is None or removed not in ds or _text(ds[removed]).upper() != "YES":
        violations.append(IDENTITY_REMOVED)

    # Deduplicated, in the order found.
    return list(dict.fromkeys(violations))


def verify_file(path: Path, pseudonym: str | None) -> dict:
    """Read-only. Raises ``InvalidDicomError`` when the file is not DICOM."""
    ds = pydicom.dcmread(str(path), stop_before_pixels=True)
    violations = verify(ds, pseudonym)
    # Counts only: even a keyword says what the file carried.
    LOG.info("verified an uploaded DICOM (%d violation(s))", len(violations))
    return {"ok": not violations, "violations": violations}
