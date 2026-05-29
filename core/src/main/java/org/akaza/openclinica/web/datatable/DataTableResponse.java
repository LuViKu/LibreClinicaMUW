/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package org.akaza.openclinica.web.datatable;

import java.util.Collections;
import java.util.List;

/**
 * DataTables.net server-side-processing response shape. JSON-serialised
 * by Jackson (mapped from the field names directly — no annotations
 * needed because the names match the protocol verbatim).
 *
 * <p>Phase B.4 jmesa PR 2 — see {@link DataTableRequest} for the request
 * side. Cohorts 2–4 return one of these per AJAX call; cohort 1 uses
 * the client-side mode and does not need it.
 */
public final class DataTableResponse<T> {

    private final int draw;
    private final long recordsTotal;
    private final long recordsFiltered;
    private final List<T> data;
    private final String error;

    private DataTableResponse(int draw, long recordsTotal, long recordsFiltered,
            List<T> data, String error) {
        this.draw = draw;
        this.recordsTotal = recordsTotal;
        this.recordsFiltered = recordsFiltered;
        this.data = data == null ? Collections.emptyList() : data;
        this.error = error;
    }

    /**
     * Standard success response.
     *
     * @param draw the {@code draw} value from the request (must be echoed unchanged)
     * @param recordsTotal total row count in the underlying dataset, before any filter
     * @param recordsFiltered row count after the global + per-column search filter
     * @param data the page of rows for the current request (length ≤ request.length)
     */
    public static <T> DataTableResponse<T> success(int draw, long recordsTotal,
            long recordsFiltered, List<T> data) {
        return new DataTableResponse<>(draw, recordsTotal, recordsFiltered, data, null);
    }

    /**
     * Error response. {@link #data} is empty; both record counts are zero.
     * DataTables renders {@code error} in the table chrome.
     */
    public static <T> DataTableResponse<T> error(int draw, String message) {
        return new DataTableResponse<>(draw, 0, 0, Collections.emptyList(), message);
    }

    public int getDraw() { return draw; }
    public long getRecordsTotal() { return recordsTotal; }
    public long getRecordsFiltered() { return recordsFiltered; }
    public List<T> getData() { return data; }
    public String getError() { return error; }
}
