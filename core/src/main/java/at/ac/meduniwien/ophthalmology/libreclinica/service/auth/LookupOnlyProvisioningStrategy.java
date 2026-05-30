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
 * Phase D.4 (DR-014): the default {@link UserProvisioningStrategy} —
 * require admin pre-provisioning. Looks up the user via
 * {@link UserAccountDAO#findByExternalIdentity}; if no row matches,
 * throws {@link UsernameNotFoundException} which the pre-auth filter
 * propagates to fall through to local username/password.
 *
 * <p>Recommended for the MedUni Wien initial rollout (GCP-validated
 * deployment, institutional-IT-only audience). Switch to
 * {@link JitProvisioningStrategy} once admin processes for SSO-user
 * onboarding are comfortable, per DR-016 (open).
 */
public class LookupOnlyProvisioningStrategy implements UserProvisioningStrategy {

    private static final Logger log =
            LoggerFactory.getLogger(LookupOnlyProvisioningStrategy.class);

    private final UserAccountDAO userAccountDao;

    public LookupOnlyProvisioningStrategy(UserAccountDAO userAccountDao) {
        this.userAccountDao = userAccountDao;
    }

    @Override
    public UserAccountBean resolveOrProvision(String provider,
                                              String principal,
                                              Map<String, String> attributes)
            throws UsernameNotFoundException {
        UserAccountBean user = userAccountDao.findByExternalIdentity(provider, principal);
        if (user == null || user.getId() <= 0) {
            log.info("LOOKUP_ONLY: rejected unknown SSO principal '{}'"
                    + " under provider '{}' (no user_account row)."
                    + " Admin must pre-provision via Create User with"
                    + " external_id + external_id_provider populated.",
                    principal, provider);
            throw new UsernameNotFoundException(
                    "No LibreClinica user bound to SSO principal '"
                            + principal + "' under provider '"
                            + provider + "'");
        }
        return user;
    }
}
