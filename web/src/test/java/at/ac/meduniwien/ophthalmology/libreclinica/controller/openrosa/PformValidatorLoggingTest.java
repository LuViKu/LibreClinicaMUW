/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.openrosa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;

import java.util.HashMap;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.invocation.Invocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.validation.Errors;
import org.springframework.validation.MapBindingResult;

/**
 * A rejected OpenRosa value is participant-entered CRF data; the validator
 * logs why it was rejected, never the value itself.
 */
class PformValidatorLoggingTest {

    private static final String MARKER = "PARTICIPANT-VALUE-7c41";

    @Test
    void rejectedValuesAreNotWrittenToTheLog() {
        new PformValidator(); // load the class outside the static mock
        Logger log = Mockito.mock(Logger.class);
        Mockito.when(log.isInfoEnabled()).thenReturn(true);
        PformValidator validator;
        try (MockedStatic<LoggerFactory> factory =
                     Mockito.mockStatic(LoggerFactory.class, Mockito.CALLS_REAL_METHODS)) {
            factory.when(() -> LoggerFactory.getLogger(eq(PformValidator.class.getName()))).thenReturn(log);
            validator = new PformValidator();
        }

        Errors errors = new MapBindingResult(new HashMap<>(), "item");
        validator.subValidator(5, MARKER + "x".repeat(4000), errors);
        validator.subValidator(6, MARKER, errors);
        validator.subValidator(7, MARKER, errors);
        validator.subValidator(9, MARKER, errors);
        validator.subValidator(10, MARKER, errors);
        assertEquals(5, errors.getErrorCount());

        int logged = 0;
        for (Invocation call : Mockito.mockingDetails(log).getInvocations()) {
            for (Object arg : call.getArguments()) {
                assertFalse(String.valueOf(arg).contains(MARKER), call.toString());
            }
            if (call.getMethod().getName().equals("info")) {
                logged++;
            }
        }
        assertTrue(logged >= 5, "each rejection is still logged");
    }
}
