# Retinal-job admin view — follow-up plan

> **Status:** scoping doc, no implementation yet. Authored 2026-06-19 after
> [feature/muw-portal-followup-fixes](https://github.com/LuViKu/LibreClinicaMUW/compare/lc-develop...feature/muw-portal-followup-fixes)
> diagnosed a P0 gap left by PR #211 (Wave 2B retinal followups).

## Existing surfaces (what works today)

PR #211 (Wave 2B) introduced `SubjectRetinalTab.vue` + `ParkedScansList.vue` +
the per-subject biomarker viewer. They are reachable via:

| Surface | Path | Gate |
|---|---|---|
| `SubjectRetinalTab` (per-subject Retinal-Verlauf) | Subject Matrix → click subject → scroll | `retinalJobCount > 0` for that subject ([SubjectDetailView.vue:509-512](../../../web/src/spa/src/views/SubjectDetailView.vue#L509-L512)) |
| `RetinalMetricsView` (single job, fundus overlay, B-scan, biomarker payload) | "View" link in the SubjectRetinalTab history table | role gate: Investigator / Monitor / Data Manager / Administrator |
| `ParkedScansList` (intended: parked jobs for one subject) | Embedded in SubjectRetinalTab as `#parked` slot | inherits SubjectRetinalTab's mount gate |

## The gap

Two distinct architectural problems make parked scans **invisible everywhere**:

1. **Parked jobs have no `study_subject_id` linkage.** The
   `retinal_inference_job` row carries an `event_crf_id`, which transitively
   gives `study_event.study_subject_id`. When a row is parked,
   `event_crf_id IS NULL` (per migration
   [`lc-muw-2026-06-18-retinal-job-event-crf-nullable.xml`](../../../core/src/main/resources/migration/lc-muw-2026-06-18-retinal-job-event-crf-nullable.xml)).
   Therefore the row has **no transitive path to any subject**. The
   originally-parsed `patient_id` from the .e2e header is recorded only on
   the `audit_log_event` row (audit_event_type_id=115), not on the job row
   itself.

2. **`listByStudySubject` SQL is an INNER JOIN over `event_crf`.** See
   [RetinalResultsApiController.java:362](../../../web/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/controller/api/RetinalResultsApiController.java#L362):
   ```sql
   FROM retinal_inference_job j
     JOIN event_crf ec ON ec.event_crf_id = j.event_crf_id
     JOIN study_event se ON se.study_event_id = ec.study_event_id
     LEFT JOIN retinal_inference_result r ON r.job_id = j.job_id
    WHERE se.study_subject_id = ?
   ```
   Parked rows fail this INNER JOIN and never appear in the response. Even
   if the join were `LEFT JOIN`, the `WHERE se.study_subject_id = ?` filter
   would still exclude them (the `se` row simply doesn't exist for a parked
   job).

The two combine to a single user-visible symptom: **a parked scan vanishes
into the database with no path back to it from the SPA.** `ParkedScansList`
loads an empty array for every subject; the bind UX is unreachable.

## Reproduction

Verified 2026-06-19 against `lc-develop` @ `0ecbacaa3` + the seed fixture
(PR #213) + the rate-limit hotfix (PR #214):

1. Drop a .e2e file whose `patientId` matches no study_subject on the
   public OCT portal (`/app/oct-upload`).
2. Click **"Parken"** on the resulting `nopatient` row.
3. `POST /api/v1/public/oct-upload/commit?park=true` returns `200` and a
   `jobId`; DB row appears in `retinal_inference_job` with
   `status='parked'`, `event_crf_id IS NULL`, `audit_log_event` carries the
   parsed metadata.
4. Log in as Administrator; navigate to any subject; section is hidden
   (gate fails because zero subject-bound jobs) — even if the section
   mounted, `ParkedScansList` would receive an empty list.
5. There is **no other surface** in the SPA that lists this job.

## Two ways to close the gap

### Option A — add a candidate-subject FK on the job row (smaller diff)

When the public commit hits the `park` branch, the `/resolve` response is
**known** at commit time:

- `nopatient` → there's no candidate to record. Park anyway; the audit row
  carries the parsed patientId.
- `novisit` → exactly one candidate. Record `candidate_study_subject_id`
  for that candidate.
- `ambiguous` → multiple candidates. Record the operator's pick from the
  StudyPickerModal as the candidate; if they park before picking, record
  nothing (similar to `nopatient`).

```sql
ALTER TABLE retinal_inference_job
  ADD COLUMN candidate_study_subject_id INT NULL REFERENCES study_subject;
```

`listByStudySubject` becomes:

```sql
... WHERE (se.study_subject_id = ? OR j.candidate_study_subject_id = ?)
```

The bind action ([`PATCH /retinal-jobs/{id}/bind`](../../../web/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/controller/api/RetinalResultsApiController.java#L606))
clears `candidate_study_subject_id` and sets `event_crf_id` atomically.

**Pros:** parked scans surface on the matching subject's `ParkedScansList`
right where the operator looks; no new view to maintain. Touches one
schema change + one query + one filter on the public commit.

**Cons:** the truly-orphaned `nopatient` parks remain unreachable per-subject.
They still need an admin browser (Option B).

### Option B — cross-study admin browser (the original Slice C from the
[Wave 2B planning doc](../../../docs/development/modernization/phase-e/oct-portal-v2-plan.md))

`GET /api/v1/retinal-jobs?status=PARKED` returns every parked job the
caller's `SiteVisibilityFilter.visibleStudyIds()` permits, joined to the
audit row to recover the parsed patientId + scanDate. New view
`/parked-scans` lists them; bind UX composes `StudyPickerModal` +
`VisitPickerModal`.

**Pros:** handles every case (single-candidate, ambiguous, nopatient).
Matches institutional pattern (Modalities / Audit-Log) — Administrators
get a triage queue.

**Cons:** larger surface — new controller endpoint, new view, new store,
new router entry + role gate, new integration test, new vitest spec. ~1-2
days of work.

## Recommendation

**Ship B.** Option A papers over the symptom but doesn't address the
truly-orphaned `nopatient` parks, which are the most common parked case
(the operator parks precisely because the patient lookup failed). The
cross-study admin view is also a natural home for the next round of
operator triage features (re-queue, dismiss, manual re-resolve) that the
clinical team will inevitably ask for.

If time-boxed, ship the audit-row JOIN list first (≈3h) and defer the
bind UX (≈1d). The list alone gives operators the proof-of-existence they
need to stop worrying about lost uploads, even before the in-app bind
works.

## Out of scope for this doc

- **Bulk-bind UX** for parked scans (one at a time is fine for v1 of the
  admin view).
- **Re-resolve from the admin view** when the patient label was a typo
  that's since been corrected. Could compose `PatientSearchModal` again,
  but defer.
- **OPERATION_FAILED audit on the public search endpoints**. Tracking
  public-portal failure rate is a separate observability slice.

## Workstream linkage

- [PR for the immediate followup fixes](https://github.com/LuViKu/LibreClinicaMUW/pulls?q=is%3Apr+head%3Afeature%2Fmuw-portal-followup-fixes)
  ships the App.vue recursion fix + the "Studie wählen" pick-study wiring.
  Does NOT close this gap.
- Next branch: `feature/muw-retinal-parked-admin` — implement Option B per
  the recommendation above.
