/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.control.submit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.invocation.Invocation;
import org.slf4j.Logger;

/**
 * The substitution context holds the subject label and every item value of
 * the event CRF; at DEBUG (the shipped level) only the token names are
 * logged.
 */
class VariableSubstitutionHelperLoggingTest {

    @Test
    void onlyTokenNamesReachTheLog() {
        Map<String, String> tokens = new LinkedHashMap<>();
        tokens.put("studySubject", "SUBJECT-LABEL-3a9f");
        tokens.put("item['DOB']", "1961-04-12-VALUE-3a9f");
        tokens.put("item['VA_OD']", "0.8-VALUE-3a9f");

        Logger log = Mockito.mock(Logger.class);
        Mockito.when(log.isDebugEnabled()).thenReturn(true);

        VariableSubstitutionHelper.logSubstitutionContext(log, tokens);

        StringBuilder written = new StringBuilder();
        for (Invocation call : Mockito.mockingDetails(log).getInvocations()) {
            for (Object arg : call.getArguments()) {
                written.append(arg).append('\n');
            }
        }
        assertFalse(written.toString().contains("3a9f"), written.toString());
        assertTrue(written.toString().contains("item['VA_OD']"), written.toString());
    }
}
