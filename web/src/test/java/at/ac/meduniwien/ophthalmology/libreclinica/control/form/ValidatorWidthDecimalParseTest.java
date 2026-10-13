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

import org.junit.jupiter.api.Test;

/** A malformed width/decimal descriptor puts no constraint on the field instead of throwing. */
class ValidatorWidthDecimalParseTest {

    @Test
    void wellFormedDescriptors() {
        assertEquals(5, Validator.parseWidth("5"));
        assertEquals(5, Validator.parseWidth("5(2)"));
        assertEquals(0, Validator.parseWidth("w(d)"));
        assertEquals(2, Validator.parseDecimal("5(2)"));
        assertEquals(2, Validator.parseDecimal("(2)"));
        assertEquals(0, Validator.parseDecimal("5"));
        assertEquals(0, Validator.parseDecimal("5(d)"));
    }

    @Test
    void malformedWidthIsIgnored() {
        assertEquals(0, Validator.parseWidth("abc"));
        assertEquals(0, Validator.parseWidth("x1(2)"));
    }

    @Test
    void malformedDecimalIsIgnored() {
        assertEquals(0, Validator.parseDecimal("5(zz)"));
        assertEquals(0, Validator.parseDecimal("(zz)"));
    }
}
