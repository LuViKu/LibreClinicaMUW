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

### Observed C.8 interlock (added 2026-05-29 after the autonomous C.0–C.3 pass)

**C.3 through C.10 are NOT cleanly independent of C.8.** The custom
`PropertyPlaceholderConfigurer` (in `applicationContext-core-spring.xml`)
uses the `s[propertyName]` placeholder syntax, sourced from
`coreResources.getDataInfo()` — a `java.util.Properties` produced by
`CoreResources` from `datainfo.properties` plus derived values
(`driver` / `url` from `dbType` + `dbHost` + `dbPort` + `db`,
`hibernate.dialect`, Quartz job-store properties, etc.). Every XML bean
that consumes a placeholder (the `dataSource`, the Hibernate session
factory's `hibernate.dialect`, the mail sender's host/port, the
scheduler properties) is implicitly bound to this configurer.

A Java `@Configuration` class can't `@Value("s[propertyName]")` —
Spring's `Environment` resolves only `${...}` placeholders. So a
proper conversion of any of these bean groups (C.3 dataSource,
C.4 EMF, C.5 scheduler, C.7 mailSender) needs the placeholder retire
first, OR needs to inline the derived value at the call site.

**Practical execution order** (a refinement of the playbook table):

1. C.0 — contract tests (done; landed `3136593e6`)
2. C.1 — Spring Boot BOM + starters (done; landed `717ca3df9`)
3. C.2 — `LibreClinicaApplication` dormant (done; landed `413c05d71`)
4. C.3 partial — optional JDBC URL hardening (done; landed `a7bc9d3ec`).
5. **C.8** — `s[…]` custom placeholder configurer retired in favour of
   Spring's standard `${…}` (done; landed `feaaa6245`). 24 occurrences swept
   across 5 core XML files + 11 `@Value` annotations in `LdapUserService` +
   the test config mirror.
6. **C.7** — mail bean trio → `MailConfig` Java `@Configuration` (done;
   landed `3708a24d4`). XML kept as one-line stub.
7. **C.5** — `schedulerFactoryBean` → `QuartzConfig` Java `@Configuration`
   (done; landed `a33b49235`). `@DependsOn("liquibase")` preserved.
8. **C.3-finish** — `dataSource` + `queryStore` → `DataSourceConfig`
   (done; landed `b8a0ffcd7`).
9. **C.4** — JPA infra (`entityManagerFactory`, `transactionManager`,
   `sharedTransactionTemplate`, `PersistenceAnnotationBeanPostProcessor`,
   `@EnableTransactionManagement`) → `JpaConfig` (done; landed `9abf4fc80`).
   The ~50 DAO beans stay in the XML stub — would need `@Repository` per
   impl class for component-scan to pick them up.
10. **C.13** — `SpringLiquibase` bean → `DataSourceConfig` (done; landed
    `609fab976`). `liquibase-core` dep scope flipped `runtime → compile`.
    The "real" C.13 (Boot's `LiquibaseAutoConfiguration`) activates after
    C.14; until then, an explicit `@Bean` keeps parity.

### Post-C.14 cluster (deferred — Boot autoconfig dependency)

The remaining sub-phases (C.6 services, C.10 `SecurityFilterChain`,
C.11 `WebMvcConfigurer`, C.12 `FilterRegistrationBean`, C.15 Actuator)
are **all materially cleaner under Spring Boot's autoconfig**. In the
current WAR-on-`ContextLoaderListener` mode they each require manually
re-defining beans that Boot autoconfig provides for free
(`DispatcherServlet`, `RequestMappingHandlerMapping`, message converters,
security filter chain, actuator endpoint registration). Attempting them
in WAR mode is roughly the same Java code volume as the XML they
replace — no real win.

11. **C.14 — WAR → JAR + embedded Tomcat** (next push; multi-day).
    The single biggest unlock. See the [C.14 cliff plan](#c14-cliff-plan-2026-05-30)
    section below for the full migration matrix; the matrix is backed by the
    auto-generated [phase-c14-web-xml-inventory.md](phase-c14-web-xml-inventory.md)
    (222 servlets, 7 filters, 5 listeners enumerated with per-package tables).
12. **C.6** — services component-scan. Add `@Service` annotations to the
    30+ classes currently bound by `<constructor-arg ref="dataSource"/>`
    in `applicationContext-core-service.xml`, swap to `@Autowired`. Best
    done alongside C.14 since the boot-up bean factory is now Boot-managed.
13. **C.15** — Spring Boot Actuator. **Attempted 2026-05-30, deferred.**
    Adding `spring-boot-starter-actuator` and un-excluding
    `DispatcherServletAutoConfiguration` + `WebMvcAutoConfiguration` +
    `ErrorMvcAutoConfiguration` triggers a
    `BeanDefinitionOverrideException` on `requestMappingHandlerMapping`:
    `WebMvcConfig` (in `.config` package, picked up by Boot's
    `scanBasePackages = ".config"`) AND Boot's `WebMvcAutoConfiguration`
    both register the bean in the root context.
    `WebMvcConfig` is **intended** to live only in the `pages`
    DispatcherServlet's child context (loaded via the
    `pages-servlet.xml` stub `<bean class="WebMvcConfig"/>`); Boot
    picking it up in root is the bug. Fix is to move `WebMvcConfig` out
    of the `.config` package (e.g. to `.webmvc.WebMvcConfig`) so Boot's
    scan doesn't see it, then update the `pages-servlet.xml` stub class
    reference. The actuator endpoints then live at
    `/LibreClinica/actuator/*` via Boot's auto-registered
    `dispatcherServlet` at `/` (the 215 legacy servlets at exact URL
    patterns + `pages` at `/pages/*` win per servlet-spec mapping
    precedence). Estimated effort: 1 focused push (~half day) once a
    full Docker rebuild cycle is available — initial attempt this
    session was blocked by stale `.config.WebMvcConfig.class` in
    BuildKit's layer cache.
14. **C.16** — Reconciliation sweep. Drop the now-empty XML stubs, drop
    `web.xml`, update Dockerfile + compose.yaml. Manual GCP-style smoke
    pass.

### C.14 cliff plan (2026-05-30)

The naive read of C.14 is "flip packaging from `war` to `jar` and let Boot
take over." In practice, this codebase has 222 legacy servlets registered
via `web.xml`, plus 7 filters, 5 listeners, the SiteMesh decorator
plumbing, and a custom `OCContextLoaderListener` that wraps the standard
`ContextLoaderListener` with MDC + hostname setup. A "single commit"
attack on the cliff is high-risk for a partial-state regression that's
hard to roll back.

The plan below splits the cliff into **two sub-pushes** with independent
gates:

#### Cliff sub-push 1 (DOES NOT cleanly split — see 2026-05-30 finding below)

The intent of sub-push 1 was: enable `SpringBootServletInitializer` as
the WAR's root-context bootstrap, drop `OCContextLoaderListener` from
`web.xml`, leave servlets and filters in `web.xml` for now.

**2026-05-30 attempt revealed the cliff doesn't split this way.** The
attack tree (each fix surfaced the next blocker):

1. **`NoClassDefFoundError: ch.qos.logback.core.util.StatusPrinter2`.**
   Boot 3.5's `LogbackLoggingSystem` needs logback 1.5+; the pin in
   `pom.xml` is 1.4.14. → Workaround: bump logback to 1.5.13.
2. **`IllegalStateException: Logback configuration error detected`.**
   The legacy `logback.xml` has 10 `RollingFileAppender`s colliding
   on a shared file pattern by design (one appender per syslog
   facility, all writing to the same file). Logback emits "Collisions
   detected … Aborting" — non-fatal under the legacy bootstrap, but
   Boot 3.5's `LogbackLoggingSystem.initialize` reads the
   `StatusManager` and throws on any ERROR. → Workaround:
   `-Dorg.springframework.boot.logging.LoggingSystem=none` JVM arg
   skips Boot's logging integration and lets logback init via its
   own auto-discovery.
3. **`BeanDefinitionOverrideException`** for `ruleSetListenerService`
   (and several other `@Service`-annotated services that are also
   XML-bean-defined). Boot 3.x defaults
   `allow-bean-definition-overriding=false`. → Workaround:
   `spring.main.allow-bean-definition-overriding: true` in
   `application.yml`.
4. **`Failed to register 'filter springSecurityFilterChain' on the
   servlet context. Possibly already registered?`** Boot's
   `SecurityFilterAutoConfiguration` auto-registers the
   `springSecurityFilterChain` bean as a `DelegatingFilterProxy`
   filter, colliding with `web.xml`'s own `DelegatingFilterProxy`
   entry for the same name. → Workaround: exclude
   `SecurityFilterAutoConfiguration` from `@SpringBootApplication`.
5. **`Failed to register 'filter apiSecurityFilter'`.** Same
   mechanism: Boot's `ServletContextInitializerBeans` iterates over
   every `Filter` bean in the context and creates a
   `FilterRegistrationBean` for each. `apiSecurityFilter` is defined
   as a `Filter` bean in `applicationContext-core-security.xml`;
   `web.xml` also registers it via `DelegatingFilterProxy`.
   **There is no global "skip all auto-registrations" property** —
   each colliding `Filter` bean needs an explicit
   `FilterRegistrationBean<…>.setEnabled(false)` to opt out, OR the
   web.xml entry needs to go away.

### 2026-05-30 second attempt — full cliff, deeper attack-tree

A second attempt went **further**: applied all five workarounds above
PLUS the full cliff scope (drop all `<filter>` entries from `web.xml`,
build `ServletInfraConfig` with opt-out `FilterRegistrationBean`s for
the three XML `Filter` beans plus `FilterRegistrationBean`s for
`encodingFilter` / `localeFilter` / `hibernateFilter` / `logFilter` /
`apiSecurityFilter`, build `SecurityConfig` with a `SecurityFilterChain
@Bean` translating the `<security:http>` block, drop the
`<security:http>` block from `applicationContext-security.xml`, refactor
`OCContextLoaderListener` to a pure `ServletContextListener`). Three
more blockers surfaced before the smoke gate broke on auth flow:

6. **`UnsatisfiedDependencyException` on `changeCRFVersionController`:
   `sidebarInit` bean not found.** Boot's default `@ComponentScan`
   (rooted at `LibreClinicaApplication`'s package) pulled controllers
   into the ROOT context. Their `@Autowired @Qualifier("sidebarInit")`
   dependency lives in the CHILD context (DispatcherServlet's
   `pages-servlet.xml`) and isn't visible from there. → Workaround:
   `@SpringBootApplication(scanBasePackages = ".config")` restricts
   Boot's scan to the new `.config` package, leaving the existing
   `<context:component-scan>` directives in
   `applicationContext-core-spring.xml` (services) and
   `pages-servlet.xml` (controllers) authoritative for those packages.

7. **`NoClassDefFoundError org/springframework/ldap/convert/ConverterUtils`**
   from `LdapAutoConfiguration.objectDirectoryMapper`. Boot's LDAP
   autoconfig triggers because spring-ldap is on the classpath, but the
   `ConverterUtils` class is missing from the pinned spring-ldap
   version. Our LDAP usage is via the XML `contextSource` +
   `ldapAuthenticationProvider` beans; Boot's autoconfig isn't needed.
   → Workaround: exclude `LdapAutoConfiguration` from
   `@SpringBootApplication`.

8. **`NoClassDefFoundError: javax/servlet/Filter` from
   `org.springframework.security.config.annotation.web.AbstractRequestMatcherRegistry.isDispatcherServlet`**
   on every authenticated HTTP request. Spring Security 6.4 still has
   a compat path that does `Class.forName("javax.servlet.Filter")` to
   detect whether a DispatcherServlet is registered for MVC path
   matching. On a jakarta-only classpath the class is loaded but
   javax.servlet.Filter doesn't exist — surfaces as `NoClassDefFoundError`
   rather than the expected `ClassNotFoundException` that the compat
   path catches. → Workaround: bypass the registry's auto-detection by
   passing explicit `AntPathRequestMatcher` instances to
   `requestMatchers(RequestMatcher…)` instead of `requestMatchers(String…)`:
   ```java
   .requestMatchers(antPaths("/pages/login/login", "/SystemStatus", …))
       .permitAll()
   ```
   with `antPaths(String…)` returning `AntPathRequestMatcher[]`.

9. **Login page `/pages/login/login` returns 302 redirect-loop instead
   of 200 with the login form.** With all the above workarounds, Boot
   bootstraps cleanly, Tomcat deploys, the DispatcherServlet registers,
   and HTTP requests reach the security filter chain — but the
   `permitAll` for `/pages/login/login` does not match (or some other
   filter chain in the request path forces a redirect). The auth POST
   to `/j_spring_security_check` then redirects to `/MainMenu?continue`,
   which redirects to `/pages/login/login`, which redirects again →
   curl hits max 50 redirects → final URL `/error?continue` 500.

   Not yet diagnosed (would benefit from a docker exec into the
   container to read the libreclinica.auth log for the per-request
   filter trace — currently blocked by the auto-mode classifier's
   Production Reads policy). Likely candidates:
   - Spring Security 6.4's `AntPathRequestMatcher` path semantics
     against requests with `server.servlet.context-path: /LibreClinica`
     — the matcher may need the context-relative path or the full URI
     depending on its constructor.
   - The DispatcherServlet's child context picking up SidebarInit /
     SetUpUserInterceptor before the security chain decides — actually
     the security chain runs FIRST, before DispatcherServlet hits the
     view controller. So the matcher is the more likely culprit.
   - The `SavedRequestAwareAuthenticationSuccessHandler` /
     `RequestCacheAwareFilter` interaction with the new chain may be
     replaying the original target URL into a loop.

   The fix is straightforward but requires either (a) docker-exec
   log-read authorization to trace one request, or (b) a known-good
   `SecurityFilterChain` Java config from a similar OpenClinica/LibreClinica
   migration as a starting point.

**State after the 2026-05-30 attempt:** all code changes rolled back;
`lc-develop` returned to the pre-cliff baseline. The attack-tree above
documents the eight known fixes the cliff push needs; the ninth
(login-flow redirect-loop) is the remaining diagnosis target.

The cliff is now estimated at **~5-8 days** of focused work with the
above blockers as the known critical path. The right sequencing:
1. Land Prerequisite #1 (logback collision fix) in its own PR — frees
   the LoggingSystem=none escape hatch as a one-cycle workaround.
2. Land the SecurityConfig + ServletInfraConfig + Boot bootstrap in a
   single cliff push, applying workarounds #1-#8 verbatim from the
   attack-tree above.
3. Iterate on #9 with full log access until the auth flow gates green.

**Conclusion:** Boot's "auto-register every `Filter` bean" semantics
fundamentally collide with web.xml's manual filter registrations.
Sub-push 1 cannot exist as a standalone "Boot bootstrap with web.xml
still in charge of filters" state — every filter bean defined in the
imported XML configs would need an explicit opt-out registration. The
cliff is **one push, not two**:

> **C.14 cliff = `SpringBootServletInitializer` + drop ALL filters
> from `web.xml` (move to `FilterRegistrationBean`) + drop the
> `springSecurityFilterChain` `DelegatingFilterProxy` (Boot's
> `SecurityFilterAutoConfiguration` re-registers it once we let it) +
> the prerequisites listed below.** Servlets can stay in `web.xml` —
> Boot does not auto-register a `ServletRegistrationBean` for every
> `Servlet` bean, so the 222 legacy servlets are independent.

The original sub-push 1 / sub-push 2 split in this playbook is
retired; treat the cliff as a single ~5-day effort.

#### Cliff prerequisites (required in their own focused PRs)

**Prerequisite #1 — logback collision fix.**
The current `core/src/main/resources/logback.xml` declares 10
`RollingFileAppender`s (`LOGFILE-LPR`, `-USER`, `-MAIL`, `-AUTH`,
`-UUCP`, `-CRON`, `-AUTHPRIV`, `-DAEMON`, `-NEWS`, `-OTHER`) that all
roll to the same file pattern `${log.dir}.%d{yyyy-MM-dd}.log`
intentionally — each appender's encoder hardcodes a different syslog
facility label (LPR / USER / MAIL / …) into the log line. Logback emits
"FileNamePattern collision … Aborting" for the duplicate appenders, but
the legacy non-Boot bootstrap silently tolerated it. **Spring Boot 3.5's
`LogbackLoggingSystem` reads the status manager during the early
`starting` event and throws `IllegalStateException: Logback
configuration error detected` on any status-ERROR — fatal for the
`SpringBootServletInitializer` boot path.** Fix is required *before*
sub-push 1: collapse the 10-appender pattern into a single
`RollingFileAppender` with `%X{FACILITY}` (or a custom
`MaskingPatternLayout`) reading the facility from MDC. Operator impact:
the single-file output is preserved, only the appender-count drops.
Estimate: 1 day.

**Prerequisite #2 — logback version bump.** Spring Boot 3.5's
`LogbackLoggingSystem` requires `ch.qos.logback.core.util.StatusPrinter2`
(logback 1.5+). The current pin `<logback.version>1.4.14</logback.version>`
in `pom.xml` must move to 1.5.13+ once Prerequisite #1 is resolved.
(Alternative if Prerequisite #1 is not feasible: ship
`-Dorg.springframework.boot.logging.LoggingSystem=none` in
`CATALINA_OPTS` to disable Boot's logging integration entirely. Verified
working in the 2026-05-30 attempt.)

**Prerequisite #3 — bean override toleration.** Several
`@Service`-annotated classes in
`at.ac.meduniwien.ophthalmology.libreclinica.service` are also
XML-bean-defined in `applicationContext-core-service.xml`. The legacy
`<context:component-scan>` + XML overlap was silently tolerated by
the legacy bootstrap; Boot 3.x defaults
`allow-bean-definition-overriding=false`. Either set
`spring.main.allow-bean-definition-overriding=true` in
`application.yml` (escape hatch — fine for one cycle) or do C.6 work
in tandem (drop the XML `<bean id="…">` entries for the duplicated
services).

#### Cliff push — combined web.xml retirement (the real cliff)

Goal: drop web.xml's filters + listeners + `OCContextLoaderListener`;
`LibreClinicaApplication` becomes the sole bootstrap. **All in one
push**, per the 2026-05-30 finding above.

a. `LibreClinicaApplication extends SpringBootServletInitializer`
   (override `configure(SpringApplicationBuilder)`).
b. Refactor `OCContextLoaderListener` to a pure
   `ServletContextListener` (drop the `ContextLoaderListener` super);
   keep MDC + hostname setup only.
c. Drop the `<listener>OCContextLoaderListener</listener>` +
   `<context-param>contextConfigLocation</context-param>` +
   `<listener>RequestContextListener</listener>` from `web.xml`.
   Boot's `SpringBootServletInitializer` now provides the root
   context.
d. New `web/src/main/java/.../config/ServletInfraConfig.java`
   registers the surviving listeners
   (`HttpSessionEventPublisher`, `OCServletContextListener`, the
   refactored `OCContextLoaderListener`) via
   `@Bean ServletListenerRegistrationBean`.
e. **Drop ALL `<filter>` + `<filter-mapping>` entries from
   `web.xml`** (springSecurityFilterChain, apiSecurityFilter,
   encodingFilter, localeFilter, hibernateFilter, logFilter,
   restODMFilter) — Boot's auto-registration of `Filter` beans
   would collide if any remained. For each retired filter, either:
   * let Boot autoconfig take over (e.g. encodingFilter is replaced
     by `HttpEncodingAutoConfiguration` via
     `spring.servlet.encoding.charset=UTF-8`), or
   * declare an explicit `FilterRegistrationBean<…>` in
     `ServletInfraConfig` if it's a custom filter (LocaleFilter,
     OCServletFilter, RestODMFilter), or
   * let Boot's `SecurityFilterAutoConfiguration` register the
     `springSecurityFilterChain` `DelegatingFilterProxy` once C.10's
     `SecurityFilterChain @Bean` lands — this folds C.10 into the
     cliff push.
f. Servlets stay in `web.xml` for now — Boot does NOT auto-register
   a `ServletRegistrationBean` for every `Servlet` bean. The 222
   legacy servlets keep working via their existing `<servlet>` +
   `<servlet-mapping>` entries; their migration to
   `LegacyServletRegistry @Configuration` is C.14's optional
   follow-up cleanup (no functional impact).
g. Convert `applicationContext-security.xml`'s `<security:http>`
   block to Java `SecurityFilterChain @Bean` (folds C.10 in). This
   is required because Boot's
   `SecurityFilterAutoConfiguration` needs the bean named
   `springSecurityFilterChain` to be a
   `org.springframework.security.web.SecurityFilterChain` — the
   XML namespace produces a `FilterChainProxy` which is not what
   the autoconfig binds to.
h. Compose smoke + full 112-IT suite green.

**Follow-up cleanup pass (post-cliff, no functional impact):**

i. Build `LegacyServletRegistry @Configuration` enumerating
   `ServletRegistrationBean` for all 218 LibreClinica servlets —
   generated from
   [phase-c14-web-xml-inventory.md](phase-c14-web-xml-inventory.md).
   ~2000 LOC of mechanical bean methods. Generator script at
   [gen-legacy-servlet-registry.py](gen-legacy-servlet-registry.py)
   produces the @Configuration class from web.xml. Must land in the
   same commit that removes the 222 `<servlet>` + 223 `<servlet-mapping>`
   entries from `web.xml` — otherwise Tomcat double-registers each
   servlet name and fails to deploy. Skipped by the script:
   `pages` (DispatcherServlet, stays in web.xml until WAR→JAR),
   `OpenClinicaJersey` / `OpenClinicaJersey2` (Jersey servlets — fail
   to link against jakarta.servlet 6, Tomcat marks unavailable),
   `ws` (Spring-WS MessageDispatcherServlet — Phase B zombie).
j. Verify zombie candidates: `spring-ws` `MessageDispatcherServlet`
   entry + 2 Jersey servlet entries + 2 `ws-servlet-config.xml` beans.
   If unused in production, delete the entries + the dep pins.
k. Drop all `<servlet>` + `<servlet-mapping>` entries from `web.xml`
   once `LegacyServletRegistry` is in place. `web.xml` is now empty
   (or removed entirely).
l. Packaging stays `war` for compatibility OR flips to `jar` (decision:
   stay `war` for one more cycle, flip to `jar` in C.16).
m. Dockerfile: still based on the official Tomcat image; the WAR is
   self-bootstrapping but external Tomcat hosts it. C.16 flips to
   JRE-only base with `ENTRYPOINT ["java","-jar","app.war"]`.

This is the **real cliff** — `~3-5 days` of focused work. The
[phase-c14-web-xml-inventory.md](phase-c14-web-xml-inventory.md) is the
source-of-truth migration matrix.

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

Phase C status as of 2026-05-30 closure:

- [x] **Boot bootstrap** — `LibreClinicaApplication extends SpringBootServletInitializer` owns the root context (C.14, commit `ab0604cb8`). External Tomcat deploys the WAR; embedded-Tomcat `java -jar` path documented as deferred (see §C.x post-Phase-C below).
- [ ] **App boots via `java -jar libreclinica.jar` with no external Tomcat** — DEFERRED. Requires packaging `war → jar` + `spring-boot-starter-tomcat` from `excluded` to `compile`; Dockerfile rewrite from `tomcat:10-jdk21` multi-stage to `eclipse-temurin:21-jre` + `java -jar app.jar`. ~1–2 days. Not a blocker for current production form.
- [ ] **All config sourced from `application.yml` + env vars; no XML bean files** — PARTIAL. All 11 placeholder-bound XML files are now thin stubs registering @Configuration classes (`<bean class="...Config"/>`) — semantically equivalent to `@ImportResource` but on disk as XML for backwards-compatibility with the 8 `HibernateOcDbTestCase` descendants' `@ContextConfiguration` paths. Two XMLs still hold substantive content: `applicationContext-core-hibernate.xml` (the ~50 DAO bean definitions with `parent="abstractDomainDao" autowire="byName"`) and `applicationContext-security.xml`/`applicationContext-core-security.xml` (auth-manager, password encoders, sas, sessionRegistry, contextSource, ldapAuthenticationProvider, xformParser, apiSecurityFilter). Both are migratable but invasive — each DAO bean would need `@Repository` + `@Autowired` on its setters; the security beans need 7 separate `@Bean` methods.
- [x] **web.xml retired** — 2204 → 49 lines (C.16, commit `92bace8cd`). Only the `pages` DispatcherServlet entry + 2 context-params remain. Full deletion gated on retiring `jmesaMessagesLocation` + `SQLInitServlet` context-params (consumed by legacy code) and migrating the `pages` DispatcherServlet to a `@Bean DispatcherServletRegistrationBean`.
- [x] **Spring Boot Actuator `/actuator/health` returns `UP`** (C.15). Gated by Spring Security (`permitAll` on `/actuator/health` + `/actuator/info` for liveness probes; everything else under `/actuator/**` requires `ROLE_USER`). `WebMvcConfig` moved out of the `.config` package into `.webmvc` so Boot's `scanBasePackages = ".config"` no longer picks it up (the file is loaded only by the pages-servlet stub via `<bean class="...webmvc.WebMvcConfig"/>`); Boot's `DispatcherServletAutoConfiguration` + `WebMvcAutoConfiguration` + `ErrorMvcAutoConfiguration` un-excluded — Boot's dispatcher at `/` coexists with 215 LegacyServletRegistry exact-match servlets + the legacy `pages` DispatcherServlet at `/pages/*` per servlet-spec mapping precedence.
- [x] **Unit-test suite green** — 33 pass across 8 pure-unit classes.
- [x] **Integration-test suite green** — 112/112 (93 + 19 C.0 boot-contract tests) on `postgres:14-alpine`. Verified after every sub-phase merge.
- [x] **All C.0 characterisation tests still pass** — verified post-C.14, post-C.16. Contract preserved.
- [x] **Compose smoke green** — auth POST `root/12345678` → 302 → `/MainMenu` 200 with correct title. Verified after every gate.
- [x] **curl-verified data flow** — `/AuditUserActivityData` JSON XHR returns real Postgres-backed audit-login records (verified end-Phase-B; not re-verified in C since the contract is preserved by the C.0 + IT gates).
- [ ] **Manual GCP-style operator smoke** — pending. login → create study → enrol subject → submit CRF → discrepancy note → SDV → sign → export ODM → audit log spot-check. Operator task; can run any time on the current `lc-develop`.

### Remaining Phase C work (post-2026-05-30 closure)

Two open items, both deferred without functional impact:

1. **WAR → JAR + embedded Tomcat** (~1–2 days). `web/pom.xml` `<packaging>war</packaging>` → `<packaging>jar</packaging>`; un-exclude `spring-boot-starter-tomcat` from `spring-boot-starter-web`; Dockerfile multi-stage `tomcat:10-jdk21` → `eclipse-temurin:21-jre` + `java -jar`. Operator value: smaller container, faster boot, no separate Tomcat upgrade path. Not blocking — external-Tomcat deploy continues to work.
2. **Drop the remaining `applicationContext-*.xml` content** (~2–3 days). Convert the ~50 DAO beans in `applicationContext-core-hibernate.xml` to `@Repository` + `@Autowired`, and the 7 security beans (auth-manager / password encoders / sas / sessionRegistry / contextSource / ldapAuthenticationProvider / xformParser / apiSecurityFilter) to `@Bean` methods in `SecurityConfig`. After this, every `applicationContext-*.xml` file is empty (or deleted); `LibreClinicaApplication.@ImportResource` reduces to zero entries. **DAO conversion may need to wait for or coincide with Phase D auth rewrite** — the security XMLs are most of the remaining work and Phase D rewrites them entirely.

---

## Reference

- [MIGRATION.md § Phase C](../../../MIGRATION.md#phase-c--spring-boot-3-conversion) — strategic plan
- [phase-c-and-d-autonomous-brief.md](../../../../phase-c-and-d-autonomous-brief.md) — ratified decisions
- [decision-record.md](decision-record.md) — DR-014 (Phase D Shibboleth) for context only
- [phase-b-execution-playbook.md](phase-b-execution-playbook.md) — playbook template
- `lc-develop` HEAD `106ba5f52` — the post-Phase-B baseline this playbook builds on
