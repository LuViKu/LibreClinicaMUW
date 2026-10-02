/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.web.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

import com.fasterxml.jackson.databind.ObjectMapper;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.control.core.SecureController;
import at.ac.meduniwien.ophthalmology.libreclinica.control.login.AccountConfigurationException;
import at.ac.meduniwien.ophthalmology.libreclinica.core.SessionManager;

/**
 * The login answers as {@code applicationContext-security.xml} wires them.
 * The SPA's login, which accepts JSON, gets {@code 204} or a {@code 401}
 * naming the reason. Every other login, a browser's form or a client that
 * accepts anything, gets the legacy redirects. {@code SpaLoginLogoutDatabaseIT}
 * drives the whole login filter against a database.
 */
@SuppressWarnings("resource") // Connection, PreparedStatement and ResultSet here are Mockito mocks; there is nothing to close
class SpaLoginHandlersTest {

    private static final String BROWSER = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8";
    private static final Authentication SIGNED_IN = new UsernamePasswordAuthenticationToken(
            "someone", null, AuthorityUtils.createAuthorityList("ROLE_USER"));

    private DefaultListableBeanFactory beans;
    private DataSource savedStaticDataSource;

    @BeforeEach
    void loadTheSecurityConfiguration() {
        beans = new DefaultListableBeanFactory();
        new XmlBeanDefinitionReader(beans).loadBeanDefinitions(new ClassPathResource(
                "at/ac/meduniwien/ophthalmology/libreclinica/applicationContext-security.xml"));
        beans.registerSingleton("dataSource", mock(DataSource.class));
        savedStaticDataSource = SessionManager.getStaticDataSource();
    }

    @AfterEach
    void restoreTheStaticDataSource() {
        // An SPA login hands its data source to SessionManager.
        SessionManager.setStaticDataSource(savedStaticDataSource);
    }

    private AuthenticationSuccessHandler success() {
        return beans.getBean("successHandler", AuthenticationSuccessHandler.class);
    }

    private AuthenticationFailureHandler failure() {
        return beans.getBean("failureHandler", AuthenticationFailureHandler.class);
    }

    private static MockHttpServletRequest login(String accept) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/LibreClinica/j_spring_security_check");
        request.setContextPath("/LibreClinica");
        request.setServletPath("/j_spring_security_check");
        if (accept != null) {
            request.addHeader("Accept", accept);
        }
        return request;
    }

    private MockHttpServletResponse failed(String accept, AuthenticationException exception) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        failure().onAuthenticationFailure(login(accept), response, exception);
        return response;
    }

    @Test
    void anyOtherLoginIsRedirectedAsBefore() throws Exception {
        for (String accept : Arrays.asList(BROWSER, "*/*", null)) {
            MockHttpServletResponse signedIn = new MockHttpServletResponse();
            success().onAuthenticationSuccess(login(accept), signedIn, SIGNED_IN);
            assertEquals("/LibreClinica/MainMenu", signedIn.getRedirectedUrl(), "Accept: " + accept);

            assertEquals("/LibreClinica/pages/login/login?action=errorLogin",
                    failed(accept, new BadCredentialsException("x")).getRedirectedUrl(), "Accept: " + accept);
            assertEquals("/LibreClinica/pages/login/login?action=errorLocked",
                    failed(accept, new LockedException("x")).getRedirectedUrl(), "Accept: " + accept);
            assertEquals("/LibreClinica/pages/login/login?action=2faOutdated",
                    failed(accept, new AccountConfigurationException()).getRedirectedUrl(), "Accept: " + accept);
        }
    }

    @Test
    void anSpaLoginIsAnswered204() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        // No user in the session, so no study to bind: the answer alone.
        success().onAuthenticationSuccess(login("application/json"), response, SIGNED_IN);

        assertEquals(204, response.getStatus());
        assertNull(response.getRedirectedUrl());
    }

    @Test
    void aStudyBindingThatFailsLeavesNoneFromAPreviousLogin() throws Exception {
        when(beans.getBean("dataSource", DataSource.class).getConnection())
                .thenThrow(new SQLException("the database cannot be reached"));
        // What an earlier login in this browser left, carried into the new
        // session beside the account now signing in.
        StudyBean previousStudy = new StudyBean();
        previousStudy.setId(1);
        StudyUserRoleBean previousRole = new StudyUserRoleBean();
        previousRole.setRole(Role.STUDYDIRECTOR);
        UserAccountBean next = new UserAccountBean();
        next.setId(42);
        next.setName("someone");
        next.setActiveStudyId(1);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("study", previousStudy);
        session.setAttribute("userRole", previousRole);
        session.setAttribute(SecureController.USER_BEAN_NAME, next);
        MockHttpServletRequest request = login("application/json");
        request.setSession(session);
        MockHttpServletResponse response = new MockHttpServletResponse();

        success().onAuthenticationSuccess(request, response, SIGNED_IN);

        assertEquals(204, response.getStatus(), "the login stands");
        assertEquals(0, ((StudyBean) Objects.requireNonNull(session.getAttribute("study"))).getId(), "no study is bound");
        assertEquals(0, ((StudyUserRoleBean) Objects.requireNonNull(session.getAttribute("userRole"))).getId(), "no role is bound");
    }

    @Test
    void anSpaLoginFailureIsA401NamingTheReason() throws Exception {
        List<Object[]> cases = List.of(
                new Object[] { new BadCredentialsException("x"), "bad_credentials" },
                new Object[] { new DisabledException("x"), "bad_credentials" },
                new Object[] { new LockedException("x"), "locked" },
                new Object[] { new AccountConfigurationException(), "2fa_outdated" });
        for (Object[] c : cases) {
            MockHttpServletResponse response = failed("application/json", (AuthenticationException) c[0]);

            assertEquals(401, response.getStatus(), c[1].toString());
            assertNull(response.getRedirectedUrl());
            assertTrue(Objects.requireNonNull(response.getContentType()).startsWith("application/json"));
            assertEquals(c[1], new ObjectMapper().readTree(response.getContentAsString()).get("error").asText());
        }
    }
}
