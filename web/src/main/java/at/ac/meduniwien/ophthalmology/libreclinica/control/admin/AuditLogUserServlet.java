/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 */
package at.ac.meduniwien.ophthalmology.libreclinica.control.admin;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.admin.AuditEventBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.control.core.SecureController;
import at.ac.meduniwien.ophthalmology.libreclinica.control.form.FormProcessor;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.admin.AuditEventDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.login.UserAccountDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.i18n.core.LocaleResolver;
import at.ac.meduniwien.ophthalmology.libreclinica.view.Page;
import at.ac.meduniwien.ophthalmology.libreclinica.web.InsufficientPermissionException;
import at.ac.meduniwien.ophthalmology.libreclinica.web.bean.AuditEventRow;
import at.ac.meduniwien.ophthalmology.libreclinica.web.bean.EntityBeanTable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;

/**
 * @author thickerson
 *
 *
 */
public class AuditLogUserServlet extends SecureController {

    /**
	 * 
	 */
	private static final long serialVersionUID = -6177899330139423029L;

	Locale locale;
    // < ResourceBundleresword,resexception;

    public static final String ARG_USERID = "userLogId";

    public static String getLink(int userId) {
        return "AuditLogUser?userLogId=" + userId;
    }

    /*
     * (non-Javadoc) Assume that we get the user id automatically. We will jump
     * from the edit user page if the user is an admin, they can get to see the
     * users' log
     *
     * @see at.ac.meduniwien.ophthalmology.libreclinica.control.core.SecureController#processRequest()
     */
    @Override
    protected void processRequest() throws Exception {
        FormProcessor fp = new FormProcessor(request);
        int userId = fp.getInt(ARG_USERID);
        if (userId == 0) {
            Integer userIntId = (Integer) session.getAttribute(ARG_USERID);
            userId = userIntId.intValue();
        } else {
            session.setAttribute(ARG_USERID, new Integer(userId));
        }
        AuditEventDAO aeDAO = new AuditEventDAO(sm.getDataSource());
        ArrayList<AuditEventBean> al = aeDAO.findAllByUserId(userId);

        EntityBeanTable table = fp.getEntityBeanTable();
        ArrayList<AuditEventRow> allRows = AuditEventRow.generateRowsFromBeans(al);

        // String[] columns = { "Date and Time", "Action", "Entity/Operation",
        // "Record ID", "Changes and Additions","Other Info" };
        // table.setColumns(new ArrayList(Arrays.asList(columns)));
        // table.hideColumnLink(4);
        // table.hideColumnLink(1);
        // table.hideColumnLink(5);
        // table.setQuery("AuditLogUser?userLogId="+userId, new HashMap<>());
        String[] columns =
            { resword.getString("date_and_time"), resword.getString("action_message"), resword.getString("entity_operation"), resword.getString("study_site"),
                resword.getString("study_subject_ID"), resword.getString("changes_and_additions"),
                // "Other Info",
                resword.getString("actions") };
        table.setColumns(new ArrayList<String>(Arrays.asList(columns)));
        table.setAscendingSort(false);
        table.hideColumnLink(1);
        table.hideColumnLink(5);
        table.hideColumnLink(6);
        // table.hideColumnLink(7);
        table.setQuery("AuditLogUser?userLogId=" + userId, new HashMap<>());
        table.setRows(allRows);

        table.computeDisplay();

        request.setAttribute("table", table);
        UserAccountDAO uadao = new UserAccountDAO(sm.getDataSource());
        UserAccountBean uabean = (UserAccountBean) uadao.findByPK(userId);
        request.setAttribute("auditUserBean", uabean);
        forwardPage(Page.AUDIT_LOG_USER);
    }

    /*
     * (non-Javadoc) Since access to this servlet is admin-only, restricts user
     * to see logs of specific users only @author thickerson
     *
     * @see at.ac.meduniwien.ophthalmology.libreclinica.control.core.SecureController#mayProceed()
     */
    @Override
    protected void mayProceed() throws InsufficientPermissionException {

        locale = LocaleResolver.getLocale(request);
        // < resword =
        // ResourceBundle.getBundle("at.ac.meduniwien.ophthalmology.libreclinica.i18n.words",locale);
        // <
        // resexception=ResourceBundle.getBundle("at.ac.meduniwien.ophthalmology.libreclinica.i18n.exceptions",locale);

        if (!ub.isSysAdmin()) {
            throw new InsufficientPermissionException(Page.MENU, resexception.getString("may_not_perform_administrative_functions"), "1");
        }
        return;
    }

    @Override
    protected String getAdminServlet() {
        return SecureController.ADMIN_SERVLET_CODE;
    }

}
