/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.admin.CRFBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.DataEntryStage;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.ResolutionStatus;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.SubjectEventStatus;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.DiscrepancyNoteBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.EventDefinitionCRFBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyEventBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyEventDefinitionBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudySubjectBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.EventCRFBean;
import at.ac.meduniwien.ophthalmology.libreclinica.control.core.SecureController;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.admin.CRFDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.DiscrepancyNoteDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.EventDefinitionCRFDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.ListDiscNotesForCRFFilter;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.ListDiscNotesForCRFSort;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyEventDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyEventDefinitionDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudySubjectDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.EventCRFDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.i18n.core.LocaleResolver;
import at.ac.meduniwien.ophthalmology.libreclinica.i18n.util.I18nFormatUtil;
import at.ac.meduniwien.ophthalmology.libreclinica.i18n.util.ResourceBundleProvider;
import at.ac.meduniwien.ophthalmology.libreclinica.view.Page;
import at.ac.meduniwien.ophthalmology.libreclinica.web.InsufficientPermissionException;
import at.ac.meduniwien.ophthalmology.libreclinica.web.datatable.DataTableRequest;

import com.fasterxml.jackson.databind.ObjectMapper;

import static at.ac.meduniwien.ophthalmology.libreclinica.core.util.ClassCastHelper.asHashSet;

/**
 * DataTables-protocol JSON endpoint for the "View all Discrepancy Notes"
 * (per-event-CRF) matrix. Phase B.4 jmesa PR 5c (cohort 3c) — replaces
 * the server-rendered HTML blob the deleted {@code
 * ListDiscNotesForCRFTableFactory} dumped into
 * {@code listDNotesForCRF.jsp}.
 *
 * <p>Layout: one row per study subject, with three static columns
 * (label / event status / event date) followed by one dynamic column
 * per active CRF in the selected {@link StudyEventDefinitionBean},
 * plus an actions column. Each row carries an {@code events} array —
 * one entry per event instance of the selected event definition for
 * that subject. The JS renderer iterates the array to display one
 * sub-cell per event in each event-status / event-date / CRF cell.
 *
 * <p>Subjects with zero events of the selected definition still get
 * a single synthetic event entry (status = NOT_SCHEDULED, all CRFs =
 * UNCOMPLETED). Mirrors the legacy factory's empty-fallback.
 *
 * <p>Required request param: {@code defId} = study-event-definition
 * id. The wrapping {@code ListDiscNotesForCRFServlet} forwards to the
 * live ViewNotes page when defId is missing, so this servlet treats
 * missing/zero defId as a 400.
 */
public class ListDiscNotesForCRFDataServlet extends SecureController {

    private static final long serialVersionUID = 1L;
    private static final String RESOLUTION_STATUS = "resolutionStatus";

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
        int length = dt.getLength();
        if (length <= 0) length = 500;
        int rowStart = Math.max(dt.getStart(), 0);
        int rowEnd = rowStart + length;

        String moduleStr = parseModule(request.getParameter("module"));
        int discNoteType = -1;
        try { discNoteType = Integer.parseInt(request.getParameter("type")); }
        catch (NumberFormatException nfe) { /* show all */ }

        int resolutionStatus = -1;
        try { resolutionStatus = Integer.parseInt(request.getParameter("resolutionStatus")); }
        catch (NumberFormatException nfe) { /* show all */ }

        Set<Integer> resolutionStatusIds = asHashSet(
                session.getAttribute(RESOLUTION_STATUS), Integer.class);

        int defId = -1;
        try { defId = Integer.parseInt(request.getParameter("defId")); }
        catch (NumberFormatException nfe) { /* fall through to 400 below */ }
        if (defId <= 0) {
            response.sendError(400, "defId is required");
            return;
        }

        StudyDAO studyDAO = new StudyDAO(sm.getDataSource());
        StudySubjectDAO studySubjectDAO = new StudySubjectDAO(sm.getDataSource());
        StudyEventDAO studyEventDAO = new StudyEventDAO(sm.getDataSource());
        StudyEventDefinitionDAO studyEventDefinitionDAO = new StudyEventDefinitionDAO(sm.getDataSource());
        EventCRFDAO eventCRFDAO = new EventCRFDAO(sm.getDataSource());
        EventDefinitionCRFDAO eventDefinitionCRFDAO = new EventDefinitionCRFDAO(sm.getDataSource());
        CRFDAO crfDAO = new CRFDAO(sm.getDataSource());
        DiscrepancyNoteDAO discrepancyNoteDAO = new DiscrepancyNoteDAO(sm.getDataSource());

        // findByPK returns an empty bean (id=0) when nothing matches.
        // Legacy factory tolerates that by rendering an empty page; we
        // do the same — short-circuit to an empty envelope instead of
        // 404'ing, which lets the JSP shell still render its sidebar
        // and filter widgets.
        StudyEventDefinitionBean selectedDef =
                (StudyEventDefinitionBean) studyEventDefinitionDAO.findByPK(defId);
        if (selectedDef == null || selectedDef.getId() <= 0) {
            writeEmptyPayload(dt, defId, moduleStr, discNoteType, resolutionStatus, resword);
            return;
        }

        // CRF set for the selected event definition drives the dynamic
        // crf_<id> column layout. Cached once at the top.
        ArrayList<EventDefinitionCRFBean> eventDefinitionCrfs =
                eventDefinitionCRFDAO.findAllActiveByEventDefinitionId(selectedDef.getId());
        List<CRFBean> crfs = new ArrayList<>(eventDefinitionCrfs.size());
        for (EventDefinitionCRFBean edc : eventDefinitionCrfs) {
            crfs.add((CRFBean) crfDAO.findByPK(edc.getCrfId()));
        }

        // Discrepancy-note constraint SQL fragment matches the legacy
        // factory's `constraints` StringBuffer composition.
        StringBuffer constraints = new StringBuffer();
        if (discNoteType > 0 && discNoteType < 10) {
            constraints.append(" and dn.discrepancy_note_type_id=").append(discNoteType);
        }
        if (resolutionStatusIds != null && !resolutionStatusIds.isEmpty()) {
            StringBuilder s = new StringBuilder(" and (");
            boolean first = true;
            for (Integer id : resolutionStatusIds) {
                if (!first) s.append(" or ");
                s.append("dn.resolution_status_id = ").append(id);
                first = false;
            }
            s.append(" )");
            constraints.append(s);
        }

        ListDiscNotesForCRFFilter filter = new ListDiscNotesForCRFFilter(selectedDef.getId());
        ListDiscNotesForCRFSort sort = new ListDiscNotesForCRFSort();
        sort.addSort("studySubject.label", "asc");

        long totalRows = studySubjectDAO.getCountWithFilter(
                new ListDiscNotesForCRFFilter(selectedDef.getId()), currentStudy);
        long filteredRows = studySubjectDAO.getCountWithFilter(filter, currentStudy);

        Collection<StudySubjectBean> items = studySubjectDAO.getWithFilterAndSort(
                currentStudy, filter, sort, rowStart, rowEnd);

        boolean studyHasDiscNotes = false;
        List<Map<String, Object>> rows = new ArrayList<>(items.size());
        for (StudySubjectBean ss : items) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", ss.getId());
            row.put("studySubject.label", ss.getLabel());

            // Build a (crfId + studyEventId) → EventCRFBean lookup so
            // the per-event iteration below is O(1) per (CRF, event).
            List<EventCRFBean> allEventCrfs = eventCRFDAO.findAllByStudySubject(ss.getId());
            Map<String, EventCRFBean> eventCrfByCrfAndEvent = new HashMap<>();
            for (EventCRFBean ecb : allEventCrfs) {
                Object crfObj = crfDAO.findByVersionId(ecb.getCRFVersionId());
                if (crfObj == null) continue;
                CRFBean crf = (CRFBean) crfObj;
                eventCrfByCrfAndEvent.put(crf.getId() + "_" + ecb.getStudyEventId(), ecb);
            }

            // Events of the selected definition for this subject. Empty
            // list → synthetic NOT_SCHEDULED entry.
            List<StudyEventBean> events = studyEventDAO.findAllByDefinitionAndSubject(selectedDef, ss);

            List<Map<String, Object>> eventList = new ArrayList<>();
            if (events.isEmpty()) {
                eventList.add(buildSyntheticEvent(crfs));
            } else {
                for (StudyEventBean event : events) {
                    Map<String, Object> eventEntry = new LinkedHashMap<>();
                    SubjectEventStatus status = event.getSubjectEventStatus();
                    eventEntry.put("statusId", status == null ? -1 : status.getId());
                    eventEntry.put("statusName", status == null ? "" : status.getName());
                    eventEntry.put("startDate",
                            event.getCreatedDate() == null ? "" :
                                    displayDateFormat.format(event.getCreatedDate()));

                    Map<String, Object> crfCells = new LinkedHashMap<>();
                    for (CRFBean crf : crfs) {
                        EventCRFBean ecb = eventCrfByCrfAndEvent.get(crf.getId() + "_" + event.getId());
                        Map<String, Object> crfCell = new LinkedHashMap<>();
                        if (ecb == null) {
                            crfCell.put("stageId", DataEntryStage.UNCOMPLETED.getId());
                            crfCell.put("discCounts", new HashMap<>());
                        } else {
                            DataEntryStage stage = ecb.getStage();
                            crfCell.put("stageId", stage == null ? DataEntryStage.UNCOMPLETED.getId() : stage.getId());

                            List<DiscrepancyNoteBean> discs = discrepancyNoteDAO
                                    .findAllParentItemNotesByEventCRFWithConstraints(ecb.getId(), constraints);
                            Map<Integer, Integer> discCounts = new HashMap<>();
                            if (discs != null) {
                                for (DiscrepancyNoteBean d : discs) {
                                    ResolutionStatus rs = d.getResStatus();
                                    if (rs == null) continue;
                                    discCounts.merge(rs.getId(), 1, Integer::sum);
                                }
                                if (!discs.isEmpty()) studyHasDiscNotes = true;
                            }
                            crfCell.put("discCounts", discCounts);
                        }
                        crfCells.put("crf_" + crf.getId(), crfCell);
                    }
                    eventEntry.put("crfs", crfCells);
                    eventList.add(eventEntry);
                }
            }
            row.put("events", eventList);
            rows.add(row);
        }

        // Inject availableActions now that the study-has-disc-notes
        // flag is final (matches legacy factory behaviour).
        for (Map<String, Object> row : rows) {
            row.put("availableActions",
                    availableActions(studyHasDiscNotes, resolutionStatus, discNoteType));
        }

        List<Map<String, Object>> columns = new ArrayList<>();
        columns.add(col("studySubject.label", resword.getString("study_subject_ID"), "label",        null));
        columns.add(col("event.status",       resword.getString("event_status"),     "eventStatus",  null));
        columns.add(col("event.startDate",    resword.getString("event_date"),       "eventDate",    null));
        for (CRFBean crf : crfs) {
            columns.add(col("crf_" + crf.getId(), crf.getName(), "eventCrf", crf.getId()));
        }
        columns.add(col("actions", resword.getString("rule_actions"), "actions", null));

        ListDiscNotesForCRFResponse payload = new ListDiscNotesForCRFResponse();
        payload.draw = dt.getDraw();
        payload.recordsTotal = totalRows;
        payload.recordsFiltered = filteredRows;
        payload.data = rows;
        payload.columns = columns;
        payload.defId = selectedDef.getId();
        payload.module = moduleStr;
        payload.discNoteType = discNoteType;
        payload.resolutionStatus = resolutionStatus;
        payload.studyHasDiscNotes = studyHasDiscNotes;

        response.setContentType("application/json;charset=UTF-8");
        try (OutputStream out = response.getOutputStream()) {
            new ObjectMapper().writeValue(out, payload);
        }
    }

    private void writeEmptyPayload(DataTableRequest dt, int defId, String moduleStr,
                                   int discNoteType, int resolutionStatus,
                                   java.util.ResourceBundle resword) throws Exception {
        List<Map<String, Object>> columns = new ArrayList<>();
        columns.add(col("studySubject.label", resword.getString("study_subject_ID"), "label",        null));
        columns.add(col("event.status",       resword.getString("event_status"),     "eventStatus",  null));
        columns.add(col("event.startDate",    resword.getString("event_date"),       "eventDate",    null));
        columns.add(col("actions",            resword.getString("rule_actions"),     "actions",      null));

        ListDiscNotesForCRFResponse payload = new ListDiscNotesForCRFResponse();
        payload.draw = dt.getDraw();
        payload.recordsTotal = 0L;
        payload.recordsFiltered = 0L;
        payload.data = new ArrayList<>();
        payload.columns = columns;
        payload.defId = defId;
        payload.module = moduleStr;
        payload.discNoteType = discNoteType;
        payload.resolutionStatus = resolutionStatus;
        payload.studyHasDiscNotes = false;

        response.setContentType("application/json;charset=UTF-8");
        try (OutputStream out = response.getOutputStream()) {
            new ObjectMapper().writeValue(out, payload);
        }
    }

    private Map<String, Object> buildSyntheticEvent(List<CRFBean> crfs) {
        Map<String, Object> entry = new LinkedHashMap<>();
        SubjectEventStatus notScheduled = SubjectEventStatus.NOT_SCHEDULED;
        entry.put("statusId", notScheduled.getId());
        entry.put("statusName", notScheduled.getName());
        entry.put("startDate", "");
        Map<String, Object> crfCells = new LinkedHashMap<>();
        for (CRFBean crf : crfs) {
            Map<String, Object> crfCell = new LinkedHashMap<>();
            crfCell.put("stageId", DataEntryStage.UNCOMPLETED.getId());
            crfCell.put("discCounts", new HashMap<>());
            crfCells.put("crf_" + crf.getId(), crfCell);
        }
        entry.put("crfs", crfCells);
        return entry;
    }

    private List<String> availableActions(boolean studyHasDiscNotes,
                                          int resolutionStatus, int discNoteType) {
        List<String> a = new ArrayList<>();
        a.add("view");
        if (studyHasDiscNotes) {
            a.add("download");
        }
        return a;
    }

    private static String parseModule(String moduleParam) {
        if (moduleParam == null) return "manage";
        String t = moduleParam.trim();
        if (t.isEmpty()) return "manage";
        if ("submit".equals(t)) return "submit";
        if ("admin".equals(t)) return "admin";
        return "manage";
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

    public static final class ListDiscNotesForCRFResponse {
        public int draw;
        public long recordsTotal;
        public long recordsFiltered;
        public List<Map<String, Object>> data;
        public List<Map<String, Object>> columns;
        public int defId;
        public String module;
        public int discNoteType;
        public int resolutionStatus;
        public boolean studyHasDiscNotes;
    }
}
