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

import at.ac.meduniwien.ophthalmology.libreclinica.domain.CompositeIdDomainObject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.springframework.transaction.annotation.Transactional;


/**
 * Phase B.5: same JPA EntityManager wiring as {@link AbstractDomainDao};
 * see that class for rationale.
 *
 * <p>Its one subclass, {@link StudyUserRoleDao}, is only ever asked to
 * {@link #saveOrUpdate} and to run its own query, so that is all this base
 * still offers.
 */
public abstract class CompositeIdAbstractDomainDao<T extends CompositeIdDomainObject> {

    @PersistenceContext
    private EntityManager entityManager;

    abstract Class<T> domainClass();

    public String getDomainClassName() {
        return domainClass().getName();
    }

    /**
     * Deprecated {@link Session#saveOrUpdate} on purpose; see
     * {@link AbstractDomainDao#saveOrUpdate} for why it is not yet
     * {@code persist}/{@code merge}.
     */
    @SuppressWarnings("deprecation")
    @Transactional
    public T saveOrUpdate(T domainObject) {
        getSessionFactory().getStatistics().logSummary();
        getCurrentSession().saveOrUpdate(domainObject);
        return domainObject;
    }

    public SessionFactory getSessionFactory() {
        return getCurrentSession().getSessionFactory();
    }

    public Session getCurrentSession() {
        return entityManager.unwrap(Session.class);
    }
}
