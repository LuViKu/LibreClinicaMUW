# Data retention

What this platform deletes on its own, what it keeps forever, and who decides.

Written for the two pilot studies (HealthAEye fundus imaging, the nAMD AI
study), but the rules are platform-wide. If you are looking for how to run a
retention sweep by hand, that is at the end.

The short version: **clinical data is never deleted automatically.** The only
things that expire are artefacts a person has already ruled out, and generated
files that can be regenerated from the data.

---

## What expires

| What | After | Setting | Who removes it |
|---|---|---|---|
| Dismissed fundus images (file + row) | 30 days | `core.ingest.retention.dismissedDays` | Nightly sweep, 03:30 |
| Dataset exports (file + row) | 90 days | `libreclinica.export.retention.days` | Nightly sweep, 03:00 |

### Dismissed images

An operator dismisses an image in the reconciliation inbox when it is not study
data: a test exposure, an image of the wrong patient, or one of the backlog a
camera flushes on first contact. That dismissal is the clinical decision, and
it is recorded in the audit trail permanently.

The photograph itself is a retinal image of an identifiable person, so keeping
it indefinitely after somebody has decided it is not study data is exactly the
indefinite retention a data-protection review asks about. The sweep removes the
file and the row once the review window has passed, and writes one audit row
per pass saying how many it removed.

The window exists so a mistaken dismissal can be caught. Within it, the row is
still in the database and the file is still on disk.

### Dataset exports

An export is a derived file. Removing it loses nothing that cannot be produced
again from the data, and leaving them accumulating is both unbounded disk
growth and an open egress window.

---

## What never expires

- **Clinical data.** Subjects, visits, CRF values. Removal in this application
  is a status change, never a delete: a removed subject is still in the
  database and still in every export that asks for removed rows.
- **The audit trail.** Including the record of every dismissal and every
  automatic write. A sweep that removed audit rows would defeat the purpose of
  having them.
- **Bound images.** An image bound to a visit is study data. It is not swept,
  at any age.
- **Unbound images.** An image nobody has decided about yet is not swept
  either, at any age. A growing unbound backlog is a workflow problem — nobody
  is working the inbox — and deleting it would hide that rather than fix it.
- **Inference artefacts.** Segmentation masks and companion files belong to the
  scan they describe.

---

## Where the files live

Everything on this list is under `/var/lib/libreclinica/` in production and is
covered by the backup step in the [deploy runbook](deploy-runbook.md) §1.

| Directory | Contents |
|---|---|
| `dicom-ingest/` | Received DICOM images and their previews, plus portal uploads |
| `e2e-uploads/` | Uploaded OCT volumes |
| `retinal-artifacts/` | Inference outputs and per-scan companions |

The database stores paths, not files. A restore of the database dump without
these directories gives a database full of references to files that are no
longer there, which is why the backup step archives both in the same window.

---

## Checking that the sweeps are running

Each pass writes one audit row, so silence means it is not running.

```bash
# Dismissed images
docker compose exec db psql -U clinica libreclinica -c \
  "SELECT audit_date, new_value FROM audit_log_event
    WHERE audit_table = 'image_ingest' AND entity_name = 'Retention sweep'
    ORDER BY audit_date DESC LIMIT 5;"

# Dataset exports
docker compose exec db psql -U clinica libreclinica -c \
  "SELECT audit_date, new_value FROM audit_log_event
    WHERE audit_table = 'dataset' AND entity_name = 'Retention sweep'
    ORDER BY audit_date DESC LIMIT 5;"
```

A pass that removed nothing writes no row, so an empty result on a quiet
instance is expected. If you need to know the schedule is alive rather than
just idle, check the application log at the scheduled times for
`ImageIngestRetentionService` and `ArchivedFileRetentionService`.

---

## Changing a window

Both settings live in `datainfo.properties` and take effect at the next
restart. Setting either to a non-positive value falls back to the default
rather than disabling the sweep — there is deliberately no "keep forever"
switch for dismissed images, because that is the state the sweep exists to
prevent.

To pause a sweep for a specific reason (an investigation, a data request),
lengthen the window rather than removing the schedule, and record why.

---

## Deleting something that is not covered

There is no supported path for deleting clinical data through the application,
by design. A regulatory erasure request is a decision for the study's data
protection officer and the investigator together, executed against the database
with a recorded rationale, and it must leave the audit trail intact.
