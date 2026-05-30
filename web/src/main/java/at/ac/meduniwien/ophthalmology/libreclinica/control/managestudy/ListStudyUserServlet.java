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
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.control.core.SecureController;
import at.ac.meduniwien.ophthalmology.libreclinica.control.form.FormProcessor;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.login.UserAccountDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.view.Page;
import at.ac.meduniwien.ophthalmology.libreclinica.web.InsufficientPermissionException;
import at.ac.meduniwien.ophthalmology.libreclinica.web.bean.EntityBeanTable;
import at.ac.meduniwien.ophthalmology.libreclinica.web.bean.StudyUserRoleRow;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;

/**
 * Lists all the users in a study
 * 
 * @author jxu
 * 
 * @version CVS: $Id: ListStudyUserServlet.java 9771 2007-08-28 15:26:26Z
 *          thickerson $
 * 
 */
public class ListStudyUserServlet extends SecureController {

    /**
	 * 
	 */
	private static final long serialVersionUID = -2575908192455735571L;

	/**
     *
     */
    @Override
    public void mayProceed() throws InsufficientPermissionException {
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
        UserAccountDAO udao = new UserAccountDAO(sm.getDataSource());
        ArrayList<StudyUserRoleBean> users = udao.findAllAssignedUsersByStudy(currentStudy.getId());

        EntityBeanTable table = fp.getEntityBeanTable();
        ArrayList<StudyUserRoleRow> allStudyUserRows = StudyUserRoleRow.generateRowsFromBeans(users);

        String[] columns =
            { resword.getString("user_name"), resword.getString("first_name"), resword.getString("last_name"), resword.getString("role"),
                resword.getString("study_name"), resword.getString("status"), resword.getString("actions") };
        table.setColumns(new ArrayList<String>(Arrays.asList(columns)));
        table.hideColumnLink(6);
        table.setQuery("ListStudyUser", new HashMap<>());
        table.addLink(restext.getString("assign_new_user_to_current_study"), "AssignUserToStudy");
        table.setRows(allStudyUserRows);
        table.computeDisplay();

        request.setAttribute("table", table);
        request.setAttribute("siteRoleMap", Role.siteRoleMap);
        request.setAttribute("studyRoleMap", Role.studyRoleMap);
        request.setAttribute("study", currentStudy);
        // request.setAttribute("users", users);
        forwardPage(Page.LIST_USER_IN_STUDY);

    }

}
