/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 */
package at.ac.meduniwien.ophthalmology.libreclinica.control.submit;

import java.util.HashMap;
import java.util.Locale;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.rule.XmlSchemaValidationHelper;
import at.ac.meduniwien.ophthalmology.libreclinica.control.SpringServletAccess;
import at.ac.meduniwien.ophthalmology.libreclinica.control.core.SecureController;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.RuleBulkExecuteContainer;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.RuleBulkExecuteContainerTwo;
import at.ac.meduniwien.ophthalmology.libreclinica.i18n.core.LocaleResolver;
import at.ac.meduniwien.ophthalmology.libreclinica.logic.rulerunner.ExecutionMode;
import at.ac.meduniwien.ophthalmology.libreclinica.service.rule.RuleSetServiceInterface;
import at.ac.meduniwien.ophthalmology.libreclinica.service.rule.RulesPostImportContainerService;
import at.ac.meduniwien.ophthalmology.libreclinica.view.Page;
import at.ac.meduniwien.ophthalmology.libreclinica.web.InsufficientPermissionException;
import at.ac.meduniwien.ophthalmology.libreclinica.web.filter.StudyTreeScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * Run Rules Using this Servlet
 *
 * @author Krikor krumlian
 */
@SuppressWarnings("all")
public class RunRuleServlet extends SecureController {
    private static final long serialVersionUID = 9116068126651934226L;
    protected final Logger log = LoggerFactory.getLogger(RunRuleServlet.class);

    Locale locale;
    XmlSchemaValidationHelper schemaValidator = new XmlSchemaValidationHelper();
    RuleSetServiceInterface ruleSetService;
    RulesPostImportContainerService rulesPostImportContainerService;

    /** GET runs the rules as a dry run; applying their actions takes a POST. */
    @Override
    protected boolean acceptsGet(HttpServletRequest request) {
        String action = request.getParameter("action");
        return action == null || "dryRun".equalsIgnoreCase(action);
    }

    @Override
    public void processRequest() throws Exception {
        String action = request.getParameter("action");
        String crfId = request.getParameter("crfId");
        String ruleSetRuleId = request.getParameter("ruleSetRuleId");
        String versionId = request.getParameter("versionId");

        if (action == null || action.trim().isEmpty()) {
            forwardPage(Page.MENU_SERVLET);
        }

        //Boolean dryRun = action == null || "dryRun".equalsIgnoreCase(action) ? true : false;
        ExecutionMode executionMode = action == null || "dryRun".equalsIgnoreCase(action) ? ExecutionMode.DRY_RUN : ExecutionMode.SAVE;
        String submitLinkParams = "";

        HashMap<RuleBulkExecuteContainer, HashMap<RuleBulkExecuteContainerTwo, Set<String>>> result = null;
        if (ruleSetRuleId != null && versionId != null) {
            submitLinkParams = "ruleSetRuleId=" + ruleSetRuleId + "&versionId=" + versionId + "&action=no";
            result = getRuleSetService().runRulesInBulk(ruleSetRuleId, versionId, executionMode, currentStudy, ub);
        } else {
            submitLinkParams = "crfId=" + crfId + "&action=no";
            result = getRuleSetService().runRulesInBulk(crfId, executionMode, currentStudy, ub);
        }

        request.setAttribute("result", result);
        request.setAttribute("submitLinkParams", submitLinkParams);
        if (executionMode == ExecutionMode.SAVE) {
            forwardPage(Page.LIST_RULE_SETS_SERVLET);
        } else {
            forwardPage(Page.VIEW_EXECUTED_RULES_FROM_CRF);
        }
    }

    private RuleSetServiceInterface getRuleSetService() {
        ruleSetService =
            this.ruleSetService != null ? ruleSetService : (RuleSetServiceInterface) SpringServletAccess.getApplicationContext(context).getBean(
                    "ruleSetService");
        ruleSetService.setMailSender((JavaMailSenderImpl) SpringServletAccess.getApplicationContext(context).getBean("mailSender"));
        ruleSetService.setContextPath(getContextPath());
        ruleSetService.setRequestURLMinusServletPath(getRequestURLMinusServletPath());
        return ruleSetService;
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
        Role r = currentRole.getRole();
        if (!ub.isSysAdmin() && !r.equals(Role.STUDYDIRECTOR) && !r.equals(Role.COORDINATOR)) {
            addPageMessage(respage.getString("no_have_correct_privilege_current_study") + respage.getString("change_study_contact_sysadmin"));
            throw new InsufficientPermissionException(Page.MENU_SERVLET, resexception.getString("may_not_submit_data"), "1");
        }
        // The rule set rule is named by id: it must be the current study's.
        String ruleSetRuleId = request.getParameter("ruleSetRuleId");
        if (ruleSetRuleId != null) {
            int id = 0;
            try {
                id = Integer.parseInt(ruleSetRuleId.trim());
            } catch (NumberFormatException e) {
                // not an id: refused below
            }
            if (!new StudyTreeScope(sm.getDataSource()).containsRuleSetRule(currentStudy, id)) {
                refuseRecordOutsideCurrentStudy();
            }
        }
    }
}