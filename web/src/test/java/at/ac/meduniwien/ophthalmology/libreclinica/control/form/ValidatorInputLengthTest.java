/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.control.form;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;

import at.ac.meduniwien.ophthalmology.libreclinica.i18n.util.ResourceBundleProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * The e-mail rule runs on the unauthenticated Contact, RequestPassword and
 * RequestAccount forms, and its pattern backtracks polynomially, so the
 * value's length is checked before the pattern. Every regular-expression
 * validation is also refused for values longer than any column a
 * regex-validated field is stored in.
 */
class ValidatorInputLengthTest {

    /** RFC 5321 path limit minus the angle brackets. */
    private static final int MAX_EMAIL_LENGTH = 254;

    /** Width of item_data.value, the widest regex-validated column. */
    private static final int MAX_REGEX_INPUT_LENGTH = 4000;

    @BeforeEach
    void bindLocale() {
        ResourceBundleProvider.updateLocale(Locale.ENGLISH);
    }

    private static HashMap<String, ArrayList<String>> validateEmail(String email) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addParameter("email", email);
        Validator v = new Validator(request);
        v.addValidation("email", Validator.IS_A_EMAIL);
        return v.validate();
    }

    private static HashMap<String, ArrayList<String>> validateRegex(String value, String regexClause) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addParameter("item", value);
        Validator v = new Validator(request);
        v.addValidation("item", Validator.processCRFValidationRegex(regexClause));
        return v.validate();
    }

    @Test
    void acceptsTheAddressesTheExistingRuleAccepts() {
        for (String ok : new String[] {
                "user@example.org",
                "first.last@sub.example.co.at",
                "x+tag@host.domain",
                "a@b.",
                "with space@host.org" }) {
            assertFalse(validateEmail(ok).containsKey("email"), ok);
        }
    }

    @Test
    void stillRejectsWhatTheExistingRuleRejects() {
        for (String bad : new String[] { "", "no-at-sign.org", "user@nodot", "@." }) {
            assertTrue(validateEmail(bad).containsKey("email"), bad);
        }
    }

    @Test
    void acceptsAnAddressOfExactlyTheMaximumLength() {
        String domain = "@example.org";
        String email = "a".repeat(MAX_EMAIL_LENGTH - domain.length()) + domain;
        assertEquals(MAX_EMAIL_LENGTH, email.length());
        assertFalse(validateEmail(email).containsKey("email"));
    }

    @Test
    void rejectsAnAddressOneCharacterOverTheMaximum() {
        String domain = "@example.org";
        String email = "a".repeat(MAX_EMAIL_LENGTH + 1 - domain.length()) + domain;
        assertTrue(validateEmail(email).containsKey("email"));
    }

    @Test
    void longHostileInputIsRejectedWithoutRunningThePattern() {
        // ~150 KB of "a@": the unbounded pattern needs tens of seconds here.
        String hostile = "a@".repeat(75_000);
        HashMap<String, ArrayList<String>> errors =
                assertTimeoutPreemptively(Duration.ofSeconds(2), () -> validateEmail(hostile));
        assertTrue(errors.containsKey("email"));
    }

    @Test
    void regexValidationStillRunsUpToTheColumnWidth() throws Exception {
        String value = "x".repeat(MAX_REGEX_INPUT_LENGTH);
        assertFalse(validateRegex(value, "regexp: /x*/").containsKey("item"));
        assertTrue(validateRegex("y", "regexp: /x*/").containsKey("item"));
    }

    @Test
    void regexValidationRefusesValuesLongerThanTheColumnWidth() throws Exception {
        String value = "x".repeat(MAX_REGEX_INPUT_LENGTH + 1);
        assertTrue(validateRegex(value, "regexp: /x*/").containsKey("item"));
    }
}
