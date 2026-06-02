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

import java.util.Collections;
import java.util.List;

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

    /**
     * Phase E RX.1b — list run-log entries whose {@code rule_oc_oid}
     * matches any of the supplied rule OIDs, newest first.
     *
     * <p>The {@code rule_action_run_log} table has no timestamp column
     * (see {@code core/src/main/resources/migration/amethyst/2010-01-13-4575.xml}
     * changeset {@code -8}), so the "newest first" ordering uses the
     * auto-increment {@code id} as a serviceable proxy — fires are
     * append-only, so id ordering matches insertion ordering.
     *
     * <p>Empty / null {@code ruleOids} returns an empty list without
     * issuing a query (an empty {@code in} clause is invalid HQL on
     * most dialects).
     *
     * @param ruleOids the {@code rule.oc_oid} values to filter by;
     *                 typically gathered by walking a {@code rule_set}'s
     *                 {@code rule_set_rule} rows.
     * @param limit    maximum number of rows to return.
     * @param offset   zero-based offset into the ordered result set.
     */
    @Transactional
    public List<RuleActionRunLogBean> findByRuleOids(List<String> ruleOids, int limit, int offset) {
        if (ruleOids == null || ruleOids.isEmpty()) {
            return Collections.emptyList();
        }
        String hql = "from " + getDomainClassName()
                + " r where r.ruleOid in (:ruleOids) order by r.id desc";
        Query<RuleActionRunLogBean> q = getCurrentSession().createQuery(hql, RuleActionRunLogBean.class);
        q.setParameterList("ruleOids", ruleOids);
        q.setMaxResults(limit);
        q.setFirstResult(offset);
        return q.list();
    }
}
