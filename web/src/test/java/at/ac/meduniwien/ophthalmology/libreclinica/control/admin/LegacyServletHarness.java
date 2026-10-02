/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.control.admin;

import java.util.Date;
import java.util.Locale;

import javax.sql.DataSource;

import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletConfig;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.GenericWebApplicationContext;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Status;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.UserType;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.control.core.SecureController;
import at.ac.meduniwien.ophthalmology.libreclinica.core.CRFLocker;
import at.ac.meduniwien.ophthalmology.libreclinica.i18n.util.ResourceBundleProvider;

/**
 * Runs a legacy {@link SecureController} servlet through its real
 * {@code service()}: method dispatch, session set-up, {@code mayProceed},
 * {@code processRequest} and the closing forward, with no container. The
 * Spring context holds only the beans these servlets look up by name; each
 * test registers the ones its servlet needs.
 */
final class LegacyServletHarness {

    static final int STUDY_ID = 1;

    private final MockServletContext servletContext = new MockServletContext();
    private final GenericWebApplicationContext spring = new GenericWebApplicationContext();

    LegacyServletHarness(DataSource dataSource) {
        spring.setServletContext(servletContext);
        spring.refresh();
        servletContext.setAttribute(WebApplicationContext.ROOT_WEB_APPLICATION_CONTEXT_ATTRIBUTE, spring);
        bean("dataSource", dataSource);
        bean("crfLocker", new CRFLocker());
    }

    LegacyServletHarness bean(String name, Object bean) {
        spring.getBeanFactory().registerSingleton(name, bean);
        return this;
    }

    /** A system administrator whose password is current, so no reset-password detour. */
    static UserAccountBean sysAdmin(int id, String name) {
        UserAccountBean ub = new UserAccountBean();
        ub.setId(id);
        ub.setName(name);
        ub.setEmail(name + "@example.invalid");
        ub.addUserType(UserType.SYSADMIN);
        ub.setPasswdTimestamp(new Date());
        return ub;
    }

    /** A request from {@code user}'s session, working in study {@link #STUDY_ID}. */
    MockHttpServletRequest request(String method, String servletPath, UserAccountBean user) {
        ResourceBundleProvider.updateLocale(Locale.ENGLISH);
        MockHttpServletRequest request = new MockHttpServletRequest(servletContext, method, servletPath);
        request.setServletPath(servletPath);
        StudyBean study = new StudyBean();
        study.setId(STUDY_ID);
        study.setStatus(Status.AVAILABLE);
        request.getSession().setAttribute(SecureController.USER_BEAN_NAME, user);
        request.getSession().setAttribute("study", study);
        return request;
    }

    MockHttpServletResponse run(SecureController servlet, MockHttpServletRequest request) throws Exception {
        if (servlet.getServletConfig() == null) {
            servlet.init(new MockServletConfig(servletContext));
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        servlet.service(request, response);
        return response;
    }
}
