/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.web.filter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;

import at.ac.meduniwien.ophthalmology.libreclinica.control.login.AccountConfigurationException;

/**
 * Login failure for the SPA: {@code 401} with a JSON body that names the
 * reason, instead of the redirect to the login page. Any other login goes to
 * the legacy handler this one wraps. The SPA's login is recognised as in
 * {@link SpaLoginSuccessHandler#SPA_LOGIN}.
 *
 * <p>The reasons are the three the legacy handler tells apart, and no more:
 * {@code locked} ({@code action=errorLocked}), {@code 2fa_outdated}
 * ({@code action=2faOutdated}) and {@code bad_credentials} for every other
 * failure ({@code action=errorLogin}). An unknown user, a wrong password and
 * a disabled account therefore read the same.
 *
 * <p>Counting failed attempts and locking the account are the login filter's
 * work ({@link OpenClinicaUsernamePasswordAuthenticationFilter}), as are the
 * {@code audit_user_login} rows. Both happen before this handler runs, for
 * either kind of login.
 */
public class SpaLoginFailureHandler implements AuthenticationFailureHandler {

    private final AuthenticationFailureHandler legacy;
    private volatile boolean internetFacing;

    public SpaLoginFailureHandler(AuthenticationFailureHandler legacy) {
        this(legacy, false);
    }

    /**
     * @param internetFacing {@code libreclinica.deployment.internet-facing}.
     *        When true every failure, for the SPA and the legacy form alike,
     *        is reported as bad credentials: telling {@code locked} apart
     *        would let anyone on the internet confirm that a user name exists
     *        and lock a named account on purpose. The filter has already
     *        written the audit row ({@code FAILED_LOGIN_LOCKED} vs
     *        {@code FAILED_LOGIN}) and the denied-login mail, so
     *        administrators still see the real reason.
     */
    public SpaLoginFailureHandler(AuthenticationFailureHandler legacy, boolean internetFacing) {
        this.legacy = legacy;
        this.internetFacing = internetFacing;
    }

    /**
     * Set by {@code SecurityConfig} from {@code libreclinica.deployment.internet-facing}
     * (the XML bean context has no property resolution).
     */
    public void setInternetFacing(boolean internetFacing) {
        this.internetFacing = internetFacing;
    }

    @SuppressWarnings("resource") // the servlet container owns and closes the response stream/writer
    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException exception) throws IOException, ServletException {
        if (internetFacing) {
            exception = new BadCredentialsException("Bad Credentials");
        }
        if (!SpaLoginSuccessHandler.SPA_LOGIN.matches(request)) {
            legacy.onAuthenticationFailure(request, response, exception);
            return;
        }
        String reason;
        String message;
        if (exception instanceof LockedException) {
            reason = "locked";
            message = "The account is locked.";
        } else if (exception instanceof AccountConfigurationException) {
            reason = "2fa_outdated";
            message = "The two-factor authentication set-up has to be renewed.";
        } else {
            reason = "bad_credentials";
            message = "Invalid username or password.";
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        // Both values are the constants above, so no escaping is needed.
        response.getWriter().write("{\"error\":\"" + reason + "\",\"message\":\"" + message + "\"}");
    }
}
