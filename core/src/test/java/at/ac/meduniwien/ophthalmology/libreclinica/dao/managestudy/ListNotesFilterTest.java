/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;

import org.junit.Test;

/**
 * The notes list's filter values arrive from a text box. A malformed number
 * must filter nothing, as an empty box does, instead of failing the listing
 * with a NumberFormatException.
 */
public class ListNotesFilterTest {

    private static String criteria(String property, String value, HashMap<Integer, Object> variables) {
        ListNotesFilter filter = new ListNotesFilter();
        filter.addFilter(property, value);
        return filter.execute("", variables);
    }

    @Test
    public void aComparisonWithANumberIsBound() {
        HashMap<Integer, Object> variables = new HashMap<>();
        String sql = criteria("age", ">5", variables);
        assertTrue(sql, sql.contains("age > ?"));
        assertEquals(Integer.valueOf(5), variables.get(1));
    }

    @Test
    public void aComparisonWithoutANumberFiltersNothing() {
        for (String property : new String[] {"age", "days"}) {
            for (String value : new String[] {">abc", "<", "=1.5"}) {
                HashMap<Integer, Object> variables = new HashMap<>();
                String sql = criteria(property, value, variables);
                assertFalse(property + " " + value + ": " + sql, sql.contains("?"));
                assertTrue(variables.isEmpty());
            }
        }
    }

    @Test
    public void aTypeOrStatusThatIsNotANumberFiltersNothing() {
        for (String property : new String[] {"discrepancyNoteBean.disType", "discrepancyNoteBean.resolutionStatus"}) {
            HashMap<Integer, Object> variables = new HashMap<>();
            String sql = criteria(property, "abc", variables);
            assertFalse(property + ": " + sql, sql.contains("?"));
            assertTrue(variables.isEmpty());
        }
    }

    @Test
    public void theGroupedTypeAndStatusCodesStillWork() {
        HashMap<Integer, Object> variables = new HashMap<>();
        assertTrue(criteria("discrepancyNoteBean.disType", "31", variables).contains("discrepancy_note_type_id = 1 or"));
        assertTrue(criteria("discrepancyNoteBean.resolutionStatus", "21", variables).contains("resolution_status_id = 1 or"));
        assertTrue(variables.isEmpty());
        String sql = criteria("discrepancyNoteBean.resolutionStatus", "4", variables);
        assertTrue(sql, sql.contains("dn.resolution_status_id = ?"));
        assertEquals(Integer.valueOf(4), variables.get(1));
    }
}
