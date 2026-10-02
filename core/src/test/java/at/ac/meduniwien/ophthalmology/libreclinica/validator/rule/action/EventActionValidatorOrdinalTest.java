/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.validator.rule.action;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Locale;
import java.util.ResourceBundle;

import javax.sql.DataSource;

import org.junit.Before;
import org.junit.Test;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyEventDefinitionBean;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.AuditableBeanWrapper;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.RuleSetBean;
import at.ac.meduniwien.ophthalmology.libreclinica.service.rule.expression.ExpressionService;

/**
 * The EventAction OID names the event occurrence a rule schedules or updates.
 * The runtime resolves only a numeric ordinal, so the importer has to reject
 * {@code [END]} the way it already rejects {@code [ALL]}, instead of storing a
 * rule that can never run.
 */
public class EventActionValidatorOrdinalTest {

    private AuditableBeanWrapper<RuleSetBean> wrapper;
    private EventActionValidator validator;

    @Before
    public void setUp() {
        StudyEventDefinitionBean repeating = new StudyEventDefinitionBean();
        repeating.setOid("SE_VISIT");
        repeating.setRepeating(true);
        ExpressionService expressionService = mock(ExpressionService.class);
        when(expressionService.getStudyEventDefinitionFromExpressionForEventScheduling("SE_VISIT[END]", true))
                .thenReturn(repeating);
        when(expressionService.getStudyEventDefinitionFromExpressionForEventScheduling("SE_VISIT[2]", true))
                .thenReturn(repeating);
        StudyEventDefinitionBean once = new StudyEventDefinitionBean();
        once.setOid("SE_ONCE");
        once.setRepeating(false);
        when(expressionService.getStudyEventDefinitionFromExpressionForEventScheduling("SE_ONCE[999999999]", true))
                .thenReturn(once);

        wrapper = new AuditableBeanWrapper<>(new RuleSetBean());
        validator = new EventActionValidator(mock(DataSource.class));
        validator.setExpressionService(expressionService);
        validator.setRuleSetBeanWrapper(wrapper);
        validator.setRespage(ResourceBundle.getBundle(
                "at.ac.meduniwien.ophthalmology.libreclinica.i18n.page_messages", Locale.ENGLISH));
    }

    @Test
    public void endOrdinal_isRejected() {
        validator.validateOidInAction("SE_VISIT[END]", null);

        assertEquals(1, wrapper.getImportErrors().size());
        String error = wrapper.getImportErrors().get(0);
        assertTrue(error, error.startsWith("OCRERR_0041"));
    }

    @Test
    public void aNineDigitOrdinalOfAnEventThatDoesNotRepeat_isRejected() {
        validator.validateOidInAction("SE_ONCE[999999999]", null);

        assertEquals(1, wrapper.getImportErrors().size());
        String error = wrapper.getImportErrors().get(0);
        assertTrue(error, error.startsWith("OCRERR_0039"));
    }

    @Test
    public void numericOrdinalOfARepeatingEvent_isAccepted() {
        validator.validateOidInAction("SE_VISIT[2]", null);

        assertEquals(0, wrapper.getImportErrors().size());
    }
}
