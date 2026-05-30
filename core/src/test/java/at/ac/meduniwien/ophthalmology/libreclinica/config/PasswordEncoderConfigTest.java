/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;
import org.springframework.security.crypto.password.MessageDigestPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Phase D.1 (DR-015): unit tests for the
 * {@link PasswordEncoderConfig} bean — the
 * {@code DelegatingPasswordEncoder} that routes by hash-format prefix.
 *
 * <p>Contract pinned:
 * <ul>
 *   <li>New encodes produce {@code {bcrypt}…} output.</li>
 *   <li>Legacy {@code {salt}<hex>} MD5 + SHA-1 hashes still match.</li>
 *   <li>Wrong password rejected on bcrypt + both legacy formats.</li>
 *   <li>{@code upgradeEncoding()} signals true for legacy hashes,
 *       false for bcrypt (so {@code PasswordRehashService} only rehashes
 *       once per user).</li>
 *   <li>Two encodes of the same plaintext produce different bcrypt
 *       hashes (bcrypt's per-call salt) but both verify.</li>
 * </ul>
 */
public class PasswordEncoderConfigTest {

    private static final String RAW_PASSWORD = "muw-d1-config-pin";

    private PasswordEncoder encoder;

    @SuppressWarnings("deprecation")
    private MessageDigestPasswordEncoder externalMd5;
    @SuppressWarnings("deprecation")
    private MessageDigestPasswordEncoder externalSha1;

    @Before
    @SuppressWarnings("deprecation")
    public void setUp() {
        encoder = new PasswordEncoderConfig().passwordEncoder();
        externalMd5 = new MessageDigestPasswordEncoder("MD5");
        externalSha1 = new MessageDigestPasswordEncoder("SHA-1");
    }

    /**
     * Every new encode carries the {@code {bcrypt}} prefix —
     * D.1's wire format. {@code PasswordRehashService} relies on the
     * prefix to detect that lazy rehash has completed for a user.
     */
    @Test
    public void encodeProducesBcryptPrefixedHash() {
        String encoded = encoder.encode(RAW_PASSWORD);

        assertNotNull(encoded);
        assertTrue("post-D.1 encode() must produce a {bcrypt}-prefixed"
                + " hash; got: " + encoded,
                encoded.startsWith("{bcrypt}"));
        // bcrypt internal format starts $2a$, $2b$, or $2y$.
        String bcryptBody = encoded.substring("{bcrypt}".length());
        assertTrue("bcrypt body must start with $2a$, $2b$, or $2y$;"
                + " got: " + bcryptBody,
                bcryptBody.startsWith("$2a$")
                        || bcryptBody.startsWith("$2b$")
                        || bcryptBody.startsWith("$2y$"));
    }

    /**
     * Round-trip through the delegating encoder: a freshly-encoded
     * plaintext matches against its own bcrypt hash.
     */
    @Test
    public void bcryptRoundTripsItsOwnHash() {
        String encoded = encoder.encode(RAW_PASSWORD);

        assertTrue("delegating encoder must match its own bcrypt output",
                encoder.matches(RAW_PASSWORD, encoded));
        assertFalse("must reject wrong password against bcrypt hash",
                encoder.matches("wrong-password", encoded));
    }

    /**
     * The institutional regression pin moved up from
     * {@code OpenClinicaPasswordEncoderTest}: an MD5-hashed credential
     * from the legacy DB must authenticate via the
     * {@code DelegatingPasswordEncoder}'s default-encoder-for-matches
     * fallback (the {@code LegacyMd5Sha1PasswordEncoder}).
     */
    @Test
    public void matchesLegacyMd5HashViaFallback() {
        String legacyMd5Hash = externalMd5.encode(RAW_PASSWORD);

        assertTrue("DelegatingPasswordEncoder must route unprefixed MD5"
                + " hashes through LegacyMd5Sha1PasswordEncoder. A"
                + " regression here locks every existing institutional"
                + " user out of the next sign-in after D.1 lands.",
                encoder.matches(RAW_PASSWORD, legacyMd5Hash));
    }

    /**
     * Same for SHA-1 — the post-bump format for users created after
     * the upstream shaPasswordEncoder rollout.
     */
    @Test
    public void matchesLegacySha1HashViaFallback() {
        String legacySha1Hash = externalSha1.encode(RAW_PASSWORD);

        assertTrue("DelegatingPasswordEncoder must route unprefixed"
                + " SHA-1 hashes through LegacyMd5Sha1PasswordEncoder",
                encoder.matches(RAW_PASSWORD, legacySha1Hash));
    }

    /**
     * Reject wrong password on every hash format. D.1 must not regress
     * the security property: wrong password → false, no exceptions.
     */
    @Test
    public void rejectsWrongPasswordOnAllFormats() {
        String md5Hash = externalMd5.encode(RAW_PASSWORD);
        String sha1Hash = externalSha1.encode(RAW_PASSWORD);
        String bcryptHash = encoder.encode(RAW_PASSWORD);

        String wrongPassword = "not-the-right-password";
        assertFalse("wrong password rejected on MD5 hash",
                encoder.matches(wrongPassword, md5Hash));
        assertFalse("wrong password rejected on SHA-1 hash",
                encoder.matches(wrongPassword, sha1Hash));
        assertFalse("wrong password rejected on bcrypt hash",
                encoder.matches(wrongPassword, bcryptHash));
    }

    /**
     * {@code upgradeEncoding()} signal drives the lazy rehash flow in
     * D.1.b. Pin that:
     * <ul>
     *   <li>Legacy hashes signal upgrade-needed = true (rehash to
     *       bcrypt on next login).</li>
     *   <li>bcrypt hashes signal upgrade-needed = false (already
     *       up-to-date; no rehash).</li>
     * </ul>
     * Without this distinction, every login would trigger a rehash
     * (wasted bcrypt work + DB writes).
     */
    @Test
    public void upgradeEncodingSignalsCorrectly() {
        String md5Hash = externalMd5.encode(RAW_PASSWORD);
        String sha1Hash = externalSha1.encode(RAW_PASSWORD);
        String bcryptHash = encoder.encode(RAW_PASSWORD);

        assertEquals("legacy MD5 → upgrade-needed=true",
                true, encoder.upgradeEncoding(md5Hash));
        assertEquals("legacy SHA-1 → upgrade-needed=true",
                true, encoder.upgradeEncoding(sha1Hash));
        assertEquals("bcrypt → upgrade-needed=false (no rehash)",
                false, encoder.upgradeEncoding(bcryptHash));
    }

    /**
     * bcrypt is per-call-salted: two encodes of the same plaintext
     * produce different ciphertexts. Both verify back to the plaintext.
     *
     * <p>This pin guards against accidentally swapping bcrypt for a
     * salt-free implementation — a regression would silently break
     * password-storage security expectations.
     */
    @Test
    public void twoBcryptEncodingsBothMatchSameRawPassword() {
        String first = encoder.encode(RAW_PASSWORD);
        String second = encoder.encode(RAW_PASSWORD);

        assertFalse("bcrypt's per-call salt must produce distinct hashes",
                first.equals(second));
        assertTrue("first encoding matches",
                encoder.matches(RAW_PASSWORD, first));
        assertTrue("second encoding matches",
                encoder.matches(RAW_PASSWORD, second));
    }
}
