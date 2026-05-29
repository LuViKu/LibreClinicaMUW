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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.admin.CRFBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
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
import at.ac.meduniwien.ophthalmology.libreclinica.i18n.core.LocaleResolver;
import at.ac.meduniwien.ophthalmology.libreclinica.view.Page;
import at.ac.meduniwien.ophthalmology.libreclinica.web.InsufficientPermissionException;
import at.ac.meduniwien.ophthalmology.libreclinica.web.bean.DatasetRow;
import at.ac.meduniwien.ophthalmology.libreclinica.web.bean.EntityBeanTable;

/**
 * ViewDatasetsServlet.java, the view datasets function accessed from the
 * extract datasets main page.
 *
 * @author thickerson
 *
 *
 *
 */
public class ViewDatasetsServlet extends SecureController {

    /**
	 * 
	 */
	private static final long serialVersionUID = -781606214750305705L;
	Locale locale;

    // < ResourceBundleresword,restext,respage,resexception;

    public static String getLink(int dsId) {
        return "ViewDatasets?action=details&datasetId=" + dsId;
    }

    @Override
    public void processRequest() throws Exception {
        DatasetDAO dsdao = new DatasetDAO(sm.getDataSource());
        String action = request.getParameter("action");
        resetPanel();
        request.setAttribute(STUDY_INFO_PANEL, panel);
        // YW, 2-15-2008 <<
        session.removeAttribute("allSelectedItems");
        session.removeAttribute("allSelectedGroups");
        session.removeAttribute("allItems");
        session.removeAttribute("newDataset");
        // YW >>
        if (action == null || action.trim().isEmpty()) {
            StudyEventDefinitionDAO seddao = new StudyEventDefinitionDAO(sm.getDataSource());
            StudyBean studyWithEventDefinitions = currentStudy;
            if (currentStudy.getParentStudyId() > 0) {
                studyWithEventDefinitions = new StudyBean();
                studyWithEventDefinitions.setId(currentStudy.getParentStudyId());

            }
            ArrayList<StudyEventDefinitionBean> seds = seddao.findAllActiveByStudy(studyWithEventDefinitions);
            CRFDAO crfdao = new CRFDAO(sm.getDataSource());
            HashMap<StudyEventDefinitionBean, ArrayList<CRFBean>> events = new LinkedHashMap<>();
            for (int i = 0; i < seds.size(); i++) {
                StudyEventDefinitionBean sed = (StudyEventDefinitionBean) seds.get(i);
                ArrayList<CRFBean> crfs = crfdao.findAllActiveByDefinition(sed);
                if (!crfs.isEmpty()) {
                    events.put(sed, crfs);
                }
            }
            session.setAttribute("eventsForCreateDataset", events);

            FormProcessor fp = new FormProcessor(request);

            EntityBeanTable table = fp.getEntityBeanTable();
            ArrayList<DatasetBean> datasets = dsdao.findAllByStudyId(currentStudy.getId());

            ArrayList<DatasetRow> datasetRows = DatasetRow.generateRowsFromBeans(datasets);

            String[] columns =
                { resword.getString("dataset_name"), resword.getString("description"), resword.getString("created_by"), resword.getString("created_date"),
                    resword.getString("status"), resword.getString("actions") };
            table.setColumns(new ArrayList<String>(Arrays.asList(columns)));
            table.hideColumnLink(5);
            table.addLink(resword.getString("show_only_my_datasets"), "ViewDatasets?action=owner&ownerId=" + ub.getId());
            table.addLink(resword.getString("create_dataset"), "CreateDataset");
            table.setQuery("ViewDatasets", new HashMap<>());
            table.setRows(datasetRows);
            table.computeDisplay();

            request.setAttribute("table", table);
            // this is the old code that the tabling code replaced:
            // ArrayList datasets = (ArrayList)dsdao.findAll();
            // request.setAttribute("datasets", datasets);
            forwardPage(Page.VIEW_DATASETS);
        } else {
            if ("owner".equalsIgnoreCase(action)) {
                FormProcessor fp = new FormProcessor(request);
                int ownerId = fp.getInt("ownerId");
                EntityBeanTable table = fp.getEntityBeanTable();

                ArrayList<DatasetBean> datasets = dsdao.findByOwnerId(ownerId, currentStudy.getId());
                ArrayList<DatasetRow> datasetRows = DatasetRow.generateRowsFromBeans(datasets);
                String[] columns =
                    { resword.getString("dataset_name"), resword.getString("description"), resword.getString("created_by"), resword.getString("created_date"),
                        resword.getString("status"), resword.getString("actions") };
                table.setColumns(new ArrayList<String>(Arrays.asList(columns)));
                table.hideColumnLink(5);
                table.addLink(resword.getString("show_all_datasets"), "ViewDatasets");
                table.addLink(resword.getString("create_dataset"), "CreateDataset");
                table.setQuery("ViewDatasets?action=owner&ownerId=" + ub.getId(), new HashMap<>());
                table.setRows(datasetRows);
                table.computeDisplay();
                request.setAttribute("table", table);
                // this is the old code:

                // ArrayList datasets = (ArrayList)dsdao.findByOwnerId(ownerId);
                // request.setAttribute("datasets", datasets);
                forwardPage(Page.VIEW_DATASETS);
                // }
            } else if ("details".equalsIgnoreCase(action)) {
                FormProcessor fp = new FormProcessor(request);
                int datasetId = fp.getInt("datasetId");

                DatasetBean db = initializeAttributes(datasetId);
                StudyDAO sdao = new StudyDAO(sm.getDataSource());
                StudyBean study = (StudyBean)sdao.findByPK(db.getStudyId());

                if (study.getId() != currentStudy.getId() && study.getParentStudyId() != currentStudy.getId()) {
                    addPageMessage(respage.getString("no_have_correct_privilege_current_study")
                            + " " + respage.getString("change_active_study_or_contact"));
                    forwardPage(Page.MENU_SERVLET);
                    return;
                }

                /*
                 * EntityBeanTable table = fp.getEntityBeanTable(); ArrayList
                 * datasetRows = DatasetRow.generateRowFromBean(db); String[]
                 * columns = { "Dataset Name", "Description", "Created By",
                 * "Created Date", "Status", "Actions" }; table.setColumns(new
                 * ArrayList(Arrays.asList(columns))); table.hideColumnLink(5);
                 * table.setQuery("ViewDatasets", new HashMap());
                 * table.setRows(datasetRows); table.computeDisplay();
                 * request.setAttribute("table", table);
                 */
                request.setAttribute("dataset", db);

                forwardPage(Page.VIEW_DATASET_DETAILS);
            }
        }

    }

    @Override
    public void mayProceed() throws InsufficientPermissionException {

        locale = LocaleResolver.getLocale(request);

        if (ub.isSysAdmin()) {
            return;
        }

        if  (!( currentRole.getRole().equals(Role.RESEARCHASSISTANT) || currentRole.getRole().equals(Role.RESEARCHASSISTANT2) ) ) {
            return;
        }
    
        addPageMessage(respage.getString("no_have_correct_privilege_current_study") + respage.getString("change_study_contact_sysadmin"));
        throw new InsufficientPermissionException(Page.MENU, resexception.getString("not_allowed_access_extract_data_servlet"), "1");

    }

    /**
     * Initialize data of a DatasetBean and set session attributes for
     * displaying selected data of this DatasetBean
     *
     * @param db
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
