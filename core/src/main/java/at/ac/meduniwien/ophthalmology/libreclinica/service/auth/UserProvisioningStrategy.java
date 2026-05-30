/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.auth;

import java.util.Map;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

/**
 * Phase D.4 (DR-014): provisioning policy for SSO-authenticated
 * principals that do not yet have a matching {@code user_account}
 * row.
 *
 * <p>Two stock strategies:
 * <ul>
 *   <li>{@link LookupOnlyProvisioningStrategy} (default) — reject
 *       unknown principals. Admins pre-provision every SSO user via
 *       the existing Create User flow with the {@code external_id +
 *       external_id_provider} columns populated. Safe for the
 *       GCP-validated initial rollout; institutional-IT-only
 *       audience.</li>
 *   <li>{@link JitProvisioningStrategy} (opt-in) — create the user
 *       row on first SSO login with the configured default role.
 *       Risky for open IdPs (every IdP-authenticated user gets a
 *       LibreClinica account); appropriate only for closed IdPs
 *       where the IdP's user population matches the LibreClinica
 *       target audience.</li>
 * </ul>
 *
 * <p>Pluggability: institutions can plug in their own strategy bean
 * (e.g. JIT with custom attribute-driven role mapping; or LOOKUP_ONLY
 * with admin-notification on miss) by replacing the bean wiring in
 * {@code PasswordEncoderConfig.userProvisioningStrategy(...)}. The
 * default selection is driven by
 * {@code libreclinica.sso.provisioning.strategy} env var.
 *
 * @see <a href="../../../../../../../docs/development/modernization/decision-record.md#dr-014--institution-agnostic-sso-via-reverse-proxy-pre-authentication">DR-014</a>
 */
public interface UserProvisioningStrategy {

    /**
     * Given an SSO principal and its attribute headers, resolve to a
     * LibreClinica {@link UserAccountBean}. If the strategy chooses
     * to reject (e.g. LOOKUP_ONLY miss, or JIT detects a policy
     * violation), throws {@link UsernameNotFoundException} — the
     * pre-auth filter then falls through to local username/password
     * (or LDAP) auth, which is the safer default than auto-failing
     * the entire request.
     *
     * @param provider    the SSO provider namespace (e.g.
     *     {@code shibboleth-meduniwien})
     * @param principal   the principal value from the configured
     *     header (eppn / sub / oid / etc.)
     * @param attributes  additional attribute headers consumed during
     *     JIT provisioning (email, displayName, …). Empty map for
     *     LOOKUP_ONLY callers that don't need attributes.
     * @return the resolved {@link UserAccountBean}; never null
     * @throws UsernameNotFoundException when the strategy rejects
     */
    UserAccountBean resolveOrProvision(String provider,
                                       String principal,
                                       Map<String, String> attributes)
            throws UsernameNotFoundException;
}
