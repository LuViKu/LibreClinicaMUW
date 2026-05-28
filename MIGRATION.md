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
| Spring WS | 3.0.10 | **4.x** | B (or removed) |
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
- [ ] **Add critical-path integration tests** (~20 tests, target ~2 weeks): login, study CRUD, subject enroll, scheduled event, CRF submission (initial DE + double DE), discrepancy note, ODM export, audit log row written, SDV state transition. Use Spring Test + Testcontainers (PostgreSQL 14).
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
- [ ] **Coverage tooling**: add JaCoCo report, surface coverage in CI summary. Target: track baseline, no enforcement yet.
- [ ] **Secret scan**: add Trivy or GitHub native secret scan to CI.
- [ ] **CodeQL**: enable GitHub Advanced Security CodeQL workflow for Java.

**Phase exit criteria:** Green CI on `lc-develop`, all unit tests run by default, critical-path integration tests in place, smoke test reliably catches "the app doesn't start" regressions, Dependabot active.

---

## Phase A — Spring 5.x hardening (CVE patches, no namespace migration)

**Goal:** close known CVEs without crossing the `jakarta` cliff. Mergeable-forward into Phase B.
**Timeline:** 2–3 weeks (after Phase 0)
**Risk:** low–medium

Phase A is being executed in two sub-batches:

- **Phase A.1 (done 2026-05-28):** low-risk CVE-closing bumps that don't change framework API surface — verified by `mvn compile`.
- **Phase A.2 (pending):** higher-risk bumps (Spring framework, Spring Security, Hibernate, Quartz) that need the Phase 0 unit-test suite to be enabled and green before they can be safely verified.

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
| Spring Framework | 5.1.4.RELEASE | **5.3.39** | A.2 | ⏳ | Final 5.x release; many CVE fixes |
| Spring Security | 5.1.4.RELEASE | **5.8.16** | A.2 | ⏳ | Final 5.x line; CVE-2024-22243, CVE-2023-34035 |
| Spring WS | 1.5.6 | (audit — Spring WS 1.5 is from 2008; jump to 4.x during Phase B) | B | ⏳ | |
| Spring OAuth2 | 2.3.5.RELEASE | (EOL since 2022; replace with Spring Authorization Server in Phase B/C) | B | ⏳ | |
| Hibernate ORM | 5.4.2.Final | **5.6.15.Final** | A.2 | ⏳ | Many fixes; dialect updates for PG ≥ 14 |
| Hibernate Validator | (audit) | **6.2.x** | A.2 | ⏳ | |
| Apache Commons Lang | 2.3 | **commons-lang3 3.14** | A.2 / B | ⏳ | Package rename `org.apache.commons.lang` → `org.apache.commons.lang3` |
| Apache Commons Collections | 3.2.1 | **3.2.2** *(or migrate to 4.4)* | A.2 | ⏳ | CVE-2015-7501 |
| Apache Commons Validator | 1.3.1 | **1.9.0** | A.2 | ⏳ | |
| Quartz | 2.2.3 | **2.3.2** | A.2 | ⏳ | Stay on 2.3 line in Phase A; jump to 2.5 in Phase D |
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

### Sequencing

1. **Java 21 baseline first.** Bump build + runtime to JDK 21. Fix warnings. Update Maven compiler plugin. Verify everything in Phase A still works on JDK 21. (1–2 weeks)
2. **Eclipse Transformer dry run.** Run Eclipse Transformer (or IntelliJ's javax→jakarta refactor) against a throwaway branch to discover the scope of mechanical changes vs. manual reconciliation work. Capture the diff size and any unconvertible sites. (1 week)
3. **Replace Castor 1.4.1 → JAXB (or Jackson XML).** Castor has no Jakarta variant. This is a *forced* replacement and touches every CDISC ODM code path. Treated as a sub-phase. (3–4 weeks)
4. **Spring 5 → 6, Security 5 → 6, WS 3 → 4** in lockstep. (2–3 weeks)
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
