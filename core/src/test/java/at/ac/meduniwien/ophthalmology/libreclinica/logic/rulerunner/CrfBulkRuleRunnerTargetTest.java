/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.logic.rulerunner;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.junit.Test;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyEventBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyEventDefinitionBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudySubjectBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.CRFVersionBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.EventCRFBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.ItemBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.ItemDataBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.ItemGroupBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.RuleActionRunLogDao;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyEventDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudySubjectDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.CRFVersionDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.EventCRFDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.RuleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.RuleSetBean;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.RuleSetRuleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.action.DiscrepancyNoteActionBean;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.action.RuleActionBean;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.expression.ExpressionBean;
import at.ac.meduniwien.ophthalmology.libreclinica.service.rule.expression.ExpressionService;

/**
 * Each action of a bulk rule run sees the rule set pointed at the target it
 * was evaluated for, as in the other three runners.
 *
 * <p>{@code runRulesBulk} evaluates every target of a rule set first and runs
 * the actions afterwards. Without pointing the rule set back at each action's
 * target, every action ran against the last target evaluated: an insert went
 * into that target's group row or visit, and a message named it. Here a rule
 * set has two targets, on two subjects' visits; the e-mail contents, built
 * per action from the rule set just before the action runs, show which
 * target the action saw.
 */
public class CrfBulkRuleRunnerTargetTest {

    private static final String TARGET_A = "SE_VISIT[101].F_CRF.IG_GROUP.I_X";
    private static final String TARGET_B = "SE_VISIT[202].F_CRF.IG_GROUP.I_X";

    private final ExpressionService expressionService = mock(ExpressionService.class);
    private final StudyEventDAO studyEventDao = mock(StudyEventDAO.class);
    private final EventCRFDAO eventCrfDao = mock(EventCRFDAO.class);
    private final CRFVersionDAO crfVersionDao = mock(CRFVersionDAO.class);
    private final StudySubjectDAO studySubjectDao = mock(StudySubjectDAO.class);
    private final RuleActionRunLogDao runLogDao = mock(RuleActionRunLogDao.class);

    /** The target the rule set pointed at as each action ran, in order. */
    private final List<String> targetsSeenByActions = new ArrayList<>();

    private class Runner extends CrfBulkRuleRunner {
        Runner() {
            super(null, "", "/LibreClinica", null);
        }

        @Override ExpressionService getExpressionService() { return expressionService; }
        @Override StudyEventDAO getStudyEventDao() { return studyEventDao; }
        @Override EventCRFDAO getEventCrfDao() { return eventCrfDao; }
        @Override CRFVersionDAO getCrfVersionDao() { return crfVersionDao; }
        @Override StudySubjectDAO getStudySubjectDao() { return studySubjectDao; }

        @Override
        HashMap<String, String> prepareEmailContents(RuleSetBean rs, RuleSetRuleBean rsr, StudyBean study, RuleActionBean action) {
            targetsSeenByActions.add(rs.getTarget().getValue());
            return new HashMap<>();
        }
    }

    @Test
    public void everyActionSeesTheTargetItWasEvaluatedFor() {
        ExpressionBean original = new ExpressionBean();
        original.setValue("SE_VISIT.F_CRF.IG_GROUP.I_X");
        ExpressionBean targetA = new ExpressionBean();
        targetA.setValue(TARGET_A);
        ExpressionBean targetB = new ExpressionBean();
        targetB.setValue(TARGET_B);

        RuleBean rule = new RuleBean();
        rule.setOid("R_ALWAYS");
        rule.setName("always");
        ExpressionBean ruleExpression = new ExpressionBean();
        ruleExpression.setValue("1 eq 1");
        rule.setExpression(ruleExpression);

        DiscrepancyNoteActionBean note = new DiscrepancyNoteActionBean();
        note.setExpressionEvaluatesTo(true);
        note.setMessage("check X");
        RuleSetRuleBean ruleSetRule = new RuleSetRuleBean();
        ruleSetRule.setRuleBean(rule);
        List<RuleActionBean> actions = new ArrayList<>();
        actions.add(note);
        ruleSetRule.setActions(actions);
        note.setRuleSetRule(ruleSetRule);

        RuleSetBean ruleSet = new RuleSetBean();
        ruleSet.setOriginalTarget(original);
        ruleSet.addExpression(targetA);
        ruleSet.addExpression(targetB);
        ruleSet.addRuleSetRule(ruleSetRule);

        ItemDataBean itemData = new ItemDataBean();
        itemData.setId(900);
        itemData.setValue("5");
        when(expressionService.getItemOid(anyString())).thenReturn("I_X");
        when(expressionService.getItemDataBeanFromDb(anyString())).thenReturn(itemData);
        when(expressionService.getStudyEventDefenitionOrdninalCurated(TARGET_A)).thenReturn("101");
        when(expressionService.getStudyEventDefenitionOrdninalCurated(TARGET_B)).thenReturn("202");
        when(expressionService.getCrfOid(anyString())).thenReturn("F_CRF");
        when(expressionService.getCustomExpressionUsedToCreateView(anyString(), anyInt()))
                .thenAnswer(inv -> inv.getArgument(0));
        when(expressionService.getStudyEventDefinitionFromExpression(anyString(), any()))
                .thenReturn(new StudyEventDefinitionBean());
        when(expressionService.getItemGroupNameAndOrdinal(anyString())).thenReturn("Group");
        when(expressionService.getItemGroupExpression(anyString())).thenReturn(new ItemGroupBean());
        when(expressionService.getItemExpression(anyString(), any())).thenReturn(new ItemBean());
        when(studyEventDao.findByPK(anyInt())).thenAnswer(inv -> {
            StudyEventBean e = new StudyEventBean();
            e.setId(inv.getArgument(0));
            e.setSampleOrdinal(1);
            return e;
        });
        EventCRFBean eventCrf = new EventCRFBean();
        eventCrf.setCRFVersionId(3);
        ArrayList<EventCRFBean> eventCrfs = new ArrayList<>();
        eventCrfs.add(eventCrf);
        when(eventCrfDao.findAllByStudyEventAndCrfOrCrfVersionOid(any(), eq("F_CRF"))).thenReturn(eventCrfs);
        when(crfVersionDao.findByPK(3)).thenReturn(new CRFVersionBean());
        when(studySubjectDao.findByPK(anyInt())).thenReturn(new StudySubjectBean());
        when(runLogDao.findCountByRuleActionRunLogBean(any())).thenReturn(0);

        Runner runner = new Runner();
        runner.setRuleActionRunLogDao(runLogDao);
        List<RuleSetBean> ruleSets = new ArrayList<>();
        ruleSets.add(ruleSet);
        runner.runRulesBulk(ruleSets, ExecutionMode.DRY_RUN, new StudyBean(), null, new UserAccountBean());

        assertEquals(List.of(TARGET_A, TARGET_B), targetsSeenByActions);
    }
}
