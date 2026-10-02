# Spring Boot 4 spike — 2026-09-30

Sizing for [DR-037](decision-record.md#dr-037--two-support-windows-set-the-order-of-platform-upgrades-postgresql-17-now-spring-boot-4-next) point 2: what breaks when the build moves from Boot 3.5.16 to Boot 4.1.1 (Spring 7.0.9, Security 7.1.1, Hibernate 7.4.5, Jakarta EE 11 / Tomcat 11), and in which code.

**Verdict.** The compile surface is small, and the rest of the move is behavioural.

- **Compile:** the tree has **32 compile errors in 6 files**, all mechanical and none in the legacy servlets, the SPA API controllers or the services.
- **Runtime, with one-line stand-ins:** the WAR builds and **all 445 DB ITs pass on Hibernate 7**. The app **starts on Tomcat 11** and serves form login, the SPA API and legacy JSP pages.
- **What remains:** Jackson 3's different defaults (13 unit failures, one cause), Hibernate 7's `save`/`saveOrUpdate` semantics (97 call sites), one Security 7 break in an XML context, and verification.
- **Estimate:** about **8–16 developer-days**. Roughly 2–4 of those disappear if DR-018's deletions come first.

## 1. Method

- **Branch.** `spike/muw-spring-boot-4` from `lc-develop` at `136650004`. The measured state is commit `8e13c117d`; its stand-ins are marked `SPIKE`, and it is not a migration.
- **Versions.** The BOM went to 4.1.1, the latest GA (4.2.0 is at M2). The pom's own pins would otherwise have kept Spring 6, so they were aligned with what Boot 4.1.1 manages:

  | Area | Version |
  |---|---|
  | Spring / Security / Hibernate | 7.0.9 / 7.1.1 / 7.4.5 |
  | Jakarta EE 11 APIs | Servlet 6.1, Pages 4.0, JAX-RS 4.0, Annotations 3.0, Persistence 3.2 |
  | springdoc | 3.1.1 |
  | Jackson 2 | 2.21.5, with annotations at 2.21 |
  | Liquibase / logback | kept at 3.6.3 / 1.5.34 |
- **Compile.** `-Xmaxerrs 100000`, then `mvn -fae compile` and `test-compile`, iterating with a one-line stand-in where one let the build go further. The second round surfaced only two test errors: none were hidden behind the first round in main code.
- **Beyond compile:**
  - unit tests;
  - the web integration profile (unit tests plus every `*DatabaseIT`, on Testcontainers);
  - a static XML-context check: Spring 7's own `XmlBeanDefinitionReader` over every XML context, with XSDs resolved from the jars only and nothing instantiated, resolving every bean class and every property setter;
  - a byte scan of every jar in `WEB-INF/lib` for removed APIs;
  - a start of the WAR on `tomcat:11.0-jdk25-temurin` against an empty PostgreSQL 14.
- **Baseline.** The XML check and the jar scan also ran on the `lc-develop` WAR (Boot 3.5.16). Anything that fails there is not attributed to Boot 4.

## 2. Compile errors

| Module | Main | Test |
|---|---|---|
| odm | 0 | 0 |
| core | 4 (2 files) | 2 (1 file) |
| web | 26 (3 files) | 0 |

By the areas DR-037 names:

| Area | Size | Errors |
|---|---|---|
| Legacy `control/**` | 264 files | **0** |
| Jersey `web/restful` | 5 files | **0** (compiles; it is inert either way, see §4) |
| XML-context beans | 15 XML files | not a compile matter; one live break, see §3 |
| Heritage DAOs, `core dao/**` | 145 files | **4**, in `AbstractDomainDao` and `CompositeIdAbstractDomainDao` |
| `controller/api` | 196 files | **0** |
| SPA-facing services, `core service/**` | 119 files | **0** main, 2 in one test |
| Boot bootstrap and security config | 2 files | **23** |
| Legacy Spring MVC `BatchCRFMigrationController` | 1 file | **3** |

Top categories:

1. **Boot 4 moved auto-configuration into modules** (18 errors, `LibreClinicaApplication`). Five excluded classes have new packages: `spring-boot-jdbc` (two), `-hibernate`, `-mail` and `-quartz`. The other four (security filter, user details, Liquibase, LDAP) have no module on this classpath, so their exclusions simply go.
2. **Hibernate 7 removed `Session.save` and `Session.saveOrUpdate`** (7 errors, 3 files): the two generic DAO bases and `BatchCRFMigrationController`.
3. **Security 7 removed `AntPathRequestMatcher` and channel security** (5 errors, `SecurityConfig`). The matcher becomes `PathPatternRequestMatcher`, and the rate-limit filter needs another anchor than `ChannelProcessingFilter`.
4. **Spring 7's `HttpHeaders` is no longer a `MultiValueMap`** (2 errors, one test): `containsKey` becomes `containsHeader`.

## 3. Beyond compile

**Unit tests.** Core passes 359/359.

The first web run had **492 errors out of 964, from one cause**. Boot 4 puts Jackson 3 (`tools.jackson` 3.1.5) on the classpath, and Spring 7 prefers it for HTTP message conversion. Jackson 3.1 needs `jackson-annotations` 2.21, but the pom pinned 2.18.10.

With Jackson 2 aligned to 2.21.5, **951/964** pass. The 13 failures, all in `CrfsApiControllerTest`, are again one cause. Jackson 3 turns on `FAIL_ON_NULL_FOR_PRIMITIVES` by default, so a `null` for a primitive field is rejected with 400 before the controller's own authentication and validation run: the tests expected 401, 403 and field errors. The code still uses Jackson 2's `ObjectMapper`; the JSON the SPA API reads and writes changes anyway, because the converter changed underneath it.

**Web integration profile.** 1,409 tests: **all 445 DB ITs pass** on Hibernate 7.4.5 with the `persist`/`merge` stand-ins. The only failures are the same 13 unit tests.

**XML contexts.** All ten contexts `LibreClinicaApplication` imports, plus `pages-servlet.xml`, parse under Spring 7 and Security 7, and every class resolves: 174 bean definitions, 182 class references and 97 set properties. **One break:**

- `applicationContext-core-security.xml` sets `userDetailsService` on `DaoAuthenticationProvider`, and Security 7 removed that setter (it is a constructor argument now). The app would not start. The fix is one element.

Four other XML files fail, identically on Boot 3.5. They are dead configuration that nothing loads, and they can be deleted:

- `createSubject-servlet-config.xml` and `ws-servlet-config.xml`: Spring Web Services is not on the classpath;
- `security-config.xml`: a Spring Security 3 attribute, `access-denied-page`;
- `ws/client/client-config.xml`: names a class that does not exist.

**App start.** On `tomcat:11.0-jdk25-temurin` against an empty database the WAR:

- started in 43 s, with health UP for the database, mail, liveness and readiness;
- let Liquibase 3.6.3 migrate the database under Spring 7;
- accepted a form login with the legacy MD5 password hash;
- served `/pages/api/v1/me` and `/pages/api/v1/studies` as JSON;
- rendered `/MainMenu`, `/ListStudySubjects`, `/ListUserAccounts` and `/ViewStudy`;
- logged no request-time error.

The SPA was not built (`-DskipSpa`), so `/app/login` is a 404, as it is on Boot 3.5.

**Build hygiene.** `maven-dependency-plugin:analyze` fails because web uses four Boot modules only transitively (`spring-boot-jdbc`, `-hibernate`, `-mail`, `-quartz`). They need declaring.

## 4. Third-party dependencies

| Dependency | Boot 4 / EE 11 status |
|---|---|
| springdoc-openapi 2.8.17 | needs 3.x; 3.1.1 exists and is what the spike used |
| Liquibase | Boot 4.1 manages **5.0.3, licensed FSL-1.1-ALv2**, so keep an explicit pin. Both 3.6.3 and 4.31.1 (the Liquibase spike) work with Spring 7 |
| Jackson 2 (30 main files import `databind`/`core`) | Spring 7 prefers Jackson 3: migrate to `tools.jackson` with explicit defaults, or keep Jackson 2 on its deprecated path |
| Jersey 1.19.3 + `jsr311-api` (`web/restful`) | **no jakarta version exists.** Already inert on Boot 3.5 and deleted under DR-018 step 1; Boot 4 changes nothing |
| Spring Web Services (the two dead servlet XMLs above) | not on the classpath today either |
| logback 1.5.34 (pinned; Boot 4.1.1 manages 1.5.38) | the pin holds and the app starts, because Boot's logging system is off (`LoggingSystem=none`). The `logback.xml` `<if>` migration still gates 1.5.37+ |
| Quartz 2.3.2 | its scheduler starts under Spring 7; Boot 4 manages 2.5.2, which the R4-libs work package adopts |
| Testcontainers 1.21.4, JUnit 4 tests | run on Boot 4's JUnit 6 platform |
| everything else in `WEB-INF/lib` | the same jars as on Boot 3.5; no jar references `jakarta.servlet.jsp.el`, which Pages 4.0 removed |

Apart from Jersey 1.x, which is already dead, no dependency lacks a Boot 4-compatible version.

## 5. Estimate

| Work | Days | What DR-018 removes |
|---|---|---|
| Compile fixes: Boot module imports and declarations, `SecurityConfig` matchers and filter anchor (security-critical, with tests), the `DaoAuthenticationProvider` XML, the `HttpHeaders` test | 1.5 | `BatchCRFMigrationController` (3 of 32 errors) |
| Hibernate 7 `save`/`saveOrUpdate` semantics through the base DAOs: `merge` returns a managed copy and `persist` returns no id, so 97 call sites need review | 2–4 | 34 of the 97 are legacy: 7 in `control/**` and 27 in heritage controllers, 14 of them in the OpenRosa chain already being removed. 63 stay: core services 45, `controller/api` 10, filters 3, domain 3, DAO 2 |
| Jackson: (a) keep Jackson 2 by excluding `tools.jackson` and registering the deprecated converters, or (b) migrate the 30 files and pin Jackson-2-compatible defaults, with SPA contract checks | (a) 0.5–1 · (b) 3–5 | nothing: the SPA API is JSON |
| Verification: SSO pre-authentication (DR-014), the LDAP provider (spring-ldap 4), Quartz jobs, exports, DICOM and retinal integration, SPA end-to-end, and the JSP screens on Tomcat 11 (4 of 97 were smoke-tested) | 3–5 | the JSP share shrinks with every screen DR-018 retires |
| Dockerfile base (`tomcat:11`), CI, compose, docs, release notes | 1 | — |
| **Total** | **8–16** | about 2–4 days |

**For DR-037's timing.** The legacy layer accounts for 3 of the 32 compile errors, about a third of the Hibernate call sites and the JSP part of verification. Waiting for DR-018's six-month bake-in (mid-2027) would save perhaps 2–4 days, at the cost of nine more months on an unsupported Spring 6.2 and Security 6.5.

The Liquibase upgrade on `spike/muw-liquibase-4` is independent and can land first. When Boot 4 comes, keep Liquibase pinned at 4.31.1.
