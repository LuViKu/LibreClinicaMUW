# Decision Record — Backend Modernization

This document captures the strategic decisions that frame the modernization project. New decisions are appended; previous decisions are not edited (record-of-truth).

---

## DR-001 — Adopt LibreClinica as MUW Ophthalmology eCRF

**Date:** 2026-05-28
**Status:** Accepted
**Owner:** Department of Ophthalmology and Optometry, MUW

**Context.** The department needs an eCRF platform for in-house clinical trials with full audit trail, multi-site capability, GCP/ICH-E6 alignment, and institutional control of code and data. Options considered: REDCap, Castor EDC, Medrio, OpenClinica Enterprise, LibreClinica.

**Decision.** Adopt LibreClinica (the open-source community successor of OpenClinica 3.14, currently maintained by ReliaTec GmbH) as the institutional eCRF.

**Consequences.**
- Institution owns the source and the operational stack
- LGPL license; institutional use does not trigger source-disclosure
- Existing capabilities (audit trail, double DE, SDV, e-signature, discrepancy notes, CDISC ODM, LDAP/AD, multi-site) align with department needs
- Onus is on the institution to (a) write the GCP / 21 CFR Part 11 validation plan, (b) operate the stack, (c) maintain the codebase

---

## DR-002 — Full backend re-platform, not incremental modernization

**Date:** 2026-05-28
**Status:** Accepted
**Owner:** Lead Developer (initial: Lukas Kuchernig)

**Context.** The upstream stack (Spring 5.1, Hibernate 5.4, Java 11, `javax.*` namespace, Tomcat 9, plus abandoned libraries: Castor, Prototype.js, GWT, iText 2.1.2, Apache POI 3.0.1) reached EOL or carries known CVEs. Four modernization scopes were considered:

1. Spring Boot 3 + Java 21 (full target, ~9–12 months)
2. Spring 6 + Java 21, keep XML config + WAR (smaller refactor, ~6–9 months)
3. Phase A hardening only — bump within Spring 5 line (~2–3 weeks)
4. Full re-platform including library replacement — Castor, iText, POI, Quartz, GWT, Prototype (~12–18 months)

**Decision.** Option 4 — **full re-platform including library replacement**, phased across Phase 0 → E as described in [MIGRATION.md](../../../MIGRATION.md).

**Consequences.**
- 12–18 month, 2–3 developer (FTE) commitment
- Eliminates the long tail of abandoned dependencies in one project rather than re-opening it later
- Modernization completes before first clinical use (DR-004), so validation overhead is paid once
- Cannot be paused mid-Phase-B without leaving the codebase in an unstable state — phase gates with go/no-go reviews are mandatory

---

## DR-003 — Hard fork from upstream ReliaTec/LibreClinica

**Date:** 2026-05-28
**Status:** Accepted

**Context.** Three postures were considered:

1. Stay aligned — track upstream, Phase A only, wait for upstream Jakarta migration (no public signal of one)
2. Coordinate — Phase A locally, engage ReliaTec on Spring 6 / Jakarta plans
3. Hard fork — accept manual upstream merges, full independence

**Decision.** Hard fork (option 3).

**Consequences.**
- Future upstream patches require manual cherry-pick + Eclipse Transformer translation post Phase B (see [MIGRATION.md § Upstream merge protocol](../../../MIGRATION.md#upstream-merge-protocol-hard-fork))
- Freedom to restructure Maven `groupId`, package names (Phase B), module boundaries, and visible branding without upstream constraint
- We own the entire test pyramid (no upstream regression net)
- LGPL compliance unchanged — internal MUW use does not trigger source disclosure

---

## DR-004 — Clinical use deferred until modernization completes

**Date:** 2026-05-28
**Status:** Accepted

**Context.** A pre-Phase-D clinical trial would force the modernization project to freeze around it (production on a mid-migration codebase). The user confirmed there is no current trial deadline.

**Decision.** No clinical trial onboarding until at least the end of Phase D. Phase E (UI modernization) may overlap with first clinical use, but only the high-traffic SPA screens are within scope; admin screens remain on JSP.

**Consequences.**
- Validation cycles can batch at phase exits rather than per dep bump
- Modernization team can refactor freely without coordinating around live trial data
- Trade-off: department waits ≥12 months for first eCRF-enabled trial. Acceptable given current state of the stack.

---

## DR-005 — MUW Ophthalmology branding applied

**Date:** 2026-05-28
**Status:** Accepted

**Context.** Hard fork (DR-003) makes institutional branding both legally permissible (LGPL) and operationally appropriate.

**Decision.**
- Maven `groupId`: `org.libreclinica` → **`at.ac.meduniwien.ophthalmology.libreclinica`**
- Project displayed name: **"LibreClinica MUW Ophthalmology"** with the strapline **"Department of Ophthalmology and Optometry · Medical University of Vienna"**
- LibreClinica & OpenClinica heritage retained in README acknowledgements and license
- Java package names (`org.akaza.openclinica`, `org.libreclinica`): **unchanged in Phase 0/A**. Renaming Java packages is Phase B work — done while every file is being touched for the Jakarta migration anyway, to avoid two cycles of merge breakage.

**Consequences.**
- Artifacts are clearly identified as institutional builds, not redistribution of upstream
- README acknowledgements section preserves heritage and license attribution
- Branded chrome (login page, page titles, redirect HTML) makes deployments unambiguous

---

## DR-006 — Castor replacement: Jakarta JAXB

**Date:** 2026-05-28
**Status:** Accepted (pre-flight ratification for Phase B.3)
**Companion analysis:** [docs/development/modernization/phase-b-dependency-analysis.md](phase-b-dependency-analysis.md) (Castor row); [docs/development/modernization/phase-b-execution-playbook.md § B.3](phase-b-execution-playbook.md)

**Context.** Castor 1.4.1 (2014) has no Jakarta-namespace variant and must be removed before Phase B can complete. The CDISC ODM 1.3 import/export paths (`ImportCRFDataServlet`, `ODMMetadataRestResource`, `MetaDataCollector`, `AdminDataCollector`, plus rule-engine XSLT executions) all currently use Castor's mapping-driven marshaller/unmarshaller and `XmlSchemaValidationHelper`. Three replacement options were considered:

| Option | Pros | Cons |
|--------|------|------|
| **Jakarta JAXB 4 (`jakarta.xml.bind` + `org.glassfish.jaxb:jaxb-runtime`)** | XSD-schema-validated; `xjc` already used in the `odm` module to generate JAXB classes from ODM 1.3 XSD; annotation-driven (no separate mapping file); the most canonical output for schema-defined XML; native to Jakarta EE 9+ | Steeper learning curve for ad-hoc XML; `XmlAdapter` needed for some date / decimal lexical preservation |
| Jackson XML (`jackson-dataformat-xml`) | Familiar (Jackson is already in the dep tree for JSON); easier for one-off bean → XML | Produces non-canonical XML (attribute ordering, default-namespace handling differs from JAXB); harder to keep byte-equivalent against an XSD-validated baseline; would still need a schema-validation step for ODM |
| EclipseLink MOXy | Drop-in JAXB implementation with extensions (oxm.xml mapping file, dynamic typing) | One more dependency to track; institutional team has no MOXy experience; benefits over plain JAXB are minor for our schema-locked use case |

**Decision.** **Jakarta JAXB 4** for the Castor → modern XML binding swap.

**Rationale.**
1. The `odm/` module already contains JAXB-generated classes from the ODM 1.3 XSD — half the work is done.
2. ODM 1.3 is schema-locked; canonical, schema-validated XML output is the requirement of every downstream consumer (regulators, biostatistics pipelines, partner sites). JAXB matches that contract; Jackson XML does not.
3. Byte-equivalence against pre-Phase-B Castor output (the [B.0 characterisation tests](phase-b-execution-playbook.md#b0--castor-characterisation-tests-pre-flight) regime) is achievable with JAXB + targeted `XmlAdapter`s where lexical formats differ.
4. Plain JAXB has the smallest dependency footprint (no MOXy install).

**Consequences.**
- Phase B.3 (Castor → JAXB swap) becomes the highest-risk sub-phase of Phase B per the playbook risk register (`RB1`). Must not start until B.0 characterisation tests are green on the current stack.
- The `OdmJaxbContext` bean recommended in the playbook is the wiring landing — a single Spring-managed `JAXBContext` cached for the lifetime of the application context (instantiation is expensive).
- `XmlSchemaValidationHelper` (Castor) → `jakarta.xml.validation.Validator` (one-line swap per call site).
- A `dependency-check-suppressions.xml` entry may be needed for transitive `commons-beanutils` 1.x pulled by Castor; verify after removal.
- Open follow-ups: lexical preservation of `dateTime` / `decimal` / `Boolean` values may need per-field `XmlAdapter`s. The B.0 characterisation suite is the harness for catching those.

**Revisit triggers.**
- If a B.0 characterisation test cannot be made byte-equivalent on JAXB output even with `XmlAdapter`s, this DR is amended in favour of Jackson XML or MOXy. The cost of that pivot is the work to date on JAXB context wiring (low — a few hours).
- If the `odm/` module's existing JAXB-generated classes are discovered to be incomplete (we have not audited every ODM 1.3 element used at this scale), the gap is filled by re-running `xjc` against the upstream XSD before the swap. Not a DR amendment.

### Amendment 2026-05-28 — namespace at B.3 is `javax.xml.bind` 2.3.x, not `jakarta.xml.bind` 4.0

**Trigger.** Phase B.3 implementation reconnaissance (commit log of `feature/phase-b-castor-to-jaxb`) surfaced a contradiction between the original DR-006 decision and the existing `odm/` module state.

**Finding.** The `odm/` module's 513 xjc-generated Java files (under `odm/src/main/java/org/cdisc/ns/odm/v130/` and siblings) were generated with `jaxb2-maven-plugin:2.5.0`, targeting **`javax.xml.bind.annotation.*` 2.3.x** (per the file headers: `// This file was generated by the JavaTM Architecture for XML Binding(JAXB) Reference Implementation, v2.3.2`). A `jakarta.xml.bind` 4.0 runtime cannot bind these classes — different annotation package, different runtime API. The two rationale points in the original DR ("use existing JAXB-generated classes" + "add `jakarta.xml.bind:4.0.x`") are mutually exclusive on the current `odm/` state.

**Amendment.** Phase B.3 uses **`javax.xml.bind` 2.3.x** (already declared in `pom.xml` dependencyManagement as the pre-Phase-B fix). The jakarta-namespace migration of the XML binding layer moves to **Phase B.4**, where it sits alongside Spring 5 → 6, Servlet, and the other namespace-cliff work that happens in a single coherent step.

**Sequencing under the amendment.**

| Sub-phase | Castor | JAXB API | JAXB-generated classes (`odm/`) |
|-----------|--------|----------|---------------------------------|
| B.2 (done) | present | `javax.xml.bind` 2.3.x | javax-annotated |
| **B.3 (this work)** | **removed** | **`javax.xml.bind` 2.3.x** | **javax-annotated (unchanged)** |
| B.4 (next) | (already gone) | **`jakarta.xml.bind` 4.0.x** | **re-generated to jakarta** via `jaxb40-maven-plugin` or equivalent |

**Consequences.**
- No mixed-namespace state during B.3 — the whole codebase stays on `javax.*` until B.4 crosses the cliff in one move.
- B.3's JAXB call sites (`OdmJaxbContext`, the rewritten Castor-replacing code) all import `javax.xml.bind.*`. B.4 then runs Eclipse Transformer across them as part of the same sweep that handles the other 235 javax-importing files (per [phase-b-eclipse-transformer-dry-run.md](phase-b-eclipse-transformer-dry-run.md)).
- The `odm/` module's `jaxb2-maven-plugin:2.5.0` stays untouched in B.3. B.4 bumps it (or swaps to `jaxb40-maven-plugin`) and re-generates the 513 classes.
- This amendment does NOT change the engine choice (still Jakarta JAXB, not Jackson XML / MOXy) — only the namespace-introduction timing. The full Jakarta migration arrives via B.4.

**No additional revisit triggers.** The Jackson-XML / MOXy fallback condition from the original DR still applies if byte-equivalence is unachievable.

---

## DR-010 — Java package rename to MUW namespace, during Phase B.11

**Date:** 2026-05-28
**Status:** Accepted (pre-flight ratification for Phase B.11)
**Companion analysis:** [docs/development/modernization/phase-b-execution-playbook.md § B.11](phase-b-execution-playbook.md)

**Context.** The heritage Java packages are `org.akaza.openclinica.*` (legacy OpenClinica) and `org.libreclinica.*` (post-2019 LibreClinica additions). Three options were considered:

1. **Rename now, during Phase 0/A** — minimal-effort while CI is light, but every Java file change cascades through cherry-picks from upstream ReliaTec, and every Phase B `javax → jakarta` Eclipse Transformer run also has to deal with the rename. Doubles the merge-conflict surface.
2. **Rename during Phase B.11** — every file is being touched anyway for `javax → jakarta`; bundle the package rename into the same touch. Single review cycle. Single upstream-divergence event.
3. **Defer indefinitely** — accept `org.akaza.openclinica.*` as a permanent institutional inheritance. Lowest immediate effort but ongoing identity confusion (institutional Maven `groupId` says `at.ac.meduniwien.ophthalmology.libreclinica` while Java packages say `org.akaza.openclinica`).

**Decision.** **Rename during Phase B.11** to `at.ac.meduniwien.ophthalmology.libreclinica.*`.

**Rationale.**
1. Per [DR-003](#dr-003--hard-fork-from-upstream-reliateclibreclinica), we are committed to a hard fork. Heritage package names no longer serve a "stay close to upstream for easier merges" purpose post Phase B.
2. Per [DR-005](#dr-005--muw-ophthalmology-branding-applied), institutional identity is a stated goal. Java packages that say `org.akaza.openclinica` undermine that goal in IDEs, in stack traces, in dependency-tree output, and in error messages copied into support tickets.
3. The single-most-expensive event in renaming Java packages is updating every Spring XML `<bean class="...">`, every JSP `<jsp:useBean class="...">`, every DBUnit `getTestDataFilePath()` (encodes package as path), every `component-scan base-package`, and every `web.xml` listener / filter class reference. Phase B.11 is when every one of those files is already being touched for the Jakarta migration anyway — there is no cheaper time.
4. The rename runs as an IntelliJ "structural search and replace" on the Phase B integration branch, immediately after the JSP taglib URI updates (B.7) land, and immediately before the reconciliation sweep (B.12). Branch name per playbook: `feature/phase-b-jakarta-cliff-package-rename` (off `feature/phase-b-jakarta-cliff`).

**Mapping.**

| Old prefix | New prefix | Notes |
|------------|-----------|-------|
| `org.akaza.openclinica.*` | `at.ac.meduniwien.ophthalmology.libreclinica.*` | Bulk |
| `org.libreclinica.*` (LibreClinica community additions) | `at.ac.meduniwien.ophthalmology.libreclinica.*` | Merge into the same MUW namespace |
| `org.akaza.openclinica.gwt.GwtMenu` | (removed per Phase D) | Do not rename; just delete during the GWT removal sub-phase |

**Consequences.**
- DBUnit `getTestDataFilePath()` in `HibernateOcDbTestCase` and `OcDbTestCase` derives the path from `getClass().getPackage().getName()`. After the rename, all `core/src/test/resources/org/akaza/openclinica/.../testdata/*.xml` files must move to `core/src/test/resources/at/ac/meduniwien/ophthalmology/libreclinica/.../testdata/*.xml`. Mechanical but voluminous.
- Spring XML `<bean class="..">` references (~70 distinct classes referenced by Spring) need rewriting.
- `web.xml` servlet classes (~295 entries) need rewriting.
- JSP `<jsp:useBean class="...">` and `<jsp:scriptlet>` directives across 413 JSPs need rewriting. Eclipse Transformer alone cannot do this since it targets `javax → jakarta`, not package renames; pair with an IntelliJ structural-replace run on the same branch.
- Liquibase changelog references: column-comment text strings that contain `org.akaza.openclinica` (e.g. in audit_log_event entries) are NOT rewritten — those are historical data, not class references.
- Logback `<logger name="...">` declarations in `logback.xml` must be rewritten.
- An entry in `upstream-merges.md` (the cherry-pick log per [DR-003](#dr-003--hard-fork-from-upstream-reliateclibreclinica)) documenting the rename event with the script used.

**Revisit triggers.**
- If during Phase B.11 the rename touches more than ~5000 files (Eclipse Transformer + structural-replace baseline), the cost-benefit shifts. Pause + reassess on a sub-phase go/no-go review.

---

## DR-014 — Institution-agnostic SSO via reverse-proxy pre-authentication

**Date:** 2026-05-28 (draft) / **Accepted 2026-05-30**
**Status:** **Accepted.** Architecture chosen; MedUni Wien Shibboleth is the reference deployment, but the in-app surface is provider-agnostic.
**Owner:** Lead Developer (Lukas Kuchernig)
**Related:** [DR-001](#dr-001--adopt-libreclinica-as-muw-ophthalmology-ecrf), [DR-005](#dr-005--muw-ophthalmology-branding-applied), [DR-015](#dr-015--password-encoder-migration-md5sha-1--bcrypt-via-delegatingpasswordencoder), R3 in [MIGRATION.md § Risk register](../../../MIGRATION.md)

**Context.** LibreClinica was originally a username/password-only system (MD5/SHA-1 hashed; optional LDAP bind for institutional users). MedUni Wien Ophthalmology — the immediate deployment target — runs **Shibboleth** as the institutional SSO IdP (`login.meduniwien.ac.at`); clinicians cannot reasonably maintain a separate LibreClinica password. The platform must also stay attractive to other institutions adopting LibreClinicaMUW for their own clinical-trial use, who may run **different** SSO providers (Azure AD / Entra ID, Okta, Keycloak, Auth0, AWS Cognito, a different SAML IdP, or no SSO at all). Phase D therefore needs an SSO architecture that is **institution-agnostic at the application layer** and treats the specific provider as a deployment-time concern.

### Decision

LibreClinicaMUW consumes external authentication via **header-based pre-authentication** terminated by a reverse proxy. The application speaks one in-app protocol — `RequestHeaderAuthenticationFilter` reading configurable HTTP request headers — and delegates the actual SSO protocol (SAML / OIDC / OAuth2 / proprietary) entirely to the reverse-proxy choice. Provider swap = redeploy a different reverse-proxy sidecar; **no application code changes are required to switch providers**.

**Concretely:**

1. **In-app surface:** a single `RequestHeaderAuthenticationFilter` (Spring Security built-in) consuming a configurable principal header (default `REMOTE_USER`) plus a configurable set of attribute headers (e.g. `mail`, `displayName`, `eduPersonPrincipalName`) for JIT provisioning. No protocol code (SAML/OIDC) lives in the WAR.
2. **Reverse-proxy choice is institution-local** — `docker-compose.override.yml` adds the appropriate sidecar:
   - **MedUni Wien (reference deployment):** Apache HTTPD + `mod_shib` (Shibboleth SP) → SAML to `login.meduniwien.ac.at`.
   - **OIDC providers (Azure AD, Okta, Auth0, Keycloak, AWS Cognito):** Apache HTTPD + `mod_auth_openidc`, or oauth2-proxy, or Keycloak Gatekeeper.
   - **AWS deployments:** ALB with OIDC authentication action propagating `x-amzn-oidc-data` (configurable as the principal header).
   - **Cloudflare Access / Tailscale:** their respective JWT or header-injection patterns.
   - **No-SSO institutions:** omit the sidecar entirely — `REMOTE_USER` header is never set, every request falls through to the local username/password path.
3. **App configuration is provider-neutral** — `application.yml` exposes:
   ```yaml
   libreclinica:
     sso:
       enabled: ${LIBRECLINICA_SSO_ENABLED:false}
       header:
         principal: ${LIBRECLINICA_SSO_PRINCIPAL_HEADER:REMOTE_USER}
         email:     ${LIBRECLINICA_SSO_EMAIL_HEADER:mail}
         displayName: ${LIBRECLINICA_SSO_DISPLAY_NAME_HEADER:displayName}
         # arbitrary additional attribute headers consumed by JIT provisioning
       provisioning:
         strategy: ${LIBRECLINICA_SSO_PROVISIONING:LOOKUP_ONLY}   # LOOKUP_ONLY | JIT
         default-role: ${LIBRECLINICA_SSO_DEFAULT_ROLE:ROLE_USER}
       trusted-proxy:
         # network controls — refuse pre-auth headers unless the request comes
         # from a trusted upstream (compose-internal network, localhost, etc.)
         allowed-cidrs: ${LIBRECLINICA_SSO_TRUSTED_CIDRS:127.0.0.1/32,172.16.0.0/12}
   ```
4. **Local accounts coexist.** Sponsor-side monitors, ReliaTec personnel, demo / break-glass accounts continue to authenticate via the existing username/password path. LDAP bind also stays per the 2026-05-28 ratification.
5. **Provisioning is pluggable** via a `UserProvisioningStrategy` interface with two stock implementations: `LookupOnlyStrategy` (require admin pre-provisioning; reject SSO logins for unknown principals) and `JitProvisioningStrategy` (auto-create on first login with `default-role`). Custom institution-specific strategies plug in by replacing the bean.

### Why this choice, vs. the alternatives previously laid out

- **vs. in-app SAML SP** (`spring-security-saml2-service-provider`): rejected. Couples the app to the SAML protocol, requires institutional SP registration for every deployment, makes cert rotation a redeploy. Other institutions on OIDC would force a second protocol implementation. Header-based pre-auth gives us one in-app surface that handles SAML, OIDC, OAuth2, custom, **and** no-SSO without code changes.
- **vs. in-app OIDC client** (`spring-security-oauth2-client`): same coupling problem; rejected for institutional flexibility.
- **vs. CAS / Spring Authorization Server**: out of scope — those are alternative *protocols* to support, not architectures. If a future institution mandates CAS, add a CAS-aware reverse-proxy sidecar; no app change.

The Apache `mod_shib` reverse-proxy pattern proposed in the original 2026-05-28 draft is preserved as the **default reference deployment** for MedUni Wien Ophthalmology; what changes is that the app no longer assumes it. The same in-app code path works for any reverse proxy that can populate request headers.

### Constraints and design notes

1. **Trust-on-network is the load-bearing assumption.** Pre-auth headers from the upstream proxy are trusted because the network controls (Tomcat binds to compose-internal network only, or `127.0.0.1`; `trusted-proxy.allowed-cidrs` enforced by a Spring Security `RemoteAddressMatcher` predicate ahead of `RequestHeaderAuthenticationFilter`) guarantee no client can reach Tomcat directly. Deployment docs must call this out explicitly per institution.
2. **GCP / 21 CFR Part 11 §11.50 e-signatures.** SSO-authenticated sign-offs need a re-authentication step to count as a binding signature event. For Shibboleth this is `forceAuthn=true`; for OIDC it is `prompt=login` or `max_age=0`; for ALB it is a re-challenge endpoint. The reverse-proxy is responsible for the re-auth flow; the app exposes a `/sso/reauth` endpoint that triggers it via a 302 → proxy challenge. **The legal/regulatory team must still ratify** that re-auth via the proxy meets §11.50 under the institutional validation plan.
3. **2FA delegation.** When the IdP enforces MFA, LibreClinica's existing TOTP 2FA (APPLICATION / LETTER modes via `TwoFactorService`) becomes vestigial for SSO-bound users. 2FA enrolment screens hide for users whose last successful auth came through SSO; local-account users still see them.
4. **Attribute mapping.** `eduPersonPrincipalName` (Shibboleth) / `sub` (OIDC) / `preferred_username` (OIDC) / `x-amzn-oidc-identity` (ALB) → `external_id`. `mail` → email. Department/role attributes → LibreClinica study-role grant (institution-specific mapping rules; default = `default-role`).
5. **User schema.** New columns on `user_account` (Liquibase changeset `lc-muw-2026-XX-XX-add-external-identity.xml`):
   - `external_id VARCHAR(255)` — the principal value from the SSO header (eppn, sub, oid, etc.).
   - `external_id_provider VARCHAR(64)` — the provider name (`shibboleth-meduniwien`, `azure-ad-tenant-xyz`, `okta-prod`, …) so multiple SSO providers can coexist in one deployment if needed.
   - Composite unique index on (`external_id`, `external_id_provider`).
   - Existing `user_name` column stays — local accounts keep using it; SSO accounts may have it auto-populated from the principal or assigned by an admin during JIT provisioning.

### Compatibility with other institutions

Deployment cookbook entries documented in `docs/development/sso-deployment-guide.md` (to be authored during Phase D):
- MedUni Wien Shibboleth (Apache + `mod_shib`)
- Generic SAML IdP (Apache + `mod_shib` with `attribute-map.xml` overrides)
- Generic OIDC IdP (Apache + `mod_auth_openidc`)
- Azure AD / Entra ID (oauth2-proxy)
- Okta (Apache + `mod_auth_openidc` OR oauth2-proxy)
- Keycloak (Keycloak Gatekeeper, or oauth2-proxy)
- AWS ALB with OIDC integration
- No-SSO (compose stack as today; local accounts only)

Each entry specifies the sidecar config and the `LIBRECLINICA_SSO_*` env var values to set. **No code changes between deployments.**

### Open questions to resolve during Phase D execution

1. **MedUni Wien IT confirmation** of the Apache + `mod_shib` reference pattern, and provisioning of the `meduniwien.ac.at` SP registration. (P0 institutional dependency.)
2. **Test IdP for CI** — Shibboleth Testbed Docker image, SAMLtest.id, or a local Keycloak realm acting as a SAML IdP. The reverse-proxy choice in CI doesn't need to match production.
3. **JIT vs LOOKUP_ONLY provisioning** as the MedUni Wien default. (UX decision: admin-invite-then-SSO vs. attribute-driven auto-provisioning. Recommend `LOOKUP_ONLY` for the GCP-validated initial rollout, switch to `JIT` once admin processes are comfortable.)
5. **Legal/regulatory ratification** of proxy-mediated re-auth as a §11.50-compliant e-signature event.
6. **`OpenClinicaLdapAuthoritiesPopulator` future:** the LDAP path stays per the 2026-05-28 ratification, but its role narrows once SSO is live. Likely becomes the auth path for local-network service accounts only.

### Revisit triggers

- An institution adopts LibreClinicaMUW and reports their reverse-proxy choice falls outside the documented cookbook patterns.
- MedUni Wien transitions off Shibboleth (e.g., to Entra ID) — no code change required, just a different sidecar.
- Spring Security ships a generic protocol-agnostic pre-auth abstraction that supersedes `RequestHeaderAuthenticationFilter` — re-evaluate the wiring.
- A regulatory inspector challenges the proxy-mediated re-auth interpretation of §11.50.

---

## DR-015 — Password encoder migration: MD5/SHA-1 → bcrypt via DelegatingPasswordEncoder

**Date:** 2026-05-30
**Status:** Accepted.
**Owner:** Lead Developer (Lukas Kuchernig)
**Related:** [DR-014](#dr-014--institution-agnostic-sso-via-reverse-proxy-pre-authentication), R3 in [MIGRATION.md § Risk register](../../../MIGRATION.md)

**Context.** Current `OpenClinicaPasswordEncoder` ([core/src/main/java/.../core/OpenClinicaPasswordEncoder.java](../../../core/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/core/OpenClinicaPasswordEncoder.java)) does SHA-1 for new hashes, MD5 as legacy-read fallback. Both algorithms are cryptographically broken for password storage. Spring Security 6 deprecates `MessageDigestPasswordEncoder` and recommends `DelegatingPasswordEncoder` with bcrypt as the default for new hashes.

**Decision.** Migrate to `DelegatingPasswordEncoder` keyed by hash-format prefix:

- New writes: `{bcrypt}` prefix, bcrypt cost factor 10 (Spring Security default; revisit after benchmarking on production hardware).
- Legacy reads: existing rows have no prefix (raw hex digest). A `LegacyMd5Sha1PasswordEncoder` registered under the `{noop-legacy}` key (or implemented as the fallback for unprefixed hashes) recognizes both algorithms by hex-length (32 chars = MD5, 40 chars = SHA-1) and verifies against both with each call.
- **On successful legacy login**, rehash the plaintext with bcrypt and update `user_account.passwd` in place (lazy migration). After 90 days of normal login traffic, audit and reset any users whose hash still has no prefix.
- `OpenClinicaPasswordEncoderTest` extended with bcrypt round-trip + legacy-fallback assertions; the existing SHA-1/MD5 cases stay green.

**Why bcrypt vs argon2id / scrypt:**
- bcrypt is Spring Security's default, has the broadest battle-testing surface, and tuning is one parameter.
- argon2id is theoretically stronger but adds a native-or-pure-Java decision (Bouncy Castle vs `argon2-jvm`); not worth the operational delta for this codebase.
- scrypt is acceptable but no clear win over bcrypt and a smaller user base.

**Risks.**
- **R-D1a** Silent encoder drift breaks login for all users → mitigated by `OpenClinicaPasswordEncoderTest` + a Phase D characterisation test that posts root/12345678 (MD5-hashed) through the live filter chain pre- and post-migration.
- **R-D1b** Lazy migration leaves cold-account users on legacy hashes indefinitely → mitigated by an admin tool (`PasswordMigrationReport`) that lists unprefixed-hash accounts; on 90-day audit, force-reset.

**Revisit triggers.**
- OWASP password-storage cheatsheet recommends a different default.
- Spring Security ships a new password-storage abstraction.
- Production bcrypt cost-10 verification latency exceeds 200ms (raise cost; reconsider argon2id).

---

## DR-008 — UI framework for Phase E: Vue 3

**Date:** 2026-05-30
**Status:** **Accepted.** Settled without the E.1 bake-off; reasoning below.
**Owner:** Lead Developer (Lukas Kuchernig)
**Related:** [DR-002](#dr-002--full-backend-re-platform-not-incremental-modernization), [DR-004](#dr-004--clinical-use-deferred-until-modernization-completes), [DR-005](#dr-005--muw-ophthalmology-branding-applied), Phase E execution playbook §E.1

**Context.** Phase E replaces 413 JSPs + Prototype.js 1.6 + jQuery 1.9 + a GWT-compiled menu with a modern SPA. The three candidates short-listed in 2026-05-28 planning were React 19, Vue 3.4, and Svelte 5. The Phase E execution playbook's E.1 sub-phase originally timeboxed a two-week bake-off to score the candidates against the Investigator Subject Matrix mockup. The bake-off is now waived; the team commits to Vue 3 directly.

### Decision

**Vue 3.4+ with `<script setup>` Composition API, TypeScript strict mode, Vite 5, Pinia for state, vue-i18n for DE/EN, Tailwind v4 for styling.**

Vendored fonts. Frontend Maven Plugin wires `pnpm build` into `mvn package`. SPA bundle lands at `web/src/main/webapp/app/`.

### Why Vue, not React or Svelte

1. **The existing mockups port more naturally.** All 18 Phase E mockups are already structured as HTML+Tailwind. Vue Single File Component templates accept that markup near-verbatim; the migration is "paste into `<template>`, gradually add `v-for` / `v-if` / `v-model`." React requires JSX rewrites of every screen — `class=` to `className=`, attribute renaming, fragment wrapping. Estimated 2–3 weeks of unproductive translation cost recovered.
2. **Form-heavy clinical work matches Vue's strengths.** The single highest-value, highest-risk screen is CRF Data Entry (964 LOC of dynamic field generation in the legacy JSP). Vue's `v-model` two-way binding maps directly to "field value ↔ model field", exactly what each CRF row needs. In React, the same pattern requires per-input ceremony or an additional library (React Hook Form / TanStack Form), adding a runtime + a maintenance surface for the 5–10 year clinical lifetime.
3. **Smaller surface to audit for a GCP-validated UI.** Vue's reactivity is one mechanism. React requires understanding hook rules, memoisation, render cycles, Strict-Mode double-renders. For a clinical-data system where a regulatory inspector may ask "why did this screen re-render?", Vue's model is simpler to defend in the institutional validation plan.
4. **Approachable for a backend-heavy team.** The lead developer's primary stack is Java/Spring/Hibernate. Vue's `<script setup>` + Composition API reads more like imperative code than React's hooks-everywhere model. Lower mental tax per component for the person carrying the project; the team has been small-and-stable, and is likely to stay that way.
5. **Svelte rejected.** Significantly smaller hiring pool, smaller ecosystem for the dense-table + accessibility primitives Phase E needs (SDV table + Audit Log are non-trivial), Svelte 5's runes are still settling into community practice.

### Why not React (the runner-up)

- **Institutional hiring pool** is React's primary win. This argument would dominate if the team were larger or expected to churn — but the team has been small-and-stable and is likely to stay that way.
- **Dense-table ecosystem** (TanStack Table, AG Grid React) is excellent but not materially better than Vue equivalents — TanStack Table ships for Vue 3 with full feature parity; AG Grid Vue is production-grade; PrimeVue covers the headless-component surface.
- **Tailwind + headless-component patterns** (Radix UI, React Aria) are React-leaning, but Vue 3 has analogous coverage via Headless UI Vue, Radix Vue, and PrimeVue. Not a differentiator for our component surface.

### Why no bake-off

Two reasons:
1. **The differences are real but not catastrophic.** Either framework would ship a working Phase E. The cost of the bake-off (two weeks × the implementation cost of the Subject Matrix in both frameworks + scoring overhead) exceeds the marginal information gained by a team that has already discussed the tradeoffs.
2. **The institutional team has decided.** The bake-off was a risk-reduction mechanism for a team without a strong prior. The team has a strong prior: Vue's ergonomics, the existing-mockup port advantage, the smaller audit surface.

Phase E execution playbook §E.1 is updated from "framework bake-off → DR-008" to "Vue 3 scaffolding into the Maven build" — see the playbook update commit landing alongside this DR.

### Stack pin

| Layer | Choice | Reason |
|---|---|---|
| Framework | Vue 3.4+ | This DR |
| Language | TypeScript 5.x (strict) | Audit-friendly; pairs with `<script setup lang="ts">` |
| Build | Vite 5 | Vue's reference build tool; fastest dev loop |
| Styling | Tailwind v4 with `@theme` | Native CSS variables; matches the `muw-tokens.css` already drafted |
| State | Pinia | Vue 3 reference store; smaller surface than Vuex |
| Routing | vue-router 4 | Reference; supports SSR if Phase E ever needs SEO |
| i18n | vue-i18n 9 (Composition API) | DE/EN per Phase E.9; integrates with `org.akaza.openclinica.i18n.words` extraction |
| Forms | Native v-model + Zod for validation | v-model covers 80% of CRF inputs without an extra form library |
| Testing | Vitest + Vue Test Utils + Playwright | Vitest pairs with Vite; Playwright covers SPA E2E and integrates with the existing `SmokeIT` harness |
| Component catalogue | Histoire 0.17 | Vue-native; lighter than Storybook 8 for a small primitive set |
| HTTP | Native `fetch` + thin wrapper; OpenAPI schemas via `openapi-typescript` | Avoids `axios`; OpenAPI schemas auto-generate types from the backend's existing `@RestController` surface |

### Consequences

- Two weeks of E.1 bake-off effort redirected to E.2 (Tailwind production build + design-token lockdown) and the start of E.3 (component library extraction).
- Phase E execution playbook's R-E1 risk (wrong framework pick discovered after E.4) becomes a known accepted risk: if Vue proves wrong for the CRF Designer screen, the rewrite cost is real (~3 months) but recoverable.
- Open job-spec text for Phase E reinforcement: *"Vue 3 + TypeScript + Tailwind, healthcare/eCRF context preferred."*

### Revisit triggers

- Vue 3 deprecation announcement (extremely unlikely near-term; Vue's release cadence is conservative).
- The CRF Designer screen hits a structural Vue limitation during E.7. If this happens, document the limitation, score React / Solid as escape paths, and either escape or accept.

---

## Future decisions (open)

- DR-007 — iText 2.1.2 replacement: OpenPDF vs. Apache PDFBox (decide before Phase D library long-tail)
- DR-009 — Spring Authorization Server adoption (replaces deprecated Spring Security OAuth2 — superseded by DR-014's reverse-proxy SSO architecture; close as obsolete)
- DR-011 — Database connection pool: HikariCP vs. DBCP2 (recommend HikariCP; decide during Phase C)
- DR-012 — Date/time API: Joda-Time → `java.time` (recommend `java.time`; decide during Phase B)
- DR-013 — L2 cache: EhCache 3 vs. Caffeine + JCache (recommend Caffeine + JCache for Spring Boot 3 default; decide during Phase B)
- DR-016 — JIT vs LOOKUP_ONLY provisioning default for SSO users (decide during Phase D execution after MedUni Wien admin-process review)
- DR-017 — Authority/role mapping from SSO attributes (institution-specific; document a mapping-rule format)
