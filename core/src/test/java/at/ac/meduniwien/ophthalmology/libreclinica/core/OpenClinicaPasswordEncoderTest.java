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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;
import org.springframework.security.crypto.password.MessageDigestPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Pin the delegating behaviour of {@link OpenClinicaPasswordEncoder}: it matches
 * passwords hashed by the configured {@code currentPasswordEncoder} (SHA-1 in
 * the wired config, see
 * {@code applicationContext-core-security.xml}) AND it matches passwords hashed
 * by the {@code oldPasswordEncoder} (MD5) so legacy users are not locked out.
 *
 * <p>Phase 0 backlog item #3 ({@code LoginFlowIT.passwordEncoderRecognisesLegacyMd5}
 * in <a href="../../../../../../MIGRATION.md">MIGRATION.md</a>), kept as a pure
 * unit test rather than an integration test because nothing here touches the DB.
 *
 * <p><strong>Phase B.4 gate.</strong> Spring Security 6 removes
 * {@link MessageDigestPasswordEncoder} entirely. When that migration lands, the
 * Spring config will need to switch to a {@code DelegatingPasswordEncoder} with
 * a noop / legacy adapter to keep MD5- and SHA-1-hashed credentials authenticating
 * until users re-login and upgrade. This test must continue to pass against
 * that new implementation - if it stops passing, every existing institutional
 * user is locked out at first sign-in after the upgrade.
 */
public class OpenClinicaPasswordEncoderTest {

    /** Same algorithm as the {@code shaPasswordEncoder} bean. */
    private MessageDigestPasswordEncoder sha1Encoder;
    /** Same algorithm as the {@code md5PasswordEncoder} bean. */
    private MessageDigestPasswordEncoder md5Encoder;
    /** Wires the two together exactly the way the Spring config does. */
    private OpenClinicaPasswordEncoder encoder;

    @Before
    @SuppressWarnings("deprecation") // MessageDigestPasswordEncoder is deprecated; replacement is Phase B.4.
    public void setUp() {
        sha1Encoder = new MessageDigestPasswordEncoder("SHA-1");
        md5Encoder = new MessageDigestPasswordEncoder("MD5");
        encoder = new OpenClinicaPasswordEncoder();
        encoder.setCurrentPasswordEncoder(sha1Encoder);
        encoder.setOldPasswordEncoder(md5Encoder);
    }

    /**
     * Round-trip on the current encoder: a freshly-hashed SHA-1 credential
     * matches against the same raw password.
     */
    @Test
    public void encodeProducesShaHashThatMatchesItself() {
        String rawPassword = "muw-secret-2026";

        String encoded = encoder.encode(rawPassword);

        assertNotNull("encode() must not return null", encoded);
        assertNotEquals("encoded form must not be the raw password",
                rawPassword, encoded);
        assertTrue("matches() must accept its own SHA-1-encoded form",
                encoder.matches(rawPassword, encoded));
    }

    /**
     * Two independent encodings of the same raw password produce different
     * hashes (Spring's {@link MessageDigestPasswordEncoder} prefixes a random
     * salt, format {@code {salt}hex}), but both verify back to the raw password
     * via {@link OpenClinicaPasswordEncoder#matches(CharSequence, String)}.
     *
     * <p>This pin guards the format: any encoder swap that drops the {@code {salt}}
     * prefix would silently break existing rows of {@code audit_user_login} that
     * carry hashes in this shape.
     */
    @Test
    public void twoEncodingsBothMatchSameRawPassword() {
        String rawPassword = "muw-secret-2026";

        String first = encoder.encode(rawPassword);
        String second = encoder.encode(rawPassword);

        assertNotEquals("MessageDigestPasswordEncoder salts each call, so two"
                + " encodings of the same password must NOT be byte-equal; if"
                + " this assertion fails, the encoder bean has been swapped"
                + " for a salt-free implementation and the Phase B.4 migration"
                + " plan needs to address downstream consumers that compare"
                + " hashes directly.",
                first, second);
        assertTrue("first encoding must match the raw password",
                encoder.matches(rawPassword, first));
        assertTrue("second encoding must match the raw password",
                encoder.matches(rawPassword, second));
    }

    /**
     * The institutional regression test for Phase B.4. Simulates an existing
     * user whose credential was hashed with the legacy MD5 encoder before the
     * SHA-1 bump. The matches() call must succeed via the oldPasswordEncoder
     * fallback path, not the currentPasswordEncoder.
     */
    @Test
    public void matchesLegacyMd5HashViaFallbackPath() {
        String rawPassword = "muw-secret-2026";
        String legacyMd5Hash = md5Encoder.encode(rawPassword);

        assertTrue("OpenClinicaPasswordEncoder.matches() must recognise an"
                + " MD5-hashed credential via the oldPasswordEncoder fallback;"
                + " a regression here locks every existing institutional user"
                + " out of the next sign-in.",
                encoder.matches(rawPassword, legacyMd5Hash));
    }

    /**
     * Symmetry check: the SHA-1 path doesn't accept MD5-of-a-different-password,
     * and the MD5 fallback doesn't accept SHA-1-of-a-different-password.
     */
    @Test
    public void doesNotMatchWrongPassword() {
        String rawPassword = "muw-secret-2026";
        String wrongPassword = "wrong-password";

        String sha1Hash = sha1Encoder.encode(rawPassword);
        String md5Hash = md5Encoder.encode(rawPassword);

        assertFalse("must reject wrong password against SHA-1 hash",
                encoder.matches(wrongPassword, sha1Hash));
        assertFalse("must reject wrong password against MD5 hash",
                encoder.matches(wrongPassword, md5Hash));
    }
}
