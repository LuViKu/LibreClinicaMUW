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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.DiscrepancyNoteType;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.ResolutionStatus;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.DiscrepancyNoteBean;
import at.ac.meduniwien.ophthalmology.libreclinica.control.core.SecureController;
import at.ac.meduniwien.ophthalmology.libreclinica.core.util.Pair;
import at.ac.meduniwien.ophthalmology.libreclinica.i18n.core.LocaleResolver;
import at.ac.meduniwien.ophthalmology.libreclinica.i18n.util.I18nFormatUtil;
import at.ac.meduniwien.ophthalmology.libreclinica.i18n.util.ResourceBundleProvider;
import at.ac.meduniwien.ophthalmology.libreclinica.service.managestudy.ViewNotesFilterCriteria;
import at.ac.meduniwien.ophthalmology.libreclinica.service.managestudy.ViewNotesService;
import at.ac.meduniwien.ophthalmology.libreclinica.service.managestudy.ViewNotesSortCriteria;
import at.ac.meduniwien.ophthalmology.libreclinica.view.Page;
import at.ac.meduniwien.ophthalmology.libreclinica.web.InsufficientPermissionException;
import at.ac.meduniwien.ophthalmology.libreclinica.web.datatable.DataTableRequest;
import org.springframework.web.context.support.WebApplicationContextUtils;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * DataTables.net-protocol JSON endpoint for the "Notes & Discrepancies"
 * table. Phase B.4 jmesa PR 5a (cohort 3a) — replaces the
 * server-rendered HTML blob the deleted {@code ListNotesTableFactory}
 * dumped into {@code viewNotes.jsp}.
 *
 * <p>The companion {@link ViewNotesServlet} still renders the JSP
 * shell plus the summary-statistics block above the table; this
 * servlet only feeds the table rows asynchronously.
 *
 * <p>Per-row {@code availableActions}:
 * <ul>
 *   <li>{@code view} — always present (opens DN popup)</li>
 *   <li>{@code resolve} — present when the study is not locked AND
 *       (entityType != "eventCrf" OR stageId == 5)</li>
 * </ul>
 */
public class ViewNotesDataServlet extends SecureController {

    private static final long serialVersionUID = 1L;

    private static final int MAX_PAGE_LENGTH = 500;

    private Locale locale;
    private ViewNotesService viewNotesService;

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
        ResourceBundle resword = ResourceBundleProvider.getWordsBundle(locale);
        ResourceBundle resformat = ResourceBundleProvider.getFormatBundle(locale);
        String dateFormatPattern = resformat.getString("date_format_string");
        SimpleDateFormat displayDateFormat = new SimpleDateFormat(dateFormatPattern);

        DataTableRequest dt = DataTableRequest.from(request);
        int length = Math.min(dt.getLength(), MAX_PAGE_LENGTH);
        if (length <= 0) length = MAX_PAGE_LENGTH;
        int start = Math.max(dt.getStart(), 0);

        // Decoders mirror what the deleted ListNotesTableFactory's
        // inner DropdownFilterEditors used to set up. Filter requests
        // pass the display string from the dropdown; the decoder maps
        // it back to the underlying id list ("1,2", "1,3" for the
        // synthesized "New & Updated" and "Query & Failed Validation"
        // groups).
        Map<String, String> typeDecoder = new HashMap<>();
        for (DiscrepancyNoteType t : DiscrepancyNoteType.list) {
            typeDecoder.put(t.getName(), Integer.toString(t.getId()));
        }
        typeDecoder.put("Query and Failed Validation Check", "1,3");

        Map<String, String> resolutionDecoder = new HashMap<>();
        for (ResolutionStatus s : ResolutionStatus.list) {
            resolutionDecoder.put(s.getName(), Integer.toString(s.getId()));
        }
        resolutionDecoder.put("New and Updated", "1,2");

        // ViewNotesFilterCriteria.buildFilterCriteria expects a
        // Map<columnKey, filterValue>. Sort criteria come in as a
        // list of (column, asc/desc) pairs.
        Map<String, String> filterMap = new HashMap<>();
        String globalSearch = dt.getGlobalSearch();
        for (DataTableRequest.Column col : dt.getColumns()) {
            if (col.getSearchValue() != null && !col.getSearchValue().isEmpty()) {
                filterMap.put(col.getData(), col.getSearchValue());
            }
        }
        // The legacy table also accepted a global box that searched
        // study-subject label; keep that affordance.
        if (globalSearch != null && !globalSearch.isEmpty()
                && !filterMap.containsKey("studySubject.label")) {
            filterMap.put("studySubject.label", globalSearch);
        }

        List<Pair<String, String>> sortPairs = new ArrayList<>();
        if (dt.getSortColumnName() != null) {
            sortPairs.add(new Pair<>(dt.getSortColumnName(), dt.getSortDirection()));
        } else {
            sortPairs.add(new Pair<>("discrepancyNoteBean.createdDate", "desc"));
        }

        // Paging mirrors what jmesa was doing: 1-indexed page number,
        // computed from the requested start/length.
        int pageNumber = (start / length) + 1;

        ViewNotesFilterCriteria filterCriteria;
        try {
            filterCriteria = ViewNotesFilterCriteria.buildFilterCriteria(
                    filterMap, dateFormatPattern, typeDecoder, resolutionDecoder,
                    pageNumber, length);
        } catch (IllegalArgumentException badColumn) {
            // The legacy factory throws on unknown filter keys —
            // preserve that behaviour as a 400.
            response.sendError(400, badColumn.getMessage());
            return;
        }
        ViewNotesSortCriteria sortCriteria =
                ViewNotesSortCriteria.buildFilterCriteria(sortPairs);

        ViewNotesService service = resolveViewNotesService();
        // calculateNotesSummary aggregates across the 5 DN entity
        // types (subject / studySubject / studyEvent / eventCrf /
        // itemData). Filtering is applied for both total and
        // filtered counts — there is no "global" count separate from
        // the filtered count in the underlying DAO.
        long totalRows = service.calculateNotesSummary(currentStudy, filterCriteria).getTotal();
        long filteredRows = totalRows;

        // If the requested page exceeds the dataset (e.g. the user
        // filtered down then navigated forward), jump to the last
        // page — same recovery the legacy factory had.
        if (totalRows > 0 && start >= totalRows) {
            int lastPage = (int) Math.ceil((double) totalRows / length);
            filterCriteria = ViewNotesFilterCriteria.buildFilterCriteria(
                    filterMap, dateFormatPattern, typeDecoder, resolutionDecoder,
                    lastPage, length);
        }

        List<DiscrepancyNoteBean> items = service.listNotes(
                currentStudy, filterCriteria, sortCriteria);

        List<Map<String, Object>> rows = new ArrayList<>(items.size());
        boolean studyLocked = currentStudy.getStatus() != null
                && currentStudy.getStatus().isLocked();
        for (DiscrepancyNoteBean dnb : items) {
            Map<String, Object> row = new HashMap<>();
            row.put("id", dnb.getId());
            row.put("studySubject.label",
                    dnb.getStudySub() == null ? "" : dnb.getStudySub().getLabel());
            row.put("siteId", dnb.getSiteId());
            row.put("discrepancyNoteBean.createdDate",
                    dnb.getCreatedDate() == null ? "" :
                            I18nFormatUtil.getDateFormat(locale).format(dnb.getCreatedDate()));
            row.put("discrepancyNoteBean.updatedDate",
                    dnb.getUpdatedDate() == null ? "" :
                            I18nFormatUtil.getDateFormat(locale).format(dnb.getUpdatedDate()));
            row.put("eventName", dnb.getEventName());
            row.put("eventStartDate",
                    dnb.getEventStart() == null ? "" :
                            displayDateFormat.format(dnb.getEventStart()));
            row.put("crfName", dnb.getCrfName());
            row.put("crfStatus", dnb.getCrfStatus());
            row.put("entityName", translateEntityName(dnb, resword));
            row.put("entityValue", dnb.getEntityValue());
            row.put("discrepancyNoteBean.description", dnb.getDescription());
            row.put("discrepancyNoteBean.detailedNotes", dnb.getDetailedNotes());
            row.put("numberOfNotes", dnb.getNumChildren());

            UserAccountBean assigned = dnb.getAssignedUser();
            row.put("discrepancyNoteBean.user", assigned == null ? "" :
                    assigned.getFirstName() + " " + assigned.getLastName() + " (" + assigned.getName() + ")");

            ResolutionStatus rs = dnb.getResStatus();
            Map<String, Object> rsCell = new HashMap<>();
            rsCell.put("id", rs == null ? -1 : rs.getId());
            rsCell.put("name", rs == null ? "" : rs.getName());
            rsCell.put("iconFilePath", rs == null ? "" : rs.getIconFilePath());
            row.put("discrepancyNoteBean.resolutionStatus", rsCell);

            DiscrepancyNoteType dt2 = dnb.getDisType();
            row.put("discrepancyNoteBean.disType", dt2 == null ? "" : dt2.getName());

            row.put("discrepancyNoteBean.entityType", dnb.getEntityType());

            UserAccountBean owner = dnb.getOwner();
            row.put("discrepancyNoteBean.owner", owner == null ? "" : owner.getName());

            row.put("age", dnb.getAge());
            row.put("days", dnb.getDays());

            row.put("availableActions", availableActions(dnb, studyLocked));

            rows.add(row);
        }

        // Column metadata block — drives both the header render and
        // the per-cell renderer dispatch on the client side.
        List<Map<String, Object>> columns = new ArrayList<>();
        columns.add(col("studySubject.label",              resword.getString("study_subject_ID"),  "label"));
        columns.add(col("siteId",                          resword.getString("site_id"),           "text"));
        columns.add(col("discrepancyNoteBean.createdDate", resword.getString("date_created"),      "text"));
        columns.add(col("discrepancyNoteBean.updatedDate", resword.getString("date_updated"),      "text"));
        columns.add(col("eventName",                       resword.getString("event_name"),        "text"));
        columns.add(col("crfName",                         resword.getString("CRF"),               "text"));
        columns.add(col("crfStatus",                       resword.getString("CRF_status"),        "text"));
        columns.add(col("entityName",                      resword.getString("entity_name"),       "text"));
        columns.add(col("entityValue",                     resword.getString("entity_value"),      "text"));
        columns.add(col("discrepancyNoteBean.description", resword.getString("description"),       "text"));
        columns.add(col("discrepancyNoteBean.detailedNotes", resword.getString("detailed_notes"),  "text"));
        columns.add(col("numberOfNotes",                   resword.getString("of_notes"),          "text"));
        columns.add(col("discrepancyNoteBean.user",        resword.getString("assigned_user"),     "text"));
        columns.add(col("discrepancyNoteBean.resolutionStatus", resword.getString("resolution_status"), "resolutionStatus"));
        columns.add(col("discrepancyNoteBean.disType",     resword.getString("type"),              "text"));
        columns.add(col("discrepancyNoteBean.entityType",  resword.getString("entity_type"),       "text"));
        columns.add(col("discrepancyNoteBean.owner",       resword.getString("owner"),             "text"));
        columns.add(col("age",                             resword.getString("days_open"),         "text"));
        columns.add(col("days",                            resword.getString("days_since_updated"),"text"));
        columns.add(col("eventStartDate",                  resword.getString("event_date"),        "text"));
        columns.add(col("actions",                         resword.getString("actions"),           "actions"));

        ViewNotesResponse payload = new ViewNotesResponse();
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

    private String translateEntityName(DiscrepancyNoteBean dnb, ResourceBundle resword) {
        String entityName = dnb.getEntityName();
        if (entityName == null) return "";
        if ("itemData".equals(dnb.getEntityType())) {
            return entityName;
        }
        try {
            return resword.getString(entityName);
        } catch (MissingResourceException e) {
            // Legacy factory rendered "###<key>###" on missing
            // translations so the operator notices.
            return "###" + entityName + "###";
        }
    }

    private List<String> availableActions(DiscrepancyNoteBean dnb, boolean studyLocked) {
        List<String> a = new ArrayList<>();
        a.add("view");
        if (!studyLocked) {
            String entityType = dnb.getEntityType();
            if (!"eventCrf".equals(entityType) || dnb.getStageId() == 5) {
                a.add("resolve");
            }
        }
        return Collections.unmodifiableList(a);
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

    protected ViewNotesService resolveViewNotesService() {
        if (viewNotesService == null) {
            viewNotesService = (ViewNotesService) WebApplicationContextUtils
                    .getWebApplicationContext(getServletContext())
                    .getBean("viewNotesService");
        }
        return viewNotesService;
    }

    public static final class ViewNotesResponse {
        public int draw;
        public long recordsTotal;
        public long recordsFiltered;
        public List<Map<String, Object>> data;
        public List<Map<String, Object>> columns;
    }
}
