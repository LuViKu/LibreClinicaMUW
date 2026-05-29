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
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.akaza.openclinica.bean.admin.CRFBean;
import org.akaza.openclinica.bean.core.DataEntryStage;
import org.akaza.openclinica.bean.core.Role;
import org.akaza.openclinica.bean.core.Status;
import org.akaza.openclinica.bean.core.SubjectEventStatus;
import org.akaza.openclinica.bean.managestudy.EventDefinitionCRFBean;
import org.akaza.openclinica.bean.managestudy.StudyBean;
import org.akaza.openclinica.bean.managestudy.StudyEventBean;
import org.akaza.openclinica.bean.managestudy.StudyEventDefinitionBean;
import org.akaza.openclinica.bean.managestudy.StudyGroupClassBean;
import org.akaza.openclinica.bean.managestudy.StudySubjectBean;
import org.akaza.openclinica.bean.submit.EventCRFBean;
import org.akaza.openclinica.bean.submit.SubjectGroupMapBean;
import org.akaza.openclinica.bean.submit.SubjectBean;
import org.akaza.openclinica.control.core.SecureController;
import org.akaza.openclinica.dao.admin.CRFDAO;
import org.akaza.openclinica.dao.managestudy.EventDefinitionCRFDAO;
import org.akaza.openclinica.dao.managestudy.ListEventsForSubjectFilter;
import org.akaza.openclinica.dao.managestudy.ListEventsForSubjectSort;
import org.akaza.openclinica.dao.managestudy.StudyDAO;
import org.akaza.openclinica.dao.managestudy.StudyEventDAO;
import org.akaza.openclinica.dao.managestudy.StudyEventDefinitionDAO;
import org.akaza.openclinica.dao.managestudy.StudyGroupClassDAO;
import org.akaza.openclinica.dao.managestudy.StudySubjectDAO;
import org.akaza.openclinica.dao.submit.EventCRFDAO;
import org.akaza.openclinica.dao.submit.SubjectDAO;
import org.akaza.openclinica.dao.submit.SubjectGroupMapDAO;
import org.akaza.openclinica.i18n.core.LocaleResolver;
import org.akaza.openclinica.i18n.util.ResourceBundleProvider;
import org.akaza.openclinica.view.Page;
import org.akaza.openclinica.web.InsufficientPermissionException;
import org.akaza.openclinica.web.datatable.DataTableRequest;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * DataTables-protocol JSON endpoint for the "Events for Subjects"
 * matrix (subjects × group-classes × CRFs of selected event
 * definition). Phase B.4 jmesa PR 6b (cohort 4b) — replaces the
 * server-rendered HTML blob the deleted {@code
 * ListEventsForSubjectTableFactory} dumped into {@code
 * listEventsForSubjects.jsp}.
 *
 * <p>Layout: one row per study subject. Each row carries an
 * {@code events: [...]} array — one entry per event instance of the
 * selected event definition for that subject. Subjects with no
 * events get a single synthetic NOT_SCHEDULED entry. Each event
 * entry carries the event status, event date, and per-CRF stage.
 *
 * <p>Columns: 4 static (label / status / siteId / gender) +
 * one {@code sgc_<id>} per StudyGroupClass + 2 static
 * (event.status / event.startDate) + one {@code crf_<id>} per
 * CRF of the selected event definition + actions.
 *
 * <p>Per-row {@code availableActions}: legacy ActionsCellEditor
 * policy preserved server-side: {@code view} always; {@code remove}
 * when study available + subject not deleted + not monitor;
 * {@code restore} when study available + subject deleted + not
 * monitor; {@code reassign} when study available + subject available
 * + not monitor + not investigator/RA/RA2.
 */
public class ListEventsForSubjectsDataServlet extends SecureController {

    private static final long serialVersionUID = 1L;
    private static final int MAX_PAGE_LENGTH = 500;

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
    @SuppressWarnings("unchecked")
    protected void processRequest() throws Exception {
        java.util.ResourceBundle resword = ResourceBundleProvider.getWordsBundle(locale);
        java.util.ResourceBundle resformat = ResourceBundleProvider.getFormatBundle(locale);
        SimpleDateFormat displayDateFormat = new SimpleDateFormat(resformat.getString("date_format_string"));

        DataTableRequest dt = DataTableRequest.from(request);
        int length = Math.min(dt.getLength(), MAX_PAGE_LENGTH);
        if (length <= 0) length = MAX_PAGE_LENGTH;
        int rowStart = Math.max(dt.getStart(), 0);
        int rowEnd = rowStart + length;

        int defId = -1;
        try { defId = Integer.parseInt(request.getParameter("defId")); }
        catch (NumberFormatException nfe) { /* fall through */ }
        if (defId <= 0) {
            response.sendError(400, "defId is required");
            return;
        }

        StudyDAO studyDAO = new StudyDAO(sm.getDataSource());
        StudySubjectDAO studySubjectDAO = new StudySubjectDAO(sm.getDataSource());
        SubjectDAO subjectDAO = new SubjectDAO(sm.getDataSource());
        StudyEventDAO studyEventDAO = new StudyEventDAO(sm.getDataSource());
        StudyEventDefinitionDAO studyEventDefinitionDAO = new StudyEventDefinitionDAO(sm.getDataSource());
        StudyGroupClassDAO studyGroupClassDAO = new StudyGroupClassDAO(sm.getDataSource());
        SubjectGroupMapDAO subjectGroupMapDAO = new SubjectGroupMapDAO(sm.getDataSource());
        EventCRFDAO eventCRFDAO = new EventCRFDAO(sm.getDataSource());
        EventDefinitionCRFDAO eventDefinitionCRFDAO = new EventDefinitionCRFDAO(sm.getDataSource());
        CRFDAO crfDAO = new CRFDAO(sm.getDataSource());

        StudyEventDefinitionBean selectedDef =
                (StudyEventDefinitionBean) studyEventDefinitionDAO.findByPK(defId);
        if (selectedDef == null || selectedDef.getId() <= 0) {
            // Legacy factory tolerated an empty bean; emit an empty
            // payload with just the static columns so the JSP shell
            // still renders.
            writeEmptyPayload(dt, defId, resword);
            return;
        }

        // Group classes drive sgc_<id> dynamic columns. Mirrored from
        // cohort 2c (FindSubjectsDataServlet).
        List<StudyGroupClassBean> studyGroupClasses = currentStudy.getParentStudyId() > 0
                ? studyGroupClassDAO.findAllActiveByStudy(
                        (StudyBean) studyDAO.findByPK(currentStudy.getParentStudyId()))
                : studyGroupClassDAO.findAllActiveByStudy(currentStudy);

        ArrayList<EventDefinitionCRFBean> eventDefinitionCrfs =
                eventDefinitionCRFDAO.findAllActiveByEventDefinitionId(selectedDef.getId());
        List<CRFBean> crfs = new ArrayList<>(eventDefinitionCrfs.size());
        for (EventDefinitionCRFBean edc : eventDefinitionCrfs) {
            crfs.add((CRFBean) crfDAO.findByPK(edc.getCrfId()));
        }

        ListEventsForSubjectFilter filter = new ListEventsForSubjectFilter(selectedDef.getId());
        ListEventsForSubjectSort sort = new ListEventsForSubjectSort();
        sort.addSort("studySubject.label", "asc");

        long totalRows = studySubjectDAO.getCountWithFilter(
                new ListEventsForSubjectFilter(selectedDef.getId()), currentStudy);
        long filteredRows = studySubjectDAO.getCountWithFilter(filter, currentStudy);

        Collection<StudySubjectBean> items = studySubjectDAO.getWithFilterAndSort(
                currentStudy, filter, sort, rowStart, rowEnd);

        boolean studyAvailable = currentStudy.getStatus() == Status.AVAILABLE;
        Role role = currentRole == null ? null : currentRole.getRole();

        List<Map<String, Object>> rows = new ArrayList<>(items.size());
        for (StudySubjectBean ss : items) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", ss.getId());
            row.put("studySubject.label", ss.getLabel());
            row.put("studySubject.status",
                    ss.getStatus() == null ? "" : ss.getStatus().getName());
            StudyBean enrolledAt = (StudyBean) studyDAO.findByPK(ss.getStudyId());
            row.put("enrolledAt", enrolledAt == null ? "" : enrolledAt.getIdentifier());

            SubjectBean subjectBean = (SubjectBean) subjectDAO.findByPK(ss.getSubjectId());
            char gender = subjectBean == null ? '\0' : subjectBean.getGender();
            row.put("subject.charGender",
                    (gender == '\0' || gender == ' ') ? "" : String.valueOf(gender));

            // Group-class mapping (cohort 2c-style).
            for (StudyGroupClassBean gc : studyGroupClasses) {
                SubjectGroupMapBean map = subjectGroupMapDAO
                        .findByStudySubjectAndStudyGroupClass(ss.getId(), gc.getId());
                row.put("sgc_" + gc.getId(),
                        map == null ? "" : map.getStudyGroupName());
            }

            // Build (crfId + eventId) → EventCRFBean lookup.
            List<EventCRFBean> allEventCrfs = eventCRFDAO.findAllByStudySubject(ss.getId());
            Map<String, EventCRFBean> ecbByCrfAndEvent = new HashMap<>();
            for (EventCRFBean ecb : allEventCrfs) {
                Object crfObj = crfDAO.findByVersionId(ecb.getCRFVersionId());
                if (crfObj == null) continue;
                CRFBean crf = (CRFBean) crfObj;
                ecbByCrfAndEvent.put(crf.getId() + "_" + ecb.getStudyEventId(), ecb);
            }

            List<StudyEventBean> events = studyEventDAO.findAllByDefinitionAndSubject(selectedDef, ss);

            List<Map<String, Object>> eventList = new ArrayList<>();
            if (events.isEmpty()) {
                eventList.add(buildSyntheticEvent(crfs));
            } else {
                for (StudyEventBean event : events) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    SubjectEventStatus status = event.getSubjectEventStatus();
                    entry.put("statusId", status == null ? -1 : status.getId());
                    entry.put("statusName", status == null ? "" : status.getName());
                    entry.put("startDate",
                            event.getDateStarted() == null ? "" :
                                    displayDateFormat.format(event.getDateStarted()));

                    Map<String, Object> crfCells = new LinkedHashMap<>();
                    for (CRFBean crf : crfs) {
                        Map<String, Object> cell = new LinkedHashMap<>();
                        EventCRFBean ecb = ecbByCrfAndEvent.get(crf.getId() + "_" + event.getId());
                        if (ecb == null) {
                            cell.put("stageId", DataEntryStage.UNCOMPLETED.getId());
                        } else {
                            DataEntryStage stage = ecb.getStage();
                            cell.put("stageId", stage == null
                                    ? DataEntryStage.UNCOMPLETED.getId() : stage.getId());
                        }
                        crfCells.put("crf_" + crf.getId(), cell);
                    }
                    entry.put("crfs", crfCells);
                    eventList.add(entry);
                }
            }
            row.put("events", eventList);
            row.put("availableActions", availableActions(ss, role, studyAvailable));
            rows.add(row);
        }

        List<Map<String, Object>> columns = new ArrayList<>();
        columns.add(col("studySubject.label",       resword.getString("study_subject_ID"),  "label",       null));
        columns.add(col("studySubject.status",      resword.getString("subject_status"),    "text",        null));
        columns.add(col("enrolledAt",               resword.getString("site_id"),           "text",        null));
        columns.add(col("subject.charGender",       resword.getString("gender"),            "text",        null));
        for (StudyGroupClassBean gc : studyGroupClasses) {
            columns.add(col("sgc_" + gc.getId(), gc.getName(), "group", null));
        }
        columns.add(col("event.status",             resword.getString("event_status"),      "eventStatus", null));
        columns.add(col("studySubject.createdDate", resword.getString("event_date"),        "eventDate",   null));
        for (CRFBean crf : crfs) {
            columns.add(col("crf_" + crf.getId(), crf.getName(), "eventCrf", crf.getId()));
        }
        columns.add(col("actions", resword.getString("rule_actions"), "actions", null));

        ListEventsForSubjectsResponse payload = new ListEventsForSubjectsResponse();
        payload.draw = dt.getDraw();
        payload.recordsTotal = totalRows;
        payload.recordsFiltered = filteredRows;
        payload.data = rows;
        payload.columns = columns;
        payload.defId = selectedDef.getId();

        response.setContentType("application/json;charset=UTF-8");
        try (OutputStream out = response.getOutputStream()) {
            new ObjectMapper().writeValue(out, payload);
        }
    }

    private void writeEmptyPayload(DataTableRequest dt, int defId,
                                   java.util.ResourceBundle resword) throws Exception {
        List<Map<String, Object>> columns = new ArrayList<>();
        columns.add(col("studySubject.label",       resword.getString("study_subject_ID"),  "label",       null));
        columns.add(col("studySubject.status",      resword.getString("subject_status"),    "text",        null));
        columns.add(col("enrolledAt",               resword.getString("site_id"),           "text",        null));
        columns.add(col("subject.charGender",       resword.getString("gender"),            "text",        null));
        columns.add(col("event.status",             resword.getString("event_status"),      "eventStatus", null));
        columns.add(col("studySubject.createdDate", resword.getString("event_date"),        "eventDate",   null));
        columns.add(col("actions",                  resword.getString("rule_actions"),      "actions",     null));

        ListEventsForSubjectsResponse payload = new ListEventsForSubjectsResponse();
        payload.draw = dt.getDraw();
        payload.recordsTotal = 0L;
        payload.recordsFiltered = 0L;
        payload.data = new ArrayList<>();
        payload.columns = columns;
        payload.defId = defId;

        response.setContentType("application/json;charset=UTF-8");
        try (OutputStream out = response.getOutputStream()) {
            new ObjectMapper().writeValue(out, payload);
        }
    }

    private Map<String, Object> buildSyntheticEvent(List<CRFBean> crfs) {
        Map<String, Object> entry = new LinkedHashMap<>();
        SubjectEventStatus ns = SubjectEventStatus.NOT_SCHEDULED;
        entry.put("statusId", ns.getId());
        entry.put("statusName", ns.getName());
        entry.put("startDate", "");
        Map<String, Object> crfCells = new LinkedHashMap<>();
        for (CRFBean crf : crfs) {
            Map<String, Object> cell = new LinkedHashMap<>();
            cell.put("stageId", DataEntryStage.UNCOMPLETED.getId());
            crfCells.put("crf_" + crf.getId(), cell);
        }
        entry.put("crfs", crfCells);
        return entry;
    }

    /**
     * Action-policy mirror of the legacy {@code ActionsCellEditor}.
     * Encoded server-side so the JS doesn't need to re-derive role/status.
     */
    private List<String> availableActions(StudySubjectBean ss, Role role, boolean studyAvailable) {
        List<String> a = new ArrayList<>();
        a.add("view");
        if (role == null || role == Role.MONITOR) return a;

        Status subjectStatus = ss.getStatus();
        boolean subjectDeleted = subjectStatus == Status.DELETED || subjectStatus == Status.AUTO_DELETED;

        if (studyAvailable && !subjectDeleted) {
            a.add("remove");
        }
        if (studyAvailable && subjectDeleted) {
            a.add("restore");
        }
        if (studyAvailable && !subjectDeleted
                && subjectStatus == Status.AVAILABLE
                && role != Role.INVESTIGATOR
                && role != Role.RESEARCHASSISTANT
                && role != Role.RESEARCHASSISTANT2) {
            a.add("reassign");
        }
        return a;
    }

    private static Map<String, Object> col(String key, String title, String type, Integer crfId) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("key", key);
        c.put("title", title);
        c.put("type", type);
        if (crfId != null) c.put("crfId", crfId);
        return c;
    }

    @Override
    protected String getAdminServlet() {
        return SecureController.ADMIN_SERVLET_CODE;
    }

    public static final class ListEventsForSubjectsResponse {
        public int draw;
        public long recordsTotal;
        public long recordsFiltered;
        public List<Map<String, Object>> data;
        public List<Map<String, Object>> columns;
        public int defId;
    }
}
