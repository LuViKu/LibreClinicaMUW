# Spring Boot 4 / Hibernate 7: `save` / `saveOrUpdate` call-site review

Stage 1 of the Spring Boot 4.1 migration ([DR-037](decision-record.md#dr-037--two-support-windows-set-the-order-of-platform-upgrades-postgresql-17-now-spring-boot-4-next), [spike report](spring-boot-4-spike-2026-09-30.md)). Hibernate 7 removed `Session.save` and `Session.saveOrUpdate`. This page records what replaced them and the review of every caller.

## What replaced them

`AbstractDomainDao.saveOrUpdate` and `CompositeIdAbstractDomainDao.saveOrUpdate` call `SessionSaveSupport.saveOrUpdate` (core `dao.hibernate`):

| State of the argument | Operation | What the caller gets |
|---|---|---|
| already in the session | none | the same instance; changes flush with the transaction |
| transient (Hibernate's own rule, `ForeignKeys.isTransient`: unsaved id value, version, or a row lookup for assigned ids; the rule `saveOrUpdate` used) | `persist` | the same instance, now managed, with its generated id |
| detached | `merge` | a different, managed instance; **the argument stays detached** |

`AbstractDomainDao.save` was already `persist` plus `getIdentifier` (gate branch) and has no caller outside core integration tests.

What changes for a caller is only the detached case. After it, the argument has no new id or version, and a change made to it is not written. A caller is a problem when it (a) goes on using the argument where it needs the managed copy, or (b) saves a graph in which a **new** parent cascades to a **detached** child (persist rejects that with "Detached entity passed to persist"; `saveOrUpdate` re-attached it).

## Findings

**One real defect, fixed:** `RuleSetAuditBean.ruleSetBean` and `RuleSetRuleAuditBean.ruleSetRuleBean` carried `cascade = ALL`. Every caller builds a new audit row that points at a rule set (rule set rule) that already exists and, whenever the DAO call before it had its own transaction, is detached. Under Hibernate 7 that audit save throws. The cascade was also wrong in intent (deleting an audit row would have cascaded REMOVE into the rule set). Both associations now have no cascade. `SaveOrUpdateSemanticsDatabaseIT` stores both audit rows against a detached subject; with the cascade restored the two tests fail with `PersistentObjectException: Detached entity passed to persist`. Affected callers: rows 36, 39 and 41 below (the audit saves), and any audit written against a detached rule set such as row 49.

**No other caller needs a change.** Every other site is one of:

- *new*: a fresh bean, nothing reads the argument's id afterwards or does so on the same instance (persist keeps the instance);
- *detached, fire-and-forget*: a bean loaded in an earlier DAO transaction, modified, saved; the argument is not used afterwards, or the caller already uses the returned value or re-reads by id;
- *managed*: inside a service-level transaction.

## Call sites

Paths are relative to `at/ac/meduniwien/ophthalmology/libreclinica/` under `core/src/main/java` (core) or `web/src/main/java` (web). "Arg" is the state of the argument at the call.

| # | file:line | Arg | Uses the argument afterwards? | Verdict |
|---|---|---|---|---|
| 1 | core `dao/hibernate/OpenClinicaVersionDAO.java:49` | new | no | OK |
| 2 | core `dao/hibernate/UsageStatsServiceDAO.java:66` | new | no | OK |
| 3 | core `dao/hibernate/UsageStatsServiceDAO.java:77` | new | no | OK |
| 4 | core `dao/hibernate/PasswordRequirementsDao.java:156` | detached (`ConfigurationBean` from `findByKey`) | no | OK (merge writes the changed value) |
| 5 | core `dao/hibernate/PasswordRequirementsDao.java:162` | detached | no | OK |
| 6 | core `dao/hibernate/StudyEventDao.java:84` (`saveOrUpdate(container)`) | new or detached `StudyEvent` | **yes**: the same event goes into `OnStudyEventUpdated` for `RuleSetListenerService` | OK: the event carries every value set before the call (id included when new, as persist keeps the instance); a detached event is as detached as it was after `saveOrUpdate`'s own closed session. Listeners read fields, they do not save it |
| 7 | core `dao/hibernate/StudyEventDao.java:90` (`saveOrUpdateTransactional`) | as 6 | as 6 | OK |
| 8 | core `service/crfdata/BeanPropertyService.java:197` | new or detached `StudyEvent` | via 6/7 | OK |
| 9 | core `domain/rule/action/DiscrepancyNoteActionProcessor.java:58` | new `RuleActionRunLogBean` | no | OK |
| 10 | core `domain/rule/action/EmailActionProcessor.java:70` | new | no | OK |
| 11 | core `domain/rule/action/InsertActionProcessor.java:94` | new | no | OK |
| 12 | core `service/audit/LoginAuditService.java:112` | new `AuditUserLoginBean` | no (only logs fields on failure) | OK |
| 13 | core `service/crfdata/DynamicsMetadataService.java:83` | new | no | OK |
| 14 | `…DynamicsMetadataService.java:199` | new | no | OK |
| 15 | `…DynamicsMetadataService.java:210` | new | no | OK |
| 16 | `…DynamicsMetadataService.java:220` (`hideItem`) | detached (from `findByItemDataBean`) or new | no | OK |
| 17 | `…DynamicsMetadataService.java:230` | new | no | OK |
| 18 | `…DynamicsMetadataService.java:239` | new | no | OK |
| 19 | `…DynamicsMetadataService.java:260` | detached | no | OK |
| 20 | `…DynamicsMetadataService.java:280` | detached | no | OK |
| 21 | `…DynamicsMetadataService.java:305` | detached | no | OK |
| 22 | `…DynamicsMetadataService.java:690` | detached | no | OK |
| 23 | `…DynamicsMetadataService.java:713` | detached | no | OK |
| 24 | `…DynamicsMetadataService.java:716` | detached | no | OK |
| 25 | `…DynamicsMetadataService.java:792` | detached | no | OK |
| 26 | `…DynamicsMetadataService.java:799` | detached | no | OK |
| 27 | `…DynamicsMetadataService.java:821` | detached | no | OK |
| 28 | `…DynamicsMetadataService.java:824` | detached | no | OK |
| 29 | core `service/managestudy/EventDefinitionCrfTagService.java:81` | detached | no | OK |
| 30 | core `service/managestudy/EventDefinitionCrfTagService.java:95` | new | no | OK |
| 31 | core `service/rule/RuleSetService.java:121` (`saveRuleSet`) | new or detached `RuleSetBean` graph | callers use the return value (`updateRuleSet`) or ignore it (`saveImport*`, which never read ids back) | OK. A new rule set cascades PERSIST to new rule set rules and actions; the rule they point at is a plain reference, not cascaded |
| 32 | core `service/rule/RuleSetService.java:138`, `:142` (`saveImportFromDesigner`) | new / detached `RuleBean` | **yes**: `ruleBeans.put(r.getOid(), r)` uses the returned `r` | OK (already written for the return value) |
| 33 | core `service/rule/RuleSetService.java:163`, `:166` (`saveImport`) | new / detached `RuleBean` | no | OK |
| 34 | core `service/rule/RuleSetService.java:182` | detached `RuleBean` | no | OK |
| 35 | core `service/rule/RuleSetService.java:183` | detached `RuleSetBean` | no | OK |
| 36 | core `service/rule/RuleSetService.java:200` (audit in `updateRuleSet`) | new `RuleSetAuditBean` pointing at the saved rule set (detached when the service has no transaction) | audit's `ruleSetBean` | **Fixed**: audit cascade removed (see Findings) |
| 37 | core `service/rule/RuleSetService.java:257` (`replaceRuleSet`) | detached graph with new rule set rules | return value is returned to the caller | OK (merge cascades into the new rows) |
| 38 | web `control/submit/RemoveRuleSetServlet.java:85` | detached `RuleSetRuleBean` | **yes**: `ruleSetRuleBean = saveOrUpdate(...)` reassigns, then audits | OK (uses the returned copy) |
| 39 | web `control/submit/RemoveRuleSetServlet.java:99` | new audit pointing at that copy (detached after the DAO transaction) | audit's `ruleSetRuleBean` | **Fixed**: audit cascade removed |
| 40 | web `control/submit/UpdateRuleSetRuleServlet.java:124` | detached | **yes**: reassigns, then audits | OK |
| 41 | web `control/submit/UpdateRuleSetRuleServlet.java:134` | new audit | as 39 | **Fixed** |
| 42 | web `control/admin/ConfigureServlet.java:82`, `:83` | detached `ConfigurationBean` x2 | no | OK |
| 43 | web `control/admin/CreateUserAccountServlet.java:285` | new `AuthoritiesBean` (assigned id: Hibernate looks the row up) | no | OK |
| 44 | web `controller/UserAccountController.java:278` | as 43 | no | OK |
| 45 | web `controller/api/UsersApiController.java:481` | as 43 | no | OK |
| 46 | web `controller/api/AdminApiController.java:380` | new or detached `ConfigurationBean` | no | OK |
| 47 | web `controller/api/RuleExpressionApiController.java:506` (create rule) | new `RuleBean` with new expression | **yes**: `persisted` is used (id, oid) | OK (persist returns the same instance) |
| 48 | web `controller/api/RuleExpressionApiController.java:783` (update rule) | detached `RuleBean`, possibly with a new `ExpressionBean` | **yes**: `fresh = persisted` is used for audit and log | OK (already uses the returned copy; the new expression's id would be null on the argument) |
| 49 | web `controller/api/RulesApiController.java:522` (run schedule) | detached `RuleSetBean` | **yes**: `writeRuleSetFieldAudit(…, rs, …)` | OK: the audit helper writes by id; any audit bean pointing at `rs` is safe since the cascade fix |
| 50 | web `controller/api/RulesApiController.java:977` (edit action) | detached `RuleSetRuleBean` holding the edited action | no (re-reads) | OK (merge cascades the edit into the action) |
| 51 | web `controller/api/RulesApiController.java:982` | detached `RuleSetBean` | no | OK |
| 52 | web `controller/api/RulesApiController.java:1302` (create rule set) | new `RuleSetBean` with new rule set rules | **yes**: `persisted.getId()`, audit, log | OK (persist keeps the instance; rules reference detached `RuleBean`s without cascade). It then re-reads by id for the cascade-created rule set rule ids |
| 53 | web `controller/api/RulesApiController.java:1606` (add action) | detached `RuleSetRuleBean` with a **new** action added | the new `action` instance gets no id under merge | OK: the controller re-reads the rule set to find the action id (`newActionId`) rather than using `action.getId()` |
| 54 | web `controller/api/RulesApiController.java:1711` (rule set lifecycle) | detached `RuleSetBean` | **yes**: `toDto(rs)` | OK (DTO built from the argument's own values, which are what was saved) |
| 55 | web `controller/api/RulesApiController.java:1770` (attached rule lifecycle) | detached `RuleSetRuleBean` | **yes**: audit, then re-reads the parent | OK |
| 56 | web `controller/StudyModuleController.java:317` | detached `StudyModuleStatus` (session model attribute, versioned) | no (`status.setComplete()`) | OK (merge applies the same version check `update` did) |
| 57 | web `web/filter/OpenClinicaSecurityContextLogoutHandler.java:103` | new `AuditUserLoginBean` | no | OK |
| 58 | web `web/filter/OpenClinicaSessionRegistryImpl.java:66` | new | no | OK |
| 59 | web `web/filter/OpenClinicaUsernamePasswordAuthenticationFilter.java:267` | new | no | OK |
| 60 | web `controller/BatchCRFMigrationController.java:354`, `:369`, `:383` (direct `Session`) | detached (loaded by DAOs in closed sessions) | no | OK: `session.merge` applies each to the run's own session; nothing reads the entities afterwards. Documented in the method |

Rows 6 to 8 matter only for event listeners, which read the event; none of them saves it.

Summary: 60 rows covering the 65 call expressions left in the tree (the spike counted 97 before the gate branch and the legacy removals trimmed it). **2 mapping lines changed** (the two audit cascades) and **0 caller changes**. Callers that already used the returned instance: rows 32, 38, 40, 47, 48, 53.

## Not reachable from a DAO call, still different on Hibernate 7

A managed parent that gains a **detached** child in a cascaded collection inside one transaction no longer re-attaches the child at flush (`persist` on flush rejects it). No call site above does this: the graphs that reach `saveOrUpdate` are either new, wholly detached (merge handles both) or wholly managed.

## Tests

- `SaveOrUpdateSemanticsDatabaseIT` (web, Testcontainers): persist keeps the instance and assigns the id; merge updates the row and returns another instance; a later change to the detached argument is not written; a managed entity is returned as it is; a composite-id entity inserts once and saving an equal id again is harmless; the two audit rows against detached subjects.
- `AbstractDomainDaoSaveIT` (core, from the gate branch): the persist-based `save`, including the `DataMapDomainObject` entities whose `getId()` is null.
