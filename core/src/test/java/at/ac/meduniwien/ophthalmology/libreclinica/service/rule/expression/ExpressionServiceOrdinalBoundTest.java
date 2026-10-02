/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.rule.expression;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import org.junit.Test;

import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.expression.ExpressionObjectWrapper;

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
}
