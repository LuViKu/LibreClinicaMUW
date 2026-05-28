# Phase B — Execution Playbook

**Status:** ready to start (Phase 0 + A complete on `feature/muw-modernization-phase-a-2`, pending merge to `lc-develop`).
**Scope:** cross the `javax.*` → `jakarta.*` cliff. Java 11 → 21, Spring 5.3 → 6.x, Hibernate 5.6 → 6.x, Tomcat 9 → 10/11, Servlet 3.1 → 6.0, JSTL 1.1 → 3.0, replace Castor.
**Target timeline:** 3–6 months, 2–3 devs FTE-equivalent. Per [DR-002](decision-record.md#dr-002--full-backend-re-platform-not-incremental-modernization).
**Companion docs:** [phase-b-dependency-analysis.md](phase-b-dependency-analysis.md) (per-library mapping), [MIGRATION.md](../../../MIGRATION.md) (full phase plan), [decision-record.md](decision-record.md) (open decisions DR-006 → DR-010).

This playbook turns the dependency analysis into a step-by-step execution plan with per-step verification gates and rollback notes. Each step lives on its own branch and is verified before merging to the Phase B integration branch.

---

## Pre-flight checklist

Phase B does not start until *all* of the following are true. Skipping any of these is borrowing risk against the clinical-data system.

- [ ] Phase 0 + A merged to `lc-develop` and pushed (CI green end-to-end).
- [x] **Castor characterisation tests** in place and passing on the current stack — see [§B.0 Castor characterisation](#b0--castor-characterisation-tests-pre-flight) below. **This is the non-negotiable gate.** Without byte-equivalent ODM XML capture, the Castor → JAXB replacement (B.3) cannot be reviewed. *Marked complete 2026-05-28 after the scope-correction noted in §B.0 (the metadata / clinical-data export paths build XML by hand-concatenation; their only Castor invocation — rules embedded in a metadata export — is already pinned by `CastorRulesMarshallerCharacterisationTest`).*
- [x] Decision record entries closed (2026-05-28):
  - [x] **[DR-006](decision-record.md#dr-006--castor-replacement-jakarta-jaxb)** — Castor replacement: **Jakarta JAXB 4**. Revisit only if a B.0 characterisation test cannot be made byte-equivalent on JAXB output even with `XmlAdapter`s.
  - [x] **[DR-010](decision-record.md#dr-010--java-package-rename-to-muw-namespace-during-phase-b11)** — Java packages `org.akaza.openclinica.*` + `org.libreclinica.*` rename to `at.ac.meduniwien.ophthalmology.libreclinica.*` during sub-phase B.11.
- [ ] Integration test backlog in [MIGRATION.md](../../../MIGRATION.md) — at least the first 5 critical-path tests (LoginFlowIT, StudyCrudIT, SubjectEnrolmentIT, StudyEventScheduleIT, AuditTrailIT) written + green. The post-Phase-0.3 test infrastructure proved we can write them; we need them as our regression net.
  - [x] Item 3 (`LoginFlowIT.passwordEncoderRecognisesLegacyMd5`) landed 2026-05-28 as `OpenClinicaPasswordEncoderTest` (4 unit tests, Phase B.4 gate).
  - [x] B.0 characterisation framework scaffolded 2026-05-28: `CastorCharacterisationIT` abstract base + `CastorCharacterisationFrameworkTest` smoke (2/2 green) + XMLUnit dependency + golden directory. Subclasses for each ODM code path are now mechanical, but still pending.
  - [x] B.0 exit gate closed 2026-05-28: 5 Castor characterisation tests in place — `CastorCharacterisationFrameworkTest` (2/2 framework smoke), `CastorRulesContainerCharacterisationTest` (empty `RulesPostImportContainer` round-trip via `mappingMarshallerMetadata.xml`), `CastorRulesMarshallerCharacterisationTest` (`Marshaller` shape via `mappingMarshaller.xml`), `CastorRulesUnmarshallerCharacterisationTest` (pins null-collection-on-empty behaviour via `mapping.xml`), `CastorClinicalDataUnmarshallerCharacterisationTest` (CRF data import unmarshaller via `cd_odm_mapping.xml`). Together these cover all five Castor invocation sites in the codebase. The two originally-planned DB-driven ITs (`OdmMetadataExportCharacterisationIT`, `OdmClinicalDataExportCharacterisationIT`) were dropped after the source-code investigation revealed the metadata + clinical-data export paths build XML by **hand-string-concatenation** in `MetaDataReportBean.addNodeStudy`, `AdminDataReportBean.addNodeAdminData`, and `ClinicalDataReportBean.addNodeClinicalData` — none of which call Castor. The only Castor invocation within the metadata export path is `MetaDataReportBean.handleLoadCastor` for the embedded `RulesPostImportContainer`, which is already pinned by `CastorRulesMarshallerCharacterisationTest`. The hand-built XML paths are independent of the Castor → JAXB swap; if a future regression net for the JDBC + hand-build flow is needed, it belongs in the Phase 0 integration test backlog, not B.0.
  - [ ] Items 1, 2, 4, 5, 6–20 (StudyCrudIT, SubjectEnrolmentIT, StudyEventScheduleIT, AuditTrailIT, full CRF/discrepancy/SDV/ODM coverage). Each is ~1–2 days of focused work given multi-entity DBUnit fixtures.
- [ ] **Compose smoke test runs on every dep bump, not just on the integration profile.** Phase A.2's Quartz 2.2.3 → 2.3.2 + Spring Security 5.1 → 5.8 + project-version rename to `1.4.0rc1-muw` all passed the 67/67 integration suite but broke the compose smoke test (Dockerfile WAR-name glob, scheduler-XML `s[...]` placeholders against an incomplete `datainfo.properties`, and `jobStore.class` bypassing Spring's wiring — three stacked bugs; see commit `b75a2c287`). The integration tests do not load `applicationContext-core-scheduler.xml` and never will detect a runtime-only regression of that shape. Every Phase B sub-phase merge gate must therefore include the smoke test job — wording made explicit in the gate column of the sub-phase table below. *Sub-phase gates updated 2026-05-28.*
- [ ] An institutional pre-Phase-B snapshot tag: `git tag -a pre-phase-b -m "..."` and pushed.
- [ ] A dedicated Phase B integration branch: `feature/phase-b-jakarta-cliff` off `lc-develop`.

---

## Sub-phase ordering and verification gates

Run sub-phases in this order. Each lives on a child branch off `feature/phase-b-jakarta-cliff`, is verified independently, and merges back when its gate is green.

| # | Sub-phase | Branch suffix | Gate (must be green to merge) | Est. effort |
|---|-----------|--------------|-------------------------------|-------------|
| B.0 | Castor characterisation tests | `castor-characterisation` | New tests pin every ODM import/export path to a byte-equivalent snapshot on the current stack | 2 weeks |
| B.1 | JDK 21 baseline (still Spring 5.3) | `jdk21-baseline` | `mvn test` + integration tests + smoke green on JDK 21 | 1–2 weeks |
| B.2 | Eclipse Transformer dry run ✅ ([report](phase-b-eclipse-transformer-dry-run.md)) | `eclipse-transformer-dry-run` | Throwaway branch; capture the diff size + list of unconvertible sites | 1 week |
| B.3 | Castor → JAXB | `castor-to-jaxb` | All B.0 characterisation tests pass byte-equivalent on JAXB output | 3–4 weeks |
| B.4 | Spring 5 → 6 + Security 5 → 6 + WS 1.5.6 → 4.x (or remove) | `spring6` | Integration tests + smoke green on Spring 6.1 / Security 6.x | 2–3 weeks |
| B.5 | Hibernate 5.6 → 6.4 (`jakarta.persistence`) | `hibernate6` | All DAO integration tests green; characterise HQL strictness regressions | 3–4 weeks |
| B.6 | Tomcat 9 → 10/11, Servlet 3.1 → 6.0 | `tomcat10` | App boots; smoke test (HTTP 200 on `/LibreClinica/`) green | 1–2 weeks |
| B.7 | JSP/JSTL taglib URI updates (413 JSPs + 4 .tld) | `jakarta-tags` | Smoke test passes on a representative cross-section of pages | 1–2 weeks |
| B.8 | Apache Commons jakarta-namespace updates (commons-fileupload2-jakarta, lang3, collections4) | `commons-jakarta` | Integration tests green | 2 weeks |
| B.9 | Mail / activation / JAX-RS Jakarta swap | `jakarta-mail` | Mail-related integration test green, JAX-RS endpoints respond | 1 week |
| B.10 | Joda-Time → `java.time` | `java-time` | All date-handling integration tests green | 1 week |
| B.11 | Java package rename (heritage `org.akaza.openclinica.*` → `at.ac.meduniwien.ophthalmology.*`) | `package-rename` | Build green; all DBUnit fixtures still resolve; `jsp:useBean` directives updated | 1 week |
| B.12 | Reconciliation sweep + GCP-style manual smoke | `phase-b-final` | Manual: login → create study → enrol subject → submit CRF (initial DE + DDE) → discrepancy note → SDV → sign → export ODM → verify audit log | 3–4 weeks |

Total: ~5 months FTE-equivalent. Some sub-phases can run in parallel after B.3 lands (B.4/B.5 vs. B.8/B.9/B.10).

---

## B.0 — Castor characterisation tests (pre-flight)

The single highest-risk change in Phase B is replacing Castor with Jakarta JAXB. Castor's marshalling/unmarshalling behaviour for the CDISC ODM 1.3 schema is the *de facto* contract of every ODM consumer. Before swapping the engine, capture that contract.

### What to characterise

| ODM code path | Where it lives | Capture |
|---|---|---|
| ODM XML import (CRF data) | `web/src/main/java/.../control/submit/ImportCRFDataServlet.java` + `core/src/main/java/.../service/ImportCRFDataService.java` | Round-trip: import a known XML, dump the resulting object graph (canonicalised), assert equality |
| ODM XML export (metadata) | `web/src/main/java/.../web/restful/ODMMetadataRestResource.java` | Given a fixture study, the produced XML must be byte-equivalent to a checked-in golden file (with XML-canonical normalisation: attribute ordering, whitespace, namespace prefixes) |
| ODM XML export (clinical data) | `web/src/main/java/.../web/restful/ODMClinicalDataController.java` | Same: byte-equivalent against golden file |
| CRF import via Excel → ODM | `web/src/main/java/.../control/admin/ImportCRFServlet.java` | Excel-driven path: same round-trip assertion |
| Rules ODM XML | `core/src/main/java/.../domain/rule/...` + `WEB-INF/schemas/rules-ODM.xsd` | Round-trip and schema-validation assertions |

### How

- Add `org.xmlunit:xmlunit-core` 2.10 to test scope for canonical XML comparison.
- Golden files live under `core/src/test/resources/org/akaza/openclinica/odm/golden/` — one per characterised path. Generated by running the current stack against the fixture; reviewed manually once for correctness; thereafter immutable.
- Tests are named `CastorCharacterisationIT` (one per path), excluded from the default surefire run (DB-dependent), included in the `integration-tests` profile.
- During B.3, the tests are not modified — the JAXB output must match the Castor golden bit-for-bit (with XMLUnit's similarity matching to tolerate insignificant whitespace).

### Exit criterion for B.0

A reviewer (you) signs off that every **Castor** invocation site in the codebase has a characterisation test pinning its current output, *and* every reviewed test's golden / pinned object graph is correct.

#### B.0 scope correction (2026-05-28)

The original framing of this section listed two DB-driven characterisation ITs against the metadata + clinical-data export REST endpoints. A source-code investigation while writing the first one (`OdmMetadataExportCharacterisationIT`) showed that:

1. **`FullReportBean.createStudyMetaOdmXml` and `MetaDataReportBean.addNodeStudy` build the ODM XML by `StringBuffer.append(...)` concatenation** — escaping each value through `StringEscapeUtils.escapeXml`. Castor is not invoked.
2. **`AdminDataReportBean.addNodeAdminData` and `ClinicalDataReportBean.addNodeClinicalData` do the same.**
3. The **only** Castor invocation in the metadata export path is `MetaDataReportBean.handleLoadCastor(RulesPostImportContainer)` (line ~134), reached from `addNodeRulesData` (line ~169), and only when the study has rules to emit. Its Castor surface is the marshaller-via-`mappingMarshallerMetadata.xml` flow which is already pinned by `CastorRulesContainerCharacterisationTest`.
4. Replacing Castor with Jakarta JAXB therefore **does not change** the metadata / clinical-data export output for a study without rules, and changes the rules sub-document via a code path that is already characterised.

The two DB-driven ITs were dropped from the B.0 exit gate. If the team later wants regression coverage for the hand-built XML output itself (e.g. ahead of B.7's JSP/JSTL conversion or B.10's date-handling sweep), that belongs in the [MIGRATION.md](../../../MIGRATION.md) Phase 0 integration test backlog — same DBUnit + multi-entity fixture pattern as the other DAO ITs — not in B.0.

---

## B.1 — JDK 21 baseline (still Spring 5.3)

Bump build + runtime to JDK 21 without changing anything else. Surfaces every JDK-compatibility issue before the dependency churn of later sub-phases.

### Steps

1. Update `pom.xml` `maven.compiler.source`/`maven.compiler.target` from 8 → 21 (and `maven-compiler-plugin` to a recent version, e.g. 3.13.0).
2. Update `Dockerfile`: builder `maven:3-eclipse-temurin-21`, runtime `tomcat:9-jdk21`.
3. Update `.github/workflows/build.yml` JDK matrix from `['8', '11']` → `['21']` (drop 8 and 11).
4. Update `.github/workflows/codeql.yml` and `.github/workflows/security.yml` JDK to 21.
5. `mvn -B -ntp clean test` — fix any compile errors. JDK 21 removed many deprecated `sun.misc.*` APIs and some `java.*` methods marked `@Deprecated(forRemoval=true)`.
6. `mvn -B -ntp -pl core -am -P integration-tests test` against `postgres:14-alpine` — must produce **67/67 green**.
7. Smoke: `docker compose up --build` → HTTP 200 on `/LibreClinica/`.

### Likely issues

- **JAXB** (`javax.xml.bind`) is fully removed in JDK 11+; we already pulled in `jaxb-api` + `jaxb-runtime` explicitly (April 2026 fix PR #440), so this should not surface again — but verify on 21.
- **Java module system warnings** from old Hibernate / Spring reflection. Suppress with `--add-opens=...` JVM flags only when needed; prefer to fix on the library upgrade in later sub-phases.
- **`commons-collections` 3.2.2 unsafe deserialization** triggers a JDK 21 warning at first use; deferred to B.8.

### Rollback

`git reset --hard pre-phase-b` and start over on a fresh sub-branch.

---

## B.3 — Castor → JAXB (the heart of Phase B)

**Prerequisite:** B.0 characterisation tests are green on the current stack.

### Steps

1. Inventory every Castor usage: `grep -rn 'org.codehaus.castor\|castor\.\(xml\|core\)' core/ web/ ws/ odm/`.
2. Replace dependency declarations:
   - Remove `org.codehaus.castor:castor` and `org.codehaus.castor:castor-xml` from `pom.xml`.
   - Add `jakarta.xml.bind:jakarta.xml.bind-api:4.0.x` and `org.glassfish.jaxb:jaxb-runtime:4.0.x`.
3. For each Castor `Unmarshaller`/`Marshaller` site, write a JAXB equivalent:
   - Castor uses XML mapping files (`*.xml.mapping`); JAXB uses JAXB annotations on classes or `xjc`-generated classes (the `odm` module already has `xjc`-generated JAXB sources — use those).
   - Helper class: a single `OdmJaxbContext` bean that holds the cached `JAXBContext` for ODM and is wired via Spring (`JAXBContext` is expensive to construct).
4. Replace Castor's `XmlSchemaValidationHelper` calls with `jakarta.xml.validation.Validator`.
5. Run B.0 characterisation tests after each replacement site — must stay green.
6. Run the full integration test suite — must stay 67/67.
7. Manual smoke: import a non-trivial ODM file via the UI; export the same study; diff the exported XML against the imported XML.

### Risk-mitigation

- **Schema-validated XSD generation** path: Castor and JAXB differ in their XSD generation output. We don't ship Castor-generated XSDs to consumers (we ship the static ODM 1.3 XSD); no action needed unless we discover a downstream consumer.
- **Default namespace handling**: ODM 1.3 uses default namespace; both Castor and JAXB support this but XMLUnit's similarity matcher should treat `xmlns="…"` and `xmlns:odm="…"` as equivalent. Configure XMLUnit `IgnoreAttributeOrder` and `NamespaceContext` accordingly.
- **Lexical preservation**: dateTime values, decimals, etc. — JAXB's `XMLGregorianCalendar` differs from Castor's string-passthrough. If a B.0 test fails on a lexical detail, add a per-field `XmlAdapter`.

### Rollback

Castor → JAXB is the largest blast-radius change in Phase B. Branch must merge atomically (all sites or none). If even one B.0 test cannot be made green, abort the sub-phase, file a DR-006 amendment recommending Jackson XML or MOXy.

---

## B.4 — Spring 5 → 6 + Security 5 → 6

### Steps

1. Bump `<spring.version>` → 6.1.x, `<spring.security.version>` → 6.3.x.
2. Run Eclipse Transformer over the diff first (mechanical `javax.servlet.*` → `jakarta.servlet.*` etc.).
3. Fix compile errors: many deprecated APIs removed in Spring 6.
   - `WebMvcConfigurerAdapter` removed → implement `WebMvcConfigurer` directly.
   - `Jdbc4SqlXmlHandler` removed.
   - `ResponseEntityExceptionHandler` signature changes.
4. **Spring Security 6**:
   - `WebSecurityConfigurerAdapter` removed → migrate to `SecurityFilterChain` bean (lambda-style DSL).
   - Password encoder default: provide a `DelegatingPasswordEncoder` that recognises legacy MD5 hashes and upgrades on next login. The existing `UserAccountBean.password` field stores MD5; do not invalidate sessions.
   - CSRF default is now ON for all stateful POSTs; some legacy form submissions may need either a CSRF token added or explicit `.csrf().disable()` on read-only paths (audit carefully — clinical-data POSTs should NOT disable CSRF).
5. **Spring WS 1.5.6** → either bump to 4.0.x or remove. Recommendation: **remove the `ws` module** entirely if no active SOAP consumer (verify with stakeholders). The README already calls it "legacy, not tested, not actively developed."
6. Run full integration test suite.
7. Smoke test, focusing on the login path (DelegatingPasswordEncoder + CSRF).

### Verification

- Unit + integration: 67/67 (or more, if Phase 0 backlog tests have landed).
- Manual: log in as an existing user (MD5 hash); verify auth succeeds.

---

## B.5 — Hibernate 5.6 → 6.4

### Steps

1. Bump `<hibernate.version>` → 6.4.x.
2. Replace `javax.persistence.*` → `jakarta.persistence.*` in entity annotations (Eclipse Transformer).
3. Audit every HQL string:
   - `grep -rn '@NamedQuery\|createQuery\(\|createNativeQuery\(' core/src/main/java/`
   - Hibernate 6 HQL is stricter: implicit joins are forbidden, `WHERE id = ?` requires explicit `e.id = ?`.
4. `Criteria` API removed in Hibernate 6 — migrate any usage to JPA Criteria.
5. Sequence generator behaviour change (the legacy compatibility mode is available via `hibernate.id.db_structure_naming_strategy=legacy`).
6. EhCache 2 → Hibernate JCache + Caffeine (or stay on EhCache 3 if simpler).
7. Run all integration tests — every DAO test exercises Hibernate sessions.

### Risk

This is the second-highest-risk sub-phase after B.3. The integration test suite is the primary regression net; Phase 0 backlog tests (especially `AuditTrailIT`, `StudyEventScheduleIT`, `CrfDataEntryIT`) should be in place before B.5.

---

## B.7 — JSP/JSTL taglib URI updates

413 JSPs + 4 custom `.tld` files. Mechanical but voluminous.

### Steps

1. Eclipse Transformer handles the bulk of `javax.servlet.*` → `jakarta.servlet.*` in JSPs.
2. Manual: replace taglib URIs in every JSP header:
   - `http://java.sun.com/jsp/jstl/core` → `jakarta.tags.core`
   - `http://java.sun.com/jsp/jstl/fmt` → `jakarta.tags.fmt`
   - `http://java.sun.com/jsp/jstl/functions` → `jakarta.tags.functions`
   - `http://java.sun.com/jsp/jstl/sql` → `jakarta.tags.sql`
   - `http://java.sun.com/jsp/jstl/xml` → `jakarta.tags.xml`
3. Update the four custom `.tld` files (`formtags.tld`, `view_tags.tld`, `openclinica.tld`, `jmesa.tld`):
   - Update root element schema to JSP 3.1
   - Update `tag-class` references to use `jakarta.servlet.jsp.*` superclasses where applicable
4. `jsp:useBean class="org.akaza.openclinica.*.SomethingBean"` references survive the Java package rename (B.11) if we do the rename first; otherwise update after.
5. Smoke test a representative sample (login, dashboard, data entry, discrepancy notes, admin) — JSP errors are runtime 500s, not compile failures.

### Verification

The compose smoke test catches "the app doesn't start". A wider smoke test (manual click-through of ~10 screens) catches taglib URI typos.

---

## B.11 — Java package rename

Renames the heritage `org.akaza.openclinica.*` and `org.libreclinica.*` Java packages to `at.ac.meduniwien.ophthalmology.libreclinica.*`. Per [DR-010](decision-record.md) (open): recommendation is **yes, during Phase B**.

### Why bundle into Phase B

Every file is being touched for `javax`→`jakarta` anyway. A second round of touching every file just for a package rename would double the merge cost against upstream cherry-picks. Do both in the same window or not at all.

### Steps

1. IntelliJ structural-replace (or `jrefactor`/manual) on every `package` and `import` declaration.
2. Update Spring XML bean class references (`<bean class="org.akaza.openclinica.*">` → new package).
3. Update `<jsp:useBean class="...">` in JSPs.
4. Update DBUnit fixture file paths if they encode packages (they do — `getTestDataFilePath()` uses `getClass().getPackage().getName().replace(".", "/")`).
5. Update `web/src/main/webapp/WEB-INF/web.xml` and `applicationContext-*.xml` `component-scan base-package` entries.
6. Update test class paths for the integration test backlog.
7. Run all tests + smoke.

### Verification

Compile + integration tests pass; smoke test passes.

---

## Phase B exit criteria

All of:

- [ ] Build runs on JDK 21
- [ ] All Spring dependencies on 6.x / Security 6.x line
- [ ] Hibernate on 6.x with `jakarta.persistence`
- [ ] Tomcat 10/11; Servlet 6.0 / JSP 3.1 / JSTL 3.0
- [ ] **Zero `javax.*` imports in `src/main/java/`** (verifiable: `! grep -rln 'import javax\.' core/src/main/java web/src/main/java ws/src/main/java`)
- [ ] All 413 JSPs updated to Jakarta taglib URIs
- [ ] Java packages renamed to `at.ac.meduniwien.ophthalmology.libreclinica.*` (if DR-010 ratified)
- [ ] Castor entirely removed (`! grep -rln 'org.codehaus.castor' .`)
- [ ] All B.0 ODM characterisation tests still pass byte-equivalent on JAXB output
- [ ] Full integration test suite passes (≥ 67 + any Phase 0 backlog tests added since)
- [ ] Manual GCP-style smoke pass executed and documented:
      login → create study → enrol subject → schedule + open event → submit CRF (initial DE + DDE comparison) → file a discrepancy note → mark SDV → sign event → export ODM → spot-check audit log
- [ ] Performance regression check: page load + ODM export times within 2× of pre-Phase-B baseline
- [ ] Tag `post-phase-b` on `lc-develop` after merge

---

## Decision record updates expected during Phase B

- DR-006 ratified or amended (Castor replacement: JAXB confirmed, or escalate to Jackson XML / MOXy)
- DR-009 closed (Spring Security OAuth2 — remove vs. replace with Spring Authorization Server, based on actual usage audit)
- DR-010 ratified (Java package rename)
- DR-011 (new): connection pool decision (HikariCP recommended)
- DR-012 (new): Joda-Time replacement (java.time recommended)
- DR-013 (new): EhCache 2 → Ehcache 3 / Caffeine

---

## Risks specific to Phase B (refines [MIGRATION.md § Risk register](../../../MIGRATION.md#risk-register))

| ID | Risk | Mitigation |
|----|------|------------|
| RB1 | B.0 characterisation tests don't cover a real-world ODM dialect we haven't seen yet | Source the test fixtures from real institutional/upstream ODM files, not synthetic |
| RB2 | Hibernate 6 HQL strictness silently breaks a runtime query | Phase 0 backlog tests (`StudyCrudIT`, `SubjectEnrolmentIT`, `AuditTrailIT`) before B.5; exercise every `@NamedQuery` |
| RB3 | DelegatingPasswordEncoder migration locks existing users out | Add a `LoginFlowIT.passwordEncoderRecognisesLegacyMd5` test (item #3 in Phase 0 backlog) before B.4 |
| RB4 | Eclipse Transformer corrupts a binary class file (uncommon but documented) | Run Transformer on source only, never on compiled JARs; cross-check transformed sources compile |
| RB5 | A JSP taglib URI typo passes compile but 500s at runtime | Phase 0 smoke test catches "the app doesn't start"; a wider manual click-through catches per-page errors |
| RB6 | `web/src/test/java` web-module tests (currently 2) break when servlets touch jakarta | Audit + fix during B.6 |
| RB7 | Phase B drags 6+ months due to scope creep | Per-sub-phase merge gates; if a sub-phase is taking >2× estimate, escalate to a go/no-go review |
| RB8 | Upstream ReliaTec lands an incompatible change that we cherry-pick | Eclipse Transformer on every cherry-pick post B.3; document each translation decision in `upstream-merges.md` (new file) |
| RB9 | **Runtime-only regression that the integration test suite does not exercise** (e.g. Spring application-context wiring in XML files only loaded at app startup, like `applicationContext-core-scheduler.xml`) | Compose smoke test must be a hard merge gate on every Phase B sub-phase, not just the matrix Build job. Precedent: Phase A.2 shipped a Quartz config gap that passed 67/67 integration tests but broke `docker compose up` — see [commit `b75a2c287`](../../..). When adding a sub-phase, ensure its row in the sub-phase table names the smoke test in the gate column explicitly. |

---

## Operational notes

- **No clinical use during Phase B**, per [DR-004](decision-record.md#dr-004--clinical-use-deferred-until-modernization-completes). This is the entire reason for the deferral decision — Phase B is the high-risk period.
- **Validation cycles**: per-sub-phase merge gates batch validation effort. Do not re-validate per dep bump.
- **Parallel branches**: B.4/B.5 can run in parallel after B.3 lands. B.8/B.9/B.10 are independent of B.4/B.5 and can run in parallel with them.
- **CI matrix during transition**: drop JDK 8 + 11 from `.github/workflows/build.yml` matrix as part of B.1.
- **Documentation hygiene**: every sub-phase merge updates [MIGRATION.md](../../../MIGRATION.md) (status column) and appends an entry to a new `phase-b-log.md` documenting the actual delta and any unexpected findings.

---

## When to start

Once the pre-flight checklist is fully ticked. Conservatively: ~6–8 weeks after Phase A.2 merges, to allow the Phase 0 integration test backlog to grow and the Castor characterisation work to complete.
