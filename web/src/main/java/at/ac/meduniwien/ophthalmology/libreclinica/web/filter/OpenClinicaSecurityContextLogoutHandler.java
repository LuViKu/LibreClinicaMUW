/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 */
package at.ac.meduniwien.ophthalmology.libreclinica.web.filter;

import java.io.IOException;
import java.util.Date;
import java.util.Locale;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import javax.sql.DataSource;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.AuditUserLoginDao;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.login.UserAccountDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.technicaladmin.AuditUserLoginBean;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.technicaladmin.LoginStatus;
import at.ac.meduniwien.ophthalmology.libreclinica.i18n.util.ResourceBundleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;

/**
 * Ends a signed-in session: the logout of the legacy screens
 * ({@code /j_spring_security_logout}), of the SPA
 * ({@code POST /pages/api/v1/auth/logout}) and of a session that a second
 * login of the same account displaced. It writes the {@code audit_user_login} row
 * "successful logout" once, then invalidates the session and clears the
 * security context, as {@link SecurityContextLogoutHandler} does.
 * <p>
 * The session registry writes that row itself when it forgets a session, and
 * invalidating a session makes it forget it. A session the registry holds is
 * therefore removed from it here, before the session is invalidated, and the
 * registry writes the row. A session it does not hold, such as an SSO login,
 * which does not pass the session strategy, gets the row from here. A failed
 * audit write does not keep the session alive.
 * <p>
 * The logout filter runs this handler ahead of its own, which would invalidate
 * the session first. As the filter's success handler it then only redirects.
 * 
 * @author Krikor Krumlian
 */
@SuppressWarnings("all")
public class OpenClinicaSecurityContextLogoutHandler extends SecurityContextLogoutHandler implements LogoutSuccessHandler {

    private static final Logger LOG = LoggerFactory.getLogger(OpenClinicaSecurityContextLogoutHandler.class);

    AuditUserLoginDao auditUserLoginDao;
    UserAccountDAO userAccountDao;
    DataSource dataSource;
    SessionRegistry sessionRegistry;

    // ~ Methods ========================================================================================================

    /**
     * Requires the request to be passed in.
     * 
     * @param request
     *            from which to obtain a HTTP session (cannot be null)
     * @param response
     *            not used (can be <code>null</code>)
     * @param authentication
     *            whose user a session the registry does not hold is logged out as
     *            (can be <code>null</code>)
     */
    @Override
    public void logout(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
        HttpSession session = request.getSession(false);
        boolean registered = session != null && sessionRegistry.getSessionInformation(session.getId()) != null;
        try {
            if (registered) {
                sessionRegistry.removeSessionInformation(session.getId());
            } else if (authentication != null) {
                auditLogout(authentication.getName());
            }
        } catch (RuntimeException e) {
            LOG.warn("Logout audit row for the session was not written", e);
        }
        super.logout(request, response, authentication);
    }

    void auditLogout(String username) {
        ResourceBundleProvider.updateLocale(Locale.US);
        UserAccountBean userAccount = (UserAccountBean) getUserAccountDao().findByUserName(username);
        AuditUserLoginBean auditUserLogin = new AuditUserLoginBean();
        auditUserLogin.setUserName(username);
        auditUserLogin.setLoginStatus(LoginStatus.SUCCESSFUL_LOGOUT);
        auditUserLogin.setLoginAttemptDate(new Date());
        auditUserLogin.setUserAccountId(userAccount != null ? userAccount.getId() : null);
        getAuditUserLoginDao().saveOrUpdate(auditUserLogin);
    }

    public DataSource getDataSource() {
        return dataSource;
    }

    public void setDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public UserAccountDAO getUserAccountDao() {
        return userAccountDao != null ? userAccountDao : new UserAccountDAO(dataSource);
    }

    public AuditUserLoginDao getAuditUserLoginDao() {
        return auditUserLoginDao;
    }

    public void setAuditUserLoginDao(AuditUserLoginDao auditUserLoginDao) {
        this.auditUserLoginDao = auditUserLoginDao;
    }

    public void setSessionRegistry(SessionRegistry sessionRegistry) {
        this.sessionRegistry = sessionRegistry;
    }

	@Override
	public void onLogoutSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication)
			throws IOException, ServletException {
		// The logout filter has already run logout(), as its first handler.
        
        String logoutSuccessUrl = request.getContextPath() + "/MainMenu";
        response.setStatus(HttpStatus.OK.value());
        response.sendRedirect(logoutSuccessUrl);
	}
}
