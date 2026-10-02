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
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.expression.ExpressionObjectWrapper;
import at.ac.meduniwien.ophthalmology.libreclinica.exception.OpenClinicaSystemException;

/**
 * getStudyEventFromOID resolves the event of a {@code SE_x[n].STARTDATE} or
 * {@code .STATUS} rule variable. {@code [ALL]} and {@code [END]} name no single
 * occurrence; such a variable has to fail the rule the way an unknown OID does
 * (OCRERR_0019, which every rule runner catches), not with a NumberFormatException
 * that none of them catches.
 */
public class ExpressionServiceEventOrdinalTest {

    private StudyEventDao studyEventDao;
    private ExpressionService service;

    @Before
    public void setUp() {
        studyEventDao = mock(StudyEventDao.class);
        ExpressionObjectWrapper wrapper = mock(ExpressionObjectWrapper.class);
        when(wrapper.getStudySubjectId()).thenReturn(7);
        when(wrapper.getStudyEventDaoHib()).thenReturn(studyEventDao);
        service = new ExpressionService(wrapper);
    }

    @Test
    public void allOrdinal_failsTheRule_notTheCaller() {
        try {
            service.getStudyEventFromOID("SE_VISIT[ALL]");
            fail("expected OpenClinicaSystemException");
        } catch (OpenClinicaSystemException e) {
            assertEquals("OCRERR_0019", e.getErrorCode());
            assertEquals("SE_VISIT[ALL]", e.getErrorParams()[0]);
        }
        verifyNoInteractions(studyEventDao);
    }

    @Test
    public void numericOrdinal_isLookedUp() {
        service.getStudyEventFromOID("SE_VISIT[3]");
        verify(studyEventDao).fetchByStudyEventDefOIDAndOrdinalTransactional("SE_VISIT", 3, 7);
    }
}
