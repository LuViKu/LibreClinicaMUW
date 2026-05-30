# Phase D — Execution playbook

**Goal:** retire MD5/SHA-1 password storage, integrate institution-agnostic SSO via reverse-proxy pre-authentication, and (in a parallel workstream) work down the abandoned-library long tail.

**Scope split:**
- **D-Sec (primary, ~1.5–2 weeks):** auth/security work. Production-deployment-blocking. This playbook focuses here.
- **D-Libs (parallel, ~2–3 months):** library long-tail (iText, POI, FOP, Quartz, GWT, Prototype.js, EhCache, log4jdbc). Independent of D-Sec; can run alongside without coupling. Documented in [§ D-Libs](#d-libs--library-long-tail) for tracking only.

**Pre-flight reference:** [phase-d-pre-flight-inventory.md](phase-d-pre-flight-inventory.md) — current-state inventory of the security stack and library versions.

**Lead-in:** Phase C closed 2026-05-30 (`lc-develop @ 646209e36`). Spring Security 6.4.4 + Spring Boot 3.5 + Hibernate 6.4.10 + Java 21 + jakarta. Phase D builds on `SecurityConfig` (the Java `SecurityFilterChain @Bean` from C.14) and adds: a new pre-auth filter, a new `UserDetailsService` path, a Liquibase changeset, an Apache-mod_shib compose sidecar (reference deployment), an updated login JSP affordance, and a password encoder swap.

---

## Decisions feeding this playbook

Pre-ratified inputs (do not re-litigate during execution):

- **[DR-014 — Institution-agnostic SSO via reverse-proxy pre-authentication](decision-record.md#dr-014--institution-agnostic-sso-via-reverse-proxy-pre-authentication)** — Accepted 2026-05-30. App speaks header-based pre-auth via `RequestHeaderAuthenticationFilter`; protocol (SAML/OIDC/OAuth) handled by the reverse-proxy sidecar. Provider swap requires only sidecar + env-var change, never code change. MedUni Wien Shibboleth is the reference deployment.
- **[DR-015 — Password encoder migration: MD5/SHA-1 → bcrypt via DelegatingPasswordEncoder](decision-record.md#dr-015--password-encoder-migration-md5sha-1--bcrypt-via-delegatingpasswordencoder)** — Accepted 2026-05-30. New writes use bcrypt (cost 10); legacy MD5/SHA-1 hashes verified on read and lazy-migrated to bcrypt on next successful login.
- **LDAP coexists** — `OpenClinicaLdapAuthoritiesPopulator` + `BindAuthenticator` stay in the chain as a third path alongside SSO (when header set) and local password. Role narrows to local-network service accounts over time.
- **GCP / 21 CFR Part 11 §11.50 e-signatures** — re-authentication via the reverse proxy is the design; legal/regulatory ratification still pending. **Don't ship the Sign Subject flow over SSO until ratified** — for the initial rollout, e-signatures still require a local password challenge even for SSO-authenticated sessions.

---

## Pre-flight checklist

Before sub-phase D.0 begins:

- [ ] `lc-develop` HEAD verified — Phase C closure landed (`646209e36` or later)
- [ ] `mvn -P integration-tests -Ddb.test=lc-test-pg test` baseline = 112/112 green (preserved Phase C gate)
- [ ] `docker compose up --build` smoke baseline: auth POST root/12345678 → MainMenu 200
- [ ] MedUni Wien IT contacted re: SP registration for the production `mod_shib` reference deployment (institutional dependency — needed for production cutover, NOT for development; dev uses a Shibboleth-Testbed or SAMLtest.id IdP)
- [ ] Test IdP chosen for CI/dev: **default = Shibboleth-Testbed Docker image** (https://github.com/UniconLabs/shibboleth-idp-dockerized); fallback = SAMLtest.id (free public test SAML IdP)
- [ ] `feature/phase-d-sec-prep` branch exists and tracks `lc-develop` (this playbook lands on that branch)

---

## Sub-phase ordering and verification gates

The cliff hypothesis for D-Sec is **untrue** — unlike Phase C.14 where Boot's autoconfig forced one big bootstrap rewrite, Phase D-Sec splits cleanly into 8 small commits. Each sub-phase has a deterministic gate; if a gate fails, rollback is `git reset --hard` to the prior tag.

| Sub-phase | What | Risk | Gate |
|---|---|---|---|
| **D.0** | Characterisation tests for the current auth + audit-login flow | Low | New tests pass; existing 112/112 IT preserved |
| **D.1** | `DelegatingPasswordEncoder` swap + legacy MD5/SHA-1 fallback + lazy bcrypt rehash | Medium | `OpenClinicaPasswordEncoderTest` green incl. new bcrypt + lazy-rehash cases; smoke auth POST root/12345678 → MainMenu 200 (MD5 hash matched + lazy-rehashed) |
| **D.2** | Liquibase: `user_account.external_id` + `external_id_provider` columns + composite index | Low | Schema migration applies cleanly; existing 112/112 IT green |
| **D.3** | `RequestHeaderAuthenticationFilter` wired into `SecurityConfig` behind `libreclinica.sso.enabled` feature flag (default off) | Medium | With flag off: smoke is identical to Phase C closure. With flag on + `REMOTE_USER` header injected by curl: filter writes audit row, session established, no local password prompted |
| **D.4** | `UserProvisioningStrategy` interface + `LookupOnlyStrategy` (default) + `JitProvisioningStrategy` (opt-in via env) | Medium | Unit tests for both strategies; IT for lookup-success / lookup-fail / JIT-create paths |
| **D.5** | `audit_user_login` enum — add `SSO_LOGIN` + `SSO_LOGIN_FAILED` codes; SSO login writes through `OpenClinicaUsernamePasswordAuthenticationFilter`'s audit hook moved into a shared `LoginAuditService` | Low | IT verifies audit row written with new enum values; legacy local-login audit unchanged |
| **D.6** | Login JSP affordance — "Sign in with Institutional Account" button visible when `libreclinica.sso.enabled=true`; reads `libreclinica.sso.button-label` and `libreclinica.sso.entry-url` from config so each deployment can brand the button | Low | JSP renders the button only when flag on; click takes user to `/sso/login` (a redirect that the reverse proxy intercepts) |
| **D.7** | Docker compose `mod_shib` sidecar (`apache` service) — the reference deployment cookbook entry. Lives in `docker-compose.sso.yml`, opt-in via `docker compose -f compose.yml -f docker-compose.sso.yml up` | Medium | Compose stack with sidecar boots; curl through the Apache port (8443) with a Shibboleth-Testbed-signed assertion → MainMenu 200; curl direct to Tomcat without the header (bypass attempt) → 403 from `trusted-proxy.allowed-cidrs` enforcement |
| **D.8** | SSO deployment cookbook (`docs/development/sso-deployment-guide.md`) — Apache+mod_shib, Apache+mod_auth_openidc, oauth2-proxy, Keycloak Gatekeeper, AWS ALB OIDC, Cloudflare Access, no-SSO | Low | Markdown only; no code change. Each cookbook entry includes the relevant `LIBRECLINICA_SSO_*` env values |
| **D.9** | 2FA reconciliation — `TwoFactorService` activation paths skip SSO-bound users; admin-UI "Manage Users" surfaces hide 2FA enrolment for SSO users | Low | Unit tests for the skip predicate; manual UI check that SSO users don't see 2FA pages |
| **D.10** | E-signature re-auth scaffolding — `/sso/reauth` endpoint that triggers proxy re-challenge; **wired behind a separate flag `libreclinica.sso.reauth.enabled` (default OFF)**. The Sign Subject flow continues to require a local password challenge until legal/regulatory ratifies proxy re-auth as §11.50-compliant | Medium | With flag off: no behaviour change. With flag on: `/sso/reauth` returns the right 302 / proxy-challenge URL |
| **D.11** | Reconciliation — playbook closure, MIGRATION.md update, characterisation tests retained as IT gate, smoke pass with the full SSO sidecar |  | All gates green; lc-develop @ Phase-D-closed tag |

### Order rationale

- **D.0 first** so every later sub-phase has a regression check. The encoder swap (D.1) is the highest-risk single sub-phase; pinning audit-login behaviour before the swap means the swap can't quietly break the audit trail.
- **D.1 before D.3** so the encoder migration is independently verifiable — the SSO work doesn't mask a hash-storage bug.
- **D.2 before D.3** so the SSO column exists before any code reads/writes it.
- **D.3–D.5 in lock-step** — filter, provisioning strategy, audit row format all touch the same code paths. Each one is small enough to be its own commit; bundling would obscure root cause if a gate fails.
- **D.6 (UI) decoupled** from filter wiring so JSP changes can land independently and the same JSP supports any future SSO provider via the configured button label.
- **D.7 (compose sidecar) last among the core integration sub-phases** because it requires a live IdP — pulling it forward would block D.0–D.5 on the Shibboleth-Testbed availability.
- **D.8 docs** can land anytime after D.7 (the cookbook references the live sidecar config).
- **D.9–D.10 cleanup** — 2FA reconciliation and e-signature re-auth scaffold are non-blocking on production rollout (e-sig stays on local password until ratified anyway).

---

## D.0 — Characterisation tests for the current auth + audit-login flow

Mirror the Phase B.0 (Castor) and Phase C.0 (boot contract) characterisation patterns: pin the **observable behaviour** of the current auth surface before any code change so the encoder swap and the SSO filter wiring can't silently regress.

### What to characterise (new IT classes under `core/src/test/java/.../contract/`)

- `AuthFlowContractIT` — POST `/j_spring_security_check` with `j_username=root&j_password=12345678` (MD5-hashed in the seed dataset). Expectations pinned:
  - 302 to `/MainMenu`
  - Session established (`JSESSIONID` cookie set, `SecurityContextRepository` populated)
  - `audit_user_login` row written: `login_status_code = SUCCESSFUL_LOGIN`, `user_name = root`, `user_account_id` non-null, `details` matches the legacy format.
- `AuthFailureContractIT` — wrong password, locked account, disabled account, status_id != AVAILABLE. Each produces the documented `audit_user_login` row and the documented exception → redirect path.
- `PasswordEncoderContractTest` (unit, extends existing `OpenClinicaPasswordEncoderTest`) — adds explicit cases:
  - bcrypt-formatted hash (`$2a$10$…`) verifies against the right plaintext
  - SHA-1-formatted hash (40 hex chars, no prefix) verifies (legacy fallback)
  - MD5-formatted hash (32 hex chars, no prefix) verifies (legacy-legacy fallback)
  - bcrypt match returns `true`-with-upgrade-needed=false; SHA-1/MD5 match returns `true`-with-upgrade-needed=true (signals lazy rehash)
- `LdapBindContractIT` — only run when an LDAP container is available (skipped in default CI; opt-in profile). Pins the current `BindAuthenticator` flow so the new filter chain doesn't break LDAP users.
- `SessionLifecycleContractIT` — login + concurrent-session-limit (1 max) + logout + re-login. Pins `OpenClinicaSessionRegistryImpl` behaviour because D.3's pre-auth filter must not break it.

### Acceptance gate for D.0

- `mvn -P integration-tests -Ddb.test=lc-test-pg test` → **117/117** (112 baseline + 5 new D.0 contracts), 0 errors, 0 failures, 0 skipped.

### Rollback

- Pure test additions. Rollback is `git revert <commit>`.

---

## D.1 — `DelegatingPasswordEncoder` + bcrypt + legacy fallback + lazy rehash

### Changes

1. New `LegacyMd5Sha1PasswordEncoder` (`core/src/main/java/.../core/LegacyMd5Sha1PasswordEncoder.java`) implementing `PasswordEncoder`:
   - `encode()` throws — this encoder only **reads** legacy hashes; new hashes must be bcrypt.
   - `matches(raw, encoded)` returns true if `MD5(raw) == encoded` OR `SHA1(raw) == encoded` (length-hinted: 32 vs 40 hex chars).
2. `PasswordEncoderConfig` Java `@Configuration` (`core/src/main/java/.../config/PasswordEncoderConfig.java`):
   - `passwordEncoder` bean = `DelegatingPasswordEncoder` with mapping:
     - `bcrypt` → `BCryptPasswordEncoder(10)`
     - empty/null prefix → `LegacyMd5Sha1PasswordEncoder` (the default for legacy rows)
3. Move the encoder bean wiring out of `applicationContext-core-security.xml` (the existing `shaPasswordEncoder` / `md5PasswordEncoder` / `openClinicaPasswordEncoder` beans are replaced by the new `passwordEncoder` bean from `PasswordEncoderConfig`).
4. `OpenClinicaUsernamePasswordAuthenticationFilter.successfulAuthentication` — after the existing audit-login write, check `encoder.upgradeEncoding(currentHash)`. If true, rehash with bcrypt and persist via `UserAccountDao.updatePasswordHash(userId, newHash)`.
5. `UserAccountDao.updatePasswordHash` — new method, single UPDATE; transactional.
6. `OpenClinicaPasswordEncoderTest` — assert the new bcrypt+legacy mappings, the rehash-on-match contract, and that legacy `OpenClinicaPasswordEncoder` is gone (compile error is the test).
7. **Delete** `OpenClinicaPasswordEncoder` ([core/src/main/java/.../core/OpenClinicaPasswordEncoder.java](../../../core/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/core/OpenClinicaPasswordEncoder.java)). It has no other callers post Spring Security 6 (DAO providers and SecurityManager will inject the new `PasswordEncoder` bean instead).
8. `SecurityManager` ([core/src/main/java/.../core/SecurityManager.java](../../../core/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/core/SecurityManager.java)) — inject the new `PasswordEncoder` bean (was injecting the deleted `OpenClinicaPasswordEncoder`).
9. Reset-password / change-password flows: hash with bcrypt directly (use the `passwordEncoder.encode(rawPassword)` — `DelegatingPasswordEncoder.encode` writes the `{bcrypt}` prefix).

### Risk

- **R-D1a** Encoder bean ID change breaks XML references. Audit `<ref bean="openClinicaPasswordEncoder"/>` + `<property name="passwordEncoder" ref="…"/>` across the security XMLs before deletion.
- **R-D1b** Lazy rehash needs a transactional context — the auth filter doesn't have one today. Solution: dispatch the rehash on a `@Transactional` service method (`PasswordRehashService.rehashAfterSuccessfulLogin`).
- **R-D1c** bcrypt cost 10 verification latency on production hardware needs measurement. Cost-10 ≈ 60–120ms on modern x86; spike on the first login per account is acceptable. If average is > 200ms, retune (cost 9 or revisit argon2id per DR-015's revisit trigger).
- **R-D1d** Tests / fixtures that seed users with raw-string passwords break. Audit DBUnit XML files in `core/src/test/resources/` for `passwd=` and replace with bcrypt-encoded values OR add a `@BeforeAll` re-hash helper.

### Acceptance gate

- All D.0 contract tests + 112 prior IT + `OpenClinicaPasswordEncoderTest` → green.
- Smoke: `docker compose up --build` clean DB → auth POST root/12345678 → MainMenu 200 (MD5 hash matches via fallback). Login a SECOND time → `audit_user_login` shows two SUCCESSFUL_LOGIN rows; `user_account.passwd` after the second login starts with `{bcrypt}$2a$10$…` (lazy rehash landed on the first login; second login uses the bcrypt path).

### Rollback

- `git revert <commit>` of the D.1 commit. The new `passwordEncoder` bean is config-only; no schema change in D.1.

---

## D.2 — Liquibase: `user_account` external-identity columns

### Changes

1. New changeset `core/src/main/resources/migration/lc-muw-2026-XX-XX-add-external-identity.xml` (date when execution starts):
   - `ALTER TABLE user_account ADD COLUMN external_id VARCHAR(255) NULL`
   - `ALTER TABLE user_account ADD COLUMN external_id_provider VARCHAR(64) NULL`
   - Composite unique index `idx_user_account_external_identity` on `(external_id_provider, external_id)` — provider-scoped uniqueness so an `okta:user-abc` doesn't collide with a `shibboleth-meduniwien:user-abc` if both providers ever exist in one deployment.
   - **Both columns nullable** so existing local-account rows pass.
2. Add to `db.changelog-master.xml`.
3. `UserAccount` entity — `@Column private String externalId; @Column private String externalIdProvider;` plus getters/setters and a `UniqueConstraint` annotation matching the index.
4. `UserAccountDao.findByExternalIdentity(provider, externalId)` — new finder.

### Risk

- **R-D2a** Liquibase changelog ordering: append to `db.changelog-master.xml` at the bottom; never insert into the middle.
- **R-D2b** Adding nullable columns to a 0-row test DB is trivial; on a populated production DB this is a metadata-only operation (PostgreSQL 13+ supports non-rewriting ADD COLUMN for nullable types).

### Acceptance gate

- `mvn -P integration-tests -Ddb.test=lc-test-pg test` → 117+/117 green (test schema bootstrap runs the new changeset; new finder has at least one unit test for null + non-null cases).
- Compose smoke: stack boots cleanly with the new columns; existing auth flow unaffected (the columns are unused for local-account users).

### Rollback

- Liquibase doesn't support runtime rollback of arbitrary changesets without `<rollback>` blocks. Include `<rollback>` in the changeset (DROP INDEX + DROP COLUMN × 2) so `liquibase rollback-to-tag` works.

---

## D.3 — `RequestHeaderAuthenticationFilter` wired into `SecurityConfig`

### Changes

1. New `SsoProperties` `@ConfigurationProperties("libreclinica.sso")` (`web/src/main/java/.../config/SsoProperties.java`):
   - `boolean enabled = false`
   - `Header header` with `principal` (default `REMOTE_USER`), `email`, `displayName`, additional attribute names (a `Map<String,String> attributeHeaders` for institution-specific extras)
   - `TrustedProxy trustedProxy` with `List<String> allowedCidrs`
   - `String entryUrl` (where the "Sign in with Institutional Account" button points — default `/sso/login`)
   - `String buttonLabel` (default `"Sign in with Institutional Account"`)
   - `Provisioning provisioning` with `strategy` (LOOKUP_ONLY / JIT) and `defaultRole`
2. `SecurityConfig` (or a new `SsoSecurityConfig`) — conditional bean:
   - When `libreclinica.sso.enabled=true`:
     - Register `RequestHeaderAuthenticationFilter` (Spring Security built-in) with the configured principal header, exception=true (so absence → next filter).
     - The filter calls `PreAuthenticatedAuthenticationProvider` (also built-in) which delegates to `userDetailsServiceWrapper(ssoUserDetailsService)`.
     - `ssoUserDetailsService` (new) — given a `PreAuthenticatedAuthenticationToken`, resolves to a `UserDetails` via `UserAccountDao.findByExternalIdentity` (D.4 wires the strategy).
     - Network guard: a custom `RequestMatcher` ahead of the filter requiring the source IP to be in `trustedProxy.allowedCidrs`; if not, the request is treated as if no SSO header was present (the request can still authenticate via the local username/password path).
   - When `libreclinica.sso.enabled=false`: the filter is not registered at all; behaviour identical to Phase C closure.
3. Filter order: pre-auth filter **before** `OpenClinicaUsernamePasswordAuthenticationFilter`. If pre-auth succeeds, the username/password filter is bypassed for that request.

### Risk

- **R-D3a** Header spoofing if Tomcat is reachable directly. Mitigated by the CIDR allowlist + deployment doc requirement that Tomcat binds to compose-internal network only.
- **R-D3b** Filter ordering surprises — Spring Security 6.4's filter-chain DSL is opinionated; verify the pre-auth filter actually runs before `OpenClinicaUsernamePasswordAuthenticationFilter` via `http.addFilterBefore(...)`.
- **R-D3c** Session-fixation protection vs pre-auth — Spring Security defaults to `SessionFixationProtectionStrategy.NEW_SESSION` which is what we want for SSO too (the session ID rotates on first SSO request post-login).

### Acceptance gate

- D.0 + D.1 + D.2 gates all still green.
- New IT `SsoPreAuthFlowIT` — POST a request with `REMOTE_USER: testuser@meduniwien.ac.at` header from a trusted CIDR → 302 to MainMenu (no password prompted) + `audit_user_login` SSO_LOGIN row.
- Same request from an untrusted CIDR → no SSO acceptance; local auth path takes over.
- `libreclinica.sso.enabled=false` (default in CI compose smoke) → identical to Phase C closure.

### Rollback

- `git revert <commit>` of the D.3 commit. The bean is `@ConditionalOnProperty("libreclinica.sso.enabled")`, so even the bean wiring rolls back cleanly.

---

## D.4 — `UserProvisioningStrategy` interface

### Changes

1. `UserProvisioningStrategy` interface (`core/src/main/java/.../service/sso/UserProvisioningStrategy.java`):
   - `UserAccount resolveOrProvision(PreAuthenticatedAuthenticationToken token, Map<String,String> attributeHeaders)`
2. `LookupOnlyStrategy` (default) — looks up by `(external_id_provider, external_id)`. If not found, throws `UsernameNotFoundException` → request falls through (or 403 depending on config).
3. `JitProvisioningStrategy` — on miss, creates a new `UserAccount` row with attributes mapped from headers + `default-role`. Posts an `AdminNotificationEvent` so admins can review.
4. `ssoUserDetailsService` from D.3 delegates to the configured strategy.
5. Strategy selected via `libreclinica.sso.provisioning.strategy=LOOKUP_ONLY|JIT` env var, default LOOKUP_ONLY.

### Risk

- **R-D4a** JIT provisioning grants `default-role` to anyone the IdP authenticates. If an institution's IdP authenticates users outside the clinical-trial cohort, JIT is dangerous. Doc must call this out; recommend LOOKUP_ONLY for production GCP-validated rollouts.
- **R-D4b** Username collisions during JIT — what if the principal value matches an existing `user_name` of a local account? Strategy: JIT creates with `user_name = "${principal}-sso"` if the bare principal collides, or fails with a clear admin-notification error.

### Acceptance gate

- Unit tests for both strategies (3 cases each: hit, miss, conflict).
- IT for the JIT path (create + login + audit row).
- IT for the LOOKUP_ONLY miss path (unknown principal → 403 + audit FAILED_LOGIN with details).

### Rollback

- `git revert <commit>`; provisioning is config-only with a default of LOOKUP_ONLY which is the safe path.

---

## D.5 — `audit_user_login` enum + `LoginAuditService` extraction

### Changes

1. `LoginStatus` enum ([core/src/main/java/.../domain/technicaladmin/LoginStatus.java](../../../core/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/domain/technicaladmin/LoginStatus.java)) — add:
   - `SSO_LOGIN(6)` — SSO pre-auth succeeded
   - `SSO_LOGIN_FAILED(7)` — pre-auth header present but provisioning rejected
2. Extract the audit-write code from `OpenClinicaUsernamePasswordAuthenticationFilter.auditUserLogin()` into a new `LoginAuditService` (`core/src/main/java/.../service/audit/LoginAuditService.java`) so both the local-password filter and the new SSO pre-auth path call the same hook.
3. SSO pre-auth path calls `loginAuditService.record(LoginStatus.SSO_LOGIN, userName, userAccountId, details)` on success and `SSO_LOGIN_FAILED` on provisioning rejection.
4. Liquibase changeset to update any constraint check on `audit_user_login.login_status_code` if one exists (verify; the existing column is just `int`).

### Risk

- **R-D5a** Existing audit-report screens may switch on the enum value — verify the StudyAuditLog + AuditUserActivity screens render unknown codes gracefully (likely fine; they just show the int).

### Acceptance gate

- IT verifies SSO login writes the new code; local login unchanged.
- StudyAuditLog screen still renders post-SSO-login.

### Rollback

- `git revert <commit>`. The new enum entries don't break the existing `int` column.

---

## D.6 — Login JSP affordance

### Changes

1. [web/src/main/webapp/WEB-INF/jsp/login/login.jsp](../../../web/src/main/webapp/WEB-INF/jsp/login/login.jsp) — add a conditional `<c:if test="${ssoEnabled}">` block above the username/password form rendering a button with `libreclinica.sso.buttonLabel` text pointing at `libreclinica.sso.entryUrl`.
2. Inject the SSO config into the login model via a `LoginPageController` (already exists? if not, a `HandlerInterceptor` that puts `ssoEnabled`, `ssoButtonLabel`, `ssoEntryUrl` into the model).
3. CSS: use the existing Phase E button styling (see `web/src/main/webapp/includes/css/`) so the SSO button looks like a first-class entry point, not an afterthought.

### Risk

- **R-D6a** The login JSP currently has known issue `/pages/login/login` returning HTTP 500 — see [`phase-e/known-issues.md`](phase-e/known-issues.md). Verify D.6 doesn't worsen it; ideally diagnose during this sub-phase.

### Acceptance gate

- With `libreclinica.sso.enabled=false`: login page identical to today.
- With `libreclinica.sso.enabled=true`: SSO button visible, links to `/sso/login`.
- The button label is configurable — set `LIBRECLINICA_SSO_BUTTON_LABEL="Sign in with Okta"` in env, button reads "Sign in with Okta".

### Rollback

- `git revert <commit>` — JSP-only change.

---

## D.7 — Docker compose `mod_shib` sidecar (reference deployment)

### Changes

1. New `docker-compose.sso.yml` (opt-in, layered onto `compose.yml`):
   ```yaml
   services:
     apache-shib:
       image: unicon/shibboleth-sp:latest   # or a pinned version
       depends_on: [libreclinica]
       ports: ["8443:443"]
       volumes:
         - ./docker/sso/apache-shib/shibboleth2.xml:/etc/shibboleth/shibboleth2.xml:ro
         - ./docker/sso/apache-shib/attribute-map.xml:/etc/shibboleth/attribute-map.xml:ro
         - ./docker/sso/apache-shib/apache-vhost.conf:/etc/apache2/sites-enabled/000-default.conf:ro
       environment:
         - SHIBD_METADATA_URL=${SHIBD_METADATA_URL:-https://samltest.id/saml/idp}
     libreclinica:
       expose: ["8080"]   # remove the public port mapping; only Apache reaches Tomcat
       networks: [internal]
   networks:
     internal:
       internal: true   # Tomcat unreachable except via Apache
   ```
2. `docker/sso/apache-shib/` directory:
   - `shibboleth2.xml` — Shib SP config pointing at the chosen IdP (default = SAMLtest.id for dev)
   - `attribute-map.xml` — `eppn` → `REMOTE_USER`, `mail` → `mail`, `displayName` → `displayName`
   - `apache-vhost.conf` — `<Location />` requires Shib auth except for `/sso/local-login` (the local-password bypass path); proxies everything to `http://libreclinica:8080/`
3. `.env.example` — document `SHIBD_METADATA_URL`, `LIBRECLINICA_SSO_ENABLED=true`, etc.

### Risk

- **R-D7a** The bypass path for local-password login needs to coexist with the Shib-protected paths. Apache config: `<Location /pages/login/login>` AuthType None; `<Location />` AuthType shibboleth.
- **R-D7b** The Shibboleth SP image is large (~500MB); we already pull two other containers (postgres, mailcrab) so this isn't a step-change in dev environment size, but document the disk cost.

### Acceptance gate

- `docker compose -f compose.yml -f docker-compose.sso.yml up` boots cleanly.
- `curl -k -L https://127.0.0.1:8443/MainMenu` → triggers SAMLtest.id redirect (or whatever IdP is configured) → after login, MainMenu 200.
- Bypass attempt `curl http://127.0.0.1:8080/MainMenu` → connection refused (internal network).

### Rollback

- Delete the new files; main `compose.yml` is unaffected.

---

## D.8 — SSO deployment cookbook

Document, no code.

`docs/development/sso-deployment-guide.md` — one section per reverse-proxy pattern:

1. **MedUni Wien Shibboleth (production reference)** — Apache + `mod_shib`, points at `login.meduniwien.ac.at`. Includes the `shibboleth2.xml` template and the required SP registration steps with MedUni Wien IT.
2. **Generic SAML IdP** — Apache + `mod_shib` with custom `attribute-map.xml` overrides.
3. **Generic OIDC IdP** — Apache + `mod_auth_openidc`. Example for Azure AD / Entra ID, Okta, Auth0, Google Workspace.
4. **AWS ALB OIDC integration** — ALB authentication action; the principal header is `x-amzn-oidc-identity` (configure `libreclinica.sso.header.principal=x-amzn-oidc-identity`).
5. **Cloudflare Access** — `Cf-Access-Authenticated-User-Email` as principal header.
6. **Keycloak (standalone or as IdP)** — Keycloak Gatekeeper sidecar OR Keycloak as the IdP behind Apache+mod_auth_openidc.
7. **oauth2-proxy** — a popular OSS reverse-proxy SSO terminator, works with most OIDC providers.
8. **No SSO** — explicit instruction to omit the sidecar; local accounts only.

Each entry: 1 paragraph context, the sidecar config snippet, the `LIBRECLINICA_SSO_*` env values to set, and one curl command demonstrating a healthy login flow.

### Acceptance gate

- Docs only; review by a second pair of eyes (request a doc-only review PR).

---

## D.9 — 2FA reconciliation for SSO users

### Changes

1. `TwoFactorService.isRequiredFor(UserAccount)` — return false if the user has a non-null `external_id` AND `libreclinica.sso.delegate-mfa-to-idp=true` (default true).
2. "Manage Users" admin screen — hide 2FA enrolment columns for SSO-bound users.
3. Login flow — if SSO pre-auth succeeded, the 2FA challenge JSP is skipped automatically (it was conditional on `factorService.twoFactorActivated && user.has2faActivated` already; the change is one extra `&& !user.isSsoBound()`).

### Risk

- **R-D9a** SSO-bound users who DO have a local TOTP secret from before SSO was enabled — keep the secret in the DB (don't delete it); just skip the prompt. If SSO is disabled later, the secret is still there.

### Acceptance gate

- `TwoFactorServiceTest` extended with the SSO-bound case.
- UI smoke: as an SSO-bound user, Manage Users page shows no 2FA enrolment row.

---

## D.10 — E-signature re-auth scaffolding (FLAG OFF; production deferred)

### Changes

1. `/sso/reauth` endpoint — returns 302 to a proxy-specific re-auth URL. For Shibboleth: `/Shibboleth.sso/Login?forceAuthn=true&target=<original>`. For OIDC under mod_auth_openidc: `/<oidc-base>/redirect_uri?prompt=login&target=<original>`. The URL is read from `libreclinica.sso.reauth.url-template`.
2. Sign Subject flow (the §11.50 e-signature site) **does NOT** invoke `/sso/reauth` in this sub-phase — it continues to require a local password challenge, even for SSO users. The endpoint exists so the wiring is in place when legal/regulatory ratifies proxy re-auth as compliant.

### Risk

- **R-D10a** Premature use of `/sso/reauth` for e-sig before ratification — guard with a feature flag `libreclinica.sso.reauth.enabled` (default false). When false, the Sign Subject flow rejects requests even if the user clicks an "Sign with SSO" button.

### Acceptance gate

- IT: `/sso/reauth` returns the right 302 for each configured template; sign flow still requires local password.

### Rollback

- `git revert <commit>`; the endpoint is gated by feature flag.

---

## D.11 — Reconciliation

- Playbook closure section updated with exit-criteria checklist.
- MIGRATION.md Phase D scope refined; library long-tail moved to a new "Phase F" or kept as "Phase D-Libs" depending on team capacity.
- Memory updates ([[modernization_state]], MEMORY.md).
- Full compose smoke against the Shibboleth-Testbed IdP — 10 round-trip logins, audit table verified.
- Manual MUW IT readiness check: have they confirmed the SP registration request? If not, production cutover is gated on that, but lc-develop can land regardless.

---

## C.deferred — Phase C carryover folded into D-Sec (opportunistic)

These were documented as Phase C exit-criteria deferrals; D-Sec naturally touches the same surface so fold them in if possible:

- **Drop remaining `applicationContext-(core-)security.xml` content** — D.1 retires `shaPasswordEncoder` / `md5PasswordEncoder` / `openClinicaPasswordEncoder` / `securityManager` from `applicationContext-core-security.xml`; D.3 retires `myFilter` / `successHandler` / `failureHandler` / `concurrencyFilter` / `sessionRegistry` / `sas` from `applicationContext-security.xml` by promoting them to `SecurityConfig` @Bean methods. At the end of D-Sec, both security XMLs should be empty or deleted. **DAO XML files** (`applicationContext-core-hibernate.xml` with ~50 DAO beans) stay out of scope — that's the other Phase C deferral and doesn't intersect D-Sec.
- **WAR → JAR + embedded Tomcat** — independent of D-Sec; do not fold in. Schedule separately when an executable-JAR deployment is actually needed.

---

## Risk register

| ID | Risk | Likelihood | Impact | Mitigation | Sub-phase |
|----|------|----|----|----|----|
| R-D1a | Encoder bean ID change breaks XML refs | M | H | Pre-audit all `<ref>` and `<property name="passwordEncoder">` sites; CI fails on missing-bean error | D.1 |
| R-D1b | Lazy rehash needs tx context the filter lacks | M | M | Dispatch through `PasswordRehashService` @Transactional method | D.1 |
| R-D1c | bcrypt cost 10 latency > 200ms on production hardware | L | M | Measure on staging; tune cost or accept first-login spike | D.1 |
| R-D1d | DBUnit fixtures break with raw-string passwords | M | L | Pre-encode all test passwords with bcrypt + helper script | D.1 |
| R-D2a | Liquibase changelog insertion in middle | L | H | Strict append-only protocol; ChangeSet checksum CI gate | D.2 |
| R-D3a | Header spoofing if Tomcat reachable directly | M | **CRITICAL** | CIDR allowlist + deployment doc + compose internal network | D.3 |
| R-D3b | Filter ordering surprise | M | H | Explicit `http.addFilterBefore(preAuth, OpenClinicaUsername…class)` + IT verifying order | D.3 |
| R-D4a | JIT grants role to any IdP-authenticated user | M | H | Default to LOOKUP_ONLY; document JIT only for closed IdPs | D.4 |
| R-D5a | Audit screens choke on new enum values | L | L | Render unknown codes as raw int; existing screens do this already | D.5 |
| R-D6a | Login JSP existing 500 error worsens | L | M | Diagnose pre-existing issue during D.6 | D.6 |
| R-D7a | Bypass path for local login conflicts with Shib auth | M | M | Apache `<Location /pages/login/login>` AuthType None; IT proves both paths work | D.7 |
| R-D10a | Premature SSO e-sig before ratification | M | **CRITICAL** | `libreclinica.sso.reauth.enabled=false` default; sign flow rejects SSO re-auth claim until flipped | D.10 |
| R-D-T1 | Test IdP unavailable mid-cliff | M | M | Stand up local Shibboleth-Testbed Docker image as primary; SAMLtest.id as fallback | D.0+ |
| R-D-T2 | MUW IT slow to register SP for production | M | M (delays cutover, not lc-develop) | Land all code + dev-IdP gates first; production-only blocker on the cutover plan | post-D.11 |

---

## Rollback strategy

- **Per sub-phase**: `git revert <commit>`. Every sub-phase is a single commit (occasionally two: the Liquibase changeset + the code change).
- **Cross-cutting rollback** (worst case): `git reset --hard <phase-c-closure-tag>` (the `lc-develop @ 646209e36` baseline). Tag explicitly: `git tag -a phase-c-closure 646209e36 -m "Phase C closure baseline for Phase D revert"` at the start of D execution.
- **Feature flags as a soft rollback**: D.3 (`libreclinica.sso.enabled=false`), D.10 (`libreclinica.sso.reauth.enabled=false`), D.9 (`libreclinica.sso.delegate-mfa-to-idp=false`) all default to safe-off; flipping a flag in env is a deploy-without-revert rollback path.

---

## Exit criteria for Phase D-Sec

- [ ] All D.0–D.11 gates green
- [ ] `mvn -P integration-tests -Ddb.test=lc-test-pg test` → ≥ 122/122 (112 baseline + ≥ 10 new D-Sec contracts + ITs)
- [ ] Compose smoke (without SSO sidecar): identical to Phase C closure
- [ ] Compose smoke (with SSO sidecar, SAMLtest.id IdP): SSO login → MainMenu 200; audit row written; local-account login still works on the bypass path
- [ ] `application.yml` SSO config block documented in [README.md](../../../README.md) deployment section
- [ ] [sso-deployment-guide.md](sso-deployment-guide.md) cookbook complete with at least the 4 highest-priority entries (Shibboleth, Generic OIDC, AWS ALB, no-SSO)
- [ ] DR-014 references the playbook + the live cookbook
- [ ] Legacy security XMLs (`applicationContext-security.xml` + `applicationContext-core-security.xml`) — either empty stubs or deleted
- [ ] `OpenClinicaPasswordEncoder` deleted; `OpenClinicaPasswordEncoderTest` extended and green
- [ ] Manual e-signature acceptance: Sign Subject flow still requires local password (proxy re-auth NOT yet ratified by legal/regulatory)
- [ ] Memory updated; MIGRATION.md updated; lc-develop tagged `phase-d-sec-closure`

---

## D-Libs — library long-tail (parallel, separately scheduled)

Independent of D-Sec; can be picked up in any order by any contributor without coupling. Each library is one PR.

| Library | Current | Target | Touchpoints | Open DR | Priority |
|---|---|---|---|---|---|
| iText | 2.1.2 | **OpenPDF 1.4+** | PDF gen (audit logs, subject reports, blank CRF prints) — ~6 callers under `core/.../bean/extract/`, `web/.../control/admin/Preview.java`, `web/.../view/form/FormServlet.java` | **DR-007** | high (license: post-2.1 iText is AGPL) |
| Apache POI | 3.0.1 | **5.3+** | Excel CRF upload / export — `SpreadsheetPreview`, `SpreadSheetTable*`, `CreateCRFVersionServlet` | (informal) | high |
| Apache FOP | 1.0 | **2.9+** | XSL-FO → PDF reports | (informal) | medium |
| Quartz | 2.2.3 | **2.5.0** | Scheduled jobs (cleanup, notifications, recurring exports) | (informal) | medium |
| GWT-compiled menu widget | (deprecated framework) | **vanilla HTML** | Top nav bar | (informal) | medium (Phase E may subsume) |
| Prototype.js + Scriptaculous | 1.6 / unversioned | **vanilla JS** | ~20 JSP screens using `$()` / `Effect.*` (`web/src/main/webapp/includes/prototype.js`, `scriptaculous.js`) | (informal) | medium |
| EhCache | 3.10.8 (already on 3) | **stay** OR Caffeine+JCache | Hibernate L2 cache | **DR-013** | low (already on EhCache 3 post B.5) |
| log4jdbc4 | 1.2 (abandoned) | **log4jdbc-log4j2 1.16** or remove | SQL logging (dev profile only) | (informal) | low |

**Per-library protocol** (preserved from MIGRATION.md):
1. Characterization tests pinning the current output (PDF byte-baseline, Excel cell layout, etc.)
2. Replacement library introduced behind interface
3. Switch-over
4. Delete old dep
5. One library per PR; never bundle replacements

**Why D-Libs is not on the critical path:** none of these block clinical deployment. iText AGPL is a long-term licensing concern but not an active risk. POI / FOP / Quartz / GWT are abandoned dependencies but they all still work today. Pick them up opportunistically; don't gate D-Sec on them.

---

## Reference

- [MIGRATION.md § Phase D](../../../MIGRATION.md#phase-d--remaining-library-replacement) — original strategic plan (now refined: Sec primary, Libs parallel)
- [DR-014 — Institution-agnostic SSO via reverse-proxy pre-authentication](decision-record.md#dr-014--institution-agnostic-sso-via-reverse-proxy-pre-authentication)
- [DR-015 — Password encoder migration: MD5/SHA-1 → bcrypt via DelegatingPasswordEncoder](decision-record.md#dr-015--password-encoder-migration-md5sha-1--bcrypt-via-delegatingpasswordencoder)
- [phase-d-pre-flight-inventory.md](phase-d-pre-flight-inventory.md) — current security-stack inventory
- [phase-c-execution-playbook.md](phase-c-execution-playbook.md) — playbook template
- `lc-develop @ 646209e36` — Phase C closure baseline this playbook builds on
