# `/e2e/describe` and `/e2e/series-image`

Series view of a stored multimodal Spectralis `.E2E` (e2e-multimodal-split, Contract 1:
[`docs/development/e2e-multimodal-split.md`](../../docs/development/e2e-multimodal-split.md),
DR-041). They sit on the service that serves `/preprocess` and are gated the same way:

* `RETINAL_INFERENCE_PREPROCESS_ENDPOINT_ENABLED=true`, otherwise 404;
* header `X-MUW-Inference-Token` equal to `RETINAL_INFERENCE_AUTH_TOKEN` (401 if wrong or
  missing, 503 if the token is not configured);
* `muw-e2e-converter` installed, otherwise 503 (DR-024: the cluster posture does not have it).

Nothing is uploaded. The file is read from `RETINAL_INFERENCE_E2E_UPLOADS_PATH`
(default `/var/lib/libreclinica/e2e-uploads`, mounted read-only) and nothing is written.
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

## `GET /e2e/series-image?storedName=…&seriesKey=…&frame=0`

`image/png` (8-bit greyscale, no metadata chunks): the en-face frame, the B-scan of an OCT line
scan, or the IR localizer of an OCT volume. `frame` defaults to 0.

## Status codes

| Code | When |
|---|---|
| 400 | `storedName` is not a bare `<name>.e2e`, or resolves outside the upload directory |
| 401 / 503 | token wrong or missing / token not configured |
| 404 | endpoint disabled; file missing; unknown `seriesKey`; `frame` out of range |
| 422 | the file exists but is not a readable E2E (the parser message is not echoed) |
| 503 | `muw-e2e-converter` not installed (DR-024) |

Tests: `tests/test_e2e_series.py` (HTTP contract, with the converter faked) and, in
`muw-e2e-converter`, `tests/test_series.py` (series logic on a synthetic E2E).
