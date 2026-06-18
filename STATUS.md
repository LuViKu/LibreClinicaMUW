# Wave 2B — SPA portal completion

## Result: PASS

### Vitest default run
`Test Files  96 passed (96)` / `Tests  948 passed | 1 skipped (949)` —
0 failures, 0 errors.

The pre-existing `1 skipped` survives from Wave 1C — no behaviour
change.

### vue-tsc --noEmit
`EXIT=0` — clean.

## What ships

### 1. SPA api plumbing
- **`web/src/spa/src/api/client.ts`** — adds the public `apiPatch<T>()`
  wrapper. The underlying `request<T>` already supported PATCH; this
  is the first SPA consumer.
- **`web/src/spa/src/api/retinal.ts`** — typed wrappers for Wave 1B's
  two new endpoints:
    * `bindParkedJob(jobId, { eventCrfId })` → `PATCH /pages/api/v1/retinal-jobs/{jobId}/bind`
    * `searchStudySubjects(q, limit)` → `GET /pages/api/v1/study-subjects/search?q=&limit=`

### 2. i18n
- **`web/src/spa/src/locales/de.json`** — DE verbatim:
    * `octPortal.modals.patientSearch.{title,placeholder,empty,tooShort,searching,cancel,siteLabel,studyLabel,loadError}`
    * `octPortal.modals.visitPicker.{title,empty,loading,cancel,loadError}`
    * `retinal.parked.{title,subtitle,empty,loading,loadError,colTask,colEye,colEnqueued,colAction,bindAction,bindSuccess,bindConflict,bindError}`
- **`web/src/spa/src/locales/en.json`** — EN mirrored, every new value
  prefixed with `[NEEDS_REVIEW] ` per the Wave 1C pattern.

### 3. PatientSearchModal
- **`web/src/spa/src/components/octportal/PatientSearchModal.vue`**
  — debounced (300 ms) search against the Wave 1B endpoint. Results
  rendered with study + site context for disambiguation. Empty / "too
  short" (< 2 chars) / loading / error states wired. Emits
  `subject-picked` with the full `StudySubjectSearchHit`. Seeds the
  search field from the operator's parsed PatientId so they don't
  re-type the unresolved id.

### 4. VisitPickerModal
- **`web/src/spa/src/components/octportal/VisitPickerModal.vue`**
  — two-hop fetch:
    1. `GET /pages/api/v1/events?subjectId=<label>` for the visit list
       (Wave 1B brief is wrong about parameter shape — the existing
       `EventsApiController` identifies subjects by LABEL, not
       numeric id, so the component takes both `studySubjectId` AND
       `subjectLabel`).
    2. On click, `GET /pages/api/v1/events/{eventId}` to extract the
       first non-removed `event_crf_id` — the bind endpoint requires
       it. If no started CRF exists for the visit, the picker emits
       `eventCrfId: -1` so the parent surfaces an error rather than
       silently sending an invalid bind.
  - Emits `event-picked` with `{ eventCrfId, definitionLabel, dateStart }`.
  - Empty / loading / error states wired.

### 5. Store extension — `octPortal.ts`
- `assignFromSearch(rowId, subject)` — replaces the row's candidate
  with the picked subject and re-runs `/resolve` so the matching
  event (or lack thereof) for the scan date is recomputed.
- `setManualVisit(rowId, eventCrfId, label, date)` — bypasses the
  auto-resolve, flips the row to `suggested` with the operator's
  pick wired into `selectedEvent`. Defensively surfaces an error
  inline when `eventCrfId <= 0`.

### 6. OctUploadPortalView wiring
- Drops the `onPickVisitUnsupported` / `onSearchPatientUnsupported`
  no-ops. The `pick-visit` and `search-patient` row emits now open
  their respective modals; on pick the view routes the result through
  the new store actions.
- Both modals mount conditionally — `PatientSearchModal` listens
  on `open=false` until a row picks it; `VisitPickerModal` is gated
  by `v-if="visitTargetSubject"` so it isn't even constructed
  outside the picker flow.

### 7. ParkedScansList
- **`web/src/spa/src/components/retinal/ParkedScansList.vue`** —
  embedded inside Wave 2A's `SubjectRetinalTab.vue` via a named slot:

  ```vue
  <SubjectRetinalTab :subject-id="subjectId">
    <template #parked>
      <ParkedScansList :study-subject-id="subjectId" />
    </template>
  </SubjectRetinalTab>
  ```

- Filters subject jobs client-side to `status === 'parked'` — the
  backend endpoint `GET /pages/api/v1/study-subjects/{id}/retinal-jobs`
  does not accept a `?status=` filter.
- "Visite zuweisen" → opens VisitPickerModal → PATCH bind.
  - 200 happy path: optimistic remove + success toast.
  - 409 conflict (bound by another session in the meantime): refresh
    + conflict toast, **not** an error banner — clinically benign.
  - 4xx/5xx/network: restore the optimistic removal + error banner.

## Note on Wave 2A dependency

Wave 2A's `SubjectRetinalTab.vue` **was not present** in this
worktree at start. ParkedScansList ships standalone with a
`subjectLabel?: string` fallback (defaults to the numeric id as a
string so the visit picker still has something to call
`/api/v1/events?subjectId=…` with).

**Harmonize action**: once Wave 2A's tab lands the main session
should:
1. Add the `#parked` slot to `SubjectRetinalTab.vue` (already
   specified by the Wave 2A brief).
2. Mount `ParkedScansList` from the parent (e.g. `SubjectDetailView`)
   inside the slot, passing both `studySubjectId` and the subject
   label.

## New tests
- **`PatientSearchModal.spec.ts`** — 6 cases: too-short empty state,
  debounce + fetch contract, results render with study + site
  context, `subject-picked` emit with full hit, cancel emit, backend
  error → error banner.
- **`VisitPickerModal.spec.ts`** — 6 cases: fetch by subject label
  on open, row content (label + date + status pill), two-hop
  `event-picked` emit with the first non-removed eventCrfId, empty
  state, cancel emit, backend error → error banner.
- **`ParkedScansList.spec.ts`** — 4 cases: parked-status filter,
  empty state, bind happy path (modal flow → PATCH → optimistic
  remove + success toast), 409 conflict (refresh + conflict toast,
  no error banner).

## Files touched

**New**:
- `web/src/spa/src/components/octportal/PatientSearchModal.vue`
- `web/src/spa/src/components/octportal/VisitPickerModal.vue`
- `web/src/spa/src/components/octportal/__tests__/PatientSearchModal.spec.ts`
- `web/src/spa/src/components/octportal/__tests__/VisitPickerModal.spec.ts`
- `web/src/spa/src/components/retinal/ParkedScansList.vue`
- `web/src/spa/src/components/retinal/__tests__/ParkedScansList.spec.ts`

**Modified**:
- `web/src/spa/src/api/client.ts`
- `web/src/spa/src/api/retinal.ts`
- `web/src/spa/src/locales/de.json`
- `web/src/spa/src/locales/en.json`
- `web/src/spa/src/stores/octPortal.ts`
- `web/src/spa/src/views/OctUploadPortalView.vue`

## Commits (this worktree)
- `bb1e269ec` feat(retinal-followups-2b): SPA api + i18n scaffolding
- `95a4aaefa` feat(retinal-followups-2b): OCT-portal modals replace v1 no-op stubs
- `15fbe4467` feat(retinal-followups-2b): ParkedScansList for Wave 2A integration

Not pushed.

## Surprises + notes

- **Spec vs reality on `/api/v1/events?subjectId=…`**: the brief
  implied the parameter takes a numeric `studySubjectId` but the
  existing `EventsApiController` (referenced as the bind target)
  takes the subject LABEL string. Modelled both on the
  `VisitPickerModal` so the prop signature documents the constraint
  rather than papering over it.

- **`event_crf_id` vs `study_event_id`**: the brief's emit signature
  for `event-picked` uses `eventCrfId` but `GET /api/v1/events`
  returns `study_event_id`. Added a second-hop to
  `GET /api/v1/events/{eventId}` to pull the first non-removed CRF;
  if no started CRF exists for the event we emit `-1` and the parent
  surfaces an error rather than firing an invalid PATCH.

- **`PrickedEvent` named export**: a `type { PickedEvent }` named
  export from a `<script setup>` block doesn't get re-exported the
  way module-style components do. Reverted the export to an internal
  interface and inlined the shape at the call site in the view.

- **OctUploadPortalView spec compatibility**: the existing view
  spec mocks `@/api/octPortal` but not `@/api/retinal`. Since the
  new modals only fire fetches when `open=true` and neither opens
  in the existing test scenarios, the mock surface stays unchanged
  — `948/948 + 1 skipped` confirms no regression.
