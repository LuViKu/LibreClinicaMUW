/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 */
package at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate;

import java.io.Serializable;
import java.util.ArrayList;

import at.ac.meduniwien.ophthalmology.libreclinica.domain.CompositeIdDomainObject;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.query.Query;
import org.springframework.transaction.annotation.Transactional;


/**
 * Phase B.5: HibernateTemplate retired — see {@link AbstractDomainDao}.
 */
public abstract class CompositeIdAbstractDomainDao<T extends CompositeIdDomainObject> {

    private SessionFactory sessionFactory;

    abstract Class<T> domainClass();

    public String getDomainClassName() {
        return domainClass().getName();
    }

    @SuppressWarnings("unchecked")
    @Transactional
    public ArrayList<T> findAll() {
        getSessionFactory().getStatistics().logSummary();
        String query = "from " + getDomainClassName() + " do";
        Query<T> q = getCurrentSession().createQuery(query);
        return new ArrayList<T>(q.list());
    }
    
    @Transactional
    public T saveOrUpdate(T domainObject) {
        getSessionFactory().getStatistics().logSummary();
        getCurrentSession().saveOrUpdate(domainObject);
        return domainObject;
    }

    @Transactional
    public Serializable save(T domainObject) {
        getSessionFactory().getStatistics().logSummary();
        // Hibernate 6: Session.save returns Object (deprecated in favour of
        // persist()); the actual id is always Serializable.
        return (Serializable) getCurrentSession().save(domainObject);
    }

    @SuppressWarnings("unchecked")
    @Transactional
    public T findByColumnName(Object id,String key) {
    String query = "from " + getDomainClassName() + " do where do."+key +"= :id";
    Query<T> q = getCurrentSession().createQuery(query);
    q.setParameter("id", id);
    return q.uniqueResult();
    } 
    
    public Long count() {
        return (Long) getCurrentSession().createQuery("select count(*) from " + domainClass().getName()).uniqueResult();
    }

    public SessionFactory getSessionFactory() {
        return sessionFactory;
    }

    public void setSessionFactory(SessionFactory sessionFactory) {
        this.sessionFactory = sessionFactory;
    }

    public Session getCurrentSession() {
        return getSessionFactory().getCurrentSession();
    }
}
