/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.rule;

//import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.RuleBean;
//import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.RuleSetBean;
//import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.RuleSetRuleBean;
//import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.RulesPostImportContainer;
//import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.action.EventActionBean;
//import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.expression.Context;
//import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.expression.ExpressionBean;
//import java.util.ArrayList;

import junit.framework.TestCase;

public class RulesPostImportContainerServiceTest extends TestCase {

    public RulesPostImportContainerServiceTest() {
        super();
    }

    /**
     * Placeholder so JUnit 3 finds at least one test method on this class.
     *
     * <p>The historical {@code testDuplicationRuleSetDefs} method below has been
     * commented out since at least the 2019 OpenClinica → LibreClinica fork.
     * Restoring it requires investigation: the referenced DAO/container types
     * and rule-validation semantics may have drifted in the intervening years.
     * Until someone restores the assertion, this class exists to keep JUnit 3
     * from raising "No tests found in ..." under {@code mvn -P integration-tests}.
     */
    public void testPlaceholder() {
        // intentionally empty — see Javadoc.
    }

    /**
    public void testDuplicationRuleSetDefs() {
        StudyDAO studyDao = new StudyDAO(getDataSource());
        StudyBean study = (StudyBean) studyDao.findByPK(1);
        RulesPostImportContainerService postImportContainerService = (RulesPostImportContainerService) getContext().getBean("rulesPostImportContainerService");
        postImportContainerService.setCurrentStudy(study);

        RulesPostImportContainer container = prepareContainer();

        container = postImportContainerService.validateRuleDefs(container);

        assertEquals(0, container.getDuplicateRuleDefs().size());
        assertEquals(0, container.getInValidRuleDefs().size());
        assertEquals(1, container.getValidRuleDefs().size());

        container = postImportContainerService.validateRuleSetDefs(container);
        assertEquals(1, container.getDuplicateRuleSetDefs().size());
        assertEquals(0, container.getInValidRuleSetDefs().size());
        assertEquals(0, container.getValidRuleSetDefs().size());
    }

    private  ArrayList<RuleSetBean> prepareContainer() {
        ArrayList<RuleSetBean> ruleSets = new ArrayList<RuleSetBean>();

        RuleBean rule = createRuleBean();
        RuleSetBean ruleSet = getRuleSet(rule.getOid(),"SE_REG.STARTDATE","SE_REG3");
        RuleSetBean ruleSet2 = getRuleSet(rule.getOid(),"SE_REG3.STARTDATE","SE_REG2");
        ruleSets.add(ruleSet);
        ruleSets.add(ruleSet2);
        return ruleSets;

    }
    

    private RuleSetBean getRuleSet(String ruleOid,String target,String oidRef) {
        RuleSetBean ruleSet = new RuleSetBean();
        ruleSet.setTarget(createExpression(Context.OC_RULES_V1, target));
        ruleSet.setOriginalTarget(createExpression(Context.OC_RULES_V1, target));
        RuleSetRuleBean ruleSetRule = createRuleSetRule(ruleSet, ruleOid,oidRef);
        ruleSet.addRuleSetRule(ruleSetRule);
        return ruleSet;

    }

    private RuleSetRuleBean createRuleSetRule(RuleSetBean ruleSet, String ruleOid, String oidRef) {
        RuleSetRuleBean ruleSetRule = new RuleSetRuleBean();
        //DiscrepancyNoteActionBean ruleAction = new DiscrepancyNoteActionBean();
        EventActionBean ruleAction = new EventActionBean();
        ruleAction.setOc_oid_reference(oidRef);
        ruleAction.setExpressionEvaluatesTo(true);
        ruleSetRule.addAction(ruleAction);
        ruleSetRule.setRuleSetBean(ruleSet);
        ruleSetRule.setOid(ruleOid);

        return ruleSetRule;
    }

    private RuleBean createRuleBean() {
        RuleBean ruleBean = new RuleBean();
        ruleBean.setName("TEST");
        ruleBean.setOid("BOY");
        ruleBean.setDescription("Yellow");
        ruleBean.setExpression(createExpression(Context.OC_RULES_V1,
                "SE_ED1NONRE.F_AGEN.IG_AGEN_UNGROUPED[1].I_AGEN_PERIODSTART eq \"07/01/2008\" and I_CONC_CON_MED_NAME eq \"Tylenol\""));
        return ruleBean;
    }

    private ExpressionBean createExpression(Context context, String value) {
        ExpressionBean expression = new ExpressionBean();
        expression.setContext(context);
        expression.setValue(value);
        return expression;
    }    
    */
}
