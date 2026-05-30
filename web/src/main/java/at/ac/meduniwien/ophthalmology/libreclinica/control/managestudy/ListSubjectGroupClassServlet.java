/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 */
package at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyGroupBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyGroupClassBean;
import at.ac.meduniwien.ophthalmology.libreclinica.control.core.SecureController;
import at.ac.meduniwien.ophthalmology.libreclinica.control.form.FormProcessor;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyGroupClassDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyGroupDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.i18n.core.LocaleResolver;
import at.ac.meduniwien.ophthalmology.libreclinica.view.Page;
import at.ac.meduniwien.ophthalmology.libreclinica.web.InsufficientPermissionException;
import at.ac.meduniwien.ophthalmology.libreclinica.web.bean.EntityBeanTable;
import at.ac.meduniwien.ophthalmology.libreclinica.web.bean.StudyGroupClassRow;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;

/**
 * Lists all the subject group classes in a study
 *
 * @author jxu
 *
 */
public class ListSubjectGroupClassServlet extends SecureController {

    /**
	 * 
	 */
	private static final long serialVersionUID = 4441881774413551129L;
	Locale locale;

    // < ResourceBundleresexception,respage,resword;

    /**
     *
     */
    @Override
    public void mayProceed() throws InsufficientPermissionException {

        locale = LocaleResolver.getLocale(request);
        // <
        // resexception=ResourceBundle.getBundle(
        // "at.ac.meduniwien.ophthalmology.libreclinica.i18n.exceptions",locale);
        // < respage =
        // ResourceBundle.getBundle("at.ac.meduniwien.ophthalmology.libreclinica.i18n.page_messages",
        // locale);
        // < resword =
        // ResourceBundle.getBundle("at.ac.meduniwien.ophthalmology.libreclinica.i18n.words",locale);

        if (ub.isSysAdmin()) {
            return;
        }

        if (currentRole.getRole().equals(Role.STUDYDIRECTOR) || currentRole.getRole().equals(Role.COORDINATOR)) {
            return;
        }

        addPageMessage(respage.getString("no_have_correct_privilege_current_study") + respage.getString("change_study_contact_sysadmin"));
        throw new InsufficientPermissionException(Page.MANAGE_STUDY_SERVLET, resexception.getString("not_study_director"), "1");

    }

    @Override
    public void processRequest() throws Exception {
        FormProcessor fp = new FormProcessor(request);
        StudyGroupClassDAO sgcdao = new StudyGroupClassDAO(sm.getDataSource());
        // YW <<
        StudyDAO stdao = new StudyDAO(sm.getDataSource());
        int parentStudyId = currentStudy.getParentStudyId();
        ArrayList<StudyGroupClassBean> groups = new ArrayList<>();
        if (parentStudyId > 0) {
            StudyBean parentStudy = (StudyBean) stdao.findByPK(parentStudyId);
            groups = sgcdao.findAllByStudy(parentStudy);
        } else {
            groups = sgcdao.findAllByStudy(currentStudy);
        }
        // YW >>

        StudyGroupDAO sgdao = new StudyGroupDAO(sm.getDataSource());
        for (StudyGroupClassBean group : groups) {
            ArrayList<StudyGroupBean> studyGroups = sgdao.findAllByGroupClass(group);
            group.setStudyGroups(studyGroups);

        }
        EntityBeanTable table = fp.getEntityBeanTable();
        ArrayList<StudyGroupClassRow> allGroupRows = StudyGroupClassRow.generateRowsFromBeans(groups);
        boolean isParentStudy = currentStudy.getParentStudyId() > 0 ? false : true;
        request.setAttribute("isParentStudy", isParentStudy);

        String[] columns =
            { resword.getString("subject_group_class"), resword.getString("type"), resword.getString("subject_assignment"), resword.getString("study_name"),
                resword.getString("subject_groups"), resword.getString("status"), resword.getString("actions") };
        table.setColumns(new ArrayList<String>(Arrays.asList(columns)));
        table.hideColumnLink(4);
        table.hideColumnLink(6);
        table.setQuery("ListSubjectGroupClass", new HashMap<>());
        table.setRows(allGroupRows);
        table.computeDisplay();

        request.setAttribute("table", table);
        if (request.getParameter("read") != null && request.getParameter("read").equals("true")) {
            request.setAttribute("readOnly", true);
        }
        forwardPage(Page.SUBJECT_GROUP_CLASS_LIST);

    }

}
