/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 */
package org.akaza.openclinica.control.login;

import java.util.ArrayList;
import java.util.Locale;

import org.akaza.openclinica.bean.core.Role;
import org.akaza.openclinica.bean.core.Status;
import org.akaza.openclinica.bean.login.StudyUserRoleBean;
import org.akaza.openclinica.bean.managestudy.StudyBean;
import org.akaza.openclinica.bean.service.StudyParameterValueBean;
import org.akaza.openclinica.bean.service.StudyParamsConfig;
import org.akaza.openclinica.bean.core.SubjectEventStatus;
import org.akaza.openclinica.control.SpringServletAccess;
import org.akaza.openclinica.control.core.SecureController;
import org.akaza.openclinica.control.form.FormProcessor;
import org.akaza.openclinica.control.form.Validator;
import org.akaza.openclinica.dao.login.UserAccountDAO;
import org.akaza.openclinica.dao.managestudy.DiscrepancyNoteDAO;
import org.akaza.openclinica.dao.managestudy.EventDefinitionCRFDAO;
import org.akaza.openclinica.dao.managestudy.StudyDAO;
import org.akaza.openclinica.dao.managestudy.StudyEventDAO;
import org.akaza.openclinica.dao.managestudy.StudyEventDefinitionDAO;
import org.akaza.openclinica.dao.managestudy.StudyGroupClassDAO;
import org.akaza.openclinica.dao.managestudy.StudyGroupDAO;
import org.akaza.openclinica.dao.managestudy.StudySubjectDAO;
import org.akaza.openclinica.dao.service.StudyConfigService;
import org.akaza.openclinica.dao.service.StudyParameterValueDAO;
import org.akaza.openclinica.dao.submit.EventCRFDAO;
import org.akaza.openclinica.dao.submit.SubjectDAO;
import org.akaza.openclinica.dao.submit.SubjectGroupMapDAO;
import org.akaza.openclinica.i18n.core.LocaleResolver;
import org.akaza.openclinica.view.Page;
import org.akaza.openclinica.web.InsufficientPermissionException;
import org.akaza.openclinica.web.table.sdv.SDVUtil;

/**
 * @author jxu
 *
 * Processes the request of changing current study
 */
public class ChangeStudyServlet extends SecureController {
    /**
	 * 
	 */
	private static final long serialVersionUID = 1371772469011700414L;
	/**
     * Checks whether the user has the correct privilege
     */

    Locale locale;
    private StudyEventDefinitionDAO studyEventDefinitionDAO;
    private SubjectDAO subjectDAO;
    private StudySubjectDAO studySubjectDAO;
    private StudyEventDAO studyEventDAO;
    private StudyGroupClassDAO studyGroupClassDAO;
    private SubjectGroupMapDAO subjectGroupMapDAO;
    private StudyDAO studyDAO;
    private EventCRFDAO eventCRFDAO;
    private EventDefinitionCRFDAO eventDefintionCRFDAO;
    private StudyGroupDAO studyGroupDAO;
    private DiscrepancyNoteDAO discrepancyNoteDAO;
    private StudyParameterValueDAO studyParameterValueDAO;

    // < ResourceBundlerestext;

    @Override
    public void mayProceed() throws InsufficientPermissionException {

        locale = LocaleResolver.getLocale(request);
        // < restext =
        // ResourceBundle.getBundle("org.akaza.openclinica.i18n.notes",locale);

    }

    @Override
    public void processRequest() throws Exception {

        String action = request.getParameter("action");// action sent by user
        UserAccountDAO udao = new UserAccountDAO(sm.getDataSource());
        StudyDAO sdao = new StudyDAO(sm.getDataSource());

        ArrayList<StudyUserRoleBean> studies = udao.findStudyByUser(ub.getName(), sdao.findAll());
        request.setAttribute("siteRoleMap", Role.siteRoleMap);
        request.setAttribute("studyRoleMap", Role.studyRoleMap);
        if(request.getAttribute("label")!=null) {
            String label = (String) request.getAttribute("label");
            if(label.length()>0) {
                request.setAttribute("label", label);
            }
        }

        ArrayList<StudyUserRoleBean> validStudies = new ArrayList<>();
        for (int i = 0; i < studies.size(); i++) {
            StudyUserRoleBean sr = (StudyUserRoleBean) studies.get(i);
            StudyBean study = (StudyBean) sdao.findByPK(sr.getStudyId());
            if (study != null && study.getStatus().equals(Status.PENDING)) {
                sr.setStatus(study.getStatus());
            }
            validStudies.add(sr);
        }


        if (action == null || action.trim().isEmpty()) {
            request.setAttribute("studies", validStudies);

            forwardPage(Page.CHANGE_STUDY);
        } else {
            if ("confirm".equalsIgnoreCase(action)) {
                logger.info("confirm");
                confirmChangeStudy(studies);

            } else if ("submit".equalsIgnoreCase(action)) {
                logger.info("submit");
                changeStudy();
            }
        }

    }

    private void confirmChangeStudy(ArrayList<StudyUserRoleBean> studies) throws Exception {
        Validator v = new Validator(request);
        FormProcessor fp = new FormProcessor(request);
        v.addValidation("studyId", Validator.IS_AN_INTEGER);

        errors = v.validate();

        if (!errors.isEmpty()) {
            request.setAttribute("studies", studies);
            forwardPage(Page.CHANGE_STUDY);
        } else {
            int studyId = fp.getInt("studyId");
            logger.info("new study id:" + studyId);
            for (StudyUserRoleBean studyWithRole : studies) {
                if (studyWithRole.getStudyId() == studyId) {
                    request.setAttribute("studyId", new Integer(studyId));
                    session.setAttribute("studyWithRole", studyWithRole);
                    request.setAttribute("currentStudy", currentStudy);
                    forwardPage(Page.CHANGE_STUDY_CONFIRM);
                    return;

                }
            }
            addPageMessage(restext.getString("no_study_selected"));

            forwardPage(Page.CHANGE_STUDY);
        }
    }

    private void changeStudy() throws Exception {
        FormProcessor fp = new FormProcessor(request);
        int studyId = fp.getInt("studyId");
        int prevStudyId = currentStudy.getId();

        StudyDAO sdao = new StudyDAO(sm.getDataSource());
        StudyBean current = (StudyBean) sdao.findByPK(studyId);

        // reset study parameters -jxu 02/09/2007
        StudyParameterValueDAO spvdao = new StudyParameterValueDAO(sm.getDataSource());

        ArrayList<StudyParamsConfig> studyParameters = spvdao.findParamConfigByStudy(current);
        current.setStudyParameters(studyParameters);
        int parentStudyId = currentStudy.getParentStudyId()>0?currentStudy.getParentStudyId():currentStudy.getId();
        StudyParameterValueBean parentSPV = spvdao.findByHandleAndStudy(parentStudyId, "subjectIdGeneration");
        current.getStudyParameterConfig().setSubjectIdGeneration(parentSPV.getValue());
        String idSetting = current.getStudyParameterConfig().getSubjectIdGeneration();
        if (idSetting.equals("auto editable") || idSetting.equals("auto non-editable")) {
            int nextLabel = this.getStudySubjectDAO().findTheGreatestLabel() + 1;
            request.setAttribute("label", new Integer(nextLabel).toString());
        }

        StudyConfigService scs = new StudyConfigService(sm.getDataSource());
        if (current.getParentStudyId() <= 0) {// top study
            scs.setParametersForStudy(current);

        } else {
            // YW <<
            if (current.getParentStudyId() > 0) {
                current.setParentStudyName(((StudyBean) sdao.findByPK(current.getParentStudyId())).getName());

            }
            // YW 06-12-2007>>
            scs.setParametersForSite(current);

        }
        if (current.getStatus().equals(Status.DELETED) || current.getStatus().equals(Status.AUTO_DELETED)) {
            session.removeAttribute("studyWithRole");
            addPageMessage(restext.getString("study_choosed_removed_restore_first"));
        } else {
            session.setAttribute("study", current);
            currentStudy = current;
            // change user's active study id
            UserAccountDAO udao = new UserAccountDAO(sm.getDataSource());
            ub.setActiveStudyId(current.getId());
            ub.setUpdater(ub);
            ub.setUpdatedDate(new java.util.Date());
            udao.update(ub);

            if (current.getParentStudyId() > 0) {
                /*
                 * The Role decription will be set depending on whether the user
                 * logged in at study lever or site level. issue-2422
                 */
                ArrayList<Role> roles = Role.toArrayList();
                for (Role role : roles) {
                    switch (role.getId()) {
                    case 2:
                        role.setDescription("site_Study_Coordinator");
                        break;
                    case 3:
                        role.setDescription("site_Study_Director");
                        break;
                    case 4:
                        role.setDescription("site_investigator");
                        break;
                    case 5:
                        role.setDescription("site_Data_Entry_Person");
                        break;
                    case 6:
                        role.setDescription("site_monitor");
                        break;
                    case 7:
                        role.setDescription("site_Data_Entry_Person2");
                        break;
                    default:
                        // logger.info("No role matched when setting role description");
                    }
                }
            } else {
                /*
                 * If the current study is a site, we will change the role
                 * description. issue-2422
                 */
                ArrayList<Role> roles = Role.toArrayList();
                for (Role role : roles) {
                    switch (role.getId()) {
                    case 2:
                        role.setDescription("Study_Coordinator");
                        break;
                    case 3:
                        role.setDescription("Study_Director");
                        break;
                    case 4:
                        role.setDescription("investigator");
                        break;
                    case 5:
                        role.setDescription("Data_Entry_Person");
                        break;
                    case 6:
                        role.setDescription("monitor");
                        break;
                    default:
                        // logger.info("No role matched when setting role description");
                    }
                }
            }

            currentRole = (StudyUserRoleBean) session.getAttribute("studyWithRole");
            session.setAttribute("userRole", currentRole);
            session.removeAttribute("studyWithRole");
            addPageMessage(restext.getString("current_study_changed_succesfully"));
        }
        ub.incNumVisitsToMainMenu();
        // YW 2-18-2008, if study has been really changed <<
        if (prevStudyId != studyId) {
            session.removeAttribute("eventsForCreateDataset");
            session.setAttribute("tableFacadeRestore", "false");
        }
        request.setAttribute("studyJustChanged", "yes");
        // YW >>

        //Integer assignedDiscrepancies = getDiscrepancyNoteDAO().countAllItemDataByStudyAndUser(currentStudy, ub);
        Integer assignedDiscrepancies = getDiscrepancyNoteDAO().getViewNotesCountWithFilter(" AND dn.assigned_user_id ="
                + ub.getId() + " AND (dn.resolution_status_id=1 OR dn.resolution_status_id=2 OR dn.resolution_status_id=3)", currentStudy);
        request.setAttribute("assignedDiscrepancies", assignedDiscrepancies == null ? 0 : assignedDiscrepancies);

        // Phase B.4 jmesa PR 4c: investigator / RA / RA2 study-subject
        // matrix is now rendered client-side via
        // managestudy/include/findSubjectsTable.jsp + /FindSubjectsData,
        // so no server-side setup is needed.
        if (currentRole.isMonitor()) {
            setupSubjectSDVTable();
        } else if (currentRole.isCoordinator() || currentRole.isDirector()) {
            if (currentStudy.getStatus().isPending()) {
                response.sendRedirect(request.getContextPath() + Page.MANAGE_STUDY_MODULE.getFileName());
                return;
            }
            setupStudySiteStatisticsTable();
            setupSubjectEventStatusStatisticsTable();
            setupStudySubjectStatusStatisticsTable();
            if (currentStudy.getParentStudyId() == 0) {
                setupStudyStatisticsTable();
            }

        }

        forwardPage(Page.MENU);

    }

    private void setupSubjectSDVTable() {

        request.setAttribute("studyId", currentStudy.getId());
        String sdvMatrix = getSDVUtil().renderEventCRFTableWithLimit(request, currentStudy.getId(), "");
        request.setAttribute("sdvMatrix", sdvMatrix);
    }

    /* Phase B.4 jmesa PR 3: same migration as MainMenuServlet — four
       small stats tables replaced with plain List<Map<String,Object>>
       request attributes that menu.jsp renders via c:forEach. */

    private void setupStudySubjectStatusStatisticsTable() {
        StudySubjectDAO dao = getStudySubjectDAO();
        Integer totalStudySubjects = dao.getCountofStudySubjects(currentStudy);
        Status[] statuses = { Status.AVAILABLE, Status.SIGNED, Status.DELETED };
        java.util.List<java.util.Map<String, Object>> rows = new java.util.ArrayList<java.util.Map<String, Object>>();
        for (Status status : statuses) {
            Integer count = dao.getCountofStudySubjectsBasedOnStatus(currentStudy, status);
            long pct = totalStudySubjects == 0 ? 0L
                    : Math.round((count.doubleValue() / totalStudySubjects.doubleValue()) * 100);
            java.util.Map<String, Object> row = new java.util.LinkedHashMap<String, Object>();
            row.put("status", status.getName());
            row.put("studySubjects", count);
            row.put("percentage", pct);
            rows.add(row);
        }
        request.setAttribute("studySubjectStatusStatisticsRows", rows);
    }

    private void setupSubjectEventStatusStatisticsTable() {
        StudyEventDAO eventDao = getStudyEventDAO();
        SubjectEventStatus[] subjectEventStatuses = {
                SubjectEventStatus.SCHEDULED,
                SubjectEventStatus.DATA_ENTRY_STARTED,
                SubjectEventStatus.COMPLETED,
                SubjectEventStatus.SIGNED,
                SubjectEventStatus.LOCKED,
                SubjectEventStatus.SKIPPED,
                SubjectEventStatus.STOPPED };
        java.util.List<java.util.Map<String, Object>> rows = new java.util.ArrayList<java.util.Map<String, Object>>();
        Integer totalEvents = eventDao.getCountofEvents(currentStudy);
        for (SubjectEventStatus subjectEventStatus : subjectEventStatuses) {
            Integer count = eventDao.getCountofEventsBasedOnEventStatus(currentStudy, subjectEventStatus);
            long pct = totalEvents == 0 ? 0L
                    : Math.round((count.doubleValue() / totalEvents.doubleValue()) * 100);
            java.util.Map<String, Object> row = new java.util.LinkedHashMap<String, Object>();
            row.put("status", subjectEventStatus.getName());
            row.put("studySubjects", count);
            row.put("percentage", pct);
            rows.add(row);
        }
        request.setAttribute("subjectEventStatusStatisticsRows", rows);
    }

    @SuppressWarnings("unchecked")
    private void setupStudySiteStatisticsTable() {
        StudyDAO studyDao = getStudyDAO();
        StudySubjectDAO subjectDao = getStudySubjectDAO();
        java.util.List<StudyBean> studies = (java.util.List<StudyBean>) studyDao.findAll(currentStudy.getId());
        java.util.List<java.util.Map<String, Object>> rows = new java.util.ArrayList<java.util.Map<String, Object>>();
        for (StudyBean studyBean : studies) {
            Integer count = subjectDao.getCountofStudySubjectsAtStudyOrSite(studyBean);
            Integer expected = studyBean.getExpectedTotalEnrollment();
            long pct = expected == null || expected == 0 ? 0L
                    : Math.round((count.doubleValue() / expected.doubleValue()) * 100);
            java.util.Map<String, Object> row = new java.util.LinkedHashMap<String, Object>();
            row.put("name", studyBean.getName());
            row.put("enrolled", count);
            row.put("expectedTotalEnrollment", expected);
            row.put("percentage", pct);
            rows.add(row);
        }
        request.setAttribute("studySiteStatisticsRows", rows);
    }

    private void setupStudyStatisticsTable() {
        StudySubjectDAO subjectDao = getStudySubjectDAO();
        Integer count = subjectDao.getCountofStudySubjectsAtStudy(currentStudy);
        Integer expected = currentStudy.getExpectedTotalEnrollment();
        long pct = expected == null || expected == 0 ? 0L
                : Math.round((count.doubleValue() / expected.doubleValue()) * 100);
        java.util.Map<String, Object> row = new java.util.LinkedHashMap<String, Object>();
        row.put("name", currentStudy.getName());
        row.put("enrolled", count);
        row.put("expectedTotalEnrollment", expected);
        row.put("percentage", pct);
        java.util.List<java.util.Map<String, Object>> rows = new java.util.ArrayList<java.util.Map<String, Object>>();
        rows.add(row);
        request.setAttribute("studyStatisticsRows", rows);
    }

public StudyEventDefinitionDAO getStudyEventDefinitionDao() {
        studyEventDefinitionDAO = studyEventDefinitionDAO == null ? new StudyEventDefinitionDAO(sm.getDataSource()) : studyEventDefinitionDAO;
        return studyEventDefinitionDAO;
    }

    public SubjectDAO getSubjectDAO() {
        subjectDAO = this.subjectDAO == null ? new SubjectDAO(sm.getDataSource()) : subjectDAO;
        return subjectDAO;
    }

    public StudySubjectDAO getStudySubjectDAO() {
        studySubjectDAO = this.studySubjectDAO == null ? new StudySubjectDAO(sm.getDataSource()) : studySubjectDAO;
        return studySubjectDAO;
    }

    public StudyGroupClassDAO getStudyGroupClassDAO() {
        studyGroupClassDAO = this.studyGroupClassDAO == null ? new StudyGroupClassDAO(sm.getDataSource()) : studyGroupClassDAO;
        return studyGroupClassDAO;
    }

    public SubjectGroupMapDAO getSubjectGroupMapDAO() {
        subjectGroupMapDAO = this.subjectGroupMapDAO == null ? new SubjectGroupMapDAO(sm.getDataSource()) : subjectGroupMapDAO;
        return subjectGroupMapDAO;
    }

    public StudyEventDAO getStudyEventDAO() {
        studyEventDAO = this.studyEventDAO == null ? new StudyEventDAO(sm.getDataSource()) : studyEventDAO;
        return studyEventDAO;
    }

    public StudyDAO getStudyDAO() {
        studyDAO = this.studyDAO == null ? new StudyDAO(sm.getDataSource()) : studyDAO;
        return studyDAO;
    }

    public EventCRFDAO getEventCRFDAO() {
        eventCRFDAO = this.eventCRFDAO == null ? new EventCRFDAO(sm.getDataSource()) : eventCRFDAO;
        return eventCRFDAO;
    }

    public EventDefinitionCRFDAO getEventDefinitionCRFDAO() {
        eventDefintionCRFDAO = this.eventDefintionCRFDAO == null ? new EventDefinitionCRFDAO(sm.getDataSource()) : eventDefintionCRFDAO;
        return eventDefintionCRFDAO;
    }

    public StudyGroupDAO getStudyGroupDAO() {
        studyGroupDAO = this.studyGroupDAO == null ? new StudyGroupDAO(sm.getDataSource()) : studyGroupDAO;
        return studyGroupDAO;
    }

    public DiscrepancyNoteDAO getDiscrepancyNoteDAO() {
        discrepancyNoteDAO = this.discrepancyNoteDAO == null ? new DiscrepancyNoteDAO(sm.getDataSource()) : discrepancyNoteDAO;
        return discrepancyNoteDAO;
    }

    public SDVUtil getSDVUtil() {
        return (SDVUtil) SpringServletAccess.getApplicationContext(context).getBean("sdvUtil");
    }

	public StudyParameterValueDAO getStudyParameterValueDAO() {
	     studyParameterValueDAO = this.studyParameterValueDAO == null ? new StudyParameterValueDAO(sm.getDataSource()) : studyParameterValueDAO;
		return studyParameterValueDAO;
	}

	public void setStudyParameterValueDAO(StudyParameterValueDAO studyParameterValueDAO) {
		this.studyParameterValueDAO = studyParameterValueDAO;
	}

    
}
