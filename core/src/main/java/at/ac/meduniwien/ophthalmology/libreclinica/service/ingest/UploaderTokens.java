/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.ingest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * DR-040 phase 2 — the shape of an uploader token, and the only code that
 * makes, checks or hashes one.
 *
 * <p>A token is {@code mwu_} followed by 43 base64url characters: 32 bytes from
 * a {@link SecureRandom}, encoded without padding. It identifies one uploader
 * (and through it one location) to the device endpoints. The platform shows it
 * once, when it is issued, and keeps only its {@linkplain #hash SHA-256}: a
 * stolen database row cannot be used as a token. The {@linkplain #hint hint}
 * (the first eight characters after the prefix) is what a person sees to tell
 * tokens apart; it is not enough to use one.
 *
 * <p>Nothing here logs. A token and its hash are credentials: neither goes
 * into a log line, an audit row or an exception message, by anyone, and this
 * class gives nobody a reason to ({@link #hash} and {@link #hint} are the only
 * things derived from a token).
 */
public final class UploaderTokens {

    /** What every token starts with, so a stray string is recognisable as one. */
    public static final String PREFIX = "mwu_";

    /** Random bytes in a token: 256 bits. */
    public static final int RANDOM_BYTES = 32;

    /** Characters after the prefix: 32 bytes in unpadded base64url. */
    public static final int BODY_LENGTH = 43;

    /** The whole token. */
    public static final int TOKEN_LENGTH = PREFIX.length() + BODY_LENGTH;

    /** Characters of the body kept as the hint. */
    public static final int HINT_LENGTH = 8;

    private static final Pattern FORMAT = Pattern.compile("mwu_[A-Za-z0-9_-]{" + BODY_LENGTH + "}");

    /** Thread-safe; seeds itself from the platform's strongest source. */
    private static final SecureRandom RANDOM = new SecureRandom();

    private UploaderTokens() {}

    /** A new token. Two calls never return the same one in practice (256 random bits). */
    public static String generate() {
        byte[] bytes = new byte[RANDOM_BYTES];
        RANDOM.nextBytes(bytes);
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * True when the string has the shape of a token. A cheap test to run before
     * anything touches the database: a header that is empty, too long or made of
     * other characters is refused without a lookup.
     */
    public static boolean wellFormed(String token) {
        return token != null && token.length() == TOKEN_LENGTH && FORMAT.matcher(token).matches();
    }

    /**
     * The lowercase hexadecimal SHA-256 of the token as it is sent (prefix
     * included, UTF-8), which is what {@code ingest_source.token_hash} holds.
     */
    public static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required of every Java platform.
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /** The first {@value #HINT_LENGTH} characters after the prefix; the caller passes a well-formed token. */
    public static String hint(String token) {
        return token.substring(PREFIX.length(), PREFIX.length() + HINT_LENGTH);
    }
}
