/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 */
package at.ac.meduniwien.ophthalmology.libreclinica.dao.rule;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;

import javax.sql.DataSource;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Status;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.rule.RuleSetBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.rule.RuleSetRuleAuditBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.rule.RuleSetRuleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.core.EntityDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.core.SQLFactory;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.core.TypeNames;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.login.UserAccountDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.exception.OpenClinicaException;

public class RuleSetRuleAuditDAO extends EntityDAO<RuleSetRuleAuditBean> {

    RuleSetDAO ruleSetDao;
    RuleSetRuleDAO ruleSetRuleDao;
    UserAccountDAO userAccountDao;

    public RuleSetRuleAuditDAO(DataSource ds) {
        super(ds);
        this.getCurrentPKName = "findCurrentPKValue";
    }

    private RuleSetRuleDAO getRuleSetRuleDao() {
        return this.ruleSetRuleDao != null ? this.ruleSetRuleDao : new RuleSetRuleDAO(ds);
    }

    private UserAccountDAO getUserAccountDao() {
        return this.userAccountDao != null ? this.userAccountDao : new UserAccountDAO(ds);
    }

    @Override
    protected void setDigesterName() {
        digesterName = SQLFactory.getInstance().DAO_RULESETRULE_AUDIT;
    }

    public void setTypesExpected() {
        this.unsetTypeExpected();
        this.setTypeExpected(1, TypeNames.INT);
        this.setTypeExpected(2, TypeNames.INT);
        this.setTypeExpected(3, TypeNames.DATE);// date_updated
        this.setTypeExpected(4, TypeNames.INT);
        this.setTypeExpected(5, TypeNames.INT);

    }

    public RuleSetRuleAuditBean getEntityFromHashMap(HashMap<String, Object> hm) {
        RuleSetRuleAuditBean ruleSetRuleAudit = new RuleSetRuleAuditBean();
        ruleSetRuleAudit.setId((Integer) hm.get("rule_set_rule_audit_id"));
        int ruleSetRuleId = (Integer) hm.get("rule_set_rule_id");
        int userAccountId = (Integer) hm.get("updater_id");
        int statusId = (Integer) hm.get("status_id");
        Date dateUpdated = (Date) hm.get("date_updated");
        ruleSetRuleAudit.setDateUpdated(dateUpdated);
        ruleSetRuleAudit.setStatus(Status.get(statusId));
        ruleSetRuleAudit.setRuleSetRuleBean((RuleSetRuleBean) getRuleSetRuleDao().findByPK(ruleSetRuleId));
        ruleSetRuleAudit.setUpdater((UserAccountBean) getUserAccountDao().findByPK(userAccountId));

        return ruleSetRuleAudit;
    }

    /**
     * NOT IMPLEMENTED
     */
    public ArrayList<RuleSetRuleAuditBean> findAll(String strOrderByColumn, boolean blnAscendingSort, String strSearchPhrase) throws OpenClinicaException {
        throw new RuntimeException("Not implemented");
    }

    /**
     * NOT IMPLEMENTED
     */
    public ArrayList<RuleSetRuleAuditBean> findAll() throws OpenClinicaException {
        throw new RuntimeException("Not implemented");
    }

    public RuleSetRuleAuditBean findByPK(int id) throws OpenClinicaException {
    	String queryName = "findByPK";
        HashMap<Integer, Object> variables = variables(id);
        return executeFindByPKQuery(queryName, variables);
    }

    public ArrayList<RuleSetRuleAuditBean> findAllByRuleSet(RuleSetBean ruleSet) {
    	String queryName = "findAllByRuleSet";
        HashMap<Integer, Object> variables = variables(ruleSet.getId());
        return executeFindAllQuery(queryName, variables);
    }

    public RuleSetRuleAuditBean create(RuleSetRuleBean ruleSetRuleBean, UserAccountBean ub) {
        // INSERT INTO rule_set_rule_audit (rule_set_rule_id, status_id,updater_id,date_updated) VALUES (?,?,?,?,?)
        RuleSetRuleAuditBean ruleSetRuleAudit = new RuleSetRuleAuditBean();
        HashMap<Integer, Object> variables = new HashMap<Integer, Object>();
        variables.put(1, ruleSetRuleBean.getId());
        variables.put(2, ruleSetRuleBean.getStatus().getId());
        variables.put(3, ub.getId());

        this.executeUpdate(digester.getQuery("create"), variables);
        if (isQuerySuccessful()) {
            ruleSetRuleAudit.setRuleSetRuleBean(ruleSetRuleBean);
            ruleSetRuleAudit.setId(getCurrentPK());
            ruleSetRuleAudit.setStatus(ruleSetRuleBean.getStatus());
            ruleSetRuleAudit.setUpdater(ub);
        }
        return ruleSetRuleAudit;
    }

    /**
     * NOT IMPLEMENTED
     */
    @Override
    public RuleSetRuleAuditBean create(RuleSetRuleAuditBean ruleSetRuleBean) throws OpenClinicaException {
    	throw new RuntimeException("Not implemented");
    }

    /**
     * NOT IMPLEMENTED
     */
    @Override
    public RuleSetRuleAuditBean update(RuleSetRuleAuditBean eb) throws OpenClinicaException {
        throw new RuntimeException("Not implemented");
    }

    /**
     * NOT IMPLEMENTED
     */
    public ArrayList<RuleSetRuleAuditBean> findAllByPermission(Object objCurrentUser, int intActionType, String strOrderByColumn, boolean blnAscendingSort, String strSearchPhrase)
            throws OpenClinicaException {
        throw new RuntimeException("Not implemented");
    }

    /**
     * NOT IMPLEMENTED
     */
    public ArrayList<RuleSetRuleAuditBean> findAllByPermission(Object objCurrentUser, int intActionType) throws OpenClinicaException {
        throw new RuntimeException("Not implemented");
    }

	@Override
	public RuleSetRuleAuditBean emptyBean() {
		return new RuleSetRuleAuditBean();
	}

}
