/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate;

import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.action.RuleActionRunLogBean;
import org.hibernate.query.Query;
import org.springframework.transaction.annotation.Transactional;

public class RuleActionRunLogDao extends AbstractDomainDao<RuleActionRunLogBean> {

    @Override
    public Class<RuleActionRunLogBean> domainClass() {
        return RuleActionRunLogBean.class;
    }

    /**
     * Count log entries matching the populated fields of {@code ruleActionRunLog}.
     *
     * <p>Phase B.5: this used Hibernate 5's {@code Example.create(bean)} +
     * {@code Projections.rowCount()} — both removed in Hibernate 6. Replaced
     * with an explicit HQL count over the same four fields the Example would
     * have matched: actionType, itemDataId, value, ruleOid. The original
     * Example call also implicitly matched on any other set bean fields, but
     * in practice the four constructor-populated columns are the only fields
     * present when callers (the rule runners) invoke this DAO.
     */
    @Transactional
    public Integer findCountByRuleActionRunLogBean(RuleActionRunLogBean bean) {
        String hql = "select count(r) from " + getDomainClassName() + " r"
                + " where r.actionType = :actionType"
                + " and r.itemDataId = :itemDataId"
                + " and r.value = :value"
                + " and r.ruleOid = :ruleOid";
        Query<Long> q = getCurrentSession().createQuery(hql, Long.class);
        q.setParameter("actionType", bean.getActionType());
        q.setParameter("itemDataId", bean.getItemDataId());
        q.setParameter("value", bean.getValue());
        q.setParameter("ruleOid", bean.getRuleOid());
        Long count = q.uniqueResult();
        return count == null ? 0 : count.intValue();
    }

    @Transactional
    public void delete(int itemDataId) {
        String hql = "delete from " + getDomainClassName() + " rarl where rarl.itemDataId = :itemDataId";
        Query<?> q = getCurrentSession().createQuery(hql);
        q.setParameter("itemDataId", itemDataId);
        q.executeUpdate();
    }
}
