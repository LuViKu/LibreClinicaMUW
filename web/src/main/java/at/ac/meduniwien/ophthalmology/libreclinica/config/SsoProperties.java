/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase D.3 (DR-014): institution-agnostic SSO configuration bound
 * from {@code application.yml} via {@code @ConfigurationProperties}.
 *
 * <p>The in-app SSO surface is a single
 * {@code RequestHeaderAuthenticationFilter} consuming configurable
 * HTTP headers; the actual SSO protocol (SAML / OIDC / OAuth) is
 * handled by the reverse-proxy sidecar (Apache + mod_shib for the
 * MedUni Wien reference deployment; mod_auth_openidc / oauth2-proxy /
 * Keycloak Gatekeeper / AWS ALB OIDC for other institutions).
 * Provider swap = sidecar + env-var change; never code change.
 *
 * <p>Default {@code enabled = false} means the filter is not
 * registered; the auth flow is identical to Phase C / D.2 closure.
 * Flipping the flag activates the SSO pre-auth path.
 *
 * <p>YAML binding (under {@code libreclinica.sso}):
 * <pre>
 * libreclinica:
 *   sso:
 *     enabled: ${LIBRECLINICA_SSO_ENABLED:false}
 *     header:
 *       principal: ${LIBRECLINICA_SSO_PRINCIPAL_HEADER:REMOTE_USER}
 *       email: ${LIBRECLINICA_SSO_EMAIL_HEADER:mail}
 *       display-name: ${LIBRECLINICA_SSO_DISPLAY_NAME_HEADER:displayName}
 *       attribute-headers:
 *         eppn: eduPersonPrincipalName
 *     provider:
 *       name: ${LIBRECLINICA_SSO_PROVIDER:shibboleth-meduniwien}
 *     provisioning:
 *       strategy: ${LIBRECLINICA_SSO_PROVISIONING:LOOKUP_ONLY}
 *       default-role: ${LIBRECLINICA_SSO_DEFAULT_ROLE:ROLE_USER}
 *     trusted-proxy:
 *       allowed-cidrs: ${LIBRECLINICA_SSO_TRUSTED_CIDRS:127.0.0.1/32,172.16.0.0/12}
 *     entry-url: ${LIBRECLINICA_SSO_ENTRY_URL:/sso/login}
 *     button-label: ${LIBRECLINICA_SSO_BUTTON_LABEL:Sign in with Institutional Account}
 *     delegate-mfa-to-idp: ${LIBRECLINICA_SSO_DELEGATE_MFA:true}
 *     reauth:
 *       enabled: ${LIBRECLINICA_SSO_REAUTH:false}
 *       url-template: ${LIBRECLINICA_SSO_REAUTH_URL_TEMPLATE:/Shibboleth.sso/Login?forceAuthn=true&target={target}}
 * </pre>
 *
 * @see <a href="../../../../../../../docs/development/modernization/decision-record.md#dr-014--institution-agnostic-sso-via-reverse-proxy-pre-authentication">DR-014</a>
 */
public class SsoProperties {

    /**
     * Master flag. False means the SSO pre-auth filter is not
     * registered and the auth flow falls through to local
     * username/password. True activates the pre-auth path; requests
     * carrying the configured principal header from a trusted
     * upstream are authenticated without password.
     */
    private boolean enabled = false;

    private Header header = new Header();
    private Provider provider = new Provider();
    private Provisioning provisioning = new Provisioning();
    private TrustedProxy trustedProxy = new TrustedProxy();

    /** URL the "Sign in with Institutional Account" button targets. */
    private String entryUrl = "/sso/login";

    /** Display label on the institutional-SSO login affordance. */
    private String buttonLabel = "Sign in with Institutional Account";

    /**
     * When true, SSO-bound users skip the TOTP 2FA challenge. The IdP
     * is responsible for MFA. Local-account users still see 2FA.
     */
    private boolean delegateMfaToIdp = true;

    private Reauth reauth = new Reauth();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public Header getHeader() { return header; }
    public void setHeader(Header header) { this.header = header; }

    public Provider getProvider() { return provider; }
    public void setProvider(Provider provider) { this.provider = provider; }

    public Provisioning getProvisioning() { return provisioning; }
    public void setProvisioning(Provisioning provisioning) { this.provisioning = provisioning; }

    public TrustedProxy getTrustedProxy() { return trustedProxy; }
    public void setTrustedProxy(TrustedProxy trustedProxy) { this.trustedProxy = trustedProxy; }

    public String getEntryUrl() { return entryUrl; }
    public void setEntryUrl(String entryUrl) { this.entryUrl = entryUrl; }

    public String getButtonLabel() { return buttonLabel; }
    public void setButtonLabel(String buttonLabel) { this.buttonLabel = buttonLabel; }

    public boolean isDelegateMfaToIdp() { return delegateMfaToIdp; }
    public void setDelegateMfaToIdp(boolean delegateMfaToIdp) { this.delegateMfaToIdp = delegateMfaToIdp; }

    public Reauth getReauth() { return reauth; }
    public void setReauth(Reauth reauth) { this.reauth = reauth; }

    /**
     * Configurable header names the in-app
     * {@code RequestHeaderAuthenticationFilter} reads. The reverse
     * proxy populates these from the SSO protocol response.
     */
    public static class Header {
        /**
         * The request header carrying the authenticated principal —
         * Shibboleth's {@code REMOTE_USER}, OIDC's {@code OIDC_CLAIM_sub},
         * AWS ALB's {@code x-amzn-oidc-identity}, etc.
         */
        private String principal = "REMOTE_USER";
        private String email = "mail";
        private String displayName = "displayName";
        /**
         * Free-form additional attribute headers consumed during JIT
         * provisioning. Key = canonical attribute name; value = actual
         * HTTP request header name.
         */
        private Map<String, String> attributeHeaders = new HashMap<>();

        public String getPrincipal() { return principal; }
        public void setPrincipal(String principal) { this.principal = principal; }

        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }

        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }

        public Map<String, String> getAttributeHeaders() { return attributeHeaders; }
        public void setAttributeHeaders(Map<String, String> attributeHeaders) {
            this.attributeHeaders = attributeHeaders;
        }
    }

    /**
     * Identifies the active SSO provider. Stored on
     * {@code user_account.external_id_provider} so multiple SSO
     * providers can coexist in one deployment.
     */
    public static class Provider {
        private String name = "shibboleth-meduniwien";

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }

    /**
     * JIT vs LOOKUP_ONLY provisioning on first SSO login. See
     * DR-016 (open) for the MedUni Wien default decision.
     */
    public static class Provisioning {
        public enum Strategy { LOOKUP_ONLY, JIT }

        private Strategy strategy = Strategy.LOOKUP_ONLY;
        private String defaultRole = "ROLE_USER";

        public Strategy getStrategy() { return strategy; }
        public void setStrategy(Strategy strategy) { this.strategy = strategy; }

        public String getDefaultRole() { return defaultRole; }
        public void setDefaultRole(String defaultRole) { this.defaultRole = defaultRole; }
    }

    /**
     * Network controls. Pre-auth headers from upstream IPs outside
     * the allowlist are ignored — defends against header spoofing
     * if Tomcat is reachable directly.
     */
    public static class TrustedProxy {
        /**
         * CIDR ranges that may inject pre-auth headers. Default to
         * loopback + compose-internal RFC1918 (172.16/12) for the
         * reference deployment; production should narrow to the
         * specific Apache sidecar IP.
         */
        private List<String> allowedCidrs = new ArrayList<>(java.util.Arrays.asList(
                "127.0.0.1/32",
                "172.16.0.0/12"));

        public List<String> getAllowedCidrs() { return allowedCidrs; }
        public void setAllowedCidrs(List<String> allowedCidrs) {
            this.allowedCidrs = allowedCidrs;
        }
    }

    /**
     * E-signature re-auth scaffolding (Phase D.10). Flag stays OFF
     * until legal/regulatory ratifies proxy-mediated re-auth as
     * §11.50-compliant. The Sign Subject flow continues to require
     * local password challenge until then.
     */
    public static class Reauth {
        private boolean enabled = false;
        /**
         * Template for the proxy re-challenge URL. For Shibboleth:
         * {@code /Shibboleth.sso/Login?forceAuthn=true&target={target}}.
         * For OIDC under mod_auth_openidc: includes {@code prompt=login}.
         * The literal {@code {target}} placeholder is replaced with
         * the user's original URL before redirect.
         */
        private String urlTemplate = "/Shibboleth.sso/Login?forceAuthn=true&target={target}";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public String getUrlTemplate() { return urlTemplate; }
        public void setUrlTemplate(String urlTemplate) { this.urlTemplate = urlTemplate; }
    }
}
