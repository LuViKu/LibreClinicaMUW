# Phase C — Spring Boot 3 Conversion Playbook

**Status:** ready to start (Phase B fully closed; `lc-develop` HEAD `106ba5f52` carries Java 21 + Spring 6.1.18 + Spring Security 6.3.6 + Hibernate 6.4.10 + JPA EntityManager + jakarta.* + Tomcat 10 + `at.ac.meduniwien.ophthalmology.libreclinica.*` package namespace).
**Scope:** convert the 9 `applicationContext-core-*.xml` Spring XML bean configurations + `web.xml` filter/listener registrations to Java `@Configuration` / Spring Boot autoconfiguration. WAR → executable JAR with embedded Tomcat. Externalise runtime config to `application.yml` + env vars.
**Target timeline:** 2–3 months, 1–2 devs FTE-equivalent. Per [MIGRATION.md § Phase C](../../../MIGRATION.md#phase-c--spring-boot-3-conversion).
**Companion docs:** [phase-c-and-d-autonomous-brief.md](../../../../phase-c-and-d-autonomous-brief.md) (decisions ratified 2026-05-28 by Lukas), [decision-record.md](decision-record.md) (DR-014 Shibboleth — picked up by Phase D, not C).

This playbook turns Phase C into a step-by-step plan with per-step verification gates. Each step lives on its own branch and is verified before merging to `lc-develop`. The autonomous brief contains the high-level shape; this document is the operational version.

---

## Decisions already ratified (2026-05-28 by Lukas)

These are binding inputs — do not re-litigate during execution:

1. **Spring Boot target = 3.5.x** (latest GA at session start). Pin to the patch version available on the day of execution.
2. **Packaging = executable JAR with embedded Tomcat.** Drop the `<packaging>war</packaging>` + external Tomcat 10 deployment. Spring Boot's bundled Tomcat takes over.
3. **PostgreSQL JDBC URL hardening** = ADD `sslMode=verify-full` + `scramMaxIterations=10000` as part of the Spring Boot config externalisation (Phase C owns this; CVE-2025-49146 + CVE-2026-42198). The Postgres server must support the SSL cert + the SCRAM iteration count before deploying — operator gate.
4. **Auth + SSO (Spring Authorization Server, Shibboleth, password-encoder format) is Phase D territory, not C.** Phase C delivers boot-on-`java -jar` + externalised config; Phase D rewires the authentication layer.

---

## Pre-flight checklist

Phase C does not start until *all* of the following are true:

- [x] Phase B fully closed (`lc-develop` HEAD `106ba5f52`; B.0–B.12 all merged; H6+JPA verified end-to-end).
- [x] Phase B exit criterion #5 (full integration-test suite passes) verified: 93/93 ITs + 48 unit tests on the new stack.
- [ ] **C.0 characterisation tests** in place (see [§C.0](#c0--bootstartup-contract-characterisation-tests)). Without these, the XML→Java config swap can't be reviewed — any silent bean-graph change is invisible.
- [ ] An institutional pre-Phase-C snapshot tag: `git tag -a pre-phase-c -m "Phase B closed; H6+JPA + Spring 6.1 on Tomcat 10"` and pushed.
- [ ] A dedicated Phase C integration branch: `feature/phase-c-spring-boot-3` off `lc-develop`.

The autonomous brief also flagged the open Phase 0 backlog (15 of 20 critical-path ITs still pending: `CrfDataEntryIT`, `DiscrepancyNoteIT`, `SdvIT`, `OdmExportIT`, `OdmImportIT`, `RandomizationRuleIT`, `StudyLockIT`, etc.). **These are NOT a hard prerequisite for Phase C** — they exist as belt-and-braces regression coverage, but the existing 93-IT suite already pins the data-flow contract Phase C must preserve. Treat them as backlog to pick up *if* a specific sub-phase exposes a need.

---

## Sub-phase ordering and verification gates

Run sub-phases in this order. Each lives on a child branch off `feature/phase-c-spring-boot-3`, is verified independently, and merges back when its gate is green.

| # | Sub-phase | Branch suffix | Gate (must be green to merge) | Est. effort |
|---|-----------|---------------|-------------------------------|-------------|
| C.0 | Boot/startup contract characterisation tests | `boot-contract-tests` | New tests pin: Spring context bootstrap, datasource bean shape, security filter chain, Quartz scheduler beans, Liquibase changelog count, JpaProperties, key DAO autowire wires | 3–5 days |
| C.1 | Add Spring Boot starters to the parent POM | `boot-starters` | `mvn -B clean package -DskipTests` green; analyze-only clean; integration tests still 93/93 | 1–2 days |
| C.2 | Bootstrap `@SpringBootApplication` + `application.yml` ladder | `boot-application` | `java -jar` produces a runnable app; HTTP 302 on `/MainMenu` (still via XML beans loaded by Boot's legacy-XML import); compose smoke green | 2–3 days |
| C.3 | Migrate `applicationContext-core-db.xml` → `DataSourceConfig` (`@Configuration`) | `boot-config-db` | C.0 datasource characterisation test passes; integration tests 93/93; JDBC URL includes `sslMode=verify-full` + `scramMaxIterations=10000` | 1–2 days |
| C.4 | Migrate `applicationContext-core-hibernate.xml` → `JpaConfig` (use Boot's `JpaProperties` + `HibernateProperties`) | `boot-config-jpa` | C.0 JpaConfig characterisation test + integration tests 93/93 | 2–3 days |
| C.5 | Migrate `applicationContext-core-scheduler.xml` → Spring Boot Quartz starter (`spring-boot-starter-quartz`) | `boot-config-quartz` | C.0 scheduler characterisation test + a smoke that registers and fires a Quartz trigger | 2 days |
| C.6 | Migrate `applicationContext-core-service.xml` → component-scanning + explicit `@Bean` for non-component services | `boot-config-services` | Integration tests 93/93; smoke green | 2–3 days |
| C.7 | Migrate `applicationContext-core-email.xml` → `spring.mail.*` properties + Boot's `JavaMailSender` autoconfig | `boot-config-mail` | `MailNotificationServiceTest` (3 tests) + `TwoFactorServiceTest` (14 tests) green; smoke green | 1 day |
| C.8 | Migrate `applicationContext-core-spring.xml` (legacy beans + the `s[...]` placeholder configurer) → `@ConfigurationProperties` + `application.yml` | `boot-config-spring` | Integration tests 93/93; all `s[...]` substitutions resolved by Boot's standard property resolution | 2–3 days |
| C.9 | Migrate `applicationContext-core-timer.xml` (if any non-Quartz schedules remain) | `boot-config-timer` | All scheduled jobs still fire | 1 day |
| C.10 | Migrate `applicationContext-security.xml` + `applicationContext-core-security.xml` → `SecurityFilterChain` bean (Java config), keep XML `<security:http>` *as a fallback* until Phase D rewrites auth | `boot-config-security` | login flow curl-verified end-to-end; integration tests 93/93 | 3–4 days |
| C.11 | Migrate `pages-servlet.xml` → `WebMvcConfigurer` bean + Boot's MVC autoconfig | `boot-config-mvc` | All MVC endpoints (login, MainMenu, ListSubject, AuditUserActivityData) curl-verified | 2 days |
| C.12 | Migrate `web.xml` filters/listeners → `FilterRegistrationBean` / `ServletListenerRegistrationBean` (OpenEntityManagerInViewFilter, springSecurityFilterChain, OCServletFilter, OCContextLoaderListener) | `boot-config-web` | All filters still hit (auth filter chain order preserved); integration tests + smoke green | 1–2 days |
| C.13 | Liquibase via `spring-boot-starter-liquibase` (replace `SpringLiquibase` bean) | `boot-liquibase` | Liquibase still applies all changesets on cold boot; changeset count matches pre-C.13 baseline | 1 day |
| C.14 | Switch packaging `war` → `jar`; embed Tomcat (drop multi-stage Tomcat copy; use `eclipse-temurin:21-jre` + `java -jar`) | `boot-jar` | Single `java -jar app.jar` boots the full stack; compose smoke green; container size shrinks | 1–2 days |
| C.15 | Add Spring Boot Actuator (`/actuator/health` + `/actuator/info`, secured by `SecurityFilterChain`) | `boot-actuator` | `/actuator/health` returns `UP`; gated by auth | 1 day |
| C.16 | Reconciliation sweep: drop now-unused XML files, drop `web.xml`, update Dockerfile + compose.yaml | `boot-reconciliation` | All non-Boot bootstrap files removed; smoke + integration tests + manual GCP-style smoke green | 3–4 days |

Total: ~6 weeks FTE-equivalent. C.3 through C.10 can run in parallel after C.2 lands.

---

## C.0 — Boot/startup contract characterisation tests (pre-flight)

The single highest-risk change in Phase C is silently dropping a bean during XML→Java config conversion. The C.0 test suite pins the current bean graph so any drift surfaces in CI, not in production.

### What to characterise

Each test sits under `core/src/test/java/at/ac/meduniwien/ophthalmology/libreclinica/contract/`, uses `@RunWith(SpringJUnit4ClassRunner.class)` + `@ContextConfiguration` loading the current XML configs, and pins the bean graph contract:

| # | Test class | Pins |
|---|-----------|------|
| C.0.1 | `BootstrapContractTest` | Spring context loads; the 9 `applicationContext-core-*.xml` + `applicationContext-security.xml` files all resolve; bean count in the root context within a +/- 5% tolerance |
| C.0.2 | `DataSourceContractTest` | `dataSource` bean is a `BasicDataSource` (or `HikariDataSource` after Boot conversion); URL points at the configured PG host; pool sized as documented |
| C.0.3 | `JpaContractTest` | `entityManagerFactory` bean is a `LocalContainerEntityManagerFactoryBean`; `transactionManager` is `JpaTransactionManager`; `hibernate.dialect` = the PG dialect; the @Entity scan resolves the same N classes as the explicit list in HEAD |
| C.0.4 | `SecurityFilterChainContractTest` | Spring Security's filter chain has the documented order (per `applicationContext-security.xml` `<security:http>` block): `LogoutFilter` → `myFilter` (OpenClinicaUsernamePasswordAuthenticationFilter) → `concurrencyFilter` → `RequestCacheAwareFilter` → `SecurityContextHolderAwareRequestFilter` → `AnonymousAuthenticationFilter` → `ExceptionTranslationFilter` → `AuthorizationFilter` |
| C.0.5 | `QuartzSchedulerContractTest` | The `scheduler` bean is a `Scheduler` (Quartz `StdScheduler`); all expected JobDetail beans are present (extract-data, xslt jobs); the scheduler is in started state after context init |
| C.0.6 | `LiquibaseContractTest` | The `liquibase` bean is a `SpringLiquibase`; `changeLog` points at `classpath:db.changelog/db.changelog-master.xml`; on a clean DB, exactly N changesets apply (N = current count) |
| C.0.7 | `DaoWiringContractTest` | A representative set of DAOs (~20 of the ~150) resolve from the context with their `entityManager` field non-null and `getCurrentSession()` returns a working Session |
| C.0.8 | `MailContractTest` | `mailSender` bean is a `JavaMailSenderImpl`; host/port/protocol match `datainfo.properties`; `openClinicaMailSender` wires it correctly |
| C.0.9 | `PlaceholderContractTest` | The custom `s[propertyName]` placeholder syntax resolves from `datainfo.properties` (this dies after C.8 — that's the test's job, to fail loudly when C.8 lands without a replacement) |
| C.0.10 | `WebXmlFilterOrderContractTest` | The filter chain order from web.xml matches the documented order (OpenEntityManagerInViewFilter → springSecurityFilterChain → logFilter → page filters); used by C.12 as the gate |

These tests do NOT use a real DB or mail server — they use the bean factory only. They run in the unit-test profile (no `-P integration-tests`) so they gate every Phase C sub-phase merge.

### Acceptance gate

`mvn -B test` runs all C.0 tests on the current HEAD → all green. Re-run after every Phase C sub-phase merge. Any test that fails is either:
- a deliberate change the sub-phase introduces (update the test in the same commit), or
- a silent bean-graph regression (block the merge, fix the underlying issue).

---

## Risk register

| ID | Risk | Mitigation |
|----|------|------------|
| RC1 | Datasource pool sizing drifts during Spring Boot autoconfig takeover | C.0.2 pins the pool configuration; explicit `spring.datasource.hikari.*` in `application.yml` matching current `commons-dbcp` settings |
| RC2 | Quartz scheduler restart semantics change (in-memory → JDBC-backed by `MultiSchemaJobStoreTx`) | C.5 keeps the existing `MultiSchemaJobStoreTx` job store; C.0.5 pins the bean shape; cluster-mode `org.quartz.scheduler.instanceId=AUTO` documented |
| RC3 | Compose smoke breaks on JAR-vs-WAR switch | C.14 lives on its own branch; compose.yaml updated in same commit; rollback by reverting compose.yaml + Dockerfile only |
| RC4 | Liquibase changelog order changes when Boot autoconfig takes over | C.0.6 pins the expected changeset count; `spring.liquibase.change-log` points at the same master file the SpringLiquibase bean did |
| RC5 | `s[...]` custom placeholder syntax doesn't resolve in Boot's standard property resolution | C.0.9 fails when C.8 lands without converting all `s[...]` sites to `${...}`; do C.8 as a single touch sweep |
| RC6 | SecurityFilterChain Java config drops a filter that the XML `<security:http>` had | C.0.4 pins the filter chain order verbatim; C.10 mechanical translation of every `<security:http>` child element to its `HttpSecurity.*` builder equivalent |
| RC7 | Spring Boot 3.5 defaults (e.g. logback config takeover) collide with the existing `logback.xml` under `docker/config/` | C.2 sets `logging.config=classpath:logback.xml` explicitly in `application.yml` to preserve the existing config |
| RC8 | Embedded Tomcat differs from external Tomcat 10 in CATALINA_OPTS / module-access flags | C.14 maps the existing `--add-opens` / `--add-exports` flags to `JAVA_TOOL_OPTIONS` environment variable in the Dockerfile; verify with Java 21 `--show-module-resolution` |
| RC9 | A `pages-servlet.xml` MVC bean isn't covered by Boot's autoconfig defaults | C.11 mirrors every `pages-servlet.xml` bean as a `@Bean` method in `WebMvcConfig`; integration tests catch any missed handler mapping |
| RC10 | Castor was retired in B.3, but `cd_odm_mapping.xml` + remnant `jaxbMarshaller` config in `pages-servlet.xml` may still reference removed paths | C.11 cleanup; C.0.7 fixture covers the JAXB marshaller bean |

---

## Rollback strategy

- Each sub-phase lives on its own branch and merges via fast-forward only after its gate is green.
- If a sub-phase merge breaks `lc-develop`, `git revert <merge-commit>` rolls back cleanly.
- The `pre-phase-c` tag is the global safety net.
- **The XML configs are kept on-disk until C.16's reconciliation sweep.** Spring Boot can load both XML and Java config simultaneously; the migration runs Java config + XML side-by-side until C.16 confirms every former-XML bean is reachable via Java config alone.

---

## Per-sub-phase: shopping list of concrete changes

This section is the executive summary of *what* each sub-phase touches. Use this with `git grep` to estimate scope before starting each branch.

### C.1 — Spring Boot starters in pom.xml

Parent POM (root) `<dependencyManagement>` gains:
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-dependencies</artifactId>
    <version>${spring-boot.version}</version>
    <type>pom</type>
    <scope>import</scope>
</dependency>
```

Set `<spring-boot.version>3.5.x</spring-boot.version>` (pin to latest patch on execution day). Spring Boot BOM transitively manages Spring 6.1.x, Spring Security 6.3.x, Hibernate 6.4.x — matches what `lc-develop` already has. No version conflicts expected.

`core/pom.xml` and `web/pom.xml` add:
- `spring-boot-starter-web` (replaces `spring-webmvc` + `spring-web` for the WebMvc bootstrap)
- `spring-boot-starter-data-jpa` (replaces `spring-orm` + `jakarta.persistence-api`)
- `spring-boot-starter-security`
- `spring-boot-starter-mail`
- `spring-boot-starter-quartz` (replaces the explicit `org.quartz-scheduler:quartz` dep + the Spring `SchedulerFactoryBean` wiring)
- `spring-boot-starter-validation`
- `spring-boot-starter-liquibase` (replaces the standalone `liquibase-core` runtime dep)
- `spring-boot-starter-test` (test scope; transitively brings JUnit Jupiter, Mockito, AssertJ, Spring Test)

Drop now-redundant explicit deps: `spring-webmvc`, `spring-web`, `spring-orm`, `spring-context-support`, `spring-test`, `org.quartz-scheduler:quartz`, `liquibase-core`, `org.springframework.security:spring-security-*` (now via the starter).

### C.2 — `@SpringBootApplication` entrypoint + `application.yml`

New file: `web/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/LibreClinicaApplication.java`:
```java
@SpringBootApplication
@ImportResource({
    // Phase C: legacy XML configs loaded by Spring Boot until C.3-C.13 migrate
    // each one to Java config. C.16 will drop these imports after the
    // reconciliation sweep confirms every former-XML bean is reachable
    // via Java config alone.
    "classpath:at/ac/meduniwien/ophthalmology/libreclinica/applicationContext-core-security.xml",
    "classpath:at/ac/meduniwien/ophthalmology/libreclinica/applicationContext-security.xml",
    "classpath:at/ac/meduniwien/ophthalmology/libreclinica/applicationContext-web-beans.xml",
    "classpath:at/ac/meduniwien/ophthalmology/libreclinica/application-context-web-beans.xml",
    "classpath:at/ac/meduniwien/ophthalmology/libreclinica/applicationContext-core-timer.xml",
    "classpath:at/ac/meduniwien/ophthalmology/libreclinica/applicationContext-core-spring.xml",
    "classpath:at/ac/meduniwien/ophthalmology/libreclinica/applicationContext-core-service.xml",
    "classpath:at/ac/meduniwien/ophthalmology/libreclinica/applicationContext-core-scheduler.xml",
    "classpath:at/ac/meduniwien/ophthalmology/libreclinica/applicationContext-core-hibernate.xml",
    "classpath:at/ac/meduniwien/ophthalmology/libreclinica/applicationContext-core-email.xml",
    "classpath:at/ac/meduniwien/ophthalmology/libreclinica/applicationContext-core-db.xml",
})
public class LibreClinicaApplication {
    public static void main(String[] args) {
        SpringApplication.run(LibreClinicaApplication.class, args);
    }
}
```

New file: `web/src/main/resources/application.yml`:
```yaml
spring:
  application:
    name: libreclinica
  datasource:
    url: ${LIBRECLINICA_DB_URL:jdbc:postgresql://db:5432/libreclinica?sslMode=verify-full&scramMaxIterations=10000}
    username: ${LIBRECLINICA_DB_USER:clinica}
    password: ${LIBRECLINICA_DB_PASSWORD}
  jpa:
    properties:
      hibernate:
        dialect: org.hibernate.dialect.PostgreSQLDialect
        implicit_naming_strategy: at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.cfg.LegacyImprovedImplicitNamingStrategy
        id:
          optimizer:
            pooled:
              preferred: none
          sequence:
            increment_size_mismatch_strategy: LOG
        cache:
          use_query_cache: true
          use_second_level_cache: true
          region:
            factory_class: jcache
        javax:
          cache:
            provider: org.ehcache.jsr107.EhcacheCachingProvider
            missing_cache_strategy: create
  mail:
    host: ${LIBRECLINICA_SMTP_HOST:smtp}
    port: ${LIBRECLINICA_SMTP_PORT:1025}
  liquibase:
    change-log: classpath:migration/db.changelog-master.xml
logging:
  config: classpath:logback.xml
server:
  servlet:
    context-path: /LibreClinica
  port: 8080
```

`application-dev.yml`, `application-test.yml`, `application-prod.yml`: profile-specific overrides (dev defaults to mailcrab SMTP, test uses `openclinica-TEST` DB, prod requires all env vars).

### C.3-C.13 — Sub-phase outlines (see [§ Sub-phase ordering](#sub-phase-ordering-and-verification-gates) above)

Each sub-phase removes one XML file or one cluster of XML beans, replacing it with a `@Configuration`-annotated class in `core/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/config/` or in the corresponding domain module's `.config` package. The XML file is kept on disk and the `@ImportResource` entry kept until C.16 confirms the Java config replaces it cleanly.

### C.14 — WAR → JAR + embedded Tomcat

`pom.xml` change: `<packaging>jar</packaging>` (in `web/pom.xml`); add `spring-boot-maven-plugin` to produce the executable JAR. Drop `<finalName>` overrides — Boot conventions take over.

Dockerfile rewrite (rough shape):
```dockerfile
FROM maven:3-eclipse-temurin-21 AS builder
# ... existing build layer ...
RUN mvn -B package
RUN cp web/target/LibreClinica-web-*.jar /app.jar

FROM eclipse-temurin:21-jre
LABEL org.opencontainers.image.title="LibreClinica MUW Ophthalmology"
ENV JAVA_TOOL_OPTIONS="\
    --add-opens=java.base/java.lang=ALL-UNNAMED \
    --add-opens=java.base/java.lang.invoke=ALL-UNNAMED \
    --add-opens=java.base/java.lang.reflect=ALL-UNNAMED \
    --add-opens=java.base/java.util=ALL-UNNAMED \
    --add-exports=java.naming/com.sun.jndi.ldap=ALL-UNNAMED"
COPY --from=builder /app.jar /app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app.jar"]
```

compose.yaml: drop the `libreclinica.config` volume mount (Spring Boot reads `application.yml` from the JAR + env vars instead); keep the `libreclinica.data` mount.

### C.16 — Reconciliation sweep

- Delete all 9 `applicationContext-core-*.xml` + `applicationContext-security.xml` + `applicationContext-web-beans.xml` + `application-context-web-beans.xml` + `pages-servlet.xml` + `createSubject-servlet-config.xml` files.
- Delete `web.xml`.
- Drop the `@ImportResource` from `LibreClinicaApplication`.
- Drop `docker/config/datainfo.properties` (now sourced from env vars + `application-{profile}.yml`).
- Update `CLAUDE.md` and `MIGRATION.md` to reflect the post-C state (XML configs removed; Spring Boot 3.5; JAR packaging).
- Cleanup pass on any now-dead Spring config helpers (`CustomPropertyPlaceholderConfigurer` if any, etc.).

---

## Exit criteria

Phase C is complete when:

- [ ] App boots via `java -jar libreclinica.jar` with no external Tomcat.
- [ ] All config sourced from `application.yml` + env vars + profile-specific overrides; no XML bean files in `core/src/main/resources/` or `web/src/main/resources/`.
- [ ] `web.xml` deleted.
- [ ] Spring Boot Actuator `/actuator/health` returns `UP`, gated by Spring Security.
- [ ] Unit-test suite (48 tests) green.
- [ ] Integration-test suite (93 tests) green on the same `postgres:14-alpine` test rig.
- [ ] All C.0 characterisation tests still pass against the post-C bean graph (they SHOULD pass — the contract was preserved).
- [ ] Compose smoke green: `docker compose up --build` → HTTP 302 on `/LibreClinica/` → 200 on `/pages/login/login`.
- [ ] curl-verified GCP-style smoke: login as root → authenticated MainMenu → at least one DataServlet JSON endpoint returns real DB-backed data.
- [ ] Manual operator smoke (carried over from Phase B): login → create study → enrol subject → submit CRF → discrepancy note → SDV → sign → export ODM → audit log spot-check.

---

## Reference

- [MIGRATION.md § Phase C](../../../MIGRATION.md#phase-c--spring-boot-3-conversion) — strategic plan
- [phase-c-and-d-autonomous-brief.md](../../../../phase-c-and-d-autonomous-brief.md) — ratified decisions
- [decision-record.md](decision-record.md) — DR-014 (Phase D Shibboleth) for context only
- [phase-b-execution-playbook.md](phase-b-execution-playbook.md) — playbook template
- `lc-develop` HEAD `106ba5f52` — the post-Phase-B baseline this playbook builds on
