/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.logic.expressionTree;

import static org.junit.Assert.assertTrue;

import java.time.LocalDate;
import java.time.ZoneId;

import org.junit.Test;

import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.expression.ExpressionBeanObjectWrapper;

/**
 * {@code _CURRENT_DATE} in an event-action value expression is today's date in
 * the study subject's time zone, or in the server's when there is none.
 * ExpressionBeanService.getSSTimeZone() returns null for a wrapper without a
 * study subject, and the fallback called isEmpty() on that null before it
 * tested for it.
 */
public class OpenClinicaBeanVariableNodeTest {

    @Test
    public void currentDateWithoutAStudySubjectIsTodayInTheServerTimeZone() {
        ExpressionBeanObjectWrapper noSubject = new ExpressionBeanObjectWrapper(null, null, null);
        ZoneId server = ZoneId.systemDefault();
        String before = LocalDate.now(server).toString();

        Object value = new OpenClinicaExpressionParser(noSubject).parseAndEvaluateExpression("_CURRENT_DATE");

        // read the clock on both sides, so a run across midnight still passes
        String after = LocalDate.now(server).toString();
        assertTrue(String.valueOf(value), before.equals(value) || after.equals(value));
    }
}
