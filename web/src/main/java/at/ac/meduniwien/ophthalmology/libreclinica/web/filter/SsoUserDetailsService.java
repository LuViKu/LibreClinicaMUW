/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.web.filter;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.config.SsoProperties;
import at.ac.meduniwien.ophthalmology.libreclinica.service.audit.LoginAuditService;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.UserProvisioningStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;

/**
 * Phase D.3 (DR-014): resolves a pre-authenticated SSO principal
 * (the value of the configured principal header — Shibboleth
 * {@code REMOTE_USER}, OIDC {@code sub}, AWS ALB
 * {@code x-amzn-oidc-identity}, etc.) to a Spring Security
 * {@link UserDetails} via
 * {@link UserAccountDAO#findByExternalIdentity}.
 *
 * <p>The provider namespace ({@code external_id_provider}) is read
 * from {@link SsoProperties.Provider#getName()} — single-provider
 * deployment is the default. Multi-provider deployments override
 * via env var per institution.
 *
 * <p>Currently implements the {@code LOOKUP_ONLY} provisioning
 * strategy implicitly: if no row matches the
 * (provider, principal) pair, throws
 * {@link UsernameNotFoundException}. Phase D.4 generalises this
 * via a {@code UserProvisioningStrategy} interface with a JIT
 * alternative.
 *
 * @see <a href="../../../../../../../docs/development/modernization/phase-d-execution-playbook.md">Phase D playbook §D.3</a>
 */
public class SsoUserDetailsService implements
        org.springframework.security.core.userdetails.AuthenticationUserDetailsService<PreAuthenticatedAuthenticationToken> {

    private static final Logger log =
            LoggerFactory.getLogger(SsoUserDetailsService.class);

    private final UserProvisioningStrategy provisioningStrategy;
    private final SsoProperties ssoProperties;
    private final LoginAuditService loginAuditService;

    /**
     * Phase D.4 (DR-014): delegates miss-handling to a pluggable
     * {@link UserProvisioningStrategy} (LOOKUP_ONLY default;
     * JIT opt-in via {@code libreclinica.sso.provisioning.strategy}).
     *
     * <p>Phase D.5 (DR-014): writes audit_user_login rows for both
     * SSO_LOGIN (success) and SSO_LOGIN_FAILED (provisioning reject)
     * via the shared {@link LoginAuditService}.
     */
    public SsoUserDetailsService(UserProvisioningStrategy provisioningStrategy,
                                 SsoProperties ssoProperties,
                                 LoginAuditService loginAuditService) {
        this.provisioningStrategy = provisioningStrategy;
        this.ssoProperties = ssoProperties;
        this.loginAuditService = loginAuditService;
    }

    /**
     * Called by {@link org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationProvider}
     * after the {@link org.springframework.security.web.authentication.preauth.RequestHeaderAuthenticationFilter}
     * extracts the principal from the configured header.
     *
     * @param token the pre-auth token carrying the principal (from
     *     the SSO header) as its name
     * @return a {@link UserDetails} for the resolved user
     * @throws UsernameNotFoundException when the configured
     *     provisioning strategy rejects the principal
     */
    @Override
    public UserDetails loadUserDetails(PreAuthenticatedAuthenticationToken token)
            throws UsernameNotFoundException {
        String principal = token.getName();
        String provider = ssoProperties.getProvider().getName();

        if (principal == null || principal.isEmpty()) {
            // The RequestHeaderAuthenticationFilter throws before
            // reaching us when the header is absent; this branch
            // is defensive against future callers.
            throw new UsernameNotFoundException(
                    "SSO principal header was empty");
        }

        // D.4 (DR-014): attribute headers consumed during JIT
        // provisioning would feed in here. Empty map is fine for
        // LOOKUP_ONLY (the default); JIT enrichment will read from
        // the request via a future filter extension.
        UserAccountBean user;
        try {
            user = provisioningStrategy.resolveOrProvision(
                    provider, principal, Collections.<String, String>emptyMap());
        } catch (UsernameNotFoundException reject) {
            // D.5: provisioning strategy rejected — audit the
            // failure so operators can see the rejected principal
            // in the audit-log report.
            loginAuditService.recordSsoFailure(principal, provider);
            throw reject;
        }

        if (user == null || user.getId() <= 0) {
            // Defensive: strategies SHOULD throw rather than return null
            // (interface contract). This branch protects against a buggy
            // custom strategy that returns null instead of throwing.
            log.warn("Provisioning strategy returned null for principal"
                    + " '{}' (provider='{}') — treating as reject",
                    principal, provider);
            loginAuditService.recordSsoFailure(principal, provider);
            throw new UsernameNotFoundException(
                    "Provisioning strategy returned no user for SSO"
                            + " principal '" + principal + "'");
        }

        // D.5: successful pre-auth → SSO_LOGIN audit row.
        loginAuditService.recordSsoSuccess(user, principal);

        // Map to Spring Security User. Authorities default to the
        // configured role from libreclinica.sso.provisioning.default-role.
        // Attribute-driven role mapping is deferred to DR-017 (open).
        String role = ssoProperties.getProvisioning().getDefaultRole();
        List<GrantedAuthority> authorities = Arrays.asList(new SimpleGrantedAuthority(role));

        // SSO-authenticated users have no local password to compare;
        // we pass the stored hash so the standard auth machinery
        // can short-circuit on the pre-auth token (the
        // PreAuthenticatedAuthenticationProvider does no password
        // matching). isCredentialsNonExpired = true.
        return new User(
                user.getName(),
                user.getPasswd() != null ? user.getPasswd() : "",
                user.getEnabled() != null ? user.getEnabled() : false,
                true,                   // accountNonExpired
                true,                   // credentialsNonExpired
                user.getAccountNonLocked() != null && user.getAccountNonLocked(),
                authorities);
    }

}
