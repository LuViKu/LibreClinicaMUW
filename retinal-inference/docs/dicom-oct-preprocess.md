# DICOM OCT volumes: preprocess contract and vendor gating

OCT volumes used to reach the cluster only as Heidelberg `.e2e`. The app-VM
preprocess sidecar now also accepts a vendor **DICOM OCT volume** and turns it
into the same normalised, de-identified `bscan.dcm` the cluster's `/run`
expects. The cluster then refuses a volume from a device that a task's model
was not trained on.

## `POST /preprocess` (same endpoint for both formats)

The endpoint is unchanged; there is no `/preprocess-dicom`. The sidecar decides
by content: an upload with the DICOM Part-10 magic (`DICM` at byte 128) is a
DICOM, and anything else is handled as `.e2e`, as before. The filename is
ignored. One endpoint keeps a single auth, store, dedup and header path, and
the Java side needs no format switch.

Request (multipart, header `X-MUW-Inference-Token`):

| field | DICOM semantics |
|---|---|
| `file` | the DICOM OCT instance (one volume per file) |
| `laterality` | `OD` / `OS`, optional. It must agree with the file's eye. It is **required** when the file says OU (`B`) or names no eye. |
| `e2e_uuid` | lower-case UUID, strict as before; it names the store directory `<store>/<uuid>/`. If absent, it is derived from sha256(body). |
| `scan_index` | must be `0` (otherwise 400) |

### Validation (422, body `{"detail": {"error": <code>, "message": <text>}}`)

| code | when |
|---|---|
| `invalid_dicom` | unreadable, or a malformed NumberOfFrames |
| `not_oct_volume` | neither SOP class `1.2.840.10008.5.1.4.1.1.77.1.5.4` nor Modality `OPT`, or NumberOfFrames ≤ 1 |
| `not_monochrome` | SamplesPerPixel ≠ 1 or not MONOCHROME1/2 |
| `burned_in_annotation` | BurnedInAnnotation = `YES` |
| `missing_manufacturer` | no Manufacturer. Without one, the device cannot be gated, and the writer would otherwise stamp its Heidelberg default. |
| `laterality_missing` / `laterality_conflict` | OU or no eye and no form value; or the file names both eyes, or it contradicts the form value |
| `spacing_unavailable` | the voxel spacing cannot be determined (see below) |
| `spacing_ambiguous` | Heidelberg device, but neither or both PixelSpacing values look like the Spectralis axial spacing (see below) |
| `unsupported_transfer_syntax` | compressed pixel data with no decoder installed. Installed: uncompressed, deflate, RLE, JPEG baseline and JPEG 2000 (via Pillow). Not installed: JPEG lossless, JPEG-LS, HTJ2K. |
| `pixel_decode_failed` | the decoder failed, or the decoded shape ≠ (frames, rows, columns) |

A 500 `deidentification_failed` means the post-write check found an identifier
in the output (fail closed; nothing is stored or returned).

Messages never quote values from the file.

### Response

The body is the normalised `bscan.dcm` (`application/dicom`). Headers:

- `X-MUW-Pixel-Axial-Mm`, `X-MUW-Pixel-Lateral-Mm`, `X-MUW-Pixel-Slice-Mm`,
  `X-MUW-Bscan-Dim-Z/Y/X`, `X-MUW-E2E-Uuid` and `X-MUW-Acquisition-Date`
  (ISO; when the file has one). These are the same as for `.e2e`.
- **new, both formats:** `X-MUW-Source-Format` (`e2e` | `dicom`),
  `X-MUW-Manufacturer`, `X-MUW-Manufacturer-Model` (when present) and
  `X-MUW-Device-Tasks`. The last is a comma-separated list of the tasks whose
  models were trained on this device; empty means none.
- **new, DICOM only:** `X-MUW-Laterality` (`OD` / `OS`, the resolved eye).
- **new, both formats:** `X-MUW-Spacing-Order`: `standard`, `swapped` or
  `standard-assumed` (see the spacing rules). The `.e2e` path always sends
  `standard`.

Laterality is read from ImageLaterality, Laterality and FrameLaterality. It
accepts the DICOM codes `R` / `L` / `B` and also `OD` / `OS` / `OU`: a
third-party conversion was seen with `Laterality = "OD"` and no
ImageLaterality.

The acquisition date is taken from the first of these that is present:
AcquisitionDateTime, then the FrameContent FrameAcquisitionDateTime (shared,
then the first frame), then AcquisitionDate, ContentDate and StudyDate.

### What `bscan.dcm` looks like

It is written by `muw_e2e_converter.write_bscan_dcm`, the writer the `.e2e`
path uses:

- Ophthalmic Tomography SOP class, Modality OPT, NumberOfFrames.
- 16-bit MONOCHROME2. Intensities are scaled max → 65535, like the `.e2e`
  path; MONOCHROME1 is inverted.
- top-level `PixelSpacing = [axial, lateral]` and `SpacingBetweenSlices`. There
  are no functional groups.
- one (0022,0031) item per B-scan with `ReferenceCoordinates` in mm. For a
  DICOM source this is the writer's synthesised grid (x 0…cols·lateral,
  y = i·slice). The source's own Ophthalmic Frame Location coordinates are
  pixels of the SLO, not mm, so they cannot be carried over.
- Manufacturer and ManufacturerModelName are kept for gating; they are never
  rewritten.

### Store (`RETINAL_INFERENCE_BSCAN_STORE`)

The sidecar writes `<store>/<uuid>/bscan.dcm` and `geometry.json`. There is
**no `fundus.png`**. `geometry.json` has the same schema as for `.e2e`, with:

| field | DICOM |
|---|---|
| `scan_index` | 0 |
| `bscan` | filled (the Java CRT reads only this) |
| `fundus` | `null` |
| `bscan_positions_fundus_px` | `[]` |
| `scan_bbox_fundus_px` | `null` |
| `fovea_estimate_fundus_px` | `null` |
| `source_format` | `"dicom"` (new; `.e2e` writes `"e2e"`) |
| `spacing_source` | `{"pixel": …, "slice": …}`: where each spacing came from |
| `laterality_source` | `"dicom"` or `"form"` |
| `device` | `{"manufacturer", "model"}` |
| `spacing_order` | `standard` / `swapped` / `standard-assumed` (`.e2e`: `standard`) |
| `plausibility` | `{depth_mm, width_mm, volume_depth_mm, depth_plausible, warnings[]}`. `depth_mm` = rows·axial, `width_mm` = cols·lateral, `volume_depth_mm` = (n−1)·slice. `depth_plausible` is true when a Heidelberg depth falls in 1.5–2.5 mm, and null for other vendors. A failed check only warns (it is also logged); it never refuses. |

Consumers checked: `CrtComputeService.loadGeometry` reads `bscan.*` through
a JsonNode tree, so it is safe. The SPA renders `FundusOverlay` only when the
job has a `fundusUrl`, which needs `fundus.png`, so it does not render for a
DICOM. The ETDRS aggregation in `RetinalMetricsView` guards `fundus?.`,
`?? []` and `!fovea`, so it returns empty. The TS `GeometryJson` type still
declares `fundus` and the bbox/fovea fields as non-null and should become
nullable on the Java/SPA side.

## Spacing rules (`retinal_inference/dicom_geometry.py`)

Pixel Spacing (0028,0030) is "adjacent row spacing \ adjacent column spacing,
in mm". An OPT frame is one B-scan whose rows run along depth, so by the
standard **value 1 = axial, value 2 = lateral**. Original Heyex/Spectralis
exports are expected to follow that order (per the user).

**The stored order is not trusted blindly.** A DICOM produced by a
third-party `.e2e` → DICOM converter (another MUW-internal platform) was
observed with `PixelSpacing = [0.005825, 0.003872]`, which is
**[lateral, axial]**. The file is 496 × 1024 × 97: 1024 × 0.005825 = 5.96 mm
of scan width, 496 × 0.003872 = 1.92 mm of depth, and 96 × 0.062138 =
5.97 mm, so a 6 × 6 mm cube. Read in standard order, every thickness would be
~1.5× too large, silently. Such files can be uploaded again, so the axial
value is resolved physically. The rule handles both orders:

- **Heidelberg** (Manufacturer contains `heidelberg`): Spectralis samples
  depth at a fixed ~3.87 µm. The value inside **[0.0035, 0.0042] mm** is the
  axial one. If exactly one value fits, the order is `standard` (it was
  first) or `swapped` (it was second). If none or both fit, the upload gets
  422 `spacing_ambiguous`; the order is never guessed. One case is ambiguous
  by design: a dense scan whose lateral spacing is also ~3.9 µm (e.g. 1536
  A-scans over 6 mm).
- **A `bscan.dcm` written by this pipeline** (DeidentificationMethod
  `LibreClinicaMUW-sidecar-v1`) is always stored [axial, lateral]. It is
  taken as `standard` without the value rule, so the dense-scan case above
  does not break `.e2e`-derived jobs on the cluster.
- **Other manufacturers:** the DICOM order is kept and recorded as
  `standard-assumed`. No task is validated for them (vendor gating), so this
  only affects stored geometry.

The normalised output is always written [axial, lateral], so the cluster
resolves it as `standard`. The cluster's `_spacing_mm` applies the same
rule to any DICOM it receives (defence in depth); an ambiguous one raises.

Sources, first match wins:

1. Pixel spacing: top-level `PixelSpacing`, then Shared Functional Groups
   `PixelMeasuresSequence[0].PixelSpacing`, then Per-frame Pixel Measures.
   Per-frame values must be present on every frame and agree to 0.1 %.
2. Slice spacing: top-level `SpacingBetweenSlices`, then Shared Pixel
   Measures, then Per-frame Pixel Measures (which must agree). Otherwise it is
   derived from per-frame `PlanePositionSequence.ImagePositionPatient` (mm,
   patient coordinates) as the first-to-last distance / (n − 1). This is done
   only when every consecutive step is within 25 % of the mean, because the
   standard calls these positions nominal (PS3.3 A.52.4.3).
3. **Not used:** `SliceThickness`, which in OPT is the beam/section thickness
   and not the distance between B-scans; and Ophthalmic Frame Location
   `ReferenceCoordinates` (0022,0032), which are row/column positions in
   **pixels** of the referenced image (an SLO in another instance).
4. A value outside 1e-5…10 mm is refused, which catches µm written as mm.
   When nothing usable is found, the upload is refused with 422
   `spacing_unavailable`. The spacing is never guessed.

The cluster's `ApptainerAdapter._spacing_mm` uses the same helpers, including the order resolution, so a
multi-frame OPT file that keeps its spacing only in the functional groups no
longer crashes every task. It keeps its historic slice = lateral fallback,
because no handler reads the slice value.

## De-identification guarantees (DICOM path)

- **Structural.** The output is a fresh dataset. Only these are copied from the
  source: the pixels, the spacing, the eye, the acquisition date/time,
  Manufacturer, ManufacturerModelName, and a hash of SeriesInstanceUID (which
  becomes a new series UID). No patient, institution, physician, operator,
  description, comment, station, serial-number or private element is copied,
  and no source UID.
- New Study, Series and SOP Instance UIDs are written.
- `phi.redact_dicom` runs as in the `.e2e` path: PatientName and PatientID are
  blank, PatientIdentityRemoved = YES, and DeidentificationMethod is set.
- The upload is refused when BurnedInAnnotation = YES. The sidecar does not
  OCR the pixels; a file that omits the flag is trusted.
- The source file is deleted from the tempdir right after reading, and the
  tempdir is removed when the request ends.
- **Post-write check.** `assert_deidentified` re-reads the output and fails
  closed on any of these:
  - a private tag;
  - non-blank patient, institution or physician name attributes;
  - a UID equal to a source UID;
  - a text value containing a source patient name, ID, accession number,
    serial number or UID;
  - a text value equal to a source institution, physician, description or
    station value.

## Vendor gating (`retinal_inference/devices.py`)

`TASK_DEVICES` declares the supported devices for each task. Today every task
(`ga`, `fluid`, `onl`, `pr`, `bm`, `layers`, `sdretinanet`) gets the same
declaration:

```json
{"label": "Heidelberg Engineering Spectralis",
 "manufacturers": ["heidelberg"], "models": ["spectralis", "hra"],
 "model_required": false}
```

Matching is a case-insensitive substring match. Manufacturer must contain
`heidelberg`. A model name that is present must contain `spectralis` or `hra`,
which keeps a Heidelberg ANTERION out. A missing model is accepted, because an
`.e2e` does not always carry one and the `.e2e`-derived Manufacturer is
`Heidelberg Retina Angiograph`.

- `/run` parses the header of a DICOM upload, and refuses it before any model
  runs if the device does not match:
  `422 {"detail": {"error": "unsupported_device", "message": "Task 'fluid' is validated only for Heidelberg Engineering Spectralis volumes; this volume comes from Carl Zeiss Meditec / CIRRUS HD-OCT 5000. …", "task", "manufacturer", "model"}}`.
  An unreadable DICOM gets `422 invalid_dicom`. `.e2e` inputs are not gated.
- `/health` publishes the declaration as `task_devices: {task: {label,
  manufacturers, models, model_required}}`.
- `/preprocess` does **not** refuse another vendor; it only reports
  `X-MUW-Device-Tasks`. The app can store the volume and show which analyses
  are possible.

## Limitations

- No SLO/fundus for DICOM sources, so the en-face overlay and ETDRS-on-fundus
  are unavailable. The referenced SLO is a separate instance and could be a
  follow-up.
- Frames are used in stored order.
- JPEG lossless, JPEG-LS and HTJ2K are not decodable (no pylibjpeg in the
  image).
- All models are Spectralis-trained, so a non-Heidelberg DICOM is normalised
  and stored, but no task runs on it.
