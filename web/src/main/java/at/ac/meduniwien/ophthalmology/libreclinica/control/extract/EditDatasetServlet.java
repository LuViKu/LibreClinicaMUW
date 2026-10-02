/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 */
package at.ac.meduniwien.ophthalmology.libreclinica.control.extract;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.admin.CRFBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Status;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.extract.DatasetBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyEventDefinitionBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyGroupClassBean;
import at.ac.meduniwien.ophthalmology.libreclinica.control.core.SecureController;
import at.ac.meduniwien.ophthalmology.libreclinica.control.form.FormProcessor;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.admin.CRFDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.extract.DatasetDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyEventDefinitionDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyGroupClassDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.view.Page;
import at.ac.meduniwien.ophthalmology.libreclinica.web.InsufficientPermissionException;

/**
 * @author thickerson
 *
 *
 */
@SuppressWarnings("all")
public class EditDatasetServlet extends SecureController {

	private static final long serialVersionUID = -6853606383296360409L;

	public static String getLink(int dsId) {
        return "EditDataset?dsId=" + dsId;
    }

    @Override
    public void processRequest() throws Exception {
        FormProcessor fp = new FormProcessor(request);

        int dsId = fp.getInt("dsId");
        DatasetBean dataset = initializeAttributes(dsId);

        StudyDAO sdao = new StudyDAO(sm.getDataSource());
        StudyBean study = (StudyBean)sdao.findByPK(dataset.getStudyId());
        // Checking if user has permission to access the current study/site
        checkRoleByUserAndStudy(ub, study.getParentStudyId(), study.getId());

        // Checking the dataset belongs to current study or a site of current study
        if (study.getId() != currentStudy.getId() && study.getParentStudyId() != currentStudy.getId()) {
            addPageMessage(respage.getString("no_have_correct_privilege_current_study")
                    + " " + respage.getString("change_active_study_or_contact"));
            forwardPage(Page.MENU_SERVLET);
            return;
        }

        if((currentRole.isMonitor() || currentRole.isInvestigator()) && dataset.getOwnerId() != ub.getId()){
            addPageMessage(respage.getString("no_have_correct_privilege_current_study")
                    + " " + respage.getString("change_active_study_or_contact"));
            forwardPage(Page.MENU_SERVLET);
            return;
        }

        @SuppressWarnings("unchecked")
		LinkedHashMap<StudyEventDefinitionBean, ArrayList<CRFBean>> events = 
			(LinkedHashMap<StudyEventDefinitionBean, ArrayList<CRFBean>>) session.getAttribute("eventsForCreateDataset");
        // << tbh
        CRFDAO crfdao = new CRFDAO(sm.getDataSource());

        // >> tbh 11/2009
        if (events == null || events.isEmpty()) {
            events = new LinkedHashMap<>();
            StudyEventDefinitionDAO seddao = new StudyEventDefinitionDAO(sm.getDataSource());

            StudyBean studyWithEventDefinitions = currentStudy;
            if (currentStudy.getParentStudyId() > 0) {
                studyWithEventDefinitions = new StudyBean();
                studyWithEventDefinitions.setId(currentStudy.getParentStudyId());

            }
            ArrayList<StudyEventDefinitionBean> seds = seddao.findAllActiveByStudy(studyWithEventDefinitions);
            for (int i = 0; i < seds.size(); i++) {
                StudyEventDefinitionBean sed = (StudyEventDefinitionBean) seds.get(i);
                ArrayList<CRFBean> crfs = crfdao.findAllActiveByDefinition(sed);
                if (!crfs.isEmpty()) {
                    events.put(sed, crfs);
                }
            }
            if (events.isEmpty()) {
                addPageMessage(respage.getString("not_have_study_definitions_assigned"));
                forwardPage(Page.VIEW_DATASETS);
            } else {
                request.setAttribute("eventlist", events);
                session.setAttribute("eventsForCreateDataset", events);
            }
        }
        request.setAttribute("dataset", dataset);
        request.setAttribute("statuses", getStatuses());
        forwardPage(Page.EDIT_DATASET);
    }

    @Override
    public void mayProceed() throws InsufficientPermissionException {

        if (ub.isSysAdmin()) {
            return;
        }
        if (currentRole.getRole().equals(Role.STUDYDIRECTOR) || currentRole.getRole().equals(Role.COORDINATOR)
            || currentRole.getRole().equals(Role.INVESTIGATOR) || currentRole.getRole().equals(Role.MONITOR)) {
            return;
        }

        addPageMessage(respage.getString("no_have_correct_privilege_current_study") + respage.getString("change_study_contact_sysadmin"));
        throw new InsufficientPermissionException(Page.MENU, resexception.getString("not_allowed_access_extract_data_servlet"), "1");

    }

    private ArrayList<Status> getStatuses() {
        Status statusesArray[] = { Status.AVAILABLE, Status.PENDING, Status.PRIVATE, Status.UNAVAILABLE };
        List<Status> statuses = Arrays.asList(statusesArray);
        return new ArrayList<>(statuses);
    }

    /**
     * Initialize data of a DatasetBean and set session attributes for displaying selected data of this DatasetBean
     *
     * @return
     *
     */
    // @author ywang (Feb, 2008)
    public DatasetBean initializeAttributes(int datasetId) {
        DatasetDAO dsdao = new DatasetDAO(sm.getDataSource());
        DatasetBean db = dsdao.initialDatasetData(datasetId);
        session.setAttribute("newDataset", db);
        session.setAttribute("allItems", db.getItemDefCrf().clone());
        session.setAttribute("allSelectedItems", db.getItemDefCrf().clone());
        StudyGroupClassDAO sgcdao = new StudyGroupClassDAO(sm.getDataSource());
        StudyDAO studydao = new StudyDAO(sm.getDataSource());
        StudyBean theStudy = (StudyBean) studydao.findByPK(sm.getUserBean().getActiveStudyId());
        ArrayList<StudyGroupClassBean> allSelectedGroups = sgcdao.findAllActiveByStudy(theStudy);
        ArrayList<Integer> selectedSubjectGroupIds = db.getSubjectGroupIds();
        if (selectedSubjectGroupIds != null && allSelectedGroups != null) {
            for (Integer id : selectedSubjectGroupIds) {
                for (int i = 0; i < allSelectedGroups.size(); ++i) {
                    if (allSelectedGroups.get(i).getId() == id) {
                        allSelectedGroups.get(i).setSelected(true);
                        break;
                    }
                }
            }
        }
        session.setAttribute("allSelectedGroups", allSelectedGroups);

        return db;
    }
}
