# Issue draft — Phase E.5 follow-up: editor adapters for Users + Study configuration

**Suggested title:** `Phase E.5 follow-up: editor adapters for Users + Study configuration`

---

## Summary

Two SPA routes — [`/manage-users`](../../../../web/src/spa/src/views/ManageUsersView.vue) and [`/build-study`](../../../../web/src/spa/src/views/BuildStudyView.vue) — are **read-only views** today. The backend exposes only GET adapters:

| Route | Backend | Editor wiring |
|---|---|---|
| `/manage-users` | `GET /api/v1/users` only | "Invite" button at [ManageUsersView.vue:94](../../../../web/src/spa/src/views/ManageUsersView.vue#L94) has no `@click` handler — UI mock |
| `/build-study` | `GET /api/v1/studies/{oid}/build-status` only | No mutation points |

This is consistent with Phase E.4's M1–M13 scope, which shipped **viewer** adapters for the data-manager workflows (subjects, audit, SDV, queries, CRF entry) and explicitly deferred admin operations.

Today the workaround is to use the legacy JSP UI:
- User CRUD: http://127.0.0.1:8080/LibreClinica/pages/ListUserAccountsServlet
- Study config: http://127.0.0.1:8080/LibreClinica/pages/BuildStudyServlet (and the wizard chain that follows)

## Adapter gap

### Users (`/api/v1/users`)

The legacy servlets cover:
- `CreateUserAccountServlet` — invite/create user
- `EditUserAccountServlet` — name/email/role/site changes
- `RestrictUserAccountServlet` / `RestoreUserAccountServlet` — soft delete + reinstate
- `ChangeUserRoleServlet` — role + per-study grant changes
- `ResetUserAccountPasswordServlet` — admin-initiated reset

Suggested REST surface (deliberate subset of the JSP surface — only the operations the SPA admin view actually needs):

| Method | Path | Notes |
|---|---|---|
| `POST` | `/api/v1/users` | Create user. Body mirrors `AddSubjectRequest` style: `userName`, `email`, `firstName`, `lastName`, `legacyRole`, initial site grant. Returns the new `StudyUserDto`. |
| `PUT` | `/api/v1/users/{userName}` | Update profile fields (`firstName`, `lastName`, `email`, `institutionalAffiliation`). |
| `PUT` | `/api/v1/users/{userName}/role` | Change role / per-study grant. Body: `{ studyOid, legacyRole }`. |
| `POST` | `/api/v1/users/{userName}/restrict` | Soft-disable. |
| `POST` | `/api/v1/users/{userName}/restore` | Re-enable. |
| `POST` | `/api/v1/users/{userName}/resetPassword` | Generate temp password + email it via Mailcrab. Returns 204. |

Authorization: chain-level `.anyRequest().hasRole("USER")` is in place; the controller must additionally enforce `currentRole == ADMIN || STUDYDIRECTOR` (per legacy `UserAccountAction.entered`). 403 otherwise.

### Study configuration (`/api/v1/studies`)

The legacy `BuildStudyServlet` wizard covers:
- Study metadata (name, protocol id, description, status, dates, locales)
- Event-definition CRUD (label, scheduling type, required/optional, repeating flag)
- CRF version assignment per event
- Site CRUD (sub-studies)
- Group-class CRUD (treatment arms)

Suggested REST surface (also a deliberate subset — the SPA's build-study view needs reading + the most-common edits, not the full wizard):

| Method | Path | Notes |
|---|---|---|
| `PUT` | `/api/v1/studies/{oid}` | Update study metadata (name, description, dates). Returns updated `StudyOptionDto`. |
| `PUT` | `/api/v1/studies/{oid}/events/{eventOid}` | Update event-definition fields (label, schedulingType, required). |
| `POST` | `/api/v1/studies/{oid}/sites` | Create a sub-study/site. Body: `{ name, oid, address?, status }`. |

Full site/group CRUD likely lives in a separate slice once the build-study SPA view extends beyond the current read-only summary.

## Non-goals

- Subject-level edits (already covered by Phase E.4 M2/M3 — `POST /api/v1/subjects`, `POST /api/v1/subjects/{oid}/sign`).
- Per-study role assignments to OTHER users — those are part of "Manage Users", scoped to a single admin form.
- Legacy JSP retirement — that's DR-018 / TODO #11 and depends on full SPA coverage.

## Acceptance

- All endpoints above ship with `@Schema`-annotated DTOs + `@ApiResponse` decorations so they surface in `components.schemas` (same convention introduced in PR #55).
- All endpoints ship with a MockMvc IT pinning the session-guard + validation-guard surface (same convention as PR #54 `SubjectsApiControllerDatabaseIT`).
- The SPA's `ManageUsersView` and `BuildStudyView` wire to the new endpoints, replacing the static "Invite" button + the read-only build-status panel respectively.
- One-time pass through the legacy JSP screens to confirm parity for the operations carried over (the regulatory-precedent comment in [`MeApiController.java:80`](../../../../web/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/controller/api/MeApiController.java#L80) is the model — link to the JSP that established the field scope).

## Related

- PR #51 (Phase E.4 mock removal) — established the GET-only viewer pattern.
- PR #55 — closed TODO #7 (TS type drift); the response-DTO convention here applies directly.
- DR-018 (legacy JSP deletion) — bookmarked once full SPA coverage exists.
