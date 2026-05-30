# SSO deployment guide

LibreClinica MUW Ophthalmology — Phase D.8 (DR-014).

This guide pairs reverse-proxy configurations with LibreClinicaMUW's
institution-agnostic SSO architecture (DR-014). The in-app code
speaks one protocol — header-based pre-authentication via Spring
Security's `RequestHeaderAuthenticationFilter` — and lets the
reverse-proxy choice handle the actual SSO protocol (SAML / OIDC /
OAuth). **Provider swap = sidecar + env-var change; never a code
change.**

Companion docs:
- [decision-record.md § DR-014](modernization/decision-record.md#dr-014--institution-agnostic-sso-via-reverse-proxy-pre-authentication) — the architectural decision
- [phase-d-execution-playbook.md](modernization/phase-d-execution-playbook.md) — the execution plan
- [`../docker/sso/README.md`](../../docker/sso/README.md) — quick-start with SAMLtest.id; this guide is the depth follow-up

## Common LibreClinica-side configuration

Every cookbook entry below assumes these env vars are set on the
`libreclinica` service:

```sh
LIBRECLINICA_SSO_ENABLED=true
LIBRECLINICA_SSO_PROVIDER=<provider-namespace>           # stored in user_account.external_id_provider
LIBRECLINICA_SSO_PRINCIPAL_HEADER=<header-name>          # default REMOTE_USER
LIBRECLINICA_SSO_TRUSTED_CIDRS=<reverse-proxy-IP-range>  # MUST narrow in production
```

Provision LibreClinica users with the SSO principal:

```sql
UPDATE user_account
SET external_id           = '<principal-value-as-it-comes-from-IdP>',
    external_id_provider  = '<same-namespace-as-LIBRECLINICA_SSO_PROVIDER>'
WHERE user_name = '<local-handle>';
```

`(external_id_provider, external_id)` is the composite unique key on
`user_account`. Different providers can coexist (one user can have
multiple `external_id` rows under different `external_id_provider`
namespaces — useful for institutional migrations).

The trusted-proxy CIDR is load-bearing: Tomcat MUST be bound to the
compose-internal network only, or behind a firewall that blocks
direct access. If a client can reach Tomcat outside the configured
CIDR, they can spoof the principal header and gain whatever
`external_id` matches.

## Pattern 1 — MedUni Wien Shibboleth (production reference)

**Context.** The MedUni Wien institutional IdP at
`login.meduniwien.ac.at` speaks SAML 2.0. Production reference
deployment for LibreClinicaMUW.

**Sidecar:** Apache HTTPD + `mod_shib` (Shibboleth Service Provider).
The provided sidecar in
[`docker/sso/apache-shib/`](../../docker/sso/apache-shib/) is
SAMLtest.id-defaulted; for production swap the `shibboleth2.xml`
template:

```xml
<!-- /etc/shibboleth/shibboleth2.xml — production overrides -->
<ApplicationDefaults entityID="https://libreclinica.meduniwien.ac.at/shibboleth"
                     REMOTE_USER="eppn mail persistent-id">
    <Sessions ... handlerSSL="true" cookieProps="https">
        <SSO entityID="https://login.meduniwien.ac.at/idp/shibboleth">
            SAML2
        </SSO>
    </Sessions>
    <MetadataProvider type="XML"
                      url="https://login.meduniwien.ac.at/idp/shibboleth/metadata"
                      backingFilePath="meduniwien-idp-metadata.xml"
                      maxRefreshDelay="7200"/>
    <CredentialResolver type="File" use="signing"
                        key="/etc/shibboleth/sp-key.pem"
                        certificate="/etc/shibboleth/sp-cert.pem"/>
    <CredentialResolver type="File" use="encryption"
                        key="/etc/shibboleth/sp-key.pem"
                        certificate="/etc/shibboleth/sp-cert.pem"/>
</ApplicationDefaults>
```

**LibreClinica env:**

```sh
LIBRECLINICA_SSO_ENABLED=true
LIBRECLINICA_SSO_PROVIDER=shibboleth-meduniwien
LIBRECLINICA_SSO_PRINCIPAL_HEADER=REMOTE_USER
LIBRECLINICA_SSO_EMAIL_HEADER=mail
LIBRECLINICA_SSO_TRUSTED_CIDRS=10.0.0.0/8     # narrow to Apache sidecar IP
LIBRECLINICA_SSO_BUTTON_LABEL="Sign in with MedUni Wien"
```

**One-time institutional steps:**

1. Generate a long-lived SP keypair under
   `/etc/shibboleth/sp-{key,cert}.pem` (per institutional cert-rotation
   policy; often 3-5 years validity).
2. Submit the SP metadata to MedUni Wien IT for inclusion in their
   IdP's metadata feed.
3. Confirm with MedUni Wien IT that they trust the SP entityID
   (`https://libreclinica.meduniwien.ac.at/shibboleth`).
4. Provision LibreClinica users with their MedUni Wien
   `eduPersonPrincipalName` as `external_id`.

**Smoke:**

```sh
# After production cutover, browse:
https://libreclinica.meduniwien.ac.at/MainMenu
# → Apache + mod_shib redirects to login.meduniwien.ac.at
# → After authentication, REMOTE_USER set to MUW eppn
# → LibreClinica matches by external_id, signs the user in
# → SSO_LOGIN row written to audit_user_login
```

## Pattern 2 — Generic SAML IdP

**Context.** Other academic institutions (Federation of Identity
Providers and Service Providers — eduGAIN), or commercial SAML IdPs
(PingFederate, Microsoft ADFS, Shibboleth in another university).

Same Apache + `mod_shib` sidecar as Pattern 1. Adjustments:

- `shibboleth2.xml` `<SSO entityID>` points at the new IdP
- `shibboleth2.xml` `<MetadataProvider url>` points at the IdP's
  federation metadata feed
- `attribute-map.xml` may need new attribute IDs if the IdP releases
  custom claims (e.g. internal user IDs, role IDs from the local
  IDM system)
- `LIBRECLINICA_SSO_PROVIDER` set to a namespace distinguishing
  this IdP from others (e.g. `shibboleth-edugain`)

The sidecar code stays identical; only config changes per institution.

## Pattern 3 — Generic OIDC IdP (Azure AD / Entra ID, Okta, Auth0, Google Workspace)

**Context.** Most modern commercial IdPs publish OpenID Connect
endpoints. OIDC is more modern than SAML and easier to operate
(JSON-based; no XML signing complexity).

**Sidecar:** Apache HTTPD + `mod_auth_openidc` (instead of
`mod_shib`). Swap the Dockerfile package + vhost config:

```dockerfile
# docker/sso/apache-oidc/Dockerfile
FROM debian:bookworm-slim
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        apache2 libapache2-mod-auth-openidc ca-certificates curl && \
    rm -rf /var/lib/apt/lists/*
RUN a2enmod ssl proxy proxy_http auth_openidc headers
COPY apache-oidc-vhost.conf /etc/apache2/sites-enabled/000-default.conf
EXPOSE 80 443
CMD ["apache2ctl", "-D", "FOREGROUND"]
```

```apache
# apache-oidc-vhost.conf
OIDCProviderMetadataURL ${OIDC_PROVIDER_METADATA_URL}
OIDCClientID            ${OIDC_CLIENT_ID}
OIDCClientSecret        ${OIDC_CLIENT_SECRET}
OIDCRedirectURI         https://libreclinica.example.org/oauth2callback
OIDCCryptoPassphrase    ${OIDC_CRYPTO_PASSPHRASE}
OIDCRemoteUserClaim     sub        # or 'preferred_username' / 'email' per IdP
OIDCScope               "openid email profile"

ProxyPreserveHost On
ProxyPass / http://libreclinica:8080/ retry=0
ProxyPassReverse / http://libreclinica:8080/

<Location />
    AuthType openid-connect
    Require valid-user
</Location>
<Location /LibreClinica/pages/login/login>
    AuthType None
    Require all granted
</Location>
<Location /LibreClinica/j_spring_security_check>
    AuthType None
    Require all granted
</Location>
```

**LibreClinica env per IdP:**

| IdP | OIDC_PROVIDER_METADATA_URL | OIDCRemoteUserClaim | LIBRECLINICA_SSO_PROVIDER |
|---|---|---|---|
| Azure AD / Entra ID | `https://login.microsoftonline.com/<tenant-id>/v2.0/.well-known/openid-configuration` | `oid` (or `preferred_username`) | `azure-ad-tenant-<tenant>` |
| Okta | `https://<org>.okta.com/.well-known/openid-configuration` | `sub` | `okta-prod` |
| Auth0 | `https://<tenant>.auth0.com/.well-known/openid-configuration` | `sub` | `auth0-<tenant>` |
| Google Workspace | `https://accounts.google.com/.well-known/openid-configuration` | `email` (or `sub`) | `google-workspace-<domain>` |
| Keycloak | `https://<keycloak-host>/realms/<realm>/.well-known/openid-configuration` | `preferred_username` | `keycloak-<realm>` |

```sh
# LibreClinica env (apply per IdP):
LIBRECLINICA_SSO_ENABLED=true
LIBRECLINICA_SSO_PROVIDER=<from-table-above>
LIBRECLINICA_SSO_PRINCIPAL_HEADER=OIDC_CLAIM_sub   # mod_auth_openidc emits claims as OIDC_CLAIM_*
LIBRECLINICA_SSO_EMAIL_HEADER=OIDC_CLAIM_email
LIBRECLINICA_SSO_TRUSTED_CIDRS=10.0.0.0/8
```

## Pattern 4 — AWS ALB with OIDC authentication action

**Context.** AWS deployments. Skip the in-cluster sidecar entirely —
the AWS Application Load Balancer authenticates users at the edge
and forwards signed identity headers to the target group.

**Sidecar:** none. The ALB IS the sidecar.

**ALB authentication action config (Terraform sketch):**

```hcl
resource "aws_lb_listener_rule" "libreclinica_auth" {
  listener_arn = aws_lb_listener.libreclinica_https.arn
  action {
    type = "authenticate-oidc"
    authenticate_oidc {
      authorization_endpoint = "https://login.example.com/oauth/authorize"
      client_id              = "<client-id>"
      client_secret          = "<client-secret>"
      issuer                 = "https://login.example.com"
      token_endpoint         = "https://login.example.com/oauth/token"
      user_info_endpoint     = "https://login.example.com/userinfo"
      session_cookie_name    = "AWSELBAuthSessionCookie"
      session_timeout        = 28800
      scope                  = "openid email profile"
    }
  }
  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.libreclinica.arn
  }
  condition {
    path_pattern { values = ["/*"] }
  }
}

# A separate listener rule WITHOUT auth for the local-login bypass:
resource "aws_lb_listener_rule" "libreclinica_local_bypass" {
  listener_arn = aws_lb_listener.libreclinica_https.arn
  priority     = 10
  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.libreclinica.arn
  }
  condition {
    path_pattern { values = ["/LibreClinica/pages/login/login", "/LibreClinica/j_spring_security_check"] }
  }
}
```

The ALB injects `x-amzn-oidc-data` (signed JWT containing user
claims) and `x-amzn-oidc-identity` (the principal).

**LibreClinica env:**

```sh
LIBRECLINICA_SSO_ENABLED=true
LIBRECLINICA_SSO_PROVIDER=aws-alb-<region>-<environment>
LIBRECLINICA_SSO_PRINCIPAL_HEADER=x-amzn-oidc-identity
LIBRECLINICA_SSO_TRUSTED_CIDRS=<VPC-CIDR>          # narrow to ALB subnet
LIBRECLINICA_SSO_BUTTON_LABEL="Sign in"
```

Tomcat MUST bind to the VPC-internal IP (not public). Tag the
target group with security-group rules restricting inbound to the
ALB's security group only.

## Pattern 5 — Cloudflare Access (zero-trust)

**Context.** Cloudflare Access in front of the Tomcat origin
(typically over Argo Tunnel for the connection back to your data
centre). Cloudflare handles auth at the edge against any of their
supported IdPs (Azure AD, Okta, Google, GitHub, SAML, OIDC, etc.).

**Sidecar:** none. Cloudflare IS the sidecar.

Cloudflare Access injects:
- `Cf-Access-Jwt-Assertion` (signed JWT, verifiable via Cloudflare's
  public key)
- `Cf-Access-Authenticated-User-Email` (the user's email)

**LibreClinica env:**

```sh
LIBRECLINICA_SSO_ENABLED=true
LIBRECLINICA_SSO_PROVIDER=cloudflare-access-<team>
LIBRECLINICA_SSO_PRINCIPAL_HEADER=Cf-Access-Authenticated-User-Email
LIBRECLINICA_SSO_TRUSTED_CIDRS=<cloudflare-edge-ranges>  # see https://www.cloudflare.com/ips/
```

Cloudflare publishes their edge IP ranges; refresh
`LIBRECLINICA_SSO_TRUSTED_CIDRS` periodically (Cloudflare doesn't
change these often but does occasionally add ranges). Production
should verify the `Cf-Access-Jwt-Assertion` JWT to guarantee the
headers really came from Cloudflare — a future commit can add a
JWT-verifying filter for this; for now the trusted-CIDR + Argo
Tunnel are the defenses.

## Pattern 6 — Keycloak (self-hosted IdP)

**Context.** Institution runs their own Keycloak realm. LibreClinica
can be added as a client. Two deployment shapes:

### 6a — Apache + mod_auth_openidc with Keycloak as the IdP

Same as Pattern 3. Use Keycloak's OIDC discovery URL:
`https://<keycloak-host>/realms/<realm>/.well-known/openid-configuration`.
LibreClinica env unchanged from Pattern 3 except the discovery URL.

### 6b — Keycloak Gatekeeper sidecar

Keycloak ships a dedicated reverse-proxy auth sidecar
(`quay.io/keycloak/keycloak-gatekeeper`, originally Louketo Proxy).
Smaller than Apache; OIDC-aware out of the box.

```yaml
# docker-compose.sso.yml — gatekeeper variant
services:
  keycloak-gatekeeper:
    image: quay.io/keycloak/keycloak-gatekeeper:latest
    command:
      - --discovery-url=https://<keycloak-host>/realms/<realm>
      - --client-id=libreclinica
      - --client-secret=<secret>
      - --listen=:8443
      - --upstream-url=http://libreclinica:8080
      - --enable-default-deny=true
      - --resources=uri=/pages/login/login|white-listed=true
      - --resources=uri=/j_spring_security_check|white-listed=true
      - --add-claims=email,preferred_username
    ports:
      - "127.0.0.1:8443:8443"
```

```sh
LIBRECLINICA_SSO_ENABLED=true
LIBRECLINICA_SSO_PROVIDER=keycloak-<realm>
LIBRECLINICA_SSO_PRINCIPAL_HEADER=X-Auth-Username     # gatekeeper convention
LIBRECLINICA_SSO_EMAIL_HEADER=X-Auth-Email
```

## Pattern 7 — oauth2-proxy

**Context.** A popular OSS reverse-proxy sidecar that handles OIDC
against Azure AD / Google / Okta / GitHub / etc. Lightweight (single
Go binary), well-maintained.

```yaml
# docker-compose.sso.yml — oauth2-proxy variant
services:
  oauth2-proxy:
    image: quay.io/oauth2-proxy/oauth2-proxy:latest
    command:
      - --provider=oidc
      - --oidc-issuer-url=https://login.example.com/oauth/v2/oidc
      - --client-id=libreclinica
      - --client-secret=<secret>
      - --cookie-secret=<32-bytes-base64>
      - --http-address=0.0.0.0:4180
      - --upstream=http://libreclinica:8080
      - --email-domain=*
      - --skip-auth-route=^/LibreClinica/pages/login/login$
      - --skip-auth-route=^/LibreClinica/j_spring_security_check$
      - --pass-user-headers=true        # adds X-Forwarded-User + X-Forwarded-Email
    ports:
      - "127.0.0.1:4180:4180"
```

```sh
LIBRECLINICA_SSO_ENABLED=true
LIBRECLINICA_SSO_PROVIDER=oauth2-proxy-prod
LIBRECLINICA_SSO_PRINCIPAL_HEADER=X-Forwarded-User
LIBRECLINICA_SSO_EMAIL_HEADER=X-Forwarded-Email
```

## Pattern 8 — No SSO

Don't deploy any sidecar. The default `compose.yaml` is the no-SSO
deployment; LibreClinica's local username/password + LDAP paths are
the only auth surface. Set:

```sh
LIBRECLINICA_SSO_ENABLED=false   # explicit; same as the default
```

The login page renders without the SSO button; the
`RequestHeaderAuthenticationFilter` is not registered; the
`SsoReauthController` returns 302 to the local login page if
called.

This is the right choice for small deployments without an
institutional IdP, or for the initial bring-up before SSO is
ratified.

## Production-readiness checklist (applies to all patterns)

- [ ] `LIBRECLINICA_SSO_TRUSTED_CIDRS` narrowed to the actual
      reverse-proxy IP — NOT the broad RFC1918 default
- [ ] Tomcat bound to the compose-internal network only
      (compose.yaml ports list emptied via `!override []` when SSO
      sidecar is active)
- [ ] Reverse-proxy bypass URLs verified: `/pages/login/login`,
      `/j_spring_security_check`, `/j_spring_security_logout`,
      `/includes/`, `/images/`, `/help/`, `/actuator/health`,
      `/actuator/info`
- [ ] User provisioning: pre-create `user_account` rows with
      `external_id` + `external_id_provider` populated (default
      LOOKUP_ONLY strategy — JIT only for closed IdPs)
- [ ] Local-account login still works (sponsor monitors, demo
      users, break-glass)
- [ ] `audit_user_login` rows are being written for both
      `SSO_LOGIN(6)` and `SSO_LOGIN_FAILED(7)` codes (verify via
      psql post-cutover)
- [ ] `libreclinica.sso.delegate-mfa-to-idp=true` IF the IdP
      enforces MFA; otherwise leave local 2FA active
- [ ] `libreclinica.sso.reauth.enabled=false` until legal /
      regulatory ratifies proxy re-auth as §11.50-compliant
- [ ] SP cert / OIDC client secret rotation calendar set per
      institutional policy
- [ ] Backup local sysadmin account exists with a known password
      (break-glass for SSO outages)

## When a new pattern is needed

If an institution adopts LibreClinicaMUW with a reverse proxy not
covered above:

1. Identify what header(s) the proxy injects with the authenticated
   principal.
2. Set `LIBRECLINICA_SSO_PRINCIPAL_HEADER` to that header name.
3. Set `LIBRECLINICA_SSO_TRUSTED_CIDRS` to the proxy's IP range.
4. Provision users with the principal value as `external_id`.
5. **No code change is needed.** That's the architectural promise of
   DR-014.

If the change is non-trivial (e.g. a proxy that uses a signed
header that needs verification), open a feature request in
[the issue tracker](https://github.com/LuViKu/LibreClinicaMUW/issues).
