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
import at.ac.meduniwien.ophthalmology.libreclinica.dao.login.UserAccountDAO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

/**
 * Phase D.4 (DR-014): JIT (just-in-time) provisioning strategy —
 * creates a {@code user_account} row on first SSO login if none
 * matches the (provider, principal) pair.
 *
 * <p><strong>Scaffold only.</strong> The actual JIT row-creation
 * code path (mapping attribute headers → user_account columns,
 * assigning the configured default role, generating a
 * unique user_name when the bare principal collides with an
 * existing local account) is intentionally deferred to the next
 * Phase D sub-phase. The interface exists so the
 * {@code libreclinica.sso.provisioning.strategy=JIT} switch is
 * recognised by configuration binding even though it currently
 * delegates to LOOKUP_ONLY-equivalent behaviour.
 *
 * <p>Deferral rationale: JIT requires a UX decision on the
 * collision behaviour (auto-suffix the user_name vs. fail with
 * an admin notification) plus the role-mapping rules from DR-017
 * (open). Shipping a half-implemented JIT path is worse than
 * shipping LOOKUP_ONLY with a feature-flagged JIT scaffold.
 *
 * <p>When the deferred work lands, this class:
 * <ol>
 *   <li>Tries findByExternalIdentity first (existing user).</li>
 *   <li>On miss, builds a {@code UserAccountBean} with attributes
 *       mapped from {@code attributes}, role from
 *       {@code SsoProperties.getProvisioning().getDefaultRole()},
 *       status = AVAILABLE.</li>
 *   <li>Resolves user_name collision via "{principal}-sso" suffix
 *       (or admin-notification per the deferred UX decision).</li>
 *   <li>Persists via {@link UserAccountDAO} (a new
 *       targeted-create method, sister of updatePasswordHash).</li>
 *   <li>Emits an {@code AdminNotificationEvent} so admins see the
 *       new account.</li>
 * </ol>
 */
public class JitProvisioningStrategy implements UserProvisioningStrategy {

    private static final Logger log =
            LoggerFactory.getLogger(JitProvisioningStrategy.class);

    private final UserAccountDAO userAccountDao;

    public JitProvisioningStrategy(UserAccountDAO userAccountDao) {
        this.userAccountDao = userAccountDao;
    }

    @Override
    public UserAccountBean resolveOrProvision(String provider,
                                              String principal,
                                              Map<String, String> attributes)
            throws UsernameNotFoundException {
        UserAccountBean user = userAccountDao.findByExternalIdentity(provider, principal);
        if (user != null && user.getId() > 0) {
            return user;
        }

        // JIT row-creation is scaffold-only in this sub-phase. See
        // class-level Javadoc for the planned shape. For now, log
        // the JIT-would-have-created intent and reject so the auth
        // flow falls through to local password (the safer default
        // until JIT is fully wired with role-mapping rules from
        // DR-017).
        log.warn("JIT provisioning REQUESTED for SSO principal '{}'"
                + " under provider '{}' (attributes={}) but JIT"
                + " implementation is scaffold-only in this sub-phase."
                + " Falling through to LOOKUP_ONLY-equivalent reject."
                + " To enable JIT, complete the row-creation path"
                + " per the class Javadoc.",
                principal, provider, attributes);
        throw new UsernameNotFoundException(
                "JIT scaffold did not provision SSO principal '"
                        + principal + "' — implementation deferred");
    }
}
