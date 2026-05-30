/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2020 - 2024 LibreClinica
 */
package at.ac.meduniwien.ophthalmology.libreclinica.control.admin;

import static at.ac.meduniwien.ophthalmology.libreclinica.view.Page.LIST_USER_ACCOUNTS_SERVLET;

import java.util.Optional;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.control.core.SecureController;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.login.UserAccountDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.service.otp.CertificateBean;
import at.ac.meduniwien.ophthalmology.libreclinica.service.otp.TwoFactorService;
import at.ac.meduniwien.ophthalmology.libreclinica.web.InsufficientPermissionException;

/**
 * {@link SecureController} only used for 2-FA printout requests.
 * 
 * @author thillger
 */
@SuppressWarnings("serial")
public class PrintoutCertificateServlet extends SecureController {

    @Override
    public void processRequest() throws Exception {
        // TODO: integrate fail fast? How?
        Optional<String> optUserId = Optional.ofNullable(request.getParameter("userId"));

		response.setHeader("Content-Disposition", "attachment; filename=\"certificate.pdf\"");
		response.addHeader("Content-Type", "application/pdf");
		response.setContentType("application/octet-stream");

		UserAccountDAO dao = new UserAccountDAO(sm.getDataSource());
        UserAccountBean user = dao.getUserById(Integer.valueOf(optUserId.get()));

		CertificateBean bean = new CertificateBean();
        bean.setUsername(user.getLastName().concat(", ").concat(user.getFirstName()));
        bean.setSecret(user.getAuthsecret());
        bean.setEmail(user.getEmail());
        bean.setLogin(user.getName());

        TwoFactorService factorService = getBean(TwoFactorService.class);
        factorService.printoutCertificate(bean, response.getOutputStream());
    }

    @Override
    public void mayProceed() throws InsufficientPermissionException {
        if (ub.isSysAdmin()) {
            return;
        }

        addPageMessage(respage.getString("no_have_correct_privilege_current_study") + respage.getString("change_study_contact_sysadmin"));
        throw new InsufficientPermissionException(LIST_USER_ACCOUNTS_SERVLET, resexception.getString("not_admin"), "1");
    }

    @Override
    protected String getAdminServlet() {
		return ADMIN_SERVLET_CODE;
    }
}
