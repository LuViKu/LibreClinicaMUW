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
import java.util.List;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.config.SsoProperties;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.login.UserAccountDAO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedGrantedAuthoritiesUserDetailsService;

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

    private final UserAccountDAO userAccountDao;
    private final SsoProperties ssoProperties;

    public SsoUserDetailsService(UserAccountDAO userAccountDao,
                                 SsoProperties ssoProperties) {
        this.userAccountDao = userAccountDao;
        this.ssoProperties = ssoProperties;
    }

    /**
     * Called by {@link org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationProvider}
     * after the {@link org.springframework.security.web.authentication.preauth.RequestHeaderAuthenticationFilter}
     * extracts the principal from the configured header.
     *
     * @param token the pre-auth token carrying the principal (from
     *     the SSO header) as its name
     * @return a {@link UserDetails} for the resolved user
     * @throws UsernameNotFoundException when no
     *     {@code user_account} row matches the (provider, principal)
     *     pair — caller (D.4) decides whether to JIT-provision or
     *     reject
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

        UserAccountBean user = userAccountDao.findByExternalIdentity(provider, principal);
        if (user == null || user.getId() <= 0) {
            log.info("SSO principal '{}' (provider='{}') not found in"
                    + " user_account — LOOKUP_ONLY rejects",
                    principal, provider);
            throw new UsernameNotFoundException(
                    "No LibreClinica user bound to SSO principal '"
                            + principal + "' under provider '"
                            + provider + "'");
        }

        // Map to Spring Security User. Authorities default to a
        // single ROLE_USER — Phase D.4 enriches via attribute
        // mapping if configured. Phase D's role mapping (DR-017)
        // refines this further once finalised.
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

    /**
     * Used internally — the
     * {@link PreAuthenticatedGrantedAuthoritiesUserDetailsService}
     * alternative just builds {@code UserDetails} from the token's
     * authorities without a DB lookup. We always do the lookup so we
     * can apply the LOOKUP_ONLY policy.
     */
    @SuppressWarnings("unused")
    private void ignore() {
        // intentionally empty — silences the unused-import warning
        new PreAuthenticatedGrantedAuthoritiesUserDetailsService();
    }
}
