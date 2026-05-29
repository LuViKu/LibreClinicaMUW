# LibreClinica MUW — Backend Modernization Plan

**Owner:** Department of Ophthalmology and Optometry, Medical University of Vienna
**Status:** Active — Phase 0 in progress (initiated 2026-05-28)
**Target:** Spring Boot 3 + Java 21 + Jakarta EE + library replacement (full re-platform)
**Posture:** Hard fork from `reliatec-gmbh/LibreClinica` upstream — manual cherry-pick of upstream patches via Eclipse Transformer
**Estimated effort:** 12–18 months · 2–3 developers (FTE-equivalent)

---

## Why this document exists

This codebase is forked from OpenClinica 3.14 (2019) and currently runs on Spring 5.1.4 / Java 11 / Tomcat 9 / `javax.*` namespace — a 2019-era stack with components from as early as 2007 (Prototype.js 1.6, Apache POI 3.0.1, iText 2.1.2). Spring 5.x reached community EOL in December 2024; security patches on the upstream stack have stopped. Modernization is a prerequisite for sustainable institutional use.

The strategic decision (2026-05-28) is to do this as a **hard fork** with a **full re-platform** target. See [docs/development/modernization/decision-record.md](docs/development/modernization/decision-record.md) for the decision context.

---

## Target stack

| Layer | From | To | Phase |
|-------|------|----|----|
| Java | 11 | **21 (LTS)** | B |
| Spring Framework | 5.1.4 | **6.1+** | B |
| Spring Boot | n/a | **3.x** | C |
| Spring Security | 5.1.4 | **6.x** | B |
| ~~Spring WS~~ | ~~3.0.10~~ | **removed** | B (#31, 2026-05-29) |
| Servlet API | `javax.servlet` 3.1 | **`jakarta.servlet` 6.0** | B |
| JPA | `javax.persistence` | **`jakarta.persistence`** | B |
| Hibernate ORM | 5.4.2 | **6.4+** | B |
| Tomcat | 9 (standalone WAR) | **embedded** (executable JAR) | C |
| JSTL | 1.1.2 | **Jakarta JSTL 3.0+** | B |
| Configuration | XML `applicationContext-*.xml` | **Java config + `@ConfigurationProperties` + env vars** | C |
| Liquibase | 3.6.3 | **4.x** | D |
| Jackson | 2.9.8 | **2.18+** | A → B |
| Logback | 1.1.3 | **1.5+** | A → B |
| Castor XML | 1.4.1 (abandoned 2014) | **Jakarta JAXB / Jackson XML / MOXy** | D (forced by B) |
| iText | 2.1.2 (last permissive) | **OpenPDF 1.4+** | D |
| Apache POI | 3.0.1 (2007) | **5.x** | D |
| Apache FOP | 1.0 | **2.x** | D |
| Quartz | 2.2.3 | **2.5+** | D |
| GWT menu widget | compiled (abandoned) | **removed** — vanilla / framework-native nav | D |
| Prototype.js | 1.6 (abandoned 2010) | **removed** | D |
| Scriptaculous | 1.x (abandoned) | **removed** | D |
| jQuery | 1.9.1 | **3.7+** during D, or removed in E | D / E |

---

## Phase 0 — Safety net

**Goal:** make it impossible to ship a broken build undetected. Pre-requisite for everything that follows.
**Timeline:** 3–4 weeks
**Risk:** low

- [x] Decision record committed to repo ([docs/development/modernization/decision-record.md](docs/development/modernization/decision-record.md))
- [x] Branding: Maven `groupId` migrated to MUW domain (`at.ac.meduniwien.ophthalmology.libreclinica`)
- [x] `.github/workflows/build.yml`: Maven build + unit tests + WAR artifact upload + Surefire reports on failure (JDK 8 + 11 matrix)
- [x] `.github/workflows/build.yml`: Compose smoke test job (HTTP 200 on `/LibreClinica/` after `docker compose up`)
- [x] `.github/dependabot.yml`: weekly Maven + GitHub Actions + Docker updates, grouped by ecosystem (Spring, Jackson, Hibernate, Apache Commons, Logback)
- [x] **Triage current test suite (2026-05-28):** 21 test classes, 45 test methods total. Of 45: **33 pure unit tests pass** ✅; **11 DB-integration tests** (extend `HibernateOcDbTestCase` / `OcDbTestCase`) fail at JVM class-init because they require a live PostgreSQL on `localhost:5432`; **1 broken-by-omission** test (`RulesPostImportContainerServiceTest` — only test method is commented out).
- [x] **Flip `<skipTests>true</skipTests>` → `false`** in [pom.xml](pom.xml) (2026-05-28). DB-integration tests excluded via surefire `<excludes>`; unit tests run by default.
- [x] **`integration-tests` Maven profile** (2026-05-28): `mvn verify -P integration-tests` overrides surefire excludes via `combine.self="override"` and runs the DB-dependent tests. Expects PostgreSQL with database `openclinica-TEST`, user `clinica`/password `clinica` (per [core/src/test/resources/datainfo.properties](core/src/test/resources/datainfo.properties)).
- [x] **CI integration-tests job** (2026-05-28): GitHub Actions service container (`postgres:14-alpine`) runs alongside the Maven job. `continue-on-error: true` while Phase 0.2 schema-bootstrap work is pending — see below.
- [x] **Phase 0.2 — Bootstrap test DB schema (2026-05-28):** Option C verified to work. The Spring test context loaded by `HibernateOcDbTestCase` already wires `SpringLiquibase` (in `applicationContext-core-db.xml`) and a `PropertyPlaceholderConfigurer` with the custom `s[...]` syntax (in `applicationContext-core-spring.xml`) that resolves `s[driver]`, `s[url]`, `s[username]`, `s[password]` from `classpath:datainfo.properties`. When run against a Postgres providing the right DB (`openclinica-TEST`, user `clinica`/password `clinica`), Liquibase applies all 145+ changesets and DAO beans are wired correctly. Fixed: `test.properties` and `datainfo.properties` referenced different DB names (`openclinica-TEST-3.12` vs `openclinica-TEST`) — DBUnit and Spring DAOs were targeting different databases. Aligned both to `openclinica-TEST`. **Result:** integration test suite went from 0 → 63 tests running (33 unit tests + 30 integration tests now reach their test methods). New layer of pre-existing breakage exposed — see Phase 0.3.
- [x] **Phase 0.3 — Hibernate session-management fix (2026-05-28):** Resolved via option (b) — minimal-diff rewrite of `HibernateOcDbTestCase` to manage transactions per-test. Static initializer no longer opens a transaction (it was opened-then-leaked); a `private TransactionStatus testTransactionStatus` field is opened in `setUp()` and rolled back (not committed) in `tearDown()`. Rollback (vs. commit) prevents test writes from accumulating across the suite. Also restored `RulesPostImportContainerServiceTest` with a `testPlaceholder()` stub + Javadoc explaining the long-commented-out historical method, so JUnit 3 no longer reports "No tests found". **Result: 63/63 integration tests pass on `mvn -P integration-tests test` against a clean Postgres** (was: 63 running, 1 failure + 26 errors). CI integration-tests job had `continue-on-error: true` removed — the job now enforces green.
- [ ] **Add critical-path integration tests** (~20 tests, target ~2 weeks). Backlog with concrete test names and minimum assertions — each should extend `HibernateOcDbTestCase` (now fixed in Phase 0.3) or migrate to `@RunWith(SpringJUnit4ClassRunner.class)`:

  | # | Class / method | Asserts |
  |---|---|---|
  | 1 | `LoginFlowIT.loginSuccessful` | `AuthenticationProvider.authenticate(...)` returns an `Authentication` with the expected `UserAccountBean` for valid credentials |
  | 2 | `LoginFlowIT.loginFailedRecordsAuditEntry` | After 1 failed login, exactly one `audit_user_login` row exists with `loginStatus = FAILED_LOGIN` and the username |
  | 3 | `LoginFlowIT.passwordEncoderRecognisesLegacyMd5` | A hash created by the legacy MD5 encoder still authenticates via `DelegatingPasswordEncoder` |
  | 4 | `StudyCrudIT.createAndRetrieveStudy` | `StudyDAO.create(study)` returns a row with a positive PK; `findByPK(pk)` round-trips all required fields |
  | 5 | `StudyCrudIT.parentChildStudy` | Saving a study with `parentStudyId` populated yields the right hierarchy via `findChildrenByParent` |
  | 6 | `SubjectEnrolmentIT.enrolSubjectInStudy` | Creating a `Subject` + `StudySubject` produces matching rows linked by foreign key; `enrollment_date` defaults sensibly |
  | 7 | `SubjectEnrolmentIT.duplicateLabelRejected` | Re-enrolling a subject with the same label in the same study throws the documented exception |
  | 8 | `StudyEventScheduleIT.scheduleEvent` | `StudyEventDAO.create(event)` for an existing `StudyEventDefinition` produces a row in `study_event` with `status=SCHEDULED` |
  | 9 | `StudyEventScheduleIT.eventStatusTransitions` | Drive an event through SCHEDULED → DATA_ENTRY_STARTED → COMPLETED → LOCKED → SIGNED; verify each transition is persisted |
  | 10 | `CrfDataEntryIT.initialDataEntry` | Submit an `EventCRF` with `ItemData` rows; verify the EventCRF status becomes `INITIAL_DATA_ENTRY_COMPLETE` and each `ItemData.value` is persisted with the right encoding |
  | 11 | `CrfDataEntryIT.doubleDataEntryComparison` | Round-trip two independent DDE submissions; verify divergence detection produces a `DiscrepancyNote` |
  | 12 | `CrfDataEntryIT.fileItemUpload` | Submit an item with `ResponseType.FILE`; verify the file blob is written under `${filePath}` and `ItemData.value` holds the relative path |
  | 13 | `DiscrepancyNoteIT.createAnnotation` | Create a `DiscrepancyNote` of type ANNOTATION attached to a specific `ItemData`; verify retrieval via `DnItemDataMap` |
  | 14 | `DiscrepancyNoteIT.queryWorkflow` | Create a QUERY, transition it through NEW → OPEN → RESOLVED → CLOSED via the rule action; verify status persisted and audit row created |
  | 15 | `SdvIT.sdvStatusToggle` | Mark an EventCRF as `sdvStatus = TRUE`; verify the change is persisted and the SDV filter query returns the row |
  | 16 | `OdmExportIT.studyMetadataExport` | Call `ODMMetadataRestResource` for a known study; assert the produced ODM XML validates against the ODM 1.3 XSD shipped in `odm/` |
  | 17 | `OdmImportIT.crfImportRoundTrip` | Import a CRF via `ImportCRFDataServlet`'s service layer; assert the resulting CRF + version + items match the input ODM document |
  | 18 | `AuditTrailIT.mutationWritesAuditRow` | For each of `INSERT`, `UPDATE`, `DELETE` on `ItemData`, assert one `audit_log_event` row with `audit_table='item_data'`, correct `old_value`/`new_value`, and `reason_for_change` |
  | 19 | `RandomizationRuleIT.ruleTriggersRandomize` | Configure a `RuleSet` with a `RandomizeAction`; submit data that satisfies the condition; assert the randomization result is persisted |
  | 20 | `StudyLockIT.lockedStudyRejectsWrites` | Set `Study.frozen_study=true`; assert that subsequent attempts to save `EventCRF` data throw the documented "study locked" exception |

  These will collectively be the institutional GCP regression suite. Open question: convert to `@RunWith(SpringJUnit4ClassRunner.class) + @Transactional` first (Phase B prep), or use the existing `HibernateOcDbTestCase` pattern for momentum?

  **Progress (2026-05-28):**

  - **Item 3 (`LoginFlowIT.passwordEncoderRecognisesLegacyMd5`)** ✅ — landed as a unit test, [`OpenClinicaPasswordEncoderTest`](core/src/test/java/at/ac/meduniwien/ophthalmology/libreclinica/core/OpenClinicaPasswordEncoderTest.java). 4 test methods pin the SHA-1 round-trip, salted-encoding equivalence, MD5 fallback path (legacy users still authenticate), and wrong-password rejection. Phase B.4 gate.
  - **Extended fixtures, not in the original 20 but pattern-proven and high-value Phase B.5 guards:**
    - [`ConfigurationDaoTest`](core/src/test/java/at/ac/meduniwien/ophthalmology/libreclinica/dao/ConfigurationDaoTest.java) 3 → 7 methods (multi-row fixture, value round-trip, null-key handling, findAll containment, saveOrUpdate update-not-insert).
    - [`AuditUserLoginDaoTest`](core/src/test/java/at/ac/meduniwien/ophthalmology/libreclinica/dao/AuditUserLoginDaoTest.java) 2 → 5 methods (multi-row fixture, findAll containment, two Hibernate-Criteria-based filter tests via `AuditUserLoginFilter` — directly pins the Phase B.5 Criteria API removal target).
  - **Phase B.0 Castor characterisation framework + 5 tests green** ✅ (on `feature/phase-b-jakarta-cliff`):
    - [`GoldenAssertions`](core/src/test/java/at/ac/meduniwien/ophthalmology/libreclinica/odm/characterisation/GoldenAssertions.java) static helper class with `assertXmlSimilarToGolden` / `assertXmlIdenticalToGolden` + capture-on-first-run helper messages. Golden directory at `core/src/test/resources/at/ac/meduniwien/ophthalmology/libreclinica/odm/characterisation/golden/`.
    - [`CastorCharacterisationIT`](core/src/test/java/at/ac/meduniwien/ophthalmology/libreclinica/odm/characterisation/CastorCharacterisationIT.java) abstract base class (extends `HibernateOcDbTestCase`) for DB-driven characterisation tests; delegates to `GoldenAssertions`.
    - [`CastorCharacterisationFrameworkTest`](core/src/test/java/at/ac/meduniwien/ophthalmology/libreclinica/odm/characterisation/CastorCharacterisationFrameworkTest.java) framework smoke test (**2/2 green**) — verifies XMLUnit is wired correctly.
    - **Concrete characterisation tests covering 4 of the 5 distinct Castor mapping files** (unit-test-level, no DB):
      - [`CastorRulesContainerCharacterisationTest`](core/src/test/java/at/ac/meduniwien/ophthalmology/libreclinica/odm/characterisation/CastorRulesContainerCharacterisationTest.java) — `mappingMarshallerMetadata.xml` via `MetaDataReportBean.handleLoadCastor`. **1/1 green**, golden captured.
      - [`CastorRulesMarshallerCharacterisationTest`](core/src/test/java/at/ac/meduniwien/ophthalmology/libreclinica/odm/characterisation/CastorRulesMarshallerCharacterisationTest.java) — `mappingMarshaller.xml` via `DownloadRuleSetXmlServlet.handleLoadCastor`. **1/1 green**, golden captured.
      - [`CastorRulesUnmarshallerCharacterisationTest`](core/src/test/java/at/ac/meduniwien/ophthalmology/libreclinica/odm/characterisation/CastorRulesUnmarshallerCharacterisationTest.java) — `mapping.xml` via `ImportRuleServlet`. **1/1 green**, asserts the *current* Castor behaviour of leaving collection fields null after parsing an empty envelope (pinned 2026-05-28 so a Phase B.3 JAXB swap surfaces if it changes this).
      - [`CastorClinicalDataUnmarshallerCharacterisationTest`](core/src/test/java/at/ac/meduniwien/ophthalmology/libreclinica/odm/characterisation/CastorClinicalDataUnmarshallerCharacterisationTest.java) — `cd_odm_mapping.xml` via `ImportCRFDataServlet`. **2/2 green**, pins ODM-envelope shape + StudyOID round-trip.
    - `org.xmlunit:xmlunit-core` 2.10 + `xmlunit-matchers` 2.10 in dependencyManagement (test scope) per [DR-006](docs/development/modernization/decision-record.md#dr-006--castor-replacement-jakarta-jaxb). Direct deps in `core/pom.xml`.
    - `**/odm/characterisation/*IT.java` excluded from default surefire (need live Postgres); they run under `mvn -P integration-tests test`.

  **Still pending for Phase B.0 to exit** (the two DB-driven export paths — the framework supports them, they just need multi-entity DBUnit fixtures):
  - `OdmMetadataExportCharacterisationIT` — `ODMMetadataRestResource` against a study fixture. Extends `CastorCharacterisationIT`. Needs Study + StudyEventDefinition + CRF + CRFVersion + ItemGroup + Item rows in a DBUnit fixture.
  - `OdmClinicalDataExportCharacterisationIT` — `ODMClinicalDataController` against the same fixture plus EventCRF + ItemData rows.

  **Counts:** unit suite 33 → 39 (+4 password + 2 framework); integration suite 67 → 74 (+3 audit-user-login extensions; the +4 from ConfigurationDaoTest were counted in the earlier 63 → 67).

  **What's NOT done (still in the 20-test backlog):** items 1–2, 4–20 inclusive. The Castor characterisation IT subclasses (one per ODM code path: `ODMMetadataRestResource`, `ImportCRFDataServlet`, `MetaDataCollector`, `AdminDataCollector`, rule XSLT) are scaffold-ready: pick a code path, subclass `CastorCharacterisationIT`, capture a golden on first run, commit. The [Phase B execution playbook §B.0](docs/development/modernization/phase-b-execution-playbook.md#b0--castor-characterisation-tests-pre-flight) lists the five canonical code paths.

#### Integration-test authoring pattern (post Phase 0.3)

Each new test class:
1. **Extends `HibernateOcDbTestCase`** (in `core/src/test/java/at/ac/meduniwien/ophthalmology/libreclinica/templates/`). The base class opens a per-test Spring transaction in `setUp()` and rolls it back in `tearDown()` — your test writes do not leak across tests.
2. **Backed by a DBUnit XML fixture** at `core/src/test/resources/<package-as-path>/testdata/<TestClassName>.xml`. DBUnit performs `CLEAN_INSERT` against the named tables before the test runs (so the rows you declare are present + nothing else in those tables interferes). Use negative IDs (`-1`, `-2`, ...) to avoid colliding with Liquibase- or bootstrap-inserted rows.
3. **Retrieves DAO beans from the Spring context**: `(MyDao) getContext().getBean("myDao")`. Wire identifier matches the bean `id` in `applicationContext-core-hibernate.xml`.
4. **Asserts both the read path and the write path** in separate test methods (`testFindById`, `testFindByXxx`, `testSaveOrUpdate*`). Prefer container assertions (`assertTrue(allRows.contains(...))`) over size equality so the test doesn't break when Liquibase or bootstrap inserts additional rows.

Verification recipe (per test class, before commit):

```sh
docker network create lc-test-net 2>/dev/null || true
docker run -d --rm --name lc-test-pg --network lc-test-net \
  -e POSTGRES_USER=clinica -e POSTGRES_PASSWORD=clinica \
  -e POSTGRES_DB=openclinica-TEST \
  postgres:14-alpine
until docker exec lc-test-pg pg_isready -U clinica -d openclinica-TEST 2>/dev/null \
    | grep -q "accepting connections"; do sleep 1; done

docker run --rm --network lc-test-net \
  -v "$(pwd)":/app -v "$(pwd)/.m2-cache":/root/.m2 -w /app \
  maven:3-eclipse-temurin-8 \
  mvn -B -ntp -pl core -am -P integration-tests -Ddb.test=lc-test-pg clean test

docker stop lc-test-pg && docker network rm lc-test-net
```
- [ ] **`RulesPostImportContainerServiceTest`** is the only "broken" test (test method commented out years ago). Either delete or restore — currently excluded so it doesn't error the build.

#### Local-dev: running integration tests against a real Postgres

```sh
# Start a dedicated Postgres for testing (NOT compose's db, which is for the app).
docker network create lc-test-net 2>/dev/null || true
docker run -d --rm --name lc-test-pg --network lc-test-net \
  -e POSTGRES_USER=clinica -e POSTGRES_PASSWORD=clinica \
  -e POSTGRES_DB=openclinica-TEST \
  postgres:14-alpine
# Wait for ready (~5s)
docker exec lc-test-pg pg_isready -U clinica -d openclinica-TEST

# Run integration tests in the same network, pointing at lc-test-pg.
docker run --rm --network lc-test-net \
  -v "$(pwd)":/app -v "$(pwd)/.m2-cache":/root/.m2 -w /app \
  maven:3-eclipse-temurin-8 \
  mvn -B -ntp -pl core -am -P integration-tests -Ddb.test=lc-test-pg test

# Cleanup
docker stop lc-test-pg && docker network rm lc-test-net
```

Currently expect: 33 unit tests pass, ~26 DAO tests error with "Session/EntityManager is closed" (Phase 0.3). 1 test still fails as "No tests found" (`RulesPostImportContainerServiceTest`).
- [x] **Coverage tooling (2026-05-28):** JaCoCo 0.8.12 plugin wired into the parent pom (`jacoco-maven-plugin`, `prepare-agent` + `report` executions), surefire bumped 2.10 → 2.22.2 for `@{argLine}` late-property support. Coverage artifact uploaded by `.github/workflows/build.yml` (both `build` and `integration-tests` jobs); per-module instruction-coverage summary surfaced in the job log. No enforcement yet.
- [x] **OWASP dependency-check (2026-05-28):** `dependency-check-maven` 9.2.0 plugin in pluginManagement (`failBuildOnCVSS=8`, `skipTestScope=true`, HTML/SARIF/JSON output). Not bound to a build phase (first-run NVD download is slow); invoked nightly by `.github/workflows/security.yml` with an NVD database cache and optional `NVD_API_KEY` secret. SARIF results upload to the GitHub Security tab. Empty suppression file at [dependency-check-suppressions.xml](dependency-check-suppressions.xml).
- [x] **Trivy filesystem scan (2026-05-28):** `.github/workflows/security.yml` runs `aquasecurity/trivy-action` on every push + PR + daily. CRITICAL/HIGH severity, ignore-unfixed, SARIF output to Security tab. `exit-code: 0` initially (report-only) — flip to `1` after Phase A.2 lands and the backlog has been triaged.
- [x] **Gitleaks secret scan (2026-05-28):** `.github/workflows/security.yml` runs the Gitleaks GitHub Action on every push + PR. (GitHub native secret scanning is also enabled on the repo by default for free public repos and for orgs with Advanced Security; Gitleaks supplements that with custom-pattern coverage for the working tree.)
- [x] **CodeQL (2026-05-28):** `.github/workflows/codeql.yml` runs the Java analyzer with `security-extended,security-and-quality` query packs on push/PR + weekly. Manual build (`mvn compile`) rather than CodeQL autobuild to match the actual project structure (legacy plugin chain breaks autobuild detection).

**Phase exit criteria:** Green CI on `lc-develop`, all unit tests run by default, critical-path integration tests in place, smoke test reliably catches "the app doesn't start" regressions, Dependabot active.

---

## Phase A — Spring 5.x hardening (CVE patches, no namespace migration)

**Goal:** close known CVEs without crossing the `jakarta` cliff. Mergeable-forward into Phase B.
**Timeline:** 2–3 weeks (after Phase 0)
**Risk:** low–medium

Phase A is being executed in two sub-batches:

- **Phase A.1 (done 2026-05-28):** low-risk CVE-closing bumps that don't change framework API surface — verified by `mvn compile`.
- **Phase A.2 (done 2026-05-28):** higher-risk bumps (Spring framework, Spring Security, Hibernate, Quartz, commons-*) verified end-to-end against `postgres:14-alpine` via the Phase 0 integration-tests profile — **63/63 tests pass on the new stack**.

Dependency bumps (all stay on `javax.*` namespace — final 5.x line):

| Library | From | To | Phase | Status | Notes |
|---------|------|----|-------|--------|-------|
| Jackson Databind | 2.9.8 | **2.13.5** | A.1 | ✅ | Last `javax`-compatible; CVE-2020-36518, CVE-2022-42003 |
| Jackson Annotations / Core | 2.9.8 | **2.13.5** | A.1 | ✅ | Match databind |
| Logback Classic / Core | 1.1.3 | **1.2.13** | A.1 | ✅ | Final 1.2.x; CVE-2023-6378, CVE-2024-12798 |
| SLF4J | 1.7.6 | **1.7.36** | A.1 | ✅ | Final 1.7.x (still `javax`-friendly) |
| Apache Commons FileUpload | 1.3.3 | **1.5** | A.1 | ✅ | CVE-2023-24998 |
| Apache Commons IO | 2.5 | **2.16.1** | A.1 | ✅ | CVE-2024-47554 |
| Apache Commons Codec | 1.11 | **1.17.1** | A.1 | ✅ | |
| PostgreSQL JDBC | 42.2.26 | **42.7.4** | A.1 | ✅ | |
| Spring Framework | 5.1.4.RELEASE | **5.3.39** | A.2 | ✅ | Final 5.x release |
| Spring Security | 5.1.4.RELEASE | **5.8.16** | A.2 | ✅ | Final 5.x; decoupled into separate `<spring.security.version>` property |
| ~~Spring WS~~ | ~~1.5.6~~ | **removed** | B | ✅ | PR #31 (2026-05-29) — `ws/` module deleted, 3 spring-ws-* depMgmt entries dropped. README + decision: no active SOAP consumer at MUW Ophthalmology. |
| Spring OAuth2 | 2.3.5.RELEASE | (EOL since 2022; replace with Spring Authorization Server in Phase B/C) | B | ⏳ | |
| Hibernate ORM | 5.4.2.Final | **5.6.15.Final** | A.2 | ✅ | Verified end-to-end against live Postgres |
| Hibernate Validator | (audit) | **6.2.x** | A.2 | ⏳ | Not currently a direct dep |
| Apache Commons Lang | 2.3 | **2.6** (then `commons-lang3 3.14` in B) | A.2 / B | ✅ A.2 / ⏳ B | 2.6 is the final 2.x; 3.x is a package rename, Phase B |
| Apache Commons Collections | 3.2.1 | **3.2.2** *(then migrate to 4.4 in Phase B)* | A.2 | ✅ A.2 / ⏳ B | CVE-2015-7501 closed; 4.x is a package rename |
| Apache Commons Beanutils | 1.8.0 | **1.9.4** | A.2 | ✅ | |
| Apache Commons Validator | 1.3.1 | **1.9.0** | A.2 | ✅ | |
| Quartz | 2.2.3 | **2.3.2** | A.2 | ✅ | Jump to 2.5 deferred to Phase D (Jakarta-namespace) |
| EhCache | 2.10.6 | (stay) | D | ⏳ | Phase D evaluates jump to Ehcache 3 / JCache |
| JMesa | 2.4.2-oc | (stay — local fork) | E | ⏳ | Replace in Phase E |
| Apache POI | 3.0.1 | (stay — defer to D for testing) | D | ⏳ | |
| iText | 2.1.2 | (stay — license cliff, replace in D) | D | ⏳ | |
| Castor | 1.4.1 | (stay — replace in D, forced by B) | B/D | ⏳ | |

**Verification protocol per bump (must do — this is a clinical-data system):**
1. Bump version in `pom.xml`
2. `mvn -DskipTests=true compile` — must succeed
3. `mvn -DskipTests=false test` — must succeed (or document why a test failure is unrelated)
4. `mvn package` + `docker compose up` + manual smoke (login, create study, submit a trivial CRF, view audit log)
5. Commit individually, one bump per commit, descriptive message

**Phase exit criteria:** All deps on final 5.x line, CI green, smoke test green, no known-critical CVEs against direct dependencies (validated via `mvn org.owasp:dependency-check-maven:check` or Trivy).

---

## Phase B — Java 21 + Spring 6 + Jakarta cliff

**Goal:** cross the `javax.*` → `jakarta.*` namespace cliff. The highest-risk single phase.
**Timeline:** 3–6 months
**Risk:** high

**Detailed execution plan:** see [docs/development/modernization/phase-b-execution-playbook.md](docs/development/modernization/phase-b-execution-playbook.md) for the pre-flight checklist, per-sub-phase ordering, per-step verification gates, Castor characterisation strategy, and per-sub-phase risk register.

**Companion: per-dependency mapping:** [docs/development/modernization/phase-b-dependency-analysis.md](docs/development/modernization/phase-b-dependency-analysis.md).

### Sequencing

1. **Java 21 baseline first.** Bump build + runtime to JDK 21. Fix warnings. Update Maven compiler plugin. Verify everything in Phase A still works on JDK 21. (1–2 weeks)
2. **Eclipse Transformer dry run.** Run Eclipse Transformer (or IntelliJ's javax→jakarta refactor) against a throwaway branch to discover the scope of mechanical changes vs. manual reconciliation work. Capture the diff size and any unconvertible sites. (1 week)
3. **Replace Castor 1.4.1 → JAXB (or Jackson XML).** Castor has no Jakarta variant. This is a *forced* replacement and touches every CDISC ODM code path. Treated as a sub-phase. (3–4 weeks)
4. **Spring 5 → 6, Security 5 → 6** in lockstep. ~~Spring WS 3 → 4~~ N/A — `ws/` module removed in PR #31 (2026-05-29). (2–3 weeks)
5. **Hibernate 5.6 → 6.4** with `jakarta.persistence`. Highest-risk single library — HQL strictness changes, sequence generator behavior, removal of `Criteria` API. (3–4 weeks)
6. **Tomcat 9 → 10/11**. Servlet 5+ / `jakarta.servlet`. (1–2 weeks)
7. **JSTL 1.1 → 3.0** + 413 JSPs updated to new taglib URIs. Mechanical but voluminous. Eclipse Transformer handles most. (1–2 weeks)
8. **All custom `.tld` files updated** to new schema URIs and JSP version. (1 week)
9. **Final reconciliation + comprehensive regression sweep** under the new namespace. (3–4 weeks)

### Tooling

- **Eclipse Transformer** ([github.com/eclipse/transformer](https://github.com/eclipse/transformer)) — mechanical `javax` → `jakarta` package rewriting. Use this on every upstream cherry-pick from this point forward.
- **OpenRewrite** ([docs.openrewrite.org](https://docs.openrewrite.org/recipes/java/migrate)) — automated migration recipes for Spring Boot 2 → 3, Java 11 → 17 → 21, JUnit 4 → 5.
- **IntelliJ structural-replace** for harder-to-automate refactors.

### Phase B exit criteria

- Build runs on JDK 21
- All Spring dependencies on 6.x / Boot 3.x line
- Zero `javax.*` imports remain in `src/main/`
- All 413 JSPs updated to Jakarta taglib URIs
- Full integration-test suite passes (now including a richer suite from Phase 0)
- Manual GCP-style smoke pass: login → create study → enroll subject → submit CRF (initial DE + double DE) → discrepancy note → SDV → sign → export ODM → audit log spot-check

### Phase B named risks

1. **Castor → JAXB cutover** is the single most dangerous change. ODM import/export is core. Plan: write characterization tests against current Castor behavior first; ensure JAXB produces byte-equivalent ODM XML; involve a clinical-data reviewer.
2. **Hibernate 6 HQL strictness**. Some HQL queries that parse on Hibernate 5 will fail on 6. Audit every `@NamedQuery` and ad-hoc HQL/JPQL string before migration.
3. **Spring Security 6 password-encoder default change**. Existing MD5-hashed credentials need migration plan — `DelegatingPasswordEncoder` allows side-by-side support.
4. **JSP/JSTL taglib URI changes** silently break pages (no compile error, just runtime 500s). Phase 0 smoke test must cover representative JSPs.

---

## Phase C — Spring Boot 3 conversion

**Goal:** XML application contexts → Java config + Spring Boot autoconfiguration. WAR → executable JAR. Externalize config.
**Timeline:** 2–3 months
**Risk:** medium

- [ ] New `application.yml` ladder: `application.yml` (defaults) + `application-{dev,test,prod}.yml` + env-var overrides
- [ ] Migrate `applicationContext-core-db.xml` → `@Configuration` class + `spring.datasource.*` properties
- [ ] Migrate `applicationContext-core-hibernate.xml` → Boot's `JpaProperties` + `HibernateProperties`
- [ ] Migrate `applicationContext-core-security.xml` → `SecurityFilterChain` bean (already required by Spring Security 6)
- [ ] Migrate `applicationContext-core-scheduler.xml` → Boot's Quartz starter
- [ ] Migrate `applicationContext-core-service.xml` → component-scanning + explicit `@Bean` declarations
- [ ] Migrate `applicationContext-core-email.xml` → `spring.mail.*` properties
- [ ] Migrate `pages-servlet.xml` → `WebMvcConfigurer` bean
- [ ] Migrate `web.xml` filters/listeners → `FilterRegistrationBean` / `ServletListenerRegistrationBean`
- [ ] Switch packaging `war` → `jar` (or keep `war` for traditional Tomcat deploy option + executable wrapper)
- [ ] Replace `datainfo.properties` substitution with `@ConfigurationProperties` classes
- [ ] Add Spring Boot Actuator: `/actuator/health`, `/actuator/info`, gated by Spring Security
- [ ] Update Dockerfile: drop multi-stage Tomcat copy, use `eclipse-temurin:21-jre` + `java -jar app.jar`
- [ ] Update `compose.yaml` accordingly

**Phase C exit criteria:** App boots as `java -jar`, all config sourced from `application.yml` + env vars (no XML beans remaining), Actuator healthy, regression suite green.

---

## Phase D — Remaining library replacement

**Goal:** retire the abandoned-library long tail.
**Timeline:** 2–3 months (can overlap C)
**Risk:** medium per library — but they're independent so risk doesn't compound

| Library | Replacement | Touchpoints | Notes |
|---------|------------|-----------|------|
| Castor 1.4.1 | **Jakarta JAXB or Jackson XML** | ODM import/export (`odm` module, `ImportCRFDataServlet`, `ODMMetadataRestResource`) | **Forced by Phase B** — done as part of B. Listed here for completeness. |
| iText 2.1.2 | **OpenPDF 1.4+** (LGPL fork of iText 4) or **Apache PDFBox 3.x** | PDF generation (audit logs, subject reports, CRF blank prints) | License-driven (post-2.1 iText is AGPL). OpenPDF is the drop-in replacement. |
| Apache POI 3.0.1 | **Apache POI 5.3** | Excel CRF upload, Excel exports | API changes: HSSFWorkbook → XSSF in places. Surface area not huge. |
| Apache FOP 1.0 | **Apache FOP 2.9** | XSL-FO → PDF (some reports) | |
| Quartz 2.2.3 | **Quartz 2.5.0** | Scheduled jobs (cleanup, notifications, recurring exports) | |
| GWT-compiled menu widget | **removed** — replace with vanilla HTML / framework-native nav | Top nav bar | GWT is abandoned. Compile artifact only; source presumably elsewhere. Treat as opaque static asset to be replaced. |
| Prototype.js 1.6 / Scriptaculous | **removed** | ~20 JSP screens using `$()` / `Effect.*` | Either rewrite in vanilla JS or rely on jQuery 3 (already present via JMesa). |
| EhCache 2.10 | **Ehcache 3 / JCache** (`javax.cache` → `jakarta.cache`) | Hibernate L2 cache | Re-evaluate need; consider Caffeine + JCache. |
| log4jdbc4 1.2 (abandoned) | **log4jdbc-log4j2 1.16** or remove | SQL logging | Optional; only active in dev profiles. |
| JMesa 2.4.2-oc (local OC fork) | **DataTables.net 2.x** (during Phase E) | All admin tables | Defer to UI phase. |

**Per-library protocol:** characterization tests first → replacement library introduced behind interface → switch over → delete old dep. One library per PR, never bundle replacements.

---

## Phase E — UI modernization

**Status:** deferred until Phase D ships. See [`docs/development/modernization/ui-modernization-plan.md`](docs/development/modernization/ui-modernization-plan.md) (TODO: copy from memory).

Hybrid SPA approach planned: React or Vue 3 for high-traffic clinician screens (data entry, dashboards, subject/study lists, discrepancy review), JSP retained for admin/low-frequency screens.

**Feature-parity baseline:** [`docs/development/modernization/phase-e/`](docs/development/modernization/phase-e/) — live-walkthrough catalogue of every UI feature reachable today as Investigator, Monitor, and Data Manager, with screenshots and servlet-to-class cross-references. The SPA rewrite must preserve every feature listed there unless explicitly retired.

**Known issues to investigate before / during Phase E:** [`docs/development/modernization/phase-e/known-issues.md`](docs/development/modernization/phase-e/known-issues.md) — runtime bugs in the existing UI that the modernization safety net has surfaced (e.g. `/pages/login/login` returning HTTP 500 as of 2026-05-28). Each entry must resolve to either a fix or an explicit "feature retired" decision before the SPA inherits the baseline.

---

## Cross-cutting workstreams

### Branding (Phase 0)

- Maven `groupId` `org.libreclinica` → **`at.ac.meduniwien.ophthalmology.libreclinica`**
- Java package `org.akaza.openclinica` / `org.libreclinica`: **unchanged in Phase 0/A** (institutional Java package rename is Phase B work — done while every file is being touched for `jakarta` migration anyway)
- README: MUW Ophthalmology framing with acknowledgement of LibreClinica & OpenClinica heritage
- `Dockerfile` redirect page, login page chrome, WAR name, page titles: **"LibreClinica MUW Ophthalmology"** / **"Department of Ophthalmology and Optometry · Medical University of Vienna"**

### Test coverage uplift

Continuous through all phases. Target end-of-Phase-B: 30% line coverage on `core/`. Critical-path integration tests are added in Phase 0 and grow throughout.

### Upstream merge protocol (hard fork)

1. Watch `reliatec-gmbh/LibreClinica:lc-develop` for new commits.
2. For each commit deemed relevant (bug fix, security patch):
   - Cherry-pick into a `cherrypick/upstream-<sha>` branch.
   - Run Eclipse Transformer over the diff (post Phase B) to translate `javax.*` → `jakarta.*`.
   - Manual reconciliation for institutional divergence.
   - PR into `lc-develop` with `upstream:` prefix in title.
3. **We do not push downstream changes upstream** unless explicitly negotiated with ReliaTec.
4. Document each cherry-pick decision in `docs/development/modernization/upstream-merges.md`.

### CVE / vulnerability monitoring

- Dependabot weekly (configured in [.github/dependabot.yml](.github/dependabot.yml))
- Trivy or `dependency-check-maven` in CI (Phase 0 follow-up)
- GitHub Advanced Security CodeQL (Phase 0 follow-up)

### Database migration discipline

- Every schema change in Liquibase, under `core/src/main/resources/migration/lc-muw-<yyyy-mm-dd>-<topic>.xml`.
- Never edit existing changesets — only append new ones.
- Migration run in CI against an ephemeral Postgres container.

---

## Risk register

| ID | Risk | Likelihood | Impact | Mitigation |
|----|------|----|----|----|
| R1 | Castor → JAXB cutover produces non-identical ODM XML, breaks regulatory interoperability | M | H | Characterization-test against current Castor output; byte-equivalence check; clinical-data reviewer sign-off |
| R2 | Hibernate 6 HQL strictness silently breaks queries at runtime | M | H | Audit every HQL string before migration; integration tests over all DAO methods |
| R3 | Spring Security 6 `PasswordEncoder` default change breaks existing user logins | H | H | `DelegatingPasswordEncoder` strategy supporting MD5 (legacy) + bcrypt (new); upgrade-on-next-login pattern |
| R4 | Loss of key developer mid-Phase-B | M | H | Document everything in this file + decision records; pair programming on highest-risk changes |
| R5 | First clinical trial goes live before Phase D ships, forcing freeze on modernization | L | M | Confirmed not a concern (2026-05-28 — no trial deadline) |
| R6 | Upstream ReliaTec also migrates to Jakarta, making divergence un-necessary | L | L | Accept; we retain optionality to re-converge later |
| R7 | Validation overhead per phase blows out timeline | M | M | Plan validation cycles per phase exit; not per dep bump |
| R8 | GWT menu widget cannot be cleanly replaced without UI regressions | M | M | Replace before Phase E (during D); manual click-through QA |

---

## Decision log

See [docs/development/modernization/decision-record.md](docs/development/modernization/decision-record.md).

---

## Acknowledgements

LibreClinica is the work of the LibreClinica community, primarily ReliaTec GmbH, building on OpenClinica 3.14. This MUW Ophthalmology fork retains the LGPL license of the upstream project and acknowledges all upstream contributors. See [README.md](README.md).
