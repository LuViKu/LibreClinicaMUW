/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.ingest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import org.junit.Test;

/**
 * DR-040 phase 2 — what an uploader token looks like and what is kept of it.
 *
 * <p>The contract (loc2-contract.md, "Concepts"): {@code mwu_} followed by 43
 * base64url characters, which are 32 random bytes; stored as the lowercase hex
 * SHA-256; the hint is the first eight characters after the prefix. Nothing but
 * the hash and the hint may ever be derived from a token.
 */
public class UploaderTokensTest {

    private static final Pattern SHAPE = Pattern.compile("mwu_[A-Za-z0-9_-]{43}");

    @Test
    public void aTokenIsThePrefixAnd43Base64urlCharactersOf32RandomBytes() {
        for (int i = 0; i < 50; i++) {
            String token = UploaderTokens.generate();
            assertTrue(token, SHAPE.matcher(token).matches());
            assertEquals(47, token.length());
            assertEquals(32, Base64.getUrlDecoder().decode(token.substring(4)).length);
            assertTrue(UploaderTokens.wellFormed(token));
        }
    }

    @Test
    public void tokensDoNotRepeatAndTheirBytesAreNotConstant() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 2000; i++) {
            assertTrue("a token repeated", seen.add(UploaderTokens.generate()));
        }
        // Not a statistical test; it only catches a generator that returns zeros or a counter.
        Set<Character> firstBodyChars = new HashSet<>();
        for (String t : seen) firstBodyChars.add(t.charAt(4));
        assertTrue("the first characters should vary: " + firstBodyChars, firstBodyChars.size() > 20);
    }

    @Test
    public void theHashIsTheLowercaseHexSha256OfTheWholeToken() {
        // sha256("mwu_" + 43 x 'A') computed independently: printf 'mwu_AAAA...' | shasum -a 256
        String token = "mwu_" + "A".repeat(43);
        String hash = UploaderTokens.hash(token);
        assertEquals(64, hash.length());
        assertTrue(hash, hash.matches("[0-9a-f]{64}"));
        assertEquals("known vector", sha256Hex(token), hash);
        assertEquals("deterministic", hash, UploaderTokens.hash(token));
        assertNotEquals("another token, another hash", hash, UploaderTokens.hash("mwu_" + "B".repeat(43)));
        assertFalse("the hash does not contain the token", hash.contains(token.substring(4, 12)));
    }

    @Test
    public void theHintIsTheFirstEightCharactersAfterThePrefix() {
        String token = "mwu_abcdefgh" + "X".repeat(35);
        assertEquals("abcdefgh", UploaderTokens.hint(token));
        String generated = UploaderTokens.generate();
        assertEquals(generated.substring(4, 12), UploaderTokens.hint(generated));
        assertEquals(8, UploaderTokens.hint(generated).length());
    }

    @Test
    public void onlyTheExactShapeIsWellFormed() {
        String good = "mwu_" + "a1_-".repeat(10) + "abc";
        assertEquals(47, good.length());
        assertTrue(UploaderTokens.wellFormed(good));

        assertFalse(UploaderTokens.wellFormed(null));
        assertFalse(UploaderTokens.wellFormed(""));
        assertFalse("no prefix", UploaderTokens.wellFormed("A".repeat(47)));
        assertFalse("upper-case prefix", UploaderTokens.wellFormed("MWU_" + "A".repeat(43)));
        assertFalse("too short", UploaderTokens.wellFormed("mwu_" + "A".repeat(42)));
        assertFalse("too long", UploaderTokens.wellFormed("mwu_" + "A".repeat(44)));
        assertFalse("padding", UploaderTokens.wellFormed("mwu_" + "A".repeat(42) + "="));
        assertFalse("plus", UploaderTokens.wellFormed("mwu_" + "A".repeat(42) + "+"));
        assertFalse("slash", UploaderTokens.wellFormed("mwu_" + "A".repeat(42) + "/"));
        assertFalse("space", UploaderTokens.wellFormed("mwu_" + "A".repeat(42) + " "));
        assertFalse("trailing newline", UploaderTokens.wellFormed("mwu_" + "A".repeat(43) + "\n"));
        assertFalse("not ASCII", UploaderTokens.wellFormed("mwu_" + "A".repeat(42) + "ä"));
        assertFalse("a header's worth of garbage", UploaderTokens.wellFormed("x".repeat(8192)));
    }

    private static String sha256Hex(String text) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }
}
