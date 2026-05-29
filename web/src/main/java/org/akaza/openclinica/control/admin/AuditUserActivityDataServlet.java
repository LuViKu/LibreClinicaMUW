/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package org.akaza.openclinica.control.admin;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import org.akaza.openclinica.control.SpringServletAccess;
import org.akaza.openclinica.control.core.SecureController;
import org.akaza.openclinica.dao.hibernate.AuditUserLoginDao;
import org.akaza.openclinica.dao.hibernate.AuditUserLoginFilter;
import org.akaza.openclinica.dao.hibernate.AuditUserLoginSort;
import org.akaza.openclinica.domain.technicaladmin.AuditUserLoginBean;
import org.akaza.openclinica.domain.technicaladmin.LoginStatus;
import org.akaza.openclinica.i18n.core.LocaleResolver;
import org.akaza.openclinica.view.Page;
import org.akaza.openclinica.web.InsufficientPermissionException;
import org.akaza.openclinica.web.datatable.DataTableRequest;
import org.akaza.openclinica.web.datatable.DataTableResponse;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * DataTables.net server-side-processing JSON endpoint for the
 * audit-user-activity table. Phase B.4 jmesa PR 4a — replaces the
 * server-rendered HTML blob that the deleted {@code
 * AuditUserLoginTableFactory} used to dump into the page.
 *
 * <p>{@link AuditUserActivityServlet} continues to render the JSP
 * (now with an empty {@code <table>} skeleton). This servlet handles
 * the AJAX requests DataTables fires on initial load and on every
 * sort/filter/page-change.
 *
 * <p>Both servlets extend {@link SecureController} to share the
 * sysadmin-only access gate; the gate logic lives in the
 * {@link #mayProceed()} override.
 */
public class AuditUserActivityDataServlet extends SecureController {

    private static final long serialVersionUID = 1L;

    /** Hard cap on page size so a malicious client can't pull millions of rows. */
    private static final int MAX_PAGE_LENGTH = 500;

    /** Columns the JSP DataTables init declares — sort whitelist is keyed off these. */
    private static final List<String> SORT_WHITELIST = java.util.Arrays.asList(
            "userName", "loginAttemptDate", "loginStatus", "details");

    private AuditUserLoginDao auditUserLoginDao;
    private final ObjectMapper jsonMapper = new ObjectMapper();
    private Locale locale;

    @Override
    protected void mayProceed() throws InsufficientPermissionException {
        locale = LocaleResolver.getLocale(request);
        if (!ub.isSysAdmin()) {
            addPageMessage(respage.getString("no_have_correct_privilege_current_study")
                    + respage.getString("change_study_contact_sysadmin"));
            throw new InsufficientPermissionException(Page.MENU_SERVLET,
                    resexception.getString("you_may_not_perform_administrative_functions"), "1");
        }
    }

    @Override
    protected void processRequest() throws Exception {
        DataTableRequest dt = DataTableRequest.from(request);
        int length = Math.min(dt.getLength(), MAX_PAGE_LENGTH);
        if (length <= 0) {
            length = MAX_PAGE_LENGTH;
        }

        AuditUserLoginFilter filter = buildFilter(dt);
        AuditUserLoginSort sort = buildSort(dt);

        int totalRows = getAuditUserLoginDao().getCountWithFilter(new AuditUserLoginFilter());
        int filteredRows = getAuditUserLoginDao().getCountWithFilter(filter);

        int rowStart = Math.max(dt.getStart(), 0);
        int rowEnd = rowStart + length;
        List<AuditUserLoginBean> page = new ArrayList<>(
                getAuditUserLoginDao().getWithFilterAndSort(filter, sort, rowStart, rowEnd));

        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        dateFormat.setTimeZone(TimeZone.getDefault());

        List<AuditUserLoginRow> rows = new ArrayList<>(page.size());
        for (AuditUserLoginBean bean : page) {
            AuditUserLoginRow row = new AuditUserLoginRow();
            row.userName = bean.getUserName();
            row.loginAttemptDate = bean.getLoginAttemptDate() == null
                    ? "" : dateFormat.format(bean.getLoginAttemptDate());
            LoginStatus status = bean.getLoginStatus();
            row.loginStatus = status == null ? "" : status.toString();
            row.loginStatusKey = status == null ? "" : status.name();
            row.details = bean.getDetails();
            row.userAccountId = bean.getUserAccountId();
            rows.add(row);
        }

        DataTableResponse<AuditUserLoginRow> payload =
                DataTableResponse.success(dt.getDraw(), totalRows, filteredRows, rows);

        response.setContentType("application/json;charset=UTF-8");
        try (OutputStream out = response.getOutputStream()) {
            jsonMapper.writeValue(out, payload);
        }
    }

    /**
     * Build the Hibernate filter command from the DataTables global
     * + per-column search values. Only the columns in {@link #SORT_WHITELIST}
     * are honoured — anything else is silently ignored (defence against
     * a client crafting unexpected column names).
     */
    private AuditUserLoginFilter buildFilter(DataTableRequest dt) {
        AuditUserLoginFilter filter = new AuditUserLoginFilter();

        String global = dt.getGlobalSearch();
        if (global != null && !global.isEmpty()) {
            // The legacy jmesa-era filter accepted a global value via the
            // 'userName' channel (free-text contains-match). Preserve that
            // behaviour so admin search habits stay intact.
            filter.addFilter("userName", global);
        }

        for (DataTableRequest.Column col : dt.getColumns()) {
            if (!col.isSearchable() || col.getSearchValue() == null || col.getSearchValue().isEmpty()) {
                continue;
            }
            if (!SORT_WHITELIST.contains(col.getData())) {
                continue;
            }
            filter.addFilter(col.getData(), col.getSearchValue());
        }
        return filter;
    }

    /**
     * Build the Hibernate sort command. Column names are whitelisted —
     * an unknown column name defaults to the legacy "loginAttemptDate desc"
     * ordering rather than passing arbitrary input to the criteria.
     */
    private AuditUserLoginSort buildSort(DataTableRequest dt) {
        AuditUserLoginSort sort = new AuditUserLoginSort();
        String sortColumn = dt.getSortColumnName();
        if (sortColumn != null && SORT_WHITELIST.contains(sortColumn)) {
            sort.addSort(sortColumn, dt.getSortDirection());
        } else {
            sort.addSort("loginAttemptDate", "desc");
        }
        return sort;
    }

    @Override
    protected String getAdminServlet() {
        return SecureController.ADMIN_SERVLET_CODE;
    }

    public AuditUserLoginDao getAuditUserLoginDao() {
        if (auditUserLoginDao == null) {
            auditUserLoginDao = (AuditUserLoginDao) SpringServletAccess
                    .getApplicationContext(context).getBean("auditUserLoginDao");
        }
        return auditUserLoginDao;
    }

    /**
     * Row shape emitted in the {@code data} array of the
     * {@link DataTableResponse}. Jackson serialises public fields by
     * default — names match what the JSP DataTables init expects under
     * each column's {@code data} key.
     */
    public static final class AuditUserLoginRow {
        public String userName;
        public String loginAttemptDate;
        public String loginStatus;
        /** The enum {@code name()}, used by the JSP renderer for a CSS-class hook. */
        public String loginStatusKey;
        public String details;
        public Integer userAccountId;
    }
}
