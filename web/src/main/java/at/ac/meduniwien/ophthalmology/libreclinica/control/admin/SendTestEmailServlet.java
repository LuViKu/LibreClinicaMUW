/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2020 - 2024 LibreClinica
 */
package at.ac.meduniwien.ophthalmology.libreclinica.control.admin;

import jakarta.ws.rs.core.MediaType;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import at.ac.meduniwien.ophthalmology.libreclinica.control.core.SecureController;
import at.ac.meduniwien.ophthalmology.libreclinica.core.EmailEngine;
import at.ac.meduniwien.ophthalmology.libreclinica.view.Page;
import at.ac.meduniwien.ophthalmology.libreclinica.web.InsufficientPermissionException;
import org.springframework.mail.MailException;

public class SendTestEmailServlet extends SecureController {
    @Override
    protected void mayProceed() throws InsufficientPermissionException {
        if (!ub.isSysAdmin()) {
            addPageMessage(respage.getString("you_may_not_perform_administrative_functions"));

            throw new InsufficientPermissionException(
                Page.ADMIN_SYSTEM_SERVLET,
                respage.getString("you_may_not_perform_administrative_functions"),
                 "1"
            );
        }

        return;
    }

    @Override
    protected void processRequest() throws Exception {
        String type;
        String message = "";

        String recipient = sm.getUserBean().getEmail();

        try {
            sendEmailImpl(
                recipient,
                EmailEngine.getAdminEmail(),
                "[LibreClinica] Test Email",
                "Since you received this email, the email setup of LibreClinica works as expected.",
                false
            );

            type = "success";
        } catch (MailException e) {
            type = "error";
            message = e.getMessage();
        }

        ObjectMapper om = new ObjectMapper();
        ObjectNode res = om.createObjectNode();
        res.put("type", type);
        res.put("message", message);
        res.put("recipient", recipient);

        response.setContentType(MediaType.APPLICATION_JSON);
        response.getWriter().write(om.writeValueAsString(res));
        response.getWriter().close();
    }

    @Override
    protected String getAdminServlet() {
        return SecureController.ADMIN_SERVLET_CODE;
    }
}
