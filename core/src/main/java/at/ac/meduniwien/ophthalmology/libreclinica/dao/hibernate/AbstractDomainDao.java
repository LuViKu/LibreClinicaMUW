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

import at.ac.meduniwien.ophthalmology.libreclinica.dao.core.CoreResources;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.DomainObject;
import org.apache.commons.lang.StringUtils;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.query.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase B.5: Spring's {@code HibernateTemplate} (org.springframework.orm.hibernate5)
 * was retired here. Even though Spring 6 keeps the package, it directly
 * references {@code org.hibernate.criterion.*} which Hibernate 6 deleted —
 * so the class can't load with Hibernate 6 on classpath. The SessionFactory
 * is injected directly instead; the {@code getSessionFactory()} /
 * {@code getCurrentSession()} surface this DAO base offered to subclasses is
 * unchanged.
 */
public abstract class AbstractDomainDao<T extends DomainObject> {

    protected final Logger logger = LoggerFactory.getLogger(getClass().getName());
    private SessionFactory sessionFactory;

    abstract Class<T> domainClass();

    public String getDomainClassName() {
        return domainClass().getName();
    }

    @SuppressWarnings("unchecked")
    @Transactional
    public T findById(Integer id) {
        getSessionFactory().getStatistics().logSummary();
        String query = "from " + getDomainClassName() + " do  where do.id = :id";
        Query<T> q = getCurrentSession().createQuery(query);
        q.setParameter("id", id);
        return q.uniqueResult();
    }

    @SuppressWarnings("unchecked")
    @Transactional
    public ArrayList<T> findAll() {
        getSessionFactory().getStatistics().logSummary();
        String query = "from " + getDomainClassName() + " do";
        Query<T> q = getCurrentSession().createQuery(query);
        return new ArrayList<T>(q.list());
    }
    
    @SuppressWarnings("unchecked")
	public T findByOcOID(String OCOID){
    	 getSessionFactory().getStatistics().logSummary();
         String query = "from " + getDomainClassName() + " do  where do.oc_oid = :oc_oid";
         Query<T> q = getCurrentSession().createQuery(query);
         q.setParameter("oc_oid", OCOID);
         return q.uniqueResult();
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
        // Hibernate 6: Session.save(Object) returns Object (deprecated; persist()
        // is the JPA-style replacement but returns void). Callers cast to
        // Integer; the underlying ID is always Serializable.
        return (Serializable) getCurrentSession().save(domainObject);
    }

    @SuppressWarnings("unchecked")
    @Transactional
    public T findByColumnName(Object id, String key) {
        String query = "from " + getDomainClassName() + " do where do." + key + " = :key_value";
        Query<T> q = getCurrentSession().createQuery(query);
        q.setParameter("key_value", id);
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

    /**
     * @return Session Object
     */
    public Session getCurrentSession() {
        return getSessionFactory().getCurrentSession();
    }

    public Session getCurrentSession(String schema) {
        Session session = getSessionFactory().getCurrentSession();

        if (StringUtils.isNotEmpty(schema)) {
            // Phase B.5: Hibernate 6 removed Session.connection() (and the
            // SessionImpl.connection() override). The portable replacement is
            // Session.doWork(Work) which exposes the underlying JDBC Connection
            // for the duration of the callback.
            session.doWork(connection -> {
                String currentSchema = connection.getSchema();
                if (!schema.equals(currentSchema)) {
                    connection.setSchema(schema);
                    CoreResources.tenantSchema.set(schema);
                }
            });
        }
        return session;
    }

}
