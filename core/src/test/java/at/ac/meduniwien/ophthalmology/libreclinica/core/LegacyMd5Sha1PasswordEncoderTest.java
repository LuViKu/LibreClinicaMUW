/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Before;
import org.junit.Test;
import org.springframework.security.crypto.password.MessageDigestPasswordEncoder;

/**
 * Phase D.1 (DR-015): unit tests for
 * {@link LegacyMd5Sha1PasswordEncoder} — the read-only legacy hash
 * matcher wired as the {@code DelegatingPasswordEncoder} fallback for
 * unprefixed {@code user_account.passwd} rows.
 *
 * <p>Replaces the deleted {@code OpenClinicaPasswordEncoderTest}.
 * The institutional regression pin (MD5-hashed credentials authenticate
 * after the encoder swap) is preserved here; new bcrypt + delegating
 * cases live in {@code PasswordEncoderConfigTest}.
 */
public class LegacyMd5Sha1PasswordEncoderTest {

    private static final String RAW_PASSWORD = "muw-d1-legacy-pin";

    private LegacyMd5Sha1PasswordEncoder encoder;

    @SuppressWarnings("deprecation")
    private MessageDigestPasswordEncoder externalMd5;
    @SuppressWarnings("deprecation")
    private MessageDigestPasswordEncoder externalSha1;

    @Before
    @SuppressWarnings("deprecation")
    public void setUp() {
        encoder = new LegacyMd5Sha1PasswordEncoder();
        externalMd5 = new MessageDigestPasswordEncoder("MD5");
        externalSha1 = new MessageDigestPasswordEncoder("SHA-1");
    }

    /**
     * The institutional regression pin: an MD5-hashed credential from
     * the legacy DB must authenticate via this encoder's MD5 path.
     * A regression here locks every existing institutional user out of
     * the next sign-in.
     */
    @Test
    public void matchesLegacyMd5Hash() {
        String legacyMd5Hash = externalMd5.encode(RAW_PASSWORD);

        assertTrue("LegacyMd5Sha1PasswordEncoder must match an MD5-hashed"
                + " credential (the upstream OpenClinica DB row format)."
                + " A failure here locks every existing user out at"
                + " their next sign-in after D.1 lands.",
                encoder.matches(RAW_PASSWORD, legacyMd5Hash));
    }

    /**
     * SHA-1-hashed credential (the post-shaPasswordEncoder format for
     * users created after the upstream SHA-1 bump but before D.1)
     * must match.
     */
    @Test
    public void matchesLegacySha1Hash() {
        String legacySha1Hash = externalSha1.encode(RAW_PASSWORD);

        assertTrue("LegacyMd5Sha1PasswordEncoder must match a SHA-1-hashed"
                + " credential",
                encoder.matches(RAW_PASSWORD, legacySha1Hash));
    }

    /**
     * Wrong password rejected against both legacy formats — the
     * institutional security property that D.1 must NOT regress.
     */
    @Test
    public void rejectsWrongPasswordOnBothFormats() {
        String md5Hash = externalMd5.encode(RAW_PASSWORD);
        String sha1Hash = externalSha1.encode(RAW_PASSWORD);

        assertFalse("must reject wrong password against MD5 hash",
                encoder.matches("not-the-right-password", md5Hash));
        assertFalse("must reject wrong password against SHA-1 hash",
                encoder.matches("not-the-right-password", sha1Hash));
    }

    /**
     * Null and empty inputs return false rather than throwing — defensive
     * parsing for malformed DB rows.
     */
    @Test
    public void nullAndEmptyEncodedReturnFalse() {
        assertFalse("null encoded returns false",
                encoder.matches(RAW_PASSWORD, null));
        assertFalse("empty encoded returns false",
                encoder.matches(RAW_PASSWORD, ""));
        assertFalse("null raw returns false",
                encoder.matches(null, externalMd5.encode(RAW_PASSWORD)));
    }

    /**
     * {@link LegacyMd5Sha1PasswordEncoder#encode} must throw — D.1
     * forbids writing new legacy hashes. The {@code DelegatingPasswordEncoder}
     * routes every encode() through the bcrypt branch; this fallback
     * encoder is read-only.
     */
    @Test
    public void encodeAlwaysThrows() {
        try {
            encoder.encode(RAW_PASSWORD);
            fail("LegacyMd5Sha1PasswordEncoder.encode() must throw —"
                    + " new hashes must use bcrypt via DelegatingPasswordEncoder");
        } catch (UnsupportedOperationException expected) {
            assertTrue("error message mentions bcrypt",
                    expected.getMessage().toLowerCase().contains("bcrypt"));
        }
    }

    /**
     * Every successful legacy match must signal upgrade-needed to the
     * caller. This pins the contract {@code PasswordRehashService}
     * (D.1.b) depends on — without an upgrade signal, legacy hashes
     * never rotate to bcrypt and the migration never completes.
     */
    @Test
    public void upgradeEncodingAlwaysReturnsTrue() {
        String md5Hash = externalMd5.encode(RAW_PASSWORD);
        String sha1Hash = externalSha1.encode(RAW_PASSWORD);

        assertEquals("MD5 hash signals upgrade-needed",
                true, encoder.upgradeEncoding(md5Hash));
        assertEquals("SHA-1 hash signals upgrade-needed",
                true, encoder.upgradeEncoding(sha1Hash));
    }
}
