/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 */
package at.ac.meduniwien.ophthalmology.libreclinica.control.extract;

import static at.ac.meduniwien.ophthalmology.libreclinica.core.util.ClassCastHelper.asArrayList;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.admin.CRFBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.extract.DatasetBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyEventDefinitionBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyGroupClassBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.ItemBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.ItemFormMetadataBean;
import at.ac.meduniwien.ophthalmology.libreclinica.control.core.SecureController;
import at.ac.meduniwien.ophthalmology.libreclinica.control.form.FormProcessor;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.admin.CRFDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyGroupClassDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.ItemDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.ItemFormMetadataDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.i18n.core.LocaleResolver;
import at.ac.meduniwien.ophthalmology.libreclinica.view.Page;
import at.ac.meduniwien.ophthalmology.libreclinica.web.InsufficientPermissionException;

/**
 * @author jxu
 *
 * Views selected items for creating dataset, aslo allow user to de-select or
 * select all items in a study
 */
public class ViewSelectedServlet extends SecureController {

    /**
	 * 
	 */
	private static final long serialVersionUID = 5458397988268565354L;
	Locale locale;

    // < ResourceBundlerestext,resexception,respage;

    @Override
    public void mayProceed() throws InsufficientPermissionException {

        locale = LocaleResolver.getLocale(request);
        // < restext =
        // ResourceBundle.getBundle("at.ac.meduniwien.ophthalmology.libreclinica.i18n.notes",locale);
        // < respage =
        // ResourceBundle.getBundle("at.ac.meduniwien.ophthalmology.libreclinica.i18n.page_messages",locale);
        // <
        // resexception=ResourceBundle.getBundle("at.ac.meduniwien.ophthalmology.libreclinica.i18n.exceptions",locale);

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

    /*
     * setup study groups, tbh, added july 2007 FIXME in general a repeated set
     * of code -- need to create a superclass which will contain this class, tbh
     */
    public void setUpStudyGroups() {
        ArrayList<StudyGroupClassBean> sgclasses = asArrayList(session.getAttribute("allSelectedGroups"), StudyGroupClassBean.class);
        if (sgclasses == null || sgclasses.size() == 0) {
            StudyDAO studydao = new StudyDAO(sm.getDataSource());
            StudyGroupClassDAO sgclassdao = new StudyGroupClassDAO(sm.getDataSource());
            StudyBean theStudy = (StudyBean) studydao.findByPK(sm.getUserBean().getActiveStudyId());
            sgclasses = sgclassdao.findAllActiveByStudy(theStudy);
        }
        session.setAttribute("allSelectedGroups", sgclasses);
        session.setAttribute("numberOfStudyGroups", sgclasses.size());
        request.setAttribute("allSelectedGroups", sgclasses);
    }

    @Override
    public void processRequest() throws Exception {

        DatasetBean db = (DatasetBean) session.getAttribute("newDataset");
        @SuppressWarnings("unchecked")
		HashMap<StudyEventDefinitionBean, ArrayList<CRFBean>> events = (HashMap<StudyEventDefinitionBean, ArrayList<CRFBean>>) session.getAttribute(CreateDatasetServlet.EVENTS_FOR_CREATE_DATASET);
        if (events == null) {
            events = new HashMap<>();
        }
        request.setAttribute("eventlist", events);

        CRFDAO crfdao = new CRFDAO(sm.getDataSource());
        ItemDAO idao = new ItemDAO(sm.getDataSource());
        ItemFormMetadataDAO imfdao = new ItemFormMetadataDAO(sm.getDataSource());
        ArrayList<String> ids = CreateDatasetServlet.allSedItemIdsInStudy(events, crfdao, idao);// new
                                                                                        // ArrayList();
        // ArrayList allItemsInStudy = EditSelectedServlet.selectAll(events,
        // crfdao, idao);
        // for (int j = 0; j < allItemsInStudy.size(); j++) {
        // ItemBean item = (ItemBean) allItemsInStudy.get(j);
        // Integer itemId = new Integer(item.getId());
        // if (!ids.contains(itemId)) {
        // ids.add(itemId);
        // }
        // }
        session.setAttribute("numberOfStudyItems", new Integer(ids.size()).toString());

        ArrayList<ItemBean> items = new ArrayList<>();
        if (db == null || db.getItemIds().size() == 0) {
            session.setAttribute("allSelectedItems", items);
            setUpStudyGroups();// FIXME can it be that we have no selected
            // items and
            // some selected groups? tbh
            forwardPage(Page.CREATE_DATASET_VIEW_SELECTED);
            return;
        }

        items = getAllSelected(db, idao, imfdao);

        session.setAttribute("allSelectedItems", items);

        FormProcessor fp = new FormProcessor(request);
        String status = fp.getString("status");
        if (!(status == null || status.trim().isEmpty()) && "html".equalsIgnoreCase(status)) {
            forwardPage(Page.CREATE_DATASET_VIEW_SELECTED_HTML);
        } else {
            setUpStudyGroups();
            forwardPage(Page.CREATE_DATASET_VIEW_SELECTED);
        }

    }

    public static ArrayList<ItemBean> getAllSelected(DatasetBean db, ItemDAO idao, ItemFormMetadataDAO imfdao) throws Exception {
        ArrayList<ItemBean> items = new ArrayList<>();
        // ArrayList itemIds = db.getItemIds();
        ArrayList<ItemBean> itemDefCrfs = db.getItemDefCrf();

        for (int i = 0; i < itemDefCrfs.size(); i++) {
            ItemBean item = (ItemBean) itemDefCrfs.get(i);
            item.setSelected(true);
            ArrayList<ItemFormMetadataBean> metas = imfdao.findAllByItemId(item.getId());
            item.setItemMetas(metas);
            items.add(item);
        }

        return items;

    }

}
