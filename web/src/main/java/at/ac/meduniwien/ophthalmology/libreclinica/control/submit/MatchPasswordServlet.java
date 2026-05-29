/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 */
package at.ac.meduniwien.ophthalmology.libreclinica.control.submit;

import at.ac.meduniwien.ophthalmology.libreclinica.control.SpringServletAccess;
import at.ac.meduniwien.ophthalmology.libreclinica.control.core.SecureController;
import at.ac.meduniwien.ophthalmology.libreclinica.core.SecurityManager;
import at.ac.meduniwien.ophthalmology.libreclinica.web.InsufficientPermissionException;

/**
 * Checks user's password with the one int the session
 *
 * @author shamim
 *
 */
public class MatchPasswordServlet extends SecureController {

    private static final long serialVersionUID = -358927626509831091L;

    @Override
    protected void processRequest() throws Exception {
        String password = request.getParameter("password");
        logger.info("password [" + password + "]");
        if (password != null && !password.equals("")) {
            SecurityManager securityManager =
                    ((SecurityManager) SpringServletAccess.getApplicationContext(context).getBean("securityManager"));
            response.getWriter().print(Boolean.toString(securityManager.verifyPassword(password, getUserDetails())));
            return;
        }
    }

    @Override
    protected void mayProceed() throws InsufficientPermissionException {
        return;
    }
}
