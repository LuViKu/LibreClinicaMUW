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

import java.io.Serializable;
import java.util.ArrayList;

import at.ac.meduniwien.ophthalmology.libreclinica.dao.core.CoreResources;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.DomainObject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.apache.commons.lang.StringUtils;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.query.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase B.5 (2026-05-29): Hibernate 5 → 6 migration. Spring 6's
 * {@code org.springframework.orm.hibernate5} package was bytecode-compiled
 * against Hibernate 5 method signatures (e.g. {@code HibernateTemplate}
 * directly references {@code org.hibernate.criterion.Criterion} which
 * Hibernate 6 deleted; {@code LocalSessionFactoryBean} can't link against
 * Hibernate 6's builder-returning Configuration setters either). The fix
 * is to wire the DAO layer through the JPA {@link EntityManager} contract,
 * which Hibernate 6 implements natively, and unwrap to {@link Session}
 * only where the existing query code uses Hibernate-specific APIs.
 *
 * <p>The {@code getCurrentSession()} / {@code getSessionFactory()} accessor
 * surface is preserved so subclasses keep working without per-DAO edits.
 */
public abstract class AbstractDomainDao<T extends DomainObject> {

    protected final Logger logger = LoggerFactory.getLogger(getClass().getName());

    @PersistenceContext
    private EntityManager entityManager;

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

    /**
     * Hibernate {@link SessionFactory} unwrapped from the injected JPA
     * EntityManager (Hibernate 6's {@code Session} extends
     * {@link jakarta.persistence.EntityManager}, so unwrap is free).
     */
    public SessionFactory getSessionFactory() {
        return getCurrentSession().getSessionFactory();
    }

    /**
     * Hibernate {@link Session} corresponding to the current JPA transaction.
     */
    public Session getCurrentSession() {
        return entityManager.unwrap(Session.class);
    }

    public Session getCurrentSession(String schema) {
        Session session = getCurrentSession();
        if (StringUtils.isNotEmpty(schema)) {
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
