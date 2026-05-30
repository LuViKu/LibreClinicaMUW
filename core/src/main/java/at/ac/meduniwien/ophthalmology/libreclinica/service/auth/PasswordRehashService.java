/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.auth;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.login.UserAccountDAO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Phase D.1.b (DR-015): lazy bcrypt rehash on successful legacy login.
 *
 * <p>Called by
 * {@code OpenClinicaUsernamePasswordAuthenticationFilter.attemptAuthentication}
 * after a successful authentication. Inspects the user's stored hash
 * via {@link PasswordEncoder#upgradeEncoding(String)} and — if the
 * signal is true (i.e. the hash is one of the legacy MD5/SHA-1
 * formats matched by {@code LegacyMd5Sha1PasswordEncoder}) — encodes
 * the plaintext with bcrypt via {@link PasswordEncoder#encode} and
 * persists the new hash via
 * {@link UserAccountDAO#updatePasswordHash(Integer, String)}.
 *
 * <p>No-op for hashes that are already bcrypt
 * ({@code upgradeEncoding} returns false on {@code {bcrypt}…}) so
 * repeated logins by a single user don't loop the rehash.
 *
 * <p>Defensive on null inputs (user, raw password, or stored hash) —
 * the caller is the auth filter, which holds the plaintext briefly
 * during the auth flow; we want to fail closed if anything looks off
 * rather than risk corrupting a row.
 *
 * <p>The plaintext password lives in JVM heap during this call. The
 * caller (the filter) clears it implicitly when the local variable
 * goes out of scope after returning the {@link
 * org.springframework.security.core.Authentication} result.
 */
public class PasswordRehashService {

    private static final Logger log =
            LoggerFactory.getLogger(PasswordRehashService.class);

    private final PasswordEncoder encoder;
    private final UserAccountDAO userAccountDao;

    public PasswordRehashService(PasswordEncoder encoder,
                                 UserAccountDAO userAccountDao) {
        this.encoder = encoder;
        this.userAccountDao = userAccountDao;
    }

    /**
     * If {@code user.getPasswd()} is a legacy hash (MD5 / SHA-1),
     * encodes {@code rawPassword} as bcrypt and persists the result.
     * No-op otherwise.
     *
     * @param user        the authenticated user — must be non-null and
     *                    have a positive id (the {@code findByUserName}
     *                    "no such user" sentinel id 0 is rejected)
     * @param rawPassword the plaintext password the user just supplied
     *                    — only used as the bcrypt input; never logged
     */
    public void rehashAfterSuccessfulLogin(UserAccountBean user, String rawPassword) {
        if (user == null || user.getId() <= 0) {
            return;
        }
        if (rawPassword == null || rawPassword.isEmpty()) {
            return;
        }
        String currentHash = user.getPasswd();
        if (currentHash == null || currentHash.isEmpty()) {
            return;
        }
        if (!encoder.upgradeEncoding(currentHash)) {
            // Already on the modern format (bcrypt) — nothing to do.
            return;
        }
        try {
            String newHash = encoder.encode(rawPassword);
            userAccountDao.updatePasswordHash(user.getId(), newHash);
            // Update the in-memory bean so callers downstream of this
            // service see the new hash (the same UserAccountBean is
            // stashed in the session for the duration of the auth
            // flow).
            user.setPasswd(newHash);
            log.info("Lazy bcrypt rehash applied to user_id={}", user.getId());
        } catch (Exception e) {
            // Rehash failure must NOT fail the login — the user has
            // already authenticated successfully against the legacy
            // hash. Log and let the next login retry.
            log.warn("Lazy bcrypt rehash failed for user_id={}; legacy"
                    + " hash retained, next login will retry."
                    + " Cause: {}",
                    user.getId(), e.getMessage());
        }
    }
}
