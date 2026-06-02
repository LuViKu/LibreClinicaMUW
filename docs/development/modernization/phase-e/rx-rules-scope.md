# Rules surface — scope analysis & RX-series plan

**Status:** planning artifact · **Created:** 2026-06-02 · **Predecessor:** [`issue-draft-editor-adapter-gap.md`](./issue-draft-editor-adapter-gap.md) A-series (closed)

Runs after the A-series adapter-gap closure (PRs #57–77). The Rules engine was deferred from A8 with "L–XL effort comparable to A7 entirely"; this document is the concrete scope.

---

## TL;DR

The Rules surface is the **largest unscoped feature** remaining on the Phase E backlog.

- **9 persisted tables** (`rule`, `rule_set`, `rule_set_rule`, `rule_action`, `rule_expression`, `rule_action_property`, `rule_action_run`, `rule_action_run_log`, `rule_action_stratification_factor`) plus audit twins.
- **8 action subtypes** (Hibernate single-table inheritance on `rule_action.action_type`): DiscrepancyNote, Email, Show, Insert, Hide, Event, Notification, Randomize.
- **Custom recursive-descent expression parser** ([`OpenClinicaExpressionParser`](../../../../core/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/logic/expressionTree/OpenClinicaExpressionParser.java)) + [`ExpressionService`](../../../../core/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/service/rule/expression/ExpressionService.java) (~1700 LOC, 66 public methods).
- **5 distinct execution entry points** — data-entry save, CRF bulk run, ruleset bulk run, import-data validation, Quartz scheduled batch.
- **15 legacy servlets** + ~1200 LOC of rule JSPs.
- **A partial REST surface already exists**: [`RuleController`](../../../../web/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/controller/RuleController.java) at `/rule/studies/{oid}/{metadata,validateRule,validateAndSaveRule,validateAndTestRule}` speaks JAXB-XML (built for the external OpenClinica Designer desktop app). Not A-series shape, but reusable plumbing.

Honest sizing: full Rules CRUD parity is **A7 × 1.5–2**. Read-only viewer + import + lifecycle is **comparable to A7 alone** (~4–5 PRs).

**Two surprises that change strategy**:

1. **The legacy UI is import-only.** There's no in-app "create a single rule" form in current LibreClinica. The closest thing is `UpdateRuleSetRuleServlet` which only flips enable/disable. To edit a rule today: download XML → external editor → re-import. Per-rule CRUD in the SPA would be **adding new functionality**, not just modernizing existing UX.
2. **The "STUDY.STUDYSUBJECT.SUBJECTGROUPID" example sometimes cited as an expression pattern doesn't exist in this codebase.** The actual grammar is `[SED.][CRF.][GROUP.]ITEM` with optional `[N]` ordinals. Subject-group filtering happens at runner time, not in expressions.

---

## 1. Domain model + tables

### 1.1 Schema

Core tables live in [`core/src/main/resources/migration/2.5/changeLogCreateTables.xml`](../../../../core/src/main/resources/migration/2.5/changeLogCreateTables.xml) (changesets `-49` through `-55`); run-log tables in [`amethyst/2010-01-13-4575.xml`](../../../../core/src/main/resources/migration/amethyst/2010-01-13-4575.xml); stratification in [`3.9/2015-11-18-OC-6825.xml`](../../../../core/src/main/resources/migration/3.9/2015-11-18-OC-6825.xml).

| Table | Key columns | Role |
|---|---|---|
| `rule` | `rule_id`, `name`, `description`, `oc_oid`, `enabled`, `rule_expression_id`, `owner_id`, `status_id` | The reusable named expression-definition (e.g. `RUL_BP_HIGH`) |
| `rule_expression` | `rule_expression_id`, `value VARCHAR(1025)`, `context INT` | Textual expression body. `context=1` is `OC_RULES_V1` |
| `rule_set` | `rule_set_id`, `rule_expression_id`, `study_event_definition_id`, `crf_id`, `crf_version_id`, `study_id`, `run_time`, `run_schedule` | A *target* (the variable/event the rule attaches to) on a study |
| `rule_set_rule` | `rule_set_rule_id`, `rule_set_id`, `rule_id`, `status_id` | Many-to-many: which rules apply to this target |
| `rule_action` | `rule_action_id`, `rule_set_rule_id`, `action_type INT`, `expression_evaluates_to BOOL`, `message`, `email_to` | One action firing on a rule_set_rule. `action_type` is the JPA single-table discriminator |
| `rule_action_property` | `rule_action_id`, `oc_oid`, `value`, `reference` | Extra k/v config (destination property OIDs, value expressions) |
| `rule_action_run` | 5 phase booleans: `administrative_data_entry`, `initial_data_entry`, `double_data_entry`, `import_data_entry`, `batch` | The phase-gate ("when may this action fire?") |
| `rule_action_run_log` | `action_type`, `item_data_id`, `value VARCHAR(4000)`, `rule_oc_oid` | Append-only fire history. `Insert`/`Randomize` writes here; `viewExecutedRules.jsp` reads here |
| `rule_action_stratification_factor` | `rule_action_id`, `rule_expression_id` | Randomize-action only |
| `rule_set_audit` / `rule_set_rule_audit` | `*_id`, `date_updated`, `updater_id`, `status_id` | Audit twins for status transitions |

### 1.2 Beans / Hibernate entities

Two parallel hierarchies (the migration from legacy `EntityBean` to Hibernate was never finished):

- **Legacy `EntityBean`-style** under `bean/rule/*` — still used by some DAOs. Only ~7 of 8 action types ported here (no `EventActionBean`, no `RandomizeActionBean`).
- **Hibernate-annotated** under `domain/rule/*` — the surface we should adapt against. All 8 action subtypes are full `@Entity` classes with `@DiscriminatorValue`:

| Discriminator | Class | Special fields |
|---|---|---|
| 1 | `DiscrepancyNoteActionBean` | `message` |
| 2 | `EmailActionBean` | `message`, `to` |
| 3 | `ShowActionBean` | `message`, `destinationProperty` (OID), `runOnStatus` |
| 4 | `InsertActionBean` | list of `PropertyBean` (destination OID + value / value-expression) |
| 5 | `HideActionBean` | `message`, `destinationProperty`, `runOnStatus` |
| 6 | `EventActionBean` | target event OID, `EventPropertyBean` (STARTDATE only currently), `runOnStatus` for all 6 event states |
| 7 | `NotificationActionBean` | `to`, `subject`, `message` (supports `${participant}` tokens) |
| 8 | `RandomizeActionBean` | list of `PropertyBean` + list of `StratificationFactorBean` (each linked to a `rule_expression`) |

`RuleSetBean` is ~460 LOC, `RuleSetRuleBean` ~340 LOC — both fat with `@Transient` lazy-list initialisers needed by the legacy Struts form binding.

### 1.3 DAOs

Three layers stacked:

- **JDBC EntityBean DAOs** (`dao.rule.*`, `dao.rule.action.RuleActionDAO`): externally-configured SQL in `properties/*.xml`. This is what the **runners** use.
- **Hibernate `AbstractDomainDao`** (`dao.hibernate.{RuleSetDao, RuleSetRuleDao, RuleDao, RuleSetAuditDao, RuleSetRuleAuditDao, RuleActionPropertyDao, RuleActionRunLogDao}`): used by `RuleSetService` for list/filter/count and by `RuleController` import paths.
- **`ViewRuleAssignmentFilter` / `ViewRuleAssignmentSort`**: CriteriaCommand for the legacy `ViewRuleAssignmentDataServlet` grid.

Both DAO layers must be kept in sync if a rule is mutated — historical bug source.

### 1.4 Services

`service.rule.*`:

- **[`RuleSetService`](../../../../core/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/service/rule/RuleSetService.java)** — 59kB / 894 LOC, **42 public methods**. The workhorse. Implements `RuleSetServiceInterface` (32 method signatures). Save/replace/update + 4 `runRulesInBulk` overloads + `runRulesInDataEntry` + `runRulesInImportData` + a large family of `filterRuleSetsBy{StudyEventOrdinal, HiddenItems, StudySubject, Section, Group}`.
- **[`RulesPostImportContainerService`](../../../../core/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/service/rule/RulesPostImportContainerService.java)** — 48kB / 900 LOC. XML import validation. `validateRuleDefs` + `validateRuleSetDefs` + cycle-detection (`inValidateInfiniteLoop`) + per-action-type validators delegated to `validator/rule/*`.
- **`RuleService`** — small Spring-managed thin wrapper.
- **`RuleSetListenerService`, `StudyEventBeanListener`** — event-action triggers on study-event lifecycle transitions.
- **[`ExpressionService`](../../../../core/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/service/rule/expression/ExpressionService.java)** — 65kB / 1700 LOC / 66 public methods. See §2.

---

## 2. The expression language

### 2.1 Parser

[`OpenClinicaExpressionParser`](../../../../core/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/logic/expressionTree/OpenClinicaExpressionParser.java) is a hand-rolled recursive-descent parser (no ANTLR, no JavaCC). ~300 LOC. Grammar:

```
expression := term { ("or"|"and") term }*
term       := term2 { ("eq"|"ne"|"ct"|"gt"|"gte"|"lt"|"lte") term2 }*
term2      := term3 { ("+"|"-") term3 }*
term3      := factor { ("*"|"/") factor }*
factor     := NUMBER | DATE("yyyy-MM-dd") | "(" expression ")"
            | WORD (variable node, dispatched via OpenClinicaVariableNode/OpenClinicaBeanVariableNode)
            | DOUBLE_QUOTE_STRING | "-" factor
```

Public surface:
- `parseExpression(String)` — parse only, throw on syntax error
- `parseAndEvaluateExpression(String)` — parse + reduce to literal (constant-folded)
- `parseAndTestEvaluateExpression(String[, HashMap])` — parse + simulate with mock variable values (powers the legacy `TestRuleServlet` / `testRules.jsp`)
- `getTestValues()` / `setTestValues(HashMap<String,String>)`

Existing unit tests in `core/src/test/java/.../logic/expressionTree/OpenClinicaExpressionParserTest.java` — ~200 LOC covering arithmetic, date arithmetic, boolean composition, syntax errors. **Valuable**: confirms parser is parse-test-safe to expose directly to operators.

### 2.2 ExpressionService validation methods

| Method | Purpose |
|---|---|
| `ruleSetExpressionChecker(String)` (line 155) | Top-level "is this a valid rule_set target?" — `checkSyntax` + `isExpressionValid` |
| `ruleExpressionChecker(String)` (line 588) | Top-level "is this a valid expression body referenced by a rule?" |
| `checkSyntax(String)` (line 1301) | Regex-based shape check against `ITEM_DATA.ITEM_GROUP.CRF.SED` |
| `isExpressionValid(String)` (line 1157) | Resolves every OID segment against the DB. Throws `OCRERR_0022..0034` for malformed item/group/crf/event-def refs |
| `isExpressionValid(String, RuleSetBean, Integer allowedLength)` (line 568) | Rule-set scoped validation for import |
| `isInsertActionExpressionValid(...)` (line 528) | Checks specific to insert-action ValueExpression |
| `isRandomizeActionExpressionValid(...)` (line 548) | Randomize-action ValueExpression |

Error codes `OCRERR_0001` (parse), `OCRERR_0005`–`0034` (validation) — existing i18n resource keys, so REST adapter can surface them directly.

### 2.3 Identifier grammar (from `ExpressionService:67–80`)

- `[A-Z_0-9]+` — bare OID (CRF/CRFVersion/Item)
- `[A-Z_0-9]+\[(ALL|[1-9]\d*)\]` — OID with ordinal, used for repeating groups / repeating events
- `[A-Z_0-9]+\[(END|ALL|[1-9]\d*)\]` — same + `[END]` (last instance of a repeating SED, EventAction targets only)
- Composed: at most 4 dot-separated parts: `ITEM_DATA_OID.ITEM_GROUP_OID.CRF_OID.STUDY_EVENT_DEFINITION_OID`
- Special suffixes (line 87–89): `.STARTDATE` and `.STATUS` on a SED reference — EventAction targets only
- Special tokens: `_CURRENT_DATE` (constant resolved by `ConstantNode`); `${participant}`, `${participant.firstname}`, `${participant.loginurl}`, `${participate.url}`, `${study.name}`, `${participant.accessCode}`, `${event.name}` (notification-action token substitution, not part of expression grammar proper)

### 2.4 Sample rules

- [`core/src/main/resources/properties/rules_template.xml`](../../../../core/src/main/resources/properties/rules_template.xml) — canonical empty skeleton, served by `ImportRuleServlet?action=downloadtemplate`
- `rules_template_with_notes.xml` — fully-commented version with one example of each action type
- `rules.xsd` — the XML Schema (251 lines)
- `core/src/test/resources/.../service/rule/testdata/RulesPostImportContainerServiceTest.xml` — DbUnit import-validation fixture

---

## 3. Import / upload path

### 3.1 Legacy servlet flow

`ImportRuleServlet.java` (~215 LOC) at `/ImportRules`:

1. GET (no action) → `Page.IMPORT_RULES` (the upload form JSP)
2. `?action=downloadrulesxsd|downloadtemplate|downloadtemplateWithNotes` → file downloads from `core/.../properties/`
3. POST `?action=confirm` (multipart XML):
   - `FileUploadHelper.returnFiles(...)` → `${filePath}/rules/original/`
   - `XmlSchemaValidationHelper.validateAgainstSchema(file, rules.xsd)` — XSD validation
   - `OdmJaxbContext.unmarshalRulesImport(in)` — JAXB 2.3 → `RulesPostImportContainer` (post-B.3 migration; replaced Castor)
   - `RulesPostImportContainerService.validateRuleDefs(rpic)` — resolves each `RuleDef`'s expression against DB
   - `RulesPostImportContainerService.validateRuleSetDefs(rpic)` — resolves each `RuleAssignment`'s target + cycle-detection
   - Container parked in `session["importedData"]`, control passes to `VerifyImportedRuleServlet` / `verifyImportRule.jsp` ("review-then-commit")
4. From verify: `VerifyImportedRuleServlet?action=save` → `RuleSetService.saveImport(rpic)` writes valid + non-duplicate items, ignoring or replacing duplicates per operator choice

### 3.2 Persistence sequence

From `RuleSetService.saveImport` (line 157), for each valid `RuleSetBean`:
1. `RuleDAO.create` for each new `RuleBean` (writes `rule` + `rule_expression`)
2. `RuleSetDAO.create` for the target (writes `rule_set` + its `rule_expression`)
3. `RuleSetRuleDAO.create` for each `(rule_set, rule)` pairing
4. `RuleActionDAO.create` for each action attached to the `rule_set_rule` (writes `rule_action` + the discriminator-driven `rule_action_run` + any `rule_action_property` rows)
5. `RuleSetAuditDAO.create` + `RuleSetRuleAuditDAO.create` log the create event

### 3.3 Designer-style REST (already exists)

[`RuleController`](../../../../web/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/controller/RuleController.java) (`@RequestMapping("/rule")`):

- `GET /rule/studies/{oid}/metadata` — ODM + RuleDef XML download (legacy "OpenClinica Designer" used this)
- `POST /rule/studies/{oid}/connect` — auth handshake
- `POST /rule/studies/{oid}/validateRule` — body is `Rules` (JAXB-bound XML); returns a `Response` with `messages[]`
- `POST /rule/studies/{oid}/validateAndSaveRule?ignoreDuplicates={bool}` — same body, persists on success
- `POST /rule/studies/{oid}/validateAndTestRule` — body is `RulesTest` (JAXB), evaluates against mock values

**Important**: this is **not** A-series-shape. It speaks JAXB-XML, has a hand-rolled `mayProceed` role check, no `@RestController`, no `@PathVariable("oid")` convention consistency, no `SiteVisibilityFilter`. **It is a building block** (a JSON wrapper can delegate to the same `RulesPostImportContainerService`), but **it does not count as "the REST surface for rules already exists."**

---

## 4. Manual rule definition UI (legacy state of the art)

**Per-rule editor:** does **not** exist. The legacy UI is **import-only**. `CreateRuleSetServlet` / `EditRuleSetServlet` do not exist in this codebase (grep confirms). The closest things:
- `UpdateRuleSetRuleServlet` — handles only **status mutations** (remove/restore/enable/disable a `rule_set_rule`), not body edits
- The historical workflow for rule edits: download XML → external editor → re-import
- Plus the external "OpenClinica Designer" Java app speaking `/rule/studies/{oid}/validateAndSaveRule` JAXB

**Test / preview paths**:
- `TestRuleServlet` (`/TestRule`) → `testRules.jsp` (583 LOC). Operator picks a rule_set + rule, fills mock values, clicks Test → JSP renders boolean result + actions that *would* fire. Backed by `OpenClinicaExpressionParser.parseAndTestEvaluateExpression(expr, mockValues)`.
- `RunRuleSetServlet` (`/RunRuleSet`) — dry-run a whole rule_set against live data. Has both `dryRun=true` mode and an "actually fire it now" mode.
- `RunRuleServlet` (`/RunRule`) — same, single-rule scope.
- `viewExecutedRules.jsp` / `viewExecutedRulesFromCrf.jsp` — historical fire log (reads `rule_action_run_log`).

These three (Test, Dry-run, Executed-log) are the **highest-value surface** for a read-only first slice.

---

## 5. Rule execution

### 5.1 Triggers

5 distinct triggers, each with a dedicated `RuleRunner` subclass under `core/.../logic/rulerunner/`:

| Trigger | Runner | Invocation site |
|---|---|---|
| Form save during data entry | `DataEntryRuleRunner` | `DataEntryServlet.java:5285` — `getRuleSetService(request).runRulesInDataEntry(...)` after each item/section save |
| Bulk re-run a whole CRF | `CrfBulkRuleRunner` | `RunRuleServlet:65,68` |
| Bulk re-run a whole ruleset | `RuleSetBulkRuleRunner` | `RunRuleSetServlet:69,74` |
| Import-data validation | `ImportDataRuleRunner` + `ImportDataRuleRunnerContainer` | `VerifyImportedCRFDataServlet:344,354` + `ImportSpringJob:827,838` (Quartz nightly) |
| Scheduled batch | `RuleSetBulkRuleRunner` in `RUN_ON_SCHEDULE` mode | Quartz, hourly via `ReportController.runonschedule` health-check + `RunOnScheduleType` on each rule_set |

All-inline; no message queue. `RuleRunner.RuleRunnerMode` enumerates the five.

### 5.2 Entry point

`RuleSetService.runRulesInBulk(...)` (3 overloads), `runRulesInDataEntry(...)`, `runRulesInImportData(...)` — construct runner, gather ruleSets via filter chain, dispatch to runner's `runRules(...)`. Each runner builds `RuleActionContainer`s, dispatches to `ActionProcessorFacade` which switches on `ActionType` and invokes the matching `*ActionProcessor`.

### 5.3 Phase gating

- **`RuleActionRunBean.Phase`** = `{ADMIN_EDITING, INITIAL_DATA_ENTRY, DOUBLE_DATA_ENTRY, IMPORT, BATCH}`. Each `rule_action` carries 5 booleans deciding which phases it runs in. Runner checks `RuleActionRunBean.canRun(phase)` (line 225) before each action.
- **`studyEventPhase`** = `{NOT_STARTED, SCHEDULED, DATA_ENTRY_STARTED, COMPLETE, SKIPPED, STOPPED}` — secondary gate used only by `EventAction` and `Show/HideAction.runOnStatus`.

---

## 6. Action types — per-type complexity

| Action | Bean LOC | Fields beyond base | Read display | CRUD effort |
|---|---|---|---|---|
| `DiscrepancyNoteAction` | 82 | `message` only | trivial | **XS** — message + phase booleans |
| `EmailAction` | 101 | `message`, `to` | trivial | **S** — message + comma-separated emails + phase booleans |
| `ShowAction` | 146 | `message`, destination OID, `runOnStatus` | needs target-item resolver | **M** — operator picks destination Item/Group OID from same CRF |
| `HideAction` | 128 | mirror of Show | same | **M** |
| `InsertAction` | 107 + 135 (PropertyBean) + 97 (Processor) | list of `PropertyBean` (each: destination OID + value *or* valueExpression) | needs ValueExpression validator | **L** — value-expression is a sub-expression that must itself validate against the same scope. Multi-row editor needed. |
| `EventAction` | 145 + 59 (EventPropertyBean) + 34 (Processor) | target event OID, `runOnStatus` for 6 states, `EventPropertyBean` (STARTDATE only) with ValueExpression | needs SED resolver | **L** — only supports STARTDATE; UI is "schedule event Y when event X's date changes" |
| `NotificationAction` | 119 + 339 (Processor) | `to`, `subject`, `message`, token substitution | needs token catalog | **L** — `${participant}`, SMS hook, email template; high test burden (no SMS adapter today) |
| `RandomizeAction` | 144 + 121 (StratificationFactor) + 135 (PropertyBean) | list of `PropertyBean`, list of `StratificationFactorBean` (each linked to another `rule_expression`) | needs randomization service callout | **XL** — couples `logic.expressionTree` + randomization service callout. Out of scope for A-series adapter. |

---

## 7. SPA UX considerations

### 7.1 Free-form vs guided

The legacy XML format is already a textual DSL. Operators who today wield `rules.xml` think in text. **Recommendation: text-area + live-validate** for first slice. Most value with least surface — expose `validateRule` endpoint live as the user types (debounced).

**Monaco** is available in `node_modules` (peer of some packages) but **not wired into Vue**. Cheapest path is plain `<textarea>` + `vue-monaco-editor` / `monaco-editor-vue3` as a follow-up. Don't bundle in first slice.

A "guided builder" (visual composer: SED → CRF → Group → Item, then operator → constant) covers ~70% of real MUW use cases (range checks, required fields, simple cross-form). That's a separate slice — RX.8 below.

### 7.2 Minimum-viable surface for MUW

Based on common clinical-trial rule patterns:
- **Range check** → `DiscrepancyNoteAction` triggered by `ITEM gt X or ITEM lt Y`
- **Required field** → `DiscrepancyNoteAction` triggered by `ITEM eq ""`
- **Cross-form constraint** → `DiscrepancyNoteAction` triggered by `CRF1.ITEM eq "value" and CRF2.ITEM gt 0`
- **Conditional show/hide** → `ShowAction` / `HideAction` pair

That's **3 of 8 action types** (`DiscrepancyNote`, `Show`, `Hide`) covering >80% of typical use cases. `Email` is easy to bolt on (XS). `Insert` / `Event` / `Notification` / `Randomize` should be **out of MVP scope** for the first authoring pass.

### 7.3 Reusable SPA patterns

Four patterns from the merged A-series:
- **List/edit list-page** → [`CrfLibraryView.vue`](../../../../web/src/spa/src/views/CrfLibraryView.vue) (328 LOC) — store-backed list + inline create form + per-row detail panel + role-gated buttons
- **Per-row edit-in-dialog** → [`EventDefinitionsView.vue`](../../../../web/src/spa/src/views/EventDefinitionsView.vue) — per-row edit dialog + reorder pattern
- **Authorization** → [`StudyAdminAuthorization.java`](../../../../web/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/controller/api/StudyAdminAuthorization.java) — maps `currentRole.role` → boolean predicates
- **REST shape** → [`CrfsApiController.java`](../../../../web/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/controller/api/CrfsApiController.java) — 29kB, multipart upload, `/api/v1/crfs` + `/api/v1/crfs/{oid}/versions`. The rules controller should be `/api/v1/rules` + `/api/v1/rule-sets` + `/api/v1/rule-sets/{id}/actions`.

---

## Proposed sub-slices

Sequenced from "must ship" to "would be nice." Each slice ≈ one A-series PR.

### RX.1 — Read-only rules surface (M, ~A8.4 sized)

**Why first**: today an operator inheriting a study with rules has **no way** to find out what's running without raw SQL or downloading the XML. Highest value per LOC.

**Endpoints**:
- `GET /api/v1/rule-sets` — list, paginated, filterable by study/event-def/crf/status. Wraps `RuleSetService.getWithFilterAndSort(...)`.
- `GET /api/v1/rule-sets/{id}` — full detail: target, expression, rules attached, all actions with per-type fields.
- `GET /api/v1/rule-sets/{id}/run-log` — pages over `rule_action_run_log`, joined with `rule_action` for context.

**SPA**: `RulesView.vue` — grid + detail panel, copied from `CrfLibraryView.vue`.

**Plumbing**:
- Build-study tile gets deep link (`/rules`).
- Role gate: any authenticated user (read-only).

**No mutations.** Status badge only. No re-run, no test, no edit.

**Defers**: legacy import UI, test screen, all CRUD.

### RX.2 — XML import adapter (M, ~A7.4 sized)

**Endpoints**:
- `POST /api/v1/rules/import` — multipart XML upload, returns `RulesImportPreviewDto` (valid count + duplicate count + invalid count + per-record errors). Wraps `ImportRuleServlet` validation flow **without persisting**.
- `POST /api/v1/rules/import/commit` — operator confirms; body is a previous preview token + `ignoreDuplicates` flag. Persists via `RulesPostImportContainerService` + `RuleSetService.saveImport`.
- `GET /api/v1/rules/template` — serves `rules_template_with_notes.xml`. Same for `/api/v1/rules/template.xsd`.

**SPA**: extends `RulesView.vue` with an upload button → modal showing preview → Apply button.

**Authorization**: study director + coordinator + sysadmin.

**Defers**: in-app rule authoring, expression editor, rule test.

### RX.3 — Rule test / dry-run (S)

**Endpoints**:
- `POST /api/v1/rules/test-expression` — body `{expression: string, testValues: {[var]: string}}`, returns `{result: string, evaluatedAt: ts}` or 400 + error code. Wraps `OpenClinicaExpressionParser.parseAndTestEvaluateExpression`.
- `POST /api/v1/rule-sets/{id}/dry-run` — re-evaluates current ruleset against live data, returns `RuleSetBasedViewContainer`-equivalent DTO without persisting any action effect. Wraps `RuleSetService.runRulesInBulk(ruleSets, true, ...)`.

**SPA**: "Test rule" pane on the detail panel + "Dry-run" button on the grid row.

**High value for trial monitors** auditing existing rules.

### RX.4 — Lifecycle mutations (S, ~A7.3 sized)

**Endpoints**:
- `POST /api/v1/rule-sets/{id}/disable` / `restore`
- `POST /api/v1/rule-sets/{id}/rules/{ruleId}/disable` / `restore`
- `DELETE /api/v1/rule-sets/{id}` — soft-delete (mirrors `RemoveRuleSetServlet`)

**SPA**: row-level buttons. Follows A8.3 disable/restore pattern exactly.

### RX.5 — Single-rule inline create (L) — **DEFERRED**

**The first XL-risk slice.** Inline rule create depends on a free-form expression editor, which is genuinely non-trivial UX.

**Endpoints**:
- `POST /api/v1/rules` — body `{oid, name, description, expression}` + study scope. Validates expression against the study scope. Wraps the chunk of `RulesPostImportContainerService` that handles a single `RuleDef`.
- `POST /api/v1/rule-sets` — body `{target, studyEventDefOid, crfOid, crfVersionOid?, ruleOids[]}`. Creates a `rule_set` + N `rule_set_rule` links.
- `POST /api/v1/rule-sets/{id}/actions` — body `{actionType, expressionEvaluatesTo, message?, runOnPhase{}, ...type-specific...}`.

**Scope-limited to** `DiscrepancyNote`, `Email`, `Show`, `Hide` action types (per §7.2). Other types stay XML-import-only.

**SPA**: "Create rule" form in the detail panel with:
- A free-form `<textarea>` for the expression body (Monaco can come later; plain textarea is a valid v1).
- A **"Validate" button** that calls `/api/v1/rules/test-expression` (syntax) **and** a new `/api/v1/rules/validate-target` (OID resolution against the study scope).
- A target picker: SED → CRF → Group → Item dropdowns, all populated from existing GET endpoints. Picking these fills in the expression's OID prefix.
- An action sub-form per supported type (DiscrepancyNote / Email / Show / Hide), with phase-gate checkboxes.

**UX risks worth flagging**:
- Expression validation has two failure modes (syntax vs. OID resolution). The UI needs to surface both clearly.
- Without Monaco, operators won't get autocompletion — they have to type OIDs from memory or copy from the dropdown. Acceptable for v1.
- Show/Hide actions need the operator to pick a destination Item/Group OID from the same CRF as the target — that's a separate picker.

**Defers**: Insert + Event + Notification + Randomize actions, Monaco editor (RX.8), bulk-edit, action-type morph (operator should delete + recreate).

**Effort estimate justification**: this is the slice where the SPA carries significant new code that doesn't have an existing A-series template. Reasonable budget: ~2 weeks. Splitting into RX.5a (single-rule body create, no actions yet) + RX.5b (action attachment + phase gates) is plausible but tightens the diff per PR.

### RX.6 — Per-rule edit (M) — **DEFERRED, depends on RX.5**

**Endpoints**:
- `PUT /api/v1/rules/{id}` — update name / description / expression. Re-runs validation; warns if expression change orphans existing rule_sets.
- `PUT /api/v1/rule-sets/{id}/actions/{actionId}` — edit message / phase gates / destination property.

**SPA**: edit dialog inside the detail panel, reusing the create-form components from RX.5.

**Defers**:
- **Action-type morph** (insert → randomize etc.). Operator should delete + recreate. Cleaner audit, simpler validation.
- **Re-validate-on-import-change**: if a CRF version is uploaded that drops an OID a rule references, this slice doesn't surface the orphan. That's a separate "rule health" follow-up.

**Why M-effort vs L for RX.5**: most of the heavy SPA work — the expression editor, the target picker, the action sub-forms — is already shipped in RX.5. The edit path reuses them; the only new work is PATCH-style update endpoints + a "you'd be orphaning X existing rule_sets" preflight.

**Order constraint**: must ship after RX.5. Shipping it first would require building all the editor scaffolding without an operator-visible "create" entry point, which is wasted work.

### RX.7 — Run-on-schedule management (S)

**Endpoints**:
- `PUT /api/v1/rule-sets/{id}/schedule` — `{runSchedule: boolean, runTime: "HH:00"}`.

**SPA**: surfaces the existing `RunOnScheduleType` field that's persisted but currently has no UI.

### RX.8 — Advanced actions + visual builder (XL) — **DEFERRED**

Three independent capabilities, all genuinely large. Should be split into its own multi-PR phase entirely.

**RX.8a — Insert + Event action CRUD (L)**:
- `Insert` requires a ValueExpression validator that runs in addition to the target expression — value-expressions can reference other items in the same CRF and must validate against the target's scope.
- `Event` requires a SED resolver + the operator to pick an event from the study and a target property (currently only `STARTDATE` is supported by `EventPropertyBean`; the bean's structure suggests extension is anticipated).
- Multi-row editor for `Insert.PropertyBean` list.
- New endpoints: extends `POST /api/v1/rule-sets/{id}/actions` to accept the additional action discriminators with their fields.

**RX.8b — Notification action + SMS adapter (L)**:
- `Notification` supports `${participant}` / `${participant.firstname}` / `${participant.loginurl}` / `${participate.url}` / `${study.name}` / `${participant.accessCode}` / `${event.name}` token substitution.
- Currently sends via the same Spring `JavaMailSenderImpl` as `EmailAction`; SMS hooks exist in the bean but are unwired.
- Adding SMS would require a new adapter (Twilio / OVH SMS / etc.) — out of scope as a free-standing slice, would warrant its own design discussion.
- The token catalog needs UI: the SPA should show available tokens as chips with click-to-insert.

**RX.8c — Randomize action CRUD (XL)**:
- The trickiest action by far. Combines:
  - List of `PropertyBean` for the destination items (which item gets the assigned arm value)
  - List of `StratificationFactorBean`, each linked to its own `rule_expression` — stratification factors are themselves expressions that must validate
  - Connection to a randomization service (currently the legacy `RandomizationServiceImpl`; might warrant rework)
- High test burden — randomization correctness is GCP-critical.
- Likely needs its own scope analysis + design review session.

**RX.8d — Visual rule composer (XL)**:
- Replace the free-form expression editor (from RX.5) with a blockly / drag-and-drop / dropdown-driven composer.
- Covers ~70% of typical MUW use cases (range checks, required fields, simple cross-form, conditional show/hide) without requiring operators to know the expression DSL syntax.
- Significant SPA investment: ~3+ weeks of focused work. Almost a sub-project on its own.
- Likely a separate phase entirely (RB-series — "rules builder") rather than a tail-end RX slice.

**Effort estimate justification**: RX.8 as a whole is XL because it's actually four independent L-XL slices bundled. None of them are technically blocking the others, but they all share the "advanced action types" theme. Sequencing should be by operator demand: if MUW needs Notification first, ship RX.8b first.

---

## Recommendation

**Start with RX.1 (read-only viewer + run-log) and RX.3 (test/dry-run).** These two together give operators ~70% of what they need for rules visibility and validation, ship in a single PR pair (~1 week each), and use only `GET` + `POST` (test) endpoints — minimal write-path complexity.

**Defer RX.2 (XML import adapter)** to the second slice. The legacy `/ImportRules` servlet keeps working in the meantime, so this is not a regression — just a UX uplift to the SPA.

**Hard line: do not attempt RX.5 / RX.6 / RX.8 in the same envelope as RX.1–RX.4 + RX.7.** Inline rule authoring requires either Monaco wiring or a visual builder, both of which are 1-week+ investments on their own. The XML-import path is the existing escape hatch; the SPA can link to it from RX.1.

### Sequencing summary

| Slice | Effort | When | What it ships |
|---|:---:|---|---|
| **RX.1** | M | A9.1 | Read-only rule grid + detail + run-log |
| **RX.3** | S | A9.2 | `/test-expression` endpoint + "Test rule" pane |
| **RX.4** | S | A9.3 | Disable / restore / delete buttons |
| **RX.2** | M | A9.4 | XML import + preview UI |
| **RX.7** | S | A9.5 | Schedule field edit |
| **RX.5** | L | future (A9.6 or RB.1) | Inline create (only `DiscrepancyNote` / `Email` / `Show` / `Hide`) |
| **RX.6** | M | future (A9.7 or RB.2; depends on RX.5) | Per-rule edit |
| **RX.8a** | L | future RB-series | Insert + Event action CRUD |
| **RX.8b** | L | future RB-series | Notification action + SMS adapter |
| **RX.8c** | XL | future RB-series | Randomize action CRUD |
| **RX.8d** | XL | future RB-series | Visual rule composer |

**Total A-series envelope (RX.1–RX.4 + RX.7)**: ~4–5 PRs, comparable to A7.
**Total full envelope (RX.1–RX.8)**: ~12+ PRs, comparable to A7 × 2.

---

## Explicitly out of scope

To prevent scope creep:

- **Rules-engine performance tuning** — no caching layer changes, no Hibernate L2 cache rework, no batch-runner threading. Existing inline execution stays.
- **Rule-action audit replay** — no "replay this action against new data" capability. Run-log stays append-only and read-only.
- **Visual rule composer / blockly-style builder** — text expression only in RX.5; the builder is RX.8d.
- **Randomization service rework** — `RandomizeAction` continues to flow through the legacy import path only (RX.8c if/when).
- **OpenClinica Designer compatibility** — keep the existing `/rule/studies/{oid}/validate*` JAXB endpoints working, do not improve or extend. New SPA-facing endpoints are JSON under `/api/v1/`.
- **SMS / notification provider integration** — `NotificationAction` continues to send via existing `JavaMailSenderImpl`; no Twilio/SES/etc. adapter until RX.8b.
- **Cross-study rule library** — rules remain study-scoped (`rule.study_id` model preserved).
- **Versioning of rules** — no "rule v1.1 / v2.0" concept; edits replace the previous body, audited via `rule_set_audit` + `rule_set_rule_audit`.
- **Expression auto-complete** — operators get raw text + validate-on-blur. Monaco / IntelliSense is RX.8d (subset).
- **`STUDY.STUDYSUBJECT.SUBJECTGROUPID` expression syntax** — does not exist in this codebase's grammar. If MUW needs it, it's a parser change (large) and **out of any RX slice**.

---

## Load-bearing files for implementation

If proceeding with RX.1–RX.4 + RX.7 (recommended A9.x envelope), the files an implementer must understand first:

- [`RuleSetService.java`](../../../../core/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/service/rule/RuleSetService.java) — the workhorse. `getWithFilterAndSort`, `runRulesInBulk(dryRun)`, `saveImport`, `updateRuleSet` are the four methods every RX slice ends up calling.
- [`ExpressionService.java`](../../../../core/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/service/rule/expression/ExpressionService.java) — 66-method API for syntax + semantic validation. RX.3 wraps `ruleSetExpressionChecker` + the parser.
- [`RulesPostImportContainerService.java`](../../../../core/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/service/rule/RulesPostImportContainerService.java) — RX.2's persistence pipeline. Validate-then-commit two-step is exactly what the SPA preview/apply UX needs.
- [`CrfsApiController.java`](../../../../web/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/controller/api/CrfsApiController.java) — **shape reference** for every new endpoint (path naming, multipart upload, role check, `@Schema` annotations, error envelope). RX.1's `RulesApiController` should be a near-mechanical analogue.
- [`CrfLibraryView.vue`](../../../../web/src/spa/src/views/CrfLibraryView.vue) — **SPA shape reference** for the rules view. Pinia store + list + detail panel + role-gated buttons. RX.1's `RulesView.vue` should match 1:1.
