/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.control.form;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;

import java.util.Locale;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.NumericComparisonOperator;
import at.ac.meduniwien.ophthalmology.libreclinica.i18n.util.ResourceBundleProvider;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.invocation.Invocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Validated fields include passwords and CRF item values. The validator's
 * DEBUG trace (DEBUG is the shipped level) names the field and the outcome,
 * not the value.
 */
class ValidatorValueLoggingTest {

    private static final String MARKER = "FIELD-VALUE-e81b";

    @Test
    void validatedValuesAreNotWrittenToTheLog() {
        ResourceBundleProvider.updateLocale(Locale.ENGLISH);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addParameter("passwd", MARKER);
        request.addParameter("item", MARKER + "-not-a-number");
        new Validator(request); // load the class outside the static mock

        Logger log = Mockito.mock(Logger.class);
        Mockito.when(log.isDebugEnabled()).thenReturn(true);
        Validator v;
        try (MockedStatic<LoggerFactory> factory =
                     Mockito.mockStatic(LoggerFactory.class, Mockito.CALLS_REAL_METHODS)) {
            factory.when(() -> LoggerFactory.getLogger(eq(Validator.class.getName()))).thenReturn(log);
            v = new Validator(request);
        }
        v.addValidation("passwd", Validator.LENGTH_NUMERIC_COMPARISON,
                NumericComparisonOperator.GREATER_THAN_OR_EQUAL_TO, 8);
        v.addValidation("item", Validator.IS_A_NUMBER);
        assertTrue(v.validate().containsKey("item"));

        int debugCalls = 0;
        for (Invocation call : Mockito.mockingDetails(log).getInvocations()) {
            for (Object arg : call.getArguments()) {
                assertFalse(String.valueOf(arg).contains(MARKER), call.toString());
            }
            if (call.getMethod().getName().equals("debug")) {
                debugCalls++;
            }
        }
        assertTrue(debugCalls > 0, "the validation trace is still written");
    }
}
