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

import java.util.ArrayList;

import at.ac.meduniwien.ophthalmology.libreclinica.domain.technicaladmin.AuditUserLoginBean;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import org.hibernate.Session;
import org.hibernate.query.Query;

/**
 * DAO over {@link AuditUserLoginBean}.
 *
 * <p>Phase B.5: the legacy {@code session.createCriteria(domainClass())}
 * (Hibernate 5) is gone in Hibernate 6. Both filter and count paths now
 * build a {@code jakarta.persistence.criteria.CriteriaQuery} via
 * {@link Session#getCriteriaBuilder()} and let {@link AuditUserLoginFilter}
 * / {@link AuditUserLoginSort} contribute predicates and ordering.
 */
public class AuditUserLoginDao extends AbstractDomainDao<AuditUserLoginBean> {

    @Override
    public Class<AuditUserLoginBean> domainClass() {
        return AuditUserLoginBean.class;
    }

    public ArrayList<AuditUserLoginBean> findAll() {
        String hql = "from " + getDomainClassName() + " aul order by aul.loginAttemptDate desc";
        Query<AuditUserLoginBean> q = getCurrentSession().createQuery(hql, AuditUserLoginBean.class);
        return new ArrayList<>(q.list());
    }

    public int getCountWithFilter(final AuditUserLoginFilter filter) {
        Session session = getCurrentSession();
        CriteriaBuilder cb = session.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<AuditUserLoginBean> root = cq.from(AuditUserLoginBean.class);
        cq.select(cb.count(root));
        filter.apply(cb, cq, root);
        Long count = session.createQuery(cq).uniqueResult();
        return count == null ? 0 : count.intValue();
    }

    public ArrayList<AuditUserLoginBean> getWithFilterAndSort(final AuditUserLoginFilter filter,
                                                               final AuditUserLoginSort sort,
                                                               final int rowStart,
                                                               final int rowEnd) {
        Session session = getCurrentSession();
        CriteriaBuilder cb = session.getCriteriaBuilder();
        CriteriaQuery<AuditUserLoginBean> cq = cb.createQuery(AuditUserLoginBean.class);
        Root<AuditUserLoginBean> root = cq.from(AuditUserLoginBean.class);
        cq.select(root);
        filter.apply(cb, cq, root);
        sort.apply(cb, cq, root);
        Query<AuditUserLoginBean> q = session.createQuery(cq);
        q.setFirstResult(rowStart);
        q.setMaxResults(rowEnd - rowStart);
        return new ArrayList<>(q.list());
    }
}
