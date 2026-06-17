# Java-side metric computation (artifacts-only contract)

As of the 2026-06-17 refactor, the inference server returns **raw segmentation
artifacts only** — `primary_metric_value`/`primary_metric_unit` come back **null**
in the `/run` envelope (and `RunEnvelope`/`FullVolumeResult` make them optional).
The **Java backend computes every clinical metric** from the persisted artifacts.

This doc is the handoff for that Java work. Cluster bring-up + the four models are
validated end-to-end (see [cluster-deployment.md](cluster-deployment.md)).

## Where it plugs in (Java)

- `core/.../service/retinal/RemoteRetinalInferenceClient.java` — receives the
  `RemoteRunResult` (envelope + base64 artifacts). Today it persists artifacts via
  `RetinalArtifactStorageService` and the controller INSERTs `retinal_inference_result`.
- New work: after the artifacts are written to disk, **read them, compute the
  task's metric, and set `primary_metric_value`/`primary_metric_unit`** on the row
  (the DB columns `primary_metric_value NUMERIC(12,4)` / `primary_metric_unit
  VARCHAR(16)` are nullable, so an interim null is fine until this lands).
- Pixel geometry: the Java side **made** the `bscan.dcm` (the `/preprocess`
  step), so it has `PixelSpacing` (axial, lateral mm) + `SpacingBetweenSlices`
  (slice mm). Use those — don't re-derive.

## What each task returns (artifacts + `output_payload`)

| task | artifacts | `output_payload` key |
|---|---|---|
| fluid | `fluidseg.npz` (label volume: 1=IRF, 2=SRF, 3=PED) | `segmentation_file` |
| onl | `*OPL-HFL*.csv`, `*BMEIS*.csv` (two surfaces) | `surface_csvs` |
| pr | `*BMEIS*.csv`, `*OB?OPR*.csv` (two surfaces) | `surface_csvs` |
| ga | `001-RPEL.csv`, `002-EZL.csv`, `003-ELM.csv` (+ the 11 IOWA layer CSVs, kept) | `rpel_csv` |

**Surface CSV format** (the layer CSVs): a grid of per-(B-scan × A-scan) **row
indices** (y of the surface in the B-scan), with `U` for undefined/missing columns.
A leading size header line precedes the data rows. (The pre-refactor adapter parsed
these as: skip the first line, then each row = floats with `U`/empty → NaN — see
`apptainer.py` history `_read_surface_csv` for the exact parse.)

## Proposed formulas — CONFIRM with the reading centre before shipping

These match what the adapter computed before the refactor; treat as a starting
point, not gospel:

- **onl** — ONL thickness map = `(BMEIS_y − OPL-HFL_y)`; metric = mean over valid
  A-scans `× axial_mm × 1000` → **µm**.
- **pr** — PR-layer thickness = `(OB-OPR_y − BMEIS_y)`; metric = mean `× axial_mm
  × 1000` → **µm**. (PR layer is bounded by BMEIS above and OB-OPR below.)
- **fluid** — per-label voxel counts from `fluidseg.npz` `× (axial × lateral ×
  slice) mm` → **mm³**, reported per biomarker (IRF/SRF/PED) + total.
- **ga** — geographic-atrophy area from `001-RPEL.csv`: count RPE-loss pixels
  (value > 0 / non-`U`) `× lateral_mm × slice_mm` → **mm²**.

## Notes / gotchas

- **pr/onl both emit a BMEIS surface** — they're different algorithms (sese_pr vs
  sese_onl); use each task's own CSVs.
- GA also returns **EZL** and **ELM** surfaces and the **11 IOWA input layers** —
  persisted intentionally (may be clinically useful); the GA *metric* uses RPEL.
- The synthesized `bscan.dcm` is 496×512 for Spectralis; reused off-spec test scans
  hit sese_pr's internal resize (metric only approximate) — real app-VM scans via
  `/preprocess` won't.
