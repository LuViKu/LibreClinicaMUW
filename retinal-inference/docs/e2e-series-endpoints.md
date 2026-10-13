# `/e2e/describe`, `/e2e/convert` and `/e2e/series-image`

Series view of a stored multimodal Spectralis `.E2E` (e2e-multimodal-split, Contract 1:
[`docs/development/e2e-multimodal-split.md`](../../docs/development/e2e-multimodal-split.md),
DR-041). They sit on the service that serves `/preprocess` and are gated the same way:

* `RETINAL_INFERENCE_PREPROCESS_ENDPOINT_ENABLED=true`, otherwise 404;
* header `X-MUW-Inference-Token` equal to `RETINAL_INFERENCE_AUTH_TOKEN` (401 if wrong or
  missing, 503 if the token is not configured);
* `muw-e2e-converter` installed, otherwise 503 (DR-024: the cluster posture does not have it).

Nothing is uploaded. The file is read from `RETINAL_INFERENCE_E2E_UPLOADS_PATH`
(default `/var/lib/libreclinica/e2e-uploads`, mounted read-only); only `/e2e/convert` writes, and only below
`RETINAL_INFERENCE_E2E_CONVERT_PATH` (default `/var/lib/libreclinica/e2e-convert`, mounted read-write here and in the app).
`storedName` must match `^[A-Za-z0-9-]+\.e2e$` (extension case-insensitive); it is resolved
below that directory and a path that leaves it (symlinks included) is refused with 400.

## `POST /e2e/describe`

```json
{ "storedName": "3f2b8c1e-....e2e", "expectedLabel": "EIAMD005" }
```

`expectedLabel` is optional (a folder name the client may have; leave it out for a file from a
shared watch folder).

Response `200` (`muw_e2e_converter.describe_e2e`):

```json
{ "headerPatient": "none | empty | matches | differs",
  "headerLabel": "EIAMD005",
  "series": [ { "seriesKey": "s3", "seriesModality": "OCT", "deviceModality": "IR",
                "scanPattern": "OCT ART Volume", "laterality": "OD",
                "acquiredAt": "2026-09-30T10:12:03", "frames": 97, "width": 1024,
                "height": 496, "octVolumeIndex": 0 } ] }
```

`headerLabel` is the study label in the **surname** slot of the patient chunk. It is non-null
only when every patient chunk in the file carries the same non-empty surname and that surname is
a printable-ASCII token of 1 to 24 characters without spaces; otherwise it is `null`.
`headerPatient` compares `expectedLabel` with the surname only: `none` when no `expectedLabel`
was sent, else `empty`, `matches` (case-insensitive) or `differs`. First name, patient ID and
birth date are never read, returned or logged, and the service never logs `headerLabel`. `octVolumeIndex` is the `scan_index` that `/preprocess`
takes for the same file.

## `POST /e2e/convert`

One DICOM per kept series, in **one pass** over the file (amendment 2026-10-13 b of the design).

```json
{ "storedName": "3f2b8c1e-....e2e", "label": "EIAMD005", "seriesKeys": ["s0", "s1", "s4"] }
```

Writes `<seriesKey>.dcm` (and `<seriesKey>.geometry.json` for an OCT volume) into
`RETINAL_INFERENCE_E2E_CONVERT_PATH/<storedName without .e2e>/`, a directory created **fresh** for the
request (whatever an earlier attempt left there is removed first) and removed again when the conversion fails.
The app imports the files and removes the directory.

```json
{ "files": [ { "seriesKey": "s0", "file": "s0.dcm", "sha256": "…", "bytes": 98700000, "frames": 97,
               "sopClassUid": "1.2.840.10008.5.1.4.1.1.77.1.5.4", "geometryFile": "s0.geometry.json" } ] }
```

* **OCT volume and OCT line scan:** Ophthalmic Tomography Image Storage, 16-bit, the same
  `write_bscan_dcm` writer and the same pixel values `read_e2e_volume` gives (oct-converter's
  log-compressed intensity scaled so the volume's maximum is 65535). The spacing and the B-scan positions
  come from the series' **own** B-scan records.
* **En-face series and time sequences:** Ophthalmic Photography 8 Bit Image Storage, MONOCHROME2,
  uncompressed, multi-frame when the series has several images.
* **What the files say about the person:** `PatientID` and `PatientName` are `label` and nothing else
  (blank when `label` is omitted); no birth date, sex, age, first name, patient-ID slot or institution.
  Laterality, acquisition date/time and Manufacturer come from the file's per-series metadata.
* **UIDs** derive from the file's SHA-256 and the series key: converting the same export again gives
  byte-identical files.
* **Memory:** one series at a time (a 97 x 496 x 1024 volume is held as 100 MB of raw words plus one
  float64 B-scan); each written file is read back and checked (frames, size, pixel digest).
* `400` for an unknown or malformed `seriesKey` (nothing written); `404` for a missing file; `422` when
  the conversion fails (the parser message is not echoed). The label is never logged.

## `GET /e2e/series-image?storedName=…&seriesKey=…&frame=0`

`image/png` (8-bit greyscale, no metadata chunks): the en-face frame, the B-scan of an OCT line
scan, or the IR localizer of an OCT volume. `frame` defaults to 0. The app does not call this since
the amendment (the browser renders its previews itself and a series item is a DICOM); it stays as a
diagnostic and for the parity tests.

## Status codes

| Code | When |
|---|---|
| 400 | `storedName` is not a bare `<name>.e2e`, or resolves outside the upload directory; `/e2e/convert`: an unknown or malformed `seriesKey` |
| 401 / 503 | token wrong or missing / token not configured |
| 404 | endpoint disabled; file missing; unknown `seriesKey`; `frame` out of range |
| 422 | the file exists but is not a readable E2E, or its conversion failed (the parser message is not echoed) |
| 503 | `muw-e2e-converter` not installed (DR-024) |

Tests: `tests/test_e2e_series.py` (HTTP contract, with the converter faked),
`tests/test_e2e_convert_dicom_oct.py` (a converted OCT DICOM through the DICOM path of `/preprocess`,
compared with the `.e2e` path) and, in `muw-e2e-converter`, `tests/test_series.py` and
`tests/test_convert.py` (series logic and conversion on a synthetic E2E).
