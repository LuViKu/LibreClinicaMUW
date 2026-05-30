/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.auth;

import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.login.UserAccountDAO;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Phase D.1.b (DR-015): unit tests for {@link PasswordRehashService}.
 *
 * <p>Pins:
 * <ul>
 *   <li>Legacy hash → bcrypt rehash + persist. The institutional
 *       success path that makes D.1's migration converge — without
 *       this rehash, legacy hashes never rotate.</li>
 *   <li>bcrypt hash → no-op. Without this skip, every login of a
 *       bcrypt-rehashed user would re-bcrypt and re-write, multiplying
 *       cost-10 latency and DB writes by N.</li>
 *   <li>Null user / null password / null hash / id=0 (the
 *       UserAccountDAO.findByUserName "no such user" sentinel) →
 *       defensive no-op, no DB write.</li>
 *   <li>DAO exception during rehash → swallowed (LOG only). The user
 *       already authenticated successfully against the legacy hash;
 *       a rehash failure must NOT fail the login.</li>
 * </ul>
 */
@RunWith(MockitoJUnitRunner.class)
public class PasswordRehashServiceTest {

    private static final String RAW_PASSWORD = "muw-d1b-rehash-pin";
    private static final String LEGACY_HASH = "{deadbeef}abcdef0123456789";
    private static final String BCRYPT_HASH = "{bcrypt}$2a$10$abcd...";

    @Mock
    private PasswordEncoder encoder;
    @Mock
    private UserAccountDAO userAccountDao;

    private PasswordRehashService rehashService;

    @Before
    public void setUp() {
        rehashService = new PasswordRehashService(encoder, userAccountDao);
    }

    /**
     * The institutional success path: a legacy MD5/SHA-1 hash signals
     * upgrade-needed → bcrypt rehash → persist via DAO. The in-memory
     * bean is updated too so any downstream consumer in the auth flow
     * sees the new hash.
     */
    @Test
    public void rehashesLegacyHashAndPersists() {
        UserAccountBean user = userWith(42, LEGACY_HASH);
        when(encoder.upgradeEncoding(LEGACY_HASH)).thenReturn(true);
        when(encoder.encode(RAW_PASSWORD)).thenReturn(BCRYPT_HASH);

        rehashService.rehashAfterSuccessfulLogin(user, RAW_PASSWORD);

        verify(encoder, times(1)).upgradeEncoding(LEGACY_HASH);
        verify(encoder, times(1)).encode(RAW_PASSWORD);
        verify(userAccountDao, times(1)).updatePasswordHash(42, BCRYPT_HASH);
        org.junit.Assert.assertEquals("in-memory bean reflects new hash",
                BCRYPT_HASH, user.getPasswd());
    }

    /**
     * Already-bcrypt hash: upgradeEncoding returns false → no encode,
     * no DAO write. Pins that bcrypt users don't pay the rehash cost
     * on every login.
     */
    @Test
    public void noOpForBcryptHash() {
        UserAccountBean user = userWith(42, BCRYPT_HASH);
        when(encoder.upgradeEncoding(BCRYPT_HASH)).thenReturn(false);

        rehashService.rehashAfterSuccessfulLogin(user, RAW_PASSWORD);

        verify(encoder, times(1)).upgradeEncoding(BCRYPT_HASH);
        verify(encoder, never()).encode(anyString());
        verify(userAccountDao, never()).updatePasswordHash(anyInt(), anyString());
        org.junit.Assert.assertEquals("bcrypt hash unchanged",
                BCRYPT_HASH, user.getPasswd());
    }

    /**
     * Null user → defensive no-op. The auth filter calls this method
     * only after a successful auth, but the defensive branch protects
     * against future callers that may pass nulls.
     */
    @Test
    public void noOpForNullUser() {
        rehashService.rehashAfterSuccessfulLogin(null, RAW_PASSWORD);

        verifyZeroInteractions(encoder);
        verifyZeroInteractions(userAccountDao);
    }

    /**
     * Sentinel user (id=0): {@code UserAccountDAO.findByUserName}
     * returns a fresh bean with id=0 when no row matches the username
     * — the upstream "no such user" marker. Rehash service must not
     * write to user_id=0 (which would be either a NPE or worse).
     */
    @Test
    public void noOpForSentinelUserId() {
        UserAccountBean sentinel = userWith(0, LEGACY_HASH);

        rehashService.rehashAfterSuccessfulLogin(sentinel, RAW_PASSWORD);

        verifyZeroInteractions(encoder);
        verifyZeroInteractions(userAccountDao);
    }

    /**
     * Null or empty plaintext → no-op. The auth filter only calls this
     * after credential parsing succeeded; the branch defends future
     * callers.
     */
    @Test
    public void noOpForNullRawPassword() {
        UserAccountBean user = userWith(42, LEGACY_HASH);

        rehashService.rehashAfterSuccessfulLogin(user, null);
        rehashService.rehashAfterSuccessfulLogin(user, "");

        verifyZeroInteractions(encoder);
        verifyZeroInteractions(userAccountDao);
    }

    /**
     * Null stored hash on the user bean → no-op (defensive; would mean
     * a misconfigured row).
     */
    @Test
    public void noOpForNullStoredHash() {
        UserAccountBean user = userWith(42, null);

        rehashService.rehashAfterSuccessfulLogin(user, RAW_PASSWORD);

        verifyZeroInteractions(encoder);
        verifyZeroInteractions(userAccountDao);
    }

    /**
     * DAO exception during the rehash UPDATE must NOT propagate — the
     * user authenticated successfully against the legacy hash, the
     * rehash is best-effort. Next login retries.
     */
    @Test
    public void daoFailureSwallowed() {
        UserAccountBean user = userWith(42, LEGACY_HASH);
        when(encoder.upgradeEncoding(LEGACY_HASH)).thenReturn(true);
        when(encoder.encode(RAW_PASSWORD)).thenReturn(BCRYPT_HASH);
        org.mockito.Mockito.doThrow(new RuntimeException("DB down"))
                .when(userAccountDao).updatePasswordHash(42, BCRYPT_HASH);

        // Must not throw — the test passes if rehashAfterSuccessfulLogin
        // returns normally despite the DAO exception.
        rehashService.rehashAfterSuccessfulLogin(user, RAW_PASSWORD);

        verify(userAccountDao, times(1)).updatePasswordHash(42, BCRYPT_HASH);
        // In-memory bean still reflects the legacy hash because the
        // commit didn't land; next login will retry the rehash.
        org.junit.Assert.assertEquals("legacy hash retained on DAO failure",
                LEGACY_HASH, user.getPasswd());
    }

    // -------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------

    private static UserAccountBean userWith(int id, String passwd) {
        UserAccountBean user = new UserAccountBean();
        user.setId(id);
        user.setPasswd(passwd);
        return user;
    }
}
