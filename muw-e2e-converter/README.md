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
