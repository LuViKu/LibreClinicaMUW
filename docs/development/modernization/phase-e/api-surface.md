# Phase E.4 — Backend API surface inventory

**Date:** 2026-05-30
**Status:** First pass. Lists every mockup → legacy URL → JSON-availability gap. Adapter PRs for category B land per-feature during E.5–E.7.

Per the Phase E execution playbook §E.4, every SPA route consumes a backend endpoint. Each endpoint falls into one of three categories:

- **A. JSON-ready** — the existing `@Controller` already produces JSON via `@ResponseBody` / `@RestController`. The SPA consumes the same URL with no backend changes.
- **B. JSP-only** — the legacy servlet / `@Controller` only forwards to a JSP. A thin `@RestController` adapter wraps the existing domain layer + DAO calls and returns JSON. The JSP path stays alive in parallel for the bake-in window per DR-018.
- **C. Greenfield** — the SPA needs an endpoint that doesn't exist anywhere. Build it.

This inventory drives the E.5–E.7 PR ordering: every workflow PR starts with its B-category adapter PR (small, focused, testable in isolation) before the Vue view PR consumes it.

---

## Per-mockup inventory

| # | Mockup | Legacy URL | Category | Notes |
|---|---|---|---|---|
| 1 | [investigator-subject-matrix](ux-mockups/investigator-subject-matrix.html) | `/ListStudySubjects` | **B** | Servlet forwards to `subjectsMatrix.jsp`. Adapter needed: `GET /pages/api/v1/subjects?siteOid=…` returning `[{ id, secondaryId, gender, status, perEventStatuses[], lastUpdated }]`. Existing DAO surface: `StudySubjectDAO#findAllByStudyId`. |
| 2 | [investigator-add-subject](ux-mockups/investigator-add-subject.html) | `/AddNewSubject` | **B** | Form-POST servlet that redirects to subjects matrix on success. Adapter needed: `POST /pages/api/v1/subjects` with `{ subjectId, secondaryId?, gender, enrollmentDate, group? }`. Existing DAO: `StudySubjectDAO#insert`. |
| 3 | [investigator-crf-entry](ux-mockups/investigator-crf-entry.html) | `/InitialDataEntry` | **B** + **C** | The CRF render is **the** complex screen (964 LOC JSP). B-adapter: `GET /pages/api/v1/eventCrfs/{id}` for the form schema + saved values; `POST /pages/api/v1/eventCrfs/{id}/items` for incremental save. C-additions: a JSON form-schema endpoint translating the existing `CRFVersionBean` / `ItemFormMetadataBean` shape into a render-driven shape the SPA can iterate over. |
| 4 | [monitor-sdv](ux-mockups/monitor-sdv.html) | `/pages/viewAllSubjectSDVtmp` | **A** ✅ | `SDVController.java` already has `@ResponseBody` endpoints at line 148 + 345 — the legacy table consumes them via XHR. SPA reuses the same JSON shape. |
| 5 | [monitor-crf-readonly](ux-mockups/monitor-crf-readonly.html) | `/ViewSectionDataEntry` | **B** | JSP read-only view. Reuses the same `GET /pages/api/v1/eventCrfs/{id}` adapter from #3 with a `readOnly=true` flag. |
| 6 | [monitor-add-query](ux-mockups/monitor-add-query.html) | `/CreateDiscrepancyNote` (current popup) | **B** | `POST /pages/api/v1/discrepancies` with `{ subjectId, eventCrfId, itemDataId, type, description, assignTo, dueDate, severity }`. Existing DAO: `DiscrepancyNoteDAO#insert`. |
| 7 | [notes-discrepancies](ux-mockups/notes-discrepancies.html) | `/ViewNotes` | **B** | Adapter: `GET /pages/api/v1/discrepancies?status=…&subjectId=…&assignedTo=…`. Existing DAO: `DiscrepancyNoteDAO#findAllByFilter`. |
| 8 | [study-audit-log](ux-mockups/study-audit-log.html) | `/ViewAuditLog` | **B** + **C** | B-adapter: `GET /pages/api/v1/audit?dateRange=…&user=…&actionType=…&subject=…`. C-addition: the **Reason-for-Change diff shape** isn't in the current `audit_event_log` row format — the SPA needs `{ before, after }` columns extracted from the existing audit detail message. May need a new view-level DAO method. |
| 9 | [view-events](ux-mockups/view-events.html) | `/ViewStudyEvents` | **B** | Cross-subject event listing. Adapter: `GET /pages/api/v1/events?siteOid=…&status=…&eventDefId=…`. Existing DAO: `StudyEventDAO#findAllByStudyId`. |
| 10 | [view-subject](ux-mockups/view-subject.html) | `/ViewStudySubject` | **B** | Subject detail. Adapter: `GET /pages/api/v1/subjects/{id}` returning subject + per-event CRF status + sign-off state + Casebook PDF URL. |
| 11 | [schedule-event](ux-mockups/schedule-event.html) | `/CreateNewStudyEvent` | **B** | Form POST. Adapter: `POST /pages/api/v1/events` with `{ subjectId, eventDefinitionOid, startDate, endDate?, location? }`. Existing DAO: `StudyEventDAO#insert`. |
| 12 | [dm-manage-users](ux-mockups/dm-manage-users.html) | `/ListStudyUser` | **B** | Adapter: `GET /pages/api/v1/users?siteOid=…&role=…`. Existing DAO: `UserAccountDAO#findAllByStudyId`. |
| 13 | [investigator-sign-subject](ux-mockups/investigator-sign-subject.html) | `/SignStudySubject` | **B** + **C** | B-adapter (preflight): `GET /pages/api/v1/subjects/{id}/preflightForSign` returning `[{ check, status, detail }]` rows. C-addition: the **e-signature event** is a new POST with re-auth payload — see DR-014 §4 and the [Sign Subject mockup](ux-mockups/investigator-sign-subject.html). `POST /pages/api/v1/subjects/{id}/sign { reauthToken }`. |
| 14 | [dm-build-study](ux-mockups/dm-build-study.html) | `/pages/studymodule` | **B** | The 7-task setup tracker. Adapter: `GET /pages/api/v1/studies/{oid}/build-status` returning per-task completion + per-task action URLs. Existing DAOs: many — `StudyDAO`, `EventDefinitionDAO`, `CRFVersionDAO`, `RuleSetDAO`, `SiteDAO`, `UserAccountDAO`. |
| 15 | [dm-update-event-definition](ux-mockups/dm-update-event-definition.html) | `/UpdateEventDefinition` | **B** | Form POST. Adapter: `GET /pages/api/v1/eventDefinitions/{oid}` + `PUT /pages/api/v1/eventDefinitions/{oid}`. |
| 16 | [dm-create-crf](ux-mockups/dm-create-crf.html) | `/CreateCRF` + `/EditCRF` | **B** + **C** | The DM's most complex screen — designer pattern. C-addition required for the **inline item-grid editor**: `PUT /pages/api/v1/crfVersions/{oid}/items` to accept item-by-item edits without full Excel re-upload. Existing path: Excel upload via `/CreateCRFVersion`. The SPA's designer is a parallel write surface; both can coexist behind feature flag per DR-018. |
| 17 | [dm-import-crf-data](ux-mockups/dm-import-crf-data.html) | `/ImportCRFData` | **B** + **C** | Wizard. B-adapter for upload + map: `POST /pages/api/v1/imports/cdiscOdm` returning a staging-id. C-additions for preview & resolve: `GET /pages/api/v1/imports/{stagingId}/preview` returning per-row diff cells + validation status; `POST /pages/api/v1/imports/{stagingId}/commit`. |
| 18 | [login](ux-mockups/login.html) | `/pages/login/login` (404 in current build per E.0) | **B** | The local-account fallback path is a standard form-login — `POST /j_spring_security_check` already works. The Shibboleth bounce is a redirect to the configurable `libreclinica.sso.entryUrl` (DR-014 §3) — no JSON. The first-login profile step is a `GET /pages/api/v1/me/profile` + `PUT /pages/api/v1/me/profile`. **Blocked behind E.0** until `/pages/*` routes resolve in the browser. |

---

## Aggregate category counts

| Category | Count | Implementation cost |
|---|---|---|
| A (JSON-ready) | 1 | trivial (consume) |
| B (JSP-only, needs adapter) | 17 | one focused adapter PR per workflow |
| C (greenfield additions on top of B) | 6 | bundled into the workflow's adapter PR or a follow-up |

The B-adapter PRs are the largest chunk of Phase E.4–E.7 work. Each adapter is small (one `@RestController` + a passthrough to the existing DAO + one MockMvc test) but there are 17 of them. Estimated 2–3 days per adapter at a steady cadence = 6–8 weeks of focused work.

---

## Adapter PR template

Every B-category PR follows the same shape:

1. **`web/src/main/java/.../controller/api/<Feature>ApiController.java`** — `@RestController` at `/pages/api/v1/<feature>/**` with the JSON shape from the mockup.
2. **`web/src/main/java/.../controller/api/<Feature>Dto.java`** — Jackson-annotated DTO (records preferred). Translates from the existing `*Bean` shape.
3. **`web/src/test/java/.../controller/api/<Feature>ApiControllerIT.java`** — MockMvc test pinning the response shape against a stable JSON snapshot. Required gate per the Phase E execution playbook.
4. **OpenAPI annotations** on each method so `openapi-typescript` can generate the SPA-side type definitions automatically (per DR-008's HTTP layer choice).
5. **Spring Security entry** in `SecurityConfig` permitAll list — only the public endpoints (anonymous form, healthcheck). All others fall through to `.anyRequest().hasRole("USER")`.

Output of step 4: `web/src/spa/src/types/api.ts` generated by `pnpm run openapi`, consumed by the Vue views with strict typing.

---

## SPA-side wiring

Each adapter is consumed by a Pinia store + a thin HTTP client wrapper:

```ts
// web/src/spa/src/api/client.ts
export async function apiGet<T>(path: string): Promise<T> { /* fetch with credentials: 'include' */ }
export async function apiPost<T>(path: string, body: unknown): Promise<T> { /* ... */ }
// + apiPut, apiDelete
```

```ts
// web/src/spa/src/stores/subjects.ts
import { defineStore } from 'pinia'
import { apiGet } from '@/api/client'
import type { Subject } from '@/types/api'

export const useSubjectsStore = defineStore('subjects', () => {
  const list = ref<Subject[]>([])
  async function load(siteOid?: string) {
    list.value = await apiGet<Subject[]>(`/pages/api/v1/subjects${siteOid ? `?siteOid=${siteOid}` : ''}`)
  }
  return { list, load }
})
```

The store + adapter pair is the unit Phase E iterates on: each workflow PR ships one pair + its Vue view.

---

## Notes for the Phase D-Sec team

The new `/pages/api/v1/**` adapters need to land in `SecurityConfig`'s `requestMatchers` list. Recommendation: add a single rule `requestMatchers("/pages/api/v1/**").hasRole("USER")` to gate them at the chain level rather than per-endpoint, then narrow individual endpoints as the SPA grows. This keeps SecurityConfig small + auditable.

The `pages` DispatcherServlet's child-context registration issue (E.0) blocks every `/pages/api/v1/**` adapter — the controllers won't register until that fix lands. Adapter PRs can land in the codebase regardless (the Java compiles + the IT tests pass via MockMvc), they just won't be reachable in a browser until E.0 resolves.
