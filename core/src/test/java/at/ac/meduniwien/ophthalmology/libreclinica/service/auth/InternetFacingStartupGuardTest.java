/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.auth;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;

import org.junit.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.crypto.password.PasswordEncoder;

import at.ac.meduniwien.ophthalmology.libreclinica.config.PasswordEncoderConfig;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.InternetFacingStartupGuard.ActiveAccount;

public class InternetFacingStartupGuardTest {

    private final PasswordEncoder encoder = new PasswordEncoderConfig().passwordEncoder();

    private InternetFacingStartupGuard guard() {
        return new InternetFacingStartupGuard(null, encoder, true);
    }

    @Test
    public void rootWithTheSeededLegacyHash_blocksStartup() {
        try {
            guard().verify(List.of(
                    new ActiveAccount("root", InternetFacingStartupGuard.SEEDED_DEFAULT_HASH)));
            fail("expected the guard to refuse");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("root"));
            assertTrue(e.getMessage(), e.getMessage().contains("internet-facing"));
            assertTrue("the message must not leak the hash",
                    !e.getMessage().contains(InternetFacingStartupGuard.SEEDED_DEFAULT_HASH));
        }
    }

    @Test
    public void rootRehashedToBcryptButStillTheDefaultPassword_blocksStartup() {
        // The first login of root rewrites the MD5 as {bcrypt}…; the password is unchanged.
        String bcryptOfDefault = encoder.encode("12345678");
        try {
            guard().verify(List.of(new ActiveAccount("root", bcryptOfDefault)));
            fail("expected the guard to refuse");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("root"));
        }
    }

    @Test
    public void anyOtherActiveAccountWithTheDefaultHash_blocksStartupAndNamesIt() {
        try {
            guard().verify(List.of(
                    new ActiveAccount("root", encoder.encode("a-Long-Unique-Passphrase-42")),
                    new ActiveAccount("dm.smith", InternetFacingStartupGuard.SEEDED_DEFAULT_HASH),
                    new ActiveAccount("nurse.jones", encoder.encode("12345678"))));
            fail("expected the guard to refuse");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("dm.smith"));
            assertTrue(e.getMessage(), e.getMessage().contains("nurse.jones"));
            assertTrue(e.getMessage(), !e.getMessage().contains("root"));
        }
    }

    @Test
    public void changedPasswords_start() {
        guard().verify(List.of(
                new ActiveAccount("root", encoder.encode("a-Long-Unique-Passphrase-42")),
                new ActiveAccount("legacy", "0123456789abcdef0123456789abcdef"),
                new ActiveAccount("blank", ""),
                new ActiveAccount("null", null),
                new ActiveAccount("garbage", "{unknownscheme}xyz")));
    }

    @Test
    public void flagOff_doesNotLookAtTheDatabase() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        new InternetFacingStartupGuard(jdbc, encoder, false).afterSingletonsInstantiated();
        verifyNoInteractions(jdbc);
    }

    @Test
    public void flagOn_readsActiveAccountsAndRefuses() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        doReturn(List.of(new ActiveAccount("root", InternetFacingStartupGuard.SEEDED_DEFAULT_HASH)))
                .when(jdbc).query(anyString(), ArgumentMatchers.<RowMapper<ActiveAccount>>any());
        try {
            new InternetFacingStartupGuard(jdbc, encoder, true).afterSingletonsInstantiated();
            fail("expected the guard to refuse");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("root"));
        }
    }

    @Test
    public void flagOn_cleanDatabase_starts() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        doReturn(List.of(new ActiveAccount("root", encoder.encode("a-Long-Unique-Passphrase-42"))))
                .when(jdbc).query(anyString(), ArgumentMatchers.<RowMapper<ActiveAccount>>any());
        new InternetFacingStartupGuard(jdbc, encoder, true).afterSingletonsInstantiated();
    }
}
