/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.auth;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Refuses to start an internet-facing deployment
 * ({@code libreclinica.deployment.internet-facing=true}) while an active
 * account still has the password every fresh LibreClinica database ships with.
 *
 * <p>A new database contains {@code root} with the password {@code 12345678},
 * stored as the legacy MD5 hash {@value #SEEDED_DEFAULT_HASH}
 * ({@code migration/2.5/changeLogDataInsert.xml}). Left alone, that is a
 * system-administrator login known to the whole internet. Two cases are caught:
 * <ul>
 *   <li>the seeded hash itself, on any active account; and</li>
 *   <li>the default password under any other stored form (for example the
 *       bcrypt hash that the first login of {@code root} writes back), found
 *       by checking the default password against every active account.</li>
 * </ul>
 * "Active" means {@code status_id = 1} and {@code enabled}. A disabled or
 * removed account cannot log in, so it does not block the start.
 *
 * <p>Runs once all singletons exist, so Liquibase has already created or
 * migrated the schema. The failure message names the accounts (never a hash).
 */
public class InternetFacingStartupGuard implements SmartInitializingSingleton {

    private static final Logger LOG = LoggerFactory.getLogger(InternetFacingStartupGuard.class);

    /** MD5 of "12345678" as inserted for user_id 1 ("root"). */
    public static final String SEEDED_DEFAULT_HASH = "25d55ad283aa400af464c76d713c07ad";
    static final String SEEDED_DEFAULT_PASSWORD = "12345678";

    /** An enabled, available account. */
    public record ActiveAccount(String userName, String passwdHash) { }

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final boolean internetFacing;

    public InternetFacingStartupGuard(JdbcTemplate jdbc, PasswordEncoder encoder, boolean internetFacing) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.internetFacing = internetFacing;
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (!internetFacing) {
            return;
        }
        List<ActiveAccount> accounts = jdbc.query(
                "SELECT user_name, passwd FROM user_account WHERE status_id = 1 AND enabled = true",
                (rs, i) -> new ActiveAccount(rs.getString("user_name"), rs.getString("passwd")));
        verify(accounts);
        LOG.info("internet-facing startup guard: no active account has the seeded default password");
    }

    /**
     * @throws IllegalStateException naming every offending account, if any
     */
    void verify(List<ActiveAccount> accounts) {
        List<String> offenders = new ArrayList<>();
        for (ActiveAccount a : accounts) {
            if (usesDefaultPassword(a.passwdHash())) {
                offenders.add(a.userName());
            }
        }
        if (offenders.isEmpty()) {
            return;
        }
        String message = "Refusing to start: libreclinica.deployment.internet-facing=true but "
                + offenders.size() + " active account(s) still have the default password "
                + "shipped with a new database: " + offenders
                + ". Change the password of each (or disable the account) and restart.";
        LOG.error(message);
        throw new IllegalStateException(message);
    }

    private boolean usesDefaultPassword(String storedHash) {
        if (storedHash == null || storedHash.isBlank()) {
            return false;
        }
        if (SEEDED_DEFAULT_HASH.equalsIgnoreCase(storedHash.trim())) {
            return true;
        }
        try {
            return encoder.matches(SEEDED_DEFAULT_PASSWORD, storedHash);
        } catch (RuntimeException e) {
            // A hash in a format the encoder cannot parse cannot be the default.
            return false;
        }
    }
}
