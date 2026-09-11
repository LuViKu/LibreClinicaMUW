"""Modality Worklist (C-FIND) support — DR-025.

The Optomed Lumo's standard-DICOM integration is worklist-driven: the camera
pulls a Modality Worklist to pick the scheduled patient/exam, then C-STOREs the
study carrying that identity. We serve the worklist from LibreClinica's
scheduled visits (fetched from the app's token-gated internal endpoint) and
issue a deterministic StudyInstanceUID + an accession (``LC<study_event_id>``)
per visit, so the study the camera sends back is auto-bound to that visit by
the ingest endpoint.

Identity is the EDC's pseudonymised subject label (PatientName == PatientID ==
label) — the EDC holds no real names.
"""
from __future__ import annotations

import logging
import re
from datetime import date

import requests
from pydicom.dataset import Dataset
from pydicom.uid import generate_uid

LOG = logging.getLogger("dicom_scp.worklist")

UID_ENTROPY_PREFIX = "libreclinica-muw-study-event-"
ACCESSION_PREFIX = "LC"
_ACCESSION_RE = re.compile(r"^LC(\d+)$")
_DA_RE = re.compile(r"^\d{8}$")


def study_instance_uid_for(study_event_id: int) -> str:
    """Deterministic per visit — the same visit always yields the same UID, so
    the study the camera sends correlates with the worklist item it picked."""
    return generate_uid(entropy_srcs=[f"{UID_ENTROPY_PREFIX}{study_event_id}"])


def accession_for(study_event_id: int) -> str:
    """``LC<study_event_id>`` (SH VR, <= 16 chars) — parsed back by the app's
    ingest endpoint to auto-bind the returning study to this visit."""
    return f"{ACCESSION_PREFIX}{study_event_id}"


def study_event_id_from_accession(accession: str | None) -> int | None:
    if not accession:
        return None
    m = _ACCESSION_RE.match(accession.strip())
    return int(m.group(1)) if m else None


# --- query interpretation -------------------------------------------------

def _iso(da: str) -> str:
    return f"{da[0:4]}-{da[4:6]}-{da[6:8]}"


def requested_date_range(query: Dataset) -> tuple[str, str]:
    """Read ScheduledProcedureStepStartDate from the C-FIND identifier.

    Accepts a single DA, a DICOM range (``A-B``, ``A-``, ``-B``) or nothing
    (→ today). Returns ISO (from, to) inclusive. Open-ended ranges are clamped
    to a 30-day window around today so a wildcard query can't dump the whole
    schedule.
    """
    today = date.today()
    raw = ""
    try:
        sps = query.ScheduledProcedureStepSequence[0]
        raw = str(getattr(sps, "ScheduledProcedureStepStartDate", "") or "").strip()
    except (AttributeError, IndexError, TypeError):
        raw = ""
    if not raw:
        d = today.strftime("%Y%m%d")
        return _iso(d), _iso(d)
    if "-" in raw:
        lo, hi = raw.split("-", 1)
        lo = lo.strip()
        hi = hi.strip()
        lo_iso = _iso(lo) if _DA_RE.match(lo) else (today.replace(day=1).isoformat())
        hi_iso = _iso(hi) if _DA_RE.match(hi) else (today.fromordinal(today.toordinal() + 30).isoformat())
        return lo_iso, hi_iso
    if _DA_RE.match(raw):
        return _iso(raw), _iso(raw)
    d = today.strftime("%Y%m%d")
    return _iso(d), _iso(d)


def _wants(query: Dataset, keyword: str, seq: bool = False) -> str | None:
    """A matching key the SCU asked for: None when absent / empty / wildcard."""
    try:
        src = query.ScheduledProcedureStepSequence[0] if seq else query
        v = str(getattr(src, keyword, "") or "").strip()
    except (AttributeError, IndexError, TypeError):
        return None
    if not v or v == "*":
        return None
    return v


def filter_entries(entries: list[dict], query: Dataset) -> list[dict]:
    """Apply the matching keys we honour: Modality, PatientID (exact), and
    ScheduledStationAETitle (an entry's station is the requester itself, so
    any non-wildcard station value matches)."""
    modality = _wants(query, "Modality", seq=True)
    patient_id = _wants(query, "PatientID")
    out: list[dict] = []
    for e in entries:
        if modality and (e.get("modality") or "OP").upper() != modality.upper():
            continue
        if patient_id and (e.get("subjectLabel") or "") != patient_id:
            continue
        out.append(e)
    return out


# --- data source ---------------------------------------------------------

def fetch_entries(url: str, token: str, day_from: str, day_to: str, timeout_s: int) -> list[dict]:
    """GET the app's internal worklist endpoint. Raises on non-2xx."""
    resp = requests.get(
        url,
        params={"from": day_from, "to": day_to},
        headers={"X-MUW-Dicom-Token": token, "Accept": "application/json"},
        timeout=timeout_s,
    )
    resp.raise_for_status()
    body = resp.json() or {}
    return list(body.get("entries") or [])


# --- response items ------------------------------------------------------

def _da(iso: str | None) -> str:
    """ISO yyyy-MM-dd → DICOM DA yyyyMMdd (empty when absent)."""
    if not iso:
        return ""
    s = str(iso).strip()
    return s.replace("-", "")[:8] if len(s) >= 10 else ""


def _sex(g: str | None) -> str:
    g = (g or "").strip().lower()
    return {"m": "M", "f": "F", "o": "O"}.get(g[:1], "") if g else ""


def build_item(entry: dict, calling_ae: str) -> Dataset:
    """One Modality Worklist item (PS3.4 K.6) from an app worklist entry."""
    sid = int(entry["studyEventId"])
    label = str(entry.get("subjectLabel") or "")
    desc = str(entry.get("eventLabel") or "Fundus imaging")[:64]
    acc = accession_for(sid)

    ds = Dataset()
    ds.SpecificCharacterSet = "ISO_IR 100"
    ds.PatientName = label
    ds.PatientID = label
    ds.PatientBirthDate = _da(entry.get("dateOfBirth"))
    ds.PatientSex = _sex(entry.get("gender"))
    ds.StudyInstanceUID = study_instance_uid_for(sid)
    ds.AccessionNumber = acc
    ds.RequestedProcedureID = acc
    ds.RequestedProcedureDescription = desc
    ds.ReferringPhysicianName = ""

    sps = Dataset()
    sps.Modality = str(entry.get("modality") or "OP")
    # Echo the requester's own AE as the scheduled station so a station-filtered
    # query always sees its items.
    sps.ScheduledStationAETitle = calling_ae or ""
    sps.ScheduledProcedureStepStartDate = _da(entry.get("date"))
    sps.ScheduledProcedureStepStartTime = str(entry.get("time") or "")
    sps.ScheduledPerformingPhysicianName = ""
    sps.ScheduledProcedureStepDescription = desc
    sps.ScheduledProcedureStepID = acc
    ds.ScheduledProcedureStepSequence = [sps]
    return ds
