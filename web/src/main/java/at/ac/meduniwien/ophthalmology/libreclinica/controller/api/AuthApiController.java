/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.core.CRFLocker;
import at.ac.meduniwien.ophthalmology.libreclinica.web.filter.OpenClinicaSecurityContextLogoutHandler;

import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The SPA's sign-out, {@code POST /pages/api/v1/auth/logout}, answered
 * {@code 204}. It replaces the SPA's {@code GET} of the legacy
 * {@code /Logout}, which stays for the JSP screens, and does what
 * {@code LogoutServlet} does:
 * <ul>
 *   <li>releases the CRFs the user holds open for data entry;</li>
 *   <li>writes the {@code audit_user_login} row "successful logout", once
 *       and during the request, and invalidates the session and clears the
 *       security context, through the handler that ends a legacy screen's
 *       session ({@link OpenClinicaSecurityContextLogoutHandler}). That also
 *       covers a session the session registry does not hold, such as an SSO
 *       login, and a failed audit write does not keep the session alive.</li>
 * </ul>
 * POST only, like every handler here that changes state: a GET can be
 * triggered from another site's page, and the {@code SameSite=Lax} session
 * cookie goes along with it.
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Auth", description = "Sign-out of the current session.")
public class AuthApiController {

    private static final Logger LOG = LoggerFactory.getLogger(AuthApiController.class);

    private final OpenClinicaSecurityContextLogoutHandler logoutHandler;
    private final CRFLocker crfLocker;

    @Autowired
    public AuthApiController(
            @Qualifier("openClinicaLogoutHandler") OpenClinicaSecurityContextLogoutHandler logoutHandler,
            CRFLocker crfLocker) {
        this.logoutHandler = logoutHandler;
        this.crfLocker = crfLocker;
    }

    @PostMapping("/logout")
    @ApiResponse(responseCode = "204", description = "Signed out; the session is gone.")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            UserAccountBean ub = (UserAccountBean) session.getAttribute("userBean");
            if (ub != null && ub.getId() > 0) {
                crfLocker.unlockAllForUser(ub.getId());
                LOG.info("User {} logged out", ub.getName());
            }
        }
        logoutHandler.logout(request, response, SecurityContextHolder.getContext().getAuthentication());
        return ResponseEntity.noContent().build();
    }
}
