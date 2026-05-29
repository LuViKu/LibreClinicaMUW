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

import at.ac.meduniwien.ophthalmology.libreclinica.bean.admin.CRFBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Status;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.EventDefinitionCRFBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.CRFVersionBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.EventCRFBean;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.RemoveCRFVersionServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.core.SecureController;
import at.ac.meduniwien.ophthalmology.libreclinica.control.form.FormProcessor;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.admin.CRFDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.EventDefinitionCRFDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.CRFVersionDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.EventCRFDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.view.Page;
import at.ac.meduniwien.ophthalmology.libreclinica.web.InsufficientPermissionException;

public class LockCRFVersionServlet extends SecureController {
    /**
	 * 
	 */
	private static final long serialVersionUID = -2976788275878626603L;

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
       throw new InsufficientPermissionException(Page.MENU_SERVLET, resexception.getString("not_study_director"), "1");

   }
   
   @Override
   public void processRequest() throws Exception {
       FormProcessor fp = new FormProcessor(request);
       
       int crfVersionId = fp.getInt("id",true);
       String action = fp.getString("action");
       
       // checks which module the requests are from
       String module = fp.getString(MODULE);
       request.setAttribute(MODULE, module);
       
       if(crfVersionId ==0) {
           addPageMessage(respage.getString("choose_valid_crf_version"));
           forwardPage(Page.CRF_LIST_SERVLET);
           return;
       }
       
       CRFVersionDAO cvdao = new CRFVersionDAO(sm.getDataSource());
       CRFDAO cdao = new CRFDAO (sm.getDataSource());
       
       CRFVersionBean version = (CRFVersionBean)cvdao.findByPK(crfVersionId);
       //System.out.println("crf version found:" + version.getName());
       CRFBean crf = (CRFBean)cdao.findByPK(version.getCrfId());

       if (!ub.isSysAdmin() && (version.getOwnerId() != ub.getId())) {
           addPageMessage(respage.getString("no_have_correct_privilege_current_study")
                   + " " + respage.getString("change_active_study_or_contact"));
           forwardPage(Page.MENU_SERVLET);
           return;
       }
      
       EventCRFDAO ecdao = new EventCRFDAO(sm.getDataSource());
       ArrayList<EventCRFBean> eventCRFs = ecdao.findAllStudySubjectByCRFVersion(crfVersionId);
       
       if (action == null || action.trim().isEmpty()) {
           request.setAttribute("crfVersionToLock", version);
           request.setAttribute("crf", crf);
           request.setAttribute("eventSubjectsUsingVersion", eventCRFs);
           forwardPage(Page.CONFIRM_LOCKING_CRF_VERSION);
           
       } else if ("confirm".equalsIgnoreCase(action)) {
           version.setStatus(Status.LOCKED);
           version.setUpdater(ub);
           cvdao.update(version);

           ArrayList<CRFVersionBean> versionList = cvdao.findAllByCRF(version.getCrfId());
           if(versionList.size() > 0){
               EventDefinitionCRFDAO edCRFDao = new EventDefinitionCRFDAO(sm.getDataSource());
               ArrayList<EventDefinitionCRFBean> edcList = edCRFDao.findAllByCRF(version.getCrfId());
               for(int i = 0; i < edcList.size(); i++){
                   EventDefinitionCRFBean edcBean = (EventDefinitionCRFBean)edcList.get(i);
                    // @pgawade 18-May-2011 #5414 - Changes for setting the correct
                    // default crf version Id to event
                    // when existing default version is locked
                    // RemoveCRFVersionServlet.updateEventDef(edcBean, edCRFDao,
                    // versionList);
                    RemoveCRFVersionServlet.updateEventDef(edcBean, edCRFDao, versionList, crfVersionId);
               }
           }

           addPageMessage(respage.getString("crf_version_archived_successfully"));
           forwardPage(Page.CRF_LIST_SERVLET);
       }
   }

}
