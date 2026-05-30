# SSO sidecar — opt-in deployment overlays

LibreClinica MUW Ophthalmology — Phase D.7 (DR-014).

This directory contains operator-facing reverse-proxy configs for the
institution-agnostic SSO architecture. The in-app code (Phase D.3 +
D.4 + D.5) speaks one in-app protocol — `RequestHeaderAuthenticationFilter`
consuming configurable HTTP headers — and lets the reverse-proxy
choice handle the actual SSO protocol (SAML / OIDC / OAuth / …).
**Provider swap = sidecar + env-var change; never a code change.**

## Quick start with SAMLtest.id

SAMLtest.id (https://samltest.id) is a public free SAML test IdP.
The smoothest path to a flag-on smoke without standing up your own
Shibboleth IdP.

### Pre-flight

- `docker compose` v2+
- LibreClinicaMUW source checkout
- Internet (SAMLtest.id is a hosted service)

### Steps

1. **Boot the stack once** so the Shibboleth daemon (`shibd`) can
   auto-generate the SP signing/encryption keypair under
   `/etc/shibboleth/`:

   ```sh
   docker compose -f compose.yaml -f docker-compose.sso.yml up --build apache-shib
   ```

2. **Fetch your SP metadata** — the XML descriptor identifying your
   SP to SAMLtest.id:

   ```sh
   curl -sk https://localhost:8443/Shibboleth.sso/Metadata > sp-metadata.xml
   ```

3. **Upload `sp-metadata.xml`** to https://samltest.id/upload.php —
   SAMLtest.id will trust your SP and provide their IdP metadata at
   https://samltest.id/saml/idp (already configured as the default
   in `apache-shib/shibboleth2.xml`).

4. **Restart the sidecar** to refresh the IdP metadata cache:

   ```sh
   docker compose -f compose.yaml -f docker-compose.sso.yml restart apache-shib
   ```

5. **Provision a LibreClinica user** with the SAMLtest.id principal
   in `user_account.external_id`:

   ```sh
   docker compose exec db psql -U clinica -d libreclinica -c \
     "UPDATE user_account SET external_id='morty@samltest.id', external_id_provider='shibboleth-meduniwien' WHERE user_name='root';"
   ```

   (Replace `morty@samltest.id` with whichever SAMLtest.id test user
   you'll be logging in as — see https://samltest.id/test-users.
   Replace `shibboleth-meduniwien` with whatever
   `LIBRECLINICA_SSO_PROVIDER` is set to in your env; default
   matches.)

6. **Test login**: browse to https://localhost:8443/LibreClinica/MainMenu.
   Apache+mod_shib redirects you to SAMLtest.id; log in as
   `morty/panic` (or any other test user). After SAML response,
   Apache populates `REMOTE_USER` and proxies to Tomcat;
   LibreClinica's pre-auth filter resolves the principal to the
   `root` row via `external_id` and signs you in.

7. **Verify audit trail** — the `audit_user_login` table now has a
   row with `login_status_code = 6` (`SSO_LOGIN`):

   ```sh
   docker compose exec db psql -U clinica -d libreclinica -c \
     "SELECT user_name, login_status_code, details FROM audit_user_login ORDER BY id DESC LIMIT 5;"
   ```

### Local-account fallback still works

The local username/password form at
https://localhost:8443/LibreClinica/pages/login/login is unaffected:
the Apache vhost has explicit `AuthType None` bypass rules for
that path and for `/j_spring_security_check`. Sponsors, demo users,
and break-glass accounts authenticate via password as before.

## Other reverse-proxy choices (cookbook stubs)

The in-app pre-auth filter is institution-agnostic; the same
LibreClinica code works behind any of these reverse proxies. Each
entry below is a one-paragraph sketch — full configs to be authored
in Phase D.8.

### Generic OIDC IdP — Apache + `mod_auth_openidc`

Swap `libapache2-mod-shib` for `libapache2-mod-auth-openidc` in the
Dockerfile. `apache-shib-vhost.conf` replaces `AuthType shibboleth`
with `AuthType openid-connect`. Required env: `OIDC_PROVIDER_METADATA_URL`,
`OIDC_CLIENT_ID`, `OIDC_CLIENT_SECRET`. Set
`LIBRECLINICA_SSO_PRINCIPAL_HEADER=OIDC_CLAIM_sub` (mod_auth_openidc
emits claims as `OIDC_CLAIM_*` headers by default). Works with Azure
AD / Entra ID, Okta, Auth0, Keycloak, Google Workspace, AWS Cognito.

### AWS ALB with OIDC authentication action

Skip the in-cluster sidecar entirely — terminate auth at the ALB.
The ALB's authentication action signs the request with the
`x-amzn-oidc-data` JWT and adds `x-amzn-oidc-identity` + claim
headers. Set `LIBRECLINICA_SSO_PRINCIPAL_HEADER=x-amzn-oidc-identity`
and the trusted-proxy CIDRs to the ALB subnet. Production-grade
deployment with zero sidecar containers to maintain.

### Cloudflare Access (zero-trust)

Cloudflare Access in front of the Tomcat origin. Signed JWT carried
in `Cf-Access-Jwt-Assertion`; user email in
`Cf-Access-Authenticated-User-Email`. Set
`LIBRECLINICA_SSO_PRINCIPAL_HEADER=Cf-Access-Authenticated-User-Email`.
Trusted-proxy CIDRs = Cloudflare's published edge IP ranges (refresh
periodically).

### Keycloak (institutional IdP or open-source SAML/OIDC server)

Two patterns: (a) Keycloak Gatekeeper sidecar (similar to oauth2-proxy
shape; deprecated upstream but still works) — or (b) Apache +
`mod_auth_openidc` with Keycloak as the OIDC provider. Both end up
populating the same headers; the app sees no difference.

### oauth2-proxy

Pomerium-style reverse-proxy sidecar that handles OIDC against
Azure AD / Google / Okta / GitHub / etc. Adds `X-Forwarded-User`
header by default. Set
`LIBRECLINICA_SSO_PRINCIPAL_HEADER=X-Forwarded-User`. Single binary,
easier to operate than Apache + mod_auth_openidc for small
deployments.

### No SSO

Don't deploy this overlay. The default `compose.yaml` is the no-SSO
deployment; LibreClinica's local username/password + LDAP paths are
the only auth surface. SSO config in `application.yml` stays
disabled (`libreclinica.sso.enabled=false`). Drop the
`docker-compose.sso.yml` file and use the base compose stack as-is.

## Production cutover from SAMLtest.id to MedUni Wien Shibboleth

When MedUni Wien IT confirms the institutional SP registration:

1. Replace the `SSO entityID` in `apache-shib/shibboleth2.xml` with
   `https://login.meduniwien.ac.at/idp/shibboleth`.
2. Replace the `MetadataProvider url` with the MedUni Wien IdP
   metadata URL.
3. Generate a long-lived SP keypair (per institutional cert-rotation
   policy) and place under `/etc/shibboleth/` — or use a
   build-arg-injected cert from a Vault/Secrets Manager.
4. Update `ApplicationDefaults entityID` to a stable URL under the
   production deployment domain (e.g.
   `https://libreclinica.meduniwien.ac.at/shibboleth`).
5. Provide the MedUni Wien-registered SP metadata to institutional
   IT for inclusion in their IdP's metadata feed.
6. UAT: log in as several test accounts; verify
   `audit_user_login.user_name` matches the local handle (not the
   raw eppn) and the `details` column captures the principal.

## Troubleshooting

**Browser redirects to SAMLtest.id but I see "No SP metadata
found"** — your SP metadata isn't registered. Re-run step 3 above.

**Apache logs "Refused SSO pre-auth from untrusted remote …"** —
Phase D.3's CIDR allowlist (`LIBRECLINICA_SSO_TRUSTED_CIDRS`) doesn't
include the Apache sidecar's IP. Either widen the allowlist or
inspect the actual remote IP via `docker compose logs libreclinica`.

**Login succeeds at the IdP but LibreClinica shows the login page
again** — the principal value SAMLtest.id sent doesn't match any
`external_id` row in `user_account`. Re-run step 5 above with the
exact principal value (visible in
`https://localhost:8443/Shibboleth.sso/Session`).

**Reset SP state** — `docker compose -f compose.yaml
-f docker-compose.sso.yml down apache-shib`; remove the named volume
if any; re-build. The shibd daemon regenerates the keypair on next
start, and you re-upload SP metadata to SAMLtest.id.
