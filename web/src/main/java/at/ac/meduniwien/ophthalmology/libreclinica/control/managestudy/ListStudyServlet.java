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
import java.util.Locale;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.admin.DisplayStudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.control.core.SecureController;
import at.ac.meduniwien.ophthalmology.libreclinica.control.form.FormProcessor;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.i18n.core.LocaleResolver;
import at.ac.meduniwien.ophthalmology.libreclinica.view.Page;
import at.ac.meduniwien.ophthalmology.libreclinica.web.InsufficientPermissionException;
import at.ac.meduniwien.ophthalmology.libreclinica.web.bean.DisplayStudyRow;
import at.ac.meduniwien.ophthalmology.libreclinica.web.bean.EntityBeanTable;

/**
 * @author jxu
 *
 * @version CVS: $Id: ListStudyServlet.java 13702 2009-12-21 20:06:48Z kkrumlian $
 */
public class ListStudyServlet extends SecureController {

    /**
	 * 
	 */
	private static final long serialVersionUID = 1660543632636179574L;
	Locale locale;

    // < ResourceBundle resword,restext,respage,resexception;

    /**
     *
     */
    @Override
    public void mayProceed() throws InsufficientPermissionException {

        locale = LocaleResolver.getLocale(request);

        if (ub.isSysAdmin() || ub.isTechAdmin()) {
            return;
        }
        // Role r = currentRole.getRole();
        // if (r.equals(Role.STUDYDIRECTOR) || r.equals(Role.COORDINATOR)) {
        // return;
        // }
        addPageMessage(respage.getString("no_have_correct_privilege_current_study") + respage.getString("change_study_contact_sysadmin"));
        throw new InsufficientPermissionException(Page.MENU_SERVLET, resexception.getString("may_not_submit_data"), "1");
    }

    /**
     * Finds all the studies
     *
     */
    @Override
    public void processRequest() throws Exception {

        StudyDAO sdao = new StudyDAO(sm.getDataSource());
        ArrayList<StudyBean> studies = sdao.findAll();
        // find all parent studies
        ArrayList<StudyBean> parents = sdao.findAllParents();
        ArrayList<DisplayStudyBean> displayStudies = new ArrayList<>();

        for (int i = 0; i < parents.size(); i++) {
            StudyBean parent = (StudyBean) parents.get(i);
            ArrayList<StudyBean> children = new ArrayList<StudyBean>(sdao.findAllByParent(parent.getId()));
            DisplayStudyBean displayStudy = new DisplayStudyBean();
            displayStudy.setParent(parent);
            displayStudy.setChildren(children);
            displayStudies.add(displayStudy);

        }

        FormProcessor fp = new FormProcessor(request);
        EntityBeanTable table = fp.getEntityBeanTable();
        ArrayList<DisplayStudyRow> allStudyRows = DisplayStudyRow.generateRowsFromBeans(displayStudies);

        String[] columns = { resword.getString("name"), resword.getString("unique_identifier"), resword.getString("OID"),
                resword.getString("principal_investigator"), resword.getString("facility_name"), resword.getString("date_created"), resword.getString("status"),
                resword.getString("actions") };
        table.setColumns(new ArrayList<String>(Arrays.asList(columns)));
        table.hideColumnLink(2);
        table.hideColumnLink(6);
        table.setQuery("ListStudy", new HashMap<>());
        table.setRows(allStudyRows);
        table.computeDisplay();

        request.setAttribute("table", table);
        // request.setAttribute("studies", studies);
        session.setAttribute("fromListSite", "no");

        resetPanel();
        panel.setStudyInfoShown(false);
        panel.setOrderedData(true);
        setToPanel(resword.getString("in_the_application"), "");
        if (parents.size() > 0) {
            setToPanel(resword.getString("studies"), new Integer(parents.size()).toString());
        }
        if (studies.size() > 0) {
            setToPanel(resword.getString("sites"), new Integer(studies.size() - parents.size()).toString());
        }
        forwardPage(Page.STUDY_LIST);

    }

    @Override
    protected String getAdminServlet() {
        return SecureController.ADMIN_SERVLET_CODE;
    }

}
