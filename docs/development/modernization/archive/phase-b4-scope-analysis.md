# Phase B.4 scope analysis — Spring 5→6 + jakarta cliff

> Captured 2026-05-28 at the close of Phase B.3 (Castor → JAXB).
> Inputs: `grep -rh "^import javax\." ...` over `core/`, `web/`, `ws/`, `odm/`.
> Owner: Lukas Kuchernig. See [MIGRATION.md § Phase B](../../../MIGRATION.md#phase-b--java-21--spring-6--jakarta-cliff) and the [Phase B execution playbook §B.4](phase-b-execution-playbook.md#b4--spring-5--6--security-5--6) for the parent plan.

This document is the **pre-flight inventory** for the next session that
swings at the cliff. Read it before you start the actual code.

## Top-line numbers

| Surface | Count | Notes |
|---|---:|---|
| Files with `import javax.*` (main code) | 923 | excludes `target/`, `.m2-cache/`, test sources |
| `javax.xml.bind.*` imports | **2,607** | mostly in `odm/` generated DTOs from CDISC ODM XSD |
| `javax.persistence.*` imports | **657** | every entity in `core/` |
| `javax.servlet.*` imports | **253** | 295 legacy servlets + filters |
| `javax.ws.rs.*` imports | 33 | JAX-RS — the OpenRosa REST endpoints |
| `javax.mail.*` imports | 24 | mail notification path |
| `javax.annotation.*` imports | 2 | `@PostConstruct` — the only jakarta-moved member |
| JSPs to retag | 413 | B.7, not B.4 |
| Custom `.tld` files | 4 | `formtags.tld`, `view_tags.tld`, `openclinica.tld`, `jmesa.tld` |
| Spring application-context XMLs | 10 | `core/`, `web/`, `ws/` plus 2 test contexts |

**Total mechanical jakarta-bound imports: ~3,576.**

## What stays as `javax` (JDK-shipped, NOT jakarta)

These show up in the import scan but require **no migration** — they
are JDK API surface that retained the `javax` prefix:

- `javax.sql.DataSource` (160 imports)
- `javax.naming.*` (4)
- `javax.xml.parsers.*`, `javax.xml.transform.*`, `javax.xml.xpath.*`,
  `javax.xml.validation.*`, `javax.xml.datatype.*`,
  `javax.xml.namespace.*`, `javax.xml.XMLConstants` (~146 combined)

When the next agent runs a `sed -E 's/javax\./jakarta\./g'` pass, those
imports must be **excluded** or the build will explode in confusing
ways (e.g. `java.lang.NoClassDefFoundError: jakarta/sql/DataSource`).
Suggested filter:

```sh
# rewrite ONLY the migrating javax sub-packages
sed -i -E 's/\bjavax\.(servlet|persistence|xml\.bind|ws\.rs|mail|annotation\.PostConstruct)\b/jakarta.\1/g' "$f"
```

(Note `javax.annotation.PostConstruct` is treated as a single token
because it is the *only* member of `javax.annotation` that moved to
jakarta — `javax.annotation.Nullable` etc. did not.)

## Risk-bearing structural changes (not mechanical)

These can't be sed-rewritten — they need a human (or careful agent) to
make a judgment call:

### 1. Spring config XML schema bumps

Each `applicationContext-*.xml` declares Spring schema URLs. Spring 6
ships new schema XSDs at the same URLs but the namespace prefixes
recognise jakarta-namespaced bean classes. Mostly a no-op except where
the XML references a class explicitly (e.g. `class="org.springframework.oxm.castor.CastorMarshaller"` — already removed in PR 3c-3).

Files:
- [core/src/main/resources/org/akaza/openclinica/applicationContext-core-spring.xml](../../../core/src/main/resources/org/akaza/openclinica/applicationContext-core-spring.xml)
- [core/src/main/resources/org/akaza/openclinica/applicationContext-core-security.xml](../../../core/src/main/resources/org/akaza/openclinica/applicationContext-core-security.xml)
- [core/src/main/resources/org/akaza/openclinica/applicationContext-core-service.xml](../../../core/src/main/resources/org/akaza/openclinica/applicationContext-core-service.xml)
- [core/src/main/resources/org/akaza/openclinica/applicationContext-core-timer.xml](../../../core/src/main/resources/org/akaza/openclinica/applicationContext-core-timer.xml)
- [core/src/main/resources/org/akaza/openclinica/applicationContext-core-db.xml](../../../core/src/main/resources/org/akaza/openclinica/applicationContext-core-db.xml)
- [web/src/main/resources/org/akaza/openclinica/applicationContext-security.xml](../../../web/src/main/resources/org/akaza/openclinica/applicationContext-security.xml)
- [web/src/main/resources/org/akaza/openclinica/applicationContext-web-beans.xml](../../../web/src/main/resources/org/akaza/openclinica/applicationContext-web-beans.xml)
- [ws/src/main/resources/org/akaza/openclinica/applicationContext-security.xml](../../../ws/src/main/resources/org/akaza/openclinica/applicationContext-security.xml)
- [ws/src/main/resources/org/akaza/openclinica/applicationContext-web-beans.xml](../../../ws/src/main/resources/org/akaza/openclinica/applicationContext-web-beans.xml)

### 2. Spring Security XML config

**Good news**: no `WebSecurityConfigurerAdapter` subclass exists
anywhere — the project still uses XML-based Spring Security
configuration via `<http>` blocks. So the most-cited Spring Security 6
migration headache (the removed `WebSecurityConfigurerAdapter`) does
not apply.

**Still TODO**:
- Spring Security 6 CSRF default flipped to **on** for all stateful
  POSTs. The clinical-data form submissions, discrepancy-note POSTs,
  and admin actions all need CSRF tokens added OR explicit
  `csrf-disabled="true"` on each `<intercept-url>` — auditing each is
  required (see playbook §B.4 step 4).
- Password encoder default change. `UserAccountBean.password` stores
  MD5 hashes (legacy upstream); a `DelegatingPasswordEncoder` that
  recognises `{md5}…` legacy hashes and upgrades on next login must be
  wired before the cliff or every existing user loses access.

### 3. `web.xml` servlet spec version

Currently:

```
<web-app version="2.4" xmlns="http://java.sun.com/xml/ns/j2ee" ...>
```

Tomcat 10/11 expects Servlet 5+/6+ schemas (`jakarta` namespace). Bump
to `version="6.0"` and `xmlns="https://jakarta.ee/xml/ns/jakartaee"`.
This is B.6 territory but typically lands with B.4 since otherwise
Spring 6 + Servlet jakarta does not boot.

### 4. JAXB runtime swap

Phase B.3 stayed on `javax.xml.bind` 2.3.x per [DR-006 amendment](decision-record.md#dr-006). B.4 swaps to `jakarta.xml.bind` 4.0.x +
`org.glassfish.jaxb:jaxb-runtime` 4.0.x. The 2,607 `javax.xml.bind`
imports are the bulk of the mechanical sed sweep — they all need to
become `jakarta.xml.bind`. The annotated DTOs from B.3 don't need
structural changes; only the import lines.

### 5. Hibernate is a *stepping stone*

Hibernate 5.6.15+ ships **both** `hibernate-core` (javax.persistence)
and `hibernate-core-jakarta` (jakarta.persistence) at the same Java
version level. This means B.4 can swap the artifact to
`hibernate-core-jakarta` and the 657 `javax.persistence` imports flip
to `jakarta.persistence` **without touching the Hibernate version** —
deferring the high-risk Hibernate 6 HQL strictness changes to B.5.

This is the recommended sequencing:

| Step | Hibernate | Persistence ns | Risk |
|---|---|---|---|
| Pre-B.4 (today) | 5.6.15 `hibernate-core` | javax | — |
| Post-B.4 | 5.6.15 `hibernate-core-jakarta` | jakarta | low (artifact swap only) |
| Post-B.5 | 6.4.x `hibernate-core` | jakarta | high (HQL strictness, Criteria API removal) |

### 6. Spring WS module fate

[MIGRATION.md § Phase B sequencing step 4](../../../MIGRATION.md) and
the [playbook step 5](phase-b-execution-playbook.md) flag the `ws/`
module for **removal**. README already calls it "legacy, not tested,
not actively developed." Removing before B.4 saves ~370 javax imports
+ avoids the Spring WS 1.5.6 → 4.0.x bump. **Needs stakeholder
sign-off** (not autonomous — Lukas to confirm no active SOAP
consumer).

## Suggested PR breakdown

The cliff is too big for one PR. Proposed split:

1. **B.4 PR 1 (this doc)** — scope analysis, no code change.
2. **B.4 PR 2** — remove the `ws/` module (subject to confirmation
   that no active SOAP consumer exists). Frees ~370 javax imports.
3. **B.4 PR 3** — Spring + Spring Security pom bumps to 6.x; Eclipse
   Transformer / sed sweep over javax→jakarta in the migrating
   sub-packages; fix compile errors. Goal: project compiles on Spring
   6 with no jakarta.persistence yet (Hibernate still javax-side).
4. **B.4 PR 4** — Hibernate artifactId swap to `hibernate-core-jakarta`
   + sed sweep `javax.persistence` → `jakarta.persistence` in entities.
   Goal: integration tests green on jakarta-persistence Hibernate 5.6.
5. **B.4 PR 5** — `web.xml` schema + Spring Security CSRF audit +
   `DelegatingPasswordEncoder` wiring. Manual smoke: log in as existing
   MD5-hashed user.
6. **B.4 PR 6** — Spring Security XML schema bumps + remaining
   reconciliation.

Each PR gates on `mvn -P integration-tests` green before merge, same
discipline as B.3.

## Out of scope for B.4

- JSP / JSTL taglib URIs (B.7)
- Tomcat 10/11 servlet API (B.6 — but `web.xml` schema lands with B.4)
- Hibernate 6.x HQL strictness (B.5)
- Java package rename `org.akaza.openclinica.*` → `at.ac.meduniwien.*` (B.11)

## What the next autonomous session should NOT do

- Do **not** run an unfiltered `javax → jakarta` sed pass. The JDK
  packages listed in the "stays as javax" section will break the build
  in confusing ways and the diff will be hard to bisect.
- Do **not** disable CSRF globally to "make the smoke test pass". The
  clinical-data POSTs are the exact paths CSRF is protecting; bypassing
  is a security regression.
- Do **not** delete legacy MD5 password hashes from the DB to force
  re-enrolment. Wire the `DelegatingPasswordEncoder` instead.
