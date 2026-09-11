# dicom-scp — DICOM C-STORE receiver (DR-025)

A DICOM **Storage SCP** ([pynetdicom](https://pydicom.github.io/pynetdicom/)) that
receives fundus images the **Optomed** modality pushes over C-STORE for the
**HealthAEye study**, writes the Part-10 object + a rendered preview into the
shared ingest store, and hands them to the app's internal ingest endpoint
(`POST /pages/api/v1/internal/dicom-ingest`, `DicomIngestApiController`), which
enqueues an `image_ingest` row (`source_kind='dicom'`) for the SPA reconciliation
inbox.

It also serves a **Modality Worklist** (C-FIND): the Optomed Lumo's standard-DICOM
integration is worklist-driven — the camera pulls the scheduled patient/exam
from us, then C-STOREs the study carrying our accession (`LC<study_event_id>`)
and a deterministic StudyInstanceUID, so the ingest endpoint **auto-binds** it
to that visit (no reconciliation needed). Worklist entries come from the app's
`GET /pages/api/v1/internal/dicom-worklist?from=&to=` (scheduled `study_event`
rows). The Remidio FOP (an iPhone + browser) has no DICOM and uses the separate
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

A row should appear in `image_ingest` (UNBOUND) and, once the reconciliation
inbox lands, in the SPA. Confirm the real SOP class + tag layout against the
first genuine Optomed sample before production.

## Tests

```sh
pip install -r requirements.txt pytest
PYTHONPATH=src python -m pytest tests -q
```
