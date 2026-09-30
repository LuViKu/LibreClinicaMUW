/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.web.deprecation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.UserType;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.FilterChain;

/**
 * The legacy-retirement tracking filter, driven with requests as Tomcat
 * presents them under the real context path: {@code /LibreClinica} in the
 * request URI, the servlet path and path info without it.
 */
class LegacyServletTelemetryFilterTest {

    private static final String CONTEXT = "/LibreClinica";

    private final LegacyServletDeprecationCatalog catalog = new LegacyServletDeprecationCatalog();

    private final Logger accessLogger = (Logger) LoggerFactory.getLogger("legacy-access");
    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();
    private Level savedLevel;

    @BeforeEach
    void captureAccessLog() {
        savedLevel = accessLogger.getLevel();
        accessLogger.setLevel(Level.INFO);
        logged.start();
        accessLogger.addAppender(logged);
    }

    @AfterEach
    void releaseAccessLog() {
        accessLogger.detachAppender(logged);
        accessLogger.setLevel(savedLevel);
    }

    private List<String> hits() {
        return logged.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.startsWith("legacy-hit"))
                .toList();
    }

    private static MockHttpServletRequest request(String method, String servletPath, String pathInfo) {
        MockHttpServletRequest req = new MockHttpServletRequest(method,
                CONTEXT + servletPath + (pathInfo == null ? "" : pathInfo));
        req.setContextPath(CONTEXT);
        req.setServletPath(servletPath);
        req.setPathInfo(pathInfo);
        return req;
    }

    private static MockHttpSession dataManager() {
        UserAccountBean user = new UserAccountBean();
        user.setId(7);
        user.setName("manual_dm");
        user.addUserType(UserType.USER);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("userBean", user);
        return session;
    }

    private LegacyServletTelemetryFilter filter() {
        return new LegacyServletTelemetryFilter(catalog);
    }

    @Test
    void logsALegacyServletRequestUnderTheContextPath() throws Exception {
        MockHttpServletRequest req = request("GET", "/ListUserAccounts", null);
        req.setSession(dataManager());
        MockFilterChain chain = new MockFilterChain();

        filter().doFilter(req, new MockHttpServletResponse(), chain);

        assertSame(req, chain.getRequest(), "the request is passed on");
        assertEquals(List.of("legacy-hit path=/ListUserAccounts bucket=USER_ACCOUNTS spaRoute=/app/manage-users"
                + " method=GET user=manual_dm alias=false action=pass"), hits());
    }

    @Test
    void logsAPagesRouteRequestUnderItsScreen() throws Exception {
        MockHttpServletRequest req = request("POST", "/pages", "/studymodule/S_DEFAULTS1/deactivate");

        filter().doFilter(req, new MockHttpServletResponse(), new MockFilterChain());

        assertEquals(List.of("legacy-hit path=/pages/studymodule bucket=STUDY_ADMIN_AND_BUILD"
                + " spaRoute=/app/build-study method=POST user=anonymous alias=false action=pass"), hits());
    }

    @Test
    void logsAScreenWithoutAnSpaRouteAsNone() throws Exception {
        filter().doFilter(request("GET", "/pages", "/listCurrentScheduledJobs"),
                new MockHttpServletResponse(), new MockFilterChain());

        assertEquals(1, hits().size());
        assertTrue(hits().get(0).contains("path=/pages/listCurrentScheduledJobs bucket=JOB_ADMIN spaRoute=none"),
                hits().get(0));
    }

    @Test
    void leavesOtherRequestsAloneAndUnlogged() throws Exception {
        LegacyServletTelemetryFilter filter = filter();
        for (MockHttpServletRequest req : List.of(
                request("GET", "/pages", "/api/v1/subjects"),
                request("GET", "/app/subjects", null),
                request("GET", "/images/bt_View.gif", null))) {
            MockFilterChain chain = new MockFilterChain();
            MockHttpServletResponse resp = new MockHttpServletResponse();

            filter.doFilter(req, resp, chain);

            assertSame(req, chain.getRequest(), req.getRequestURI());
            assertEquals(200, resp.getStatus(), req.getRequestURI());
        }
        assertEquals(List.of(), hits());
    }

    @Test
    void logsTheCatalogueKeyNotTheRequestedPathOrQuery() throws Exception {
        MockHttpServletRequest req = request("GET", "/pages", "/studymodule/S_DEFAULTS1/deactivate");
        req.setQueryString("label=M-001");

        filter().doFilter(req, new MockHttpServletResponse(), new MockFilterChain());

        assertEquals(1, hits().size(), hits().toString());
        assertTrue(hits().get(0).contains("path=/pages/studymodule "), hits().get(0));
        assertTrue(!hits().get(0).contains("S_DEFAULTS1") && !hits().get(0).contains("M-001"), hits().get(0));
    }

    @Test
    void logsAnUnusualMethodAsOther() throws Exception {
        filter().doFilter(request("PROPFIND", "/ListUserAccounts", null),
                new MockHttpServletResponse(), new MockFilterChain());

        assertEquals(1, hits().size(), hits().toString());
        assertTrue(hits().get(0).contains(" method=OTHER "), hits().get(0));
    }

    @Test
    void placeholderSessionUserIsLoggedAsAnonymous() throws Exception {
        // SetUpUserInterceptor stores an id-0 bean named "unknown" for anonymous requests.
        MockHttpServletRequest req = request("GET", "/ListUserAccounts", null);
        MockHttpSession session = new MockHttpSession();
        UserAccountBean unknown = new UserAccountBean();
        unknown.setName("unknown");
        session.setAttribute("userBean", unknown);
        req.setSession(session);

        filter().doFilter(req, new MockHttpServletResponse(), new MockFilterChain());

        assertEquals(1, hits().size(), hits().toString());
        assertTrue(hits().get(0).contains("user=anonymous"), hits().get(0));
    }

    @Test
    void nonHttpRequestPassesThrough() throws Exception {
        jakarta.servlet.ServletRequest req = Mockito.mock(jakarta.servlet.ServletRequest.class);
        jakarta.servlet.ServletResponse resp = Mockito.mock(jakarta.servlet.ServletResponse.class);
        FilterChain chain = Mockito.mock(FilterChain.class);

        filter().doFilter(req, resp, chain);

        verify(chain, times(1)).doFilter(req, resp);
        assertEquals(List.of(), hits());
    }
}
