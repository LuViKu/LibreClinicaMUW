# Browser de-identification of imaging uploads (layer 1)

On the internet-facing deployment (`GET /pages/api/v1/me` → `deidentificationRequired: true`) an uploaded imaging file must not carry patient-identifying information. Three layers enforce it; this page documents layer 1, which runs in the browser before a byte is uploaded. Layer 2 (the server verifies and rejects non-compliant files) and layer 3 (a scan of stored files) are server-side.

Code: [`web/src/spa/src/lib/deid/`](../../web/src/spa/src/lib/deid/). Store wiring: `stores/uploadWorkbench.ts`. Tests: `lib/__tests__/deid/`, `stores/__tests__/uploadWorkbench.deid.test.ts`, `components/__tests__/DeidentificationUi.test.ts`, and the Python contract test `muw-e2e-converter/tests/test_browser_deid_contract.py`.

## What is accepted

Heidelberg `.e2e` and DICOM only. JPEG, PNG and anything else are refused in the browser. CRF item file attachments are disabled (`CrfItemWidget` + `FileUploadInput`); the server refuses them too.

## E2E

In **every** chunk of type 9 (patient data; a chunk counts when its own header or its directory entry says 9) the 127-byte payload is rewritten in place:

| offset | length | field | becomes |
|---|---|---|---|
| 0 | 31 | first_name | zero bytes |
| 31 | 51 | surname | study label, NUL-padded |
| 82 | 15 | title | zero bytes |
| 97 | 4 (u32) | birthdate | 0 |
| 101 | 1 | sex | 0 |
| 102 | 25 | patient_id | study label, NUL-padded |

The file length does not change and no other byte changes. The label must be printable ASCII and at most 24 characters. The layout is the one `oct_converter` 0.7.0 reads (`e2e_binary.patient_id_structure`, 127 bytes).

**Kept on purpose: the chunk header's `patient_db_id`.** It is a numeric internal database key of the acquisition software; volume grouping `(patient_db_id, study_id, series_id)` needs it, and it is neither a name, a hospital ID nor a date.

Known limits: other chunks that `oct_converter` or Heidelberg tools may fill with operator or exam text (for example the operator login in the type-10 acquisition-info chunk) are not rewritten. They are covered only by the residual sweep when they repeat a patient value.

## DICOM

dcmjs (MIT, lazy-loaded, about 0.7 MB minified) reads and writes the object. The sidecar's list in `dicom-scp/src/dicom_scp/deidentify.py` is ported exactly (PatientName/PatientID become the label; the cleared and removed lists apply) with these additions: PatientSex, PatientSize, PatientWeight, PatientBirthName, StudyDescription, SeriesDescription, ImageComments, PerformedProcedureStepDescription, RequestedProcedureDescription, ProtocolName, DeviceSerialNumber, StationName are emptied; every private (odd-group) tag is removed; all rules apply inside nested sequences; `PatientIdentityRemoved=YES` and `DeidentificationMethod="LibreClinica browser de-identification v1"` are stamped. `BurnedInAnnotation=YES` (anywhere) refuses the file. Pixel data is never touched; the written file is re-read and its pixel-data fingerprint compared to the original's.

## Residual-identifier sweep (both formats)

Before stripping, the original identifying strings are collected (E2E: first name, surname, patient_id, title; DICOM: PatientName components, PatientID, OtherPatientIDs, OtherPatientNames, PatientBirthName, PatientMotherBirthName, PatientBirthDate). Values shorter than 3 characters and values equal to the label are ignored. After stripping, the entire output is searched for each value as single-byte, UTF-8 and UTF-16LE, letters compared case-insensitively. Any hit refuses the upload; the message names the file, never the value.

A consequence: a 3-4 character identifier can by chance occur in the pixel data of a very large file and cause a false refusal. That fails closed.

## Matching and what is sent

The header patient ID (E2E: the `patient_id` slot only, no surname/first-name fallback; DICOM: PatientID) is matched locally and exactly against the labels of the subjects the user sees in the active study (`GET /pages/api/v1/subjects`). A match selects that subject; otherwise the operator picks. The header value is never transmitted: not in `/resolve`, not in the commit fields, not in error reports. `patientId` in `/resolve` and `/commit` is always a study label. The original file name is never sent; the upload name is `<label>_<yyyyMMdd>_<OD|OS>[_<n>].<e2e|dcm>` (date = acquisition date, else upload date; `_<n>` is the volume ordinal for a multi-volume E2E).

## Confirmation

Each file shows a preview (E2E: the SLO image, else the first B-scan; DICOM: the first frame for native and JPEG-baseline data, otherwise a notice that no preview is possible). The operator ticks "no patient name or ID visible in the image" per file (or once for the batch with all previews visible). The commit carries `deidConfirmed=true` and `deidSha256=<hex of the stripped bytes>`; the server verifies the hash against the bytes it received.

## Fail-closed and memory

Any parse, strip or verify error leaves the file un-uploaded with a message. Work runs in a Web Worker (one file at a time, worker terminated afterwards); a file is read once, stripped in place, hashed, and handed back as a `Blob`.
