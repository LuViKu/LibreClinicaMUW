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
- **OCT / retinal metrics viewer** (`<role>/21-retinal-viewer`) is **not**
  captured: every retinal job in the demo DB is *parked* (no subject binding),
  so the per-job viewer has no subject context and renders blank. To capture
  it, bind a completed job to a visible subject (the park-bind workflow, or a
  fresh upload through the OCT portal), then re-run with
  `MANUAL_RETINAL_JOB_ID=<that job>`. The harness already supports this.
