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
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.control.core.SecureController;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.login.UserAccountDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.i18n.util.ResourceBundleProvider;
import at.ac.meduniwien.ophthalmology.libreclinica.view.Page;
import at.ac.meduniwien.ophthalmology.libreclinica.web.InsufficientPermissionException;

/**
 * @author jxu
 */
public class ViewStudyUserServlet extends SecureController {

	private static final long serialVersionUID = -3392123982341988217L;
    
    @Override
    public void mayProceed() throws InsufficientPermissionException {
        if (ub.isSysAdmin()) {
            return;
        }

        if (currentRole.getRole().equals(Role.STUDYDIRECTOR) || currentRole.getRole().equals(Role.COORDINATOR)) {
            return;
        }

        addPageMessage(respage.getString("no_have_correct_privilege_current_study") + " " + respage.getString("change_study_contact_sysadmin"));
        throw new InsufficientPermissionException(Page.LIST_USER_IN_STUDY_SERVLET, resexception.getString("not_study_director"), "1");
    }

    @Override
    public void processRequest() throws Exception {
        UserAccountDAO udao = new UserAccountDAO(sm.getDataSource());
        String name = request.getParameter("name");
        String studyIdString = request.getParameter("studyId");
    	 
        if ((name == null || name.trim().isEmpty()) || (studyIdString == null || studyIdString.trim().isEmpty())) {
            addPageMessage(respage.getString("please_choose_a_user_to_view"));
            forwardPage(Page.LIST_USER_IN_STUDY_SERVLET);
     	} else {
            int studyId = Integer.parseInt(studyIdString.trim());

            UserAccountBean user = udao.findByUserName(name);
            request.setAttribute("user", user);

            StudyUserRoleBean uRole = udao.findRoleByUserNameAndStudyId(name, studyId);
            request.setAttribute("uRole", uRole);

            StudyDAO sdao = new StudyDAO(sm.getDataSource());
            StudyBean study = sdao.findByPK(studyId);
            request.setAttribute("uStudy", study);

            // Role names are specified in localised properties
            request.setAttribute("siteRoleMap", Role.siteRoleMap);
            
            // To provide the view with the correct date format pattern is locale sensitive
            String dateFormatPattern = ResourceBundleProvider.getFormatBundle().getString("date_format_string");
            request.setAttribute("dateFormatPattern", dateFormatPattern);
            request.setAttribute("action","");

            forwardPage(Page.VIEW_USER_IN_STUDY);
        }

    }

}
