/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 */
package at.ac.meduniwien.ophthalmology.libreclinica.control.submit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;

import jakarta.servlet.http.HttpServletRequest;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.admin.CRFBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyEventDefinitionBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.rule.XmlSchemaValidationHelper;
import at.ac.meduniwien.ophthalmology.libreclinica.control.SpringServletAccess;
import at.ac.meduniwien.ophthalmology.libreclinica.control.core.SecureController;
import at.ac.meduniwien.ophthalmology.libreclinica.control.form.FormProcessor;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.admin.CRFDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.core.CoreResources;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyEventDefinitionDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.ItemDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.ItemFormMetadataDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.i18n.core.LocaleResolver;
import at.ac.meduniwien.ophthalmology.libreclinica.service.crfdata.HideCRFManager;
import at.ac.meduniwien.ophthalmology.libreclinica.service.rule.RuleSetServiceInterface;
import at.ac.meduniwien.ophthalmology.libreclinica.service.rule.RulesPostImportContainerService;
import at.ac.meduniwien.ophthalmology.libreclinica.view.Page;
import at.ac.meduniwien.ophthalmology.libreclinica.web.InsufficientPermissionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Verify the Rule import , show records that have Errors as well as records that will be saved.
 *
 * @author Krikor krumlian
 */
public class ViewRuleAssignmentNewServlet extends SecureController {

    private static final long serialVersionUID = 9116068126651934226L;
    protected final Logger log = LoggerFactory.getLogger(ViewRuleAssignmentNewServlet.class);

    Locale locale;
    XmlSchemaValidationHelper schemaValidator = new XmlSchemaValidationHelper();
    RuleSetServiceInterface ruleSetService;
    RulesPostImportContainerService rulesPostImportContainerService;
    ItemFormMetadataDAO itemFormMetadataDAO;

    private boolean showMoreLink;
    private boolean isDesigner;

    @Override
    public void processRequest() throws Exception {
        FormProcessor fp = new FormProcessor(request);
        if (fp.getString("designer").equals("")) {
            isDesigner = false;
        } else {
            isDesigner = Boolean.parseBoolean(fp.getString("designer"));
        }
        if (fp.getString("showMoreLink").equals("")) {
            showMoreLink = true;
        } else {
            showMoreLink = Boolean.parseBoolean(fp.getString("showMoreLink"));
        }
        createTable();

    }

    private void createStudyEventForInfoPanel() {

        StudyEventDefinitionDAO seddao = new StudyEventDefinitionDAO(sm.getDataSource());
        ItemDAO itemdao = new ItemDAO(sm.getDataSource());
        StudyBean studyWithEventDefinitions = currentStudy;
        if (currentStudy.getParentStudyId() > 0) {
            studyWithEventDefinitions = new StudyBean();
            studyWithEventDefinitions.setId(currentStudy.getParentStudyId());

        }
        CRFDAO crfdao = new CRFDAO(sm.getDataSource());
        ArrayList<StudyEventDefinitionBean> seds = seddao.findAllActiveByStudy(studyWithEventDefinitions);

        HashMap<StudyEventDefinitionBean, ArrayList<CRFBean>> events = new LinkedHashMap<>();
        for (int i = 0; i < seds.size(); i++) {
            StudyEventDefinitionBean sed = seds.get(i);
            ArrayList<CRFBean> crfs = crfdao.findAllActiveByDefinition(sed);

            if (currentStudy.getParentStudyId() > 0) {
                // sift through these CRFs and see which ones are hidden
                HideCRFManager hideCRFs = HideCRFManager.createHideCRFManager();
                crfs = hideCRFs.removeHiddenCRFBeans(studyWithEventDefinitions, sed, crfs, sm.getDataSource());
            }

            if (!crfs.isEmpty()) {
                events.put(sed, crfs);
            }
        }
        request.setAttribute("eventlist", events);
        request.setAttribute("crfCount", crfdao.getCountofActiveCRFs());
        request.setAttribute("itemCount", itemdao.getCountofActiveItems());
        request.setAttribute("ruleSetCount", getRuleSetService().getRuleSetDao().count(currentStudy));

    }

    private void createTable() {
        // Phase B.4 jmesa PR 7a (cohort 5a): factory.createTable().render()
        // is gone. The JSP shell now includes a vanilla-JS fragment that
        // fetches /ViewRuleAssignmentData asynchronously.
        getCoreResources();
        String designerLink = CoreResources.getField("designer.url")
                + "access?host=" + getHostPathFromSysUrl(
                        CoreResources.getField("sysURL.base"), request.getContextPath())
                + "&app=" + getContextPath(request);
        request.getSession().setAttribute("ruleDesignerUrl", designerLink);
        createStudyEventForInfoPanel();
        if (isDesigner) {
            forwardPage(Page.VIEW_RULE_SETS_DESIGNER);
        } else {
            forwardPage(Page.VIEW_RULE_SETS2);
        }
    }
    private String getHostPathFromSysUrl(String sysURL,String contextPath) {
        return sysURL.replaceAll(contextPath+"/", "");
       }
    public String getContextPath(HttpServletRequest request) {
        String contextPath = request.getContextPath().replaceAll("/", "");
        return contextPath;
    }
    public String getHostPath(HttpServletRequest request) {
        String requestURLMinusServletPath = getRequestURLMinusServletPath(request);
        String hostPath = "";
        if (null != requestURLMinusServletPath) {
             hostPath = requestURLMinusServletPath.substring(0, requestURLMinusServletPath.lastIndexOf("/"));
           // hostPath = tmpPath.substring(0, tmpPath.lastIndexOf("/"));
        }
        return hostPath;
    }
    public String getRequestURLMinusServletPath(HttpServletRequest request) {
        String requestURLMinusServletPath = request.getRequestURL().toString().replaceAll(request.getServletPath(), "");
        return requestURLMinusServletPath;
    }

    @Override
    protected String getAdminServlet() {
        if (ub.isSysAdmin()) {
            return SecureController.ADMIN_SERVLET_CODE;
        } else {
            return "";
        }
    }

    @Override
    public void mayProceed() throws InsufficientPermissionException {
        locale = LocaleResolver.getLocale(request);
        if (ub.isSysAdmin()) {
            return;
        }
        Role r = currentRole.getRole();
       if (r.equals(Role.STUDYDIRECTOR) || r.equals(Role.COORDINATOR)) {
            return;
        }
        addPageMessage(respage.getString("no_have_correct_privilege_current_study") + respage.getString("change_study_contact_sysadmin"));
        throw new InsufficientPermissionException(Page.MENU_SERVLET, resexception.getString("may_not_submit_data"), "1");
    }

    private RuleSetServiceInterface getRuleSetService() {
        ruleSetService =
            this.ruleSetService != null ? ruleSetService : (RuleSetServiceInterface) SpringServletAccess.getApplicationContext(context).getBean(
                    "ruleSetService");
        // TODO: Add getRequestURLMinusServletPath(),getContextPath()
        return ruleSetService;
    }

    public ItemFormMetadataDAO getItemFormMetadataDAO() {
        itemFormMetadataDAO = this.itemFormMetadataDAO == null ? new ItemFormMetadataDAO(sm.getDataSource()) : itemFormMetadataDAO;
        return itemFormMetadataDAO;
    }

    private CoreResources getCoreResources() {
        return (CoreResources) SpringServletAccess.getApplicationContext(context).getBean("coreResources");
    }

}