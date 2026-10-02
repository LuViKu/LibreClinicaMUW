/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.rule.expression;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import org.junit.Test;

import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.expression.ExpressionObjectWrapper;
import at.ac.meduniwien.ophthalmology.libreclinica.exception.OpenClinicaSystemException;

/**
 * An event or group ordinal in a rule expression is read as an int when the
 * rule runs. The syntax check therefore admits at most nine digits: a longer
 * ordinal is refused when the rule is written, instead of failing with
 * NumberFormatException each time the rule runs.
 */
public class ExpressionServiceOrdinalBoundTest {

    private final ExpressionService service = new ExpressionService(mock(ExpressionObjectWrapper.class));

    @Test
    public void anOrdinalOfUpToNineDigitsPasses() {
        assertTrue(service.checkSyntax("SE_VISIT[2].F_CRF.IG_ROWS[999999999].I_X"));
    }

    @Test
    public void aLongerOrdinalIsRefused() {
        assertFalse(service.checkSyntax("SE_VISIT[2].F_CRF.IG_ROWS[9999999999].I_X"));
        assertFalse(service.checkSyntax("SE_VISIT[99999999999].F_CRF.IG_ROWS[1].I_X"));
    }

    @Test
    public void aRuleExpressionTakesNineDigitsButNotTenNorAll() {
        assertTrue(service.checkRuleExpressionSyntax("SE_VISIT[999999999].F_CRF.IG_ROWS[999999999].I_X"));
        assertFalse(service.checkRuleExpressionSyntax("SE_VISIT[2].F_CRF.IG_ROWS[1000000000].I_X"));
        assertFalse(service.checkRuleExpressionSyntax("SE_VISIT[1000000000].F_CRF.IG_ROWS[1].I_X"));
        assertFalse(service.checkRuleExpressionSyntax("SE_VISIT[2].F_CRF.IG_ROWS[ALL].I_X"));
    }

    @Test
    public void anActionTargetTakesEndAndAllAndNineDigitsButNotTen() {
        assertTrue(service.checkInsertActionExpressionSyntax("SE_VISIT[2].F_CRF.IG_ROWS[END].I_X"));
        assertTrue(service.checkInsertActionExpressionSyntax("SE_VISIT[2].F_CRF.IG_ROWS[ALL].I_X"));
        assertTrue(service.checkInsertActionExpressionSyntax("SE_VISIT[ALL].F_CRF.IG_ROWS[999999999].I_X"));
        assertFalse(service.checkInsertActionExpressionSyntax("SE_VISIT[2].F_CRF.IG_ROWS[1000000000].I_X"));
        assertFalse(service.checkInsertActionExpressionSyntax("SE_VISIT[1000000000].F_CRF.IG_ROWS[1].I_X"));
    }

    @Test
    public void theEventOrdinalIsReadUpToNineDigits() {
        assertEquals("999999999",
                service.getStudyEventDefinitionOidOrdinalFromExpression("SE_VISIT[999999999].F_CRF.IG_ROWS.I_X"));
        assertEquals("", service.getStudyEventDefinitionOidOrdinalFromExpression("SE_VISIT.F_CRF.IG_ROWS.I_X"));
        OpenClinicaSystemException refused = assertThrows(OpenClinicaSystemException.class,
                () -> service.getStudyEventDefinitionOidOrdinalFromExpression("SE_VISIT[1000000000].F_CRF.IG_ROWS.I_X"));
        assertEquals("OCRERR_0019", refused.getErrorCode());
    }
}
