/* LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 */
package at.ac.meduniwien.ophthalmology.libreclinica.control.submit;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.control.SpringServletAccess;
import at.ac.meduniwien.ophthalmology.libreclinica.control.core.SecureController;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.core.CoreResources;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.RuleSetRuleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.RulesPostImportContainer;
import at.ac.meduniwien.ophthalmology.libreclinica.exception.OpenClinicaSystemException;
import at.ac.meduniwien.ophthalmology.libreclinica.service.rule.RuleSetServiceInterface;
import at.ac.meduniwien.ophthalmology.libreclinica.service.xml.OdmJaxbContext;
import at.ac.meduniwien.ophthalmology.libreclinica.view.Page;
import at.ac.meduniwien.ophthalmology.libreclinica.web.InsufficientPermissionException;
import at.ac.meduniwien.ophthalmology.libreclinica.web.SQLInitServlet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import jakarta.servlet.ServletOutputStream;

public class DownloadRuleSetXmlServlet extends SecureController {

    protected final Logger log = LoggerFactory.getLogger(DownloadRuleSetXmlServlet.class);
    private static final long serialVersionUID = 5381321212952389008L;
    RuleSetServiceInterface ruleSetService;

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

    /**
     * Serialise a {@link RulesPostImportContainer} to {@code out} as XML.
     *
     * <p>Phase B.3 PR 1/3 (DR-006): swapped from Castor 1.4.1 to
     * {@code jakarta.xml.bind} 2.3.x JAXB via {@link OdmJaxbContext}. The
     * legacy method name {@code handleLoadCastor} is preserved deliberately
     * so call sites and stack traces stay grep-compatible until B.3 PR 3/3
     * does the final rename + Castor dep drop.
     */
    private void handleLoadCastor(OutputStream out, RulesPostImportContainer rpic) {
        try {
            getOdmJaxbContext().marshalRulesExport(rpic, out);
        } catch (Exception e) {
            throw new OpenClinicaSystemException(e.getMessage(), e.getCause());
        }
    }

    private OdmJaxbContext getOdmJaxbContext() {
        return (OdmJaxbContext) SpringServletAccess.getApplicationContext(context)
                .getBean("odmJaxbContext");
    }

    private RulesPostImportContainer prepareRulesPostImportRuleSetRuleContainer(String ruleSetRuleIds) {
        List<RuleSetRuleBean> ruleSetRules = new ArrayList<RuleSetRuleBean>();
        RulesPostImportContainer rpic = new RulesPostImportContainer();

        if (ruleSetRuleIds !="") {
        String[] splitExpression = ruleSetRuleIds.split(",");

        for (String string : splitExpression) {
            RuleSetRuleBean rsr = getRuleSetService().getRuleSetRuleDao().findById(Integer.valueOf(string));
            ruleSetRules.add(rsr);
        }
        rpic.populate(ruleSetRules);
        
        } 
        return rpic;
    }

    @Override
    public void processRequest() throws Exception {

        // String ruleSetId = request.getParameter("ruleSetId");
        String ruleSetRuleIds = request.getParameter("ruleSetRuleIds");

        String dir = SQLInitServlet.getField("filePath") + "rules" + File.separator;
        Long time = System.currentTimeMillis();
        File f = new File(dir + "rules" + currentStudy.getOid() + "-" + time + ".xml");
        try (OutputStream writer = new FileOutputStream(f)) {
            handleLoadCastor(writer, prepareRulesPostImportRuleSetRuleContainer(ruleSetRuleIds));
        }

        response.setHeader("Content-disposition", "attachment; filename=\"" + "rules" + currentStudy.getOid() + "-" + time + ".xml" + "\";");
        response.setContentType("text/xml");
        response.setHeader("Pragma", "public");

        ServletOutputStream op = response.getOutputStream();

        DataInputStream in = null;
        try {
            response.setContentType("text/xml");
            response.setHeader("Pragma", "public");
            response.setContentLength((int) f.length());

            byte[] bbuf = new byte[(int) f.length()];
            in = new DataInputStream(new FileInputStream(f));
            int length;
            while ((in != null) && ((length = in.read(bbuf)) != -1)) {
                op.write(bbuf, 0, length);
            }

            in.close();
            op.flush();
            op.close();
        } catch (Exception ee) {
            logger.error("Unable to process the Input processRequest: ", ee);
        } finally {
            if (in != null) {
                in.close();
            }
            if (op != null) {
                op.close();
            }
        }

    }

    private RuleSetServiceInterface getRuleSetService() {
        ruleSetService =
            this.ruleSetService != null ? ruleSetService : (RuleSetServiceInterface) SpringServletAccess.getApplicationContext(context).getBean(
                    "ruleSetService");
        // TODO: Add getRequestURLMinusServletPath(),getContextPath()
        return ruleSetService;
    }

    private CoreResources getCoreResources() {
        return (CoreResources) SpringServletAccess.getApplicationContext(context).getBean("coreResources");
    }
}
