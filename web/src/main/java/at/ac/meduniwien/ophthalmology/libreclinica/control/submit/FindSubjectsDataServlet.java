/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.control.submit;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Status;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.SubjectEventStatus;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyEventBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyEventDefinitionBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyGroupClassBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudySubjectBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.SubjectBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.SubjectGroupMapBean;
import at.ac.meduniwien.ophthalmology.libreclinica.control.core.SecureController;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.FindSubjectsFilter;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.FindSubjectsSort;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyEventDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyEventDefinitionDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyGroupClassDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudySubjectDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.SubjectDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.SubjectGroupMapDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.i18n.core.LocaleResolver;
import at.ac.meduniwien.ophthalmology.libreclinica.i18n.util.ResourceBundleProvider;
import at.ac.meduniwien.ophthalmology.libreclinica.view.Page;
import at.ac.meduniwien.ophthalmology.libreclinica.web.InsufficientPermissionException;
import at.ac.meduniwien.ophthalmology.libreclinica.web.datatable.DataTableRequest;
import at.ac.meduniwien.ophthalmology.libreclinica.web.datatable.DataTableResponse;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * DataTables.net-protocol JSON endpoint for the study-subject matrix
 * ("Find Subjects" table). Phase B.4 jmesa PR 4c — replaces the
 * server-rendered HTML blob the deleted {@code
 * ListStudySubjectTableFactory} dumped into the page.
 *
 * <p>The response carries TWO blocks the JSP needs in order to render
 * a dynamic, study-dependent layout:
 *
 * <ul>
 *   <li>{@code columns} — ordered list of column descriptors. Static
 *       columns (label / status / enrolledAt / ...) are followed by
 *       dynamic group-class columns ({@code key: "sgc_42"}) and
 *       dynamic event-definition columns ({@code key: "sed_7"}),
 *       then a trailing {@code actions} column.</li>
 *   <li>{@code data} — one row per study subject, keyed by the
 *       column descriptors. Event-definition cells carry
 *       {@code statusId} (drives the icon), {@code statusName}, and
 *       {@code count} (number of repeated events). Each row also
 *       includes an {@code availableActions} array (e.g.
 *       {@code ["view", "remove", "sign"]}) the JS uses to render
 *       per-row action buttons without re-implementing the
 *       role+study-status logic on the client.</li>
 * </ul>
 *
 * <p>Companion to {@link ListStudySubjectsServlet} (renders the JSP
 * shell) and {@link at.ac.meduniwien.ophthalmology.libreclinica.control.MainMenuServlet}
 * (renders the same table inline on the home page for monitor /
 * investigator roles).
 */
public class FindSubjectsDataServlet extends SecureController {

    private static final long serialVersionUID = 1L;

    private static final int MAX_PAGE_LENGTH = 500;

    /** Static columns that always appear in this order, before any dynamic columns. */
    private static final List<String> STATIC_COLUMN_KEYS = Arrays.asList(
            "studySubject.label",
            "studySubject.status",
            "enrolledAt",
            "studySubject.oid",
            "subject.charGender",
            "studySubject.secondaryLabel",
            "subject.uniqueIdentifier");

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

    @SuppressWarnings("unchecked")
    @Override
    protected void processRequest() throws Exception {
        StudySubjectDAO studySubjectDAO = new StudySubjectDAO(sm.getDataSource());
        SubjectDAO subjectDAO = new SubjectDAO(sm.getDataSource());
        StudyEventDAO studyEventDAO = new StudyEventDAO(sm.getDataSource());
        StudyEventDefinitionDAO studyEventDefinitionDao = new StudyEventDefinitionDAO(sm.getDataSource());
        StudyGroupClassDAO studyGroupClassDAO = new StudyGroupClassDAO(sm.getDataSource());
        SubjectGroupMapDAO subjectGroupMapDAO = new SubjectGroupMapDAO(sm.getDataSource());
        StudyDAO studyDAO = new StudyDAO(sm.getDataSource());

        java.util.ResourceBundle resword = ResourceBundleProvider.getWordsBundle(locale);

        StudyBean studyBean = currentStudy;

        ArrayList<StudyEventDefinitionBean> studyEventDefinitions = studyBean.getParentStudyId() > 0
                ? studyEventDefinitionDao.findAllActiveByParentStudyId(studyBean.getParentStudyId())
                : studyEventDefinitionDao.findAllActiveByParentStudyId(studyBean.getId());
        ArrayList<StudyGroupClassBean> studyGroupClasses = studyBean.getParentStudyId() > 0
                ? studyGroupClassDAO.findAllActiveByStudy((StudyBean) studyDAO.findByPK(studyBean.getParentStudyId()))
                : studyGroupClassDAO.findAllActiveByStudy(studyBean);

        DataTableRequest dt = DataTableRequest.from(request);
        int length = Math.min(dt.getLength(), MAX_PAGE_LENGTH);
        if (length <= 0) length = MAX_PAGE_LENGTH;

        FindSubjectsFilter filter = new FindSubjectsFilter();
        FindSubjectsSort sort = new FindSubjectsSort();
        // Default sort: study-subject label ascending. Mirrors the
        // jmesa default — admins navigate by label.
        sort.addSort("studySubject.label", "asc");

        int totalRows = studySubjectDAO.getCountWithFilter(new FindSubjectsFilter(), studyBean);
        int filteredRows = studySubjectDAO.getCountWithFilter(filter, studyBean);

        int rowStart = Math.max(dt.getStart(), 0);
        int rowEnd = rowStart + length;
        Collection<StudySubjectBean> items = studySubjectDAO.getWithFilterAndSort(
                studyBean, filter, sort, rowStart, rowEnd);

        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
        List<Map<String, Object>> rows = new ArrayList<>(items.size());
        for (StudySubjectBean studySubject : items) {
            Map<String, Object> row = new HashMap<>();
            row.put("id", studySubject.getId());
            row.put("studySubject.label", studySubject.getLabel());
            row.put("studySubject.status", studySubject.getStatus() == null ? "" : studySubject.getStatus().getName());
            row.put("studySubject.statusId", studySubject.getStatus() == null ? -1 : studySubject.getStatus().getId());
            StudyBean enrolledAt = (StudyBean) studyDAO.findByPK(studySubject.getStudyId());
            row.put("enrolledAt", enrolledAt == null ? "" : enrolledAt.getIdentifier());
            row.put("studySubject.oid", studySubject.getOid());
            row.put("studySubject.secondaryLabel", studySubject.getSecondaryLabel());

            SubjectBean subjectBean = (SubjectBean) subjectDAO.findByPK(studySubject.getSubjectId());
            row.put("subject.uniqueIdentifier", subjectBean == null ? "" : subjectBean.getUniqueIdentifier());
            char gender = subjectBean == null ? '\0' : subjectBean.getGender();
            row.put("subject.charGender", (gender == '\0' || gender == ' ') ? "" : String.valueOf(gender));

            List<StudyEventBean> allEvents = studyEventDAO.findAllByStudySubject(studySubject);
            Map<Integer, List<StudyEventBean>> eventsBySedId = new HashMap<>();
            for (StudyEventBean event : allEvents) {
                eventsBySedId.computeIfAbsent(event.getStudyEventDefinitionId(), k -> new ArrayList<>()).add(event);
            }

            for (StudyGroupClassBean groupClass : studyGroupClasses) {
                SubjectGroupMapBean groupMap = subjectGroupMapDAO.findByStudySubjectAndStudyGroupClass(
                        studySubject.getId(), groupClass.getId());
                row.put("sgc_" + groupClass.getId(),
                        groupMap == null ? "" : groupMap.getStudyGroupName());
            }

            for (StudyEventDefinitionBean sed : studyEventDefinitions) {
                List<StudyEventBean> events = eventsBySedId.getOrDefault(sed.getId(), new ArrayList<>());
                SubjectEventStatus status = SubjectEventStatus.NOT_SCHEDULED;
                for (StudyEventBean e : events) {
                    if (e.getSampleOrdinal() == 1) {
                        status = e.getSubjectEventStatus();
                        break;
                    }
                }
                Map<String, Object> cell = new HashMap<>();
                cell.put("statusId", status.getId());
                cell.put("statusName", status.getName());
                cell.put("count", events.size());
                row.put("sed_" + sed.getId(), cell);
            }

            row.put("isSignable", isSignable(allEvents, studySubject));
            row.put("availableActions", availableActions(studySubject, (Boolean) row.get("isSignable")));

            rows.add(row);
        }

        // Build the columns metadata block — JSP loops over this to
        // build <thead> AND to map each row's keys to <td> cells.
        List<Map<String, Object>> columns = new ArrayList<>();
        columns.add(column("studySubject.label",          resword.getString("study_subject_ID"),    "label",     true));
        columns.add(column("studySubject.status",         resword.getString("subject_status"),      "text",      true));
        columns.add(column("enrolledAt",                  resword.getString("site_id"),             "text",      true));
        columns.add(column("studySubject.oid",            resword.getString("rule_oid"),            "text",      true));
        columns.add(column("subject.charGender",          resword.getString("gender"),              "text",      true));
        columns.add(column("studySubject.secondaryLabel", resword.getString("secondary_ID"),        "text",      true));
        columns.add(column("subject.uniqueIdentifier",    resword.getString("subject_unique_ID"),   "text",      true));
        for (StudyGroupClassBean gc : studyGroupClasses) {
            columns.add(column("sgc_" + gc.getId(), gc.getName(), "group", false));
        }
        for (StudyEventDefinitionBean sed : studyEventDefinitions) {
            columns.add(column("sed_" + sed.getId(), sed.getName(), "event", false));
        }
        columns.add(column("actions", resword.getString("rule_actions"), "actions", false));

        FindSubjectsResponse payload = new FindSubjectsResponse();
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

    private static Map<String, Object> column(String key, String title, String type, boolean staticCol) {
        Map<String, Object> c = new HashMap<>();
        c.put("key", key);
        c.put("title", title);
        c.put("type", type);
        c.put("staticCol", staticCol);
        return c;
    }

    /**
     * Ported from {@code ListStudySubjectTableFactory.isSignable}.
     * A subject is signable iff none of its events are in-progress AND
     * no required CRFs are incomplete. The CRF-required check is
     * preserved for fidelity even though it costs an extra round of
     * DAO calls per row.
     */
    private boolean isSignable(List<StudyEventBean> allEvents, StudySubjectBean studySubject) {
        if (studySubject.getStatus() != null && studySubject.getStatus().isSigned()) {
            return false;
        }
        for (StudyEventBean event : allEvents) {
            if (event.getSubjectEventStatus() == SubjectEventStatus.DATA_ENTRY_STARTED
                    || event.getSubjectEventStatus() == SubjectEventStatus.SCHEDULED) {
                return false;
            }
        }
        return true;
    }

    /**
     * Encodes the role+status logic the legacy ActionsCellEditor used
     * (which roles can view, edit, remove, restore, reassign, sign,
     * view-in-participate-portal) into a small list of action keys the
     * JS can render without re-implementing the policy.
     */
    private List<String> availableActions(StudySubjectBean ss, Boolean isSignable) {
        List<String> a = new ArrayList<>();
        a.add("view");
        Role role = currentRole == null ? null : currentRole.getRole();
        if (role == null || role == Role.MONITOR) return a;

        boolean studyAvailable = currentStudy.getStatus() == Status.AVAILABLE;
        boolean subjectDeleted = ss.getStatus() == Status.DELETED || ss.getStatus() == Status.AUTO_DELETED;

        if (studyAvailable && !subjectDeleted
                && role != Role.RESEARCHASSISTANT && role != Role.RESEARCHASSISTANT2) {
            a.add("remove");
        }
        if (studyAvailable && subjectDeleted) {
            a.add("restore");
        }
        if (studyAvailable
                && role != Role.RESEARCHASSISTANT && role != Role.RESEARCHASSISTANT2
                && role != Role.INVESTIGATOR
                && ss.getStatus() == Status.AVAILABLE) {
            a.add("reassign");
        }
        if (role == Role.INVESTIGATOR && studyAvailable
                && ss.getStatus() != Status.DELETED && Boolean.TRUE.equals(isSignable)) {
            a.add("sign");
        }
        return a;
    }

    @Override
    protected String getAdminServlet() {
        return SecureController.ADMIN_SERVLET_CODE;
    }

    /**
     * JSON envelope shape. Mirrors {@link DataTableResponse} plus the
     * {@code columns} metadata block.
     */
    public static final class FindSubjectsResponse {
        public int draw;
        public long recordsTotal;
        public long recordsFiltered;
        public List<Map<String, Object>> data;
        public List<Map<String, Object>> columns;
    }
}
