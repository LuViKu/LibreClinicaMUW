/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 */
package org.akaza.openclinica.controller;

import static org.akaza.openclinica.core.util.ClassCastHelper.asArrayList;
import static org.akaza.openclinica.core.util.ClassCastHelper.asEnumeration;

import java.io.IOException;
import java.io.OutputStream;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.sql.DataSource;

import org.akaza.openclinica.bean.admin.CRFBean;
import org.akaza.openclinica.bean.core.DataEntryStage;
import org.akaza.openclinica.bean.core.Role;
import org.akaza.openclinica.bean.core.Status;
import org.akaza.openclinica.bean.core.SubjectEventStatus;
import org.akaza.openclinica.bean.login.StudyUserRoleBean;
import org.akaza.openclinica.bean.login.UserAccountBean;
import org.akaza.openclinica.bean.managestudy.EventDefinitionCRFBean;
import org.akaza.openclinica.bean.managestudy.StudyBean;
import org.akaza.openclinica.bean.managestudy.StudyEventBean;
import org.akaza.openclinica.bean.managestudy.StudyGroupBean;
import org.akaza.openclinica.bean.managestudy.StudySubjectBean;
import org.akaza.openclinica.bean.submit.EventCRFBean;
import org.akaza.openclinica.bean.submit.SubjectBean;
import org.akaza.openclinica.controller.helper.SdvFilterDataBean;
import org.akaza.openclinica.dao.EventCRFSDVFilter;
import org.akaza.openclinica.dao.EventCRFSDVSort;
import org.akaza.openclinica.dao.StudySubjectSDVFilter;
import org.akaza.openclinica.dao.StudySubjectSDVSort;
import org.akaza.openclinica.dao.admin.CRFDAO;
import org.akaza.openclinica.dao.managestudy.EventDefinitionCRFDAO;
import org.akaza.openclinica.dao.managestudy.StudyDAO;
import org.akaza.openclinica.dao.managestudy.StudyEventDAO;
import org.akaza.openclinica.dao.managestudy.StudyGroupDAO;
import org.akaza.openclinica.dao.managestudy.StudySubjectDAO;
import org.akaza.openclinica.dao.submit.EventCRFDAO;
import org.akaza.openclinica.dao.submit.SubjectDAO;
import org.akaza.openclinica.domain.SourceDataVerification;
import org.akaza.openclinica.i18n.core.LocaleResolver;
import org.akaza.openclinica.i18n.util.I18nFormatUtil;
import org.akaza.openclinica.i18n.util.ResourceBundleProvider;
import org.akaza.openclinica.view.StudyInfoPanel;
import org.akaza.openclinica.web.table.sdv.SDVUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.propertyeditors.CustomDateEditor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.ModelMap;
import org.springframework.web.bind.ServletRequestDataBinder;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Implement the functionality for displaying a table of Event CRFs for Source Data
 * Verification. This is an autowired, multiaction Controller.
 */
@Controller("sdvController")
public class SDVController {
    protected final Logger logger = LoggerFactory.getLogger(getClass().getName());

    public final static String SUBJECT_SDV_TABLE_ATTRIBUTE = "sdvTableAttribute";
    @Autowired
    @Qualifier("dataSource")
    private DataSource dataSource;

    @Autowired
    @Qualifier("sdvUtil")
    private SDVUtil sdvUtil;

    // Phase B.4 jmesa PR 7b (cohort 5b): sdvFactory removed. The
    // per-subject SDV matrix is now rendered client-side via
    // /viewSubjectAggregateData (vanilla-JS fetch + DOM build).

    //Autowire the class that handles the sidebar structure with a configured
    //bean named "sidebarInit"
    @Autowired
    @Qualifier("sidebarInit")
    private SidebarInit sidebarInit;

    public SDVController() {
    }

    @RequestMapping("/viewSubjectAggregate")
    public ModelMap viewSubjectAggregateHandler(HttpServletRequest request, HttpServletResponse response, @RequestParam("studyId") int studyId) {
        if (!mayProceed(request)) {
            try {
                response.sendRedirect(request.getContextPath() + "/MainMenu?message=authentication_failed");
            } catch (Exception e) {
                logger.error("Error while redirecting to MainMenu: ", e);
            }
            return null;
        }
        // Phase B.4 jmesa PR 7b (cohort 5b): sdvFactory.createTable().render()
        // is gone. JSP shell includes a vanilla-JS fragment that fetches
        // /viewSubjectAggregateData asynchronously.
        ModelMap gridMap = new ModelMap();
        request.setAttribute("studyId", studyId);
        request.setAttribute("imagePathPrefix", "../");

        ArrayList<String> pageMessages = asArrayList(request.getAttribute("pageMessages"), String.class);
        if (pageMessages == null) {
            pageMessages = new ArrayList<String>();
        }
        request.setAttribute("pageMessages", pageMessages);
        return gridMap;
    }

    /**
     * JSON endpoint backing the per-subject SDV table.
     * Phase B.4 jmesa PR 7b (cohort 5b) — replaces the
     * {@code SubjectIdSDVFactory.createTable().render()} blob the
     * {@code /viewSubjectAggregate} mapping used to emit.
     *
     * <p>Each row carries pre-computed display fields (label / siteId /
     * personId / status / group / counts) plus a semantic {@code
     * sdvState} ("complete" | "needsSdv" | "notApplicable") and an
     * {@code availableActions} array. The JSP fragment renders icons
     * and per-row Submit buttons from those — no HTML built
     * server-side.
     */
    @RequestMapping("/viewSubjectAggregateData")
    @ResponseBody
    public void viewSubjectAggregateData(HttpServletRequest request, HttpServletResponse response,
                                         @RequestParam("studyId") int studyId)
            throws IOException {
        if (!mayProceed(request)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }

        StudySubjectDAO studySubjectDAO = new StudySubjectDAO(dataSource);
        StudyDAO studyDAO = new StudyDAO(dataSource);
        StudyGroupDAO studyGroupDAO = new StudyGroupDAO(dataSource);
        EventCRFDAO eventCRFDAO = new EventCRFDAO(dataSource);
        StudyEventDAO studyEventDAO = new StudyEventDAO(dataSource);
        EventDefinitionCRFDAO eventDefinitionCrfDAO = new EventDefinitionCRFDAO(dataSource);
        CRFDAO crfDAO = new CRFDAO(dataSource);

        java.util.ResourceBundle resword = ResourceBundleProvider.getWordsBundle(
                LocaleResolver.getLocale(request));

        StudyBean studyBean = (StudyBean) studyDAO.findByPK(studyId);
        boolean studyLocked = studyBean != null && studyBean.getStatus() != null
                && studyBean.getStatus().isLocked();

        StudySubjectSDVFilter filter = new StudySubjectSDVFilter();
        StudySubjectSDVSort sort = new StudySubjectSDVSort();
        sort.addSort("studySubjectId", "asc");

        // Cap to MAX_PAGE_LENGTH; this is a non-paginated single fetch.
        int rowStart = 0;
        int rowEnd = 500;
        int totalRows = studySubjectDAO.countAllByStudySDV(studyId, studyId, filter);
        List<StudySubjectBean> studySubjects = studySubjectDAO.findAllByStudySDV(
                studyId, studyId, filter, sort, rowStart, rowEnd);

        List<Map<String, Object>> rows = new ArrayList<>(studySubjects.size());
        for (StudySubjectBean ss : studySubjects) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", ss.getId());
            row.put("studySubjectId", ss.getLabel());
            row.put("personId", ss.getUniqueIdentifier());
            row.put("studySubjectStatus", ss.getStatus() == null ? "" : ss.getStatus().getName());

            int total = eventCRFDAO.countEventCRFsByStudySubject(ss.getId(), ss.getStudyId(), ss.getStudyId());
            row.put("totalEventCRF", total);

            StudyBean parentStudy = (StudyBean) studyDAO.findByPK(ss.getStudyId());
            row.put("siteId", parentStudy == null ? "" : parentStudy.getIdentifier());

            List<EventCRFBean> ecbs = eventCRFDAO.getEventCRFsByStudySubject(
                    ss.getId(), ss.getStudyId(), ss.getStudyId());
            Map<String, Integer> stats = computeEventCRFStats(
                    ecbs, ss, studyEventDAO, eventDefinitionCrfDAO, crfDAO);

            row.put("numberCRFComplete", stats.get("numberOfCompletedEventCRFs"));
            row.put("numberOfCRFsSDV", stats.get("numberOfSDVdEventCRFs"));

            boolean studySubjectSDVd = stats.get("areEventCRFsSDVd") == -1
                    || stats.get("areEventCRFsSDVd") == 1 ? false : true;

            String sdvState;
            if (stats.get("shouldDisplaySDVButton") == 0) {
                sdvState = "notApplicable";
            } else if (studySubjectSDVd) {
                sdvState = "complete";
            } else {
                sdvState = "needsSdv";
            }
            row.put("sdvState", sdvState);

            List<StudyGroupBean> groups = studyGroupDAO.getGroupByStudySubject(
                    ss.getId(), ss.getStudyId(), ss.getStudyId());
            row.put("group", (groups == null || groups.isEmpty()) ? "" : groups.get(0).getName());

            // availableActions: 'view' always; 'sdv' if needs-SDV +
            // study not locked. Mirrors the legacy ActionsCellEditor.
            List<String> actions = new ArrayList<>();
            actions.add("view");
            if ("needsSdv".equals(sdvState) && !studyLocked) {
                actions.add("sdv");
            }
            row.put("availableActions", actions);

            rows.add(row);
        }

        List<Map<String, Object>> columns = new ArrayList<>();
        columns.add(column("sdvStatus",          resword.getString("SDV_status")));
        columns.add(column("studySubjectId",     resword.getString("study_subject_ID")));
        columns.add(column("siteId",             resword.getString("site_id")));
        columns.add(column("personId",           resword.getString("person_ID")));
        columns.add(column("studySubjectStatus", resword.getString("study_subject_status")));
        columns.add(column("group",              resword.getString("group")));
        columns.add(column("numberCRFComplete",  resword.getString("num_CRFs_completed")));
        columns.add(column("numberOfCRFsSDV",    resword.getString("num_CRFs_SDV")));
        columns.add(column("totalEventCRF",      resword.getString("total_events_CRF")));
        columns.add(column("actions",            resword.getString("rule_actions")));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("draw", parseDraw(request));
        payload.put("recordsTotal", totalRows);
        payload.put("recordsFiltered", totalRows);
        payload.put("data", rows);
        payload.put("columns", columns);
        payload.put("studyLocked", studyLocked);

        response.setContentType("application/json;charset=UTF-8");
        try (OutputStream out = response.getOutputStream()) {
            new ObjectMapper().writeValue(out, payload);
        }
    }

    /**
     * Subject-level EventCRF aggregation. Lifted verbatim from the
     * deleted {@code SubjectIdSDVFactory.getEventCRFStats} so the
     * client sees the same counts.
     */
    private Map<String, Integer> computeEventCRFStats(
            List<EventCRFBean> eventCRFBeans,
            StudySubjectBean studySubject,
            StudyEventDAO studyEventDAO,
            EventDefinitionCRFDAO eventDefinitionCrfDAO,
            CRFDAO crfDAO) {
        int numberOfCompletedEventCRFs = 0;
        int numberOfSDVdEventCRFs = 0;
        int areEventCRFsSDVd = eventCRFBeans.size() > 0 ? 0 : -1;
        boolean partialOrHundred = false;

        for (EventCRFBean ecb : eventCRFBeans) {
            StudyEventBean studyEventBean = (StudyEventBean) studyEventDAO.findByPK(ecb.getStudyEventId());
            CRFBean crfBean = crfDAO.findByVersionId(ecb.getCRFVersionId());

            if (ecb.getStatus() == Status.UNAVAILABLE || ecb.getStatus() == Status.LOCKED) {
                numberOfCompletedEventCRFs++;
            }
            if (ecb.isSdvStatus()) {
                numberOfSDVdEventCRFs++;
            }
            EventDefinitionCRFBean edc = eventDefinitionCrfDAO
                    .findByStudyEventDefinitionIdAndCRFIdAndStudyId(
                            studyEventBean.getStudyEventDefinitionId(),
                            crfBean.getId(), studySubject.getStudyId());
            if (edc.getId() == 0) {
                edc = eventDefinitionCrfDAO.findForStudyByStudyEventDefinitionIdAndCRFId(
                        studyEventBean.getStudyEventDefinitionId(), crfBean.getId());
            }
            boolean requiresSdv = edc.getSourceDataVerification() == SourceDataVerification.AllREQUIRED
                    || edc.getSourceDataVerification() == SourceDataVerification.PARTIALREQUIRED;
            boolean ecbLocked = ecb.getStatus() == Status.UNAVAILABLE || ecb.getStatus() == Status.LOCKED;
            if (requiresSdv && ecbLocked) {
                partialOrHundred = true;
            }
            if (requiresSdv && !ecb.isSdvStatus() && ecbLocked) {
                areEventCRFsSDVd = 1;
            }
        }

        Map<String, Integer> stats = new HashMap<>();
        stats.put("numberOfCompletedEventCRFs", numberOfCompletedEventCRFs);
        stats.put("numberOfSDVdEventCRFs", numberOfSDVdEventCRFs);
        stats.put("areEventCRFsSDVd", partialOrHundred ? areEventCRFsSDVd : 1);
        stats.put("shouldDisplaySDVButton",
                numberOfCompletedEventCRFs > 0 && partialOrHundred ? 1 : 0);
        return stats;
    }

    private static Map<String, Object> column(String key, String title) {
        Map<String, Object> c = new HashMap<>();
        c.put("key", key);
        c.put("title", title);
        return c;
    }

    private static int parseDraw(HttpServletRequest request) {
        String s = request.getParameter("draw");
        if (s == null || s.isEmpty()) return 0;
        try { return Integer.parseInt(s); } catch (NumberFormatException nfe) { return 0; }
    }

    /**
     * JSON endpoint backing the per-event-CRF SDV table rendered by
     * {@code /pages/viewAllSubjectSDVtmp}, {@code /viewAllSubjectSDVform},
     * and {@code /viewAllSubjectSDV} (per-subject variant). Phase B.4
     * jmesa PR 9 (cohort 7) — replaces the
     * {@code SDVUtil.renderEventCRFTableWithLimit} /
     * {@code SDVUtil.renderSubjectsTableWithLimit} HTML blobs.
     *
     * <p>Optional {@code studySubjectId} query param restricts the
     * result to one subject (per-subject variant); omit it for the
     * full-study event-CRF table.
     *
     * <p>Each row carries semantic fields plus an {@code
     * availableActions} array; the JS fragment renders the SDV
     * checkbox / DoubleCheck icon / CRF-status icon / submit button
     * based on those.
     */
    @RequestMapping("/viewAllSubjectSdvData")
    @ResponseBody
    public void viewEventCrfSdvData(HttpServletRequest request, HttpServletResponse response,
                                    @RequestParam("studyId") int studyId)
            throws IOException {
        if (!mayProceed(request)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        int studySubjectIdFilter = -1;
        String ssidParam = request.getParameter("studySubjectId");
        if (ssidParam != null && !ssidParam.isEmpty()) {
            try { studySubjectIdFilter = Integer.parseInt(ssidParam); }
            catch (NumberFormatException nfe) { studySubjectIdFilter = -1; }
        }

        EventCRFDAO eventCRFDAO = new EventCRFDAO(dataSource);
        StudySubjectDAO studySubjectDAO = new StudySubjectDAO(dataSource);
        SubjectDAO subjectDAO = new SubjectDAO(dataSource);
        StudyDAO studyDAO = new StudyDAO(dataSource);
        StudyEventDAO studyEventDAO = new StudyEventDAO(dataSource);
        EventDefinitionCRFDAO eventDefinitionCRFDAO = new EventDefinitionCRFDAO(dataSource);

        java.util.ResourceBundle resword = ResourceBundleProvider.getWordsBundle(
                LocaleResolver.getLocale(request));
        SimpleDateFormat dateFormat = I18nFormatUtil.getDateFormat(LocaleResolver.getLocale(request));

        int rowStart = 0;
        int rowEnd = 500;

        // Materialise the row list. Per-subject variant uses the
        // legacy by-subject DAO call; full-study uses the filter/sort
        // overload from the (now-deleted) jmesa rendering path.
        List<EventCRFBean> eventCRFBeans;
        long totalRows;
        if (studySubjectIdFilter > 0) {
            eventCRFBeans = eventCRFDAO.getEventCRFsByStudySubjectLimit(
                    studySubjectIdFilter, studyId, studyId, rowEnd - rowStart, rowStart);
            totalRows = eventCRFBeans.size();
        } else {
            EventCRFSDVFilter filter = new EventCRFSDVFilter(studyId);
            EventCRFSDVSort sort = new EventCRFSDVSort();
            totalRows = eventCRFDAO.getCountWithFilter(studyId, studyId, filter);
            eventCRFBeans = eventCRFDAO.getWithFilterAndSort(
                    studyId, studyId, filter, sort, rowStart, rowEnd);
        }

        List<Map<String, Object>> rows = new ArrayList<>(eventCRFBeans.size());
        // Resolve event names + display values per row (lifted from
        // the deleted SDVUtil.getSubjectRows).
        for (EventCRFBean crfBean : eventCRFBeans) {
            StudySubjectBean ss = (StudySubjectBean) studySubjectDAO.findByPK(crfBean.getStudySubjectId());
            StudyEventBean studyEvent = (StudyEventBean) studyEventDAO.findByPK(crfBean.getStudyEventId());
            SubjectBean subject = (SubjectBean) subjectDAO.findByPK(ss.getSubjectId());
            StudyBean parentStudy = (StudyBean) studyDAO.findByPK(ss.getStudyId());
            EventDefinitionCRFBean edc = eventDefinitionCRFDAO
                    .findByStudyEventIdAndCRFVersionId(parentStudy, studyEvent.getId(), crfBean.getCRFVersionId());

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", crfBean.getId());
            row.put("studySubjectId", ss.getLabel());
            row.put("studySubjectInternalId", ss.getId());
            row.put("studyIdentifier", parentStudy == null ? "" : parentStudy.getIdentifier());
            row.put("personId", subject == null ? "" : subject.getUniqueIdentifier());
            row.put("secondaryId", ss.getSecondaryLabel());
            row.put("eventName", studyEvent.getName());
            row.put("eventDate", studyEvent.getDateStarted() == null
                    ? "" : dateFormat.format(studyEvent.getDateStarted()));
            row.put("enrollmentDate", ss.getEnrollmentDate() == null
                    ? "" : dateFormat.format(ss.getEnrollmentDate()));
            row.put("studySubjectStatus",
                    ss.getStatus() == null ? "" : ss.getStatus().getName());

            row.put("crfNameVersion", sdvUtil.getCRFName(crfBean.getCRFVersionId())
                    + "/ " + sdvUtil.getCRFVersionName(crfBean.getCRFVersionId()));

            SourceDataVerification sourceData =
                    edc == null ? null : edc.getSourceDataVerification();
            row.put("sdvRequirementDefinition",
                    sourceData == null ? "" : sourceData.toString());

            int crfStageId = crfBean.getStage() == null
                    ? DataEntryStage.UNCOMPLETED.getId() : crfBean.getStage().getId();
            if (studyEvent.getSubjectEventStatus() == SubjectEventStatus.LOCKED
                    || studyEvent.getSubjectEventStatus() == SubjectEventStatus.STOPPED
                    || studyEvent.getSubjectEventStatus() == SubjectEventStatus.SKIPPED) {
                crfStageId = DataEntryStage.LOCKED.getId();
            }
            row.put("crfStageId", crfStageId);
            row.put("eventDefinitionCRFId", edc == null ? 0 : edc.getId());
            row.put("crfVersionId", crfBean.getCRFVersionId());

            row.put("lastUpdatedDate", crfBean.getUpdatedDate() == null
                    ? "" : dateFormat.format(crfBean.getUpdatedDate()));
            row.put("lastUpdatedBy", crfBean.getUpdater() == null ? ""
                    : crfBean.getUpdater().getFirstName() + " " + crfBean.getUpdater().getLastName());
            row.put("studyEventStatus", studyEvent.getStatus() == null
                    ? "" : studyEvent.getStatus().getName());

            boolean sdvVerified = crfBean.isSdvStatus();
            boolean studyLocked = parentStudy != null && parentStudy.getStatus() != null
                    && parentStudy.getStatus().isLocked();
            row.put("sdvVerified", sdvVerified);
            row.put("studyLocked", studyLocked);

            List<String> actions = new ArrayList<>();
            if (!sdvVerified && !studyLocked) {
                actions.add("sdv");
            }
            row.put("availableActions", actions);

            rows.add(row);
        }

        List<Map<String, Object>> columns = new ArrayList<>();
        columns.add(column("sdvStatus",                resword.getString("SDV_status")));
        columns.add(column("studySubjectId",           resword.getString("study_subject_ID")));
        columns.add(column("studyIdentifier",          resword.getString("site_id")));
        columns.add(column("personId",                 resword.getString("person_ID")));
        columns.add(column("secondaryId",              resword.getString("secondary_ID")));
        columns.add(column("eventName",                resword.getString("event_name")));
        columns.add(column("eventDate",                resword.getString("event_date")));
        columns.add(column("enrollmentDate",           resword.getString("enrollment_date")));
        columns.add(column("studySubjectStatus",       resword.getString("subject_status")));
        columns.add(column("crfNameVersion",           resword.getString("CRF_name") + " / " + resword.getString("version")));
        columns.add(column("sdvRequirementDefinition", resword.getString("SDV_requirement")));
        columns.add(column("crfStatus",                resword.getString("CRF_status")));
        columns.add(column("lastUpdatedDate",          resword.getString("last_updated_date")));
        columns.add(column("lastUpdatedBy",            resword.getString("last_updated_by")));
        columns.add(column("studyEventStatus",         resword.getString("study_event_status")));
        columns.add(column("sdvStatusActions",         resword.getString("actions")));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("draw", parseDraw(request));
        payload.put("recordsTotal", totalRows);
        payload.put("recordsFiltered", totalRows);
        payload.put("data", rows);
        payload.put("columns", columns);

        response.setContentType("application/json;charset=UTF-8");
        try (OutputStream out = response.getOutputStream()) {
            new ObjectMapper().writeValue(out, payload);
        }
    }

    @RequestMapping("/viewAllSubjectSDV")
    public ModelMap viewSubjectHandler(HttpServletRequest request, @RequestParam("studySubjectId") int studySubjectId, @RequestParam("studyId") int studyId) {

        ModelMap gridMap = new ModelMap();
        /*EventCRFDAO eventCRFDAO = new EventCRFDAO(dataSource);
        List<EventCRFBean> eventCRFBeans = eventCRFDAO.findAllByStudySubject(studySubjectId);*/

        request.setAttribute("studyId", studyId);
        request.setAttribute("studySubjectId", studySubjectId);
        //  request.setAttribute("isViewSubjectRequest","y");
        request.setAttribute("imagePathPrefix", "../");

        ArrayList<String> pageMessages = asArrayList(request.getAttribute("pageMessages"), String.class);
        if (pageMessages == null) {
            pageMessages = new ArrayList<String>();
        }

        request.setAttribute("pageMessages", pageMessages);
        // Phase B.4 jmesa PR 9 (cohort 7): sdvUtil.renderSubjectsTableWithLimit
        // is gone. JSP shell now includes a vanilla-JS fragment that fetches
        // /pages/viewAllSubjectSdvData asynchronously.
        return gridMap;
    }

    @RequestMapping("/viewAllSubjectSDVtmp")
    public ModelMap viewAllSubjectHandler(HttpServletRequest request, @RequestParam("studyId") int studyId, HttpServletResponse response) {

        if (!mayProceed(request)) {
            try {
                response.sendRedirect(request.getContextPath() + "/MainMenu?message=authentication_failed");
            } catch (Exception e) {
                logger.error("Error while redirecting to MainMenu: ", e);
            }
            return null;
        }
    ResourceBundleProvider.updateLocale(LocaleResolver.getLocale(request));
        // Reseting the side info panel set by SecureControler Mantis Issue: 8680.
        // Todo need something to reset panel from all the Spring Controllers
        StudyInfoPanel panel = new StudyInfoPanel();
        panel.reset();
        HttpSession session = request.getSession();
        request.getSession().setAttribute("panel", panel);

        ModelMap gridMap = new ModelMap();
        //set up request attributes for sidebar
        //Not necessary when using old page design...
        // setUpSidebar(request);
        boolean showMoreLink = false;
        if(session.getAttribute("tableFacadeRestore") != null && session.getAttribute("tableFacadeRestore") == "false") {
            session.setAttribute("tableFacadeRestore","true");
            session.setAttribute("sSdvRestore", "false");
            showMoreLink = true;
        }else if(request.getParameter("showMoreLink")!=null){
            showMoreLink = Boolean.parseBoolean(request.getParameter("showMoreLink").toString());
        }else if(session.getAttribute("sdv_showMoreLink")!=null) {
            showMoreLink = Boolean.parseBoolean(session.getAttribute("sdv_showMoreLink")+"");
        } else {
            showMoreLink = true;
        }
        request.setAttribute("showMoreLink", showMoreLink+"");
        session.setAttribute("sdv_showMoreLink", showMoreLink+"");
        request.setAttribute("studyId", studyId);
        String restore = (String)request.getAttribute("sdv_restore");
        restore = restore != null && restore.length()>0 ? restore : "false";
        request.setAttribute("sdv_restore", restore);
        //request.setAttribute("imagePathPrefix","../");
        //We need a study subject id for the first tab;
        Integer studySubjectId = (Integer) request.getAttribute("studySubjectId");
        studySubjectId = studySubjectId == null || studySubjectId == 0 ? 0 : studySubjectId;
        request.setAttribute("studySubjectId", studySubjectId);

        //set up the elements for the view's filter box
        // sdvUtil.prepareSDVSelectElements(request,studyBean);

        ArrayList<String> pageMessages = asArrayList(request.getAttribute("pageMessages"), String.class);
        if (pageMessages == null) {
            pageMessages = new ArrayList<String>();
        }

        request.setAttribute("pageMessages", pageMessages);
        // Phase B.4 jmesa PR 9 (cohort 7): sdvUtil.renderEventCRFTableWithLimit
        // is gone — replaced by /pages/viewAllSubjectSdvData + JSP fragment.
        return gridMap;
    }

    @RequestMapping("/viewAllSubjectSDVform")
    public ModelMap viewAllSubjectFormHandler(HttpServletRequest request, HttpServletResponse response, @RequestParam("studyId") int studyId) {

        ModelMap gridMap = new ModelMap();
        String pattern = "MM/dd/yyyy";
        SimpleDateFormat sdf = new SimpleDateFormat(pattern);

        //  List<StudyEventBean> studyEventBeans = studyEventDAO.findAllByStudy(studyBean);
        //  List<EventCRFBean> eventCRFBeans = sdvUtil.getAllEventCRFs(studyEventBeans);

        //set up the parameters to take part in filtering
        ServletRequestDataBinder dataBinder = new ServletRequestDataBinder(new SdvFilterDataBean());
        dataBinder.setAllowedFields(new String[] { "study_subject_id", "studyEventDefinition", "studyEventStatus", "eventCRFStatus", "sdvRequirement",
            "eventcrfSDVStatus", "startUpdatedDate", "endDate", "eventCRFName" });

        dataBinder.registerCustomEditor(java.util.Date.class, new CustomDateEditor(sdf, true));
        dataBinder.bind(request);
        
        request.setAttribute("studyId", studyId);

        ArrayList<String> pageMessages = asArrayList(request.getAttribute("pageMessages"), String.class);
        if (pageMessages == null) {
            pageMessages = new ArrayList<String>();
        }

        request.setAttribute("pageMessages", pageMessages);
        // Phase B.4 jmesa PR 9 (cohort 7): sdvUtil.renderEventCRFTableWithLimit
        // is gone — replaced by /pages/viewAllSubjectSdvData + JSP fragment.
        return gridMap;
    }

    /*  @RequestMapping("/viewSubjectAggregateSDV")
    public ModelMap viewSubjectAggregateHandler(HttpServletRequest request,
                                                @RequestParam("studyId") int studyId) {

        ModelMap gridMap = new ModelMap();

        //set up request attributes for sidebar
        setUpSidebar(request);
        String sdvMatrix = sdvUtil.renderSubjectsAggregateTable(studyId,request);
        gridMap.addAttribute(SUBJECT_SDV_TABLE_ATTRIBUTE,sdvMatrix);
        return gridMap;
    }*/

    //method = RequestMethod.POST
    @RequestMapping("/handleSDVPost")
    public String sdvAllSubjectsFormHandler(HttpServletRequest request, HttpServletResponse response, @RequestParam("studyId") int studyId,
            @RequestParam("redirection") String redirection, ModelMap model) {

        //The application is POSTing parameters with the name "sdvCheck_" plus the
        //Event CRF id, so the parameter is sdvCheck_534.

        Enumeration<String> paramNames = asEnumeration(request.getParameterNames(), String.class);
        Map<String, String> parameterMap = new HashMap<String, String>();
        String tmpName = "";
        for (; paramNames.hasMoreElements();) {
            tmpName = paramNames.nextElement();
            if (tmpName.contains(SDVUtil.CHECKBOX_NAME)) {
                parameterMap.put(tmpName, request.getParameter(tmpName));
            }
        }
        request.setAttribute("sdv_restore", "true");

        //For the messages that appear in the left column of the results page
        ArrayList<String> pageMessages = new ArrayList<String>();

        //In this case, no checked event CRFs were submitted
        if (parameterMap.isEmpty()) {
            pageMessages.add("None of the Event CRFs were selected for SDV.");
            request.setAttribute("pageMessages", pageMessages);
            sdvUtil.forwardRequestFromController(request, response, "/pages/" + redirection);

        }
        List<Integer> eventCRFIds = sdvUtil.getListOfSdvEventCRFIds(parameterMap.keySet());
        boolean updateCRFs = sdvUtil.setSDVerified(eventCRFIds, getCurrentUser(request).getId(), true);

        if (updateCRFs) {
            pageMessages.add("The Event CRFs have been source data verified.");
        } else {

            pageMessages
                    .add("There was a problem with submitting the Event CRF verification to the database. Is it possible that the database system is down temporarily?");

        }
        request.setAttribute("pageMessages", pageMessages);

        //model.addAttribute("allParams",parameterMap);
        //model.addAttribute("verified",updateCRFs);
        sdvUtil.forwardRequestFromController(request, response, "/pages/" + redirection);

        //The name of the view, as in allSdvResult.jsp
        return null;

    }

    @RequestMapping("/handleSDVGet")
    public String sdvOneCRFFormHandler(HttpServletRequest request, HttpServletResponse response, @RequestParam("crfId") int crfId,
            @RequestParam("redirection") String redirection, ModelMap model) {

        if (!mayProceed(request)) {
            try {
                response.sendRedirect(request.getContextPath() + "/MainMenu?message=authentication_failed");
            } catch (Exception e) {
                logger.error("Error while redirecting to MainMenu: ", e);
            }
            return null;
        }
        //For the messages that appear in the left column of the results page
        ArrayList<String> pageMessages = new ArrayList<String>();

        List<Integer> eventCRFIds = new ArrayList<Integer>();
        eventCRFIds.add(crfId);
        boolean updateCRFs = sdvUtil.setSDVerified(eventCRFIds, getCurrentUser(request).getId(), true);

        if (updateCRFs) {
            pageMessages.add("The Event CRFs have been source data verified.");
        } else {

            pageMessages
                    .add("There was a problem with submitting the Event CRF verification to the database. Is it possible that the database system is down temporarily?");

        }
        request.setAttribute("pageMessages", pageMessages);

        request.setAttribute("sdv_restore", "true");

        //model.addAttribute("allParams",parameterMap);
        //model.addAttribute("verified",updateCRFs);
        sdvUtil.forwardRequestFromController(request, response, "/pages/" + redirection);

        //The name of the view, as in allSdvResult.jsp
        return null;

    }

    @RequestMapping("/handleSDVRemove")
    public String changeSDVHandler(HttpServletRequest request, HttpServletResponse response, @RequestParam("crfId") int crfId,
            @RequestParam("redirection") String redirection, ModelMap model) {

        //For the messages that appear in the left column of the results page
        ArrayList<String> pageMessages = new ArrayList<String>();

        List<Integer> eventCRFIds = new ArrayList<Integer>();
        eventCRFIds.add(crfId);
        boolean updateCRFs = sdvUtil.setSDVerified(eventCRFIds, getCurrentUser(request).getId(), false);

        if (updateCRFs) {
            pageMessages.add("The application has unset SDV for the Event CRF.");
        } else {

            pageMessages
                    .add("There was a problem with submitting the Event CRF verification to the database. Is it possible that the database system is down temporarily?");

        }
        request.setAttribute("pageMessages", pageMessages);
        request.setAttribute("sdv_restore", "true");

        //model.addAttribute("allParams",parameterMap);
        //model.addAttribute("verified",updateCRFs);
        sdvUtil.forwardRequestFromController(request, response, "/pages/" + redirection);

        //The name of the view, as in allSdvResult.jsp
        return null;

    }

    @RequestMapping("/sdvStudySubject")
    public String sdvStudySubjectHandler(HttpServletRequest request, HttpServletResponse response, @RequestParam("theStudySubjectId") int studySubjectId,
            @RequestParam("redirection") String redirection, ModelMap model) {

        //For the messages that appear in the left column of the results page
        ArrayList<String> pageMessages = new ArrayList<String>();

        List<Integer> studySubjectIds = new ArrayList<Integer>();
        studySubjectIds.add(studySubjectId);
        boolean updateCRFs = sdvUtil.setSDVStatusForStudySubjects(studySubjectIds, getCurrentUser(request).getId(), true);

        if (updateCRFs) {
            pageMessages.add("The Subject has been source data verified.");
        } else {

            pageMessages
                    .add("There was a problem with submitting the Event CRF verification to the database. Is it possible that the database system is down temporarily?");

        }
        request.setAttribute("pageMessages", pageMessages);
        request.setAttribute("s_sdv_restore", "true");
        sdvUtil.forwardRequestFromController(request, response, "/pages/" + redirection);
        return null;
    }

    @RequestMapping("/unSdvStudySubject")
    public String unSdvStudySubjectHandler(HttpServletRequest request, HttpServletResponse response, @RequestParam("theStudySubjectId") int studySubjectId,
            @RequestParam("redirection") String redirection, ModelMap model) {

        ArrayList<String> pageMessages = new ArrayList<String>();
        List<Integer> studySubjectIds = new ArrayList<Integer>();

        studySubjectIds.add(studySubjectId);
        boolean updateCRFs = sdvUtil.setSDVStatusForStudySubjects(studySubjectIds, getCurrentUser(request).getId(), false);

        if (updateCRFs) {
            pageMessages.add("The application has unset SDV for the Event CRF.");
        } else {
            pageMessages
                    .add("There was a problem with submitting the Event CRF verification to the database. Is it possible that the database system is down temporarily?");
        }
        request.setAttribute("pageMessages", pageMessages);
        request.setAttribute("s_sdv_restore", "true");
        sdvUtil.forwardRequestFromController(request, response, "/pages/" + redirection);
        return null;

    }

    @RequestMapping("/sdvStudySubjects")
    public String sdvStudySubjectsHandler(HttpServletRequest request, HttpServletResponse response, @RequestParam("studyId") int studyId,
            @RequestParam("redirection") String redirection, ModelMap model) {

        //The application is POSTing parameters with the name "sdvCheck_" plus the
        //Event CRF id, so the parameter is sdvCheck_534.

        Enumeration<String> paramNames = asEnumeration(request.getParameterNames(), String.class);
        Map<String, String> parameterMap = new HashMap<String, String>();
        String tmpName = "";
        for (; paramNames.hasMoreElements();) {
            tmpName = paramNames.nextElement();
            if (tmpName.contains(SDVUtil.CHECKBOX_NAME)) {
                parameterMap.put(tmpName, request.getParameter(tmpName));
            }
        }
        request.setAttribute("s_sdv_restore", "true");

        //For the messages that appear in the left column of the results page
        ArrayList<String> pageMessages = new ArrayList<String>();

        //In this case, no checked event CRFs were submitted
        if (parameterMap.isEmpty()) {
            pageMessages.add("None of the Study Subjects were selected for SDV.");
            request.setAttribute("pageMessages", pageMessages);
            sdvUtil.forwardRequestFromController(request, response, "/pages/" + redirection);

        }
        List<Integer> studySubjectIds = sdvUtil.getListOfStudySubjectIds(parameterMap.keySet());
        boolean updateCRFs = sdvUtil.setSDVStatusForStudySubjects(studySubjectIds, getCurrentUser(request).getId(), true);

        if (updateCRFs) {
            pageMessages.add("The Event CRFs have been source data verified.");
        } else {

            pageMessages
                    .add("There was a problem with submitting the Event CRF verification to the database. Is it possible that the database system is down temporarily?");

        }
        request.setAttribute("pageMessages", pageMessages);

        //model.addAttribute("allParams",parameterMap);
        //model.addAttribute("verified",updateCRFs);
        sdvUtil.forwardRequestFromController(request, response, "/pages/" + redirection);

        //The name of the view, as in allSdvResult.jsp
        return null;

    }

    private UserAccountBean getCurrentUser (HttpServletRequest request){
        UserAccountBean ub = (UserAccountBean)request.getSession().getAttribute("userBean");
        return ub;
    }

    public static void main(String[] args) throws ParseException {

        String pattern = "MM/dd/yyyy";
        SimpleDateFormat sdf = new SimpleDateFormat(pattern);

        Date date = sdf.parse("01/01/2007");
        System.out.println("date = " + date);

    }

	 private boolean mayProceed(HttpServletRequest request) {
        StudyUserRoleBean currentRole = (StudyUserRoleBean)request.getSession().getAttribute("userRole");
        Role r = currentRole.getRole();

        if (r.equals(Role.STUDYDIRECTOR) || r.equals(Role.COORDINATOR) || r.equals(Role.MONITOR)) {
            return true;
        }

        return false;
    }
}
