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
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.Before;
import org.junit.Test;

import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.StudyEventDao;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.expression.ExpressionBeanObjectWrapper;
import at.ac.meduniwien.ophthalmology.libreclinica.exception.OpenClinicaSystemException;

/**
 * getStudyEventFromOID resolves the destination of an EventAction, for example
 * {@code SE_VISIT[2]}. The EventAction validator also admitted {@code SE_VISIT[END]}
 * for a repeating event, which names no single occurrence. That has to fail the
 * rule with the rule engine exception, which BeanPropertyRuleRunner logs and
 * skips, and not with a NumberFormatException that escapes the runner and fails
 * the study-event update that triggered the rule.
 */
public class ExpressionBeanServiceEventOrdinalTest {

    private StudyEventDao studyEventDao;
    private ExpressionBeanService service;

    @Before
    public void setUp() {
        studyEventDao = mock(StudyEventDao.class);
        ExpressionBeanObjectWrapper wrapper = mock(ExpressionBeanObjectWrapper.class);
        when(wrapper.getStudySubjectBeanId()).thenReturn(7);
        when(wrapper.getStudyEventDaoHib()).thenReturn(studyEventDao);
        service = new ExpressionBeanService(wrapper);
    }

    @Test
    public void endOrdinal_failsTheRule_notTheCaller() {
        try {
            service.getStudyEventFromOID("SE_VISIT[END]");
            fail("expected OpenClinicaSystemException");
        } catch (OpenClinicaSystemException e) {
            assertEquals("OCRERR_0019", e.getErrorCode());
            assertEquals("SE_VISIT[END]", e.getErrorParams()[0]);
        }
        verifyNoInteractions(studyEventDao);
    }

    @Test
    public void numericOrdinal_isLookedUp() {
        service.getStudyEventFromOID("SE_VISIT[2]");
        verify(studyEventDao).fetchByStudyEventDefOIDAndOrdinal("SE_VISIT", 2, 7);
    }

    @Test
    public void noOrdinal_meansTheFirstOccurrence() {
        service.getStudyEventFromOID("SE_VISIT");
        verify(studyEventDao).fetchByStudyEventDefOIDAndOrdinal("SE_VISIT", 1, 7);
    }
}
