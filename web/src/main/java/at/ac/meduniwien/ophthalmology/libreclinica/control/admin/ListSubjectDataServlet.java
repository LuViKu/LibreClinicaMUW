/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.control.admin;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Status;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudySubjectBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.SubjectBean;
import at.ac.meduniwien.ophthalmology.libreclinica.control.core.SecureController;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.login.UserAccountDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudySubjectDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.ListSubjectFilter;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.ListSubjectSort;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.SubjectDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.i18n.core.LocaleResolver;
import at.ac.meduniwien.ophthalmology.libreclinica.i18n.util.I18nFormatUtil;
import at.ac.meduniwien.ophthalmology.libreclinica.i18n.util.ResourceBundleProvider;
import at.ac.meduniwien.ophthalmology.libreclinica.view.Page;
import at.ac.meduniwien.ophthalmology.libreclinica.web.InsufficientPermissionException;
import at.ac.meduniwien.ophthalmology.libreclinica.web.datatable.DataTableRequest;
import at.ac.meduniwien.ophthalmology.libreclinica.web.datatable.DataTableResponse;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * DataTables.net server-side JSON endpoint for the admin "List
 * Subjects" page. Phase B.4 jmesa PR 4b — replaces the server-rendered
 * HTML blob that the deleted {@code ListSubjectTableFactory} dumped
 * into the page.
 *
 * <p>Companion to {@link ListSubjectServlet} (still renders the JSP
 * shell). Both extend {@link SecureController} to share the
 * sysadmin-only access gate.
 */
public class ListSubjectDataServlet extends SecureController {

    private static final long serialVersionUID = 1L;

    /** Hard cap on page size — DoS guard against a malicious client. */
    private static final int MAX_PAGE_LENGTH = 500;

    /** Whitelisted sortable / searchable column keys (must match JSP DataTables init). */
    private static final List<String> COLUMN_WHITELIST = Arrays.asList(
            "subject.uniqueIdentifier",
            "studySubjectIdAndStudy",
            "subject.gender",
            "subject.createdDate",
            "subject.owner",
            "subject.updatedDate",
            "subject.updater",
            "subject.status");

    private Locale locale;

    @Override
    protected void mayProceed() throws InsufficientPermissionException {
        locale = LocaleResolver.getLocale(request);
        if (!ub.isSysAdmin()) {
            addPageMessage(respage.getString("no_have_correct_privilege_current_study")
                    + respage.getString("change_study_contact_sysadmin"));
            throw new InsufficientPermissionException(Page.ADMIN_SYSTEM_SERVLET,
                    resexception.getString("not_admin"), "1");
        }
    }

    @Override
    protected void processRequest() throws Exception {
        ResourceBundle resformat = ResourceBundleProvider.getFormatBundle(locale);
        String dateFormatPattern = resformat.getString("date_format_string");
        SimpleDateFormat dateFormat = new SimpleDateFormat(dateFormatPattern);

        SubjectDAO subjectDao = new SubjectDAO(sm.getDataSource());
        StudySubjectDAO studySubjectDao = new StudySubjectDAO(sm.getDataSource());
        StudyDAO studyDao = new StudyDAO(sm.getDataSource());
        UserAccountDAO userAccountDao = new UserAccountDAO(sm.getDataSource());

        DataTableRequest dt = DataTableRequest.from(request);
        int length = Math.min(dt.getLength(), MAX_PAGE_LENGTH);
        if (length <= 0) {
            length = MAX_PAGE_LENGTH;
        }

        ListSubjectFilter filter = buildFilter(dt, dateFormatPattern);
        ListSubjectSort sort = buildSort(dt);

        Integer totalRows = subjectDao.getCountWithFilter(new ListSubjectFilter(dateFormatPattern), currentStudy);
        if (totalRows == null) {
            totalRows = 0;
        }
        Integer filteredRows = subjectDao.getCountWithFilter(filter, currentStudy);
        if (filteredRows == null) {
            filteredRows = 0;
        }

        int rowStart = Math.max(dt.getStart(), 0);
        int rowEnd = rowStart + length;
        Collection<SubjectBean> page = subjectDao.getWithFilterAndSort(
                currentStudy, filter, sort, rowStart, rowEnd);

        List<ListSubjectRow> rows = new ArrayList<>(page.size());
        for (SubjectBean subject : page) {
            ListSubjectRow row = new ListSubjectRow();
            row.id = subject.getId();
            row.uniqueIdentifier = subject.getUniqueIdentifier();
            // SubjectBean.getGender() is a primitive char (cannot be null);
            // the unset/legacy value is '\0' or ' ' depending on the
            // schema migration that touched the row.
            char gender = subject.getGender();
            row.gender = (gender == '\0' || gender == ' ') ? "" : String.valueOf(gender);

            UserAccountBean owner = userAccountDao.findByPK(subject.getOwnerId());
            row.owner = owner == null ? "" : owner.getName();

            UserAccountBean updater = subject.getUpdaterId() == 0
                    ? null : userAccountDao.findByPK(subject.getUpdaterId());
            row.updater = updater == null ? "" : updater.getName();

            row.createdDate = subject.getCreatedDate() == null
                    ? "" : I18nFormatUtil.getDateFormat(locale).format(subject.getCreatedDate());
            row.updatedDate = subject.getUpdatedDate() == null
                    ? "" : I18nFormatUtil.getDateFormat(locale).format(subject.getUpdatedDate());

            Status status = subject.getStatus();
            row.status = status == null ? "" : status.getName();
            row.statusDeleted = status == Status.DELETED;

            StringBuilder studySubjectIdAndStudy = new StringBuilder();
            List<StudySubjectBean> studySubjects = studySubjectDao.findAllBySubjectId(subject.getId());
            for (StudySubjectBean studySubjectBean : studySubjects) {
                StudyBean study = (StudyBean) studyDao.findByPK(studySubjectBean.getStudyId());
                if (studySubjectIdAndStudy.length() > 0) {
                    studySubjectIdAndStudy.append(",");
                }
                studySubjectIdAndStudy.append(study.getIdentifier()).append("-").append(studySubjectBean.getLabel());
            }
            row.studySubjectIdAndStudy = studySubjectIdAndStudy.toString();

            rows.add(row);
        }

        DataTableResponse<ListSubjectRow> payload =
                DataTableResponse.success(dt.getDraw(), totalRows, filteredRows, rows);

        response.setContentType("application/json;charset=UTF-8");
        try (OutputStream out = response.getOutputStream()) {
            new ObjectMapper().writeValue(out, payload);
        }
    }

    private ListSubjectFilter buildFilter(DataTableRequest dt, String dateFormatPattern) {
        ListSubjectFilter filter = new ListSubjectFilter(dateFormatPattern);

        String global = dt.getGlobalSearch();
        if (global != null && !global.isEmpty()) {
            // Legacy jmesa-era behaviour: the global box searched the
            // unique-identifier column. Preserve that to keep admin
            // muscle-memory intact.
            filter.addFilter("subject.uniqueIdentifier", global);
        }

        for (DataTableRequest.Column col : dt.getColumns()) {
            if (!col.isSearchable()) continue;
            if (col.getSearchValue() == null || col.getSearchValue().isEmpty()) continue;
            if (!COLUMN_WHITELIST.contains(col.getData())) continue;
            filter.addFilter(col.getData(), col.getSearchValue());
        }
        return filter;
    }

    private ListSubjectSort buildSort(DataTableRequest dt) {
        ListSubjectSort sort = new ListSubjectSort();
        String sortColumn = dt.getSortColumnName();
        if (sortColumn != null && COLUMN_WHITELIST.contains(sortColumn)) {
            sort.addSort(sortColumn, dt.getSortDirection());
        } else {
            sort.addSort("subject.createdDate", "desc");
        }
        return sort;
    }

    @Override
    protected String getAdminServlet() {
        return ub.isSysAdmin() ? SecureController.ADMIN_SERVLET_CODE : "";
    }

    /**
     * Row shape emitted in the JSON response. Field names align with
     * the {@code data:} keys declared in the JSP DataTables init.
     */
    public static final class ListSubjectRow {
        public Integer id;
        public String uniqueIdentifier;
        public String studySubjectIdAndStudy;
        public String gender;
        public String createdDate;
        public String owner;
        public String updatedDate;
        public String updater;
        public String status;
        /** When true, the actions column renders Restore instead of Edit/Remove. */
        public boolean statusDeleted;
    }
}
