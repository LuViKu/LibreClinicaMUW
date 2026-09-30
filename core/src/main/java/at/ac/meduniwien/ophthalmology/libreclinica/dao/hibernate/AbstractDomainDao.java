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

    @Transactional
    public T findById(Integer id) {
        getSessionFactory().getStatistics().logSummary();
        String query = "from " + getDomainClassName() + " do  where do.id = :id";
        Query<T> q = getCurrentSession().createQuery(query, domainClass());
        q.setParameter("id", id);
        return q.getSingleResultOrNull();
    }

    @Transactional
    public ArrayList<T> findAll() {
        getSessionFactory().getStatistics().logSummary();
        String query = "from " + getDomainClassName() + " do";
        Query<T> q = getCurrentSession().createQuery(query, domainClass());
        return new ArrayList<T>(q.getResultList());
    }

    public T findByOcOID(String OCOID){
         getSessionFactory().getStatistics().logSummary();
         String query = "from " + getDomainClassName() + " do  where do.oc_oid = :oc_oid";
         Query<T> q = getCurrentSession().createQuery(query, domainClass());
         q.setParameter("oc_oid", OCOID);
         return q.getSingleResultOrNull();
    }

    /**
     * Still {@link Session#saveOrUpdate}, deprecated since Hibernate 6.0 and
     * gone in 7. Neither replacement keeps its contract: {@code merge} returns
     * a managed copy and leaves a detached argument detached, and
     * {@code persist} rejects a detached entity reached by cascade. Callers
     * rely on the argument itself becoming persistent (the rule import saves
     * graphs that were loaded in an earlier request), so the move belongs with
     * the Hibernate 7 upgrade, caller by caller.
     */
    @SuppressWarnings("deprecation")
    @Transactional
    public T saveOrUpdate(T domainObject) {
        getSessionFactory().getStatistics().logSummary();
        getCurrentSession().saveOrUpdate(domainObject);
        return domainObject;
    }

    /**
     * Insert a new entity and return its generated id. Every caller hands in
     * a freshly constructed entity, for which {@code persist} does what the
     * deprecated {@code Session.save} did: it assigns the id to the instance
     * itself.
     */
    @Transactional
    public Serializable save(T domainObject) {
        getSessionFactory().getStatistics().logSummary();
        getCurrentSession().persist(domainObject);
        return domainObject.getId();
    }

    @Transactional
    public T findByColumnName(Object id, String key) {
        String query = "from " + getDomainClassName() + " do where do." + key + " = :key_value";
        Query<T> q = getCurrentSession().createQuery(query, domainClass());
        q.setParameter("key_value", id);
        return q.getSingleResultOrNull();
    }

    public Long count() {
        return getCurrentSession().createQuery("select count(*) from " + domainClass().getName(), Long.class).uniqueResult();
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
