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

import org.akaza.openclinica.control.core.SecureController;
import org.akaza.openclinica.i18n.core.LocaleResolver;
import org.akaza.openclinica.view.Page;
import org.akaza.openclinica.web.InsufficientPermissionException;

/**
 * Servlet for the audit-user-activity page. Phase B.4 jmesa PR 4a:
 * the deleted {@code AuditUserLoginTableFactory} used to render the
 * full table as an HTML string here; the JSP now ships an empty
 * {@code <table>} skeleton and the rows arrive via the
 * {@link AuditUserActivityDataServlet} AJAX endpoint.
 *
 * <p>Responsibility split:
 * <ul>
 *   <li>{@code AuditUserActivityServlet} (this class) — renders the
 *       JSP shell, enforces sysadmin-only access.</li>
 *   <li>{@code AuditUserActivityDataServlet} — JSON endpoint at
 *       {@code /AuditUserActivityData}, returns one DataTables page
 *       per AJAX request.</li>
 * </ul>
 *
 * @author Krikor Krumlian
 */
public class AuditUserActivityServlet extends SecureController {

    private static final long serialVersionUID = 1L;
    Locale locale;

    @Override
    protected void mayProceed() throws InsufficientPermissionException {

        locale = LocaleResolver.getLocale(request);

        if (!ub.isSysAdmin()) {
            addPageMessage(respage.getString("no_have_correct_privilege_current_study") + respage.getString("change_study_contact_sysadmin"));
            throw new InsufficientPermissionException(Page.MENU_SERVLET, resexception.getString("you_may_not_perform_administrative_functions"), "1");
        }

        return;
    }

    @Override
    protected void processRequest() throws Exception {
        forwardPage(Page.AUDIT_USER_ACTIVITY);
    }

    @Override
    protected String getAdminServlet() {
        return SecureController.ADMIN_SERVLET_CODE;
    }
}
