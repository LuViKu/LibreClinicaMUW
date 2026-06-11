# Audit-coverage evaluation — 2026-06-11

## Why this doc exists

LibreClinicaMUW is a clinical-trial eCRF. Every create / modify / delete on
electronic records must reach an audit trail (21 CFR Part 11 § 11.10(e); GCP
ALCOA+). This document captures the 2026-06-11 evaluation of that coverage,
the dual-audit-table architecture finding, the 11 confirmed gaps, and the
gap closures that landed in the same PR.

## TL;DR (compliance posture, 2026-06-11)

- **Phase E.6+ stack is substantially compliant** for clinical-data CRUD
  (item-data, EventCRF state transitions, retinal-inference enqueue, subject
  + study-event lifecycle, study identity).
- **11 confirmed § 11.10(e) gaps closed** in this PR — CRF library
  mutations, user-account lifecycle, EventDefinition create, and discrepancy
  note threading (the highest-risk regulatory gap).
- **Major architectural finding documented but NOT remediated here**: there
  are *two* audit tables (`audit_event`, legacy; `audit_log_event`, newer)
  written by different DAO paths. A unification migration is the next slice.

## The dual-audit-table architecture

This was the load-bearing discovery of the evaluation, and re-shapes how
audit coverage should be reasoned about going forward.

| Table | Schema | Written by | SPA Audit Log view sees it? |
|---|---|---|---|
| `audit_event` (legacy, OpenClinica 2.5 era) | 5 cols: `audit_date`, `audit_table`, `user_id`, `entity_id`, `reason_for_change`, `action_message`. **No `audit_log_event_type_id` FK.** | `AuditEventDAO.create()` — used by `EventCrfsApiController.writeAuditEvent` (the canonical Phase E.6 helper), `CrfsApiController.writeLifecycleAudit`, `EventDefinitionsApiController.writeEventDefFieldAudit`, `UsersApiController` role/profile audits, `SubjectsApiController` via `FailureAuditTemplate`, legacy `createRowFor*` helpers. | **No.** |
| `audit_log_event` (newer) | 14 cols incl. `audit_log_event_type_id` FK → `audit_log_event_type`. | `AuditEventDAO.insertOperationFailure()` (Phase A1 OPERATION_FAILED, type 61, REQUIRES_NEW-equivalent), AND direct-SQL `PreparedStatement` blocks in `StudiesApiController:860` (type 51), `DiscrepancyApiController:1121` (type 56 export), `EyeCohortTransitionsApiController` (type 57), `ModalitiesApiController` (types 58-60), `UsersApiController:1609` (type 50 unlock), and now the 12 new gap-fix sites (types 63-74). | **Yes.** |

**Smoking gun:** `AuditEventDAO.java:198-209`:

```java
/**
 * Creates a new row in the audit_log_event table
 */
public AuditEventBean create(AuditEventBean sb) {
    HashMap<Integer, Object> variables = new HashMap<Integer, Object>();
    // INSERT INTO audit_event
    // (AUDIT_DATE,AUDIT_TABLE,USER_ID,ENTITY_ID,REASON_FOR_CHANGE,
    // ACTION_MESSAGE)
    // VALUES (NOW(),?,?,?,?,?)
    // needs to change, tbh 02/2009
    // new query needs to be
    // INSERT INTO audit_log_event(audit_id, audit_log_event_type_id, ...)
```

The Javadoc says `audit_log_event`, the actual SQL writes to `audit_event`,
and the inline comment is a 16-year-old TODO to migrate. Phase E.6 (study
identity, eye-cohort transition, discrepancy export, modality CRUD,
unlock) has been migrating *individual writers* to direct-SQL writes into
`audit_log_event` so they surface in the SPA Audit Log view. This PR
extends that pattern; it does not migrate the canonical helper.

**Compliance implication:** a "give me everything that happened to subject
X" query needs to `UNION` both tables. The SPA Audit Log view today shows
only `audit_log_event` rows — legacy `audit_event` writes are invisible
through the UI. For regulatory inspections that read directly from the
DB, both tables are evidence; for inspections that read through the SPA,
only `audit_log_event` is.

## Verified gaps (11 actions × file:line evidence)

Verification was performed by four parallel Explore agents, each scoped to
one controller, with the constraint that an audit-write claim required a
controller-side, DAO-trigger, or service-layer file:line.

### A. CRF library mutations (3 gaps)

| Action | Endpoint | Pre-fix state |
|---|---|---|
| CRF create | `CrfsApiController:161-216` (`@PostMapping`) | UNAUDITED — `crfDao.create()` at :204, no audit-write |
| CRF version upload (XLS) | `CrfsApiController:309-435` (`uploadVersion`) | UNAUDITED — `parserService.parseAndPersist()` returns, no follow-up audit |
| CRF version author (JSON) | `CrfsApiController:478-588` (`authorVersion`) | UNAUDITED — same parser path, no audit |

Positive control: `disableVersion` at `:785-818` already calls
`writeLifecycleAudit` (writes to legacy `audit_event`).

### B. User-account lifecycle (4 gaps)

| Action | Endpoint | Pre-fix state |
|---|---|---|
| User disable | `UsersApiController:641` (`@PostMapping .../disable`) | UNAUDITED — `userDao.delete(target)` at :667, no audit |
| User restore | `UsersApiController:701` (`@PostMapping .../restore`) | UNAUDITED — `userDao.restore(target)` at :734, no audit |
| Self-service forgot-password | `RequestPasswordServlet.java:120` | UNAUDITED — `uDAO.update(ubDB)` writes password hash, no audit |
| Expired-on-login forced reset | `ResetPasswordServlet.java:152` | UNAUDITED — `udao.update(ub)` writes new hash, no audit |

Positive controls: `unlock` at `:1579`, `grantRole` at `:964`,
`resetPassword` (DM-initiated) at `:1410`, bulk-create at `:1247/1283`,
profile-update at `:1133` are all audited (some to `audit_log_event`,
some to legacy `audit_event`).

### C. EventDefinition create (1 gap)

| Action | Endpoint | Pre-fix state |
|---|---|---|
| EventDefinition create | `EventDefinitionsApiController:131-210` | UNAUDITED — `setOwner` + `setCreatedDate` is entity metadata, NOT an audit trail row |

Positive controls: `edit` at `:216-305` (per-field `writeEventDefFieldAudit`,
legacy table) and `deactivate` at `:377-433` (`AuditEventDAO.create`, legacy
table) are audited.

### D. Discrepancy-note threading (4 gaps — highest regulatory risk)

| Action | Endpoint | Pre-fix state |
|---|---|---|
| DN create — success path | `DiscrepancyApiController:540` | UNAUDITED — only FAILURE path audited via `FailureAuditTemplate` (type 61) |
| DN thread append | `appendThread:730` (`dnDao.create(child)`) | UNAUDITED + misleading docstring claimed `discrepancy_note_trigger` handled it — **trigger does not exist** |
| DN status transition | `appendThread:737` (`dnDao.update(parent)`) | UNAUDITED — bare `UPDATE` on `resolution_status_id` |
| DN reassignment | `appendThread:742` (`dnDao.updateAssignedUser(parent)`) | UNAUDITED — bare `UPDATE` on `assigned_user_id` |

Positive control: DN export CSV at `:246` is audited via `emitExportAudit`
(type 56, `audit_log_event`).

**The DN-threading docstring lie was the most serious finding.** A reviewer
reading the controller code in good faith would have concluded the audit
trail was complete. The comment was a regulatory landmine and is removed
by the gap-fix.

## What this PR shipped

Liquibase changeset `lc-muw-2026-06-11-audit-event-types-gap-coverage.xml`
seeds twelve new rows in `audit_log_event_type`:

| ID | Name | Site |
|---|---|---|
| 63 | `crf_created` | `CrfsApiController.create` |
| 64 | `crf_version_uploaded` | `CrfsApiController.uploadVersion` (XLS) |
| 65 | `crf_version_authored` | `CrfsApiController.authorVersion` (JSON) |
| 66 | `user_account_disabled` | `UsersApiController.disable` |
| 67 | `user_account_restored` | `UsersApiController.restore` |
| 68 | `user_password_reset_requested` | `RequestPasswordServlet` (self-service) |
| 69 | `user_password_expired_reset` | `ResetPasswordServlet` (forced reset) |
| 70 | `event_definition_created` | `EventDefinitionsApiController.create` |
| 71 | `discrepancy_note_created` | `DiscrepancyApiController.create` (success-path companion to type 61) |
| 72 | `discrepancy_thread_appended` | `DiscrepancyApiController.appendThread` (child note insert) |
| 73 | `discrepancy_status_changed` | `DiscrepancyApiController.appendThread` (parent `resolution_status_id` flip) |
| 74 | `discrepancy_reassigned` | `DiscrepancyApiController.appendThread` (parent `assigned_user_id` flip) |

Each call site uses a direct `PreparedStatement` INSERT into
`audit_log_event`, copying the pattern established at
`StudiesApiController:858-873`. The rows land in `audit_log_event`
specifically (not the legacy `audit_event`), so they're queryable by
`audit_log_event_type_id` and surface in the SPA Audit Log view.

The status-transition (73) and reassignment (74) audits are guarded —
only emit when the field actually changed, not on every PUT.

## What this PR did NOT change (deliberate)

- **The canonical `EventCrfsApiController.writeAuditEvent` helper.** Still
  writes to legacy `audit_event` via `AuditEventDAO.create()`. ~13 existing
  call sites continue to work; ~10 historical actions still need their own
  types in `audit_log_event_type` before they can be migrated.
- **`EventDefinitionsApiController` edit / deactivate.** Already audited to
  legacy `audit_event`; out of scope for this PR.
- **`CrfsApiController` disable / `disableVersion`.** Already audited via
  `writeLifecycleAudit` to legacy `audit_event`.
- **Historical `audit_event` rows.** No data migration in this PR.

## Audit-write reliability (architectural notes)

Even with the gaps closed, two reliability characteristics deserve doc
visibility for regulatory review:

1. **Audit-write failures are silently swallowed** (logged at WARN, not
   propagated). All new gap-fix sites follow this convention to match the
   existing pattern in `StudiesApiController.writeStudyFieldAudit`,
   `UsersApiController.emitUnlockAudit`,
   `DiscrepancyApiController.emitExportAudit`, etc. The trade-off: a
   transient audit-write failure does NOT roll back the user's mutation
   (operation success > audit completeness), but it does mean compliance
   coverage depends on operator-side reconciliation against SLF4J `WARN`
   logs. No automated reconciliation job exists.
2. **Tier-1 success-path audits are in the same transaction as the data
   write.** If the operation rolls back, the audit row rolls back too —
   correct for success audits, but no trail of "attempted then rolled
   back" actions. The Phase A1 `insertOperationFailure` mechanism (type
   61, REQUIRES_NEW-equivalent via `autoCommit=true`) covers the failure
   case but only when the call site is wrapped in `FailureAuditTemplate`.

## Follow-up slices

Ranked by regulatory risk × effort:

1. **Audit-write reconciliation job** — nightly cron that reads `WARN`
   lines emitted by audit-write catch blocks, parses the intended payload,
   and back-fills `audit_log_event` rows of a new type
   `AUDIT_RECONCILIATION_BACKFILL`. Closes the silent-swallow gap.
2. **Authentication-event audit dimension** — types for login failure
   (75), logout (76), SSO provisioning (77), DM-initiated password reset
   already audits via the legacy table at `:1457-1468`; move it to
   `audit_log_event` with a typed id.
3. **The big unification migration** — migrate
   `EventCrfsApiController.writeAuditEvent` from `audit_event` to
   `audit_log_event` with a per-action type id, plus a Liquibase
   data-migration changeset copying historical `audit_event` rows into
   `audit_log_event`. ~1-2 weeks; needs a regression sweep of every
   controller that writes audits.
4. **Audit-rate dashboard** — `/api/v1/audit/coverage` admin endpoint
   surfacing "rows per type per day," so a silent-swallow incident
   becomes visible operationally before a regulator surfaces it.

## Reference: existing audit_log_event_type IDs

| Range | Owner |
|---|---|
| 1-31 | Legacy OpenClinica 2.5+ vocabulary (item data, study-event, study-subject lifecycle, EventCRF state transitions) |
| 32-35 | Phase 3.x extensions (SDV, export markers) |
| 50 | `user_account_profile_updated` |
| 51 | `study_identity_updated` |
| 52-55 | Dataset / subject-data / audit-log / discrepancy-log export markers |
| 56 | `discrepancy_log_exported` |
| 57 | `eye_cohort_transition` |
| 58-60 | Modality CRUD |
| 61 | `OPERATION_FAILED` (Phase A1 catch-all, REQUIRES_NEW) |
| 62 | (reserved; unused) |
| **63-74** | **Gap-fix sweep — this PR** |
