/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 */
package org.akaza.openclinica.control.admin;

import java.util.Locale;

import org.akaza.openclinica.control.SpringServletAccess;
import org.akaza.openclinica.control.core.SecureController;
import org.akaza.openclinica.dao.hibernate.DatabaseChangeLogDao;
import org.akaza.openclinica.i18n.core.LocaleResolver;
import org.akaza.openclinica.view.Page;
import org.akaza.openclinica.web.InsufficientPermissionException;

/**
 * Servlet for creating a user account.
 *
 * @author Krikor Krumlian
 */
public class AuditDatabaseServlet extends SecureController {

    private static final long serialVersionUID = 1L;

    // < ResourceBundle restext;
    Locale locale;
    private DatabaseChangeLogDao databaseChangeLogDao;

    /*
     * (non-Javadoc)
     * @see org.akaza.openclinica.control.core.SecureController#mayProceed()
     */
    @Override
    protected void mayProceed() throws InsufficientPermissionException {

        locale = LocaleResolver.getLocale(request);
        // < restext =
        // ResourceBundle.getBundle("org.akaza.openclinica.i18n.notes",locale);

        if (!ub.isSysAdmin()) {
            throw new InsufficientPermissionException(Page.MENU, resexception.getString("you_may_not_perform_administrative_functions"), "1");
        }

        return;
    }

    @Override
    protected void processRequest() throws Exception {
        // Phase B.4 jmesa PR 9 (cohort 7): jmesa renderAuditDatabaseTable
        // gone. JSP renders the rows directly via c:forEach over the
        // databaseChangeLogs request attribute.
        request.setAttribute("databaseChangeLogs", getDatabaseChangeLogDao().findAll());
        forwardPage(Page.AUDIT_DATABASE);
    }

    @Override
    protected String getAdminServlet() {
        return SecureController.ADMIN_SERVLET_CODE;
    }

    public DatabaseChangeLogDao getDatabaseChangeLogDao() {
        databaseChangeLogDao =
            this.databaseChangeLogDao != null ? databaseChangeLogDao : (DatabaseChangeLogDao) SpringServletAccess.getApplicationContext(context).getBean(
                    "databaseChangeLogDao");
        return databaseChangeLogDao;
    }

    public void setDatabaseChangeLogDao(DatabaseChangeLogDao databaseChangeLogDao) {
        this.databaseChangeLogDao = databaseChangeLogDao;
    }

}
