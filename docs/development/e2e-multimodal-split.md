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

> **Amended 2026-10-13 (subject rule).** Exports can land in one shared watch folder, so the folder cannot be relied on. The subject is the study label in the header's **surname** slot. The first-name and patient-ID slots can hold a clinic number and are never used. When the file sits in a patient folder, that folder must agree with the label; otherwise the file is parked. See [heidelberg-e2e-format-notes.md](heidelberg-e2e-format-notes.md). This supersedes decision 3 and the folder rules in Contract 3 below.

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
{ "storedName": "3f2b8c1e-....e2e", "expectedLabel": "EIAMD005" }
```

Response:

```json
{
  "headerPatient": "empty | matches | differs",
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

- **No identifying data.** The response never carries a name, a patient ID or a birth date. `headerPatient` compares the header ID with `expectedLabel` inside the sidecar.
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
- `patientId`: the subject label, taken from the folder name.
- `device=spectralis`
- Optionally `studyEventId`, to bind every series to one visit (the staff page's batch visit), or `park=true`.

`scanDate`, `laterality` and `scanIndex` are not sent in split mode. The server takes them per series from `/e2e/describe`.

Response `201`:

```json
{
  "sha256": "…",
  "headerPatient": "empty | matches | differs",
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
2. **Describe.** The server calls `/e2e/describe` with the folder label.
3. **Create one item per series.** Each `ingest_item` row has:
   - kind `e2e`, the same stored path and SHA-256;
   - `e2e_series_key`, `series_modality` and `series_frames` (new columns);
   - laterality and acquisition date from the series;
   - `scan_index = octVolumeIndex` for OCT and OCT_LINE, `null` otherwise.

   Deduplication is unique on `(sha256, e2e_series_key)`. The existing `(sha256, scan_index)` uniqueness is kept for non-split rows.
4. **Bind or park.**
   - If `headerPatient` is `differs`, every item is parked.
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
- **Failure of the sidecar.** The stored file is deleted and nothing is filed: 503 when the converter is not configured or unreachable, 502 for an error or an unusable answer (duplicate or malformed `seriesKey`, unknown `seriesModality`, `headerPatient` outside `empty|matches|differs`), 422 for a file without series.
- **Inbox.** Rows carry `seriesModality`, `seriesFrames`, `seriesKey`; `GET /api/v1/ingest/{id}/series-image?frame=0` proxies `/e2e/series-image` under the same visibility rules as the other previews. `previewUrl` of a series row points at it.
- **Retention and undo.** The retention sweep already kept a file while another row names it; the undo endpoints now do the same (they used to unlink the file unconditionally).
- **Limits.** `web.xml` multipart 2 GiB; nginx 2048m, unbuffered request, 30-minute send/read timeouts on the public, ingest-upload and event-CRF OCT upload locations. The Tomcat connector has no custom settings in this repository (`maxPostSize` only concerns form-urlencoded bodies; `maxSwallowSize` only an aborted upload).

## Contract 3: clients

**Export Watcher** (`deploy/export-watcher/ExportWatcher.ps1`):

- **Subject.** The subject is the name of the folder that directly contains the file. Both clients use this rule. An E2E file directly under the watch folder has no subject: it is skipped and reported.
- **Upload.** For each settled file, the watcher checks the preflight by a streamed SHA-256, then makes one streamed commit with `split=true`. The upload uses `StreamContent` and a timeout sized for 2 GiB. The watcher no longer loops over volumes, and no file is read whole into memory.
- **Moving files.** A `201` or `409` moves the file to `_uploaded`, as before.

**Browser upload** (`UploadWorkbench`):

- **Selecting files.** A folder picker and folder drag-and-drop. For an E2E file, the subject is the folder that directly contains it, as in the watcher. A file picked on its own has no subject, and the user searches for one.
- **Hashing.** The SHA-256 is computed in chunks, never by reading the whole file into memory. Large files are processed one at a time.
- **Rows.** One row per E2E file, showing the subject from the folder. After commit, the row lists its series items (modality, eye, frames, status).
- **Size.** Files over 2 GiB are refused in the browser before upload.
- **De-identification.** The browser de-identification path used on internet-facing deployments keeps its current per-volume behaviour. Split mode applies where the browser does not de-identify.

## Out of scope

- **Study setup.** Configuring the drusen study (visits, catalogue entries with `e2e_series`, CRF items) happens once the study exists.
- **Device-specific analysis** of qAF, ICGA or FA.
