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
**Companion analysis:** [docs/development/modernization/archive/phase-b-dependency-analysis.md](archive/phase-b-dependency-analysis.md) (Castor row); [docs/development/modernization/archive/phase-b-execution-playbook.md § B.3](archive/phase-b-execution-playbook.md)

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
3. Byte-equivalence against pre-Phase-B Castor output (the [B.0 characterisation tests](archive/phase-b-execution-playbook.md#b0--castor-characterisation-tests-pre-flight) regime) is achievable with JAXB + targeted `XmlAdapter`s where lexical formats differ.
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
- B.3's JAXB call sites (`OdmJaxbContext`, the rewritten Castor-replacing code) all import `javax.xml.bind.*`. B.4 then runs Eclipse Transformer across them as part of the same sweep that handles the other 235 javax-importing files (per [phase-b-eclipse-transformer-dry-run.md](archive/phase-b-eclipse-transformer-dry-run.md)).
- The `odm/` module's `jaxb2-maven-plugin:2.5.0` stays untouched in B.3. B.4 bumps it (or swaps to `jaxb40-maven-plugin`) and re-generates the 513 classes.
- This amendment does NOT change the engine choice (still Jakarta JAXB, not Jackson XML / MOXy) — only the namespace-introduction timing. The full Jakarta migration arrives via B.4.

**No additional revisit triggers.** The Jackson-XML / MOXy fallback condition from the original DR still applies if byte-equivalence is unachievable.

---

## DR-010 — Java package rename to MUW namespace, during Phase B.11

**Date:** 2026-05-28
**Status:** Accepted (pre-flight ratification for Phase B.11)
**Companion analysis:** [docs/development/modernization/archive/phase-b-execution-playbook.md § B.11](archive/phase-b-execution-playbook.md)

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

## DR-019 — Phase E usability-acceptance bar

**Date:** 2026-05-30
**Status:** **Accepted.** Sets the quantitative criteria the Phase E.10 usability tests must meet before the SPA is approved for clinical use at MedUni Wien Ophthalmology.
**Owner:** Lead Developer (Lukas Kuchernig)
**Related:** [DR-004](#dr-004--clinical-use-deferred-until-modernization-completes), [DR-005](#dr-005--muw-ophthalmology-branding-applied), [DR-008](#dr-008--ui-framework-for-phase-e-vue-3), Phase E execution playbook §§ E.9 + E.10

**Context.** Phase E ships clinician-facing screens that replace the legacy JSP UI. Without an objective acceptance bar, "ready for clinical use" becomes a judgment call by whoever is in the room — risky for a GCP-validated system. DR-019 fixes the bar so the Phase E exit gate is unambiguous.

The test protocol (`docs/development/modernization/phase-e/usability-test-protocol.md`) operationalises this DR.

### Decision — the four-dimension bar

To pass the E.10 acceptance gate, the SPA must clear **all four** dimensions across a panel of ≥5 clinicians per role (Investigator + Monitor + Data Manager — so ≥15 sessions total, ≥45 task attempts).

| Dimension | Target | Source |
|---|---|---|
| **Task success rate** | ≥ 80 % of canonical task attempts complete unaided within the time budget | Per-scenario success/fail captured by the observer |
| **System Usability Scale (SUS)** | Median SUS score ≥ 70 across the panel | Standard 10-item SUS questionnaire, administered after each session |
| **Critical errors** | **0** per role | Critical error = a flow that could compromise clinical data integrity (wrong subject selected for sign-off, query attached to wrong item, CRF data lost on navigation, etc.). One critical error in any session fails the role gate |
| **Severe pain points** | ≤ 2 distinct **severe** findings per role across the panel | Severe = a pattern that prevents a non-trivial subset of users from completing the workflow without help, even if the task technically succeeded |

### Why these specific numbers

1. **Task success ≥ 80 %.** Below this, the SPA isn't usable end-to-end for the average study-team member. The 80 % figure is the standard benchmark in clinical-software usability literature (e.g. NIST IR 7741) and matches the bar the Phase D-Sec team's pen-test report used for "ready for clinical use" sign-off.
2. **SUS ≥ 70.** Standard SUS interpretation places 68 as the median across consumer software, ≥ 70 as "good", ≥ 80 as "excellent". A median ≥ 70 on a clinical-trial-data UI — where users are time-pressed, the data is dense, and the consequences of errors are real — is a meaningful bar.
3. **Critical errors = 0.** Non-negotiable. A clinical-data system that ever silently writes wrong data is GCP-non-compliant. The protocol's observer checklist enumerates the critical errors per scenario.
4. **Severe findings ≤ 2 per role.** Allows for two distinct rough edges per role that we then commit to fixing as carry-overs without failing the gate outright — anything more suggests structural rework.

### Sample size justification

Five users per role is the **minimum** Nielsen / NIST guidance for usability testing to surface ≥ 80 % of issues. The protocol allows the institutional team to scale up to seven per role when scheduling permits; the four-dimension bar applies regardless.

### Stopping rules

- **Critical error in any session ⇒ stop that role's testing immediately**, fix the cause, re-test from session 1 of that role.
- **SUS median < 70 after the first 3 sessions** ⇒ pause + revisit the panel composition / scenarios / interpreter quality before scheduling more sessions.
- **All four dimensions clear ⇒ role passes.** When all three roles pass, the E.10 gate closes and the SPA enters the institutional GCP-validation cycle.

### Consequences

- The four-dimension bar lands in the institutional validation plan as a documented Phase E exit criterion.
- The Phase E execution playbook's E.10 success criteria + the closure tag (`phase-e-closure`) reference this DR.
- The protocol carries enough operational detail (scenarios, scoring rubric, SUS form, observer template) that the next operator can run the sessions without re-deriving the bar.

### Revisit triggers

- A protocol revision after the first round of testing surfaces a dimension that doesn't map cleanly to the SUS / success-rate framework.
- An institutional QM/GCP reviewer pushes back on the ≥ 80 % / ≥ 70 / 0-critical / ≤ 2-severe numbers — in which case the bar is renegotiated transparently in this DR.

---

## DR-020 — Cross-CRF response-set catalog is virtual, not a new table

**Date:** 2026-06-03
**Status:** **Accepted.** Frames the response-set sharing affordance shipped by the Phase E.6 Milestone B CRF-authoring wizard.
**Owner:** Lead Developer (Lukas Kuchernig)
**Related:** Phase E.6 plan (`robust-jumping-eich.md`), `ResponseSetsApiController`, `CrfJsonValidator`

**Context.** Operators authoring CRFs through the wizard want to pick "Yes/No", "0–10 NRS pain scale", or other reusable response sets without re-typing the options on every new CRF. The legacy schema already stores option tuples in `response_set`, but every row is scoped to a specific CRF `version_id` — there is no global ID and label uniqueness is per-version.

Three approaches were considered:

1. **New `response_set_template` table.** Standalone catalog with its own ID space. Authoring picks a template; the JSON-to-workbook adapter inlines the chosen tuple into the version-specific `response_set` row.
2. **Promote existing rows to global by clearing `version_id`.** Either dual-write or re-key — both risk breaking the parser's per-version persistence invariants.
3. **Virtual catalog — distinct-tuples view of existing `response_set` rows, no new write path.** The catalog is a `GROUP BY (label, response_type, options_text, options_values)` projection over rows the spreadsheet parser already creates. `POST /api/v1/response-sets` accepts the entry shape, validates it, and echoes it back without persisting; the SPA caches it client-side. Persistence happens when the operator submits the CRF version — at which point the existing parser creates a fresh per-version `response_set` row the usual way.

### Decision

Option 3. The catalog is read-only at the database level; "create" is a UX affordance the SPA owns.

### Why

- **No schema change.** Approach (1) needed a new Liquibase changeset and a write path the legacy parser doesn't know about. Approach (3) reuses what's already there.
- **Zero parity drift with XLS upload.** Every CRF authored through the wizard ends up with the same `response_set` row shape as one uploaded via XLS — both go through the same parser. Catalog templates never become a separate persistence concern that the XLS path could miss.
- **Cheap to surface.** `response_set` is small enough at MUW (low thousands of rows) that a `GROUP BY` over the four key columns is sub-millisecond. A search index would be over-engineering.
- **Reversible.** If usage grows or operators demand template ownership semantics, a follow-up DR can promote the catalog to a real table. Until then we ship the simpler thing.

### Consequences

- `POST /api/v1/response-sets` returns `201` but never writes; the response body is the echoed entry the SPA stuffs into its picker state.
- A catalog entry only becomes "real" once at least one CRF version persists a `response_set` row carrying that label + tuple. Operators see fresh entries appear in `GET /api/v1/response-sets` after the first version-create that references them.
- The SPA's `crfAuthoring` store treats catalog picks as inline-materialised options at submit time — `ref: { label }` resolves to the inline `type/label/options` shape before the workbook is synthesised.

### Out of scope (for this DR)

- Per-study scoping of catalog entries (deferred — current scope returns all entries, with an `inActiveStudy` boolean for UX hinting).
- Ownership / authorship metadata on entries (the underlying `response_set` table has no owner column).
- Template deletion (catalog tuples disappear when no CRF version references them anymore; explicit deletion would require either a real table or a CRF-version cleanup pass).

---

## DR-022 — Remote stateless GPU sidecar for retinal inference

**Date:** 2026-06-16
**Status:** Accepted
**Owner:** Lead Developer (Lukas Kuchernig)

**Context.** Retinal inference (fluid / onl / bmeis / ga task families) is GPU-bound — ONL's 5-fold ensemble takes 25+ minutes per scan under linux/amd64 emulation on Apple Silicon. The institution has a Linux VM with NVIDIA TITAN + RTX 2080 GPUs that can host the sidecar + per-task runners. The main Tomcat continues to run on the institutional Tomcat host; sidecar / runners colocate on the GPU VM, both inside the same institutional network.

**Decision.** Add a stateless `POST /run` endpoint to the sidecar (`retinal-inference/src/retinal_inference/api/run.py`) that accepts a Heidelberg `.e2e` inline, runs the existing OptimaAdapter against it inside a per-request `TemporaryDirectory`, returns a JSON envelope containing the runner-produced CSV/NPY/PNG outputs as base64-encoded `artifacts`, and deletes the tempdir before the response completes. The institutional Tomcat reaches it via a new `RemoteRetinalInferenceClient` (`core/src/main/java/.../service/retinal/`) using `RestTemplate` multipart POST with an `X-MUW-Inference-Token` header + an `Idempotency-Key` header. The local `/screen` + DB-poll worker stay as the fallback path; setting `core.retinalInference.remotePushUrl` opts in to the remote branch.

**Trade-offs considered.**

| Axis | Choice | Rejected alternative |
|---|---|---|
| Transport | Plain sync POST with 60-min timeout | Async + callback URL (rejected — would need an inbound port on the institutional host) |
| Runner I/O | Sidecar + runners share `/var/lib/retinal-inference/tmp` host-bind so the runner sees the sidecar's `TemporaryDirectory` natively | Sidecar serves files to runners via HTTP (future-proofs horizontal scaling — deferred) |
| Result encoding | JSON envelope with base64 artifact bytes | `multipart/mixed` response (rejected — Java client complexity for 33% wire savings) |
| Streaming | None (single sync response) | `ndjson` heartbeats (rejected — same institutional network, no egress proxy buffering) |
| Scope | CSV outputs for every task — sidecar mirrors what the DB-poll worker already emits | DICOM SEG creation in v1 (deferred to a follow-up DR; sidecar generates `StudyInstanceUID` + `SeriesInstanceUID` on the bscan now so the SEG work is cheap when it lands) |

**Consequences.**

- The GPU VM never persists scan data past the request lifetime. The tempdir is created with `TemporaryDirectory(dir=RETINAL_INFERENCE_SHARED_TMPDIR)` and deleted via `with`-block exit; PHI redaction (`inference/phi.py`) blanks `PatientName`/`PatientID`/`PatientBirthDate`/etc. on the synthesised bscan before anything else reads the file (DICOM SUP 142 attestation).
- The institutional artifact store at `core.retinalInference.artifactStorePath` (default `/var/lib/libreclinica/retinal-artifacts`) keeps the CSV/NPY outputs the SPA's modality-results panel reads. Result rows are written exactly like the DB-poll path; only the **source** differs.
- Opt-in via four `datainfo.properties` keys: `remotePushUrl`, `remotePushToken`, `remotePushTimeoutSecs`, `artifactStorePath`. Local dev compose with all four unset behaves identically to before.
- Auth is a shared-secret header; production should source `remotePushToken` from `/etc/libreclinica/env` at Tomcat startup rather than committing it into the properties file.

**Reversible** — disabling the remote branch is a single-line config change (blank `remotePushUrl`); the sidecar's `/run` endpoint is itself opt-in via `RETINAL_INFERENCE_RUN_ENDPOINT_ENABLED=true`. The DB-poll worker stays untouched, so reverting is a no-op.

**Out of scope (for this DR).**

- DICOM SEG creation — separate phase. Will add `dcm4che-core 5.x`, a `DicomSegService`, and `bscan_dcm_path`/`seg_dcm_path` columns when the downstream PACS viewer + ophthalmic workflow nail down their SEG SOP-class requirements.
- HTTP file-serving between sidecar + runners (deferred until horizontal runner scaling is real).
- ndjson heartbeats / streaming (revisit if MUW network policy ever places an egress proxy between the institutional Tomcat and the GPU host).
- Java-side PHI assertion on returned bscan bytes (deferred until dcm4che lands).

---

## DR-024 — Single bscan.dcm ingestion seam

**Status:** Accepted (2026-06-25)

**Context.** DR-022 introduced the remote GPU sidecar pattern with two deployment postures sharing a single `retinal-inference` Python codebase:

1. **App-VM `retinal-preprocess` container** (local docker compose) — receives uploaded `.e2e` files, converts to `bscan.dcm`, strips PHI, persists SPA-viewer companions (`fundus.png`, `geometry.json`). Serves `/preprocess`.
2. **Cluster inference sidecar on cn5** (bare-metal uvicorn under `~/start_sidecar.sh`) — receives a pre-converted `bscan.dcm` over HTTPS from the institutional Tomcat, dispatches inference via the host-native `OCTLayerSeg` binary + Apptainer/.sif containers. Serves `/run` only.

The cluster's `ApptainerAdapter.full_volume` explicitly rejects `.e2e` inputs (raises `ValueError`) — production conversion ALWAYS happens app-side in posture (1) before the bytes leave the institutional VM. The cluster never sees raw `.e2e` content. PHI redaction therefore happens on the app VM, not on the GPU host.

**Problem.** Because both deployments share the same git tree, the `e2e_parser.py` module physically existed on cn5's disk even though it's never executed there. On 2026-06-24 a 6-hour debug session of an IOWA SIGSEGV cost ~4 hours fixing `e2e_parser.py` on the cluster, with the changes never taking effect — `git pull` + uvicorn restart applied the new code to the file, but `ApptainerAdapter` never imports it. The duplication was invisible to anyone tailing logs or reading the diff.

**Decision.** Move the `.e2e` → `bscan.dcm` + SLO companion code into a **separate Python package** at `muw-e2e-converter/` (sibling of `retinal-inference/`). The cluster's bare-metal deployment runbook installs `retinal-inference` WITHOUT this package; the LOCAL `retinal-preprocess` Docker image installs both via the Dockerfile's `pip install -e /app/muw-e2e-converter` step.

The new package contains:

| Module | Purpose |
|---|---|
| `e2e_parser.py` | Heidelberg .e2e → multi-frame `bscan.dcm` (oct-converter + pydicom). Produces `BscanVolume` with real-mm geometry from the .e2e header. |
| `fundus_extract.py` | SLO en-face PNG + `geometry.json` build (per-B-scan-to-fundus registration metadata). |
| `phi.py` | DICOM Supplement 142 blanking helper. Only ever runs against synthesised bscan.dcm bytes, so it belongs with the synthesiser. |

`retinal-inference` imports the converter via a guarded `try/except ModuleNotFoundError` block:

- `api/preprocess.py` — when the converter is absent, `/preprocess` returns HTTP 503 with `detail` pointing at DR-024.
- `inference/optima.py` — when the converter is absent, `OptimaAdapter.__init__` raises `ModuleNotFoundError` immediately. (Cluster operators who mistakenly set `RETINAL_INFERENCE_ADAPTER=optima` see the failure at adapter-selection time, not mid-request.)

The cluster posture is verified by the runbook's smoke step: after starting uvicorn, `curl /preprocess` must return 503 (anything else means the converter was mistakenly installed; remove it).

**Alternatives considered.**

| Option | Why not |
|---|---|
| Documentation-only (docstring + DR) | Doesn't prevent the next operator from hot-fixing the cluster's `e2e_parser.py`. Visibility doesn't equal enforcement. |
| Runtime startup banner | Same as above — operators tailing the launch logs would see "code path inactive" but the file is still right there to edit. |
| Single git repo for the converter | Overkill for a one-institution fork; an extra repo to manage with no clear ownership boundary. |

**Consequences.**

- **Iteration loop tightens.** When a future bug touches `prepare_bscan_dcm`, the only deployment that can show it is the local `retinal-preprocess` container — one `docker compose build retinal-preprocess` away. No cluster redeploy, no uvicorn env-var dance.
- **Cluster image surface shrinks.** `oct-converter`, `pillow`, and the SLO geometry build no longer ship to cn5. Smaller install footprint + fewer dependencies that could conflict with cluster-side Python pins.
- **Test split.** `test_fundus_extract.py` and `test_phi_strip.py` move to `muw-e2e-converter/tests/`. `test_preprocess.py` and `test_optima_dispatch.py` stay in `retinal-inference/tests/` (they exercise the consumer behavior). A new `test_preprocess_without_converter.py` simulates the cluster posture by monkeypatching `_CONVERTER_AVAILABLE = False`.
- **Compose build context broadens.** The retinal-inference Docker build context becomes the repo root (was `./retinal-inference`) so the Dockerfile can COPY both packages. The `retinal-preprocess` service reuses the same image and gets the converter for free.

**Reversible** — undoing the split is a `git mv` of the three modules + a Dockerfile diff. The two-package state isn't load-bearing for any downstream Java/SPA consumer (they only ever see the HTTP surface).

**Out of scope (for this DR).**

- Removing `OptimaAdapter` (kept as the dev-compose multi-runner path).
- A startup banner reflecting the active adapter + endpoint set (could be a future DR-024.5 if cluster operators want it).
- CI workflow that runs both pytest suites — current CI runs `retinal-inference/tests/` only; the new `muw-e2e-converter/tests/` add to that loop in a follow-up.

---

## DR-025 — DICOM C-STORE receiver for handheld fundus cameras

**Date:** 2026-09-09
**Status:** Accepted
**Owner:** Lead Developer (Lukas Kuchernig)
**Related:** DR-022, DR-024; `StudySubjectFinder` / `EventCandidate` (`core/.../service/retinal/`); `image_ingest` (`migration/lc-muw-2026-09-09-image-ingest.xml`); `RetinalArtifactStorageService`; `RetinalResultsApiController`; the **HealthAEye study** (#26). Implementation plan: [dicom-fundus-receiver.md](dicom-fundus-receiver.md).

**Context.** The **HealthAEye study** captures fundus images from two handheld devices with different capabilities. The **Remidio FOP** is effectively an iPhone + browser with **no DICOM export** — the operator uploads the captured JPEG through a web page. The **Optomed** is a full **DICOM modality** — it consumes a Modality Worklist and pushes images via **C-STORE**. Both need the image stored and attached to a subject/event/CRF; neither needs GPU inference (2-D fundus photos, distinct from the OCT `bscan.dcm` path, DR-024). The platform already has reusable pieces — `StudySubjectFinder` matches an image to a subject + event, and `retinal_inference_job` is a proven persisted queue. The operator has no LibreClinica account, so both ingress paths are unauthenticated (reverse-proxy gated).

**Decision.** One source-agnostic **`image_ingest`** queue (`source_kind` = `upload` | `dicom`) feeds a single SPA **reconciliation inbox**: bindings are nullable, images arrive `UNBOUND`, and an operator links each to a subject/event/CRF. Two ingress paths write it — (1) **upload**: a public upload page (no account, reverse-proxy gated, mirroring the OCT/BCVA portals) takes the Remidio JPEG; (2) **dicom**: a **`pynetdicom` C-STORE Storage SCP** app-VM sidecar (`dicom-scp`, alongside the `retinal-preprocess` posture — chosen over in-process `dcm4che` to keep the raw DICOM socket out of Tomcat) receives the Optomed's push, writes the Part-10 + a preview, and hands off to a shared-secret app endpoint. **Sequencing:** the generalized `image_ingest` schema first, then the two ingress paths (plain **C-STORE** first), then a **Modality Worklist SCP** so Optomed images auto-identify from the worklist we issue — **un-deferred 2026-09-11**: the Optomed Lumo manual and a live packet capture showed its standard-DICOM integration is *worklist-driven* (it pulls an MWL, then C-STOREs the study; without a worklist it opens the TCP connection and closes it without ever sending an association), so the sidecar serves a worklist from scheduled `study_event` rows (via a token-gated `internal/dicom-worklist` endpoint) and the ingest endpoint auto-binds a returning study by its accession `LC<study_event_id>` (`match_policy='worklist'`). **Live-verified end-to-end with the Lumo on 2026-09-17** (worklist pull → capture → C-STORE of *Ophthalmic Photography 8 Bit Image Storage*, JPEG Baseline → row `BOUND` to the scheduled visit); details in the plan doc. PatientName/ID are kept (images stay on-prem; no redaction, unlike DR-022's outbound path).

**Consequences.**

- A new opt-in sidecar + an upload endpoint + the `image_ingest` table (migration `lc-muw-2026-09-09-image-ingest.xml`); the DICOM sidecar is gated behind `core.dicom.scp.enabled` — unset is a no-op for existing deploys (mirrors DR-022's opt-in discipline).
- The receiver runs on the app VM (persists PHI, reaches the camera network); the GPU-sidecar "never persists" invariant is untouched, and no fundus image reaches the GPU cluster.
- Operator workflow gains a "DICOM inbox" reconciliation view with audited bind/dismiss.
- **2026-09-20 — no worklist entity, confirmed.** The question "how do we create a worklist entry for a new patient" has the answer "you don't": the worklist *is* the visit schedule filtered to today, and a subject is on the camera exactly when a visit dated today is open in a study the camera serves. What was missing was the operator's view of that fact, so the subject page carries a **Kamera-Worklist** strip (`GET /subjects/{oid}/worklist` — same `ScheduledVisitQuery` and same study scope as the worklist endpoint, so page and device cannot disagree) that says whether the camera lists the patient today and offers the fix in place: schedule the study's only visit definition for today, or move an open visit from another day. The strip is absent where no receiver is configured or the study is out of the camera's scope. Manuals (investigator §4d, CRC §4) and `docs/tests/t046.md` (T046-13) describe it.

**Reversible** — `core.dicom.scp.enabled=false` + not deploying the sidecar; the `dicom_ingest` table is additive and unused when off. No change to the retinal or CRF paths.

**Out of scope (for this DR).** MPPS; image Query/Retrieve (study-root C-FIND/C-MOVE); DICOM-TLS (single-site internal for v1); OCT/SEG creation (the DR-022 follow-up); PACS forwarding; multi-institution AE-title management.

---

## DR-026 — One ingest queue for every inbound file

**Date:** 2026-11-16
**Status:** Accepted
**Owner:** Lead Developer (Lukas Kuchernig)
**Related:** DR-022, DR-024, DR-025; `ingest_item` (`migration/lc-muw-2026-10-05-ingest-item.xml`, `lc-muw-2026-10-19-retinal-job-ingest-item.xml`); `IngestInboxApiController`, `IngestBindService`, `IngestItemRepository`, `IngestArtifactStore`, `IngestResolutionService`.

**Context.** The platform had grown **two queues for one activity**. A file arrives from a device, somebody says whose visit it belongs to, and it becomes study data — but an OCT volume went to the retinal pipeline's `parked` job list (sysadmin-only, cross-study, its own admin view) and a fundus photo went to the `image_ingest` inbox (DM/Investigator/CRC, its own bind API and SPA view). An operator had to know which queue a file had landed in before they could look for it, and neither view could show them that one patient had both waiting. Three copies of "which subject does this label mean", five of "which directory is this kind of file stored in", and two duplicated `INSERT INTO event_crf` statements had accumulated alongside. A third study would have added a third queue.

**Decision.** One table (**`ingest_item`**, renamed from `image_ingest` and given `kind` ∈ {`e2e`, `dicom`, `image`, `other`}), one inbox (`/api/v1/ingest`), one bind (`IngestBindService`). Existing inference jobs were **backfilled** onto `ingest_item` — one row per distinct scan, not per job, since one `.e2e` is enqueued once per task — with the patient hint recovered from the type-115 audit trail, which was the only place the operator-typed label had ever been kept. `parked` jobs became `cancelled` with a `status_message` naming their replacement: **nothing deleted, reversible by hand**. The public OCT portal now writes an `ingest_item` before anything is enqueued, and a parked upload produces an UNBOUND row and **no job at all** — there is nothing for a GPU to do with a scan whose patient is unknown.

**Consequences.**

- **Unbind exists** (audit 130). Previously a mis-bind was fixed by editing the row, leaving the CRF tick the bind had caused; the form kept asserting a modality was performed with nothing left to show for it. Undoing a bind now undoes what it did — repointing the value to another file from the same device when one remains, and removing it only when nothing does.
- The dedup key moved from `retinal_inference_job.e2e_sha256` to the scan's own row, and **gained** a condition: cancelled jobs are excluded, so a scan whose run was cancelled can be re-filed and run again. The old index forbade that.
- **A previous WAR cannot survive the rename.** Rolling back the application means restoring the pre-deploy dump, not redeploying the old image (`deploy-runbook.md` §rollback).
- Audit rows' `audit_table` locator was repointed from `image_ingest` to `ingest_item`. No `old_value`, `new_value`, `user_id`, timestamp or event type was touched — a pointer to a table that no longer exists preserves no observation.
- `ImageIngestApiController` and `/image-inbox` remain for one release as a façade and a redirect.

**Reversible** — the migration's `<rollback>` is complete and was verified to restore the schema byte-identically with rows preserved; the parked jobs are cancelled rather than deleted, so restoring them is an `UPDATE`.

**Out of scope.** Cancelling inference jobs on unbind (no bind starts one yet); SSE on the inbox; retiring the `parked` status decoder (kept one release).

---

## DR-027 — A study declares what it does, rather than the code knowing

**Date:** 2026-11-16
**Status:** Accepted
**Owner:** Lead Developer (Lukas Kuchernig)
**Related:** DR-026; `imaging_modality` + `imaging_modality_item_binding` (`migration/lc-muw-2026-11-02-imaging-modality.xml`); `study_setting` + `study_item_binding` (`migration/lc-muw-2026-11-16-study-setting.xml`); `PerformedItemAutoTicker`, `StudySettingService`, `StudyBindings`, `AiArmPolicy`, `SharedControllersHaveNoStudyLiteralsTest`.

**Context.** Onboarding a study required editing code every other study shares. Which device ticks which CRF box was two hard-coded rows. Whether a study receives DICOM was an instance-wide property naming study OIDs, changeable only by editing a file on the server and restarting. Which item an inference metric lands in was a literal — `I_NAMD_OD_IRF_MM3` — in a shared populator, as were `F_NAMD_VISIT` and the randomisation group names `AI_SHOWN` / `AI_HIDDEN`. Each of those is a study that cannot exist without a code change.

**Decision.** Three catalogues, all resolving **site → parent → configuration → code default**, so a site inherits its study and an absent row means *as before*:

1. **`imaging_modality`** + **`imaging_modality_item_binding`** — what a study photographs, on which device, and which CRF item each acquisition ticks. Role-based binding rows (`performed` / `not_performed_reason` / `initials`, per eye) rather than columns, so the Visitenplan's three-items-per-modality shape grows without another migration. Deliberately **separate from the existing `modality` table**, which is a global catalogue of *measurements* with a value per eye — same word, different thing.
2. **`study_setting`** — whether a study receives DICOM, accepts uploads, runs inference, exports bundles, offers the today's-visits list.
3. **`study_item_binding`** — which item a study means by a role the shared code asks for.

**Consequences.**

- **The auto-ticker starts the form that carries the box** (via `EventCrfEnsurer`, inheriting its refusal to revive a removed form). Previously it wrote nothing when no CRF was open, so the checklist silently disagreed with the files until an operator noticed.
- A DICOM whose calling AE title matches `auto_match_ae_title` is **classified on arrival**.
- **Arm names are translated at the boundary.** `armForSubject` / `armForEvent` map whatever a study calls its groups onto one fixed pair of tokens, so the seven masking call sites keep comparing against a constant. An earlier version of this change compared the raw name and would have **silently unblinded** a study that renamed its hidden group — nothing would have reported it, and analysis would have been the first to find out. Handing the raw name outward is the design that fails quietly.
- Retiring a modality is a **status change**: files filed under it keep naming it, and an audit row explaining a CRF value must stay resolvable after somebody tidies the catalogue.
- A binding's item OID is **checked to exist** before it is accepted. A binding pointing at nothing does not fail loudly — it stops ticking, and an un-ticked box reads exactly like a modality that was not performed.
- Audit types 131–134 cover catalogue and setting changes. A study that stops receiving DICOM because somebody flipped a switch looks, from the inbox, exactly like a camera that stopped sending.
- `SharedControllersHaveNoStudyLiteralsTest` is a **ratchet**: the remaining literals are documented fallbacks, the list may shrink and must never grow.

**Reversible** — every table is additive and every default preserves prior behaviour; an instance that sets nothing behaves exactly as it did.

**Out of scope.** Deleting the fallback literals (waits for every deployment's studies to have rows); migrating the measurement `modality` table's per-eye aliases onto the role-based binding shape (possible later, not needed now).

---

## DR-028 — An export carries the evidence, and says what a person did not write

**Date:** 2026-09-19
**Status:** Accepted
**Owner:** Lead Developer (Lukas Kuchernig)
**Related:** DR-022, DR-025, DR-027; `BundleExportWriter`, `FileItemValue`, `SubjectExportApiController`, `ItemDataBean`/`ItemDataDAO`, `SubjectExportBundleDatabaseIT`, `SubjectExportProvenanceDatabaseIT`.

**Context.** "Export the subject" produced text only. For a study whose endpoint is an image, that is not an export: the OCT volumes, the fundus photographs, the segmentation masks and the files attached to CRF items never left the server. Worse, a FILE item exported as a **server path** — worthless to the recipient, who cannot reach that filesystem, and a disclosure of the directory layout of a machine holding patient data to anyone who receives a casebook. Meanwhile the numbers the platform wrote into CRFs — an auto-ticked checklist box, an inference metric — were indistinguishable in the output from a figure a clinician typed.

**Decision.** The subject bundle (`format=bundle`, off unless `export.bundle.enabled`) is a zip of `casebook.xml`, `casebook.csv`, the acquisitions, the CRF file attachments, the inference artifacts, and `manifest.json` **last**. `ItemData` elements carry `muw:SourceKind`, `muw:IngestItemId` / `muw:RetinalJobId`, and — inside a bundle — `muw:ManifestPath`. FILE items export as their filename in every format, via one implementation (`FileItemValue`).

**Consequences.**

- **The manifest is last, deliberately.** A bundle without one is an incomplete bundle, so a truncated download is detectable rather than silently short.
- **An omission is named, never silent.** A file outside the store, a missing file, an AI artifact withheld from a blinded recipient: each appears in `omitted[]` with a reason. A recipient must be able to tell "this subject had no scan" from "the scan is gone" — and a blinded export that looks complete is worse than one that says so.
- **Blinding follows the data out of the platform**, and splits on what the artifact *is*: the model's reading is withheld, the rendering of the eye is kept. A physician is blinded to the algorithm, not to their patient. An unanswerable arm lookup withholds.
- **Every path is confined before it is read** — the acquisition store, the retinal artifact store, the CRF file store, each separately. Those paths come from rows an unauthenticated ingress can write; reading one unchecked turns an export into an arbitrary file read.
- **`muw:ManifestPath` appears only where the bundle really carries the file.** A casebook pointing at an entry that is not in the zip reads as evidence that has merely been misplaced, which is worse than a casebook that says nothing.
- **`ItemDataBean` now carries provenance.** The columns had existed since nAMD Slice 3 and DR-025 P1-5, but `ItemDataDAO` declared them without mapping them — so every consumer holding a bean saw an operator entry. Absence is normalised back to null, because `EntityDAO` turns SQL NULL into `0L` and "job 0 wrote this" is a false claim, not a missing one.
- **The path leak had three routes**, not one: the per-subject export, `ExtractBean` (tab/CSV/SPSS/SAS), and `OdmExtractDAO` (dataset ODM). Fixing the first two would have left the third. The rule keys on the declared data type, never on the value looking path-like — free text with a slash in it is clinical data.
- **The endpoint writes the zip to the response directly.** `ResponseEntity<?>`'s wildcard erases the body type, so Spring never selects `StreamingResponseBodyReturnValueHandler` and falls through to the message converters, which cannot write a lambda — a 500 for every caller. Narrowing the return type is not available: the same method answers with JSON errors and with `byte[]`. Nothing is buffered in heap either way.

**Reversible** — the bundle is a new format behind a per-study setting; the annotations are additive attributes in a private namespace. The FILE-path substitution is not reversible in spirit: emitting server paths again would reintroduce the disclosure.

**Follow-ups landed (2026-09-19).**

- **The dataset bundle (P3.8)** goes through the export-job queue: one zip, one manifest, a folder per subject (`subjects/<label>/`), registered under a new `export_format` row 6 (`application/zip`) so nothing that trusts the mime type hands a browser an archive labelled as text. Blinding is decided **per subject** — two subjects of one dataset can be in different arms — and the manifest says `mixed` when they differ rather than pretending one answer for the archive. The gate is applied at enqueue *and* in the worker: a study that switches the export off must not have its imaging leave the platform because a job was already waiting. The SPA queues it and polls; nothing is built on the request thread.
- **Blinding now fails closed on screen as well.** The viewer's arm lookup used to return "not the hidden arm" on a database error, showing AI output to a clinician the trial had randomised not to see it; the export path had already decided the opposite. An unblinding event is one whether or not a file moved, so the two halves of the platform now agree. Only treating roles are affected.
- **The four nAMD flag bindings hold OIDs**, like every other `study_item_binding` row. They had been seeded with item *names* because the query that read them matched on `item.name`; the code matches on `oc_oid` now, and an additive changeset (`lc-muw-2026-12-01-namd-flag-bindings-oid.xml`) corrects the seeded rows without touching the deployed seed.

## DR-029 — One front door for every file a device exports

**Status:** Accepted (2026-09-20). Lands with the combined uploader (`/app/upload`, `/app/ingest-inbox/upload`).

**Context.** Phase 3 unified everything *behind* the front doors — one `ingest_item` queue, one artifact store, one resolver, one inbox, one bind service — but left the doors themselves as they were: an OCT page that took `.e2e` and nothing else, and an image page that took JPEG/PNG and nothing else. The Zeiss devices on the HealthAEye Visitenplan (Clarus fundus camera, PlexElite OCTA) export **DICOM**, which fitted neither, and an operator standing at a device had to know which page a file belonged to before they could hand it in. Both pages also trusted a claim about the file — the browser's content type on one, the extension on the other — and a Clarus visit is half a dozen files, each of which meant re-picking the visit.

The second problem is the one that decided the shape. A camera that sits in the clinic fills its DICOM header from the hospital system: the real patient name, the hospital ID, the date of birth, the operator. The Optomed path never had this problem because its header carries the label *we* put on the worklist. A Clarus export dropped on an upload page does — and the file it arrives in is the file the export bundle (DR-028) later hands to a researcher.

**Decision.**

1. **One page, any kind, the kind read off the bytes.** `FileKindSniffer` (server) and `lib/fileKind.ts` (page) decide from the leading bytes — PNG and JPEG signatures, `DICM` at 128, the three Spectralis magics — and the claim is ignored. A file the platform cannot name is refused with a message, not stored as "other".
2. **One visit pick for a batch.** The page files every reviewable row against the visit picked once (today's list, a search, or a typed label the backend resolves); a row can still be pointed elsewhere by hand. The OCT route keeps its own per-scan resolution underneath.
3. **The OCT route is delegated to, not moved.** `PublicUploadController` hands an `.e2e` to `PublicOctUploadController.commit(...)` unchanged — per-scan rows, retinal jobs, the async pipeline, undo by job — because a move that alters what an endpoint answers is not a move, and the batch-visit behaviour was the new thing, not that route. Images and DICOM go through `IngestUploadService`, shared by the public and the staff controller; the two older pages stay mounted for one release and redirect.
4. **A DICOM upload is pseudonymised before it is kept, by the sidecar that already has a DICOM parser.** The app stores the file on the volume both containers share and asks `dicom-scp` (`POST /describe`, same shared secret as the ingest hand-off, path confined to the ingest roots) for the exam and device tags and a preview; the sidecar rewrites the file in place first. What goes: `PatientName`/`PatientID` (replaced by the visit's subject label, or blanked when the upload is not yet filed — never a guessed label), birth date and demographics beyond sex, contact details, the physicians and operators, the institution, the hospital's accession and study IDs. What stays: the UIDs (dedup, provenance), the dates (the clinical timeline), the device and its private tags (calibration the research needs; droppable by configuration), the pixels untouched and untranscoded. The file is stamped `PatientIdentityRemoved=YES`; the row gets `deidentified_at`. **If the sidecar is unconfigured or unreachable the upload is refused (503), not stored as-is** — the point of the call is that the file is clean before it is kept. The tags the app receives never include the patient module.
5. **The same uploader behind a login.** `/ingest-inbox/upload` runs the same workbench in staff mode: the session's cookie, the upload attributed to the person (`bound_by_user_id`, a user-bearing audit row), the visits in reach those of the site visibility rather than a configured portal scope, no public throttle. Role-gated like the inbox: whoever may reconcile a file may bring one in.
6. **The throttle distinguishes lookups from commits, on the new prefix only.** The 30/hour budget exists to stop label enumeration through the lookups; a commit needs a real file and is bounded by disk. On `/public/upload/` the lookups keep 30/hour and commits get 300/hour; the older prefixes are left exactly as they were for the release they stay alive.
7. **A study that has turned an ingress off refuses the kind — discreetly on the public page.** The portal scope already folds `ingest.image.enabled` in; the upload's own gate covers DICOM. On the page with no login both answer in the same words as a visit outside the scope, so an anonymous caller learns nothing about which studies exist and what they refuse; behind a login the refusal says why, because the operator can go and change the setting.
8. **The catalogue says the Zeiss devices export DICOM.** `FUNDUS_CLARUS` and `OCTA_PLEXELITE` gain `dicom` in `kinds_accepted` (additive changeset); a DICOM upload bound at upload time is filed under the study's matching modality when exactly one matches the device and kind — a guess between two would be filed as a fact, so two is none.

**Consequences.** One page to bookmark and one QR code at every device; the Clarus and PlexElite workflows exist without a Java DICOM parser; a hospital patient never reaches a database row or an export bundle; the DICOM route is only as available as the `dicom` compose profile — a deployment that wants Clarus uploads without a C-STORE camera still runs the sidecar. What is *not* decided here: relabelling a file whose binding changes later in the inbox (the file keeps a blank identity; the row and the manifest carry the binding), and retiring the two older controllers, which follow once nothing calls them.

**Reversible** — routes redirect, the old controllers and views are untouched, the changesets are additive, and the sidecar endpoint is off at `DICOM_SCP_DESCRIBE_PORT=0`. The pseudonymisation is not reversible for a file that went through it, which is the intent.

## DR-030 — One landmark per concern: top bar, page trail, section rail

**Status:** Accepted (2026-09-20). Lands with the navigation-chrome PR.

**Context.** The Phase E shell grew three ways of saying where the operator is, and they had drifted into each other. The top bar carried the brand, the role's primary navigation (added with the home dashboard) *and* a breadcrumb whose root was the study name — in one type size, on one line — so on the subject page the word *Studienteilnehmer* appeared as a highlighted pill and again as a crumb 200 px later, the study name read as a sixth destination, and on CRF entry the six-level trail wrapped into two-line crumbs at 1440 px while folding away neatly below 1024 px (the header was cleaner on a small screen than on a laptop). The trail fell back to `route.meta.title` on 34 of 41 routes, which is English on a German-first UI. Each page printed its own eyebrow line as well, and the event page a second, clickable in-page trail plus a "back" link: five ways back to the subject on one screen. The side rail, meanwhile, was mounted on 24 views; 14 of them held only links the top bar already carried, one was empty, and two had a job (the CRF entry's section table of contents with fill badges; the subject matrix's statistics button). It cost 224 px on every page it sat on — the Datasets table was clipped beside a rail holding three links — and it came and went between adjacent pages of one workflow, shifting the content column as the operator clicked through.

**Decision.** Three rules, one landmark each.

1. **The top bar is brand, primary navigation and user — nothing else.** The highlighted pill is the section indicator. The active study is a chip beside the user menu that leads to the study picker; the version/build line moved from the rail's footer into the profile menu, so a page without a rail is not a page without a version. The breadcrumb, its store and its composable are gone.
2. **A trail only where a hierarchy exists below the section, rendered in the page header** (`PageHeader`). It lists the *ancestors* as links — `Studienteilnehmer › M-007 › V1 Inclusion` above a CRF — and never the page itself: the H1 is the page. Labels come from the views (German), never from route metadata. Flat pages get no trail; the pill and the heading say where they are. The in-page duplicates (event mini-trail, "Zurück zum Probanden", the subject page's "Zurück zur Probandenmatrix") are removed.
3. **A side rail only where it has a section to navigate.** Two remain: the CRF entry's table of contents, and a real **Studienaufbau rail** (`BuildStudyRail`) listing every build page — tracker, study, parameters, CRFs, visits, groups, rules, sites, modalities, users — filtered by role, current page highlighted. The other 21 views are single-column, centred at the width the page needs (Datasets widened to what its table needs). The subject matrix's statistics button and study facts moved into its header.

**Consequences.** One `<nav>` in the top bar, at most one trail and one section rail per page (each a labelled landmark, which is what the a11y gate checks); page geometry no longer jumps between adjacent pages; the header stops competing with itself for width at laptop sizes; ~24 views lost a block each. The Studienaufbau rail is the pattern for any future section with several pages; a page that wants a rail has to be able to say what section it navigates.

**Addendum 2026-09-22 — a second section.** After the rework, the five instance-level pages — Systemstatus, System-Audit-Protokoll, Passwort-Richtlinie, Anwendungskonfiguration, Geplante Jobs — were linked from nowhere: the top bar's Administrator-only entry led to the audit trail alone and the other four were reachable only by typing their address, including the status page that the cluster-health panel (PR #313) lands on. A status page nobody can reach by clicking does not report an outage. They are now the **System** section, by rule 3: one Administrator-only **System** entry in the top bar, kept to the right of the study chip because everything left of the chip is scoped to the active study and these pages are not, landing on Systemstatus; and a `SystemRail` on all five pages. The rail does no role filtering, since every route in it admits only the Administrator. The entry is current across both of the section's path prefixes (`/admin/`, `/system/`), which is why it is not a primary-nav item with a single `to`.

**Reversible** — the components are additive and the views are the only consumers; restoring a rail on a view is one block. The breadcrumb store is deleted rather than kept dormant, because a second way to publish a trail is how the drift started.

---

## DR-032 — Device exports reach the platform from the folder they are exported to

**Date:** 2026-09-23
**Status:** Accepted
**Owner:** Lead Developer (Lukas Kuchernig)
**Related:** DR-025 (Optomed bridge, the precedent), DR-029 (the one front door), DR-031 (Remidio pull); `deploy/export-watcher/ExportWatcher.ps1`; `web/src/spa/src/lib/e2eParser.ts` (the reader this ports).

**Context.** Two of the HealthAEye modalities have no path to the platform at all: the Zeiss Clarus exports three DICOM objects per capture (the photograph and two Raw Data objects) and the Heidelberg Spectralis one `.e2e` per export, both to a folder on the acquisition PC, and the photographer re-uploads each file through the browser page. The page works, but it is a second job per capture done by the person least placed to do it, and it is where files get forgotten. The Optomed bridge (DR-025) already solved the same shape for the Lumo: a tray app on the clinic PC reads the header, asks `/resolve`, and posts through the public front door.

**Decision.** A second tray app of the same shape, **one instance per acquisition PC**, each watching that PC's export folder: on the Clarus PC beside the bridge, on the Spectralis PC alone. It reuses the bridge's DICOM reader and **ports the page's `.e2e` reader** (chunk directory → patient id, acquisition date, laterality per volume) so an export with several volumes is uploaded once per volume, as the page does. It makes **one `/resolve` call per sweep** for all scans found, because the public front door budgets lookups at 30 per hour per client and a Clarus session is easily 40 files. An `.e2e` that does not resolve is **parked** (`park=true`): the OCT route refuses a scan with neither a visit nor the park flag, because a scan on a visit starts inference and one without must not. No server change: the watcher speaks exactly the API the page speaks, and the item that lands is indistinguishable from a hand upload.

**Consequences.**

- The convention every device ingress here now shares is spelled out once: *the patient id typed into the device is the study subject label* — Clarus PatientID, HEYEX patient id or, after anonymisation, the surname slot where MUW keeps it, Remidio MRN, Optomed worklist entry. It belongs in the HealthAEye SOP.
- The two `.e2e` readers must be kept in step; the PowerShell port names the TypeScript file and repeats its offsets. A format drift shows up first as a parked file with an empty label.
- An `.e2e` date the watcher read out of the file arrives at the server as an operator-typed one (`acquisition_date_source = operator`), because the form field cannot say where it came from — the page's client-side parse has the same limit. So the bind-time date check from #312 does not cover `.e2e` uploads from either. A trusted `dateSource=file` hint from the two readers is the follow-up if that matters.
- The exports stay on the PC, moved aside and never deleted; Clarus headers carry names until the platform's copy is pseudonymised on ingest. Retention on the PC is a local decision, as for the Optomed `Studies\` folder.
- Verified 2026-09-23 from a Mac, in a PowerShell 7 container against the dev stack: a real Spectralis export (two volumes) bound to its subject's visit of that day with inference queued, a synthetic unknown-label `.e2e` parked into the inbox with its hints, Lumo and Clarus-shaped DICOMs recognised (duplicates answered 409 and moved aside), a fresh DICOM filed. The tray itself is the bridge's, unchanged in shape.
- Verified 2026-09-24 on real Clarus 700 exports: the photograph (JPEG baseline, Ophthalmic Photography 8-bit) reads label, date and eye, files bound to a visit of that day and pseudonymised by the sidecar; the two Raw Data objects the Clarus writes beside it are set aside. Two reader defects surfaced and are fixed in both the watcher and the Optomed bridge: the reader stopped at the first undefined-length sequence (the Clarus writes two in group 0008, before the patient group), and the undefined-length sentinel was written as the hex literal `0xFFFFFFFF`, which PowerShell parses as the Int32 -1, so the check never matched.

**Reversible** — a script on two PCs; uninstall removes the startup shortcut. No schema, no server code.

---

## Future decisions (open)

- DR-007 — iText 2.1.2 replacement: OpenPDF vs. Apache PDFBox (decide before Phase D library long-tail)
- DR-009 — Spring Authorization Server adoption (replaces deprecated Spring Security OAuth2 — superseded by DR-014's reverse-proxy SSO architecture; close as obsolete)
- DR-011 — Database connection pool: HikariCP vs. DBCP2 (recommend HikariCP; decide during Phase C)
- DR-012 — Date/time API: Joda-Time → `java.time` (recommend `java.time`; decide during Phase B)
- DR-013 — L2 cache: EhCache 3 vs. Caffeine + JCache (recommend Caffeine + JCache for Spring Boot 3 default; decide during Phase B)
- DR-016 — JIT vs LOOKUP_ONLY provisioning default for SSO users (decide during Phase D execution after MedUni Wien admin-process review)
- DR-017 — Authority/role mapping from SSO attributes (institution-specific; document a mapping-rule format)
