/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 */
package at.ac.meduniwien.ophthalmology.libreclinica.bean.rule;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.AuditableEntityBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.oid.GenericOidGenerator;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.oid.OidGenerator;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.rule.expression.ExpressionBean;

import java.util.List;

/**
 * <p>
 * Rule, the object that collects rules associated with RuleSets.
 * </p>
 * If the sourceExpression evaluates to true then the targetExpression should
 * evaluate to true too ; if target does not evaluate to true actions will be
 * executed.
 *
 * @author Krikor Krumlian
 */
public class RuleBean extends AuditableEntityBean {

    /**
	 * 
	 */
	private static final long serialVersionUID = 6836960662728524106L;
	private String oid;
    private String type;
    private String description;
    private boolean enabled;

    private ExpressionBean expression;
    private List<RuleSetRuleBean> ruleSetRules;
    private OidGenerator oidGenerator;

    public RuleBean() {
        this.oidGenerator = new GenericOidGenerator();
    }

    // SETTERS & GETTERS

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public ExpressionBean getExpression() {
        return expression;
    }

    public void setExpression(ExpressionBean expression) {
        this.expression = expression;
    }

    public List<RuleSetRuleBean> getRuleSetRules() {
        return ruleSetRules;
    }

    public void setRuleSetRules(List<RuleSetRuleBean> ruleSetRules) {
        this.ruleSetRules = ruleSetRules;
    }

    public String getOid() {
        return oid;
    }

    public void setOid(String oid) {
        this.oid = oid;
    }

    public OidGenerator getOidGenerator() {
        return oidGenerator;
    }

    public void setOidGenerator(OidGenerator oidGenerator) {
        this.oidGenerator = oidGenerator;
    }
}