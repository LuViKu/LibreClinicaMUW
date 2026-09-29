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
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Before;
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
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.RuleBulkExecuteContainer;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.RuleBulkExecuteContainerTwo;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.RuleSetBean;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.RuleSetRuleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.action.DiscrepancyNoteActionBean;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.action.RuleActionBean;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.action.RuleActionRunLogBean;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.expression.ExpressionBean;
import at.ac.meduniwien.ophthalmology.libreclinica.service.rule.expression.ExpressionService;

/**
 * The legacy "Run rule" dry run lists what its Submit would do (2026-09-29).
 *
 * <p>{@code runRulesBulk} built its preview map {@code hms} but never put
 * anything in it, so the dry-run page was always empty while its Submit
 * button executed every action. The runner is exercised here with its
 * database lookups mocked: one rule, one discrepancy-note action, a target
 * that resolves to two subjects' visits.
 */
public class CrfBulkRuleRunnerDryRunTest {

    private static final String TARGET_A = "SE_VISIT[101].F_CRF.IG_GROUP.I_X";
    private static final String TARGET_B = "SE_VISIT[202].F_CRF.IG_GROUP.I_X";

    private final ExpressionService expressionService = mock(ExpressionService.class);
    private final StudyEventDAO studyEventDao = mock(StudyEventDAO.class);
    private final EventCRFDAO eventCrfDao = mock(EventCRFDAO.class);
    private final CRFVersionDAO crfVersionDao = mock(CRFVersionDAO.class);
    private final StudySubjectDAO studySubjectDao = mock(StudySubjectDAO.class);
    private final RuleActionRunLogDao runLogDao = mock(RuleActionRunLogDao.class);

    private RuleSetBean ruleSet;
    private ExpressionBean targetB;

    /** The runner with its lookups replaced; e-mail contents are not under test. */
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
            return new HashMap<>();
        }
    }

    private static StudyEventBean event(int id, int studySubjectId) {
        StudyEventBean e = new StudyEventBean();
        e.setId(id);
        e.setStudySubjectId(studySubjectId);
        e.setSampleOrdinal(1);
        return e;
    }

    private static StudySubjectBean subject(int id, String label) {
        StudySubjectBean s = new StudySubjectBean();
        s.setId(id);
        s.setLabel(label);
        return s;
    }

    @Before
    public void setUp() {
        ExpressionBean original = new ExpressionBean();
        original.setValue("SE_VISIT.F_CRF.IG_GROUP.I_X");
        ExpressionBean targetA = new ExpressionBean();
        targetA.setValue(TARGET_A);
        targetB = new ExpressionBean();
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

        ruleSet = new RuleSetBean();
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
        StudyEventDefinitionBean visit = new StudyEventDefinitionBean();
        visit.setName("Visit");
        when(expressionService.getStudyEventDefinitionFromExpression(anyString(), any())).thenReturn(visit);
        when(expressionService.getItemGroupNameAndOrdinal(anyString())).thenReturn("Group");
        when(expressionService.getItemGroupExpression(anyString())).thenReturn(new ItemGroupBean());
        ItemBean item = new ItemBean();
        item.setName("X");
        when(expressionService.getItemExpression(anyString(), any())).thenReturn(item);

        when(studyEventDao.findByPK(101)).thenReturn(event(101, 11));
        when(studyEventDao.findByPK(202)).thenReturn(event(202, 12));
        EventCRFBean eventCrf = new EventCRFBean();
        eventCrf.setCRFVersionId(3);
        ArrayList<EventCRFBean> eventCrfs = new ArrayList<>();
        eventCrfs.add(eventCrf);
        when(eventCrfDao.findAllByStudyEventAndCrfOrCrfVersionOid(any(), eq("F_CRF"))).thenReturn(eventCrfs);
        CRFVersionBean version = new CRFVersionBean();
        version.setName("v1.0");
        when(crfVersionDao.findByPK(3)).thenReturn(version);
        when(studySubjectDao.findByPK(11)).thenReturn(subject(11, "SUBJ-A"));
        when(studySubjectDao.findByPK(12)).thenReturn(subject(12, "SUBJ-B"));
        when(runLogDao.findCountByRuleActionRunLogBean(any())).thenReturn(0);
    }

    private Map<RuleBulkExecuteContainer, HashMap<RuleBulkExecuteContainerTwo, Set<String>>> dryRun() {
        Runner runner = new Runner();
        runner.setRuleActionRunLogDao(runLogDao);
        List<RuleSetBean> ruleSets = new ArrayList<>();
        ruleSets.add(ruleSet);
        return runner.runRulesBulk(ruleSets, ExecutionMode.DRY_RUN, new StudyBean(), null, new UserAccountBean());
    }

    @Test
    public void theDryRunListsTheRuleItsResultItsActionAndEverySubject() {
        Map<RuleBulkExecuteContainer, HashMap<RuleBulkExecuteContainerTwo, Set<String>>> preview = dryRun();

        assertEquals(1, preview.size());
        RuleBulkExecuteContainer rule = preview.keySet().iterator().next();
        assertEquals("v1.0", rule.getCrfVersion());
        assertEquals("always", rule.getRuleName());
        assertEquals("true", rule.getResult());
        assertEquals(1, rule.getActions().size());
        assertEquals("check X", rule.getActions().get(0).getSummary());

        HashMap<RuleBulkExecuteContainerTwo, Set<String>> byVisit = preview.get(rule);
        assertEquals(2, byVisit.size());
        Map<String, Set<String>> subjectsByTarget = new HashMap<>();
        for (Map.Entry<RuleBulkExecuteContainerTwo, Set<String>> e : byVisit.entrySet()) {
            subjectsByTarget.put(e.getKey().getExpression(), e.getValue());
        }
        assertEquals(Set.of("SUBJ-A"), subjectsByTarget.get(TARGET_A));
        assertEquals(Set.of("SUBJ-B"), subjectsByTarget.get(TARGET_B));
    }

    @Test
    public void theDryRunWritesNothingAndLeavesTheRuleSetAsTheRunLeftIt() {
        dryRun();

        verify(runLogDao, never()).saveOrUpdate(any(RuleActionRunLogBean.class));
        // Execution sees the rule set pointed at its last target; building
        // the preview must not change that.
        assertSame(targetB, ruleSet.getTarget());
    }
}
