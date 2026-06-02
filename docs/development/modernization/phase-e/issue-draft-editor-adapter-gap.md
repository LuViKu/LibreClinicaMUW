# Adapter gap registry — what's missing for full SPA coverage

**Status:** Living document. Phase E.4 shipped 18 viewer endpoints (PR #51, M1–M13); Phase E.5 + PR #56 closed 9 of 14 follow-up TODOs. This doc enumerates the **remaining editor + mutation adapters** the SPA needs to retire the legacy JSP UI per DR-018.

Group prefix legend: **A** = workflow blockers (clinical-data-correction paths the SPA UI promises but can't reach); **B** = self-service (user-account operations on `/me`); **C** = whole feature areas with no REST coverage at all.

Priority: **P0** = clinical-trial regulatory or quality blocker. **P1** = SPA UI exists but is inert. **P2** = legacy-feature parity, lower urgency.

Effort: **S** = single endpoint + happy-path IT, ~0.5 d. **M** = small endpoint family, ~1–2 d. **L** = multi-endpoint slice with new DTOs + SPA work, ~3 d+.

---

## A — Workflow blockers

| ID | Adapter | Have today | Missing | Legacy precedent | Priority | Effort |
|---|---|---|---|---|---|---|
| A1 | Discrepancy-note workflow | `POST /api/v1/discrepancies` (create parent note) | `POST /api/v1/discrepancies/{parentId}/thread` — append a child note with a new status + optional comment (the OpenClinica discrepancy model tracks state via child-note chain, not parent-row updates) | `ResolveDiscrepancyServlet`, `CreateOneDiscrepancyNoteServlet`. SPA's `note.ts` already declares the `'new' \| 'updated' \| 'resolved' \| 'closed' \| 'not-applicable'` enum and a `canCloseNote(role, status)` helper. | **P0** | M |
| A2 | Subject demographics edit | `POST /api/v1/subjects` (create), `GET /api/v1/subjects/{oid}` | `PUT /api/v1/subjects/{oid}` — change `secondaryId`, `gender`, `yearOfBirth`, `groupLabel` | `AdministrativeEditingServlet` (gates per-field by role + study state). | **P0** | M |
| A3 | Subject lifecycle | `POST .../sign`, `POST` (add) | `POST /api/v1/subjects/{oid}/remove` (soft-delete), `POST .../restore`, `POST .../lock`, `POST .../unlock` | `RemoveSubjectServlet`, `RestoreSubjectServlet`. SPA matrix row menu already shows greyed-out items for these. | **P0** | M |
| A4 | Event editing + cancellation | `POST /api/v1/events` (schedule) | `PUT /api/v1/events/{id}` — change `dateStarted`, `dateEnded`, `location`, `status`; `DELETE /api/v1/events/{id}` — cancel | `EditStudyEventServlet`, `RemoveStudyEventServlet` (`reg115` in `LegacyServletRegistry`). | **P0** | M |
| A5 | CRF reopen + lock | `POST .../items` (save values), `POST .../markComplete` | `POST /api/v1/eventCrfs/{id}/markIncomplete` — reopen for editing; `POST .../lock` + `POST .../unlock` (data-manager freeze) | `ResetEventCrfServlet` family. Drives the "correction" path after sign-off. Critical for GCP data-correction workflows. | **P0** | S |
| A6 | SDV un-verify | `POST /api/v1/sdv/verify` (batch verify) | `POST /api/v1/sdv/unverify` — rollback verification when source data changes after the verification stamp | `handleSDVRemove`, `unSdvStudySubject` (controller exists, no REST surface). | **P0** | S |
| A7 | Users — CRUD | `GET /api/v1/users` | `POST /api/v1/users`, `PUT /api/v1/users/{userName}`, `PUT .../role`, `POST .../restrict`, `POST .../restore`, `POST .../resetPassword` | `CreateUserAccountServlet`, `EditUserAccountServlet`, `RestrictUserAccountServlet`, `RestoreUserAccountServlet`, `ChangeUserRoleServlet`, `ResetUserAccountPasswordServlet`. `ManageUsersView.vue`'s "Invite" button has no `@click` handler today. | P1 | **L** |
| A8 | Study config | `GET /api/v1/studies/{oid}/build-status` | `PUT /api/v1/studies/{oid}` (metadata), `PUT .../events/{eventOid}` (event-definition edit), `POST .../sites` (sub-study/site CRUD) | `BuildStudyServlet` and the wizard chain that follows. `BuildStudyView.vue` is purely read-only. | P1 | **L** |

---

## B — Self-service (user-account operations on `/me`)

| ID | Adapter | Have today | Missing | Legacy precedent | Priority | Effort |
|---|---|---|---|---|---|---|
| B1 | Password rotation | `PUT /api/v1/me/profile` (displayName + locale + timezone) | `POST /api/v1/me/changePassword` — body `{ currentPassword, newPassword }` | `ChangePasswordServlet`. Must verify `currentPassword` via `PasswordEncoder.matches`, then re-hash + store with `passwd_timestamp = now()`. Per Phase D.5 audit-on-login precedent, write an `audit_log_event` row for the rotation. | **P1** | S |
| B2 | 2FA enrollment | `MeDto.mfaSatisfied` field (read-only) | `POST /api/v1/me/twoFactor/enroll` (return QR + secret), `POST /api/v1/me/twoFactor/confirm` (verify TOTP), `DELETE /api/v1/me/twoFactor` (disable) | The existing `TwoFactorService` bean has the verification logic; legacy enrollment lives in the JSP-rendered `/profile` page. SPA never invoked this. | P2 | M |
| B3 | API-key management | `user_account.api_key` column + `enable_api_key` flag exist | `POST /api/v1/me/apiKey` — rotate (returns the new key once, store hash), `DELETE /api/v1/me/apiKey` — revoke | Legacy admin UI carries it. The `/openrosa` adapter family already validates API keys against this column. | P2 | S |

---

## C — Whole feature areas with no REST coverage

| ID | Area | What exists today | What's missing | Legacy precedent | Priority | Effort |
|---|---|---|---|---|---|---|
| C1 | CRF data import | `ImportCrfDataView.vue` is a **248-line UI shell** with upload/map/validate steps and **zero `apiPost` / `fetch` calls**. Wizard advances on local state only. | Full REST surface: `POST /api/v1/imports` (multipart upload), `GET /api/v1/imports/{id}` (validation result), `POST /api/v1/imports/{id}/commit` (apply). Background-job semantics — the legacy version queues via Quartz. | `ImportCRFDataServlet` (`reg66`). Validates ODM/CSV against the study's event-definition / CRF metadata, surfaces field-level errors, then commits a batch insert into `item_data`. | **P0** (regulatory: bulk corrections + sponsor-data imports) | **L** |
| C2 | Datasets / exports | No SPA view. | `POST /api/v1/datasets` (define), `GET /api/v1/datasets` (list), `POST /api/v1/datasets/{id}/extract` (kick off Quartz job, returns job-id), `GET /api/v1/extracts/{jobId}` (status), `GET /api/v1/extracts/{jobId}/download?format=odm\|csv\|spss` | `CreateDatasetServlet` (`reg23`), `ExtractDatasetsMainServlet` (`reg59`), `ChooseDownloadFormat` (`reg17`). The whole extract pipeline runs via Quartz today. | **P0** (sponsor data deliverables) | **L** |
| C3 | Audit-log export | `GET /api/v1/audit` (paginated list) | `GET /api/v1/audit/export?format=csv\|json` (or kicks off a Quartz job for large studies) | `AuditLogStudyServlet` + the extract pipeline. | P1 | M |
| C4 | Scheduled jobs | Legacy `@RequestMapping("/cancelScheduledJob")`, `/listCurrentScheduledJobs`, `/listCurrentScheduledJobsData` exist. No SPA equivalent. | `GET /api/v1/jobs` (list), `GET /api/v1/jobs/{id}`, `POST /api/v1/jobs/{id}/cancel`. Surface ongoing imports + extracts. | The 3 `@RequestMapping` paths above plus their Quartz integration. | P1 | M |
| C5 | Rules engine | RuleSet CRUD, validate, deploy, run-against-form. Multiple legacy servlets in `…/control/rule/`. | Full RuleSet surface — out of scope for the first iteration; in-scope eventually because edit-checks are GCP-mandatory. | `…/control/rule/RuleSetRuleServlet`, `ListRuleSetsServlet`, etc. | P2 | **L+** |
| C6 | CRF design + versioning | Legacy `CreateCRFServlet` + `CreateCRFVersionServlet` + the `/managestudy/changeCRFVersion*` chain. SPA can render filled CRFs but cannot author, version, or assign them. | `POST /api/v1/crfs` (Excel/ODM upload), `POST /api/v1/crfs/{id}/versions`, `PUT /api/v1/studies/{oid}/events/{eventOid}/crfVersion` | Above plus `BatchCRFMigrationServlet`. | P2 | **L** |
| C7 | Treatment arms / groups | None — beyond the read-only `groupLabel` returned by `SubjectListItemDto`. | `POST /api/v1/studies/{oid}/groups`, `PUT .../groups/{id}`, `POST .../groups/{id}/assign` (associate subjects). | `SubjectGroupClassListServlet`, `EditSubjectGroupClassServlet`, `AssignSubjectToGroupServlet`. Required for studies with randomised arms. | P1 | M |

---

## Deliberately **out of scope**

- Item-level CRF data writes — already covered by `POST /api/v1/eventCrfs/{id}/items`.
- Subject-level edits within a single event — same as above.
- Legacy JSP retirement itself (DR-018 TODO #11) — gated on full A + C parity.

## Acceptance pattern (for every new adapter)

These conventions are now baked in across PR #54 + #55 — repeat them per slice:

1. **DTO records** carry `@Schema(name="…")` so they surface in `components.schemas`.
2. **Controller methods** carry `@ApiResponse(content = @Content(schema = @Schema(implementation = …Dto.class)))`. Without it, springdoc inlines the schema and the SPA can't import via `components['schemas']['…']`.
3. **MockMvc IT** per controller method pins the session-guard + validation-guard contract surface (PR #54 `SubjectsApiControllerTest` is the template).
4. **Happy-path Testcontainers IT** for at least one method per controller family (PR #54 `SubjectsApiControllerDatabaseIT` is the template).
5. **`@RestControllerAdvice`** is already in place (`ApiExceptionHandler` from PR #54 #6) — throw exceptions instead of inline error maps.
6. **Audit-event emission** for any mutation that maps to a regulatory event. Reuse type 50 (Profile updated) as the precedent for "DM-grade single-record edit"; new audit-event-type rows go in a `lc-muw-<date>-audit-event-type-<topic>.xml` Liquibase changeset (audit-ids 50–60 reserved for SPA-driven events).
7. **Role-based authorization** uses session-bound `StudyUserRoleBean.getRole().getId()` — mirror the legacy servlet's `entered()` check verbatim; throw `IllegalStateException` for 403 (handled by `ApiExceptionHandler`).

## References

- PR #51 (Phase E.4 mock removal) — established the GET-only viewer pattern.
- PR #56 — established the `@Schema` / `@ApiResponse` / `Required<components[…]>` SPA-migration patterns.
- DR-018 (legacy JSP deletion) — unblocked once A + C reach SPA parity.
