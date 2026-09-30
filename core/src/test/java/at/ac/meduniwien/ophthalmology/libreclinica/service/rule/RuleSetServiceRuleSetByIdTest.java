/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.rule;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.Before;
import org.junit.Test;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.RuleSetDao;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.RuleSetBean;

/**
 * getRuleSetById(study, id) serves the legacy rule-set pages (view, audit,
 * run, remove, restore) and the SPA dry-run, all of which take the id from the
 * request. It answers for a rule set of the current study or, on a site, of the
 * site's parent, whose rule sets data entry on the site runs. Any other id is
 * answered like one that does not exist, before the rule set's event
 * definition, CRF and item are loaded.
 */
public class RuleSetServiceRuleSetByIdTest {

    private static final int STUDY = 1;
    private static final int OTHER_STUDY = 2;
    private static final int SITE = 5;
    private static final int OTHER_SITE = 6;

    private final RuleSetDao ruleSetDao = mock(RuleSetDao.class);
    private RuleSetService service;

    @Before
    public void setUp() {
        service = spy(new RuleSetService());
        service.setRuleSetDao(ruleSetDao);
        doAnswer(invocation -> invocation.getArgument(0)).when(service).getObjects(any());
    }

    @Test
    public void ruleSetOfTheCurrentStudy_isFound() {
        RuleSetBean ruleSet = ruleSetOf(STUDY);

        assertSame(ruleSet, service.getRuleSetById(study(STUDY, 0), "7"));
        verify(service).getObjects(ruleSet);
    }

    @Test
    public void ruleSetOfTheParent_isFoundFromASite() {
        RuleSetBean ruleSet = ruleSetOf(STUDY);

        assertSame(ruleSet, service.getRuleSetById(study(SITE, STUDY), "7"));
    }

    @Test
    public void ruleSetOfASite_isFoundFromThatSite() {
        RuleSetBean ruleSet = ruleSetOf(SITE);

        assertSame(ruleSet, service.getRuleSetById(study(SITE, STUDY), "7"));
    }

    @Test
    public void ruleSetOfAnotherStudy_isNotFound() {
        ruleSetOf(OTHER_STUDY);

        assertNull(service.getRuleSetById(study(STUDY, 0), "7"));
        assertNull(service.getRuleSetById(study(SITE, STUDY), "7"));
        verify(service, never()).getObjects(any());
    }

    @Test
    public void ruleSetOfAnotherSite_isNotFoundFromASite() {
        ruleSetOf(OTHER_SITE);

        assertNull(service.getRuleSetById(study(SITE, STUDY), "7"));
    }

    /** The rule-set lists show a study's own rule sets only; from the parent, so does this lookup. */
    @Test
    public void ruleSetOfASite_isNotFoundFromItsParent() {
        ruleSetOf(SITE);

        assertNull(service.getRuleSetById(study(STUDY, 0), "7"));
    }

    @Test
    public void unknownId_isNotFound() {
        assertNull(service.getRuleSetById(study(STUDY, 0), "7"));
        verify(service, never()).getObjects(any());
    }

    private RuleSetBean ruleSetOf(int studyId) {
        RuleSetBean ruleSet = new RuleSetBean();
        ruleSet.setId(7);
        ruleSet.setStudyId(studyId);
        when(ruleSetDao.findById(7)).thenReturn(ruleSet);
        return ruleSet;
    }

    private static StudyBean study(int id, int parentId) {
        StudyBean study = new StudyBean();
        study.setId(id);
        study.setParentStudyId(parentId);
        return study;
    }
}
