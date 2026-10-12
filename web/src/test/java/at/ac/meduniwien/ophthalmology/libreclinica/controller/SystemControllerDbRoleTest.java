/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;

import org.junit.jupiter.api.Test;

/** The role lookup binds the user name and closes its statement and result set. */
class SystemControllerDbRoleTest {

    @Test
    void theUserNameIsBoundNotConcatenatedAndTheResourcesAreClosed() throws Exception {
        Connection conn = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true, false);
        when(rs.getString("rolname")).thenReturn("x' or '1'='1");
        when(rs.getString("rolsuper")).thenReturn("f");
        when(rs.getString("rolinherit")).thenReturn("t");
        when(rs.getString("rolcreaterole")).thenReturn("f");
        when(rs.getString("rolcreatedb")).thenReturn("f");
        when(rs.getString("rolcanlogin")).thenReturn("t");

        HashMap<String, String> role = new SystemController()
                .getDbRoleProperties(conn, new HashMap<>(), "x' or '1'='1");

        verify(conn).prepareStatement("select * from pg_roles where rolname = ?");
        verify(ps).setString(1, "x' or '1'='1");
        verify(rs).close();
        verify(ps).close();
        assertEquals("True", role.get("Login"));
        assertEquals("False", role.get("SuperUser"));
    }
}
