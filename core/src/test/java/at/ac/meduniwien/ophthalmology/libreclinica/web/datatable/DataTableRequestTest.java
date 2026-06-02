/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.web.datatable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.junit.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

/**
 * Unit characterisation for the {@link DataTableRequest} parser. Pins the
 * DataTables.net protocol mapping so cohort PRs that add new server-side
 * tables can rely on the parsed view without re-reading the source.
 */
public class DataTableRequestTest {

    private static HttpServletRequest request(final Map<String, String> params) {
        HttpServletRequest req = org.mockito.Mockito.mock(HttpServletRequest.class);
        org.mockito.Mockito.when(req.getParameter(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(new Answer<String>() {
                    @Override
                    public String answer(InvocationOnMock invocation) {
                        return params.get((String) invocation.getArguments()[0]);
                    }
                });
        return req;
    }

    @Test
    public void parsesMinimalRequest() {
        Map<String, String> p = new HashMap<>();
        p.put("draw", "1");
        p.put("start", "0");
        p.put("length", "10");
        DataTableRequest r = DataTableRequest.from(request(p));
        assertEquals(1, r.getDraw());
        assertEquals(0, r.getStart());
        assertEquals(10, r.getLength());
        assertEquals("", r.getGlobalSearch());
        assertEquals(-1, r.getSortColumnIndex());
        assertEquals("asc", r.getSortDirection());
        assertTrue(r.getColumns().isEmpty());
        assertNull(r.getSortColumnName());
    }

    @Test
    public void parsesGlobalSearch() {
        Map<String, String> p = new HashMap<>();
        p.put("draw", "5");
        p.put("start", "20");
        p.put("length", "10");
        p.put("search[value]", "smith");
        DataTableRequest r = DataTableRequest.from(request(p));
        assertEquals("smith", r.getGlobalSearch());
        assertEquals(20, r.getStart());
    }

    @Test
    public void parsesSortDirectionAndColumn() {
        Map<String, String> p = new HashMap<>();
        p.put("draw", "1");
        p.put("order[0][column]", "2");
        p.put("order[0][dir]", "desc");
        p.put("columns[0][data]", "name");
        p.put("columns[1][data]", "enrolled");
        p.put("columns[2][data]", "percentage");
        DataTableRequest r = DataTableRequest.from(request(p));
        assertEquals(2, r.getSortColumnIndex());
        assertEquals("desc", r.getSortDirection());
        assertEquals("percentage", r.getSortColumnName());
        assertEquals(3, r.getColumns().size());
    }

    @Test
    public void rejectsInvalidSortDirection() {
        Map<String, String> p = new HashMap<>();
        p.put("draw", "1");
        p.put("order[0][dir]", "'; DROP TABLE study; --");
        DataTableRequest r = DataTableRequest.from(request(p));
        assertEquals("asc", r.getSortDirection());
    }

    @Test
    public void defaultsAreSafeOnGarbageInput() {
        Map<String, String> p = new HashMap<>();
        p.put("draw", "not-a-number");
        p.put("start", "");
        p.put("length", null);
        DataTableRequest r = DataTableRequest.from(request(p));
        assertEquals(0, r.getDraw());
        assertEquals(0, r.getStart());
        assertEquals(10, r.getLength());
    }

    @Test
    public void parsesColumnSearchValues() {
        Map<String, String> p = new HashMap<>();
        p.put("draw", "1");
        p.put("columns[0][data]", "name");
        p.put("columns[0][searchable]", "true");
        p.put("columns[0][search][value]", "A");
        p.put("columns[1][data]", "id");
        p.put("columns[1][searchable]", "false");
        DataTableRequest r = DataTableRequest.from(request(p));
        assertEquals(2, r.getColumns().size());
        assertEquals("name", r.getColumns().get(0).getData());
        assertTrue(r.getColumns().get(0).isSearchable());
        assertEquals("A", r.getColumns().get(0).getSearchValue());
        assertEquals("id", r.getColumns().get(1).getData());
        assertTrue(!r.getColumns().get(1).isSearchable());
    }
}
