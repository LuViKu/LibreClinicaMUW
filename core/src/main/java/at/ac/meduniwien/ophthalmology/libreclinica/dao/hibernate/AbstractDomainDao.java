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
@SuppressWarnings("resource") // Session comes from the JPA EntityManager (getCurrentSession); the transaction manager closes it
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
     * Persist a new entity (the instance itself becomes managed and gets its
     * id), or merge a detached one. <b>Use the returned instance:</b> for a
     * detached argument it is the managed copy and the argument stays detached.
     * See {@link SessionSaveSupport} for the rules.
     */
    @Transactional
    public T saveOrUpdate(T domainObject) {
        getSessionFactory().getStatistics().logSummary();
        return SessionSaveSupport.saveOrUpdate(getCurrentSession(), domainObject);
    }

    /**
     * Insert a new entity and return its generated id. Every caller hands in
     * a freshly constructed entity, for which {@code persist} does what the
     * deprecated {@code Session.save} did: it assigns the id to the instance
     * itself. The id returned is the one the session holds for the instance,
     * not {@link DomainObject#getId()}: the {@code DataMapDomainObject}
     * entities (CrfBean, CrfVersion, ItemGroup, Item and most of
     * {@code domain.datamap}) map their id on a getter of their own, and
     * their {@code getId()} returns null.
     */
    @Transactional
    public Serializable save(T domainObject) {
        getSessionFactory().getStatistics().logSummary();
        getCurrentSession().persist(domainObject);
        return (Serializable) getCurrentSession().getIdentifier(domainObject);
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
