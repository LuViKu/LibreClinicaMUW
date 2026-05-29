/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package org.akaza.openclinica.control.managestudy;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.akaza.openclinica.bean.core.Role;
import org.akaza.openclinica.bean.core.Status;
import org.akaza.openclinica.bean.login.UserAccountBean;
import org.akaza.openclinica.bean.managestudy.StudySubjectBean;
import org.akaza.openclinica.bean.submit.SubjectBean;
import org.akaza.openclinica.control.core.SecureController;
import org.akaza.openclinica.dao.login.UserAccountDAO;
import org.akaza.openclinica.dao.managestudy.StudyAuditLogFilter;
import org.akaza.openclinica.dao.managestudy.StudyAuditLogSort;
import org.akaza.openclinica.dao.managestudy.StudySubjectDAO;
import org.akaza.openclinica.dao.submit.SubjectDAO;
import org.akaza.openclinica.i18n.core.LocaleResolver;
import org.akaza.openclinica.i18n.util.I18nFormatUtil;
import org.akaza.openclinica.i18n.util.ResourceBundleProvider;
import org.akaza.openclinica.view.Page;
import org.akaza.openclinica.web.InsufficientPermissionException;
import org.akaza.openclinica.web.datatable.DataTableRequest;
import org.akaza.openclinica.web.datatable.DataTableResponse;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * DataTables-protocol JSON endpoint for the "Study Audit Log" subject
 * table. Phase B.4 jmesa PR 6a (cohort 4a) — replaces the
 * server-rendered HTML blob the deleted {@code
 * StudyAuditLogTableFactory} dumped into {@code studyAuditLog.jsp}.
 *
 * <p>Pure 1-to-1 subject-row layout: 8 static columns
 * (label / secondaryLabel / oid / dateOfBirth / personId / createdBy
 * / status / actions). View-only actions; no role/status branching.
 *
 * <p>Companion to {@link StudyAuditLogServlet} (still renders the
 * JSP shell + sidebar + sysadmin/director-coordinator-monitor access
 * gate). Both extend {@link SecureController}.
 */
public class StudyAuditLogDataServlet extends SecureController {

    private static final long serialVersionUID = 1L;
    private static final int MAX_PAGE_LENGTH = 500;

    private static final List<String> COLUMN_WHITELIST = Arrays.asList(
            "studySubject.label",
            "studySubject.secondaryLabel",
            "studySubject.oid",
            "subject.dateOfBirth",
            "subject.uniqueIdentifier",
            "studySubject.owner",
            "studySubject.status");

    private Locale locale;

    @Override
    protected void mayProceed() throws InsufficientPermissionException {
        locale = LocaleResolver.getLocale(request);
        if (currentRole == null || currentRole.getRole() == null
                || currentRole.getRole().equals(Role.INVALID)) {
            throw new InsufficientPermissionException(Page.MENU_SERVLET,
                    resexception.getString("you_may_not_perform_administrative_functions"), "1");
        }
    }

    @Override
    protected void processRequest() throws Exception {
        java.util.ResourceBundle resword = ResourceBundleProvider.getWordsBundle(locale);
        java.util.ResourceBundle resformat = ResourceBundleProvider.getFormatBundle(locale);
        String dateFormatPattern = resformat.getString("date_format_string");

        DataTableRequest dt = DataTableRequest.from(request);
        int length = Math.min(dt.getLength(), MAX_PAGE_LENGTH);
        if (length <= 0) length = MAX_PAGE_LENGTH;
        int rowStart = Math.max(dt.getStart(), 0);
        int rowEnd = rowStart + length;

        StudySubjectDAO studySubjectDAO = new StudySubjectDAO(sm.getDataSource());
        SubjectDAO subjectDAO = new SubjectDAO(sm.getDataSource());
        UserAccountDAO userAccountDAO = new UserAccountDAO(sm.getDataSource());

        StudyAuditLogFilter filter = new StudyAuditLogFilter(dateFormatPattern);
        for (DataTableRequest.Column col : dt.getColumns()) {
            if (col.getSearchValue() == null || col.getSearchValue().isEmpty()) continue;
            if (COLUMN_WHITELIST.contains(col.getData())) {
                filter.addFilter(col.getData(), col.getSearchValue());
            }
        }

        StudyAuditLogSort sort = new StudyAuditLogSort();
        String sortColumn = dt.getSortColumnName();
        if (sortColumn != null && COLUMN_WHITELIST.contains(sortColumn)) {
            sort.addSort(sortColumn, dt.getSortDirection());
        } else {
            sort.addSort("studySubject.label", "asc");
        }

        long totalRows = studySubjectDAO.getCountWithFilter(
                new StudyAuditLogFilter(dateFormatPattern), currentStudy);
        long filteredRows = studySubjectDAO.getCountWithFilter(filter, currentStudy);

        Collection<StudySubjectBean> items = studySubjectDAO.getWithFilterAndSort(
                currentStudy, filter, sort, rowStart, rowEnd);

        List<Map<String, Object>> rows = new ArrayList<>(items.size());
        for (StudySubjectBean ss : items) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", ss.getId());
            row.put("studySubject.label", ss.getLabel());
            row.put("studySubject.secondaryLabel", ss.getSecondaryLabel());
            row.put("studySubject.oid", ss.getOid());

            SubjectBean subject = (SubjectBean) subjectDAO.findByPK(ss.getSubjectId());
            row.put("subject.dateOfBirth",
                    subject == null || subject.getDateOfBirth() == null ? "" :
                            I18nFormatUtil.getDateFormat(locale).format(subject.getDateOfBirth()));
            row.put("subject.uniqueIdentifier", subject == null ? "" : subject.getUniqueIdentifier());

            UserAccountBean owner = userAccountDAO.findByPK(ss.getOwnerId());
            row.put("studySubject.owner", owner == null ? "" : owner.getName());

            Status status = ss.getStatus();
            row.put("studySubject.status", status == null ? "" : status.getName());

            row.put("availableActions", new ArrayList<>(Arrays.asList("view")));
            rows.add(row);
        }

        List<Map<String, Object>> columns = new ArrayList<>();
        columns.add(col("studySubject.label",          resword.getString("study_subject_ID"),       "label"));
        columns.add(col("studySubject.secondaryLabel", resword.getString("secondary_subject_ID"),   "text"));
        columns.add(col("studySubject.oid",            resword.getString("study_subject_oid"),      "text"));
        columns.add(col("subject.dateOfBirth",         resword.getString("date_of_birth"),          "text"));
        columns.add(col("subject.uniqueIdentifier",    resword.getString("person_ID"),              "text"));
        columns.add(col("studySubject.owner",          resword.getString("created_by"),             "text"));
        columns.add(col("studySubject.status",         resword.getString("status"),                 "text"));
        columns.add(col("actions",                     resword.getString("actions"),                "actions"));

        StudyAuditLogResponse payload = new StudyAuditLogResponse();
        payload.draw = dt.getDraw();
        payload.recordsTotal = totalRows;
        payload.recordsFiltered = filteredRows;
        payload.data = rows;
        payload.columns = columns;

        response.setContentType("application/json;charset=UTF-8");
        try (OutputStream out = response.getOutputStream()) {
            new ObjectMapper().writeValue(out, payload);
        }
    }

    private static Map<String, Object> col(String key, String title, String type) {
        Map<String, Object> c = new HashMap<>();
        c.put("key", key);
        c.put("title", title);
        c.put("type", type);
        return c;
    }

    @Override
    protected String getAdminServlet() {
        return SecureController.ADMIN_SERVLET_CODE;
    }

    public static final class StudyAuditLogResponse {
        public int draw;
        public long recordsTotal;
        public long recordsFiltered;
        public List<Map<String, Object>> data;
        public List<Map<String, Object>> columns;
    }
}
