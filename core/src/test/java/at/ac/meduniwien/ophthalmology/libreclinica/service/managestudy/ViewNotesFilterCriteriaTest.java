/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.managestudy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Locale;

import org.junit.Test;

/**
 * The View Notes filter values come straight off the request. A non-numeric
 * value for a numeric column must stay an IllegalArgumentException -- that is
 * what ViewNotesDataServlet turns into a 400 -- and must say which filter it
 * was.
 */
public class ViewNotesFilterCriteriaTest {

    private static final DateFormat DF = new SimpleDateFormat("dd-MMM-yyyy", Locale.ENGLISH);

    @Test
    public void numericFilterParsesSingleValue() {
        assertEquals(Integer.valueOf(3), ViewNotesFilterCriteria.processValue("days", "3", DF));
    }

    @Test
    public void numericFilterParsesCommaSeparatedList() {
        assertEquals(Arrays.asList(1, 2),
                ViewNotesFilterCriteria.processValue("resolution_status_id", "1,2", DF));
    }

    @Test
    public void nonNumericValueIsAnIllegalArgumentExceptionNamingTheFilter() {
        try {
            ViewNotesFilterCriteria.processValue("days", "not-a-number", DF);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException ex) {
            String msg = String.valueOf(ex.getMessage());
            assertTrue(msg, msg.contains("days"));
            assertTrue(msg, msg.contains("not-a-number"));
        }
    }

    @Test
    public void nonNumericValueInAListIsAlsoAnIllegalArgumentException() {
        try {
            ViewNotesFilterCriteria.processValue("age", "1,x", DF);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException ex) {
            assertTrue(String.valueOf(ex.getMessage()), ex.getMessage().contains("age"));
        }
    }

    @Test
    public void textFilterIsWrappedForLike() {
        assertEquals("%abc%", ViewNotesFilterCriteria.processValue("label", " abc ", DF));
    }
}
