/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 */
package at.ac.meduniwien.ophthalmology.libreclinica.control.submit;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.text.MessageFormat;
import java.util.Locale;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.rule.FileProperties;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.rule.FileUploadHelper;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.rule.XmlSchemaValidationHelper;
import at.ac.meduniwien.ophthalmology.libreclinica.control.SpringServletAccess;
import at.ac.meduniwien.ophthalmology.libreclinica.control.core.SecureController;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.core.CoreResources;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.RulesPostImportContainer;
import at.ac.meduniwien.ophthalmology.libreclinica.exception.OpenClinicaSystemException;
import at.ac.meduniwien.ophthalmology.libreclinica.i18n.core.LocaleResolver;
import at.ac.meduniwien.ophthalmology.libreclinica.service.rule.RulesPostImportContainerService;
import at.ac.meduniwien.ophthalmology.libreclinica.service.xml.OdmJaxbContext;
import at.ac.meduniwien.ophthalmology.libreclinica.view.Page;
import at.ac.meduniwien.ophthalmology.libreclinica.web.InsufficientPermissionException;
import at.ac.meduniwien.ophthalmology.libreclinica.web.SQLInitServlet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Verify the Rule import , show records that have Errors as well as records that will be saved.
 *
 * @author Krikor krumlian
 */
public class ImportRuleServlet extends SecureController {
    private static final long serialVersionUID = 9116068126651934226L;
    protected final Logger log = LoggerFactory.getLogger(ImportRuleServlet.class);

    Locale locale;
    FileUploadHelper uploadHelper = new FileUploadHelper(new FileProperties("xml"));
    XmlSchemaValidationHelper schemaValidator = new XmlSchemaValidationHelper();
    RulesPostImportContainerService rulesPostImportContainerService;

    @Override
    public void processRequest() throws Exception {
        String action = request.getParameter("action");
        request.setAttribute("contextPath", getContextPath());
        request.setAttribute("hostPath", getHostPath());
        copyFiles();
        //@pgawade 13-April-2011 -  #8877
        // request.setAttribute("designerURL",
        // getCoreResources().getField("designer.url"));

        if (action == null || action.trim().isEmpty()) {
            forwardPage(Page.IMPORT_RULES);

        }
        if ("downloadrulesxsd".equalsIgnoreCase(action)) {
            // File xsdFile = new File(SpringServletAccess.getPropertiesDir(context) + "rules.xsd");
            File xsdFile = getCoreResources().getFile("rules.xsd", "rules"+File.separator);
            dowloadFile(xsdFile, "text/xml");
        }
        if ("downloadtemplate".equalsIgnoreCase(action)) {
            // File file = new File(SpringServletAccess.getPropertiesDir(context) + "rules_template.xml");
            File file = getCoreResources().getFile("rules_template.xml",  "rules"+File.separator);
            dowloadFile(file, "text/xml");
        }
        if ("downloadtemplateWithNotes".equalsIgnoreCase(action)) {
            // File file = new File(SpringServletAccess.getPropertiesDir(context) + "rules_template_with_notes.xml");
            File file = getCoreResources().getFile("rules_template_with_notes.xml",  "rules"+File.separator);
            dowloadFile(file, "text/xml");
        }
        if ("confirm".equalsIgnoreCase(action)) {

            try {
                File f = uploadHelper.returnFiles(request, context, getDirToSaveUploadedFileIn()).get(0);
                // File xsdFile = new File(getServletContext().getInitParameter("propertiesDir") + "rules.xsd");
                // File xsdFile = new File(SpringServletAccess.getPropertiesDir(context) + "rules.xsd");
                InputStream xsdFile = getCoreResources().getInputStream("rules.xsd");

                
                schemaValidator.validateAgainstSchema(f, xsdFile);
                
                RulesPostImportContainer importedRules = handleLoadCastor(f);
                logger.info(ub.getFirstName());
                importedRules = getRulesPostImportContainerService().validateRuleDefs(importedRules);
                importedRules = getRulesPostImportContainerService().validateRuleSetDefs(importedRules);
                session.setAttribute("importedData", importedRules);
                provideMessage(importedRules);
                forwardPage(Page.VERIFY_RULES_IMPORT_SERVLET);
            } catch (OpenClinicaSystemException re) {
                // re.printStackTrace();
                MessageFormat mf = new MessageFormat("");
                mf.applyPattern(re.getErrorCode() == null ? respage.getString("OCRERR_0016") : respage.getString(re.getErrorCode()));
                Object[] arguments = { re.getMessage() };
                if (re.getErrorCode() != null) {
                    arguments = re.getErrorParams();
                }
                addPageMessage(mf.format(arguments));
                forwardPage(Page.IMPORT_RULES);
            }
        }
    }

    private void copyFiles() {


    }

    private void provideMessage(RulesPostImportContainer rulesContainer) {
        int validRuleSetDefs = rulesContainer.getValidRuleSetDefs().size();
        int duplicateRuleSetDefs = rulesContainer.getDuplicateRuleSetDefs().size();
        int invalidRuleSetDefs = rulesContainer.getInValidRuleSetDefs().size();

        int duplicateRuleDefs = rulesContainer.getDuplicateRuleDefs().size();
        int invalidRuleDefs = rulesContainer.getInValidRuleDefs().size();

        if (validRuleSetDefs > 0 && duplicateRuleSetDefs == 0 && invalidRuleSetDefs == 0 && duplicateRuleDefs == 0 && invalidRuleDefs == 0) {
            addPageMessage(respage.getString("rules_Import_message1"));
        }
        if (duplicateRuleSetDefs > 0 && invalidRuleSetDefs == 0 && duplicateRuleDefs >= 0 && invalidRuleDefs == 0) {
            addPageMessage(respage.getString("rules_Import_message2"));
        }
        if (invalidRuleSetDefs > 0 && invalidRuleDefs >= 0) {
            addPageMessage(respage.getString("rules_Import_message3"));
        }
    }

    private String getDirToSaveUploadedFileIn() throws OpenClinicaSystemException {
        String dir = SQLInitServlet.getField("filePath");
        if (!new File(dir).exists()) {
            throw new OpenClinicaSystemException(respage.getString("filepath_you_defined_not_seem_valid"));
        }
        String theDir = dir + "rules" + File.separator + "original" + File.separator;
        return theDir;
    }

    /**
     * Parse the uploaded rules XML into a {@link RulesPostImportContainer}.
     *
     * <p>Phase B.3 PR 1/3 (DR-006): swapped from Castor 1.4.1 to
     * {@code jakarta.xml.bind} 2.3.x JAXB via {@link OdmJaxbContext}. The
     * legacy method name {@code handleLoadCastor} is preserved deliberately
     * so call sites and stack traces stay grep-compatible until B.3 PR 3/3
     * does the final rename + Castor dep drop.
     */
    private RulesPostImportContainer handleLoadCastor(File xmlFile) {
        try (InputStream in = new FileInputStream(xmlFile)) {
            RulesPostImportContainer ruleImport = getOdmJaxbContext().unmarshalRulesImport(in);
            ruleImport.initializeRuleDef();
            logRuleImport(ruleImport);
            return ruleImport;
        } catch (FileNotFoundException ex) {
            throw new OpenClinicaSystemException(ex.getMessage(), ex.getCause());
        } catch (IOException ex) {
            throw new OpenClinicaSystemException(ex.getMessage(), ex.getCause());
        }
    }

    private OdmJaxbContext getOdmJaxbContext() {
        return (OdmJaxbContext) SpringServletAccess.getApplicationContext(context)
                .getBean("odmJaxbContext");
    }

    private void logRuleImport(RulesPostImportContainer ruleImport) {
        logger.info("Total Number of RuleDefs Being imported : {} ", ruleImport.getRuleDefs().size());
        logger.info("Total Number of RuleAssignments Being imported : {} ", ruleImport.getRuleSets().size());
    }

    private RulesPostImportContainerService getRulesPostImportContainerService() {
        rulesPostImportContainerService =
            this.rulesPostImportContainerService != null ? rulesPostImportContainerService : (RulesPostImportContainerService) SpringServletAccess
                    .getApplicationContext(context).getBean("rulesPostImportContainerService");
        rulesPostImportContainerService.setCurrentStudy(currentStudy);
        rulesPostImportContainerService.setRespage(respage);
        rulesPostImportContainerService.setUserAccount(ub);
        return rulesPostImportContainerService;
    }

    private CoreResources getCoreResources() {
        return (CoreResources) SpringServletAccess.getApplicationContext(context).getBean("coreResources");
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
}
