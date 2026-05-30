/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.core;

import org.springframework.security.crypto.password.MessageDigestPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Read-only encoder for legacy MD5/SHA-1 password hashes stored in
 * {@code user_account.passwd} from pre-D.1 LibreClinica/OpenClinica
 * deployments.
 *
 * <p>Phase D.1 (DR-015): wired as the fallback encoder of the
 * {@code passwordEncoder} {@link org.springframework.security.crypto.password.DelegatingPasswordEncoder}
 * bean. Matches legacy {@code {salt}<hex>} rows produced by
 * {@link MessageDigestPasswordEncoder} on the upstream stack
 * (MD5 = 32 hex chars; SHA-1 = 40 hex chars).
 *
 * <p>{@link #encode(CharSequence)} throws — D.1 forbids writing new
 * legacy hashes. All new writes go through {@code DelegatingPasswordEncoder}'s
 * {@code bcrypt} mapping. Lazy bcrypt rehash on successful legacy match
 * is implemented by {@code PasswordRehashService}, which observes
 * {@link #upgradeEncoding(String)} returning true and persists a fresh
 * {@code {bcrypt}…} hash.
 *
 * <p><strong>Security note.</strong> MD5 and SHA-1 are cryptographically
 * broken for password storage. This encoder exists only to verify
 * existing rows so users can authenticate once after the migration —
 * each successful match triggers a rehash to bcrypt, so the population
 * of legacy hashes drains over time. A 90-day post-D.1 audit identifies
 * still-on-legacy accounts (cold/inactive); admin force-reset reseeds
 * those with bcrypt.
 */
public class LegacyMd5Sha1PasswordEncoder implements PasswordEncoder {

    @SuppressWarnings("deprecation")
    private final MessageDigestPasswordEncoder md5 = new MessageDigestPasswordEncoder("MD5");
    @SuppressWarnings("deprecation")
    private final MessageDigestPasswordEncoder sha1 = new MessageDigestPasswordEncoder("SHA-1");

    /**
     * @throws UnsupportedOperationException always. New password writes
     *     must go through the bcrypt branch of the
     *     {@code DelegatingPasswordEncoder}.
     */
    @Override
    public String encode(CharSequence rawPassword) {
        throw new UnsupportedOperationException(
                "LegacyMd5Sha1PasswordEncoder is read-only;"
                + " new hashes must use bcrypt via DelegatingPasswordEncoder.");
    }

    /**
     * Verifies a raw password against a legacy {@code {salt}<hex>} hash.
     * Tries MD5 first, then SHA-1; either match returns true. Returns
     * false for null, empty, or otherwise unrecognised encoded forms.
     *
     * <p>{@link MessageDigestPasswordEncoder#matches} parses the
     * {@code {salt}} prefix, recomputes the digest, and constant-time
     * compares — same behaviour as the upstream
     * {@code OpenClinicaPasswordEncoder.matches()} path that this
     * encoder replaces in D.1.
     */
    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        if (rawPassword == null || encodedPassword == null || encodedPassword.isEmpty()) {
            return false;
        }
        // The two MessageDigestPasswordEncoders share an extracted-salt
        // format; the digest-length mismatch on the wrong algorithm
        // causes matches() to return false rather than throw, so we
        // can simply OR the two paths.
        return md5.matches(rawPassword, encodedPassword)
                || sha1.matches(rawPassword, encodedPassword);
    }

    /**
     * Every successful legacy match must trigger a bcrypt rehash.
     * Returns {@code true} unconditionally so the
     * {@code DelegatingPasswordEncoder} signals upgrade-needed to the
     * caller; {@code PasswordRehashService} acts on the signal.
     */
    @Override
    public boolean upgradeEncoding(String encodedPassword) {
        return true;
    }
}
