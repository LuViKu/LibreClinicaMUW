# Phase D — Pre-flight inventory

Snapshot of the current security stack and library long-tail as of `lc-develop @ 646209e36` (Phase C closure, 2026-05-30). Compiled to feed the [Phase D execution playbook](phase-d-execution-playbook.md). Facts here pin "current state" so Phase D's first sub-phase (D.0 characterisation tests) knows what to lock in before any code moves.

## Security stack

### 1. Password encoder

**File:** [core/src/main/java/.../core/OpenClinicaPasswordEncoder.java](../../../core/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/core/OpenClinicaPasswordEncoder.java) (lines 19–65)

- Algorithm: SHA-1 for new hashes; MD5 as legacy-read fallback.
- Mechanism: a hand-rolled dual encoder with `currentPasswordEncoder` (SHA-1 `MessageDigestPasswordEncoder`) and `oldPasswordEncoder` (MD5 `MessageDigestPasswordEncoder`).
- `matches()` returns true if either encoder verifies — legacy MD5 rows authenticate without rehashing.
- **No prefix scheme** on stored hashes — raw hex digest, length-distinguishable (32 chars = MD5, 40 chars = SHA-1).
- Test pin: [core/src/test/java/.../core/OpenClinicaPasswordEncoderTest.java](../../../core/src/test/java/at/ac/meduniwien/ophthalmology/libreclinica/core/OpenClinicaPasswordEncoderTest.java) — pins SHA-1 round-trip, MD5 fallback, salt-per-call semantics.

**Wiring:**
- Bean `openClinicaPasswordEncoder` defined in [core/src/main/resources/.../applicationContext-core-security.xml](../../../core/src/main/resources/at/ac/meduniwien/ophthalmology/libreclinica/applicationContext-core-security.xml) (lines 32–35) → injects `shaPasswordEncoder` (line 25–27) + `md5PasswordEncoder` (line 28–30).
- Consumed by `DaoAuthenticationProvider` (lines 42–45) and `SecurityManager` (lines 37–48).

**Phase D destination ([DR-015](decision-record.md#dr-015--password-encoder-migration-md5sha-1--bcrypt-via-delegatingpasswordencoder)):**
- `DelegatingPasswordEncoder` keyed by prefix. New writes: `{bcrypt}…`. Legacy unprefixed hashes routed to a new `LegacyMd5Sha1PasswordEncoder` that length-detects MD5 vs SHA-1 and verifies against both.
- Lazy bcrypt rehash on first successful legacy match.

### 2. Authentication filter chain

**Custom filter:** [web/src/main/java/.../web/filter/OpenClinicaUsernamePasswordAuthenticationFilter.java](../../../web/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/web/filter/OpenClinicaUsernamePasswordAuthenticationFilter.java) — extends `AbstractAuthenticationProcessingFilter`.

`attemptAuthentication` (lines 107–187):
1. Reads `j_username` / `j_password` from POST body
2. Validates non-blank → BadCredentialsException
3. Checks user `active` status (line 139)
4. If `factorService.twoFactorActivated && user.has2faActivated`, verifies TOTP via `factorService.verify()` (lines 143–148)
5. Calls `authenticationManager.authenticate(authRequest)` (line 160)
6. On success: writes `audit_user_login` row with `SUCCESSFUL_LOGIN` (line 161); stashes user in session under `SecureController.USER_BEAN_NAME` (line 163)
7. On failure: writes `FAILED_LOGIN` or `FAILED_LOGIN_LOCKED` audit row; increments `lock_counter`; locks account on threshold

`successfulAuthentication` is the default `SavedRequestAwareAuthenticationSuccessHandler` declared in `applicationContext-security.xml:58`.

**SecurityFilterChain** (now a Java `@Bean` in [web/src/main/java/.../config/SecurityConfig.java](../../../web/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/config/SecurityConfig.java) since Phase C.14):
- `OpenClinicaUsernamePasswordAuthenticationFilter` at the `UsernamePasswordAuthenticationFilter` position
- `ConcurrentSessionFilter` at the `ConcurrentSessionFilter` position
- 24 unauthenticated `permitAll` matchers (login page, contact, openrosa, healthcheck, accounts, /actuator/health + /actuator/info, etc.)
- Everything else requires `ROLE_USER`

**Phase D destination:**
- Add `RequestHeaderAuthenticationFilter` (Spring Security built-in) **before** `OpenClinicaUsernamePasswordAuthenticationFilter`. Conditional on `libreclinica.sso.enabled=true`.
- Extract the `auditUserLogin()` calls into a shared `LoginAuditService` so the new SSO pre-auth path writes the same audit rows via the new `SSO_LOGIN` / `SSO_LOGIN_FAILED` enum values.

### 3. LDAP authentication provider

**Status:** wired and active (provider #2 in `authenticationManager`).

[core/src/main/resources/.../applicationContext-core-security.xml](../../../core/src/main/resources/at/ac/meduniwien/ophthalmology/libreclinica/applicationContext-core-security.xml):
- `authenticationManager` (lines 18–23) — providers: `ocUserDetailsService` (DAO) + `ldapAuthenticationProvider` (LDAP).
- `contextSource` (lines 79–89) — uses `${ldap.host}`, `${ldap.userDn}`, `${ldap.password}` from env.
- `ldapAuthenticationProvider` (lines 91–101) — `BindAuthenticator` + `OpenClinicaLdapAuthoritiesPopulator`.

**Custom auth-helper classes:**
- [core/src/main/java/.../service/user/OpenClinicaLdapUserSearch.java](../../../core/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/service/user/OpenClinicaLdapUserSearch.java) (lines 25–37) — delegates to `LdapUserService.searchForUser()`
- [web/src/main/java/.../web/filter/OpenClinicaLdapAuthoritiesPopulator.java](../../../web/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/web/filter/OpenClinicaLdapAuthoritiesPopulator.java) (lines 25–31) — grants all LDAP-authenticated users `ROLE_USER`

**Phase D destination:**
- Stays in the chain per the 2026-05-28 ratification. Becomes the **third path** alongside (1) SSO pre-auth from a header, (2) local username/password, (3) LDAP bind. Order: pre-auth → local → LDAP, by `authenticationManager` provider order.

### 4. Substantive security XML beans (Phase C deferred items)

After Phase C closure, the security XMLs still hold beans not yet promoted to `@Configuration`:

**[web/src/main/resources/.../applicationContext-security.xml](../../../web/src/main/resources/at/ac/meduniwien/ophthalmology/libreclinica/applicationContext-security.xml):**
| Line | Bean | Purpose |
|---|---|---|
| 27–31 | `authenticationProcessingFilterEntryPoint` | LoginUrlAuthenticationEntryPoint → `/pages/login/login` |
| 35–36 | `securityContextRepository` | HttpSessionSecurityContextRepository |
| 38–56 | `myFilter` | OpenClinicaUsernamePasswordAuthenticationFilter (wired with DAOs + SAS) |
| 58–60 | `successHandler` | SavedRequestAwareAuthenticationSuccessHandler → `/MainMenu` |
| 62–70 | `failureHandler` | ExceptionMappingAuthenticationFailureHandler (LockedException + AccountConfigurationException mappings) |
| 72–80 | `concurrencyFilter` | ConcurrentSessionFilter (uses sessionRegistry + openClinicaLogoutHandler) |
| 82–86 | `sessionRegistry` | OpenClinicaSessionRegistryImpl (custom — tracks concurrent sessions) |
| 90–110 | `sas` | CompositeSessionAuthenticationStrategy (concurrent-session-control max 1; session-fixation-protection; register-session-strategy) |

**[core/src/main/resources/.../applicationContext-core-security.xml](../../../core/src/main/resources/at/ac/meduniwien/ophthalmology/libreclinica/applicationContext-core-security.xml):**
| Line | Bean | Purpose |
|---|---|---|
| 18–23 | `authenticationManager` | DaoAuthenticationProvider + LdapAuthenticationProvider |
| 25–27 | `shaPasswordEncoder` | MessageDigestPasswordEncoder("SHA-1") — retired in D.1 |
| 28–30 | `md5PasswordEncoder` | MessageDigestPasswordEncoder("MD5") — retired in D.1 |
| 32–35 | `openClinicaPasswordEncoder` | The dual encoder — retired in D.1 |
| 37–48 | `securityManager` | Legacy utility class with encoder + provider list |
| 67–69 | `ocUserDetailsService` | JdbcDaoImpl querying user_account on user_name |
| 79–89 | `contextSource` | DefaultSpringSecurityContextSource for LDAP bind |
| 91–101 | `ldapAuthenticationProvider` | BindAuthenticator + authorities populator |
| 103–107 | `xformParser` | XformParser bean (non-security — OpenRosa related) |
| 109 | `apiSecurityFilter` | ApiSecurityFilter |

**Phase D destination:** D.1 + D.3 + D.5 collectively promote the substantive beans to `@Bean` methods in `SecurityConfig`. After D-Sec closes, both files should be empty stubs or deleted. The non-security beans (`xformParser`, `apiSecurityFilter`) stay in scope-appropriate Java config (not in `SecurityConfig`).

### 5. User schema

**Entity:** [core/src/main/java/.../domain/user/UserAccount.java](../../../core/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/domain/user/UserAccount.java)

Columns relevant to auth:
- `user_id` (pk, int, sequence-generated)
- `user_name` (varchar 64) — local login identifier; widened from 32 to 64 in `lc-1.4.0/username-length.xml`
- `passwd` (text) — hashed password (currently SHA-1 / MD5, will become bcrypt-prefixed in D.1)
- `passwd_timestamp` (date) — last password change
- `passwd_challenge_question`, `passwd_challenge_answer` (varchar) — legacy security question (unused in modern auth flow but column survives)
- `enabled`, `account_non_locked`, `lock_counter`, `status_id` — account lock state
- `authtype` (VARCHAR 255 enum: `STANDARD` / `MARKED` / `TWO_FACTOR`) — 2FA mode
- `authsecret` (varchar 255) — TOTP secret for 2FA

**Federated-identity column: NONE.** D.2 adds:
- `external_id VARCHAR(255) NULL` — SSO principal value (eppn / sub / oid / etc.)
- `external_id_provider VARCHAR(64) NULL` — namespace (e.g. `shibboleth-meduniwien`, `azure-ad-tenant-xyz`)
- Composite unique index `idx_user_account_external_identity (external_id_provider, external_id)`

### 6. Audit-login table

**Entity:** [core/src/main/java/.../domain/technicaladmin/AuditUserLoginBean.java](../../../core/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/domain/technicaladmin/AuditUserLoginBean.java)

**Enum:** [core/src/main/java/.../domain/technicaladmin/LoginStatus.java](../../../core/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/domain/technicaladmin/LoginStatus.java)

Current codes (D.5 adds two more):
| Code | Enum | Trigger |
|---|---|---|
| 1 | `SUCCESSFUL_LOGIN` | Successful local-password or LDAP login |
| 2 | `FAILED_LOGIN` | Failed local-password / LDAP authentication |
| 3 | `FAILED_LOGIN_LOCKED` | Login blocked because `account_non_locked = false` |
| 4 | `SUCCESSFUL_LOGOUT` | User-initiated logout |
| 5 | `ACCESS_CODE_VIEWED` | Participant access-code view event |

**Phase D additions:**
- `6 SSO_LOGIN` — SSO pre-auth succeeded
- `7 SSO_LOGIN_FAILED` — pre-auth header present but provisioning rejected

Audit-write code currently lives inline in `OpenClinicaUsernamePasswordAuthenticationFilter.auditUserLogin()` (lines 197–204). D.5 extracts to `LoginAuditService` so both auth paths call the same hook.

### 7. Two-factor authentication

**Service:** [core/src/main/java/.../service/otp/TwoFactorService.java](../../../core/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/service/otp/TwoFactorService.java)

- TOTP via `dev.samstevens.totp` library
- SHA1 algorithm, 6 digits, 30-second period
- Two activation modes via `TwoFactorType` enum: `APPLICATION` (QR shown in browser) or `LETTER` (QR embedded in admin-printed PDF)
- System-wide flag: `2fa.activated` property
- Per-user fields: `authtype` + `authsecret`
- Test pin: [TwoFactorServiceTest.java](../../../core/src/test/java/at/ac/meduniwien/ophthalmology/libreclinica/service/otp/TwoFactorServiceTest.java) — 14 tests

**Phase D destination:** D.9 — when `libreclinica.sso.delegate-mfa-to-idp=true` (default), users with non-null `external_id` bypass the TOTP challenge (the IdP is responsible for MFA). Local-account users continue to see 2FA.

### 8. Login JSP

**File:** [web/src/main/webapp/WEB-INF/jsp/login/login.jsp](../../../web/src/main/webapp/WEB-INF/jsp/login/login.jsp)

- Form fields (lines 94–115): `j_username`, `j_password`, conditional `j_factor` if 2FA active
- Form action: hardcoded `/j_spring_security_check`
- **No SSO affordance** — no institutional-login button, no SAML/OIDC endpoint, no provider-discovery UI

**Phase D destination:** D.6 adds a conditional `<c:if test="${ssoEnabled}">` block above the form rendering a button labelled per `libreclinica.sso.buttonLabel` pointing at `libreclinica.sso.entryUrl`. Button styling matches the [Phase E design system](phase-e/) where possible.

**Known issue:** `GET /pages/login/login` currently returns HTTP 500 in the compose smoke. Pre-dates Phase C; tracked in [phase-e/known-issues.md](phase-e/known-issues.md). The login flow still works because the POST → `/j_spring_security_check` path doesn't depend on the GET. D.6 should diagnose during the JSP touch.

---

## Library long-tail (D-Libs scope, not gating)

Current versions in [pom.xml](../../../pom.xml) and [core/pom.xml](../../../core/pom.xml):

| Library | Current | Target ([MIGRATION.md § Phase D](../../../MIGRATION.md#phase-d--remaining-library-replacement)) | Status |
|---|---|---|---|
| iText | **2.1.2** | OpenPDF 1.4+ (per DR-007 when accepted) | NOT YET |
| Apache POI | (legacy 3.0.1 referenced in some sites) | 5.3+ | NOT YET |
| Apache FOP | (legacy 1.0 references) | 2.9+ | NOT YET |
| Quartz | (pinned at Spring Boot's managed version) | 2.5.0 | check post-C |
| EhCache | 3.10.8 (already on EhCache 3 post-B.5) | (stay) | DONE |
| log4jdbc4 | 1.2 (abandoned) | log4jdbc-log4j2 1.16 OR drop | NOT YET (dev only) |
| GWT-compiled menu | nocache.js artifact at `web/src/main/webapp/gwt/GwtMenu/` | vanilla HTML | NOT YET (Phase E may subsume) |
| Prototype.js + Scriptaculous | `web/src/main/webapp/includes/prototype.js`, `scriptaculous.js` | vanilla JS / jQuery | NOT YET (~20 JSP callers) |
| JMesa | **REMOVED** (B.4 phase eviction PRs #32–#49) | — | DONE |
| Castor | **REMOVED** (B.3 PRs #27/#28) | — | DONE |

D-Libs runs as independent PRs alongside D-Sec; no coupling.

---

## Reference

- [phase-d-execution-playbook.md](phase-d-execution-playbook.md) — sub-phase ordering, gates, risk register
- [DR-014](decision-record.md#dr-014--institution-agnostic-sso-via-reverse-proxy-pre-authentication)
- [DR-015](decision-record.md#dr-015--password-encoder-migration-md5sha-1--bcrypt-via-delegatingpasswordencoder)
- `lc-develop @ 646209e36` — Phase C closure baseline
