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
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import at.ac.meduniwien.ophthalmology.libreclinica.exception.OpenClinicaSystemException;

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
        assertEquals("true", test("5 ne \"abc\""));
    }

    /* The behaviour the rewrite keeps as it was: contains, the Float reading, the visit status. */

    private static String compare(Operator op, String left, String right) {
        return (String) new EqualityOpNode(op, new ConstantNode(left), new ConstantNode(right)).calculate();
    }

    @Test
    public void containsComparesText() {
        assertEquals("true", compare(Operator.CONTAINS, "abcdef", "cde"));
        assertEquals("false", compare(Operator.CONTAINS, "abcdef", "x"));
        assertEquals("true", compare(Operator.CONTAINS, "abc", ""));
    }

    @Test
    public void containsReadsTwoNumbersAsFloatsAsItAlwaysDid() {
        // 12345.0 does not contain 234.0; it is never a numeric comparison.
        assertEquals("false", compare(Operator.CONTAINS, "12345", "234"));
        assertEquals("true", compare(Operator.CONTAINS, "1.50", "1.5"));
    }

    @Test
    public void whatOnlyFloatReadsAsANumberStillComparesAsFloat() {
        assertEquals("true", compare(Operator.EQUAL, "1f", "1"));
        assertEquals("true", compare(Operator.EQUAL, "2d", "2.0"));
        assertEquals("true", compare(Operator.EQUAL, "NaN", "NaN"));
    }

    @Test
    public void emptyAndMissingOperandsCompareAsText() {
        assertEquals("true", compare(Operator.EQUAL, "", ""));
        assertEquals("false", compare(Operator.EQUAL, "", "0"));
        assertEquals("true", compare(Operator.NOT_EQUAL, null, "1"));
        assertEquals("true", compare(Operator.EQUAL, null, null));
    }

    @Test
    public void aVisitStatusIsComparedWithTheNamesOfTheStatuses() {
        EqualityOpNode known = new EqualityOpNode(Operator.EQUAL,
                new ConstantNode("SE_V1.STATUS"), new ConstantNode("completed"));
        assertEquals("false", known.testCalculate());

        EqualityOpNode unknown = new EqualityOpNode(Operator.EQUAL,
                new ConstantNode("SE_V1.STATUS"), new ConstantNode("3"));
        OpenClinicaSystemException refused = assertThrows(OpenClinicaSystemException.class, unknown::testCalculate);
        assertEquals("OCRERR_0038", refused.getErrorCode());
        assertEquals("the status as written", "3", refused.getErrorParams()[0]);
    }
}
