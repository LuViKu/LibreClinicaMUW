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

import at.ac.meduniwien.ophthalmology.libreclinica.domain.technicaladmin.DatabaseChangeLogBean;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.Session;
import org.hibernate.query.Query;

/**
 * Phase B.5: standalone DAO (not a child of {@link AbstractDomainDao})
 * switched from injected SessionFactory to {@code @PersistenceContext}
 * EntityManager. Same rationale as {@link AbstractDomainDao} — Spring 6's
 * hibernate5 legacy package can't link against Hibernate 6.
 */
public class DatabaseChangeLogDao {

    @PersistenceContext
    private EntityManager entityManager;

    public String getDomainClassName() {
        return domainClass().getName();
    }

    public Class<DatabaseChangeLogBean> domainClass() {
        return DatabaseChangeLogBean.class;
    }

    public ArrayList<DatabaseChangeLogBean> findAll() {
        String query = "from " + getDomainClassName() + " dcl order by dcl.id desc ";
        Query<DatabaseChangeLogBean> q = getCurrentSession().createQuery(query, DatabaseChangeLogBean.class);
        return new ArrayList<>(q.list());
    }

    public DatabaseChangeLogBean findById(String id, String author, String fileName) {
        String query = "from " + getDomainClassName() + " do  where do.id = :id and do.author = :author and do.fileName = :fileName ";
        Query<DatabaseChangeLogBean> q = getCurrentSession().createQuery(query, DatabaseChangeLogBean.class);
        q.setParameter("id", id);
        q.setParameter("author", author);
        q.setParameter("fileName", fileName);
        return q.uniqueResult();
    }

    public Long count() {
        return (Long) getCurrentSession().createQuery("select count(*) from " + domainClass().getName()).uniqueResult();
    }

    protected Session getCurrentSession() {
        return entityManager.unwrap(Session.class);
    }
}
