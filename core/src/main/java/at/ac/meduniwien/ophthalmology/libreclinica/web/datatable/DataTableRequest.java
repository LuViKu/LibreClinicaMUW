/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.web.datatable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Parsed view of a DataTables.net server-side-processing AJAX request.
 *
 * <p>Phase B.4 jmesa PR 2 introduces this class as part of the jmesa
 * replacement. Cohort 1 (admin statistics) uses the client-side mode and
 * does not need it; cohorts 2–4 (list+search) bind one of these per
 * AJAX call.
 *
 * <p>The request shape is documented at
 * <a href="https://datatables.net/manual/server-side">DataTables server-side</a>.
 * Relevant keys parsed here:
 * <ul>
 *   <li>{@code draw} — round-trip identifier, echoed back unchanged</li>
 *   <li>{@code start} — zero-based row offset</li>
 *   <li>{@code length} — page size; {@code -1} means "all rows"</li>
 *   <li>{@code search[value]} — global search term</li>
 *   <li>{@code order[0][column]}, {@code order[0][dir]} — primary sort column index and direction</li>
 *   <li>{@code columns[N][data]}, {@code columns[N][searchable]}, {@code columns[N][search][value]} — per-column metadata</li>
 * </ul>
 */
public final class DataTableRequest {

    private final int draw;
    private final int start;
    private final int length;
    private final String globalSearch;
    private final int sortColumnIndex;
    private final String sortDirection;
    private final List<Column> columns;

    private DataTableRequest(int draw, int start, int length, String globalSearch,
            int sortColumnIndex, String sortDirection, List<Column> columns) {
        this.draw = draw;
        this.start = start;
        this.length = length;
        this.globalSearch = globalSearch;
        this.sortColumnIndex = sortColumnIndex;
        this.sortDirection = sortDirection;
        this.columns = Collections.unmodifiableList(columns);
    }

    /**
     * Parse a DataTables AJAX request. Missing or malformed fields default
     * to safe values (start=0, length=10, no sort, no search). The caller
     * should validate the resulting bounds against domain rules (e.g. cap
     * {@code length} to prevent denial-of-service via 10-million-row pulls).
     */
    public static DataTableRequest from(HttpServletRequest request) {
        int draw = parseIntOr(request.getParameter("draw"), 0);
        int start = parseIntOr(request.getParameter("start"), 0);
        int length = parseIntOr(request.getParameter("length"), 10);
        String search = request.getParameter("search[value]");
        if (search == null) {
            search = "";
        }
        int sortColumn = parseIntOr(request.getParameter("order[0][column]"), -1);
        String sortDir = request.getParameter("order[0][dir]");
        if (!"asc".equals(sortDir) && !"desc".equals(sortDir)) {
            sortDir = "asc";
        }

        List<Column> columns = new ArrayList<>();
        for (int i = 0; ; i++) {
            String data = request.getParameter("columns[" + i + "][data]");
            if (data == null) {
                break;
            }
            String searchable = request.getParameter("columns[" + i + "][searchable]");
            String colSearch = request.getParameter("columns[" + i + "][search][value]");
            columns.add(new Column(i, data,
                    "true".equalsIgnoreCase(searchable),
                    colSearch == null ? "" : colSearch));
        }

        return new DataTableRequest(draw, start, length, search,
                sortColumn, sortDir, columns);
    }

    private static int parseIntOr(String value, int fallback) {
        if (value == null || value.isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public int getDraw() { return draw; }
    public int getStart() { return start; }
    public int getLength() { return length; }
    public String getGlobalSearch() { return globalSearch; }
    public int getSortColumnIndex() { return sortColumnIndex; }
    public String getSortDirection() { return sortDirection; }
    public List<Column> getColumns() { return columns; }

    /**
     * Returns the {@code data} key of the column at {@link #getSortColumnIndex},
     * or {@code null} if no sort is requested or the index is out of range.
     */
    public String getSortColumnName() {
        if (sortColumnIndex < 0 || sortColumnIndex >= columns.size()) {
            return null;
        }
        return columns.get(sortColumnIndex).getData();
    }

    /** A single column entry inside the DataTables request. */
    public static final class Column {
        private final int index;
        private final String data;
        private final boolean searchable;
        private final String searchValue;

        Column(int index, String data, boolean searchable, String searchValue) {
            this.index = index;
            this.data = data;
            this.searchable = searchable;
            this.searchValue = searchValue;
        }

        public int getIndex() { return index; }
        public String getData() { return data; }
        public boolean isSearchable() { return searchable; }
        public String getSearchValue() { return searchValue; }
    }
}
