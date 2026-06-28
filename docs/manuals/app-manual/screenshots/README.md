# Screenshots

These figures are **generated**, not hand-placed. Run the capture harness
([`web/src/spa/tests/manual/capture-manual.spec.ts`](../../../../web/src/spa/tests/manual/capture-manual.spec.ts))
against a running SPA with demo data — see [the manual index](../README.md#how-the-screenshots-are-generated)
for the exact command.

Layout (one folder per role, plus `common/` for the shared login/study-picker shots):

```
screenshots/
  common/        00-login, 01-first-login, 02-pick-study
  administrator/ 00-home … 16-scheduled-jobs, 20-subject-detail
  data-manager/  00-home … 11-import-crf-data
  monitor/       00-home … 05-datasets
  investigator/  00-home … 02-add-subject, 20-subject-detail
  crc/           00-home … 02-add-subject, 20-subject-detail
```

The filenames match the `![…](screenshots/<role>/<id>.png)` references in the
role chapters, so regenerating overwrites every figure in place.

## Captured so far (2026-06-28)

46 figures captured against the live demo stack as the dedicated `manual_*`
accounts, showing real seeded data (subjects M-001…M-007, visit statuses,
discrepancy queues, audit log, the filled Add-Subject form, the subject
casebook, SDV, etc.).

### Known gaps — regenerate when convenient

- **Data Manager** `02-build-study`, `10-create-dataset`, `11-import-crf-data`
  and **Administrator** `13-system-status` rendered blank on the final run
  (the demo stack was under heavy load and these async views didn't paint in
  time). Re-run the harness on a quiet stack to capture them.
- **OCT / retinal metrics viewer** (`investigator/21-retinal-viewer`) **is
  captured** — for subject EIAMD150 (RIS — Retinal Imaging Study), whose jobs
  are bound to visits. Taken via the `capture: oct-viewer` test, logging in as a
  user with a RIS grant and switching to that study:
  `MANUAL_OCT_USER=… MANUAL_OCT_PASS=… MANUAL_OCT_STUDY=RIS MANUAL_RETINAL_JOB_ID=16`.
  (The dedicated `manual_*` accounts are scoped to the Default Study, where the
  retinal jobs are parked/unbound — hence the cross-study account here.)
