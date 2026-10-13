# Multimodal Spectralis exports: one upload, one item per series

**Status:** in progress, 2026-10-13 (server side implemented, see below). Decision record: [DR-041](modernization/decision-record.md#dr-041--a-multimodal-spectralis-export-is-uploaded-once-and-split-into-one-item-per-series-on-the-server).

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
