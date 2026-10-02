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

import org.junit.Test;

import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.action.DiscrepancyNoteActionBean;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.action.HideActionBean;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.action.InsertActionBean;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.action.PropertyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.action.RandomizeActionBean;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.action.ShowActionBean;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.expression.ExpressionBean;

/**
 * The rule-set, rule-set-rule and show/hide/insert/randomize action beans each
 * compare a collection in equals(), and that collection is null until something
 * adds to it. equals() has to answer for a null collection instead of throwing:
 * two rule sets whose transient expression list was never filled are equal, and
 * a bean that has entries is not equal to one that has none, in either order.
 */
public class RuleBeanEqualsNullCollectionTest {

    @Test
    public void ruleSetsWithoutExpressions_areEqual() {
        assertTrue(new RuleSetBean().equals(new RuleSetBean()));
    }

    @Test
    public void ruleSetWithExpressions_isNotEqualToOneWithout() {
        RuleSetBean with = new RuleSetBean();
        with.addExpression(new ExpressionBean());

        assertFalse(with.equals(new RuleSetBean()));
        assertFalse(new RuleSetBean().equals(with));
    }

    @Test
    public void ruleSetRuleWithActions_isNotEqualToOneWithout() {
        RuleSetRuleBean with = new RuleSetRuleBean();
        with.addAction(new DiscrepancyNoteActionBean());

        assertFalse(with.equals(new RuleSetRuleBean()));
        assertFalse(new RuleSetRuleBean().equals(with));
    }

    @Test
    public void showActionWithProperties_isNotEqualToOneWithout() {
        ShowActionBean with = new ShowActionBean();
        with.addProperty(new PropertyBean());

        assertFalse(with.equals(new ShowActionBean()));
        assertFalse(new ShowActionBean().equals(with));
    }

    @Test
    public void hideActionWithProperties_isNotEqualToOneWithout() {
        HideActionBean with = new HideActionBean();
        with.addProperty(new PropertyBean());

        assertFalse(with.equals(new HideActionBean()));
        assertFalse(new HideActionBean().equals(with));
    }

    @Test
    public void insertActionWithProperties_isNotEqualToOneWithout() {
        InsertActionBean with = new InsertActionBean();
        with.addProperty(new PropertyBean());

        assertFalse(with.equals(new InsertActionBean()));
        assertFalse(new InsertActionBean().equals(with));
    }

    @Test
    public void randomizeActionWithProperties_isNotEqualToOneWithout() {
        RandomizeActionBean with = new RandomizeActionBean();
        with.addProperty(new PropertyBean());

        assertFalse(with.equals(new RandomizeActionBean()));
        assertFalse(new RandomizeActionBean().equals(with));
    }
}
