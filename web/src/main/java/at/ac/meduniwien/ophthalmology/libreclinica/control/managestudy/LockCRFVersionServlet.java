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
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.admin.CRFBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Status;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.EventDefinitionCRFBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.CRFVersionBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.EventCRFBean;
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
   
   /** GET shows the confirmation; archiving the CRF version takes a POST. */
   @Override
   protected boolean acceptsGet(HttpServletRequest request) {
       String action = request.getParameter("action");
       return action == null || action.trim().isEmpty();
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
                    updateEventDef(edcBean, edCRFDao, versionList, crfVersionId);
               }
           }

           addPageMessage(respage.getString("crf_version_archived_successfully"));
           forwardPage(Page.CRF_LIST_SERVLET);
       }
   }

    // @pgawade 18-May-2011 #5414 - Ovrloaded the method updateEventDef for an
    // additional parameter of crf version being locked.
    // These are changes for setting the correct default crf version Id to event
    // when existing default version is locked
    // Moved here from RemoveCRFVersionServlet when that servlet was deleted
    // (DR-018, waves 0 and 1); this servlet is its last caller.
    static void updateEventDef(EventDefinitionCRFBean edcBean, EventDefinitionCRFDAO edcDao, ArrayList<CRFVersionBean> versionList, int crfVIdToLock) {
        if (versionList == null || versionList.isEmpty()) {
            return;
        }
        List<Integer> idList = new ArrayList<Integer>();
        CRFVersionBean temp = versionList.get(0);
        // Check the first version in list if it is getting locked
        // here. If not, make that as default version. Otherwise get the next
        // element in list and make that as the default version.

        String selectedVersionIds = edcBean.getSelectedVersionIds();
		if (selectedVersionIds == null || selectedVersionIds.trim().isEmpty()){
            if (temp.getId() == crfVIdToLock && versionList.size() > 1) {
                CRFVersionBean temp2 = versionList.get(1);
                edcBean.setDefaultVersionId(temp2.getId());
            } else {
                edcBean.setDefaultVersionId(temp.getId());
            }
            edcDao.update(edcBean);
        } else {
            idList.addAll(parseIds(selectedVersionIds));
            for(int i = 0; i < versionList.size(); i++){
                CRFVersionBean versionBean = (CRFVersionBean)versionList.get(i);
                if (idList.contains(versionBean.getId())){
                    edcBean.setDefaultVersionId(versionBean.getId());
                    edcDao.update(edcBean);
                    break;
                }
            }
        }
    }
    /**
     * Parses a comma separated list of ids. Blank and non-numeric tokens are skipped.
     */
    static List<Integer> parseIds(String csv) {
        List<Integer> ids = new ArrayList<Integer>();
        if (csv == null) {
            return ids;
        }
        for (String token : csv.split(",")) {
            String t = token.trim();
            if (t.isEmpty()) {
                continue;
            }
            try {
                ids.add(Integer.valueOf(t));
            } catch (NumberFormatException e) {
                // ignore a malformed id
            }
        }
        return ids;
    }
}
