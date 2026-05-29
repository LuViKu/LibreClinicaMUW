# Phase B — Dependency analysis: javax → jakarta

Reference inventory for Phase B planning. For each direct dependency in the current `pom.xml`, this lists whether a Jakarta-namespace variant exists, what the migration target version is, and any known migration risks. Generated on 2026-05-28 based on the current `pom.xml` after Phase A.1.

This is a static snapshot — verify current versions via Maven Central before committing.

## Categories

- ✅ **Jakarta variant exists** — drop-in or near-drop-in replacement available.
- 🛠️ **Replacement library needed** — original lib abandoned or has no Jakarta variant.
- ⚠️ **API breaking change** — migration is mechanical but touches many call sites.
- 🔴 **High-risk replacement** — touches core domain code (e.g. ODM XML handling).

---

## Spring family

| Current | Target | Category | Notes |
|---------|--------|----------|-------|
| `org.springframework:spring-* 5.1.4` (post-A: 5.3.39) | `6.1.x` | ✅ ⚠️ | Spring 6 requires Java 17+, jakarta.* namespace, Servlet 5+. Pure mechanical via Eclipse Transformer where deprecated APIs aren't removed; manual reconciliation for removed APIs (`Jdbc4SqlXmlHandler`, `WebApplicationObjectSupport.getMessageSourceAccessor`, etc.). |
| `org.springframework.security:spring-security-* 5.1.4` (post-A: 5.8.16) | `6.3.x` | ✅ ⚠️ | Filter chain DSL changed (`SecurityFilterChain` bean required). `PasswordEncoder` defaults flipped — provide `DelegatingPasswordEncoder` to keep legacy MD5 hashes working until users re-login. |
| `org.springframework.security.oauth:spring-security-oauth2 2.3.5.RELEASE` | — (remove) | 🛠️ | EOL since 2022. Replace with **Spring Authorization Server** (separate project) if OAuth2 is still needed. Check actual usage: very limited in this codebase. |
| ~~`org.springframework.ws:spring-ws-* 1.5.6`~~ | **removed (PR #31, 2026-05-29)** | ✅ | Entire `ws/` SOAP module deleted along with the 3 `spring-ws-*` depMgmt entries. No active SOAP consumer at MUW Ophthalmology. |
| `org.springframework.ldap:spring-ldap-core 2.3.2.RELEASE` | `3.2.x` | ✅ | Jakarta variant in 3.x. |
| `org.springframework:spring-oxm` (uses Castor) | replace | 🔴 | Spring OXM is still present in Spring 6, but the Castor backend isn't. See "ODM / XML" below. |

## Persistence

| Current | Target | Category | Notes |
|---------|--------|----------|-------|
| `org.hibernate:hibernate-core 5.4.2.Final` (post-A: 5.6.15) | `6.4.x` (or `7.0` once released) | ✅ ⚠️ 🔴 | `javax.persistence` → `jakarta.persistence`. HQL strictness changes — audit every HQL string before migration. `Criteria` API removed (use JPA Criteria). Sequence generator behavior change (legacy compatibility mode available). |
| `javax.persistence:javax.persistence-api 2.2` | `jakarta.persistence:jakarta.persistence-api 3.1` | ✅ | Pure namespace swap. |
| `org.hibernate:hibernate-ehcache 5.4.2` | `hibernate-jcache 6.4` + `caffeine-jcache` or `ehcache 3` | 🛠️ | Hibernate dropped its EhCache integration in 6.x. Use JCache (JSR-107) backed by Caffeine (recommended) or Ehcache 3. |
| `net.sf.ehcache:ehcache 2.10.6` | `org.ehcache:ehcache 3.10.x` (or Caffeine) | 🛠️ | Ehcache 2.x is `javax.cache`; Ehcache 3.x is `jakarta.cache`. |
| `org.liquibase:liquibase-core 3.6.3` | `4.27.x` | ✅ ⚠️ | Liquibase 4 dropped support for some XML schema features. Validate every changeset still parses on 4.x. |
| `com.mattbertolini:liquibase-slf4j 3.0.0` | `4.0.x` | ✅ | |
| `org.postgresql:postgresql 42.7.4` (post-A: 42.7.4) | `42.7.x` | ✅ | Already current; works with Jakarta. |

## Servlet / JSP / JSTL

| Current | Target | Category | Notes |
|---------|--------|----------|-------|
| `javax.servlet:javax.servlet-api 3.1.0` | `jakarta.servlet:jakarta.servlet-api 6.0` | ✅ ⚠️ | Mechanical namespace swap; many JSP scriptlets reference `javax.servlet.*` directly — touch every JSP. |
| `javax.servlet.jsp:jsp-api 2.0` | `jakarta.servlet.jsp:jakarta.servlet.jsp-api 3.1` | ✅ | |
| `javax.servlet:jstl 1.1.2` | `jakarta.servlet.jsp.jstl:jakarta.servlet.jsp.jstl-api 3.0` + `org.glassfish.web:jakarta.servlet.jsp.jstl 3.0` | ✅ ⚠️ | **Taglib URI changes:** `http://java.sun.com/jsp/jstl/core` → `jakarta.tags.core` (similar for fmt/fn/sql/xml). Every JSP (413 files) must be updated. Eclipse Transformer handles this. |
| `taglibs:standard 1.1.2` | (subsumed by Jakarta JSTL impl above) | 🛠️ | Remove. |
| Custom `.tld` files (`formtags.tld`, `view_tags.tld`, `openclinica.tld`, `jmesa.tld`) | update to JSP 3.1 schema URIs + jakarta.servlet imports | ⚠️ | 4 files; mechanical edits. |

## XML / SOAP / ODM (highest-risk cluster)

| Current | Target | Category | Notes |
|---------|--------|----------|-------|
| `org.codehaus.castor:castor 1.4.1` | **Jakarta JAXB** (`jakarta.xml.bind` 4.0 + Glassfish/EclipseLink MOXy) **or** Jackson XML (`jackson-dataformat-xml`) | 🛠️ 🔴 | **Castor is abandoned since 2014, no Jakarta variant.** Touches every CDISC ODM code path (`ImportCRFDataServlet`, `ODMMetadataRestResource`, `MetaDataCollector`, `AdminDataCollector`). Must produce byte-equivalent ODM XML — write characterization tests first. **This is the single highest-risk change in the modernization.** Recommend Jakarta JAXB to keep schema-validated XSD generation flow. |
| `javax.xml.bind:jaxb-api 2.2.12` | `jakarta.xml.bind:jakarta.xml.bind-api 4.0.x` + `org.glassfish.jaxb:jaxb-runtime 4.0.x` | ✅ ⚠️ | Already a JAXB dep (alongside Castor!). Namespace swap. |
| `net.sf.saxon:saxon 8.7` | `net.sf.saxon:Saxon-HE 12.x` | ⚠️ | Saxon 8.7 is from 2007; large API change to 9+/10+/11+/12+. XSLT scripts may need touch-ups. Audit rule-engine XSLT executions. |
| ~~`com.sun.xml.wss:xws-security 3.0`~~ | **removed (PR #31, 2026-05-29)** | ✅ | Dropped with the `ws/` module. |
| ~~`org.springframework.ws:spring-ws-core-tiger 1.5.6`~~ | **removed (PR #31, 2026-05-29)** | ✅ | Dropped with the `ws/` module. |

## Logging

| Current | Target | Category | Notes |
|---------|--------|----------|-------|
| `ch.qos.logback:logback-* 1.2.13` (post-A: 1.2.13) | `1.5.x` | ✅ | Logback 1.4+ requires Java 11+; 1.5+ is the current line. |
| `org.slf4j:slf4j-api 1.7.36` (post-A: 1.7.36) | `2.0.x` | ✅ ⚠️ | SLF4J 2.x uses the `jakarta.servlet` namespace by convention. API mostly compatible; deprecated method removal. |
| `org.slf4j:jul-to-slf4j 1.7.36` | `2.0.x` | ✅ | |
| `org.slf4j:jcl-over-slf4j 1.7.36` | `2.0.x` | ✅ | |
| `log4jdbc:log4jdbc4 1.2` | replace or remove | 🛠️ | Abandoned. Optional dev-only logging. Replace with `org.bgee.log4jdbc-log4j2:log4jdbc-log4j2-jdbc4.1 1.16` or remove. |
| `org.codehaus.janino:janino 2.7.5` | `3.1.x` | ✅ | Logback conditional-config dependency. |

## Apache Commons (low-risk cluster — mostly already done in A.1)

| Current | Target | Category | Notes |
|---------|--------|----------|-------|
| `commons-fileupload:commons-fileupload 1.5` (post-A.1: 1.5) | `commons-fileupload2-jakarta 2.0` | ⚠️ | Commons FileUpload 1.x is `javax.servlet`. The Jakarta-namespace variant is a separate artifact (`commons-fileupload2-jakarta`). API mostly compatible. |
| `commons-io 2.16.1` (post-A.1: 2.16.1) | (current) | ✅ | Namespace-neutral. |
| `commons-codec 1.17.1` (post-A.1: 1.17.1) | (current) | ✅ | Namespace-neutral. |
| `commons-lang 2.3` | `org.apache.commons:commons-lang3 3.14` | ⚠️ | Package rename `org.apache.commons.lang` → `org.apache.commons.lang3`. ~40 source touches expected. |
| `commons-collections 3.2.1` | `org.apache.commons:commons-collections4 4.4` | ⚠️ | Package + class name changes. Audit `StringEnumeration`, `MultiMap`, etc. usage. |
| `commons-validator 1.3.1` | `1.9.0` | ✅ | |
| `commons-dbcp 1.4` | `org.apache.commons:commons-dbcp2 2.13` | ⚠️ 🛠️ | Custom `ExtendedBasicDataSource` extends DBCP 1.4. Rewrite against DBCP 2 or migrate to **HikariCP** (recommended — Spring Boot default). |
| `commons-beanutils 1.8.0` | `1.9.4` | ✅ | CVE-fixed releases available. |
| `commons-digester 1.7` | `commons-digester3 3.2` | ⚠️ | Package rename. Verify XML-rule digesters still parse. |
| `commons-logging 1.0.4` | (remove — use `spring-jcl`) | ✅ | Already replaced by `spring-jcl` in newer Spring versions. Remove from explicit deps. |
| `commons-math 1.1` | `org.apache.commons:commons-math3 3.6.1` | ⚠️ | Find usage first — possibly unused. |

## PDF / Document generation

| Current | Target | Category | Notes |
|---------|--------|----------|-------|
| `com.lowagie:itext 2.1.2` | `com.github.librepdf:openpdf 1.4.x` (LGPL fork) **or** `org.apache.pdfbox:pdfbox 3.0.x` | 🛠️ | Post-2.1 iText is AGPL — license cliff. **OpenPDF** is the drop-in API-compatible fork. PDFBox is a different API but more actively maintained. Recommend OpenPDF for migration speed, evaluate PDFBox for greenfield report code. |
| `org.apache.pdfbox:pdfbox 2.0.x` (already present) | `3.0.x` | ⚠️ | Some API changes 2 → 3. |
| `com.github.ralfstuckert.pdfbox-layout:pdfbox2-layout` | check version on Maven Central | ⚠️ | Hobby project — verify maintained. |
| `org.apache.xmlgraphics:fop 1.0` | `2.9` | ⚠️ 🛠️ | FOP 1.0 is from 2010. Major version jump; XSL-FO syntax mostly stable but plumbing changed. |
| `org.apache.poi:poi 3.0.1-FINAL` | `5.3.x` | ⚠️ 🛠️ | POI 3.0.1 is from 2007. Workbook factory APIs changed; `HSSFWorkbook` for `.xls`, `XSSFWorkbook` for `.xlsx`. Audit every Excel CRF upload/export path. |

## Scheduling / Mail / Misc

| Current | Target | Category | Notes |
|---------|--------|----------|-------|
| `org.quartz-scheduler:quartz 2.2.3` | `2.5.0` | ✅ ⚠️ | Quartz 2.5 supports both `javax.persistence` and `jakarta.persistence` (auto-detect). Storage schema unchanged. |
| `com.sun.mail:javax.mail 1.6.2` | `org.eclipse.angus:angus-mail 2.0.x` + `jakarta.mail:jakarta.mail-api 2.1` | 🛠️ | JavaMail → Jakarta Mail rename. New Maven coordinates. API surface mostly compatible. |
| `javax.activation:activation 1.1.1` + `javax.activation-api 1.2.0` | `jakarta.activation:jakarta.activation-api 2.1` + `org.eclipse.angus:angus-activation 2.0` | ✅ | Namespace swap. |
| `javax.ws.rs:jsr311-api 1.1.1` | `jakarta.ws.rs:jakarta.ws.rs-api 3.1` | ✅ | If Jersey REST is retained. Otherwise consider migrating to Spring's REST controllers. |
| `com.sun.jersey.contribs:jersey-spring 1.19.3` | replace with `org.glassfish.jersey.containers:jersey-container-servlet` 3.1 (if Jersey retained), or migrate REST endpoints to Spring MVC `@RestController` | 🛠️ | Jersey 1.x is EOL. Recommend migrating any Jersey-mounted endpoints to Spring `@RestController` and removing Jersey entirely. |
| `joda-time 1.6` | `2.13.x` **or** `java.time` (JSR-310) | ⚠️ 🛠️ | Joda 1.6 is from 2010. Modern Java has `java.time`. Audit usage; pure replacement preferred. |
| `dev.samstevens.totp 1.7.1` | (verify maintained) | ✅ | TOTP for 2FA. |

## GWT / GUI cleanup (Phase D)

| Current | Target | Category | Notes |
|---------|--------|----------|-------|
| `org.akaza.openclinica.gwt.GwtMenu` (compiled) | remove — vanilla nav | 🛠️ | GWT is essentially abandoned. Compiled artifact only — source is upstream OpenClinica's. Replace with hand-written HTML/JS nav. |
| `prototype.js 1.6` + `scriptaculous` | remove | 🛠️ | Abandoned 2010. ~20 JSPs use `$()` / `Effect.*`. Rewrite in vanilla JS or migrate to jQuery 3 (already in tree via JMesa). |
| `jQuery 1.9.1` (in includes/jmesa/) | `3.7.1` during D, or remove in E | ⚠️ | 1.9 → 3.7 mostly compatible via `jquery-migrate`. |
| `JMesa 2.4.2-oc` (local OC fork) | DataTables.net 2.x during Phase E | 🛠️ | Defer to UI phase. |

## Tooling

| Current | Target | Category | Notes |
|---------|--------|----------|-------|
| `maven-compiler-plugin` (implicit) | `3.13.0` with `<release>21</release>` | ✅ | |
| `maven-war-plugin 3.3.2` | (current) | ✅ | |
| `maven-surefire-plugin` (implicit) | `3.2.5` for JUnit 5 support | ✅ | |
| `JUnit 4.13.2` | `5.10.x` (Vintage adapter for legacy tests) | ⚠️ | Migrate one test class at a time with the Vintage engine in place. |
| `org.zeroturnaround:jrebel-maven-plugin 1.0.7` | drop or upgrade | ⚠️ | JRebel is commercial; if no team JRebel licence, remove. |
| `jaxb2-maven-plugin 2.5.0` | `3.2.0` (Jakarta XML Binding 4) | ⚠️ | XJC schema-to-Java generation. |
| `dependency-check-maven` | add | ✅ | OWASP CVE scanning. |
| `jacoco-maven-plugin` | add | ✅ | Coverage reporting. |

---

## Recommended Phase B ordering (refines [MIGRATION.md § Phase B](../../../MIGRATION.md#phase-b--java-21--spring-6--jakarta-cliff))

1. **JDK 21 baseline first** — surface compiler warnings/errors before touching deps
2. **Castor → JAXB sub-phase** — 3–4 weeks, characterization tests, byte-equivalence check
3. **Spring 5 → 6, Security 5 → 6, WS removed-or-bumped** — 2–3 weeks
4. **Hibernate 5.6 → 6.4** + `jakarta.persistence` — 3–4 weeks
5. **Servlet 3.1 → 6.0, JSP/JSTL taglib updates across 413 JSPs** — 1–2 weeks (Eclipse Transformer)
6. **Custom `.tld` files updated** — 1 week
7. **Apache Commons family migration** (lang3, collections4, dbcp2-or-Hikari, beanutils, digester3, math3) — 2 weeks
8. **Mail / activation / JAX-RS Jakarta switch** — 1 week
9. **Joda → java.time** — 1 week (or defer to D)
10. **Full reconciliation + regression sweep** — 3–4 weeks

---

## Open questions for decision (record as new DR-006 onwards)

- **DR-006**: Castor replacement — Jakarta JAXB vs. Jackson XML?
  - Recommendation: **Jakarta JAXB**. ODM 1.3 is XSD-schema-validated; JAXB matches the production model. Jackson XML is faster to write but produces less-canonical output.
- **DR-007**: iText replacement — OpenPDF vs. PDFBox?
  - Recommendation: **OpenPDF** for the migration (API-compatible). Then consider PDFBox for greenfield report code in Phase E.
- **DR-009**: Spring Security OAuth2 — replace with Spring Authorization Server, or remove?
  - Recommendation: audit actual OAuth2 usage first. If only used for one or two integrations, may be removable.
- **DR-010**: Java package rename `org.akaza.openclinica.*` → `at.ac.meduniwien.ophthalmology.*`
  - Recommendation: **yes, during Phase B**. Every file is being touched anyway. Use IntelliJ structural-replace + Eclipse Transformer.
- **Database connection pool**: stay on DBCP, jump to DBCP 2, or migrate to HikariCP?
  - Recommendation: **HikariCP**. Faster, more stable, Spring Boot default. Custom `ExtendedBasicDataSource` needs reworking either way.
- **Joda-Time**: replace with `java.time` (recommended) or stay on Joda 2.x?
  - Recommendation: `java.time`. Lower long-term maintenance.
