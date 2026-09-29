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

import org.junit.jupiter.api.Test;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.Errors;

/**
 * An empty value is skipped, not validated (2026-09-29).
 *
 * <p>subValidator guarded with {@code value != ""}, which compares references.
 * A value parsed out of a participant's submission is not the interned literal,
 * so the guard was true for an empty value as well and the empty value reached
 * the type switch: {@code Integer.valueOf("")} then rejected a blank field as
 * invalid, which is the opposite of what the guard was written to do.
 */
class PformValidatorEmptyValueTest {

    private static final int INTEGER = 6;
    private static final int REAL = 7;

    private Errors errorsFor(int dataTypeId, String value) {
        Errors e = new BeanPropertyBindingResult(new Object(), "item");
        new PformValidator().subValidator(dataTypeId, value, e);
        return e;
    }

    @Test
    void anEmptyIntegerIsSkippedRatherThanRejected() {
        // new String("") is a distinct instance, never the interned literal --
        // which is what a value parsed out of a submission may also be. A
        // StringBuilder would NOT do: it hands back the interned "" on this JDK,
        // and the test would pass against the very bug it is meant to catch.
        String empty = new String("");
        assertEquals(0, errorsFor(INTEGER, empty).getErrorCount(),
                "a blank integer field must be skipped, not reported invalid");
    }

    @Test
    void anEmptyRealIsSkippedRatherThanRejected() {
        String empty = new String("");
        assertEquals(0, errorsFor(REAL, empty).getErrorCount(),
                "a blank real field must be skipped, not reported invalid");
    }

    @Test
    void aNullValueIsStillSkipped() {
        assertEquals(0, errorsFor(INTEGER, null).getErrorCount());
    }

    @Test
    void aNonNumericIntegerIsStillRejected() {
        assertEquals(1, errorsFor(INTEGER, "not-a-number").getErrorCount(),
                "the guard must not stop real validation from running");
    }
}
