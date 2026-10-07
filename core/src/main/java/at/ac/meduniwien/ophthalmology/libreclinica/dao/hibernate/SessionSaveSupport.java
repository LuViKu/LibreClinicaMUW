/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate;

import org.hibernate.Session;
import org.hibernate.engine.internal.ForeignKeys;
import org.hibernate.engine.spi.SessionImplementor;

/**
 * What {@code Session.saveOrUpdate} did, built from the JPA operations that
 * Hibernate 7 leaves. {@code saveOrUpdate} was removed in Hibernate 7 together
 * with {@code save}.
 *
 * <ul>
 *   <li><b>Managed</b> (already in this session): nothing to do, the instance
 *       is returned; its changes are flushed with the transaction.</li>
 *   <li><b>Transient</b> (never stored, judged by Hibernate's own rule: the
 *       unsaved-value of the id, the version, or a row lookup for assigned
 *       ids): {@code persist}. The passed instance becomes managed and gets
 *       its id, as it did with {@code save}.</li>
 *   <li><b>Detached</b> (stored, loaded in a session that is gone): {@code
 *       merge}. This is the one place the contract differs: the passed
 *       instance stays detached and the <i>returned</i> instance is the
 *       managed one. A caller that goes on to read an id or a version
 *       Hibernate assigns, or to change the entity and expect the change to
 *       be written, must use the return value. {@code docs/development/
 *       modernization/spring-boot-4-hibernate-7-call-sites.md} lists every
 *       caller and what it does.</li>
 * </ul>
 */
final class SessionSaveSupport {

    private SessionSaveSupport() {
    }

    static <T> T saveOrUpdate(Session session, T entity) {
        if (session.contains(entity)) {
            return entity;
        }
        SessionImplementor impl = session.unwrap(SessionImplementor.class);
        if (ForeignKeys.isTransient(impl.bestGuessEntityName(entity), entity, null, impl)) {
            session.persist(entity);
            return entity;
        }
        return session.merge(entity);
    }
}
