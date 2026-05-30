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

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.GroupClassType;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyGroupBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyGroupClassBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.SubjectGroupMapBean;
import at.ac.meduniwien.ophthalmology.libreclinica.control.core.SecureController;
import at.ac.meduniwien.ophthalmology.libreclinica.control.form.FormProcessor;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyGroupClassDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyGroupDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.SubjectGroupMapDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.view.Page;
import at.ac.meduniwien.ophthalmology.libreclinica.web.InsufficientPermissionException;

/**
 * @author jxu, modified by ywang
 *
 * Views details of a Subject Group Class
 */
public class ViewSubjectGroupClassServlet extends SecureController {
    /**
	 * 
	 */
	private static final long serialVersionUID = -842052669736496090L;

	@Override
    public void mayProceed() throws InsufficientPermissionException {
        if (ub.isSysAdmin()) {
            return;
        }
        if (currentRole.getRole().equals(Role.STUDYDIRECTOR) || currentRole.getRole().equals(Role.COORDINATOR)) {
            return;
        }
        addPageMessage(respage.getString("no_have_correct_privilege_current_study") + "\n" + respage.getString("change_study_contact_sysadmin"));
        throw new InsufficientPermissionException(Page.SUBJECT_GROUP_CLASS_LIST_SERVLET, resexception.getString("not_study_director"), "1");

    }

    @Override
    public void processRequest() throws Exception {
        FormProcessor fp = new FormProcessor(request);
        int classId = fp.getInt("id");

        if (classId == 0) {

            addPageMessage(respage.getString("please_choose_a_subject_group_class_to_view"));
            forwardPage(Page.SUBJECT_GROUP_CLASS_LIST_SERVLET);
        } else {
            StudyGroupClassDAO sgcdao = new StudyGroupClassDAO(sm.getDataSource());
            StudyGroupDAO sgdao = new StudyGroupDAO(sm.getDataSource());
            SubjectGroupMapDAO sgmdao = new SubjectGroupMapDAO(sm.getDataSource());
            StudyDAO studyDao = new StudyDAO(sm.getDataSource());

            StudyGroupClassBean sgcb = (StudyGroupClassBean) sgcdao.findByPK(classId);
            StudyBean study = (StudyBean)studyDao.findByPK(sgcb.getStudyId());

            checkRoleByUserAndStudy(ub, sgcb.getStudyId(), study.getParentStudyId());

            // YW 09-19-2007 <<
            sgcb.setGroupClassTypeName(GroupClassType.get(sgcb.getGroupClassTypeId()).getName());
            // YW >>

            ArrayList<StudyGroupBean> groups = sgdao.findAllByGroupClass(sgcb);
            ArrayList<StudyGroupBean> studyGroups = new ArrayList<>();

            for (int i = 0; i < groups.size(); i++) {
                StudyGroupBean sg = (StudyGroupBean) groups.get(i);
                ArrayList<SubjectGroupMapBean> subjectMaps = sgmdao.findAllByStudyGroupClassAndGroup(sgcb.getId(), sg.getId());
                sg.setSubjectMaps(subjectMaps);
                // YW<<
                studyGroups.add(sg);
                // YW>>
            }

            request.setAttribute("group", sgcb);
            // request.setAttribute("studyGroups", groups);
            request.setAttribute("studyGroups", studyGroups);
            forwardPage(Page.VIEW_SUBJECT_GROUP_CLASS);
        }
    }

}
