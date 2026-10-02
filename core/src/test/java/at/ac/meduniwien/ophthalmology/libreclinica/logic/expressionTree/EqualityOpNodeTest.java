/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.logic.expressionTree;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * {@code eq} and {@code ne} compare numbers exactly.
 *
 * <p>The operands were compared as {@link Float}, which holds about seven
 * significant digits, so two different large integers or long numeric codes
 * compared equal, and an edit check on them gave the wrong answer. Both the
 * rule test and the evaluation paths go through the same comparison.
 */
public class EqualityOpNodeTest {

    private static String test(String expression) {
        return new OpenClinicaExpressionParser().parseAndTestEvaluateExpression(expression);
    }

    private static String evaluate(String expression) {
        return (String) new OpenClinicaExpressionParser().parseAndEvaluateExpression(expression);
    }

    @Test
    public void largeIntegersThatDifferAreNotEqual() {
        assertEquals("false", test("123456789 eq 123456790"));
        assertEquals("true", test("123456789 ne 123456790"));
        assertEquals("false", evaluate("123456789 eq 123456790"));
        assertEquals("false", evaluate("20260930120001 eq 20260930120002"));
    }

    @Test
    public void decimalsThatDifferBeyondFloatPrecisionAreNotEqual() {
        assertEquals("false", test("0.1 eq 0.10000000149"));
    }

    @Test
    public void theSameValueWrittenDifferentlyIsEqual() {
        assertEquals("true", test("1.0 eq 1"));
        assertEquals("true", test("1.10 eq 1.1"));
        assertEquals("true", test("123456789 eq 123456789"));
        assertEquals("false", test("1.0 ne 1"));
    }

    @Test
    public void textStillComparesAsText() {
        assertEquals("true", test("\"abc\" eq \"abc\""));
        assertEquals("false", test("\"abc\" eq \"abd\""));
        assertEquals("false", test("5 eq \"abc\""));
    }
}
