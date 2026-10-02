/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.bean.admin;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.junit.Test;

import at.ac.meduniwien.ophthalmology.libreclinica.exception.OpenClinicaException;

/**
 * When the database cannot be reached, a CRF upload must report the database
 * error. The catch blocks rolled back the connection unconditionally, and it
 * is still null when getConnection() itself fails, so the upload answered a
 * NullPointerException and the database error was lost.
 */
public class NewCRFBeanRollbackTest {

    @Test
    public void aConnectionFailureIsReportedAsTheDatabaseError() throws Exception {
        DataSource ds = mock(DataSource.class);
        when(ds.getConnection()).thenThrow(new SQLException("connection refused"));
        NewCRFBean bean = new NewCRFBean(ds, 1);
        ArrayList<String> queries = new ArrayList<>();
        queries.add("INSERT INTO crf_version (name) VALUES ('v1')");
        bean.setQueries(queries);

        try {
            bean.insertToDB();
            fail("expected OpenClinicaException");
        } catch (OpenClinicaException expected) {
            List<String> errors = bean.getErrors();
            assertTrue(String.valueOf(errors), errors.stream().anyMatch(e -> e.contains("connection refused")));
        }
    }
}
