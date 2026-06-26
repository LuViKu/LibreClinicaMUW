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
