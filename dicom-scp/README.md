# dicom-scp — DICOM C-STORE receiver (DR-025)

A DICOM **Storage SCP** ([pynetdicom](https://pydicom.github.io/pynetdicom/)) that
receives fundus images the **Optomed** modality pushes over C-STORE for the
**HealthAEye study**, writes the Part-10 object + a rendered preview into the
shared ingest store, and hands them to the app's internal ingest endpoint
(`POST /pages/api/v1/internal/dicom-ingest`, `DicomIngestApiController`), which
enqueues an `ingest_item` row (`source_kind='dicom'`) for the SPA reconciliation
inbox.

It also serves a **Modality Worklist** (C-FIND): the Optomed Lumo's standard-DICOM
integration is worklist-driven — the camera pulls the scheduled patient/exam
from us, then C-STOREs the study carrying our accession (`LC<study_event_id>`)
and a deterministic StudyInstanceUID, so the ingest endpoint **auto-binds** it
to that visit (no reconciliation needed). Worklist entries come from the app's
`GET /pages/api/v1/internal/dicom-worklist?from=&to=&callingAe=` (scheduled
`study_event` rows at the camera's location, see below). The Remidio FOP (an iPhone + browser) has no DICOM and uses the separate
upload page instead.

## Configuration (`DICOM_SCP_*`)

| Env | Default | Purpose |
|-----|---------|---------|
| `DICOM_SCP_AE_TITLE` | `LIBRECLINICA` | our SCP's AE title |
| `DICOM_SCP_HOST` / `DICOM_SCP_PORT` | `0.0.0.0` / `11112` | listener bind |
| `DICOM_SCP_ALLOWED_CALLING_AE_TITLES` | *(empty = any)* | comma-separated calling-AE allow-list |
| `DICOM_SCP_STORE_PATH` | `/var/lib/libreclinica/dicom-ingest` | shared ingest store (also mounted by the app) |
| `DICOM_SCP_INGEST_URL` | *(required)* | the app internal ingest endpoint |
| `DICOM_SCP_INGEST_TOKEN` | *(required)* | shared secret; must equal `core.dicom.ingest.token` on the app |
| `DICOM_SCP_WORKLIST_URL` | *(optional)* | the app internal worklist endpoint; blank = C-FIND answers failure |
| `DICOM_SCP_LOG_LEVEL` | `INFO` | `DEBUG` dumps every A-ASSOCIATE-RQ (proposed SOP classes / transfer syntaxes) and DIMSE message — the way to see what a modality negotiates when its UI only says "failed". At `INFO`, pynetdicom's per-dataset dumps (which include PatientID) are suppressed. |

### The calling AE title is the camera's source (DR-040)

The receiver hands the calling AE title (trimmed of padding) to the app as
`sourceAeTitle`. The app files each title as an **ingest source**: the first
file (or worklist query) from a title makes a source, at the one location in
single-location mode and with no location in multi mode, where an
administrator assigns it on System → Standorte → Quellen; every file is
stamped with its source and, as its origin, the source's location.

**The worklist is the camera's location's (DR-040 phase 3).** A C-FIND passes
the calling AE title to the app as `callingAe`, and the app lists only the
visits of studies at that title's location (within
`core.dicom.worklist.studyOids`). In single-location mode that is the whole
schedule, as before. In multi mode a camera whose title has no location yet is
offered an empty worklist until it is assigned, and a request without a title
(a receiver older than this) is refused, so the receiver and the app are
upgraded together. A title is 1 to 16 of letters,
digits, space, `.`, `_`, `-`; anything else, or no title, files without a
source. Because an **empty allow-list accepts any title**, the app makes at most
200 sources and files a title past that without one (one warning an hour), so
set `DICOM_SCP_ALLOWED_CALLING_AE_TITLES` to the cameras' AE titles.

### Verified against the Optomed Lumo (2026-09-17)

Calling AE `OPTOMEDLUMO`, station AE `LUMO`. Flow: C-ECHO → MWL C-FIND (keys: SPS start date, station AE; charset `ISO_IR 192`; also proposes Study-Root Q/R MOVE, which we reject) → C-STORE of *Ophthalmic Photography 8 Bit Image Storage* in **JPEG Baseline (Process 1)**, one object per eye. Storage contexts are therefore offered with every transfer syntax (no transcoding — the object is persisted as received; Pillow decodes JPEG Baseline for the preview). Note the Lumo flushes its **entire** stored-study backlog once a storage target answers.

`INGEST_URL` + `INGEST_TOKEN` are required — the SCP refuses to start without them.

## Run

```sh
python -m dicom_scp            # local (PYTHONPATH=src)
# or, opt-in via compose:
docker compose --profile dicom up -d dicom-scp
```

## Test with dcmtk / pynetdicom (no real device needed)

Synthesise a Secondary Capture / VL Photographic object whose `PatientID` matches
a `study_subject.label` and `StudyDate` a scheduled visit, then push it:

```sh
python -m pynetdicom storescu 127.0.0.1 11112 sample.dcm -aec LIBRECLINICA -aet OPTOMED_AE
# or: storescu -aec LIBRECLINICA 127.0.0.1 11112 sample.dcm   (dcmtk)
```

A row should appear in `ingest_item` (UNBOUND) and, once the reconciliation
inbox lands, in the SPA. Confirm the real SOP class + tag layout against the
first genuine Optomed sample before production.

## Tests

```sh
pip install -r requirements.txt pytest
PYTHONPATH=src python -m pytest tests -q
```
