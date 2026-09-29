/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;

import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

/**
 * The XForm CRF-version upload looks up versions and items by the names it
 * was given. The names are bound as query parameters, never spliced into
 * the SQL text.
 */
public class NameLookupBindingTest {

    private static final String HOSTILE = "v1' or '1'='1";

    private Session session;
    private NativeQuery<?> query;

    private <D extends AbstractDomainDao<?>> D wire(D dao) throws Exception {
        session = mock(Session.class);
        query = mock(NativeQuery.class, RETURNS_SELF);
        when(query.getSingleResultOrNull()).thenReturn(null);
        when(session.createNativeQuery(anyString())).thenReturn((NativeQuery) query);
        EntityManager em = mock(EntityManager.class);
        when(em.unwrap(Session.class)).thenReturn(session);
        Field f = AbstractDomainDao.class.getDeclaredField("entityManager");
        f.setAccessible(true);
        f.set(dao, em);
        return dao;
    }

    private String executedSql() {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(session).createNativeQuery(sql.capture());
        return sql.getValue();
    }

    @Test
    public void crfVersionNameIsBound() throws Exception {
        CrfVersionDao dao = wire(new CrfVersionDao());

        assertNull(dao.findByNameCrfId(HOSTILE, 7));

        String sql = executedSql();
        assertFalse(sql, sql.contains(HOSTILE));
        assertTrue(sql, sql.contains(":name") && sql.contains(":crfId"));
        verify(query).setParameter("name", HOSTILE, String.class);
        verify(query).setParameter("crfId", 7, Integer.class);
    }

    @Test
    public void itemNameIsBound() throws Exception {
        ItemDao dao = wire(new ItemDao());

        assertNull(dao.findByNameCrfId(HOSTILE, 7));

        String sql = executedSql();
        assertFalse(sql, sql.contains(HOSTILE));
        assertTrue(sql, sql.contains(":name") && sql.contains(":crfId"));
        verify(query).setParameter("name", HOSTILE, String.class);
        verify(query).setParameter("crfId", 7, Integer.class);
    }
}
