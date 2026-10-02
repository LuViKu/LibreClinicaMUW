/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Date;

import jakarta.servlet.FilterChain;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.security.web.session.ConcurrentSessionFilter;

/**
 * An account has one session at a time: a second login expires the first
 * session, and that session's next request is sent to the main menu, which
 * leads to the login page. The concurrencyFilter bean of
 * applicationContext-security.xml used a constructor whose expiry handler
 * redirects through a strategy Spring Security 6 leaves unset, so the
 * displaced session answered HTTP 500 with a NullPointerException.
 */
class ExpiredSessionRedirectTest {

    @Test
    void aDisplacedSessionIsRedirectedToTheMainMenu() throws Exception {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        new XmlBeanDefinitionReader(beans).loadBeanDefinitions(new ClassPathResource(
                "at/ac/meduniwien/ophthalmology/libreclinica/applicationContext-security.xml"));
        SessionInformation displaced = new SessionInformation("manual_admin", "FIRST-SESSION", new Date());
        displaced.expireNow();
        SessionRegistry registry = mock(SessionRegistry.class);
        when(registry.getSessionInformation("FIRST-SESSION")).thenReturn(displaced);
        beans.registerSingleton("sessionRegistry", registry);
        beans.registerSingleton("openClinicaLogoutHandler", mock(LogoutHandler.class));
        ConcurrentSessionFilter filter = beans.getBean("concurrencyFilter", ConcurrentSessionFilter.class);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/LibreClinica/ListStudySubjects");
        request.setContextPath("/LibreClinica");
        request.setSession(new MockHttpSession(null, "FIRST-SESSION"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertEquals(302, response.getStatus());
        assertEquals("/LibreClinica/MainMenu", response.getRedirectedUrl());
        verifyNoInteractions(chain);
    }
}
