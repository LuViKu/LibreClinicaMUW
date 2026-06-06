# LibreClinicaMUW Phase E.6+ SPA-Coverage Closure — Unified Build Playbook

**Audience:** implementer (Lukas or future agent) picking up Tier 1 + Tier 2 cluster work after Phase E.4 + E.5 + E.6 M-A/B/C land.
**Source:** 13 reviewer-verified cluster playbooks (5 Tier 1 / 8 Tier 2), reviewer estimate deltas applied, operational constraints (2026-06-05) honored.
**Last sync:** 2026-05-31.

---

## 1. Executive summary

### Totals (reviewer-adjusted)

| Tier | Clusters | Raw estimate | Adjusted estimate | Notes |
|------|----------|-------------:|------------------:|-------|
| **Tier 1 (P0)** | 5 | 32d | **34d** | `study-params` +1, `crf-data-types` +1, `bulk-import` `admin-rfc` `unlock-user` unchanged |
| **Tier 2 (P1)** | 8 | 62d | **67d** | `subject-lifecycle` +1, `audit-discrepancy-export` +1, `restore-quickwins` +1, `dde` +2, `auth-admin` unchanged, `crf-library` `discrepancy-full` `crf-entry-advanced` unchanged |
| **Combined** | 13 | **94d** | **101d** | ≈ 20 work-weeks solo |

### Wall-clock projections

- **Solo developer, sequential**: ~20 work-weeks (~5 months at 5d/week) including review + smoke + buffer.
- **Solo developer with disciplined parallelism** (one Tier 1 cluster while a Tier 2 cluster bakes in CI): ~16 weeks (~4 months) — the constraint becomes review bandwidth, not coding.
- **2 parallel tracks** (one engineer on backend-heavy clusters, one on SPA-heavy): ~11 weeks (~2.5 months). See §2 for the cross-track dependency graph.

### Critical path

```
unlock-user (1d)
  → study-params (6d, +1 reviewer)
    → admin-rfc (4d)
      → crf-data-types (9d, +1 reviewer)
        → bulk-import (14d)
          → crf-library (10d)
            → discrepancy-full (10d)
              → dde (14d, +2 reviewer)
                → audit-discrepancy-export (5d, +1 reviewer)
```

Longest dependent chain ≈ **73 work-days**. Off-critical work (`auth-admin` test-gap-closure, `restore-quickwins`, `crf-entry-advanced`, `subject-lifecycle`) can run in parallel.

### Blocked-on-decision items

1. **`bulk-import` transactional strategy** — playbook assumes option (a) extract `ImportCRFDataPersistenceService` (+2d already in 14d estimate). If option (b) manual `TransactionTemplate` is preferred for risk, drop estimate to ~12d. **Pin before starting bulk-import.**
2. **`admin-rfc` legacy `admin_forced_reason_for_change` study param** — playbook hard-codes `force=true`. Confirm with team this is acceptable for MUW (single-site) vs. honoring the per-study toggle.
3. **`crf-data-types` multipart wiring** — `applicationContext-web-beans.xml` has no `MultipartResolver` bean today. Confirm whether Spring 6 auto-config covers it; if not, bean registration is a brittle XML edit (already absorbed in +1d).
4. **`audit-discrepancy-export` POI bump** — POI 3.0.1 → 5.3.0 cascades into 4 HSSF call sites with int-constant → `CellType` enum migrations. Reviewer flagged the existing estimate as understated; +1d applied. **Land the bump as a preparatory commit before endpoint work.**
5. **`dde` writeAuditEvent extraction** — currently a private static helper inside `EventCrfsApiController`. Reviewer flagged `DdeService` cannot call private; promote to package-private utility as a sub-task before DDE backend work starts.

---

## 2. Build sequencing

### Tier 1 ordering (34d on critical path)

```
Day 1                                                                        Day 34
|--unlock-user--|                                                            
                |--study-params (6d)---------|                                
                                             |--admin-rfc (4d)---|            
                                                                 |--crf-data-types (9d)---|
                                                                                          |--bulk-import (14d)---|
```

Recommended order:

1. **`unlock-user` (1d)** — smallest blast radius, ships SPA-only go-live the first time someone fat-fingers a password. Bundles the `StudyUserDto.locked` wire-shape extension.
2. **`study-params` (6d)** — unblocks `AddSubjectView` + `CrfEntryView` field-visibility consumers; needs `build-study-edit` `StudyAdminAuthorization` helper already on `lc-develop`.
3. **`admin-rfc` (4d)** — RFC capture infrastructure that `bulk-import` and `dde` both reuse; lands AFTER bcrypt + audit-log clusters already on `lc-develop`.
4. **`crf-data-types` (9d)** — split into two PRs: (a) repeating groups + select-multi (5d, JSON-only), (b) file upload (4d, multipart pipeline). Land AFTER audit-log so `writeAuditEvent` actionMessages already serialise.
5. **`bulk-import` (14d)** — depends on `auth-admin` session contract (already shipped) + `admin-rfc` RFC field. Largest single cluster; split into preview-token PR (7d) + commit-pipeline PR (7d) if review queue jams.

**Tier 1 parallelism opportunities:**

- `study-params` backend (3d) + `unlock-user` SPA polish can overlap.
- `crf-data-types` PR (a) (repeating groups + select-multi) can run in parallel with `admin-rfc` SPA modal + i18n work — disjoint file sets.

### Tier 2 ordering (67d)

```
auth-admin (1d, test gaps only)         [trivial, drop in anywhere]
restore-quickwins (5d)                  [post-Tier-1; touches 5 disjoint controllers]
subject-lifecycle (10d)                 [needs group-classes already shipped]
crf-entry-advanced (12d)                [needs CRF entry core + notes core, both shipped]
crf-library (10d)                       [needs manage-users + audit-trail patterns]
discrepancy-full (10d)                  [extends M7 discrepancy adapter]
dde (14d, +2 reviewer)                  [needs discrepancy-full FAILEDVAL generalisation]
audit-discrepancy-export (5d, +1 reviewer) [POI bump + lands after all data-mutation clusters]
```

Recommended Tier 2 sequence:

1. **`auth-admin` (1d)** — test-gap-closure only; everything else shipped under Phase E A7.4. Land as a tidying PR alongside any Tier 1 work.
2. **`restore-quickwins` (5d)** — five disjoint endpoints, ships parity wins (study event restore, event-CRF restore, dataset restore, rule-set XML export, rule-set dry-run wiring). Lowest risk Tier 2 cluster.
3. **`subject-lifecycle` (10d)** — split into PR1 (restore wiring + showRemoved toggle, 2d) + PR2 (Person-ID branch + group assignment, 8d). PR1 is low-risk and could ship alongside `restore-quickwins`.
4. **`crf-entry-advanced` (12d)** — split into 3 feature branches per playbook: section badges (3d), notes-rollup + popover (5d), concurrent-edit probe (4d). All additive endpoints, additive DTO fields, no Liquibase.
5. **`crf-library` (10d)** — lock/unlock/restore/hard-remove/download/migrate. Sysadmin-gated hard-remove is the highest-risk endpoint inside this cluster; wire last.
6. **`discrepancy-full` (10d)** — extends M7 with thread hydration, type field, email, CSV. **Must land before `dde`** because DDE auto-spawns FAILEDVAL notes via the type-field generalisation.
7. **`dde` (14d, +2)** — depends on `discrepancy-full` for FAILEDVAL spawn. Largest Tier 2 cluster; risky paper-first parity work.
8. **`audit-discrepancy-export` (5d, +1)** — lands LAST; POI bump touches 4 unrelated HSSF call sites. Non-blocking for go-live (legacy JSP exports still available).

### Cross-cluster dependency graph

```
unlock-user ──┐
              ├─→ study-params ──→ admin-rfc ──→ crf-data-types ──→ bulk-import
              │                          │                                │
              │                          ├──────→ dde (FAILEDVAL spawn) ──┤
              │                          │                                │
              └─→ (canManage gate reuse) │                                │
                                          ├──→ crf-library                 │
                                          │                                │
                                          └──→ crf-entry-advanced          │
                                                                           │
restore-quickwins ──→ (independent; 5 disjoint endpoints) ──────────────────┤
subject-lifecycle ──→ (independent; needs already-shipped group-classes) ───┤
discrepancy-full ──→ dde ──────────────────────────────────────────────────┤
                                                                           │
audit-discrepancy-export ──────────────────────────────────────────────────┘
        (lands last; POI bump preparatory commit; uses audit_event_type_id 54/55)

auth-admin ──→ (test-gap-only; can land any time)
```

### Parallelism opportunities (2-track team)

| Track A (backend-heavy) | Track B (SPA-heavy) | Reason |
|--------------------------|---------------------|--------|
| `bulk-import` backend (7d) | `crf-entry-advanced` SPA (8d) | Disjoint controllers; both depend on already-shipped infra |
| `dde` `DdeService` + endpoints (5d) | `discrepancy-full` SPA + email notifier (4d) | DDE backend needs FAILEDVAL but can stub-spawn until B lands |
| `crf-library` backend (4d) | `subject-lifecycle` SPA PR2 (5d) | Disjoint controllers |
| `audit-discrepancy-export` POI bump (1d) | `restore-quickwins` SPA (3d) | POI bump is preparatory and isolated |

---

## 3. Per-cluster playbook sections

---

### 3.1 `unlock-user` — Tier 1 P0, **1d** (no adjustment)

**Summary:** POST `/api/v1/users/{username}/unlock` flips `account_non_locked=true`, resets `lock_counter=0`, issues fresh one-time password for local users; SPA Manage Users gets per-row Unlock button + locked badge.

#### Backend skeleton

```
NEW    (none)
MOD    web/.../UsersApiController.java               (add @PostMapping("/{username}/unlock") handler)
MOD    web/.../StudyUserDto.java                     (add `boolean locked` field — record positional ctor)
MOD    web/.../UsersApiController.java               (list() projection AND projectToStudyUserDto: populate locked)
```

Endpoint:

```
POST   /api/v1/users/{username}/unlock
  auth: sysadmin/techadmin (preflightLifecycle)
  req:  UnlockUserRequest { sendEmail: Boolean? }
  res:  Map<String,Object> { user: StudyUserDto, generatedPassword: String|null }
  err:  401 anon · 400 no active study · 403 non-sysadmin
      · 400 directory-owned (SSO/LDAP) · 404 unknown · 409 not currently locked
```

Audit row write: copy `resetPassword` audit block at `UsersApiController.java:1108-1135` BUT replace:
- `columnName="passwd"` → `columnName="account_non_locked"`
- old=`""` new=`""` → old=`"false"` new=`"true"`
- action message → `"unlock by admin <admin> for user <target>"`

Liquibase: none (`account_non_locked` + `lock_counter` already on `user_account`).

#### SPA skeleton

```
NEW    (none)
MOD    web/src/spa/src/stores/users.ts              (add unlock() action)
MOD    web/src/spa/src/types/user.ts                (drop `locked` from StudyUser Omit list)
MOD    web/src/spa/src/views/ManageUsersView.vue    (per-row Unlock button + locked badge)
NEW    web/src/spa/src/stores/__tests__/users.test.ts  (NET-NEW — does not exist today)
```

Store action:

```ts
unlock(username) → apiPost('/pages/api/v1/users/{username}/unlock', { sendEmail: false })
  on success: replaces row in rows.value, returns { ok: true, user, generatedPassword }
  mirrors restoreUser() error-handling verbatim
```

View change: extend `authVariant()` so `locked && auth==='local'` shows a `Locked` `StatusPill`; `canUnlock(u)` gate matches `canResetPassword`'s directory-owned filter.

i18n keys: `manageUsers.lifecycle.unlock`, `.unlockConfirm`, `.unlockedIntro`, `manageUsers.locked.badge`, `manageUsers.locked.tooltip`.

#### Tests

- **SPA**: 5 cases in net-new `users.test.ts` — URL/body shape, success row replacement, generatedPassword for local, null for directory, 4xx mapping.
- **Backend**: 4 MockMvc cases mirroring `resetPassword` lines 469-501.
- **Smoke**: trip lock by 6 failed logins → confirm `locked:true` → unlock → log in with returned cleartext → forced change.

#### Acceptance criteria (abbrev.)

- 200 + fresh one-time password; `account_non_locked` flips true; `lock_counter=0`; `passwd_timestamp=null`.
- 400 for SSO/LDAP; 409 when not locked; audit row written.
- `GET /users` includes `locked: boolean` on every row.
- SPA renders per-row Unlock only when `u.locked && u.auth==='local' && canInvite`.

#### Sequencing & reviewer flags

- **First in Tier 1.** Bundles `users-locked-on-wire` wire-shape extension.
- **Reviewer flags:**
  - `users-locked-on-wire` dependency was invented — absorbed into this cluster, drop the entry.
  - Audit-block copy needs `columnName=account_non_locked` + value swap (NOT a literal "passwd" copy).
  - List projection at `UsersApiController.java:237` uses positional `new StudyUserDto(...)` — update that call site too.
  - `users.test.ts` is net-new, not extended.
  - Response shape is inline `Map<String,Object>` mirroring `resetPassword`, NOT a typed DTO.

---

### 3.2 `study-params` — Tier 1 P0, **6d** (+1 reviewer)

**Summary:** New `StudyParametersApiController` (GET + PUT) exposing the 19 `study_parameter_value` handles so DMs can configure subject-ID generation, DOB collection, discrepancy management etc. from the SPA. Downstream views (`AddSubjectView`, `CrfEntryView`) read the new store instead of hard-coded defaults.

#### Backend skeleton

```
NEW    web/.../StudyParametersApiController.java
NEW    web/.../StudyParametersDto.java                  (19 handles)
NEW    web/.../UpdateStudyParametersRequest.java        (same 19 minus studyOid, all nullable)
NEW    web/.../StudyParametersApiControllerTest.java
MOD    web/.../UpdateStudyRequest.java                  (drop "out of scope" javadoc)
MOD    web/.../MeApiController.java                     (embed thin StudyParametersDto ref)
MOD    core/.../StudyParameterValueDAO.java             (add batchUpsert helper)
```

Endpoints:

```
GET    /api/v1/studies/{studyOid}/parameters
  auth: USER + SiteVisibilityFilter
  res:  StudyParametersDto (19 fields)
  err:  401 · 404 unknown oid · 403 outside visible tree

PUT    /api/v1/studies/{studyOid}/parameters
  auth: StudyAdminAuthorization.roleMayEditStudy
  req:  UpdateStudyParametersRequest
  res:  StudyParametersDto
  err:  401 · 403 · 404 · 409 LOCKED/FROZEN · 400 enum out-of-range
```

DTO fields (use **19** consistently — reviewer flag):
`studyOid, subjectIdGeneration, subjectIdPrefixSuffix, subjectPersonIdRequired, personIdShownOnCRF, collectDob, genderRequired, eventLocationRequired, discrepancyManagement, interviewerNameRequired, interviewerNameDefault, interviewerNameEditable, interviewDateRequired, interviewDateDefault, interviewDateEditable, secondaryLabelViewable, adminForcedReasonForChange, interventionType, participantPortal, randomization`

Validation allow-lists per playbook (subjectIdGeneration ∈ {manual, auto non-editable, auto editable}, collectDob ∈ {1,2,3}, etc.).

Liquibase: none. `study_parameter` table seeded since OC 2.5 + amethyst migrations.

#### SPA skeleton

```
NEW    web/src/spa/src/types/studyParameters.ts
NEW    web/src/spa/src/stores/studyParameters.ts
NEW    web/src/spa/src/views/StudyParametersEditView.vue
NEW    web/src/spa/src/stores/__tests__/studyParameters.test.ts
MOD    web/src/spa/src/views/BuildStudyView.vue        (add 'Parameters' tile)
MOD    web/src/spa/src/views/AddSubjectView.vue        (drive field visibility from store)
MOD    web/src/spa/src/views/CrfEntryView.vue          (seed header from *Default/*Editable)
MOD    web/src/spa/src/router/index.ts                 (/study/:oid/parameters)
MOD    web/src/spa/src/locales/de.json                 (REVIEWER: .json not .ts)
MOD    web/src/spa/src/locales/en.json                 (REVIEWER: .json not .ts)
```

Store actions: `load(oid)`, `update(oid, patch)`, `reset()`. Pattern matches `study.ts`.

Router entry:

```
{ path: '/study/:oid/parameters', component: StudyParametersEditView }
```

i18n keys (selected): `buildStudy.parameters.title`, `.sections.subjectId`, `.sections.discrepancy`, `.fields.subjectIdGeneration`, `.enums.required`, `.enums.collectDob.full`, `.confirm.title`, `.errors.statusLocked`.

#### Tests

- **SPA**: 9 store cases + 5 view cases (`StudyParametersEditView.test.ts`) + 3 cases NET-NEW `AddSubjectView.test.ts` (reviewer: file does not exist, **create** not extend).
- **Backend**: 12 MockMvc IT cases covering 401/403/404/409/400 enum/200 partial/audit-row-per-handle.
- **Smoke**: PUT collectDob=3 → reload AddSubject → DOB picker disappears.

#### Acceptance criteria

- GET returns 19 handles with `StudyParameterConfig` defaults fallback.
- PUT writes only supplied handles via `setParameterValue` (or batchUpsert) + one audit row per changed handle.
- 403 non-sysadmin/non-Director; 409 LOCKED/FROZEN; 400 enum out-of-range.
- AddSubjectView field visibility driven entirely by store; CrfEntryView seeds from `*Default`/`*Editable`; `discrepancyManagement==='false'` hides DN affordance.
- BuildStudyView shows 'Parameters' tile gated by canManageStudy.

#### Sequencing & reviewer flags

- Land AFTER `build-study-edit` (`StudyAdminAuthorization` + session 'study' refresh pattern).
- Land BEFORE any AddSubject/CrfEntry hardening cluster.
- **Reviewer flags:**
  - `build-study-edit` + `audit-log` dependency keys invented — drop or rename to `audit-discrepancy-export`.
  - locale paths are `.json` not `.ts`.
  - `AddSubjectView.test.ts` is **new**, not extend.
  - `MeApiController` embed has no matching AC — **add an AC bullet** or drop the change.
  - Field-count inconsistency (16 vs 19) — **use 19 throughout**.
  - Estimate bumped +1d for downstream view rewires.

---

### 3.3 `admin-rfc` — Tier 1 P0, **4d** (no adjustment)

**Summary:** Capture Reason-For-Change on edits to a CRF after `date_completed`. Backend gates the save + writes a REASON_FOR_CHANGE-type `discrepancy_note` auto-threaded under any prior RFC parent; SPA prompts inline via a modal before Save enables.

#### Backend skeleton

```
NEW    web/.../SaveItemsRequest.java                (promote from inline record)
NEW    web/.../service/crf/ReasonForChangeWriter.java
MOD    web/.../EventCrfsApiController.java          (gate post-complete save, delegate to writer)
MOD    web/.../CrfEntryDto.java                     (add boolean requiresReasonForChange)
MOD    core/.../DiscrepancyNoteDAO.java             (REVIEWER: new METHOD not new FILE)
MOD    core/src/main/resources/properties/discrepancy_note_dao.xml
       (REVIEWER: NOT under java/managestudy/, NOT named discrepancy_note.xml)
```

Endpoint (extension of existing):

```
POST   /api/v1/eventCrfs/{id}/items
  req:  SaveItemsRequest { values: Map<String,Object>, reasons: Map<String,String> }
  res:  inline LinkedHashMap (existing shape) extended with rfcCreatedCount: int
        (REVIEWER: NOT a typed SaveItemsResponse — that DTO does not exist)
  err:  400 missing values
      · 400 post-complete edit missing reason
            ({message, missingReasonItemOids: [oid…]})
      · 401/403/404/409 unchanged
```

DAO method:

```java
DiscrepancyNoteDAO.findLatestRfcParentForItemData(int itemDataId)
  → SELECT … FROM discrepancy_note JOIN dn_item_data_map …
    WHERE item_data_id=? AND discrepancy_note_type_id=4 AND parent_dn_id IS NULL
    ORDER BY date_created DESC LIMIT 1
```

Reviewer flag: `dn_item_data_map.createMapping` is invoked but not named in any DAO change — confirm `DnItemDataMapDAO.createMapping` exists in M7's surface before coding.

Liquibase: none (type_id=4 REASON_FOR_CHANGE seeded since OC core).

#### SPA skeleton

```
NEW    web/src/spa/src/components/ReasonForChangeModal.vue
NEW    web/src/spa/src/components/__tests__/ReasonForChangeModal.test.ts
MOD    web/src/spa/src/stores/crfEntry.ts            (pendingReasons, stageReason, save extended)
MOD    web/src/spa/src/views/CrfEntryView.vue        (mount modal; gate Save on staged reasons)
MOD    web/src/spa/src/types/crf.ts                  (CrfEntry.requiresReasonForChange; SaveItemsRequest)
MOD    web/src/spa/src/stores/__tests__/crfEntry.test.ts
MOD    web/src/spa/src/locales/de.json + en.json
```

Store actions: `stageReason(itemOid, reason)` local-only; `save()` extended to POST `{values, reasons}` when `requiresReasonForChange`, re-arms modal on `missingReasonItemOids` 400 body.

i18n keys: `crfEntry.rfc.modalTitle`, `.prompt`, `.placeholder`, `.confirm`, `.cancel`, `.headerTell`, `.capturedChip`, `.missingForItem`.

#### Tests

- **SPA**: +4 cases in existing `crfEntry.test.ts` (stageReason; save omits reasons pre-complete; save sends reasons + clears pending; 400 re-arms); 3 cases new modal test.
- **Backend**: +4 in `EventCrfsApiControllerTest` (missing-reason 400 with `missingReasonItemOids`; 200 with reasons; still 400 on missing values; GET carries `requiresReasonForChange=false`); 3 in new `ReasonForChangeWriterTest` (first RFC writes parent; subsequent threads as child; DAO failure does not roll back item_data update).
- **Smoke**: POST without reason → 400; POST with reason → 200; SELECT confirms `discrepancy_note_type_id=4` row.

#### Acceptance criteria

- Editing a saved item in completed CRF opens modal before Save enables.
- 400 `{missingReasonItemOids:[...]}` reopens modal scoped to those oids.
- Persists DN with `type_id=4`, `entity_type='itemData'`, `entity_id=<item_data.id>`, `column='value'`; `createMapping` populates `dn_item_data_map`.
- Subsequent RFCs auto-thread via `parent_dn_id=<existing>`.
- Audit row `item_data_rfc` next to existing item_data_update; audit-row failure doesn't roll back item_data save.
- Pre-complete entries: no modal, no reasons required.
- LOCKED/SIGNED CRFs still 409 unchanged.

#### Sequencing & reviewer flags

- Land AFTER bcrypt + audit-log clusters (RFC piggybacks the audit_event write).
- Safe BEFORE e-signature / sign-event work (SIGNED/LOCKED stays on legacy admin path).
- Day 1: SaveItemsRequest promotion + DAO method + writer unit tests; Day 2: controller wiring + IT; Day 3: SPA modal + store + vitest; Day 4: i18n + compose smoke.
- **Reviewer flags:**
  - DAO XML path is `core/src/main/resources/properties/discrepancy_note_dao.xml` (NOT under `java/`, NOT `discrepancy_note.xml`).
  - `findLatestRfcParentForItemData` is a **method on existing** `DiscrepancyNoteDAO.java`, not a new file.
  - Invented cluster keys (`audit-log-infra`, `bcrypt-encoder`) — drop.
  - `SaveItemsResponse` does NOT exist; response is inline LinkedHashMap.
  - Orphan DTO `RfcNoteDto` listed but never referenced — drop.
  - Confirm `DnItemDataMapDAO.createMapping` exists before coding.

---

### 3.4 `crf-data-types` — Tier 1 P0, **9d** (+1 reviewer)

**Summary:** Repeating item groups (per-eye findings), select-multi checkboxes, and file-upload items. Three feature payloads behind two PRs: (a) repeating groups + select-multi (JSON only, 5d), (b) file upload (multipart, 4d).

#### Backend skeleton

```
NEW    web/.../EventCrfFileController.java                  (multipart endpoints split off)
NEW    web/.../CrfGroupDto.java                             (ItemGroupDto + ItemGroupRowDto)
NEW    web/.../CrfFileUploadResponseDto.java
NEW    web/.../service/crf/CrfFileStorageService.java       (extension allowlist + size cap)
NEW    core/src/main/resources/migration/lc-muw-2026-06-09-crf-file-attachment-index.xml

MOD    web/.../CrfEntryDto.java                             (groups[], maxFileBytes, fileExtensions)
MOD    web/.../EventCrfsApiController.java                  (GET groups[], saveItems extended,
                                                             POST/DELETE /groups/{groupOid}/rows)
MOD    web/.../config/SecurityConfig.java                   (REVIEWER: config/ not spring/)
MOD    web/src/main/resources/at/.../applicationContext-web-beans.xml
       (REVIEWER: NOT applicationContext-mvc.xml — register CommonsMultipartResolver if missing)
MOD    docker/datainfo.properties                            (crf.file.maxBytes=52428800)
```

Endpoints:

```
POST   /api/v1/eventCrfs/{id}/items/{itemOid}/file       multipart/form-data
       → CrfFileUploadResponseDto
       err: 400/401/403/404/409/413/415
GET    /api/v1/eventCrfs/{id}/items/{itemOid}/file?rowOrdinal=N
       → application/octet-stream, Content-Disposition: attachment
DELETE /api/v1/eventCrfs/{id}/items/{itemOid}/file?rowOrdinal=N → 204
POST   /api/v1/eventCrfs/{id}/groups/{groupOid}/rows
       → { groupOid, rowOrdinal, values: {} }            (REVIEWER: not ItemGroupRowDto)
DELETE /api/v1/eventCrfs/{id}/groups/{groupOid}/rows/{rowOrdinal} → 204
POST   /api/v1/eventCrfs/{id}/items                       (existing, extended)
       req: SaveItemsRequest { values?: Map, groups?: List<GroupRowSavePayload> }
       res: existing + groupRowsSaved: int
```

Add explicit top-level `SaveItemsRequest` to `backend.dtos` (reviewer flag).

`CrfFileUploadResponseDto`: include `rowOrdinal` consistently (reviewer flag — playbook had it in DTO listing but not endpoint shape).

Liquibase: `idx_item_data_ec_item_ord` on `item_data(event_crf_id, item_id, ordinal)` with `not-exists` precondition.

#### SPA skeleton

```
NEW    web/src/spa/src/components/crf/RepeatingGroupSection.vue
NEW    web/src/spa/src/components/crf/CheckboxArrayInput.vue
NEW    web/src/spa/src/components/crf/FileUploadInput.vue
NEW    web/src/spa/src/api/upload.ts                     (apiUpload<T>(path, file, fields?))
MOD    web/src/spa/src/types/crf.ts                      (extend ItemDataType with 'file';
                                                          CrfItemGroup, CrfGroupRow, CrfFileMetadata)
MOD    web/src/spa/src/stores/crfEntry.ts                (addGroupRow, deleteGroupRow,
                                                          uploadFile, deleteFile, setValueInRow)
MOD    web/src/spa/src/views/CrfEntryView.vue            (render groups + select-multi + file branches)
MOD    web/src/spa/src/api/client.ts                     (extract handleResponse + detectAuthRedirect)
MOD    web/src/spa/src/locales/en.json + de.json
```

Test directory layout decision (reviewer flag): project convention is **flat** `web/src/spa/src/components/__tests__/`. Either keep flat (consistent) or introduce `components/crf/__tests__/` as new convention — **pick one and document.**

Store actions: `addGroupRow(groupOid)`, `deleteGroupRow(groupOid, rowOrdinal)`, `uploadFile(itemOid, file, rowOrdinal=1)`, `deleteFile(itemOid, rowOrdinal=1)`, `setValueInRow(groupOid, rowOrdinal, itemOid, value)`.

i18n keys (selected): `crfEntry.group.addRow`, `.deleteRow`, `.repeatMaxReached`, `crfEntry.file.dropPrompt`, `.tooBig`, `.badExtension`, `crfEntry.selectMulti.noneSelected`.

#### Tests

- **SPA**: 18 cases — 6 in crfEntry.test.ts, 4 RepeatingGroupSection, 3 CheckboxArrayInput, 5 FileUploadInput.
- **Backend**: 19 cases — 8 groups IT, 7 file controller IT, 4 storage service unit.
- **Smoke**: per-eye repeating group save + select-multi 3-box + retinal JPG upload + reload.

#### Acceptance criteria

- `groups[]` populated for `item_group_metadata.repeating_group=true`; rows survive round-trip.
- Add past `repeatMax` → 409 + i18n `crfEntry.group.repeatMaxReached`.
- select-multi: SPA renders checkboxes; backend stores comma-joined; GET deserialises to array.
- 5MB JPG upload lands under `attached_file_location/<crfOid>/<crfVersionOid>/<sha1+name>`; `ItemDataBean.value=absolute path`; GET streams bytes.
- 50MB+1 → 413; disallowed extension → 415; nothing written to disk.
- SIGNED/LOCKED → 409 on all new endpoints.
- Audit rows written for `item_group_row_create`/`_delete`/`item_data_file_upload`/`_delete`.

#### Sequencing & reviewer flags

- Split into 2 PRs: (a) groups + select-multi (5d), (b) file upload (4d).
- Land AFTER audit-log so `writeAuditEvent` actionMessages already serialise.
- Land BEFORE ophthalmology-specific CRF rollout.
- **Reviewer flags:**
  - `SecurityConfig.java` is at `config/`, not `spring/`.
  - `applicationContext-mvc.xml` does not exist; the file is `applicationContext-web-beans.xml` and has NO `MultipartResolver` bean today — confirm wiring before starting PR (b).
  - Invented cluster keys (`crf-entry-status-locked`, `audit-log-v1`) — drop or rename.
  - `SaveItemsRequest` missing from top-level DTO list — add explicitly.
  - `CrfFileUploadResponseDto` shape inconsistency: confirm `rowOrdinal` present.
  - Test-dir convention discrepancy — pick flat or per-subdir.
  - `EventCrfsApiController` already handles `select-multi` at line 666 — confirm current state before claiming "move serialization".
  - Estimate bumped +1d for multipart discovery + IT infra.

---

### 3.5 `bulk-import` — Tier 1 P0, **14d** (no adjustment)

**Summary:** Wire ImportCrfDataView's 4-step wireframe to the real `ImportCRFDataService` pipeline. Upload XML → preview diff per row → commit with RFC. Reuses RX.2 RulesImportApiController patterns (preflightWrite, session-parked container, single-use UUID token).

#### Backend skeleton

```
NEW    web/.../ImportApiController.java                     (mirrors RulesImportApiController)
NEW    web/.../ImportCrfPreviewDto.java
NEW    web/.../ImportCrfCommitResult.java
NEW    web/.../internal/ImportPreviewSession.java          (parked container, 15-min TTL)
NEW    web/.../ImportApiControllerTest.java
MOD    web/src/spa/src/views/ImportCrfDataView.vue          (replace hardcoded preview)
MOD    docs/development/modernization/phase-e/api-surface.md
MOD    web/src/spa/src/locales/en.json + de.json
```

Endpoints:

```
POST   /api/v1/import                              multipart .xml
       auth: sysadmin OR Director/Coordinator/Investigator/RA (StudyAdminAuthorization)
       res:  ImportCrfPreviewDto
       err:  401/403/415/400/422/500

POST   /api/v1/import/commit
       req:  CommitRequest { previewToken, reasonForChange?, overwriteMode? }
       res:  ImportCrfCommitResult
       err:  400 missing token/reason · 410 token unknown/expired/used · 500 rolled back

GET    /api/v1/import/{previewToken}/rows?offset=&limit=
       res:  PageImpl<PreviewRowDto>
             (REVIEWER: declare PreviewRowsPageDto explicitly OR call out
              Spring-default PageImpl shape so SPA TS mirror is unambiguous)
       err:  410 token unknown/expired
```

DTO fields per playbook (ImportCrfPreviewDto: token + summary counts + first 200 rows inline + issues list; PreviewRowDto: status/subject/event/crf/item triple; ImportIssue: scope/identifier/severity/message).

Liquibase: none.

#### SPA skeleton

```
NEW    web/src/spa/src/stores/importCrf.ts
NEW    web/src/spa/src/types/importCrf.ts                   (TS mirror of all DTOs)
NEW    web/src/spa/src/stores/__tests__/importCrf.test.ts
MOD    web/src/spa/src/views/ImportCrfDataView.vue
```

Store actions: `uploadFile(file)`, `fetchMoreRows(offset, limit)`, `commit(reasonForChange, overwriteMode)`, `reset()`. Wired into `useAuthStore.pickStudy` per per-study reset pattern.

View changes: step1 fileInput → store.uploadFile; step2 real counts; step3 paginated rows + RFC textarea gated on overwriteCount>0; step4 commit + audit-log link.

i18n keys: `importCrf.upload.uploading`, `.errorXsd`, `.errorWrongStudy`, `.errorFileType`, `importCrf.preview.loadingMoreRows`, `.issuesHeading`, `.overwriteMode.replace`, `.overwriteMode.skip`, `importCrf.commit.tokenExpired`, `.rowsInserted`, `.rowsOverwritten`, `.rowsSkipped`, `.discrepancyNotes`.

#### Tests

- **SPA**: 14 cases (10 store + 4 view).
- **Backend**: 8 MockMvc + 3 Testcontainers IT (deferred to MockMvc IT cohort).
- **Smoke**: compose up → login → upload sample ODM XML → preview → commit → audit_log row visible.

**Reviewer flag**: smoke references `core/src/test/resources/data/sampleImport.xml` — directory does not exist. **Commit a sample ODM 1.3 fixture as part of this cluster** OR change smoke to reference existing fixture.

#### Acceptance criteria

- Real subject/event/CRF/row counts in step 2 (no hardcoded 3/8/17/412).
- Preview rows with before/after diff + OCRERR_* messages.
- Imports >200 rows paginate via `/api/v1/import/{token}/rows`.
- Commit writes through `ItemDataDAO`/`EventCRFDAO` inside one Spring `@Transactional`.
- Every overwrite carries RFC in `audit_log.new_value` (GCP/21 CFR Part 11).
- Single-use token; 15-min expiry; double-Commit → 410.
- Non-XML → 415; wrong study OID → 422; XSD fail → 400.

#### Sequencing & reviewer flags

- Land AFTER `auth-admin` session contract (already shipped).
- Land BEFORE RFC generalisation across CrfEntryView edits — import-commit is the simplest concrete RFC use.
- **Pin transaction strategy upfront**: option (a) extract `ImportCRFDataPersistenceService` `@Service @Transactional` — 14d estimate **assumes (a)**. Option (b) drops estimate ~2d.
- **Reviewer flags:**
  - Invented cluster keys (`auth-session`, `reason-for-change`) — rename to `auth-admin` and `admin-rfc`.
  - Sample fixture missing — commit one or change smoke.
  - `Page<PreviewRowDto>` wrapper — declare DTO or call out PageImpl shape.
  - SPA viewChange[3] adds audit-log link with no AC — drop or add bullet.
  - `synchronized(rulesPostImportContainerService)` pattern from RX.2: `ImportCRFDataService` is also stateful — apply same single-flight guard.

---

### 3.6 `auth-admin` — Tier 2 P1, **1d** (no adjustment, mostly shipped)

**Summary:** DM-initiated user password reset already shipped under Phase E A7.4. Remaining work is test-gap closure (Vitest spec for `users.resetPassword` + 3 missing MockMvc negative branches).

#### Backend skeleton

```
MOD    web/.../UsersApiControllerTest.java
       (add SSO 400 / LDAP 400 / disabled 409 / unknown 404 cases)
```

Endpoint (LIVE):

```
POST   /api/v1/users/{username}/resetPassword
  req:  ResetPasswordRequest { sendEmail: Boolean? }
  res:  { generatedPassword: String|null }
```

#### SPA skeleton

```
NEW    web/src/spa/src/stores/__tests__/users.test.ts
       (4 cases — overlap with unlock-user's new file; consolidate)
```

Store action (LIVE): `users.resetPassword(username)` at `users.ts:280-311`.
View (LIVE): per-row 'Passwort zurücksetzen' in `ManageUsersView.vue`.

#### Tests

- **SPA**: 4 new cases.
- **Backend**: 3 new MockMvc cases.
- **Smoke**: curl POST → 200 + generatedPassword; SSO user → 400 'identity provider'.

#### Acceptance criteria

All 10 ACs in playbook are LIVE; remaining gaps:
- Vitest spec covers happy-path + directory-owned + 401 + 403.
- MockMvc covers SSO/LDAP 400, disabled 409, unknown 404.

#### Sequencing & reviewer flags

- Drop in alongside any other PR; ~0.25d Vitest + 0.25d MockMvc + 0.5d smoke + CHANGELOG.
- **Reviewer flag**: invented cluster keys `auth-admin-create` / `auth-admin-lifecycle` — collapse to `auth-admin` or remove (intra-cluster sequencing).
- Coordinate with `unlock-user` to avoid duplicating `users.test.ts` creation.

---

### 3.7 `restore-quickwins` — Tier 2 P1, **5d** (+1 reviewer)

**Summary:** Five disjoint parity wins — restore study event, restore event-CRF, restore dataset, download rule-set XML, wire existing dry-run endpoint to SPA.

#### Backend skeleton

```
MOD    web/.../EventsApiController.java                      (POST /{id}/restore + listEvents
                                                              ?includeRemoved=true — REVIEWER)
MOD    web/.../EventCrfsApiController.java                   (POST /{id}/restore;
                                                              extend EventDetailDto to surface
                                                              removed event_crf slots — REVIEWER)
MOD    web/.../DatasetsApiController.java                    (POST /datasets/{id}/restore +
                                                              listDatasets ?includeRemoved=true)
MOD    web/.../RulesApiController.java                       (GET /rule-sets/export streams XML)
```

Endpoints:

```
POST /pages/api/v1/events/{id}/restore                       (DM/Admin)  → StudyEventDto
POST /pages/api/v1/eventCrfs/{id}/restore                    (DM/Admin)  → 204
POST /pages/api/v1/datasets/{datasetId}/restore              (export RA) → DatasetDto
GET  /pages/api/v1/rule-sets/export?ruleSetRuleIds=…         (study admin) → application/xml
POST /pages/api/v1/rule-sets/{id}/dry-run                    (already shipped — wiring only)
```

DTOs: reuses existing `StudyEventDto`, `DatasetDto`.

Liquibase: none.

#### SPA skeleton

```
MOD    web/src/spa/src/stores/events.ts                (restoreEvent)
MOD    web/src/spa/src/stores/eventDetail.ts           (restoreEventCrf)
MOD    web/src/spa/src/stores/datasets.ts              (restoreDataset + showRemoved)
MOD    web/src/spa/src/stores/rules.ts                 (dryRunRuleSet + exportRulesXml)
MOD    web/src/spa/src/views/SubjectDetailView.vue
MOD    web/src/spa/src/views/EventDetailView.vue
MOD    web/src/spa/src/views/DatasetListView.vue
MOD    web/src/spa/src/views/RulesView.vue
NEW    web/src/spa/src/stores/__tests__/rules.test.ts  (REVIEWER: net-new, mark NEW)
```

i18n keys: `subjectDetail.event.restore`, `eventDetail.crf.restore`, `datasetList.showRemoved`, `rules.action.dryRun`, `rules.action.exportXml`, `rules.dryRun.empty`.

#### Tests

- **SPA**: 4 store specs + restore button view-level checks.
- **Backend**: 4 MockMvc test classes (Events / EventCrfs / Datasets / Rules).
- **Smoke**: curl POST restore → 200; curl GET rule-set XML → xmllint clean.

#### Acceptance criteria

- Restore endpoints flip status back + cascade nested rows from AUTO_DELETED → AVAILABLE.
- 409 when parent removed; sysadmin/Admin gate.
- DatasetListView 'Show removed' toggle + Restore action.
- RulesView Dry-run + Download XML buttons (canManage gate).
- Rules export XML round-trips through legacy import.

#### Sequencing & reviewer flags

- Ship as single PR — five endpoints independent.
- Lower-risk path: split into backend-first then SPA-wiring PRs if 5d slips.
- **Reviewer flags:**
  - Invented cluster keys (`phase-e4-m11-events`, `phase-e6-export-mvp`, `phase-e-rx`) — drop.
  - `events.test.ts` net-new — mark NEW.
  - `EventDetailDto.crfs()` built from event_definition_crf slots — must surface removed event_crf instances for restore button to render.
  - Need `?includeRemoved=true` on `GET /events` (or subjects.events source) — missing backend mod on `EventsApiController.listEvents`.
  - Estimate bumped +1d for the missing backend hooks.
- Flag: rules XML export should switch from temp-file dance to direct `OutputStream marshal` to avoid `SQLInitServlet.getField('filePath')` flakes.

---

### 3.8 `subject-lifecycle` — Tier 2 P1, **10d** (+1 reviewer)

**Summary:** Three SPA paths: (a) Show-removed matrix toggle + Restore button (b) Person-ID re-enrol branch reusing subject_id across sibling studies (c) StudyGroupClass assignment at enrolment + reassignment.

#### Backend skeleton

```
NEW    web/.../SubjectGroupAssignmentService.java
MOD    web/.../SubjectsApiController.java                   (extend AddSubjectRequest record:
                                                             personId + groupAssignments[];
                                                             ?includeRemoved on list;
                                                             PUT /subjects/{oid}/groups)
MOD    web/.../SubjectListItemDto.java                       (status + groups)
MOD    web/.../SubjectDetailDto.java                         (status + groupAssignments)
```

**Reviewer flag**: `AddSubjectRequest` is a Java record at `SubjectsApiController.java:1721` with positional ctor; adding fields forces edits at every positional call site (lines 703, 1811, 1938). Call out **record-callsite churn** as a subtask.

Endpoints:

```
POST /api/v1/subjects                                       (extended)
GET  /api/v1/subjects?includeRemoved=false                  (extended)
PUT  /api/v1/subjects/{oid}/groups                          (NEW; SubjectEditAuthorization)
POST /api/v1/subjects/{oid}/restore                         (already shipped)
```

DTOs (selected):

```
AddSubjectRequest (extended): personId: String, groupAssignments: List<GroupAssignment>
UpdateSubjectGroupsRequest: assignments: List<GroupAssignment>
GroupAssignmentSnapshot: groupClassId, groupClassName, groupId, groupName, subjectAssignment
```

**Reviewer flag**: `subject_group_map` has DB-level audit triggers (audit_event_type_id 28/29 since migration 3.2). If `SubjectGroupAssignmentService` also calls `AuditEventDAO`, every edit double-audits. **Decision required**: trigger-owned or service-owned audit row.

**Reviewer flag**: Adding `groupAssignments` to `SubjectListItemDto` risks N+1 on matrix load. Mitigation: join in `StudySubjectDAO.findAllByStudy` or batch-fetch.

Liquibase: none.

#### SPA skeleton

```
NEW    web/src/spa/src/components/subjects/GroupAssignmentPicker.vue
NEW    web/src/spa/src/components/subjects/PersonIdLookup.vue
NEW    web/src/spa/src/stores/__tests__/subjects.lifecycle.test.ts
MOD    web/src/spa/src/stores/subjects.ts                    (showRemoved + restoreSubject +
                                                              replaceGroups + extended add())
MOD    web/src/spa/src/stores/groupClasses.ts                (assignableGroups computed)
MOD    web/src/spa/src/types/subject.ts                      (status + groupAssignments)
MOD    web/src/spa/src/views/SubjectMatrixView.vue           (Show-removed + Restore + Status column)
MOD    web/src/spa/src/views/AddSubjectView.vue              (PersonIdLookup + GroupAssignmentPicker)
MOD    web/src/spa/src/views/SubjectDetailView.vue           (Restore + Edit-assignments modal)
```

Store actions: `restoreSubject(oid)`, `replaceGroups(oid, assignments)`, extended `load()` w/ `?includeRemoved=true`, extended `add()` with personId + groupAssignments.

i18n keys (selected): `subjectMatrix.filter.showRemoved`, `.action.restore`, `addSubject.field.personId.reusing`, `.error.personIdAlreadyEnrolled`, `subjectDetail.groups.edit`.

#### Tests

- **SPA**: 12 lifecycle store cases + 3 matrix view + 4 AddSubject view.
- **Backend**: 10 SubjectsApi IT + 5 SubjectGroupAssignmentService unit.
- **Smoke**: curl POST with personId + groupAssignments → 201; matrix?includeRemoved=true.

#### Acceptance criteria

- DM/Admin Show-removed toggle + Restore button end-to-end.
- Investigator Person-ID lookup surfaces 'Reusing existing record'; sibling study reuses subject_id (one subject row, two study_subject rows).
- 409 Person-ID already enrolled in current study.
- REQUIRED group_classes block submit until picked.
- SubjectDetailView Edit modal reconciles inserts/soft-deletes.
- Legacy JSP still works untouched.

#### Sequencing & reviewer flags

- Land AFTER M5 (CRF read endpoint) and BEFORE Tier 1 Sign-Subject hardening.
- Split into PR1 (restore + showRemoved, 2d, low risk) + PR2 (Person-ID + groups, 8d).
- **Reviewer flags:**
  - Invented cluster keys (`group-classes-crud`, `subject-matrix-already-shipped`) — drop.
  - Record-callsite churn for `AddSubjectRequest` not noted.
  - DB-trigger vs service audit decision required (double-audit risk).
  - N+1 risk on matrix load not flagged.
  - Estimate bumped +1d.

---

### 3.9 `crf-entry-advanced` — Tier 2 P1, **12d** (no adjustment)

**Summary:** Three composable enhancements to `CrfEntryView`: (1) TOC SideRail badges per section (required/filled/error/openQueries), (2) concurrent-edit soft-lock probe + 30s heartbeat banner, (3) per-item discrepancy indicator + thread popover.

#### Backend skeleton

```
NEW    web/.../EventCrfLockProbeDto.java
NEW    web/.../EventCrfNotesRollupDto.java
NEW    web/.../service/crf/EventCrfPresenceRegistry.java     (@Scope("singleton") ConcurrentHashMap,
                                                              60s TTL; REVIEWER: create service/crf/
                                                              package — only service/extract/ exists)
MOD    web/.../EventCrfsApiController.java                   (4 new endpoints; SectionStatusDto)
MOD    web/.../CrfEntryDto.java                              (sectionStatuses; sectionOid)
MOD    web/.../DiscrepancyApiController.java                 (extract queryByEventCrfId helper)
MOD    web/src/main/resources/at/.../applicationContext-web-beans.xml
       (REVIEWER: register EventCrfPresenceRegistry singleton — missing from file inventory)
```

Endpoints:

```
GET    /api/v1/eventCrfs/{id}/lock-status        → EventCrfLockProbeDto
POST   /api/v1/eventCrfs/{id}/heartbeat          → EventCrfLockProbeDto (409 collision)
GET    /api/v1/eventCrfs/{id}/notes              → EventCrfNotesRollupDto
GET    /api/v1/eventCrfs/{id}/section-status     → List<SectionStatusDto>
```

Liquibase: none (soft-lock in-memory).

#### SPA skeleton

```
NEW    web/src/spa/src/components/SectionBadge.vue
NEW    web/src/spa/src/components/ConcurrentEditBanner.vue
NEW    web/src/spa/src/components/ItemNoteThread.vue
NEW    web/src/spa/src/components/ItemNoteIndicator.vue
NEW    web/src/spa/src/stores/crfEntryAdvanced.ts
NEW    web/src/spa/src/stores/__tests__/crfEntryAdvanced.test.ts
MOD    web/src/spa/src/views/CrfEntryView.vue
MOD    web/src/spa/src/types/crf.ts                          (SectionStatus, LockProbe,
                                                              ItemNoteSummary, NotesRollup)
MOD    web/src/spa/src/stores/crfEntry.ts                    (sectionFilledCounts getter)
MOD    web/src/spa/src/locales/en.json + de.json
       (REVIEWER: locales/ not i18n/ — playbook had wrong dir)
```

Store actions: `loadLockProbe`, `startHeartbeat`/`stopHeartbeat`, `loadNotesRollup`, `loadSectionStatuses`. Composes with `useCrfEntryStore` — does not replace it.

i18n keys: `crfEntry.sectionBadge.requiredFilled`, `.errors`, `.openQueries`, `crfEntry.concurrentEdit.bannerTitle`, `.lastSeen`, `crfEntry.itemNote.openThread`, `.statusNew`, `.statusResolved`.

#### Tests

- **SPA**: 12 store + 2 added to crfEntry + 5 SectionBadge + 6 ItemNoteThread = 25 cases.
- **Backend**: 8 added EventCrfsApi MockMvc + 5 EventCrfPresenceRegistry unit (thread-safety).
- **Smoke**: two cookie jars → both GET → second sees `sameUser=false`; SPA SideRail badges render.

#### Acceptance criteria

- Single batched fetch on view open; SideRail badges in same paint.
- SideRail shows `X/Y` filled + error icon + amber query badge.
- Two concurrent sessions: second sees banner with first user + lastSeen.
- ItemNoteIndicator + popover; new query from popover increments section badge.
- All four endpoints honour `siteVisibilityFilter`.
- i18n parity en + de.

#### Sequencing & reviewer flags

- Land after M5/M6 (shipped) + M7 (shipped) + core SPA cleanup clusters.
- Split into 3 feature branches: SideRail badges (3d, lowest risk) → notes-rollup + popover (5d) → concurrent-edit + heartbeat (4d).
- **Reviewer flags:**
  - locale paths are `locales/`, not `i18n/`.
  - Invented cluster keys (`crf-entry-core`, `notes-discrepancies-core`) — drop.
  - `service/crf/` package needs creating.
  - `applicationContext-web.xml` singleton wiring missing from file inventory.
- Watch: Pinia ordering on view mount; fake-timers config for heartbeat tests.

---

### 3.10 `crf-library` — Tier 2 P1, **10d** (no adjustment)

**Summary:** Per-version Lock/Unlock/Restore/Hard-remove/Download XLS actions + per-CRF batch v.A → v.B assignment migration. Fills the CRF-Library SPA gap so DMs no longer bail to legacy JSP.

#### Backend skeleton

```
NEW    web/.../service/CrfVersionMigrationService.java
NEW    web/.../CrfsApiControllerLifecycleTest.java
MOD    web/.../CrfsApiController.java                        (5 endpoints + DTOs)
MOD    web/.../config/LegacyServletRegistry.java             (keep legacy paths reachable
                                                              for parallel-run reconciliation)
```

Endpoints:

```
POST   /api/v1/crfs/{crfOid}/versions/{versionOid}/lock
POST   /api/v1/crfs/{crfOid}/versions/{versionOid}/unlock
POST   /api/v1/crfs/{crfOid}/versions/{versionOid}/restore
DELETE /api/v1/crfs/{crfOid}/versions/{versionOid}            (sysadmin only)
       err 409 + VersionUsageReport when referenced
GET    /api/v1/crfs/{crfOid}/versions/{versionOid}/xls         (any authenticated)
POST   /api/v1/crfs/{crfOid}/versions/{from}/migrate-to/{to}
       req:  MigrateVersionRequest { sedOids: List<String>?, dryRun: boolean }
       res:  MigrateVersionResult
```

DTOs: `MigrateVersionRequest`, `MigrateVersionResult` + `SedMigrationRow`, `VersionUsageReport`.

**Reviewer flag (AC6)**: `writeLifecycleAudit(oldStatus, newStatus)` only models status_id transitions; **migrate touches `event_definition_crf.default_version_id`, NOT a status flip**. Either add a new audit helper variant or drop the per-SED audit claim.

**Reviewer flag (liquibase entry)**: placeholder Liquibase row is self-cancelling. `AuditEventDAO.create` does not bind `audit_log_event_type_id`, so `writeLifecycleAudit` works without seed. **Either drop the placeholder OR commit to a follow-up seed.**

Liquibase: drop placeholder unless `audit_log_event_type` seed needed for SPA filter buckets.

#### SPA skeleton

```
NEW    web/src/spa/src/components/CrfVersionMigrationDialog.vue
MOD    web/src/spa/src/views/CrfLibraryView.vue
MOD    web/src/spa/src/stores/crfLibrary.ts                  (6 new actions)
MOD    web/src/spa/src/types/crfLibrary.ts                   (status union; new interfaces)
MOD    web/src/spa/src/locales/de.json + en.json
```

Store actions: `lockVersion`, `unlockVersion`, `restoreVersion`, `hardRemoveVersion`, `downloadVersionXls`, `migrateVersion`.

i18n keys (selected): `crfLibrary.lock`, `.unlock`, `.restore`, `.hardRemove`, `.hardRemoveBlocked`, `crfLibrary.migrate.action`, `.dryRunHeading`, `.commit`.

#### Tests

- **SPA**: 12 store + 5 dialog.
- **Backend**: 10 lifecycle MockMvc.
- **Smoke**: curl POST lock → status=locked; GET .xls → opens in Excel; POST migrate dry-run → perSed table.

#### Acceptance criteria

- Lock/Unlock end-to-end without reload.
- Restore exposed only when `includeRemoved && status==='removed'`.
- Hard-remove sysadmin-only + 409 + VersionUsageReport when referenced.
- Download .xls returns original spreadsheet w/ legacy filename fallback.
- Batch migrate reassigns SEDs + writes audit per affected SED (subject to reviewer flag resolution).
- Legacy `/pages/*` paths reachable for ≥1 release cycle.

#### Sequencing & reviewer flags

- Land AFTER `manage-users` reset-password (shipped) + audit-trail viewer (shipped).
- Hard-remove wired last (highest risk).
- **Reviewer flags:**
  - Invented cluster keys (`manage-users`, `audit-trail`) — rename.
  - AC6 audit claim needs new helper variant OR drop.
  - Liquibase placeholder is noise — drop or commit to seed.

---

### 3.11 `discrepancy-full` — Tier 2 P1, **10d** (no adjustment)

**Summary:** Extend M7 with (1) full parent+children thread render, (2) accept all four note types on composer, (3) email notification on every state change, (4) CSV export mirroring `DiscrepancyNoteOutputServlet`.

**Note: must land BEFORE `dde`** because DDE auto-spawns FAILEDVAL notes via this cluster's type-field generalisation.

#### Backend skeleton

```
NEW    web/.../DiscrepancyThreadEntryDto.java
NEW    web/.../service/discrepancy/DiscrepancyEmailNotifier.java
NEW    web/.../DiscrepancyExportCsv.java                     (RFC 4180 CSV builder)
MOD    web/.../DiscrepancyApiController.java                 (4 changes — REVIEWER: AddQueryRequest
                                                              type field edit is INSIDE this file,
                                                              line 598 inner record — not a separate
                                                              AddQueryRequest.java file)
MOD    web/.../DiscrepancyNoteDto.java                       (thread field)
MOD    web/.../NoteTransitionMatrix.java                     (typeIdForSpaName helper)
```

Endpoints:

```
GET    /api/v1/discrepancies/{parentId}/thread          → DiscrepancyNoteDto (thread populated)
POST   /api/v1/discrepancies                             (existing + type field + role check on RFC)
POST   /api/v1/discrepancies/{parentId}/thread          (existing + email fires)
GET    /api/v1/discrepancies/export.csv                 → text/csv attachment
```

DTOs: `DiscrepancyThreadEntryDto`, extended `DiscrepancyNoteDto.thread`, extended `AddQueryRequest.type`.

Liquibase: none.

#### SPA skeleton

```
NEW    web/src/spa/src/components/discrepancy/ThreadTimeline.vue
NEW    web/src/spa/src/components/discrepancy/NoteTypeSelect.vue
NEW    web/src/spa/src/stores/__tests__/notes.thread.test.ts
MOD    web/src/spa/src/stores/notes.ts                       (loadThread + add(type) +
                                                              buildExportUrl)
MOD    web/src/spa/src/types/note.ts                         (re-export ThreadEntry)
MOD    web/src/spa/src/views/NotesDiscrepanciesView.vue      (expand chevron + composer +
                                                              CSV export button + NEW parent-note
                                                              creation dialog — REVIEWER: distinct
                                                              from existing thread-response composer)
MOD    web/src/spa/src/locales/en.json + de.json
```

Store actions: `loadThread(parentId)`, extended `add(input)` w/ type, `buildExportUrl()`.

i18n keys: `notes.thread.title`, `.empty`, `notes.composer.type`, `.typeReasonForChangeRoleHint`, `notes.actions.export`, `notes.email.subjectAssigned`.

#### Tests

- **SPA**: 5 store + 3 view.
- **Backend**: 6 DiscrepancyApiController + 3 EmailNotifier unit.
- **Smoke**: curl GET thread; type=reason-for-change as Monitor → 403; CSV download in browser; MailHog :1080 shows assignment email.

#### Acceptance criteria

- Expand row → full thread from server data.
- All 4 types; RFC hidden for non-DM/Admin + 403 on forge.
- Email fires on create + state change + reassignment; MailException swallowed.
- CSV: text/csv attachment + RFC 4180 + filename includes study OID + ISO date.
- Backwards compat: `thread` defaults to `[]` when caller did not request thread endpoint.

#### Sequencing & reviewer flags

- Land after M7 baseline (shipped).
- Recommended order: backend thread+tests → SPA thread render → type field → email (verify against MailHog) → CSV.
- **Reviewer flags:**
  - `AddQueryRequest.java` does NOT exist — type-field edit is inside `DiscrepancyApiController.java:598` inner record.
  - Invented cluster keys (`m7-discrepancies-baseline`, `m4-users`) — drop.
  - Clarify NEW parent-note creation dialog vs existing thread-response composer.

---

### 3.12 `dde` — Tier 2 P1, **14d** (+2 reviewer)

**Summary:** Blind double-data-entry pass 2 + DM-led reconciliation. Pass 1 clerk completes; different clerk re-keys blind; mismatches spawn FAILEDVAL notes; DM picks IDE/DDE/manual winner with RFC.

#### Backend skeleton

```
NEW    web/.../DdePassDto.java
NEW    web/.../DdeConflictsDto.java
NEW    web/.../DdeCommitRequest.java
NEW    web/.../DdeCommitResponse.java
NEW    web/.../DdeReconcileRequest.java
NEW    web/.../service/dde/DdeService.java
NEW    web/.../EventCrfsApiControllerDdeTest.java
MOD    web/.../EventCrfsApiController.java                   (4 endpoints + DDE branch in getEventCrf)
MOD    web/.../CrfEntryDto.java                              (nullable dde block)
MOD    web/.../DiscrepancyApiController.java                 (allow type=failed-validation,
                                                              currently hard-coded QUERY at line 299)
MOD    core/.../EventCRFDAO.java                             (wire markCompleteDDE +
                                                              findValidatorIdByEventCrfId)
```

**Reviewer flag (BLOCKER)**: `writeAuditEvent` is a **private static helper** inside `EventCrfsApiController`. `DdeService` cannot call private. **Promote to package-private utility** as a sub-task **before DDE work starts**.

**Reviewer flag (DAO path)**: `markCompleteDDE` is at `core/src/main/resources/properties/eventcrf_dao.xml:411` (and `oracle_eventcrf_dao.xml`), **NOT `oc_db_postgres.xml`** (which doesn't exist).

Endpoints:

```
GET    /api/v1/eventCrfs/{id}/dde-pass               → DdePassDto
                                                       (403 same-clerk-as-IDE; 409 not DDE-enabled)
POST   /api/v1/eventCrfs/{id}/dde-commit             → DdeCommitResponse
                                                       (spawns FAILEDVAL DNs)
GET    /api/v1/eventCrfs/{id}/dde-conflicts          → DdeConflictsDto  (DM/Admin/Investigator)
POST   /api/v1/eventCrfs/{id}/dde-conflicts/{itemOid}/resolve
       req:  DdeReconcileRequest { winner, value?, reasonForChange }
       res:  204 (REVIEWER: 303 See Other if redirecting to list, OR 200 + URI body —
              204 + Location is unusual REST)
```

Liquibase: `lc-muw-2026-06-08-dde-index.xml`.

**Reviewer flag (Liquibase)**: partial index `WHERE status_id IN (5,6)` is semantically wrong — `item_data.status_id=5/6` = removed/locked. The DDE diff compares live AVAILABLE (1). **Fix to `WHERE status_id = 1`** or drop the status filter entirely.

#### SPA skeleton

```
NEW    web/src/spa/src/views/DdeReconcileView.vue
NEW    web/src/spa/src/stores/dde.ts
NEW    web/src/spa/src/types/dde.ts
NEW    web/src/spa/src/stores/__tests__/dde.test.ts
MOD    web/src/spa/src/views/CrfEntryView.vue                (pass-2 blind mode + banner +
                                                              delegate save → ddeStore)
MOD    web/src/spa/src/stores/crfEntry.ts                    (carry dde block; delegate save)
MOD    web/src/spa/src/types/crf.ts                          (CrfEntry.dde)
MOD    web/src/spa/src/router/index.ts                       (/event-crfs/:oid/dde-reconcile)
MOD    web/src/spa/src/views/BuildStudyView.vue              (REVIEWER: doubleEntry is written at
                                                              EventDefinitionsApiController.java:681
                                                              not BuildStudyApiController, AND the
                                                              SPA edit point is likely
                                                              EventCrfAssignmentsDialog —
                                                              CONFIRM before coding)
MOD    web/src/spa/src/views/SubjectMatrixView.vue           (badge variant + action-menu entry)
MOD    web/src/spa/src/locales/en.json + de.json             (26 dde.* keys including German)
```

Store actions: `loadPass`, `commitPass2`, `loadConflicts`, `resolve`; extended `crfEntry.save()` delegates to `ddeStore.commitPass2` when `pass===2`.

i18n keys (selected): `dde.banner.blindSecondPass`, `dde.commit.button`, `dde.reconcile.title`, `dde.reconcile.winner.ide/dde/manual`, `dde.reconcile.reasonForChange.label`, `dde.errors.sameClerk`, `dde.errors.notDdeEnabled`.

#### Tests

- **SPA**: 8 dde store + 2 crfEntry + 4 reconcile table = 14 cases.
- **Backend**: 12 EventCrfsApi DDE MockMvc + 6 DdeService unit.
- **Smoke**: clerk1 Pass1 → clerk2 banner + blind Pass2 with mismatch → DM reconcile.

#### Acceptance criteria

- BuildStudy persists doubleEntry=true.
- Pass 1 complete → status='complete' + dde.pass=1.
- Pass 2 GET returns values={} (server-side blinding).
- 0 mismatches → DDE_COMPLETE; N mismatches → N FAILEDVAL notes + status='dde-conflicts'.
- Reconciliation side-by-side; IDE/DDE/manual winner; RFC required; batch resolve flips to complete.
- Same-clerk blocked (403).
- Audit log surfaces dde-commit + dde-resolve.
- Single-entry flow unchanged.
- **Add explicit AC** for SubjectMatrix badge + 'Reconcile DDE' menu DM/Admin-only visibility (reviewer flag).
- **Add explicit AC** for German strings on every new key (reviewer flag, MUW German-UI binding).

#### Sequencing & reviewer flags

- Land AFTER `discrepancy-full`'s FAILEDVAL generalisation.
- Land BEFORE `audit-discrepancy-export` Phase E.4 M10 viewer so dde-commit/dde-resolve action_messages already in audit_event table.
- Budget: 3d backend service+endpoints, 2d tests, 3d SPA blinding, 2d reconcile view + matrix badge, 1d BuildStudy checkbox, 1d i18n, 2d buffer.
- **Reviewer flags:**
  - `BuildStudyApiController.java` is only 208 lines; `doubleEntry` is at `EventDefinitionsApiController.java:681`. SPA edit likely `EventCrfAssignmentsDialog`, not `BuildStudyView.vue`. **Confirm before coding**.
  - `markCompleteDDE` path corrected to `eventcrf_dao.xml`.
  - Invented cluster keys (`discrepancies`, `audit-log`, `build-study`) — rename to `discrepancy-full` and `audit-discrepancy-export`.
  - Partial index semantically wrong — fix `status_id` filter.
  - `writeAuditEvent` private — promote BEFORE DdeService work.
  - Missing AC for SubjectMatrix badge.
  - Missing AC for German i18n parity.
  - `204 + Location` REST anti-pattern — use `303 See Other` or `200 + URI body`.
  - Estimate bumped +2d for extraction subtask + matrix-badge work + reviewer-flag remediation.

---

### 3.13 `audit-discrepancy-export` — Tier 2 P1, **5d** (+1 reviewer)

**Summary:** Two new endpoints for sponsor/inspector hand-offs — `/audit/export.xlsx` and `/discrepancies/export.csv`. Includes Apache POI 3.0.1 → 5.3.0 bump (preparatory commit).

#### Backend skeleton

```
NEW    web/.../export/XlsxWorkbookBuilder.java               (XSSF helper)
NEW    web/.../export/CsvWriter.java                         (RFC 4180 + UTF-8 + BOM + CRLF)
NEW    web/.../export/AuditWorkbookProjector.java
NEW    web/.../export/DiscrepancyCsvProjector.java
NEW    web/.../service/audit/ExportAuditEmitter.java
NEW    web/.../export/XlsxWorkbookBuilderTest.java
NEW    web/.../export/CsvWriterTest.java
MOD    web/.../AuditApiController.java                       (GET /export.xlsx + extract
                                                              collectFilteredRows)
MOD    web/.../DiscrepancyApiController.java                 (GET /export.csv)
MOD    web/pom.xml                                            (POI 3.0.1-FINAL → 5.3.0 + poi-ooxml)
MOD    pom.xml                                                (dependencyManagement bump)
```

**Reviewer flag (POI bump)**: existing call sites use POI 3.x int constants (`HSSFCell.CELL_TYPE_NUMERIC` etc.) at `CrfJsonToWorkbookAdapter.java:618` and `SpreadSheetTableClassic.java:710,733,1232-1389`. POI 4.0 removed `setCellType(int)`; multi-file `CellType` enum refactor required. **Land bump as preparatory commit** before endpoint code. +1d already applied.

Endpoints:

```
GET    /api/v1/audit/export.xlsx?actor=&variant=&subjectId=
       → application/vnd.openxmlformats-officedocument.spreadsheetml.sheet
       Content-Disposition: attachment; filename=audit_<studyOid>_<yyyyMMdd>.xlsx
GET    /api/v1/discrepancies/export.csv?status=&subjectId=&assignedTo=
       → text/csv; charset=utf-8
       Content-Disposition: attachment; filename=discrepancies_<studyOid>[_<subject>]_<yyyyMMdd>.csv
```

DTOs: reuses existing `AuditEventDto` + `DiscrepancyNoteDto`.

Liquibase:

```
core/src/main/resources/migration/lc-muw-2026-06-10-audit-event-type-audit-log-export.xml
  → audit_log_event_type id=54 name='audit_log_exported'
core/src/main/resources/migration/lc-muw-2026-06-10-audit-event-type-discrepancy-export.xml
  → audit_log_event_type id=55 name='discrepancy_log_exported'
core/src/main/resources/migration/master.xml
  (REVIEWER: NOT lc-muw-changelog.xml — master file is master.xml; add <include> entries here)
```

`AuditApiController.variantForType` maps 54/55 → 'admin'.

#### SPA skeleton

```
NEW    web/src/spa/src/api/download.ts                       (apiDownload helper)
NEW    web/src/spa/src/stores/__tests__/auditExport.test.ts
NEW    web/src/spa/src/stores/__tests__/notesExport.test.ts
MOD    web/src/spa/src/stores/auditLog.ts                    (isExporting + exportXlsx)
MOD    web/src/spa/src/stores/notes.ts                       (isExporting + exportCsv)
MOD    web/src/spa/src/views/StudyAuditLogView.vue           (Export XLSX button)
MOD    web/src/spa/src/views/NotesDiscrepanciesView.vue      (Export CSV button — subject-scoped label)
MOD    web/src/spa/src/locales/en.json + de.json
```

Store actions: `exportXlsx()`, `exportCsv(opts?)` w/ optional subject scope.

i18n keys: `auditLog.actions.exportXlsx`, `.exporting`, `.error.exportFailed`, `notes.actions.exportCsv`, `.exportForSubject`, `.exporting`, `.error.exportFailed`.

#### Tests

- **SPA**: 3 auditExport + 4 notesExport.
- **Backend**: 4 + 2 + 2 = 8 cases (XlsxWorkbookBuilder, CsvWriter, AuditApi 400/401, DiscrepancyApi 400/401).
- **Smoke**: download .xlsx, verify Excel opens; .csv with umlauts (BOM); verify `audit_log_event` row with type_id 54 or 55.

#### Acceptance criteria

- 'Export XLSX' downloads `audit_<studyOid>_<date>.xlsx` matching on-screen filter count.
- 'Export CSV' downloads `discrepancies_…csv` UTF-8 + BOM + CRLF + RFC 4180.
- Server-side narrowing matches GET endpoint filters.
- `SiteVisibilityFilter` honoured (Monitor site-only never sees sibling-site rows).
- Each export writes `audit_log_event` row with type 54/55 + active filter set.
- POI 5.3.0 bump verified; mvn package green; existing HSSF call sites compile.

#### Sequencing & reviewer flags

- **Land POI bump as preparatory commit/PR** (multi-file CellType enum refactor).
- Land BEFORE any other cluster allocating audit_log_event_type 54/55. (E.6 used 50/51/52/53.)
- Non-blocking for go-live (legacy JSP still exports).
- **Reviewer flags:**
  - Invented cluster keys (`auth-bootstrap-active-study`, `discrepancy-thread`) — rename.
  - POI bump understated — +1d applied.
  - Master changelog path is `master.xml`, NOT `lc-muw-changelog.xml`.
- Watch: POI 5.x brings xmlbeans + commons-compress + log4j-api 2.x — confirm log4j-to-slf4j bridge covers POI's new log4j-api callers.

---

## 4. Reviewer issue log (appendix)

All clusters returned `fix-needed`. Issues grouped by category for quick triage:

### Invented cluster keys (drop or rename)

| Cluster | Invented key(s) | Fix |
|---------|----------------|-----|
| `unlock-user` | `users-locked-on-wire` | Absorbed; drop |
| `study-params` | `build-study-edit`, `audit-log` | Drop or → `audit-discrepancy-export` |
| `admin-rfc` | `audit-log-infra`, `bcrypt-encoder` | Drop |
| `crf-data-types` | `crf-entry-status-locked`, `audit-log-v1` | → `crf-entry-advanced`, `audit-discrepancy-export` |
| `bulk-import` | `auth-session`, `reason-for-change` | → `auth-admin`, `admin-rfc` |
| `crf-library` | `manage-users`, `audit-trail` | → `auth-admin`, `audit-discrepancy-export` |
| `crf-entry-advanced` | `crf-entry-core`, `notes-discrepancies-core` | Drop (implicit Phase E.4 contracts) |
| `subject-lifecycle` | `group-classes-crud`, `subject-matrix-already-shipped` | Drop |
| `discrepancy-full` | `m7-discrepancies-baseline`, `m4-users` | Drop |
| `restore-quickwins` | `phase-e4-m11-events`, `phase-e6-export-mvp`, `phase-e-rx` | Drop |
| `audit-discrepancy-export` | `auth-bootstrap-active-study`, `discrepancy-thread` | Drop / → `discrepancy-full` |
| `dde` | `discrepancies`, `audit-log`, `build-study` | → `discrepancy-full`, `audit-discrepancy-export` |
| `auth-admin` | `auth-admin-create`, `auth-admin-lifecycle` | Collapse to `auth-admin` |

### File-path corrections (apply before coding)

| Cluster | Wrong path | Correct path |
|---------|-----------|--------------|
| `study-params` | `locales/de.ts` `en.ts` | `locales/de.json` `en.json` |
| `study-params` | `AddSubjectView.test.ts (extend)` | mark NEW |
| `admin-rfc` | `core/.../dao/managestudy/discrepancy_note.xml` | `core/src/main/resources/properties/discrepancy_note_dao.xml` |
| `admin-rfc` | New `DiscrepancyNoteDAO.findLatestRfcParentForItemData` file | Method on existing `DiscrepancyNoteDAO.java` |
| `crf-data-types` | `spring/SecurityConfig.java` | `config/SecurityConfig.java` |
| `crf-data-types` | `applicationContext-mvc.xml` | `applicationContext-web-beans.xml` |
| `crf-entry-advanced` | `i18n/en.json` `de.json` | `locales/en.json` `de.json` |
| `discrepancy-full` | `AddQueryRequest.java` file | Inner record at `DiscrepancyApiController.java:598` |
| `dde` | `oc_db_postgres.xml` | `core/src/main/resources/properties/eventcrf_dao.xml:411` + `oracle_eventcrf_dao.xml` |
| `dde` | `BuildStudyApiController.java:681` | `EventDefinitionsApiController.java:681`; SPA likely `EventCrfAssignmentsDialog` |
| `audit-discrepancy-export` | `lc-muw-changelog.xml` | `core/src/main/resources/migration/master.xml` |
| `restore-quickwins` | `events.test.ts (extend)` | mark NEW |
| `bulk-import` | `core/src/test/resources/data/sampleImport.xml` | Commit fixture OR change smoke |

### DTO / contract issues

| Cluster | Issue |
|---------|-------|
| `unlock-user` | responseShape is inline `Map<String,Object>` mirroring resetPassword, NOT typed UnlockUserResponse |
| `study-params` | `MeApiController` embed has no AC — add bullet or drop |
| `study-params` | Field count: use **19** consistently (DTO + ACs disagreed) |
| `admin-rfc` | `SaveItemsResponse` does NOT exist; response inline LinkedHashMap |
| `admin-rfc` | Orphan `RfcNoteDto` listed but never referenced |
| `crf-data-types` | `SaveItemsRequest` missing from top-level DTO list |
| `crf-data-types` | POST `/groups/{groupOid}/rows` inline `{groupOid, rowOrdinal, values}` vs declared `ItemGroupRowDto` |
| `crf-data-types` | `CrfFileUploadResponseDto` rowOrdinal inconsistency |
| `bulk-import` | `Page<PreviewRowDto>` — declare wrapper DTO or call out PageImpl shape |
| `dde` | 204 + Location is unusual REST — use 303 or 200+URI |

### Logic / acceptance gaps

| Cluster | Issue |
|---------|-------|
| `unlock-user` | Audit block copy needs columnName + value swap (not literal "passwd") |
| `unlock-user` | Update positional `new StudyUserDto(...)` at line ~237 |
| `admin-rfc` | Confirm `DnItemDataMapDAO.createMapping` exists |
| `crf-data-types` | Confirm Spring 6 multipart auto-config — bean wiring may be needed |
| `crf-data-types` | Confirm `select-multi` current state at line 666 (may already split) |
| `crf-data-types` | Test-dir convention (flat vs subdir) — pick one |
| `subject-lifecycle` | Record-callsite churn for `AddSubjectRequest` (lines 703, 1811, 1938) |
| `subject-lifecycle` | DB-trigger vs service audit decision (subject_group_map double-audit risk) |
| `subject-lifecycle` | N+1 risk on matrix load — batch-fetch needed |
| `crf-library` | Migrate writes `default_version_id` not status — audit helper variant or drop AC |
| `crf-library` | Liquibase placeholder self-cancelling — drop or commit to seed |
| `crf-entry-advanced` | `service/crf/` package needs creating |
| `crf-entry-advanced` | `applicationContext-web-beans.xml` singleton wiring missing from inventory |
| `dde` | `writeAuditEvent` private — promote BEFORE DdeService work |
| `dde` | Partial index `status_id IN (5,6)` semantically wrong (= removed/locked) — use `status_id = 1` |
| `dde` | Missing AC for SubjectMatrix badge variant + DM/Admin menu visibility |
| `dde` | Missing AC for German i18n parity |
| `bulk-import` | Audit-log download link mentioned in view but no AC — drop or add bullet |
| `bulk-import` | Pin transaction strategy (a) vs (b) before starting — affects estimate ±2d |
| `restore-quickwins` | `EventDetailDto.crfs()` must surface removed event_crf slots for restore to render |
| `restore-quickwins` | Need `?includeRemoved=true` on `GET /events` (missing backend mod) |
| `discrepancy-full` | Clarify NEW parent-note dialog vs existing thread-response composer |
| `audit-discrepancy-export` | POI 4.0+ removed `setCellType(int)` — 4 call sites need CellType enum refactor |

### Estimate adjustments applied

| Cluster | Raw | Adjusted | Reason |
|---------|----:|---------:|--------|
| `study-params` | 5 | **6** | View rewires (AddSubject, CrfEntry, DN-affordance) understated |
| `crf-data-types` | 8 | **9** | Multipart wiring discovery on legacy XML config |
| `subject-lifecycle` | 9 | **10** | Record-callsite churn + N+1 mitigation + audit-source decision |
| `restore-quickwins` | 4 | **5** | Missing backend mods (EventDetailDto + listEvents includeRemoved) |
| `audit-discrepancy-export` | 4 | **5** | POI 5.x CellType enum refactor at 4 call sites |
| `dde` | 12 | **14** | `writeAuditEvent` extraction + SubjectMatrix badge + missing AC remediation |

---

## 5. Closing recommendation — first 3 PR-sized chunks

Given the operational constraints (paper-first DDE remains, 3 local accounts means forgot-password stays dropped, single-site removes multi-site work), my recommended ship sequence for the next 3 PRs:

### PR 1 — `unlock-user` + `auth-admin` test gaps (1.5d)

**Why first:** Smallest blast radius, ships go-live unblock the first time someone trips the lock-counter. `auth-admin` test-gap closure piggybacks because both touch `users.ts` + `UsersApiControllerTest.java` + `users.test.ts` (NET-NEW for unlock; same file extended for resetPassword tests). One PR, one review cycle, one new test file.

Scope:
- POST `/api/v1/users/{username}/unlock` + `StudyUserDto.locked` wire-shape extension.
- New `users.test.ts` covering both `unlock()` (5 cases) + `resetPassword()` (4 cases).
- 4 MockMvc cases for unlock + 3 added negative branches for resetPassword.
- ManageUsersView per-row Unlock button + locked badge + i18n.

### PR 2 — `study-params` backend + minimal SPA (6d)

**Why second:** Unblocks every downstream view (`AddSubjectView`, `CrfEntryView`) that currently hard-codes defaults disagreeing with study config. The GET endpoint alone makes `AddSubjectView` field-visibility correct end-to-end; PUT lets DMs onboard new studies without bailing to legacy JSP.

Scope:
- `StudyParametersApiController` GET + PUT with 19 handles.
- `StudyParametersDto` + `UpdateStudyParametersRequest` + validation.
- `BuildStudyView` 'Parameters' tile + new `StudyParametersEditView`.
- AddSubjectView consumes store (no hard-coded fallbacks).
- 12 MockMvc + 9 store + 5 view + 3 AddSubject tests.

Defer CrfEntryView header-seed wiring to a follow-up PR if review queue pressure builds — it's additive and decoupled.

### PR 3 — `admin-rfc` end-to-end (4d)

**Why third:** GCP/Part 11 compliance binding (operational constraint #5: every edit-after-completion needs RFC). Cleanest concrete use case is single-CRF post-complete edits; downstream `bulk-import` and `dde` both reuse the RFC infrastructure. Landing this before `bulk-import` means the import-commit can write RFC notes via the same writer.

Scope:
- `ReasonForChangeWriter` service + DAO `findLatestRfcParentForItemData` method.
- `EventCrfsApiController` post-complete gate + `requiresReasonForChange` flag on `CrfEntryDto`.
- `DiscrepancyApiController` already accepts the type field after `discrepancy-full` (but `discrepancy-full` isn't shipped yet) — so this PR creates DNs via direct DAO call inside `ReasonForChangeWriter`, NOT through `DiscrepancyApiController.create()`. Decouples PR 3 from PR sequence on `discrepancy-full`.
- `ReasonForChangeModal.vue` + CrfEntry store extensions + view wiring.
- 4 + 4 + 3 tests; compose smoke.

### What's deferred and why

- **`bulk-import` (PR 4-5)**: largest backend cluster; split 7d preview-token + 7d commit-pipeline. Pin transaction strategy (a) vs (b) before starting.
- **`crf-data-types` (PR 6-7)**: split into JSON groups+select-multi PR and multipart file-upload PR. Multipart pipeline needs Spring config audit first.
- **`discrepancy-full` (PR 8) → `dde` (PR 9-10)**: must order this way; DDE depends on FAILEDVAL generalisation. Promote `writeAuditEvent` BEFORE PR 10.
- **Everything else**: shippable in parallel by a second engineer once PRs 1-3 establish the cluster patterns.

**Critical reminders for the implementer**:

1. Always use `git -C modernization` per autonomous Phase B authorization.
2. Avoid `git add -A` per same.
3. Operate in the `modernization/` worktree; `main/` and `phase-e/` worktrees are for parallel work.
4. Resolve each cluster's reviewer flags **before** writing code — most are 5-minute path/key fixes that compound badly if discovered mid-build.
5. CHANGELOG entry per PR; flag dropped self-service forgot-password explicitly in the PR that closes `auth-admin`.