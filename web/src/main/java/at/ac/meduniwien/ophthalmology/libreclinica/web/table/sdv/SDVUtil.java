/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.web.table.sdv;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import javax.sql.DataSource;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.admin.CRFBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.EventDefinitionCRFBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyEventBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudySubjectBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.CRFVersionBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.EventCRFBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.admin.CRFDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.EventDefinitionCRFDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyEventDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudySubjectDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.CRFVersionDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.EventCRFDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.SourceDataVerification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Helpers for the Source Data Verification (SDV) workflow.
 *
 * <p>Phase B.4 jmesa PR 9 (cohort 7): the jmesa-based table-rendering
 * methods ({@code renderEventCRFTableWithLimit},
 * {@code renderSubjectsTableWithLimit},
 * {@code renderSubjectsAggregateTable}, {@code getSubjectRows},
 * {@code getSubjectAggregateRows}, plus all jmesa filter/sort wiring
 * helpers and the inner {@code SDVView} / {@code NoEscapeHtmlCellEditor}
 * classes) have been deleted. The per-event-CRF table is now rendered
 * client-side by {@code WEB-INF/jsp/include/viewAllSubjectSdvTable.jsp}
 * fetching {@code /pages/viewAllSubjectSdvData} (see
 * {@code SDVController.viewEventCrfSdvData}).
 *
 * <p>What remains: the form-post handlers' shared helpers (set-SDV
 * mutations, parameter-name parsing, CRF-name lookups, and a
 * forward-from-controller utility).
 */
public class SDVUtil {

    private static final Logger logger = LoggerFactory.getLogger(SDVUtil.class);

    /**
     * The {@code <input type=checkbox name="sdvCheck_<eventCRFId>">}
     * name prefix the JSP fragment uses for row checkboxes. Matched
     * by {@link #getListOfSdvEventCRFIds(Collection)} when the form
     * is submitted.
     */
    public static final String CHECKBOX_NAME = "sdvCheck_";

    private DataSource dataSource;

    public DataSource getDataSource() {
        return dataSource;
    }

    public void setDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Mark every checked Event CRF as SDV verified (or un-verify if
     * {@code setVerification == false}). Called by
     * {@code /pages/handleSDVPost}, {@code /handleSDVGet}, and
     * {@code /handleSDVRemove}.
     *
     * @return {@code true} on success; {@code false} on the first DAO
     *         error.
     */
    public boolean setSDVerified(List<Integer> eventCRFIds, int userId, boolean setVerification) {
        if (eventCRFIds == null || eventCRFIds.isEmpty()) {
            return true;
        }
        EventCRFDAO eventCRFDAO = new EventCRFDAO(dataSource);
        for (Integer eventCrfId : eventCRFIds) {
            try {
                eventCRFDAO.setSDVStatus(setVerification, userId, eventCrfId);
            } catch (Exception exc) {
                logger.error("Failed to set SDV status on event CRF {}", eventCrfId, exc);
                return false;
            }
        }
        return true;
    }

    /**
     * For each study subject, mark every Event CRF that is
     * complete-or-locked AND has an SDV-required configuration as
     * verified. Called by {@code /sdvStudySubject},
     * {@code /unSdvStudySubject}, and {@code /sdvStudySubjects}.
     */
    public boolean setSDVStatusForStudySubjects(List<Integer> studySubjectIds, int userId, boolean setVerification) {
        if (studySubjectIds == null || studySubjectIds.isEmpty()) {
            return true;
        }
        EventCRFDAO eventCRFDAO = new EventCRFDAO(dataSource);
        StudySubjectDAO studySubjectDAO = new StudySubjectDAO(dataSource);
        EventDefinitionCRFDAO eventDefinitionCrfDAO = new EventDefinitionCRFDAO(dataSource);
        StudyEventDAO studyEventDAO = new StudyEventDAO(dataSource);
        CRFDAO crfDAO = new CRFDAO(dataSource);

        for (Integer studySubjectId : studySubjectIds) {
            ArrayList<EventCRFBean> eventCrfs =
                    eventCRFDAO.getEventCRFsByStudySubjectCompleteOrLocked(studySubjectId);
            StudySubjectBean studySubject = (StudySubjectBean) studySubjectDAO.findByPK(studySubjectId);
            for (EventCRFBean eventCRFBean : eventCrfs) {
                CRFBean crfBean = crfDAO.findByVersionId(eventCRFBean.getCRFVersionId());
                StudyEventBean studyEvent = (StudyEventBean) studyEventDAO.findByPK(eventCRFBean.getStudyEventId());
                EventDefinitionCRFBean edc = eventDefinitionCrfDAO
                        .findByStudyEventDefinitionIdAndCRFIdAndStudyId(
                                studyEvent.getStudyEventDefinitionId(), crfBean.getId(),
                                studySubject.getStudyId());
                if (edc.getId() == 0) {
                    edc = eventDefinitionCrfDAO.findForStudyByStudyEventDefinitionIdAndCRFId(
                            studyEvent.getStudyEventDefinitionId(), crfBean.getId());
                }
                if (edc.getSourceDataVerification() == SourceDataVerification.AllREQUIRED
                        || edc.getSourceDataVerification() == SourceDataVerification.PARTIALREQUIRED) {
                    try {
                        eventCRFDAO.setSDVStatus(setVerification, userId, eventCRFBean.getId());
                    } catch (Exception exc) {
                        logger.error("Failed to set SDV status on event CRF {}", eventCRFBean.getId(), exc);
                        return false;
                    }
                }
            }
            studySubjectDAO.update(studySubject);
        }
        return true;
    }

    /**
     * Parse the {@code sdvCheck_<id>} form param names from a POST
     * and extract the EventCRF ids.
     */
    public List<Integer> getListOfSdvEventCRFIds(Collection<String> paramsContainingIds) {
        List<Integer> eventCRFWithSDV = new ArrayList<>();
        if (paramsContainingIds == null || paramsContainingIds.isEmpty()) {
            return eventCRFWithSDV;
        }
        for (String param : paramsContainingIds) {
            int id = stripPrefixFromParam(param);
            if (id != 0) {
                eventCRFWithSDV.add(id);
            }
        }
        return eventCRFWithSDV;
    }

    /**
     * Parse the {@code sdvCheck_<id>} form param names from a POST
     * and extract the StudySubject ids. (Same prefix convention as
     * EventCRF ids; the caller picks the right interpretation.)
     */
    public List<Integer> getListOfStudySubjectIds(Set<String> paramsContainingIds) {
        List<Integer> studySubjectIds = new ArrayList<>();
        if (paramsContainingIds == null || paramsContainingIds.isEmpty()) {
            return studySubjectIds;
        }
        for (String param : paramsContainingIds) {
            int id = stripPrefixFromParam(param);
            if (id != 0) {
                studySubjectIds.add(id);
            }
        }
        return studySubjectIds;
    }

    private static int stripPrefixFromParam(String param) {
        if (param != null && param.contains(CHECKBOX_NAME)) {
            return Integer.parseInt(param.substring(param.indexOf("_") + 1));
        }
        return 0;
    }

    /**
     * Forward to another URL (used by handlers that need to return
     * the operator to a fresh table view after a state-change post).
     */
    public void forwardRequestFromController(HttpServletRequest request, HttpServletResponse response, String path) {
        try {
            request.getRequestDispatcher(path).forward(request, response);
        } catch (ServletException | IOException e) {
            logger.error("Error while forwarding to {}", path, e);
        }
    }

    /**
     * CRF name lookup by CRF-version id. Used by the SDV data
     * endpoint to build the "CRF name / version" column.
     */
    public String getCRFName(int crfVersionId) {
        CRFVersionDAO crfVersionDAO = new CRFVersionDAO(dataSource);
        CRFDAO crfDAO = new CRFDAO(dataSource);
        CRFVersionBean version = (CRFVersionBean) crfVersionDAO.findByPK(crfVersionId);
        if (version == null) return "";
        CRFBean crf = (CRFBean) crfDAO.findByPK(version.getCrfId());
        return crf == null ? "" : crf.getName();
    }

    /**
     * CRF-version name lookup by CRF-version id.
     */
    public String getCRFVersionName(int crfVersionId) {
        CRFVersionDAO crfVersionDAO = new CRFVersionDAO(dataSource);
        CRFVersionBean version = (CRFVersionBean) crfVersionDAO.findByPK(crfVersionId);
        return version == null ? "" : version.getName();
    }
}
