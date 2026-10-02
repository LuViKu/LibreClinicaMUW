/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.domain.rule;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;

import org.junit.Test;

import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.action.DiscrepancyNoteActionBean;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.action.HideActionBean;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.action.InsertActionBean;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.action.PropertyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.action.RandomizeActionBean;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.action.ShowActionBean;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.expression.ExpressionBean;

/**
 * equals() on the rule beans when both sides have a list. A rule set compares
 * only how many expressions each has. A rule-set rule and the show, hide,
 * insert and randomize actions compare the sizes, then check that each entry of
 * the other bean's list is in this one's. That check runs one way only, so the
 * size comparison is what keeps a bean with entries from equalling one whose
 * list is empty. {@link RuleBeanEqualsNullCollectionTest} covers a list that
 * was never filled.
 *
 * <p>The rules importer marks an imported rule-set rule that equals a
 * persisted one as an exact duplicate, and a persisted rule-set rule always has
 * its action list.
 */
public class RuleBeanEqualsCollectionSizeTest {

    @Test
    public void ruleSetWithOneExpression_isNotEqualToOneWithTwo() {
        RuleSetBean one = ruleSetWithExpressions(1);
        RuleSetBean two = ruleSetWithExpressions(2);

        assertFalse(one.equals(two));
        assertFalse(two.equals(one));
    }

    @Test
    public void ruleSetsWithTheSameExpressions_areEqual() {
        assertTrue(ruleSetWithExpressions(2).equals(ruleSetWithExpressions(2)));
    }

    @Test
    public void ruleSetRuleWithAnAction_isNotEqualToOneWithAnEmptyList() {
        RuleSetRuleBean with = new RuleSetRuleBean();
        with.addAction(new DiscrepancyNoteActionBean());
        RuleSetRuleBean empty = new RuleSetRuleBean();
        empty.setActions(new ArrayList<>());

        assertFalse(with.equals(empty));
        assertFalse(empty.equals(with));
    }

    @Test
    public void ruleSetRulesWhoseActionsHaveTheSameProperties_areEqual() {
        RuleSetRuleBean imported = new RuleSetRuleBean();
        imported.addAction(insertAction(property("I_B", "Y")));
        RuleSetRuleBean persisted = new RuleSetRuleBean();
        persisted.addAction(insertAction(property("I_B", "Y")));

        assertTrue(imported.equals(persisted));
        assertTrue(persisted.equals(imported));
    }

    @Test
    public void showActionWithAProperty_isNotEqualToOneWithAnEmptyList() {
        ShowActionBean with = new ShowActionBean();
        with.addProperty(new PropertyBean());
        ShowActionBean empty = new ShowActionBean();
        empty.setProperties(new ArrayList<>());

        assertFalse(with.equals(empty));
        assertFalse(empty.equals(with));
    }

    @Test
    public void hideActionWithAProperty_isNotEqualToOneWithAnEmptyList() {
        HideActionBean with = new HideActionBean();
        with.addProperty(new PropertyBean());
        HideActionBean empty = new HideActionBean();
        empty.setProperties(new ArrayList<>());

        assertFalse(with.equals(empty));
        assertFalse(empty.equals(with));
    }

    @Test
    public void insertActionWithAProperty_isNotEqualToOneWithAnEmptyList() {
        InsertActionBean with = insertAction(new PropertyBean());
        InsertActionBean empty = new InsertActionBean();
        empty.setProperties(new ArrayList<>());

        assertFalse(with.equals(empty));
        assertFalse(empty.equals(with));
    }

    @Test
    public void randomizeActionWithAProperty_isNotEqualToOneWithAnEmptyList() {
        RandomizeActionBean with = new RandomizeActionBean();
        with.addProperty(new PropertyBean());
        RandomizeActionBean empty = new RandomizeActionBean();
        empty.setProperties(new ArrayList<>());

        assertFalse(with.equals(empty));
        assertFalse(empty.equals(with));
    }

    private static RuleSetBean ruleSetWithExpressions(int count) {
        RuleSetBean ruleSet = new RuleSetBean();
        for (int i = 0; i < count; i++) {
            ruleSet.addExpression(new ExpressionBean());
        }
        return ruleSet;
    }

    private static InsertActionBean insertAction(PropertyBean property) {
        InsertActionBean action = new InsertActionBean();
        action.addProperty(property);
        return action;
    }

    private static PropertyBean property(String oid, String value) {
        PropertyBean property = new PropertyBean();
        property.setOid(oid);
        property.setValue(value);
        return property;
    }
}
