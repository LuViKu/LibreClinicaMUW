# muw-e2e-converter

Heidelberg `.e2e` → `bscan.dcm` + SLO companions for LibreClinicaMUW.

## Purpose

The shared ingestion seam for retinal inference: takes a Spectralis `.e2e`
binary, returns a multi-frame DICOM (`bscan.dcm`), an SLO en-face PNG
(`fundus.png`), and a `geometry.json` carrying per-B-scan-to-fundus
registration metadata. Strips PHI before writing the DICOM.

## Why it's a separate package (DR-024)

The production retinal-inference pipeline has TWO distinct deployment
postures:

| Posture | Where | What it does |
|---|---|---|
| **App-VM `retinal-preprocess`** | local docker compose | Converts `.e2e` → `bscan.dcm` + companions; serves `/preprocess` |
| **Cluster sidecar on cn5** | bare uvicorn | Runs Apptainer-based inference on a pre-converted `bscan.dcm` |

The cluster's `ApptainerAdapter.full_volume` **explicitly rejects** `.e2e`
inputs — production conversion ALWAYS happens app-side via the local
`retinal-preprocess` container before forwarding to cluster `/run`.

On 2026-06-24, four hours of debugging time were wasted fixing
`e2e_parser.py` *on the cluster*, where the changes were never executed.
Splitting the converter into this package makes the same mistake fail at
`pip install` time: the cluster's deployment runbook installs
`retinal-inference` WITHOUT this package, so any attempt to use the
converter on cn5 raises `ModuleNotFoundError` immediately.

## Usage

```python
from muw_e2e_converter import prepare_bscan_dcm, read_e2e_volume

# Returns Path to a directory containing bscan.dcm
out_dir = prepare_bscan_dcm(Path("/uploads/patient.e2e"), Path("/tmp/work"))
```

See [decision-record.md DR-024](../docs/development/modernization/decision-record.md)
for the full split rationale.

## Series view of a multimodal export

A Spectralis export holds a whole session: OCT volumes, OCT line scans and the en-face
modalities (IR, BAF, BR, IRAF, qAF, ICGA, FA) for both eyes. Two functions list and preview its
series (contract: `docs/development/e2e-multimodal-split.md`, Contract 1). They walk the chunk
headers only and keep file offsets, so a 600 MB export is described in about a second and
under 100 MB of memory; no pixel data is decoded until a frame is rendered.

```python
from muw_e2e_converter import describe_e2e, render_series_png

d = describe_e2e(Path("/uploads/3f2b8c1e.e2e"), expected_label="EIAMD005")
# {"headerPatient": "empty" | "matches" | "differs",
#  "series": [{"seriesKey": "s3", "seriesModality": "OCT", "deviceModality": "IR",
#              "scanPattern": "OCT ART Volume", "laterality": "OD",
#              "acquiredAt": "2026-09-30T10:12:03", "frames": 97, "width": 1024,
#              "height": 496, "octVolumeIndex": 0}, ...]}

png = render_series_png(Path("/uploads/3f2b8c1e.e2e"), "s3", frame=0)  # bytes
```

* `describe_e2e(path, expected_label) -> dict`. `headerPatient` compares the header patient ID
  with `expected_label` (case-insensitive; `differs` if any header ID differs) and is the only
  trace of it: the ID, names and birth date are never returned or logged. Series are ordered
  OD, OS, unknown eye; then `OCT, OCT_LINE, IR, BAF, BR, IRAF, QAF, ICGA, FA, OTHER`; then time.
  `seriesKey` is `s<ordinal>` over the series ids sorted numerically, so the same bytes always
  give the same keys. `seriesModality` is `OCT` (scan pattern contains `Volume`), `OCT_LINE`
  (any other series with OCT B-scans), the device modality (`qAF` becomes `QAF`) or `OTHER`.
  `frames` is the number of B-scans for an OCT series and the number of en-face images
  otherwise (a time sequence is one series); `width`/`height` are those of one frame.
  `acquiredAt` is the series' earliest frame time (naive ISO date-time) or `null`.
  `octVolumeIndex` is the index into `E2E.read_oct_volume()` (the `scan_index` of
  `read_e2e_volume` / `/preprocess`) for `OCT` and `OCT_LINE`, else `null`.
* `render_series_png(path, series_key, frame=0) -> bytes`: the en-face frame; the B-scan of an
  OCT line scan; the IR localizer of an OCT volume. 8-bit greyscale PNG with no text or other
  ancillary chunks. Raises `KeyError` (unknown series key) or `IndexError` (frame out of range).

## Deployment

```sh
# Local app-VM (compose retinal-preprocess service): INSTALLED
pip install -e ./muw-e2e-converter

# Cluster cn5 inference sidecar: NOT INSTALLED
# (deployment runbook explicitly skips this step)
```

## Tests

```sh
cd muw-e2e-converter
pip install -e ".[dev]"
pytest
```
