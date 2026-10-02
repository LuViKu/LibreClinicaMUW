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
import java.util.Set;

import javax.sql.DataSource;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.WebAttributes;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Status;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.control.core.SecureController;
import at.ac.meduniwien.ophthalmology.libreclinica.core.SessionManager;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.login.UserAccountDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.service.StudyConfigService;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.service.StudyParameterValueDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.web.SQLInitServlet;

/**
 * Login success for the SPA: {@code 204 No Content} instead of the redirect
 * to {@code /MainMenu}. Any other login goes to the legacy handler this one
 * wraps, which redirects as before.
 *
 * <p>The SPA asks for this answer with {@code Accept: application/json}
 * ({@link #SPA_LOGIN}). A browser submitting a form never sends that.
 *
 * <p>The SPA used to follow the redirect, and {@code SecureController} and
 * {@code MainMenuServlet} then did four things it depends on. This handler
 * does them itself:
 * <ul>
 *   <li>the session's idle timeout, {@code max_inactive_interval} (3600
 *       seconds when the setting is not a number);</li>
 *   <li>the {@code study} and {@code userRole} session attributes for the
 *       account's stored active study, which {@code GET /me} and the SPA's
 *       API controllers read;</li>
 *   <li>{@code user_account.date_lastvisit}, which the SPA's user list shows
 *       as the last login; while it is empty the account reads as invited
 *       but never signed in;</li>
 *   <li>the data source {@link SessionManager#getStaticDataSource()} gives
 *       to beans that load their owner lazily, such as the author of a
 *       discrepancy note.</li>
 * </ul>
 * The password checks need nothing here. {@code GET /me} works out
 * {@code mustChangePassword} (first login, expired password) from the
 * session user on every call, and the SPA's router acts on it.
 *
 * <p>{@code MainMenuServlet} also stamps the account as updated by itself,
 * as a side effect of saving the visit through the full-row update. The SPA
 * does not read that, and a login is not a change to the account, so this
 * handler writes the visit alone.
 */
public class SpaLoginSuccessHandler implements AuthenticationSuccessHandler {

    /** A login sent by the SPA: it accepts JSON. A wildcard does not count. */
    public static final RequestMatcher SPA_LOGIN = spaLogin();

    private static final Logger LOG = LoggerFactory.getLogger(SpaLoginSuccessHandler.class);

    /** SecureController's value when max_inactive_interval is not a number. */
    private static final int DEFAULT_MAX_INACTIVE_INTERVAL = 3600;

    private final AuthenticationSuccessHandler legacy;
    private final DataSource dataSource;

    public SpaLoginSuccessHandler(AuthenticationSuccessHandler legacy, DataSource dataSource) {
        this.legacy = legacy;
        this.dataSource = dataSource;
    }

    private static RequestMatcher spaLogin() {
        MediaTypeRequestMatcher json = new MediaTypeRequestMatcher(MediaType.APPLICATION_JSON);
        json.setIgnoredMediaTypes(Set.of(MediaType.ALL));
        return json;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
            Authentication authentication) throws IOException, ServletException {
        if (!SPA_LOGIN.matches(request)) {
            legacy.onAuthenticationSuccess(request, response, authentication);
            return;
        }
        HttpSession session = request.getSession();
        SessionManager.setStaticDataSource(dataSource);
        session.setMaxInactiveInterval(maxInactiveInterval());
        UserAccountBean ub = (UserAccountBean) session.getAttribute(SecureController.USER_BEAN_NAME);
        if (ub != null && ub.getId() > 0) {
            // The account is signed in by now. A failure here costs the SPA
            // its study binding (it offers the study picker) or the visit
            // date, not the login. A previous login's binding, which the
            // session strategy carried into this session, goes first, so a
            // failure cannot leave it beside this account.
            session.setAttribute("study", new StudyBean());
            session.setAttribute("userRole", new StudyUserRoleBean());
            try {
                bindActiveStudy(session, ub);
                new UserAccountDAO(dataSource).updateLastVisitDate(ub.getId());
            } catch (RuntimeException e) {
                LOG.warn("Session set-up after the SPA login of user_id={} failed", ub.getId(), e);
            }
        }
        // What the legacy handler clears once it has redirected.
        session.removeAttribute(WebAttributes.AUTHENTICATION_EXCEPTION);
        new HttpSessionRequestCache().removeRequest(request, response);
        response.setStatus(HttpServletResponse.SC_NO_CONTENT);
    }

    private static int maxInactiveInterval() {
        try {
            return Integer.parseInt(SQLInitServlet.getField("max_inactive_interval"));
        } catch (NumberFormatException e) {
            return DEFAULT_MAX_INACTIVE_INTERVAL;
        }
    }

    /**
     * Binds the session to the account's stored active study as
     * {@code SecureController} does for a session without one: the study
     * with its parameter configuration, and the account's role in it, which
     * on a site is raised to its role in the parent study. Without an active
     * study both attributes are empty beans, as there. A removed or
     * auto-removed study gets no role, which is what {@code SecureController}
     * gives such a study from the next request on. Attributes a previous
     * login left in this browser session are replaced.
     */
    private void bindActiveStudy(HttpSession session, UserAccountBean ub) {
        StudyBean study = new StudyBean();
        StudyUserRoleBean role = new StudyUserRoleBean();
        if (ub.getActiveStudyId() > 0) {
            StudyDAO studyDao = new StudyDAO(dataSource);
            study = studyDao.findByPK(ub.getActiveStudyId());
            study.setStudyParameters(new StudyParameterValueDAO(dataSource).findParamConfigByStudy(study));
            StudyConfigService config = new StudyConfigService(dataSource);
            if (study.getParentStudyId() <= 0) {
                config.setParametersForStudy(study);
            } else {
                study.setParentStudyName(studyDao.findByPK(study.getParentStudyId()).getName());
                config.setParametersForSite(study);
            }
            boolean removed = Status.DELETED.equals(study.getStatus())
                    || Status.AUTO_DELETED.equals(study.getStatus());
            if (study.getId() > 0 && !removed) {
                role = ub.getRoleByStudy(study.getId());
                if (study.getParentStudyId() > 0) {
                    StudyUserRoleBean roleInParent = ub.getRoleByStudy(study.getParentStudyId());
                    role.setRole(Role.max(role.getRole(), roleInParent.getRole()));
                }
            }
        }
        session.setAttribute("study", study);
        session.setAttribute("userRole", role);
    }
}
