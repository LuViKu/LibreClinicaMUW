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

import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
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
 *   <li>writes the {@code audit_user_login} row "successful logout". The
 *       session registry writes it when it forgets a session: for
 *       {@code LogoutServlet} and for a session timeout, when the session
 *       dies. This endpoint ends the registry entry itself, before the
 *       session, so the row is written once and during the request;</li>
 *   <li>invalidates the session and clears the security context.</li>
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

    private final SessionRegistry sessionRegistry;
    private final CRFLocker crfLocker;

    @Autowired
    public AuthApiController(@Qualifier("sessionRegistry") SessionRegistry sessionRegistry,
                             CRFLocker crfLocker) {
        this.sessionRegistry = sessionRegistry;
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
            try {
                sessionRegistry.removeSessionInformation(session.getId());
            } catch (RuntimeException e) {
                // As when the row is written for a dying session: a failed
                // audit write does not keep the session alive.
                LOG.warn("Logout audit row for the session was not written", e);
            }
        }
        new SecurityContextLogoutHandler().logout(request, response,
                SecurityContextHolder.getContext().getAuthentication());
        return ResponseEntity.noContent().build();
    }
}
