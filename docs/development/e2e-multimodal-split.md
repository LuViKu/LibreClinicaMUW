# Multimodal Spectralis exports: one upload, one item per series

**Status:** in progress, 2026-10-14. Server side implemented, including the amendment (b) storage model (one DICOM per series, `excludeSeries`, typed responses; see "Implemented (amendment b)" at the end). The browser preview is built separately. Decision record: [DR-041](modernization/decision-record.md#dr-041--a-multimodal-spectralis-export-is-uploaded-once-and-split-into-one-item-per-series-on-the-server).

## Why

The drusen study exports each patient from the Spectralis as one folder of `.E2E` files (`<root>/EIAMD005/EIAMD01D.E2E`). One file holds a whole multimodal session for both eyes. A survey of the first 22 exports found these series:

| Device modality | Scan pattern | Series |
|---|---|---|
| ICGA | Images | 202 |
| qAF | Time Sequence | 79 |
| IR | OCT ART Volume (the OCT volume and its IR localizer) | 73 |
| IR | OCT B-Scan (line scan) | 68 |
| BAF | Images | 38 |
| BR | Images | 37 |
| IR | Images | 36 |
| IRAF | Images | 26 |
| FA | Images | 17 |
| qAF | Images | 6 |
| ICGA | Time Sequence | 3 |
| IRAF | Time Sequence | 1 |

Files are 10–640 MB. The header patient ID is empty in 13 of 22 files, matches the folder in 4 and differs in 5. One of those 5 is not shaped like a study label.

Before this change:

- **Only OCT volumes became items.** Each one was a separate upload of the same file.
- **The other series were never items.**
- **The app refused anything over 200 MiB.**
- **Both clients read whole files into memory.**

## Decisions (maintainer, 2026-10-13)

1. **Every series becomes its own item, per eye.**
   - The kinds are OCT volume, OCT line scan, IR, BAF, BR, IRAF, qAF, ICGA and FA.
   - A time sequence is one item that holds its frames.
2. **A file is uploaded once and the server splits it.** This applies to the Export Watcher and to the browser upload alike.
3. **The folder name is the subject label.**
   - When the header patient ID is empty or equal to the folder name, the file can be bound automatically.
   - When it differs, every item of that file is parked in the inbox.
4. **The drusen study is not configured yet.** Items park in the inbox until the study exists. The design must still bind them by modality once it does.

> **Amended 2026-10-13 (subject rule).** Exports can land in one shared watch folder, so the folder cannot be relied on. The subject is the study label in the header's **surname** slot. The first-name and patient-ID slots can hold a clinic number and are never used, returned, sent, stored or logged. When the file sits in a patient folder, that folder must agree with the label; otherwise the file is parked. See [heidelberg-e2e-format-notes.md](heidelberg-e2e-format-notes.md). This supersedes decision 3; the contracts below are written to the amended rule (implemented 2026-10-13).

## Series kinds

The kind is taken from the file's own per-series metadata (`oct-converter` `metadata['enface_modality']` and `metadata['scan_pattern']`, keyed by series):

| `seriesModality` | Rule |
|---|---|
| `OCT` | scan pattern contains `Volume` |
| `OCT_LINE` | scan pattern is `OCT B-Scan` (or any other single-B-scan OCT pattern) |
| `IR`, `BAF`, `BR`, `IRAF`, `QAF`, `ICGA`, `FA` | otherwise, the device modality `IR`, `BAF`, `BR`, `IRAF`, `qAF`, `ICGA`, `FA` |
| `OTHER` | anything else; the device modality is kept in `deviceModality` |

## Contract 1: app → converter sidecar

These endpoints sit on the service that already serves `/preprocess`: `retinal-preprocess` in development, `retinal-inference` in production. They use the same preprocess token.

Files are not uploaded again. The sidecar reads the stored file from the E2E upload directory, which it already mounts read-only (`/var/lib/libreclinica/e2e-uploads`).

`storedName` must be a bare file name: `^[A-Za-z0-9-]+\.e2e$` (the app stores files as `<uuid>.e2e`). Anything else is refused with 400. No paths are accepted.

### `POST /e2e/describe`

Request (JSON):

```json
{ "storedName": "3f2b8c1e-....e2e", "expectedLabel": "EIAMD005" }   // expectedLabel is optional
```

Response:

```json
{
  "headerPatient": "none | empty | matches | differs",
  "headerLabel": "EIAMD005",
  "series": [
    {
      "seriesKey": "s3",
      "seriesModality": "OCT",
      "deviceModality": "IR",
      "scanPattern": "OCT ART Volume",
      "laterality": "OD",
      "acquiredAt": "2026-09-30T10:12:03",
      "frames": 97,
      "width": 1024,
      "height": 496,
      "octVolumeIndex": 0
    }
  ]
}
```

- **The label, and nothing else from the patient record.** `headerLabel` is the surname slot (chunk type 9, offset 31, 51 bytes) when every patient chunk in the file carries the same non-empty surname and it is a printable-ASCII token of 1 to 24 characters without spaces; otherwise `null`. `headerPatient` compares `expectedLabel` with the surname only: `none` when no `expectedLabel` was sent, else `empty`, `matches` (case-insensitive) or `differs`. The response never carries the first name, the patient ID slot or the birth date, and the sidecar never logs `headerLabel`.
- **`seriesKey`** is stable for a given file: the same bytes always give the same keys. It is opaque to the app, and it is unique within the file.
- **`octVolumeIndex`** is the index into `oct-converter`'s `read_oct_volume()`. It is the `scan_index` that the existing `/preprocess` call takes. It is `null` for en-face series.
- **`laterality`** is `OD`, `OS` or `null`.
- **`acquiredAt`** is ISO local date-time, or `null`.
- **Order.** Series are ordered by eye (`OD` first), then by modality in the order of the table above, then by time.

### `GET /e2e/series-image?storedName=…&seriesKey=…&frame=0`

Returns `image/png` with `frame` defaulting to 0:

- an en-face series gives that frame;
- an OCT line scan gives the B-scan;
- an OCT volume gives its IR localizer.

For an out-of-range frame or an unknown key it answers 404. The PNG carries no metadata.

## Contract 2: clients → app

`POST /pages/api/v1/public/upload/commit` (watcher, public page) and `POST /pages/api/v1/ingest/upload/commit` (staff page) gain a split mode.

Multipart fields:

- `file`: the whole `.E2E`, streamed.
- `split=true`
- `patientId` (optional): the folder the client found the file in, the immediate parent folder. Left out for a file in a shared watch folder. The subject comes from the file's own header label; see the server rules.
- `device=spectralis`
- Optionally `studyEventId`, to bind every series to one visit (the staff page's batch visit), or `park=true`.

`scanDate`, `laterality` and `scanIndex` are not sent in split mode. The server takes them per series from `/e2e/describe`.

Response `201`:

```json
{
  "sha256": "…",
  "headerPatient": "none | empty | matches | differs",
  "subjectLabel": "EIAMD005",
  "parked": true,
  "items": [
    {
      "ingestItemId": 123,
      "seriesKey": "s3",
      "seriesModality": "OCT",
      "laterality": "OD",
      "acquisitionDate": "2026-09-30",
      "frames": 97,
      "imagingModalityId": null,
      "status": "UNBOUND",
      "jobs": []
    }
  ]
}
```

- `409` when a file with the same SHA-256 was already split. The body is `{duplicate:true, existingIngestItemIds:[…]}`.
- `GET …/preflight?sha256=…&split=true` answers whether the file is already there, so a client can skip uploading it.

### Server rules

1. **Store once.** The file is streamed to `e2eUploadsPath/<uuid>.e2e` once, and the SHA-256 is computed while it streams.
2. **Describe.** The server calls `/e2e/describe` with the folder name as `expectedLabel`, when there is one. The answer is checked: `headerLabel` must be null or a plain token.
3. **Create one item per series.** Each `ingest_item` row has:
   - kind `e2e`, the same stored path and SHA-256;
   - `e2e_series_key`, `series_modality` and `series_frames` (new columns);
   - laterality and acquisition date from the series;
   - `scan_index = octVolumeIndex` for OCT and OCT_LINE, `null` otherwise.

   Deduplication is unique on `(sha256, e2e_series_key)`. The existing `(sha256, scan_index)` uniqueness is kept for non-split rows.
4. **Bind or park.**
   - The label is the header label; if a folder was sent and the header label is non-null and differs, every item is parked (`headerPatient=differs`). If the file has no usable header label the folder is the label; with neither, every item is parked with no subject.
   - The label must name exactly one visible subject, else every item is parked.
   - A label read from the header is stored, audited and logged only when it resolves to exactly one visible subject (stored as that subject's own label). An unmatched header label is stored nowhere: not in `ingest_item`, not in the audit trail, not in a log. `subjectLabel` in the response is the matched subject's label, else `null`.
   - If `park=true` was sent, or no visit can be resolved, every item is parked.
   - Otherwise, when `studyEventId` was sent, every item is bound to that visit.
   - Otherwise each series resolves its own visit from label and date, using the existing resolution.
5. **Modality.** A new nullable column `imaging_modality.e2e_series` holds one `seriesModality`. A series item gets the study's active catalogue entry with that token. Parked items get none, and binding from the inbox resolves it. When nothing matches, the item stays without a modality, as today.
6. **Jobs.** Only `OCT` items get retinal-inference jobs, from the existing imaging plan. They go through `/preprocess` with `scan_index`, as today.
7. **Limits.** Uploads of up to 2 GiB are accepted (servlet multipart config and nginx). Requests stream to disk and are not buffered in memory.
8. **Retention.** A stored file shared by several items is deleted only when no item references it any more.
9. **Inbox.** Items show their series modality and frame count. Staff can preview a series through an app endpoint that checks inbox visibility and proxies `/e2e/series-image`.

### Implemented (server side, 2026-10-13)

> **Superseded in part by amendment (b), below.** Items are now DICOM rows with their own file, not `e2e` rows sharing one stored path; there are no `/preprocess` calls on the `.e2e`; the `series-image` proxy is gone; `scan_index` is not set. Rules 1, 3, 6, 8 and 9 above and the bullets "Analysable", "Jobs", "Inbox" and "Retention and undo" below describe the first build; "Implemented (amendment b)" has the current behaviour. Binding, parking, modality tokens, limits and the failure statuses are unchanged.

Built as specified, with these details worth knowing:

- **Entry points.** `split=true` on `POST …/public/upload/commit` and `POST …/ingest/upload/commit`, and `?sha256=…&split=true` on both preflights (`{exists, split:true, existingIngestItemIds:[…]}`). `eventCrfId`, `scanDate` and `laterality` are refused with 400 in split mode (use `studyEventId` or `park`). On the staff route `split=true` answers 400 while de-identification is required (that path keeps its per-volume behaviour).
- **Migration** `lc-muw-2026-10-13-e2e-series.xml`: `ingest_item.e2e_series_key/series_modality/series_frames`, `imaging_modality.e2e_series`, unique `(sha256, e2e_series_key)`. The old `(sha256, COALESCE(scan_index,-1))` index is recreated as partial (`e2e_series_key IS NULL`), because the non-OCT series of one file all have `scan_index` NULL and would collide.
- **Analysable.** A series row is `kind='e2e'` with `oct_volume` TRUE for `OCT` and FALSE otherwise; `OctVolumes.isAnalysable` now treats an `.e2e` row with `oct_volume = FALSE` as not analysable (legacy rows have NULL and stay analysable). So binding an IR/FA/line-scan series from the inbox starts no job.
- **Binding.** The label alone must name exactly one visible subject, else the whole file is parked. A series with no visit on its date is parked on its own; the rest of the file is bound. `headerPatient=differs` or `park=true` parks everything. A series whose OCT volume has the same picture as an earlier item is parked on its own with the usual held-back audit row (the per-volume route refuses a same-label twin instead; refusing one volume of a split file would drop the others).
- **Modality.** Looked up by `imaging_modality.e2e_series` in the bound study, only if exactly one active entry carries the token. An OCT volume with no token entry falls back to the study's single `e2e`-accepting entry, as the per-volume route does. Binding from the inbox sets the modality the same way; unbinding clears it for a series row.
- **Jobs.** Only `OCT` volumes with a laterality get jobs (visit plan, inference switch as before). Preprocessing streams the stored file (`RemoteRetinalInferenceClient.preprocessUploadFile`, JDK HTTP client) once per OCT volume with its `scan_index`; the acquisition date comes from `/e2e/describe`, not from `/preprocess`.
- **Failure of the sidecar.** The stored file is deleted and nothing is filed: 503 when the converter is not configured or unreachable, 502 for an error or an unusable answer (duplicate or malformed `seriesKey`, unknown `seriesModality`, `headerPatient` outside `none|empty|matches|differs`, a `headerLabel` that is not a plain token), 422 for a file without series.
- **Inbox.** Rows carry `seriesModality`, `seriesFrames`, `seriesKey`; `GET /api/v1/ingest/{id}/series-image?frame=0` proxies `/e2e/series-image` under the same visibility rules as the other previews. `previewUrl` of a series row points at it.
- **Retention and undo.** The retention sweep already kept a file while another row names it; the undo endpoints now do the same (they used to unlink the file unconditionally).
- **Limits.** `web.xml` multipart 2 GiB; nginx 2048m, unbuffered request, 30-minute send/read timeouts on the public, ingest-upload and event-CRF OCT upload locations. The Tomcat connector has no custom settings in this repository (`maxPostSize` only concerns form-urlencoded bodies; `maxSwallowSize` only an aborted upload).

## Contract 3: clients

**Export Watcher** (`deploy/export-watcher/ExportWatcher.ps1`):

- **Subject.** The platform reads the subject from the file. A file directly in the watch folder is uploaded without `patientId`. A file in a sub-folder is uploaded with `patientId` = the folder that directly contains it. Nothing is skipped for lack of a folder; the coded problem `no-subject-folder` no longer exists. `file-too-large` remains.
- **Upload.** For each settled file, the watcher checks the preflight by a streamed SHA-256, then makes one streamed commit with `split=true`. The upload uses `StreamContent` and a timeout sized for 2 GiB. The watcher no longer loops over volumes, and no file is read whole into memory.
- **Moving files.** A `201` or `409` moves the file to `_uploaded`, as before.

**Browser upload** (`UploadWorkbench`):

- **Selecting files.** A folder picker and folder drag-and-drop. For an E2E file in a folder, `patientId` is the folder that directly contains it, as in the watcher. A file picked on its own is committed without `patientId`: the row is ready to send, and after the commit it shows the server's `subjectLabel` or "no subject, parked in the inbox".
- **Hashing.** The SHA-256 is computed in chunks, never by reading the whole file into memory. Large files are processed one at a time.
- **Rows.** One row per E2E file, showing the folder label when there is one and, after commit, the matched subject. After commit, the row lists its series items (modality, eye, frames, status).
- **Size.** Files over 2 GiB are refused in the browser before upload.
- **De-identification.** The browser de-identification path used on internet-facing deployments keeps its current per-volume behaviour. Split mode applies where the browser does not de-identify.

## Out of scope

- **Study setup.** Configuring the drusen study (visits, catalogue entries with `e2e_series`, CRF items) happens once the study exists.
- **Device-specific analysis** of qAF, ICGA or FA.

## Amendment 2026-10-13 (b): one DICOM per series, browser preview, leaving series out

These are maintainer decisions taken after the first build. They change the storage model and add a preview to the browser upload.

### One DICOM per series; the E2E becomes a time-limited source copy

**Storage.**

- After `describe`, the server has the sidecar convert the whole file in **one pass**. Each kept series becomes its own DICOM, and the series items are ordinary DICOM items with their own stored file. The OCT jobs run through the existing DICOM OCT path (DR-039). `/preprocess` no longer reads the E2E.
- The original `.E2E` moves to a source area (`core.retinalInference.e2eSourcePath`, default `/var/lib/libreclinica/e2e-source`). It is not served and not analysed there.
- Each item records `e2e_source_sha256`. A retention job deletes source files older than `core.retinalInference.e2eSourceRetentionDays` (default 90).
- Duplicate detection stays on the E2E's SHA-256 through `e2e_source_sha256`. The same export uploaded again is refused even after its source file is gone.

**DICOM content.**

- Patient data is only the study label: `PatientID` and `PatientName`. There is no birth date, no sex and no other name.
- **Laterality and dates.** Each DICOM carries the series' laterality and acquisition date/time.
- **OCT volume.** The existing `write_bscan_dcm` output: 16-bit, with the same pixel values the analysis receives today (oct-converter's log-compressed intensity), plus `geometry.json` as now.
- **OCT line scan.** The same writer, with one frame.
- **En-face images and time sequences** (IR, BAF, BR, IRAF, qAF, ICGA, FA). Ophthalmic Photography 8-bit (SOP class 1.2.840.10008.5.1.4.1.1.77.1.5.1), MONOCHROME2, uncompressed, multi-frame when the series has several images.

**Sidecar contract.** `POST /e2e/convert`:

- **Request:** `{storedName, label, seriesKeys}` (the kept series).
- **Behaviour:** the sidecar writes `<seriesKey>.dcm` (plus `<seriesKey>.geometry.json` for OCT) into `/var/lib/libreclinica/e2e-convert/<storedName-without-extension>/`. Both the sidecar and the app mount that directory read-write.
- **Response:** `{files:[{seriesKey, file, sha256, bytes, frames, sopClassUid}]}`.
- **Errors:** an unknown key is a 400. The output directory is created fresh, and a failed conversion removes it.

### Browser preview and leaving series out

**Browser preview.**

- The browser reads the series list from the file's chunk directory with `File.slice`, never the whole file. It produces exactly `describe_e2e`'s list: the same `seriesKey`, order, `seriesModality`, laterality, `acquiredAt` and frames.
- **The label it reads** is `headerLabel`, from the surname slot only, under the same token rule.
- **Thumbnails, one per series:**
  - en-face: the raw 8-bit pixels of the series' first image;
  - OCT volume: its IR localizer;
  - OCT line scan: the B-scan, decoded from UFloat16 with oct-converter's log curve.
- **Before upload,** the row lists the series per eye, each with a checkbox. The user can untick series.

**Leaving series out.**

- The commit sends `excludeSeries` (comma-separated `seriesKey`s). The server checks every key against its own `describe`, and an unknown key is a 400 with nothing stored. Excluded series are not converted, not stored and not itemised.
- If every series is excluded, the commit is a 400 and nothing is stored.
- The response lists `excluded`.

**Parity.**

- Shared synthetic fixtures live in `testdata/e2e-synthetic/` (`*.e2e` built by `muw-e2e-converter/tests/e2e_synth.py`, with the expected `describe_e2e` output as `*.describe.json`). They contain no real data.
- The Python suite and the SPA suite both assert their parser against these files, so the browser and the server cannot drift apart.

**Typed responses.** The split commit and preflight return typed DTOs, so `api.ts` generates their types and the SPA uses the generated ones.

### Implemented (amendment b, server side, 2026-10-14)

**Sidecar.** `muw_e2e_converter.convert_series(path, label, series_keys, out_dir)` and `POST /e2e/convert` (`retinal-inference`, same gating as `/e2e/describe`; contract in [`retinal-inference/docs/e2e-series-endpoints.md`](../../retinal-inference/docs/e2e-series-endpoints.md)). The chunk index is built once per file (cached; `describe` and `convert` share it) and each requested series is decoded once, one at a time.

- **OCT.** `write_bscan_dcm` writes the volume or line scan, 16-bit, with exactly the pixel values `read_e2e_volume` gives (a test compares every voxel). The scale `65535 / max` comes from the set of distinct raw values, so the volume is never held as float64: a 97 x 496 x 1024 volume costs about 100 MB of raw words. A volume also gets `<seriesKey>.geometry.json`.
- **En-face.** A new Ophthalmic Photography 8 Bit writer (pydicom): MONOCHROME2, uncompressed, multi-frame when the series has several images.
- **Identity.** `PatientID` and `PatientName` are the label; birth date, sex and age are blank; the E2E's first-name and patient-ID slots are never read. Laterality, acquisition date/time and Manufacturer come from the series. UIDs derive from the file's SHA-256 and the series key, so re-converting gives byte-identical files. Every written file is read back and its frame count, size and pixel digest checked.
- **Finding.** The old path took the B-scan geometry records of the whole file (`read_all_metadata()['bscan_data']`) for every volume, so the second and later volumes of a file got the first volume's lateral and slice spacing. `convert_series` uses the series' own records. On the real export below, the slice spacing of the four volumes was 0.0619-0.0629 mm against 0.0654 mm from the old path (the 6 x 6 mm cube the geometry notes describe gives about 0.062); axial spacing is identical. The pixels are identical.

**Server** (`E2eSplitUpload`, both controllers, `split=true`):

1. The `.e2e` is streamed to `e2eUploadsPath/<uuid>.e2e` (digest on the way). A digest already in `ingest_item.e2e_source_sha256` (or an item of the first layout) is a `409` before anything else.
2. `describe`. Then `excludeSeries` (comma-separated keys, validated for shape first) is checked against that list: an unknown key is a `400`, excluding every series is a `400`, and in both cases nothing was converted, stored or itemised.
3. The label rule is unchanged (header surname, folder must agree, only a label that resolves to one visible subject is written down). The label handed to the converter, and later to the DICOM sidecar, is the matched subject's own label, or none while the file is parked.
4. `POST /e2e/convert` for the kept series. The app copies each file into the DICOM ingest store (`IngestArtifactStore`, digest checked against the converter's), has the DICOM sidecar describe and pseudonymise it in place (the existing DICOM upload step: preview PNG, SOP UIDs, pixel digest, OCT classification), and files it as an ordinary `kind='dicom'` row. The row keeps `e2e_series_key`, `series_modality`, `series_frames`, and the new `e2e_source_sha256`. `original_filename` is `<kind>_<eye>_<key>.dcm`: the E2E's own name derives from the patient's name and is not kept. `scan_index` is not set.
5. The rows are written in one transaction. Same-picture detection (DR-036) now runs for every series, on the DICOM sidecar's pixel digest; a series whose picture is already here is parked and audited as held back. The unique index `(e2e_source_sha256, e2e_series_key)` refuses two concurrent uploads of one export.
6. **OCT through the DICOM path (DR-039).** A bound OCT volume with an eye is handed to `RetinalJobFollower.ensure`, exactly as a DICOM OCT upload and an inbox bind do: the study's inference switch and the visit plan decide the tasks, the job names the item's own DICOM, `/preprocess` receives that DICOM, and `handleRemote` follows. `IR`, `FA`, line scans and parked items start nothing. The response lists the jobs under their item.
7. After the commit the `.e2e` moves to `core.retinalInference.e2eSourcePath` (its modification time set to the move, so the retention window starts at the upload) and the convert directory is removed. On any failure before the commit the staged file, the stored DICOMs and their previews, and the convert directory are all removed. A converter or DICOM sidecar that is not configured or reachable is a `503`, an error or an unusable answer a `502`.

**Response** (`201`, `E2eSplitCommitResponse`): `sha256` (of the `.e2e`), `headerPatient`, `subjectLabel`, `parked`, `items[]` (`ingestItemId`, `seriesKey`, `seriesModality`, `laterality`, `acquisitionDate`, `frames`, `imagingModalityId`, `status`, `jobs[]`, `heldBack`, `sameImageAs`), `excluded[]` (the left-out `seriesKey`s), and `kind`/`format` = `e2e`. `409` is `E2eSplitDuplicateResponse` (`message`, `duplicate`, `existingIngestItemIds`). The preflight (`?sha256=&split=true`) is `E2eSplitPreflightResponse` (`exists`, `split`, `existingIngestItemIds`) and also finds an export whose source copy has been deleted.

**Typed responses.** The split commit and the split preflight are their own handler methods on both controllers (`params = "split=true"`), returning the DTO records directly; every other outcome is thrown as `SplitUploadRejection` and written by `ApiExceptionHandler` with the same status and body as before. `api.ts` generates the types from the OpenAPI spec.

**Previews.** A series item is an ordinary DICOM item, so the inbox uses the existing `/ingest/{id}/preview` (the DICOM sidecar's PNG). `GET /ingest/{id}/series-image`, its sidecar client method and the `previewUrl` special case are removed. The sidecar's `GET /e2e/series-image` stays (diagnostics, parity tests); the app does not use it, and the browser renders its own thumbnails before upload.

**Retention.** `E2eSourceRetentionService` and a nightly Quartz job (03:45) delete `<uuid>.e2e` files in `e2eSourcePath` older than `core.retinalInference.e2eSourceRetentionDays` (default 90) by modification time, one audit row (type 209 `e2e_source_deleted`) per pass that deleted something. Only regular `<uuid>.e2e` files directly in that directory are touched. The items stay. The shared-stored-file logic (retention of dismissed items, undo) is kept for rows of the first layout; new items have a file of their own, which the existing dismissed-item sweep and the undo delete with the row.

**Migration** `lc-muw-2026-10-14-e2e-source.xml`: `ingest_item.e2e_source_sha256`, unique `(e2e_source_sha256, e2e_series_key)` where set, audit type 209; each with a rollback.

**Directories.** `e2e-convert` (app and sidecar, read-write) and `e2e-source` (app only) in `compose.yaml`, `deploy/compose.production.yaml`, `setup-ubuntu-host.sh` (and the nightly backup list), `.gitignore`; settings `core.retinalInference.e2eConvertPath`, `e2eSourcePath`, `e2eSourceRetentionDays` in `datainfo.properties`. See [deploy/README.md](../../deploy/README.md).

**Real export, checked read-only** (one 599 MB export, 38 series, in a container, nothing printed that identifies anyone):

| Series kind | Series | Frames | DICOM bytes |
|---|---|---|---|
| OCT volume | 4 | 388 | 394.2 MB (largest 98.5 MB) |
| OCT line scan | 4 | 4 | 4.6 MB |
| IR | 2 | 2 | 4.7 MB |
| BAF | 2 | 2 | 4.7 MB |
| BR | 2 | 2 | 4.7 MB |
| IRAF | 2 | 2 | 4.7 MB |
| qAF | 8 | 96 | 56.6 MB |
| ICGA | 14 | 26 | 61.4 MB |
| **Total** | **38** | | **535.6 MB** |

`convert_series` for all 38 took 16.1 s with a peak of 397 MB resident (417 MB after a second conversion of three series, which gave identical digests); the `describe` step took 8.2 s on its first read through the container's bind mount. `read_oct_volume()` for one volume takes 7 s and 2.1 GB. Every file re-read with the frame, row and column counts that `describe` gave. The four OCT DICOMs went through `read_dicom_oct` and the de-identification check and matched the `.e2e` path: frames (97), dimensions (496 x 1024), eye, acquisition date, spacing order `standard`, axial spacing and every pixel; lateral spacing differs by at most 1.5 % and slice spacing by about 5 % for the reason under "Finding".

**Deviations.** `excluded` is a list of keys, not objects. `original_filename` is a generated name. Same-picture detection no longer matches an earlier `.e2e`-fingerprinted row (a different digest algorithm). A dense OCT scan whose lateral spacing is also about 3.9 um is refused by `/preprocess` as `spacing_ambiguous` (the DICOM path decides the axial value physically and does not trust the file's own writer mark, by design); the real exports have 5.7-5.8 um.
