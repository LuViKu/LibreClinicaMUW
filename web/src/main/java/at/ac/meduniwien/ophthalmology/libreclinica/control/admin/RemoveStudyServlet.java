/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 */
package at.ac.meduniwien.ophthalmology.libreclinica.control.admin;

import java.util.ArrayList;
import java.util.Date;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Status;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.extract.DatasetBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.EventDefinitionCRFBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyEventBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyEventDefinitionBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyGroupClassBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudySubjectBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.EventCRFBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.ItemDataBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.SubjectGroupMapBean;
import at.ac.meduniwien.ophthalmology.libreclinica.control.core.SecureController;
import at.ac.meduniwien.ophthalmology.libreclinica.control.form.FormProcessor;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.extract.DatasetDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.login.UserAccountDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.EventDefinitionCRFDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyEventDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyEventDefinitionDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyGroupClassDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudySubjectDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.EventCRFDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.ItemDataDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.SubjectGroupMapDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.view.Page;
import at.ac.meduniwien.ophthalmology.libreclinica.web.InsufficientPermissionException;

/**
 * @author jxu
 *
 * Processes the request of removing a top level study, all the data assoicated
 * with this study will be removed
 */
public class RemoveStudyServlet extends SecureController {
    /**
	 * 
	 */
	private static final long serialVersionUID = -1535010063336494257L;

	/**
     *
     */
    @Override
    public void mayProceed() throws InsufficientPermissionException {
        if (ub.isSysAdmin()) {
            return;
        }

        addPageMessage(respage.getString("no_have_correct_privilege_current_study") + respage.getString("change_study_contact_sysadmin"));
        throw new InsufficientPermissionException(Page.STUDY_LIST_SERVLET, resexception.getString("not_admin"), "1");

    }

    @Override
    public void processRequest() throws Exception {

        StudyDAO sdao = new StudyDAO(sm.getDataSource());
        FormProcessor fp = new FormProcessor(request);
        int studyId = fp.getInt("id");

        StudyBean study = (StudyBean) sdao.findByPK(studyId);
        // find all sites
        ArrayList<StudyBean> sites = sdao.findAllByParent(studyId);

        // find all user and roles in the study, include ones in sites
        UserAccountDAO udao = new UserAccountDAO(sm.getDataSource());
        ArrayList<StudyUserRoleBean> userRoles = udao.findAllByStudyId(studyId);

        // find all subjects in the study, include ones in sites
        StudySubjectDAO ssdao = new StudySubjectDAO(sm.getDataSource());
        ArrayList<StudySubjectBean> subjects = ssdao.findAllByStudy(study);

        // find all events in the study, include ones in sites
        StudyEventDefinitionDAO sefdao = new StudyEventDefinitionDAO(sm.getDataSource());
        ArrayList<StudyEventDefinitionBean> definitions = sefdao.findAllByStudy(study);

        String action = request.getParameter("action");
        if (studyId == 0) {
            addPageMessage(respage.getString("please_choose_a_study_to_remove"));
            forwardPage(Page.STUDY_LIST_SERVLET);
        } else {
            if ("confirm".equalsIgnoreCase(action)) {
                request.setAttribute("studyToRemove", study);

                request.setAttribute("sitesToRemove", sites);

                request.setAttribute("userRolesToRemove", userRoles);

                request.setAttribute("subjectsToRemove", subjects);

                request.setAttribute("definitionsToRemove", definitions);
                forwardPage(Page.REMOVE_STUDY);
            } else {
                logger.info("submit to remove the study");
                // change all statuses to unavailable
                StudyDAO studao = new StudyDAO(sm.getDataSource());
                study.setOldStatus(study.getStatus());
                study.setStatus(Status.DELETED);
                study.setUpdater(ub);
                study.setUpdatedDate(new Date());
                studao.update(study);

                // remove all sites
                for (int i = 0; i < sites.size(); i++) {
                    StudyBean site = (StudyBean) sites.get(i);
                    if (!site.getStatus().equals(Status.DELETED)) {
                        site.setOldStatus(site.getStatus());
                        site.setStatus(Status.AUTO_DELETED);
                        site.setUpdater(ub);
                        site.setUpdatedDate(new Date());
                        sdao.update(site);
                    }
                }

                // remove all users and roles
                for (int i = 0; i < userRoles.size(); i++) {
                    StudyUserRoleBean role = (StudyUserRoleBean) userRoles.get(i);
                    logger.info("remove user role" + role.getName());
                    if (!role.getStatus().equals(Status.DELETED)) {
                        role.setStatus(Status.AUTO_DELETED);
                        role.setUpdater(ub);
                        role.setUpdatedDate(new Date());
                        udao.updateStudyUserRole(role, role.getUserName());
                    }
                }

                // YW << bug fix for that current active study has been deleted
                if (study.getId() == currentStudy.getId()) {
                    currentStudy.setStatus(Status.DELETED);
                    currentRole.setStatus(Status.DELETED);
                }
                // if current active study is a site and the deleted study is
                // this active site's parent study,
                // then this active site has to be removed as well
                // (auto-removed)
                else if (currentStudy.getParentStudyId() == study.getId()) {
                    currentStudy.setStatus(Status.AUTO_DELETED);
                    // we may need handle this later?
                    currentRole.setStatus(Status.DELETED);
                }
                // YW 06-18-2007 >>

                // remove all subjects
                for (int i = 0; i < subjects.size(); i++) {
                    StudySubjectBean subject = (StudySubjectBean) subjects.get(i);
                    if (!subject.getStatus().equals(Status.DELETED)) {
                        subject.setStatus(Status.AUTO_DELETED);
                        subject.setUpdater(ub);
                        subject.setUpdatedDate(new Date());
                        ssdao.update(subject);
                    }
                }

                // remove all study_group_class
                // changed by jxu on 08-31-06, to fix the problem of no study_id
                // in study_group table
                StudyGroupClassDAO sgcdao = new StudyGroupClassDAO(sm.getDataSource());
                SubjectGroupMapDAO sgmdao = new SubjectGroupMapDAO(sm.getDataSource());

                // YW 09-27-2007, enable status updating for StudyGroupClassBean
                ArrayList<StudyGroupClassBean> groups = sgcdao.findAllByStudy(study);
                for (int i = 0; i < groups.size(); i++) {
                    StudyGroupClassBean group = (StudyGroupClassBean) groups.get(i);
                    if (!group.getStatus().equals(Status.DELETED)) {
                        group.setStatus(Status.AUTO_DELETED);
                        group.setUpdater(ub);
                        group.setUpdatedDate(new Date());
                        sgcdao.update(group);
                        // all subject_group_map
                        ArrayList<SubjectGroupMapBean> subjectGroupMaps = sgmdao.findAllByStudyGroupClassId(group.getId());
                        for (int j = 0; j < subjectGroupMaps.size(); j++) {
                            SubjectGroupMapBean sgMap = (SubjectGroupMapBean) subjectGroupMaps.get(j);
                            if (!sgMap.getStatus().equals(Status.DELETED)) {
                                sgMap.setStatus(Status.AUTO_DELETED);
                                sgMap.setUpdater(ub);
                                sgMap.setUpdatedDate(new Date());
                                sgmdao.update(sgMap);
                            }
                        }
                    }
                }

                ArrayList<StudyGroupClassBean> groupClasses = sgcdao.findAllActiveByStudy(study);
                for (int i = 0; i < groupClasses.size(); i++) {
                    StudyGroupClassBean gc = (StudyGroupClassBean) groupClasses.get(i);
                    if (!gc.getStatus().equals(Status.DELETED)) {
                        gc.setStatus(Status.AUTO_DELETED);
                        gc.setUpdater(ub);
                        gc.setUpdatedDate(new Date());
                        sgcdao.update(gc);
                    }
                }

                // remove all event definitions and event
                EventDefinitionCRFDAO edcdao = new EventDefinitionCRFDAO(sm.getDataSource());
                StudyEventDAO sedao = new StudyEventDAO(sm.getDataSource());
                for (int i = 0; i < definitions.size(); i++) {
                    StudyEventDefinitionBean definition = (StudyEventDefinitionBean) definitions.get(i);
                    if (!definition.getStatus().equals(Status.DELETED)) {
                        definition.setStatus(Status.AUTO_DELETED);
                        definition.setUpdater(ub);
                        definition.setUpdatedDate(new Date());
                        sefdao.update(definition);
                        ArrayList<EventDefinitionCRFBean> edcs = edcdao.findAllByDefinition(definition.getId());
                        for (int j = 0; j < edcs.size(); j++) {
                            EventDefinitionCRFBean edc = (EventDefinitionCRFBean) edcs.get(j);
                            if (!edc.getStatus().equals(Status.DELETED)) {
                                edc.setStatus(Status.AUTO_DELETED);
                                edc.setUpdater(ub);
                                edc.setUpdatedDate(new Date());
                                edcdao.update(edc);
                            }
                        }

                        ArrayList<StudyEventBean> events = sedao.findAllByDefinition(definition.getId());
                        EventCRFDAO ecdao = new EventCRFDAO(sm.getDataSource());

                        for (int j = 0; j < events.size(); j++) {
                            StudyEventBean event = (StudyEventBean) events.get(j);
                            if (!event.getStatus().equals(Status.DELETED)) {
                                event.setStatus(Status.AUTO_DELETED);
                                event.setUpdater(ub);
                                event.setUpdatedDate(new Date());
                                sedao.update(event);

                                ArrayList<EventCRFBean> eventCRFs = ecdao.findAllByStudyEvent(event);

                                ItemDataDAO iddao = new ItemDataDAO(sm.getDataSource());
                                for (int k = 0; k < eventCRFs.size(); k++) {
                                    EventCRFBean eventCRF = (EventCRFBean) eventCRFs.get(k);
                                    if (!eventCRF.getStatus().equals(Status.DELETED)) {
                                        eventCRF.setOldStatus(eventCRF.getStatus());
                                        eventCRF.setStatus(Status.AUTO_DELETED);
                                        eventCRF.setUpdater(ub);
                                        eventCRF.setUpdatedDate(new Date());
                                        ecdao.update(eventCRF);

                                        ArrayList<ItemDataBean> itemDatas = iddao.findAllByEventCRFId(eventCRF.getId());
                                        for (int a = 0; a < itemDatas.size(); a++) {
                                            ItemDataBean item = (ItemDataBean) itemDatas.get(a);
                                            if (!item.getStatus().equals(Status.DELETED)) {
                                                item.setOldStatus(item.getStatus());
                                                item.setStatus(Status.AUTO_DELETED);
                                                item.setUpdater(ub);
                                                item.setUpdatedDate(new Date());
                                                iddao.update(item);
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }// for definitions

                DatasetDAO datadao = new DatasetDAO(sm.getDataSource());
                ArrayList<DatasetBean> dataset = datadao.findAllByStudyId(study.getId());
                for (int i = 0; i < dataset.size(); i++) {
                    DatasetBean data = (DatasetBean) dataset.get(i);
                    if (!data.getStatus().equals(Status.DELETED)) {
                        data.setStatus(Status.AUTO_DELETED);
                        data.setUpdater(ub);
                        data.setUpdatedDate(new Date());
                        datadao.update(data);
                    }
                }

                addPageMessage(resexception.getString("this_study_has_been_removed_succesfully"));
                forwardPage(Page.STUDY_LIST_SERVLET);

            }
        }

    }

    @Override
    protected String getAdminServlet() {
        return SecureController.ADMIN_SERVLET_CODE;
    }

}
