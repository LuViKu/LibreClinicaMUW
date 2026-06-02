# Session-1 plan — A1: Discrepancy-note workflow

**Adapter gap:** [A1 in the registry](./issue-draft-editor-adapter-gap.md#a--workflow-blockers). The SPA's `note.ts` declares the 5-value `NoteStatus` enum and the design note mentions a `canCloseNote(role, status)` helper, but the helper isn't implemented, the `NotesDiscrepanciesView` has no row-level action buttons, and the backend has no endpoint to transition a note's status. **One backend endpoint + the SPA UI it unblocks.**

**Branch:** `feature/muw-phase-e-a1-note-workflow` cut from `lc-develop` (which currently sits at `3a2b1c7c5`, the PR #56 merge).

**Why A1 first:**
1. Highest clinical-quality impact — query-closure is the central QA workflow.
2. SPA UI primitives are partly there (status enum exists; the design comments document the intent).
3. Scope fits one session: 1 new endpoint, 1 new request DTO, ~30 lines of SPA template + 2 helpers.
4. Establishes the **role-based authorization pattern** that every subsequent A-series gap (A2 subject edit, A3 lifecycle, A4 event edit, A5 CRF reopen, A6 SDV un-verify) will reuse verbatim.

---

## Backend slice

### B1. New request DTO

**File:** `web/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/controller/api/AddThreadEntryRequest.java` (new)

```java
@Schema(name = "AddThreadEntryRequest")
public record AddThreadEntryRequest(
        /** New status for the parent note. Required. One of:
            'updated' (Investigator response), 'resolved' (Investigator
            marks complete), 'closed' (Monitor or DM accepts), 'not-applicable'. */
        String newStatus,
        /** Free-text reply / explanation. Required for status transitions
            EXCEPT 'closed' (closure may be wordless per legacy convention). */
        String description,
        /** Optional reassignment — user_name of the new assignee. */
        String assignedTo
) {}
```

### B2. New controller method

**File:** [`DiscrepancyApiController.java`](../../../../web/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/controller/api/DiscrepancyApiController.java) — add a `@PostMapping("/{parentId}/thread")` below the existing `add(...)` method (line 235).

```java
@PostMapping("/{parentId}/thread")
@ApiResponse(responseCode = "200",
             content = @Content(schema = @Schema(implementation = DiscrepancyNoteDto.class)))
public ResponseEntity<?> appendThread(@PathVariable long parentId,
                                       @RequestBody AddThreadEntryRequest body,
                                       HttpSession session) {
    // 1. Auth + active study (mirror existing add()) — throw if missing
    // 2. Load parent note via DiscrepancyNoteDAO.findByPK(parentId)
    //    -> 404 if missing
    // 3. Site-visibility check: parent.studyId ∈ SiteVisibilityFilter.visibleStudyIds(...)
    //    -> 403 if not
    // 4. Role × transition matrix (see B3 below) -> 403 if illegal
    // 5. Insert child note: parent_dn_id=parentId, resolution_status_id=mapStatus(newStatus),
    //    description=body.description, owner_id=currentUser, date_created=now,
    //    entity_type/entity_id/study_id copied from parent (per legacy convention)
    // 6. Update parent's resolution_status_id to match the new latest child
    // 7. (optional) Update parent's assigned_user_id if body.assignedTo present
    // 8. audit_log_event via existing trigger (table-trigger handles this)
    // 9. Return refreshed DiscrepancyNoteDto for the parent (re-run the existing GET projection)
}
```

### B3. Transition matrix (the rule the controller enforces)

Mirrors the legacy `ResolveDiscrepancyServlet` + `UpdateDiscrepancyNoteServlet`:

| Current status | Permitted next | Required role |
|---|---|---|
| `new` | `updated` | Investigator, CRC, Data Manager, Administrator |
| `new` | `not-applicable` | Data Manager, Administrator |
| `updated` | `updated` (additional reply) | any USER role on the study |
| `updated` | `resolved` | Investigator, CRC |
| `updated` | `not-applicable` | Data Manager, Administrator |
| `resolved` | `closed` | Monitor, Data Manager, Administrator |
| `resolved` | `updated` (reopen, e.g. monitor rejects resolution) | Monitor, Data Manager, Administrator |
| `closed` | (terminal — no transitions; only Admin can reopen via legacy path) | — |

Encode as a static `Map<Pair<Status, Status>, Set<RoleId>> ALLOWED_TRANSITIONS` in the controller (or a sibling `NoteTransitionMatrix` utility class for testability).

### B4. Tests

**`DiscrepancyApiControllerTest.java`** (extend the existing) — add MockMvc cases:
- `appendThreadReturns401WhenAnonymous`
- `appendThreadReturns400WhenNoActiveStudy`
- `appendThreadReturns404WhenParentMissing`
- `appendThreadReturns403WhenMonitorAttemptsResolved` (role mismatch)
- `appendThreadReturns400WhenStatusOmitted`
- `appendThreadReturns400WhenDescriptionMissingForUpdated`

**`DiscrepancyApiControllerDatabaseIT.java`** (new — first IT for this controller family) — happy paths:
- Investigator transitions `new → updated → resolved`; assert parent's `resolution_status_id` becomes 3 and a child note exists.
- Monitor closes a `resolved` note; assert `resolution_status_id = 4` and audit row inserted.

---

## SPA slice

### S1. Helpers in `web/src/spa/src/types/note.ts`

Add three role-aware predicates the View imports:

```ts
export function canRespondToNote(role: UserRole, status: NoteStatus): boolean {
  return (
    (role === 'Investigator' || role === 'CRC' || role === 'Data Manager') &&
    (status === 'new' || status === 'updated' || status === 'resolved')
  )
}

export function canResolveNote(role: UserRole, status: NoteStatus): boolean {
  return (
    (role === 'Investigator' || role === 'CRC') &&
    status === 'updated'
  )
}

export function canCloseNote(role: UserRole, status: NoteStatus): boolean {
  return (
    (role === 'Monitor' || role === 'Data Manager' || role === 'Administrator') &&
    status === 'resolved'
  )
}
```

(The exact rules are mirrored from B3 above. Keep the predicates source-of-truth in TS so the UI hides what the backend would 403 — defense in depth.)

### S2. Store method in `web/src/spa/src/stores/notes.ts`

Add:

```ts
async function appendThread(parentId: string, body: {
  newStatus: NoteStatus
  description?: string
  assignedTo?: string | null
}): Promise<DiscrepancyNote> {
  state.value = 'saving'
  try {
    const refreshed = await apiPost<DiscrepancyNote>(
      `/pages/api/v1/discrepancies/${parentId}/thread`, body)
    // Update the in-memory note (mutate the array entry by id)
    const idx = items.value.findIndex(n => n.id === parentId)
    if (idx >= 0) items.value[idx] = refreshed
    state.value = 'ready'
    return refreshed
  } catch (e) {
    error.value = formatApiError(e)
    state.value = 'error'
    throw e
  }
}
```

Export it from the returned object alongside `add`.

### S3. View — wire action buttons in `NotesDiscrepanciesView.vue`

Inside the table row template for each note (likely around the `lastActivityAt` column), add a conditional action group:

```vue
<td class="px-3 py-2 text-right">
  <button v-if="canRespondToNote(auth.user.role, note.status)"
          class="text-xs text-muw-blue underline"
          @click="openThreadComposer(note, 'updated')">
    Respond
  </button>
  <button v-if="canResolveNote(auth.user.role, note.status)"
          class="ml-2 text-xs text-muw-teal-700 underline"
          @click="openThreadComposer(note, 'resolved')">
    Mark resolved
  </button>
  <button v-if="canCloseNote(auth.user.role, note.status)"
          class="ml-2 text-xs text-slate-700 underline"
          @click="openThreadComposer(note, 'closed')">
    Close
  </button>
</td>
```

`openThreadComposer(note, intendedStatus)` opens an existing slideover/modal primitive (or add a small inline `<TextArea>` if one doesn't exist) — collects the `description`, then calls `notes.appendThread(note.id, { newStatus, description })`.

### S4. Vitest coverage

Add `web/src/spa/src/stores/__tests__/notes.test.ts` (mirror PR #54 #6's `auth.test.ts` pattern):
- `appendThread` POSTs to the right URL with the right body
- 4xx response surfaces `error.value` and re-throws
- Success updates `items.value` in-place by id

---

## Verification

```sh
# Backend
mvn -pl web -am test                   # 49 web + new IT pass

# SPA
cd web/src/spa
pnpm tsc --noEmit                      # clean
pnpm run test -- --run                 # 96 + new notes.test pass

# Spec regenerates
docker compose up --build -d           # stack up
pnpm run codegen:openapi               # api.ts now carries AddThreadEntryRequest
git diff web/src/spa/src/types/api.ts  # diff shows ONE new schema + one new path
```

Manual walk (golden path):
1. Log in as `physician` (Investigator) — open a `new` note → "Respond" button visible → submit "Confirmed via source" → status flips to `updated`.
2. Log out, log in as `monitor` (Monitor) — same note now shows "Close" button only after physician marks it `resolved`. (To enable that you also click "Mark resolved" while still logged in as physician.)
3. Audit log at `/app/audit-log` shows two new rows: the `updated` and the `resolved` transition (the table trigger handles audit; verify the SPA renders them with human-readable type from PR #54 #8).

---

## Deliverables

1. **Backend commit:** `DiscrepancyApiController.appendThread` + `AddThreadEntryRequest` DTO + `NoteTransitionMatrix` utility + tests. Spec regenerates with new path + schema.
2. **SPA commit:** `notes.ts` `appendThread` + role helpers in `note.ts` + buttons in `NotesDiscrepanciesView.vue` + `notes.test.ts`.
3. **Stacked PR**, base = `lc-develop`. Title: `feat(phase-e a1): discrepancy-note workflow — POST /discrepancies/{id}/thread + SPA buttons`.

## Follow-up next session

Pick from the registry. Recommended next slice:

- **A5 (CRF reopen)** — single endpoint, same role-matrix pattern, ~half-day. Pairs well with the audit-event-type allocation from B1.
- OR **A2 (Subject demographics edit)** — broader UI work but reuses the A1 authorization pattern.

## Out of scope (deliberate)

- **Bulk close** — closing N queries at once via a multi-select toolbar. Add only if user testing demands it.
- **Re-assignment as primary action** (assigning a query to another user) — body field reserved (`assignedTo`), but no dedicated UI in this slice.
- **Threaded UI display** — the current table view shows the parent row only. Surface the child-note chain as a slideover panel in a follow-up slice.
- **Wordless transitions** — the backend permits `description=null` for `closed`, but the SPA always collects a comment for consistency.
- **Audit-event-type allocation** — relies on the existing `study_subject_trigger`-style audit row that the discrepancy_note table-trigger already writes. No new audit-type seed needed for this slice.
