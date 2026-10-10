#!/usr/bin/env bash
# =============================================================================
# DR-039 — compare two retinal jobs of the same scan, side by side
# =============================================================================
# The validation aid for DICOM OCT volumes. Export one Spectralis scan twice,
# as .e2e and as DICOM, upload both, run the same task on each, then:
#
#   deploy/compare-oct-jobs.sh <e2e-job-id> <dicom-job-id>
#
# It prints, for both jobs: the source each was read from (format, device,
# spacing order, eye), the geometry the preprocess sidecar stored for the scan
# (dimensions, voxel spacing in mm, scan depth and width), the primary metric
# and every numeric value of the result payload, with the difference. Arrays
# (per-B-scan traces) are summarised by length and sum.
#
# Read-only: one SELECT, and the two geometry.json files. Nothing identifying
# is printed — the jobs hold no patient fields, and the device strings are the
# file's Manufacturer / ManufacturerModelName.
#
# Run it on the app VM from the compose project directory, as a user that can
# read the bscan store.
#
# Environment:
#   COMPOSE_FILES  compose -f arguments (default: production overlay if present)
#   DB_USER        postgres role       (default: clinica)
#   DB_NAME        database            (default: libreclinica)
#   BSCAN_STORE    core.retinalInference.bscanStorePath
#                  (default: /var/lib/libreclinica/retinal-artifacts/bscans)
#
# Exit: 0 printed, 2 usage/environment problem.
# =============================================================================
set -uo pipefail

if [ $# -ne 2 ] || ! [[ "$1" =~ ^[0-9]+$ ]] || ! [[ "$2" =~ ^[0-9]+$ ]]; then
  echo "usage: $0 <job-id> <job-id>" >&2
  exit 2
fi
command -v python3 >/dev/null || { echo "python3 is required" >&2; exit 2; }

DB_USER="${DB_USER:-clinica}"
DB_NAME="${DB_NAME:-libreclinica}"
BSCAN_STORE="${BSCAN_STORE:-/var/lib/libreclinica/retinal-artifacts/bscans}"

if [ -n "${COMPOSE_FILES:-}" ]; then
  # shellcheck disable=SC2206
  COMPOSE=(docker compose ${COMPOSE_FILES})
elif [ -f deploy/compose.production.yaml ]; then
  COMPOSE=(docker compose -f compose.yaml -f deploy/compose.production.yaml)
  [ -r /etc/libreclinica/env ] && COMPOSE+=(--env-file /etc/libreclinica/env)
else
  COMPOSE=(docker compose)
fi

ROWS=$("${COMPOSE[@]}" exec -T db psql -qAt -U "$DB_USER" -d "$DB_NAME" -c "
  SELECT json_build_object(
           'job_id', j.job_id, 'task', j.task, 'status', j.status,
           'status_message', j.status_message, 'e2e_path', j.e2e_path,
           'scan_index', j.scan_index, 'eye', j.eye_laterality,
           'source_format', j.source_format, 'manufacturer', j.device_manufacturer,
           'model', j.device_model, 'spacing_order', j.spacing_order,
           'model_version', j.model_version,
           'primary_value', r.primary_metric_value, 'primary_unit', r.primary_metric_unit,
           'payload', r.output_payload)
    FROM retinal_inference_job j
    LEFT JOIN retinal_inference_result r ON r.job_id = j.job_id
   WHERE j.job_id IN ($1, $2)
   ORDER BY array_position(ARRAY[$1, $2]::bigint[], j.job_id);" 2>&1 | tr -d '\r')
if [ "$(printf '%s\n' "$ROWS" | grep -c '^{')" -ne 2 ]; then
  echo "could not read both jobs:" >&2
  printf '%s\n' "$ROWS" >&2
  exit 2
fi

printf '%s\n' "$ROWS" | BSCAN_STORE="$BSCAN_STORE" python3 -c '
import hashlib, json, os, sys, uuid

def artifact_key(stored_path):
    """RetinalArtifactKey.of: the name without .e2e, else a name-based UUID of the path."""
    base = os.path.basename(stored_path)
    if base.lower().endswith(".e2e"):
        return base[:-4]
    b = bytearray(hashlib.md5(("muw-retinal-artifact-key:" + os.path.normpath(stored_path)).encode()).digest())
    b[6] = (b[6] & 0x0F) | 0x30
    b[8] = (b[8] & 0x3F) | 0x80
    return str(uuid.UUID(bytes=bytes(b)))

def geometry(job):
    key = artifact_key(job["e2e_path"] or "")
    i = job["scan_index"] or 0
    d = os.path.join(os.environ["BSCAN_STORE"], key, "scan-%d" % i if i > 0 else "")
    try:
        with open(os.path.join(d, "geometry.json")) as f:
            return json.load(f)
    except OSError:
        return None

def flatten(node, prefix=""):
    out = {}
    if isinstance(node, dict):
        for k, v in node.items():
            out.update(flatten(v, prefix + "." + k if prefix else k))
    elif isinstance(node, list):
        nums = [x for x in node if isinstance(x, (int, float)) and not isinstance(x, bool)]
        if nums and len(nums) == len(node):
            out[prefix + "[n]"] = len(nums)
            out[prefix + "[sum]"] = sum(nums)
    elif isinstance(node, (int, float)) and not isinstance(node, bool):
        out[prefix] = node
    return out

jobs = [json.loads(line) for line in sys.stdin if line.startswith("{")]
cols = []
for j in jobs:
    g = geometry(j) or {}
    b = g.get("bscan") or {}
    row = {
        "task": j["task"], "status": j["status"], "model version": j["model_version"],
        "source format": j["source_format"] or g.get("source_format"),
        "device": " ".join(x for x in (j["manufacturer"], j["model"]) if x) or None,
        "spacing order": j["spacing_order"] or g.get("spacing_order"),
        "eye": j["eye"], "geometry.json": "found" if g else "missing",
    }
    for k in ("dim_z_bscans", "dim_y_rows", "dim_x_ascans",
              "pixel_axial_mm", "pixel_lateral_mm", "pixel_slice_mm"):
        row["bscan." + k] = b.get(k)
    if b:
        row["depth mm (rows x axial)"] = b["dim_y_rows"] * b["pixel_axial_mm"]
        row["width mm (cols x lateral)"] = b["dim_x_ascans"] * b["pixel_lateral_mm"]
        row["volume depth mm ((n-1) x slice)"] = (b["dim_z_bscans"] - 1) * b["pixel_slice_mm"]
    row["primary metric"] = float(j["primary_value"]) if j["primary_value"] is not None else None
    row["primary unit"] = j["primary_unit"]
    for k, v in sorted(flatten(j["payload"] or {}).items()):
        row["payload." + k] = v
    if j["status_message"]:
        row["status message"] = j["status_message"]
    cols.append(row)

keys = []
for c in cols:
    for k in c:
        if k not in keys:
            keys.append(k)

def fmt(v):
    if v is None:
        return "-"
    if isinstance(v, float):
        return "%.6g" % v
    return str(v)

w = max(len(k) for k in keys)
print("%-*s  %-36s  %-36s  %s" % (w, "", "job %s" % jobs[0]["job_id"], "job %s" % jobs[1]["job_id"], "diff (rel.)"))
for k in keys:
    a, b = cols[0].get(k), cols[1].get(k)
    diff = ""
    if isinstance(a, (int, float)) and isinstance(b, (int, float)) and not isinstance(a, bool):
        d = b - a
        diff = "%+.6g" % d + ("  (%+.2f%%)" % (100.0 * d / a) if a else "")
    print("%-*s  %-36s  %-36s  %s" % (w, k, fmt(a)[:36], fmt(b)[:36], diff))
'
