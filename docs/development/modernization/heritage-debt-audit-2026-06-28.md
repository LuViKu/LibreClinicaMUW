# Heritage debt audit — 2026-06-28

Audit of LibreClinicaMUW heritage warnings + author-left FIXMEs/TODOs/HACKs against
the 1.5.0-beta.4-muw release (`lc-develop` @ `06509d24d`). Conducted ahead of Phase
B.5 (Hibernate 6 migration) so the cliff can be planned against a clean baseline.

## Totals at a glance

| Surface | Count |
|---|---|
| `mvn compile` warnings | **203** (zero errors) |
| `@SuppressWarnings` hiding additional surface | **241** |
| `@Deprecated` markers still actively called | **26** |
| Heritage `FIXME` / `TODO` / hardcoded admission comments | **17** substantive |
| Debug-print sites (`println` + `printStackTrace`) | **168** (12 active `println`, ~90 `printStackTrace`, ~55 commented) |

**Key insight.** The 98 `@SuppressWarnings("deprecation")` on the Hibernate 5.6 DAOs
are *masking* Phase B.5's real surface — that's why `mvn compile` reports zero
Hibernate deprecation warnings today. Removing those suppressions first turns the
"unknown cliff size" into a concrete punch list.

---

## P0 — Clinical-data correctness risks (act before next release)

These risk losing or silently corrupting clinical data:

| # | Site | What | Fix |
|---|------|------|-----|
| 1 | [NewCRFBean.java:225-289](../../../core/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/bean/admin/NewCRFBean.java#L225) | 7× `printStackTrace()` in CRF XML parse `catch` blocks — corrupt CRF uploads continue silently | Replace with `LOG.error("CRF parse failed", e)` + throw a validation exception so the UI surfaces it |
| 2 | [ScoreUtil.java:95-442](../../../core/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/logic/score/ScoreUtil.java#L95) | 9× `printStackTrace()` in clinical-score calculation paths | Same — log + propagate; an unscored CRF must not look the same as a scored one |
| 3 | [DownloadDiscrepancyNote.java:86-138](../../../core/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/bean/extract/DownloadDiscrepancyNote.java#L86) | 8× `printStackTrace()` during regulatory discrepancy-note export | SLF4J + surface failure to the operator; this output is regulator-facing |
| 4 | [ExtractBean.java:1800](../../../core/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/bean/extract/ExtractBean.java#L1800) | Bare `catch (NullPointerException e)` with silent recovery — downstream code then NPEs on line 1808 | Identify the null reference, add an explicit guard, fail loudly |
| 5 | [OdmExtractDAO.java:1120,1128,1743,1751](../../../core/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/dao/extract/OdmExtractDAO.java#L1120) | Response-set parse failures return null/empty without logging — option-code mappings silently lost in ODM export | Log WARN with the offending row id; consider failing the export |
| 6 | [RuleExecutionBusinessObject.java:64](../../../core/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/bean/rule/RuleExecutionBusinessObject.java#L64) | `// TODO KK FIX HERE` then `sourceResult = true; targetResult = true;` — rule engine bypassed entirely | Either complete the rule eval or document the dead-code path; today rules effectively never fire |
| 7 | [EntityDAO.java:248](../../../core/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/dao/core/EntityDAO.java#L248) | SQL exception returns empty `ArrayList` — callers cannot distinguish "no rows" from "query exploded" | Throw a `DataAccessException` or at minimum log ERROR with the query text |
| 8 | [UserAccountController.java:121-226](../../../web/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/controller/UserAccountController.java#L121) | 7× `System.out.println("I'm in createUserAccount")` in account-creation path | SLF4J INFO; this is audit-critical (who created which account when) |
| 9 | [OpenRosaServices.java:331,605](../../../web/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/web/pform/OpenRosaServices.java#L331) | `System.out.println(e.getMessage())` on xform finalisation; never logged | SLF4J ERROR + propagate so OpenRosa clients see the 5xx |
| 10 | [NotificationActionProcessor.java:249,256](../../../core/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/domain/rule/action/NotificationActionProcessor.java#L249) | Participant-email send confirmations to stdout only — audit trail incomplete | SLF4J INFO + ensure the `audit_log_event` row carries the email outcome |

---

## P1 — Phase B.5 (Hibernate 6) blockers — hidden behind `@SuppressWarnings`

The DAO layer ([core/.../dao/hibernate/](../../../core/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/dao/hibernate/)) — 23 classes, **98** `@SuppressWarnings("deprecation")` suppressions hiding:

- `Session.createQuery(String, Class)` — Hibernate 6 removes; use `createSelectionQuery` or a typed HQL `createQuery`.
- `Session.createNativeQuery(String)` — Hibernate 6 wants `createNativeQuery(String, Class)`.
- `Query.list()` → `getResultList()`; `Query.uniqueResult()` → `getSingleResultOrNull()`.

**Hot-spots:** [RuleSetDao.java](../../../core/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/dao/hibernate/RuleSetDao.java) (15 suppressions), [ItemDao.java:51](../../../core/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/dao/hibernate/ItemDao.java#L51), [ItemFormMetadataDao.java:37](../../../core/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/dao/hibernate/ItemFormMetadataDao.java#L37).

**Other P1 sites:**

- [ClassCastHelper.java](../../../core/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/core/util/ClassCastHelper.java) — 7× `@SuppressWarnings("unchecked")` on unvalidated request-attribute casts. Add `Class<T>.isInstance` guard now.

---

## P2 — Mechanical lints (low risk, high count — clean before B.5 starts)

These don't block anything but their volume drowns the build log:

| Category | Count | Cleanup |
|---|---|---|
| Redundant cast | **98** | IntelliJ "Remove redundant cast" inspection. Hot-spots: [ExtractBean.java](../../../core/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/bean/extract/ExtractBean.java) (24), [CreateCRFVersionServlet.java:241](../../../web/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/control/admin/CreateCRFVersionServlet.java#L241) (10), [AuditEventDAO.java:139](../../../core/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/dao/admin/AuditEventDAO.java#L139) (4) |
| Boxed-primitive ctors (`new Integer(x)` etc.) | **42** | `Integer.valueOf` / `String.valueOf` / `Integer.parseInt`. Hot-spots: [AuditEventDAO.java](../../../core/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/dao/admin/AuditEventDAO.java) (9), [AdminSystemServlet.java](../../../web/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/control/admin/AdminSystemServlet.java) (8), [UserAccountBean.java](../../../core/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/bean/login/UserAccountBean.java) (5) |
| Misc JDK deprecations | **4** | `new Locale("en_US")` → `Locale.US` at [OpenClinicaUsernamePasswordAuthenticationFilter.java:168](../../../web/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/filter/OpenClinicaUsernamePasswordAuthenticationFilter.java#L168); `new URL(s)` → `URI.create(s).toURL()` at [SecureController.java:1167](../../../web/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/control/core/SecureController.java#L1167) |

---

## P3 — Structural items (planned, not urgent)

### `this`-escape in DAO/bean constructors (8 warnings)

[EntityDAO.java:112](../../../core/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/dao/core/EntityDAO.java#L112), [Term.java:34,40](../../../core/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/bean/core/Term.java#L34), [StudyUserRoleBean.java:61](../../../core/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/bean/login/StudyUserRoleBean.java#L61), [ResponseSetBean.java:49](../../../core/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/bean/submit/ResponseSetBean.java#L49), [UserAccountDAO.java:66,72](../../../core/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/dao/login/UserAccountDAO.java#L66).

Base classes call non-`final` setters from constructors → subclass overrides see partially-constructed state. Lowest-risk fix: mark the setters `final` after verifying no subclass overrides.

### Non-transient `Serializable` fields (42 warnings)

[MainMenuServlet.java:60-71](../../../web/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/control/MainMenuServlet.java#L60) (12 fields), [SecureController.java:158-180](../../../web/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/control/core/SecureController.java#L158) (9 fields), plus 21 `*Bean` classes.

MUW is single-host (no session replication) — `@SuppressWarnings("serial")` at the class level on servlets and `transient` on DAO fields in `*Bean` classes.

### Retire mockrunner + log4jdbc

The 6 JAXB-namespace + 3 missing-POM warnings all trace to two unmaintained transitive deps in [web/pom.xml](../../../web/pom.xml):
- `com.mockrunner.jdk15.jee5:mockrunner:0.4.2` + `:mockrunner-servlet:0.4.2` — supplanted by MockMvc / Spring Boot Test.
- `log4jdbc:log4jdbc4:1.2` — replace with `p6spy` or `datasource-proxy`.

### Deprecated bean enums still in active use

| Class | Callers | Plan |
|---|---|---|
| [`bean.submit.ResponseType`](../../../core/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/bean/submit/ResponseType.java) | ~158 | Defer — Phase E (SPA conversion) retires it. |
| [`bean.submit.DataType`](../../../core/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/bean/submit/DataType.java) | ~40 | Same. |
| [`bean.submit.GroupRole`](../../../core/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/bean/submit/GroupRole.java) | ~20 | Same. |
| `EventCRFBean.{get,set}StatusId(int)` | ~20 | Migrate to `setStatus(Status)` — type-safe, 5-line per-site change. |

### Dead heritage code — safe to delete

- [`bean.extract.SasNameValidator`](../../../core/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/bean/extract/SasNameValidator.java) — zero callers
- [`bean.extract.SPSSReportBean`](../../../core/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/bean/extract/SPSSReportBean.java) — zero callers
- [`bean.extract.SPSSVariableNameValidator`](../../../core/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/bean/extract/SPSSVariableNameValidator.java) — zero callers
- [`core.ExtendedBasicDataSource`](../../../core/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/core/ExtendedBasicDataSource.java) — author-marked candidate
- ~55 commented-out `// System.out.println(...)` lines

---

## Suggested PR plan

| PR | Scope | Files | Effort | Status |
|---|---|---|---|---|
| **audit-doc** | This document | 1 | — | in progress |
| **p0-error-handling** | P0-1 … P0-10 silent-error fixes | ~10 | 2 days | TBD |
| **b5-prep-manifest** | Drop `@SuppressWarnings("deprecation")` from `dao/hibernate/`, count + classify the warnings, file as B.5 manifest | 23 DAOs + 1 doc | 1 day | TBD |
| **lint-core** | Categories 1+3+6 mechanical cleanup in `core/` | ~50 | 1 day | TBD |
| **lint-web** | Same in `web/` | ~80 | 1-2 days | TBD |
| **structural-base** | `serial-cleanup` + `this-escape-finals` | ~30 | 1 day | TBD |
| **retire-mockrunner-and-dead-code** | Drop mockrunner deps + delete 4 dead heritage classes | `web/pom.xml` + 4 | 0.5 day | TBD |
| **statusid-migration** | Migrate `setStatusId(int)` → `setStatus(Status)` callers | ~20 | 1 day | TBD |

After `b5-prep-manifest` + `lint-core` + `lint-web` + `structural-base` land, `mvn compile` should drop from 203 warnings to a single-digit count — real Phase B.5 / Phase C deprecations will stand out instead of getting lost in chaff.

---

## Method

Three parallel scans on 2026-06-28 produced the underlying findings:

- **Heritage author comments** — `grep` for `FIXME` / `TODO` / `XXX` / `HACK` / `KLUDGE` in `core/src/main/java`, `web/src/main/java`, `web/src/main/webapp` excluding MUW-authored 2026-* date-prefixed notes.
- **Build warnings** — `mvn -DskipTests=true clean compile` against the canonical Docker image, categorising warnings emitted by `javac` + Maven plugins.
- **`@SuppressWarnings` + `@Deprecated` + debug-print** — `grep` across `core/src/main/java` + `web/src/main/java` with surrounding-context inspection.

Excluded:
- Tests (`core/src/test`, `web/src/test`) — own conventions, lower stakes.
- Generated CDISC ODM bindings (`odm/`).
- MUW author comments (recognisable by ISO date prefix `2026-…`).
