/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 */
package at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.admin.CRFBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.EventDefinitionCRFBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyEventDefinitionBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.CRFVersionBean;
import at.ac.meduniwien.ophthalmology.libreclinica.control.core.SecureController;
import at.ac.meduniwien.ophthalmology.libreclinica.control.form.FormProcessor;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.admin.CRFDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.EventDefinitionCRFDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyEventDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyEventDefinitionDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.CRFVersionDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.i18n.core.LocaleResolver;
import at.ac.meduniwien.ophthalmology.libreclinica.view.Page;
import at.ac.meduniwien.ophthalmology.libreclinica.web.InsufficientPermissionException;
import at.ac.meduniwien.ophthalmology.libreclinica.web.bean.EntityBeanTable;
import at.ac.meduniwien.ophthalmology.libreclinica.web.bean.StudyEventDefinitionRow;

/**
 * Processes user reuqest to generate study event definition list
 *
 * @author jxu
 *
 */
public class ListEventDefinitionServlet extends SecureController {

    /**
	 * 
	 */
	private static final long serialVersionUID = 5997027756299567929L;
	Locale locale;

    // < ResourceBundleresword, resworkflow, respage,resexception;

    /**
     * Checks whether the user has the correct privilege
     */
    @Override
    public void mayProceed() throws InsufficientPermissionException {
        session.removeAttribute("tmpCRFIdMap");
        session.removeAttribute("crfsWithVersion");
        session.removeAttribute("eventDefinitionCRFs");

        locale = LocaleResolver.getLocale(request);
        // < resword =
        // ResourceBundle.getBundle("at.ac.meduniwien.ophthalmology.libreclinica.i18n.words",locale);
        // <
        // resexception=ResourceBundle.getBundle(
        // "at.ac.meduniwien.ophthalmology.libreclinica.i18n.exceptions",locale);
        // < respage =
        // ResourceBundle.getBundle("at.ac.meduniwien.ophthalmology.libreclinica.i18n.page_messages",
        // locale);
        // < resworkflow =
        //ResourceBundle.getBundle("at.ac.meduniwien.ophthalmology.libreclinica.i18n.workflow",locale)
        // ;

        if (ub.isSysAdmin()) {
            return;
        }

        if (currentRole.getRole().equals(Role.STUDYDIRECTOR) || currentRole.getRole().equals(Role.COORDINATOR)) {
            return;
        }

        addPageMessage(respage.getString("no_have_correct_privilege_current_study") + respage.getString("change_study_contact_sysadmin"));
        throw new InsufficientPermissionException(Page.MANAGE_STUDY_SERVLET, resexception.getString("not_study_director"), "1");

    }

    /**
     * Processes the request
     */
    @Override
    public void processRequest() throws Exception {

        StudyEventDefinitionDAO edao = new StudyEventDefinitionDAO(sm.getDataSource());
        EventDefinitionCRFDAO edcdao = new EventDefinitionCRFDAO(sm.getDataSource());
        CRFDAO crfDao = new CRFDAO(sm.getDataSource());
        CRFVersionDAO crfVersionDao = new CRFVersionDAO(sm.getDataSource());
        ArrayList<StudyEventDefinitionBean> seds = edao.findAllByStudy(currentStudy);

        // request.setAttribute("seds", seds);

        StudyEventDAO sedao = new StudyEventDAO(sm.getDataSource());
        for (int i = 0; i < seds.size(); i++) {
            StudyEventDefinitionBean sed = (StudyEventDefinitionBean) seds.get(i);
            ArrayList<EventDefinitionCRFBean> eventDefinitionCRFlist = edcdao.findAllParentsByDefinition(sed.getId());
            Map<String, String> crfWithDefaultVersion = new LinkedHashMap<>();
            for(EventDefinitionCRFBean edcBean : eventDefinitionCRFlist) {
                CRFBean crfBean = crfDao.findByPK(edcBean.getCrfId());
                CRFVersionBean crfVersionBean = (CRFVersionBean) crfVersionDao.findByPK(edcBean.getDefaultVersionId());
                logger.info("ED[" + sed.getName() + "]crf[" + crfBean.getName() + "]dv[" + crfVersionBean.getName() + "]");
                crfWithDefaultVersion.put(crfBean.getName(), crfVersionBean.getName());
            }
            sed.setCrfsWithDefaultVersion(crfWithDefaultVersion);
            logger.info("CRF size [" + sed.getCrfs().size() + "]");
            if (sed.getUpdater().getId() == 0) {
                sed.setUpdater(sed.getOwner());
                sed.setUpdatedDate(sed.getCreatedDate());
            }
            if (isPopulated(sed, sedao)) {
                sed.setPopulated(true);
            }
        }

        FormProcessor fp = new FormProcessor(request);
        EntityBeanTable table = fp.getEntityBeanTable();
        ArrayList<StudyEventDefinitionRow> allStudyRows = StudyEventDefinitionRow.generateRowsFromBeans(seds);

        String[] columns =
            { resword.getString("order"), resword.getString("name"), resword.getString("OID"), resword.getString("repeating"), resword.getString("type"),
                resword.getString("category"), resword.getString("populated"), resword.getString("date_created"), resword.getString("date_updated"),
                resword.getString("CRFs"), resword.getString("default_version"), resword.getString("actions") };
        table.setColumns(new ArrayList<String>(Arrays.asList(columns)));
        // >> tbh #4169 09/2009
        table.hideColumnLink(2);
        table.hideColumnLink(3);
        table.hideColumnLink(4);
        table.hideColumnLink(6);
        table.hideColumnLink(7);
        table.hideColumnLink(8);
        table.hideColumnLink(9);
        table.hideColumnLink(10); // crfs, tbh
        table.hideColumnLink(11);
        table.hideColumnLink(12);
        // << tbh 09/2009
        table.setQuery("ListEventDefinition", new HashMap<>());
        // if (!currentStudy.getStatus().isLocked()) {
        // table.addLink(resworkflow.getString(
        // "create_a_new_study_event_definition"), "DefineStudyEvent");
        // }

        table.setRows(allStudyRows);

        table.setPaginated(false);
        table.computeDisplay();

        request.setAttribute("table", table);
        request.setAttribute("defSize", new Integer(seds.size()));

        if (request.getParameter("read") != null && request.getParameter("read").equals("true")) {
            request.setAttribute("readOnly", true);
        }

        forwardPage(Page.STUDY_EVENT_DEFINITION_LIST);
    }

    /**
     * Checked whether a definition is available to be locked
     *
     * @param sed
     * @return
     */
    private boolean isPopulated(StudyEventDefinitionBean sed, StudyEventDAO sedao) {
        /*
        // checks study event
        ArrayList events = (ArrayList) sedao.findAllByDefinition(sed.getId());
        for (int j = 0; j < events.size(); j++) {
            StudyEventBean event = (StudyEventBean) events.get(j);
            if (!event.getStatus().equals(Status.DELETED) && !event.getStatus().equals(Status.AUTO_DELETED)) {
                return true;
            }
        }
        return false;
        */
        if(sedao.countNotRemovedEvents(sed.getId())>0) {
            return true;
        } else {
            return false;
        }
    }

}
