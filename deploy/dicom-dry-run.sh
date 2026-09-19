#!/usr/bin/env bash
# =============================================================================
# DR-025 — pre-go-live dry run for the DICOM fundus path
# =============================================================================
# Proves the receiver works before a camera and a patient are in the room. It
# exercises the three exchanges the Optomed Lumo performs, in the order the
# device performs them:
#
#   1. C-ECHO          — the device's "test connection" button
#   2. MWL C-FIND      — the worklist the operator picks the patient from
#   3. C-STORE         — the image, carrying the accession the worklist gave
#
# then checks in the database that the stored image was bound to the visit
# rather than landing in the reconciliation inbox. Everything it creates it
# removes again: the run leaves one audit trail entry per bind, which is the
# point, and no image_ingest row and no file.
#
# It talks to the sidecar from inside the sidecar's own container, so it tests
# the service rather than the firewall. Confirm separately that the camera VLAN
# can reach port 11112 and that a general workstation cannot.
#
# USAGE (from the compose project directory on the VM):
#   deploy/dicom-dry-run.sh
#
# Environment:
#   COMPOSE_FILES   compose -f arguments (default: production overlay if present)
#   DB_USER         postgres role       (default: clinica)
#   DB_NAME         database            (default: libreclinica)
#   SCP_HOST        host the sidecar listens on, as seen from inside its own
#                   container (default: 127.0.0.1)
#   SCP_PORT        DICOM port          (default: 11112)
#   CALLING_AE      the AE title to present; must be on the sidecar's allowlist
#                   when one is configured (default: OPTOMEDLUMO)
#   STATION_AE      scheduled station AE used for the C-FIND (default: LUMO)
#
# Exit: 0 all checks passed, 1 a check failed, 2 usage/environment problem.
# =============================================================================
set -uo pipefail

DB_USER="${DB_USER:-clinica}"
DB_NAME="${DB_NAME:-libreclinica}"
SCP_HOST="${SCP_HOST:-127.0.0.1}"
SCP_PORT="${SCP_PORT:-11112}"
CALLING_AE="${CALLING_AE:-OPTOMEDLUMO}"
STATION_AE="${STATION_AE:-LUMO}"

if [ -n "${COMPOSE_FILES:-}" ]; then
  # shellcheck disable=SC2206
  COMPOSE=(docker compose ${COMPOSE_FILES})
elif [ -f deploy/compose.production.yaml ]; then
  COMPOSE=(docker compose -f compose.yaml -f deploy/compose.production.yaml)
else
  COMPOSE=(docker compose)
fi

pass=0
fail=0
ok()   { printf '  \033[32mok\033[0m    %s\n' "$1"; pass=$((pass+1)); }
bad()  { printf '  \033[31mFAIL\033[0m  %s\n' "$1"; fail=$((fail+1)); }
note() { printf '        %s\n' "$1"; }

psql_q() {
  "${COMPOSE[@]}" exec -T db psql -qAt -U "$DB_USER" -d "$DB_NAME" -c "$1" 2>/dev/null | tr -d '\r'
}

echo "DR-025 DICOM dry run — $(date '+%Y-%m-%d %H:%M:%S')"
echo

# --- 0. the pieces are running -----------------------------------------------
if ! "${COMPOSE[@]}" ps --status running --services 2>/dev/null | grep -qx 'dicom-scp'; then
  echo "dicom-scp is not running. Start it with COMPOSE_PROFILES=dicom, then re-run." >&2
  exit 2
fi
if ! "${COMPOSE[@]}" ps --status running --services 2>/dev/null | grep -qx 'db'; then
  echo "db is not running." >&2
  exit 2
fi

# --- 1. is a visit scheduled today? ------------------------------------------
# The worklist only offers scheduled (1) and data-entry-started (3) visits, so
# without one there is nothing to find and nothing to bind to. Rather than
# creating clinical rows from a shell script, the run asks for one.
EVENT_ID="$(psql_q "SELECT se.study_event_id FROM study_event se
                      JOIN study_subject ss ON ss.study_subject_id = se.study_subject_id
                     WHERE date(se.date_start) = CURRENT_DATE
                       AND se.subject_event_status_id IN (1, 3)
                       AND ss.status_id NOT IN (5, 7)
                     ORDER BY se.study_event_id LIMIT 1")"

if [ -z "$EVENT_ID" ]; then
  note "No visit is scheduled for today, so the worklist and bind checks cannot run."
  note "Schedule a visit for a test subject today and re-run to exercise them."
fi

# --- 2..4. the three DICOM exchanges -----------------------------------------
# Run from inside the sidecar container: it already has pynetdicom, and this is
# a test of the service rather than of the network path.
DICOM_OUT="$("${COMPOSE[@]}" exec -T \
  -e DRY_HOST="$SCP_HOST" -e DRY_PORT="$SCP_PORT" \
  -e DRY_CALLING_AE="$CALLING_AE" -e DRY_STATION_AE="$STATION_AE" \
  -e DRY_EVENT_ID="${EVENT_ID:-}" \
  dicom-scp python - <<'PY' 2>&1
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

host = os.environ["DRY_HOST"]
port = int(os.environ["DRY_PORT"])
calling = os.environ["DRY_CALLING_AE"]
station = os.environ["DRY_STATION_AE"]
event_id = os.environ.get("DRY_EVENT_ID") or ""
today = dt.date.today().strftime("%Y%m%d")


def emit(key, value):
    print(f"{key}={value}")


# --- C-ECHO ---------------------------------------------------------------
ae = AE(ae_title=calling)
ae.add_requested_context(Verification)
assoc = ae.associate(host, port)
if not assoc.is_established:
    emit("echo", "no-association")
    sys.exit(0)
status = assoc.send_c_echo()
emit("echo", "ok" if status and status.Status == 0x0000 else f"status={status}")
assoc.release()

# --- MWL C-FIND -----------------------------------------------------------
ae = AE(ae_title=calling)
ae.add_requested_context(ModalityWorklistInformationFind)
assoc = ae.associate(host, port)
if not assoc.is_established:
    emit("find", "no-association")
    sys.exit(0)

q = Dataset()
q.PatientName = ""
q.PatientID = ""
q.AccessionNumber = ""
sps = Dataset()
sps.ScheduledStationAETitle = station
sps.ScheduledProcedureStepStartDate = today
sps.Modality = ""
q.ScheduledProcedureStepSequence = [sps]

accessions = []
for status, identifier in assoc.send_c_find(q, ModalityWorklistInformationFind):
    if status and status.Status in (0xFF00, 0xFF01) and identifier is not None:
        accessions.append(getattr(identifier, "AccessionNumber", "") or "")
assoc.release()
emit("find_count", len(accessions))
emit("find_has_event", "yes" if event_id and f"LC{event_id}" in accessions else "no")

# --- C-STORE --------------------------------------------------------------
# A 2x2 grey image is enough: this exercises the association, the accession
# parse and the bind, not the pixel pipeline.
ds = Dataset()
ds.file_meta = FileMetaDataset()
ds.file_meta.MediaStorageSOPClassUID = OphthalmicPhotography8BitImageStorage
ds.file_meta.MediaStorageSOPInstanceUID = generate_uid()
ds.file_meta.TransferSyntaxUID = ExplicitVRLittleEndian
ds.SOPClassUID = OphthalmicPhotography8BitImageStorage
ds.SOPInstanceUID = ds.file_meta.MediaStorageSOPInstanceUID
ds.StudyInstanceUID = generate_uid()
ds.SeriesInstanceUID = generate_uid()
ds.PatientName = "DRYRUN^DICOM"
ds.PatientID = "DRYRUN"
ds.AccessionNumber = f"LC{event_id}" if event_id else "LC0"
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

ae = AE(ae_title=calling)
ae.add_requested_context(OphthalmicPhotography8BitImageStorage, ExplicitVRLittleEndian)
assoc = ae.associate(host, port)
if not assoc.is_established:
    emit("store", "no-association")
    sys.exit(0)
status = assoc.send_c_store(ds)
assoc.release()
emit("store", "ok" if status and status.Status == 0x0000 else f"status={status}")
emit("sop_uid", ds.SOPInstanceUID)
PY
)"

get() { printf '%s\n' "$DICOM_OUT" | sed -n "s/^$1=//p" | tail -1; }

echo "DICOM exchanges"
[ "$(get echo)" = "ok" ] && ok "C-ECHO answered" || bad "C-ECHO — $(get echo)"

FIND_COUNT="$(get find_count)"
if [ -n "$EVENT_ID" ]; then
  if [ "$(get find_has_event)" = "yes" ]; then
    ok "MWL C-FIND returned today's visit (LC$EVENT_ID), ${FIND_COUNT:-0} item(s) total"
  else
    bad "MWL C-FIND did not return LC$EVENT_ID (${FIND_COUNT:-0} item(s) returned)"
    note "Check core.dicom.worklist.studyOids — a scope that excludes this study yields an empty worklist."
  fi
else
  note "MWL C-FIND returned ${FIND_COUNT:-0} item(s) (no visit scheduled today to check against)."
fi

[ "$(get store)" = "ok" ] && ok "C-STORE accepted" || bad "C-STORE — $(get store)"
SOP_UID="$(get sop_uid)"

# --- 5. the database saw it --------------------------------------------------
echo
echo "Database"
if [ -z "$SOP_UID" ]; then
  bad "no SOP Instance UID returned — nothing to look up"
else
  ROW="$(psql_q "SELECT status || '|' || COALESCE(match_policy,'') || '|' ||
                        COALESCE(bound_study_event_id::text,'')
                   FROM image_ingest WHERE sop_instance_uid = '$SOP_UID'")"
  if [ -z "$ROW" ]; then
    bad "the stored image did not reach the database"
    note "Check DICOM_SCP_INGEST_TOKEN against core.dicom.ingest.token, and the sidecar log."
  else
    STATUS="${ROW%%|*}"; REST="${ROW#*|}"
    POLICY="${REST%%|*}"
    BOUND_EVENT="${REST#*|}"
    if [ -n "$EVENT_ID" ]; then
      if [ "$STATUS" = "BOUND" ] && [ "$BOUND_EVENT" = "$EVENT_ID" ]; then
        ok "image bound to visit $EVENT_ID (match policy: ${POLICY:-none})"
      else
        bad "image landed as $STATUS (expected BOUND to visit $EVENT_ID)"
        note "An UNBOUND row means the accession was not recognised — it is in the inbox, not lost."
      fi
    else
      [ "$STATUS" = "UNBOUND" ] && ok "image landed UNBOUND, as expected without a visit today" \
                                || bad "image landed as $STATUS (expected UNBOUND)"
    fi
  fi
fi

# --- 6. leave nothing behind --------------------------------------------------
# The dry run's image is not clinical data. Clean up by SOP Instance UID rather
# than by the recorded path, so a run whose ingest was rejected — the row never
# existed, but the sidecar had already written the files — is cleaned up too.
if [ -n "$SOP_UID" ]; then
  psql_q "DELETE FROM image_ingest WHERE sop_instance_uid = '$SOP_UID'" >/dev/null
  "${COMPOSE[@]}" exec -T dicom-scp sh -c \
    'find "${DICOM_SCP_STORE_PATH:-/var/lib/libreclinica/dicom-ingest}" -name "'"$SOP_UID"'.*" -delete' \
    >/dev/null 2>&1
  LEFT="$("${COMPOSE[@]}" exec -T dicom-scp sh -c \
    'find "${DICOM_SCP_STORE_PATH:-/var/lib/libreclinica/dicom-ingest}" -name "'"$SOP_UID"'.*" | wc -l' \
    2>/dev/null | tr -d ' \r')"
  if [ "${LEFT:-0}" = "0" ]; then
    ok "test image and its row removed"
  else
    bad "${LEFT} file(s) from the test image are still in the ingest store"
  fi
fi

# --- 7. the log gave nothing away --------------------------------------------
echo
echo "Log hygiene"
if "${COMPOSE[@]}" logs --since 5m dicom-scp 2>/dev/null | grep -qi "DRYRUN\^DICOM"; then
  bad "the patient name sent in the test image appears in the sidecar log"
  note "Set DICOM_SCP_LOG_LEVEL=INFO — DEBUG dumps whole datasets."
else
  ok "no patient identifier from the test image in the sidecar log"
fi

echo
echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ] || exit 1
