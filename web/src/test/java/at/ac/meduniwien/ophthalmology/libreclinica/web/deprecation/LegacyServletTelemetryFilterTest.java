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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
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
import jakarta.servlet.http.HttpServletResponse;

/**
 * The legacy-retirement gate, driven with requests as Tomcat presents them
 * under the real context path: {@code /LibreClinica} in the request URI, the
 * servlet path and path info without it.
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

    private static MockHttpSession sessionOf(int id, String name, UserType type) {
        UserAccountBean user = new UserAccountBean();
        user.setId(id);
        user.setName(name);
        user.addUserType(type);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("userBean", user);
        return session;
    }

    private static MockHttpSession admin() {
        return sessionOf(1, "root", UserType.SYSADMIN);
    }

    private static MockHttpSession dataManager() {
        return sessionOf(7, "manual_dm", UserType.USER);
    }

    private LegacyServletTelemetryFilter filter(String... closed) {
        return new LegacyServletTelemetryFilter(catalog, List.of(closed));
    }

    // --- telemetry -------------------------------------------------------

    @Test
    void logsALegacyServletRequestUnderTheContextPath() throws Exception {
        MockHttpServletRequest req = request("GET", "/ListUserAccounts", null);
        req.setSession(dataManager());
        MockFilterChain chain = new MockFilterChain();

        filter().doFilter(req, new MockHttpServletResponse(), chain);

        assertSame(req, chain.getRequest(), "an open screen is passed on");
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
        LegacyServletTelemetryFilter filter = filter("/ListUserAccounts");
        for (MockHttpServletRequest req : List.of(
                request("GET", "/pages", "/api/v1/subjects"),
                request("GET", "/app/subjects", null),
                request("GET", "/images/bt_View.gif", null),
                request("GET", "/legacy", "/ListUserAccounts"))) {
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

    // --- closure ---------------------------------------------------------

    @Test
    void closedScreenAnswersGoneToAnonymousWithTheSpaRoute() throws Exception {
        MockHttpServletRequest req = request("GET", "/ListUserAccounts", null);
        req.addHeader("Accept", "application/json");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = Mockito.mock(FilterChain.class);

        filter("/ListUserAccounts").doFilter(req, resp, chain);

        verify(chain, never()).doFilter(any(), any());
        assertEquals(HttpServletResponse.SC_GONE, resp.getStatus());
        assertEquals("no-store", resp.getHeader("Cache-Control"));
        assertTrue(resp.getContentType().startsWith("application/json"), resp.getContentType());
        String body = resp.getContentAsString();
        assertTrue(body.contains("\"legacyPath\":\"/ListUserAccounts\""), body);
        assertTrue(body.contains("\"spaRoute\":\"/app/manage-users\""), body);
        assertTrue(hits().get(0).endsWith("user=anonymous alias=false action=gone"), hits().get(0));
    }

    @Test
    void closedScreenAnswersGoneToANonAdministratorAsHtml() throws Exception {
        MockHttpServletRequest req = request("GET", "/ListUserAccounts", null);
        req.addHeader("Accept", "text/html,application/xhtml+xml");
        req.setSession(dataManager());
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = Mockito.mock(FilterChain.class);

        filter("/ListUserAccounts").doFilter(req, resp, chain);

        verify(chain, never()).doFilter(any(), any());
        assertEquals(HttpServletResponse.SC_GONE, resp.getStatus());
        assertTrue(resp.getContentType().startsWith("text/html"), resp.getContentType());
        assertTrue(resp.getContentAsString().contains("href=\"/LibreClinica/app/manage-users\""),
                resp.getContentAsString());
        assertTrue(hits().get(0).endsWith("user=manual_dm alias=false action=gone"), hits().get(0));
    }

    @Test
    void closedScreenSendsAnAdministratorToTheAliasKeepingMethodBodyAndQuery() throws Exception {
        MockHttpServletRequest req = request("POST", "/ListUserAccounts", null);
        req.setQueryString("module=admin&listUserAccounts_mr_=15");
        req.setSession(admin());
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = Mockito.mock(FilterChain.class);

        filter("/ListUserAccounts").doFilter(req, resp, chain);

        verify(chain, never()).doFilter(any(), any());
        // 307, not 302: the browser repeats the POST with its body.
        assertEquals(HttpServletResponse.SC_TEMPORARY_REDIRECT, resp.getStatus());
        assertEquals("/LibreClinica/legacy/ListUserAccounts?module=admin&listUserAccounts_mr_=15",
                resp.getHeader("Location"));
        assertTrue(hits().get(0).endsWith("method=POST user=root alias=false action=redirect"), hits().get(0));
    }

    @Test
    void closedPagesRouteClosesTheScreensSubPaths() throws Exception {
        LegacyServletTelemetryFilter filter = filter("/pages/studymodule");

        MockHttpServletResponse anonymous = new MockHttpServletResponse();
        filter.doFilter(request("POST", "/pages", "/studymodule/S_DEFAULTS1/deactivate"),
                anonymous, Mockito.mock(FilterChain.class));
        assertEquals(HttpServletResponse.SC_GONE, anonymous.getStatus());

        MockHttpServletRequest byAdmin = request("POST", "/pages", "/studymodule/S_DEFAULTS1/deactivate");
        byAdmin.setSession(admin());
        MockHttpServletResponse redirected = new MockHttpServletResponse();
        filter.doFilter(byAdmin, redirected, Mockito.mock(FilterChain.class));
        assertEquals(HttpServletResponse.SC_TEMPORARY_REDIRECT, redirected.getStatus());
        assertEquals("/LibreClinica/legacy/pages/studymodule/S_DEFAULTS1/deactivate",
                redirected.getHeader("Location"));
    }

    @Test
    void closedHeritageApiAnswersGoneToAnApiKeyCallerAndLeavesTheSpaApiAlone() throws Exception {
        LegacyServletTelemetryFilter filter = filter("/pages/auth");

        MockHttpServletRequest apiCall = request("GET", "/pages", "/auth/api/v1/system/config");
        apiCall.addHeader("Authorization", "Basic " + java.util.Base64.getEncoder()
                .encodeToString("api-key:unused".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        MockHttpServletResponse gone = new MockHttpServletResponse();
        FilterChain apiChain = Mockito.mock(FilterChain.class);
        filter.doFilter(apiCall, gone, apiChain);
        assertEquals(HttpServletResponse.SC_GONE, gone.getStatus());
        Mockito.verifyNoInteractions(apiChain);

        MockHttpServletResponse spa = new MockHttpServletResponse();
        FilterChain spaChain = Mockito.mock(FilterChain.class);
        filter.doFilter(request("GET", "/pages", "/api/v1/me"), spa, spaChain);
        Mockito.verify(spaChain).doFilter(Mockito.any(), Mockito.any());
    }

    @Test
    void closingOneScreenLeavesTheOthersOpen() throws Exception {
        FilterChain chain = Mockito.mock(FilterChain.class);
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter("/ListUserAccounts").doFilter(request("GET", "/ViewUserAccount", null), resp, chain);

        verify(chain, times(1)).doFilter(any(), any());
        assertEquals(200, resp.getStatus());
    }

    @Test
    void placeholderSessionUserCountsAsAnonymous() throws Exception {
        // SetUpUserInterceptor stores an id-0 bean named "unknown" for anonymous requests.
        MockHttpServletRequest req = request("GET", "/ListUserAccounts", null);
        MockHttpSession session = new MockHttpSession();
        UserAccountBean unknown = new UserAccountBean();
        unknown.setName("unknown");
        unknown.addUserType(UserType.SYSADMIN);
        session.setAttribute("userBean", unknown);
        req.setSession(session);
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter("/ListUserAccounts").doFilter(req, resp, Mockito.mock(FilterChain.class));

        assertEquals(HttpServletResponse.SC_GONE, resp.getStatus());
        assertTrue(hits().get(0).contains("user=anonymous"), hits().get(0));
    }

    // --- configuration ---------------------------------------------------

    @Test
    void closedPathsAreParsedFromCommasAndWhitespace() {
        assertEquals(List.of("/ListUserAccounts", "/pages/studymodule", "/Configure"),
                LegacyServletTelemetryFilter.parseClosedPaths(" /ListUserAccounts,/pages/studymodule\n  /Configure, "));
        assertEquals(List.of(), LegacyServletTelemetryFilter.parseClosedPaths(""));
        assertEquals(List.of(), LegacyServletTelemetryFilter.parseClosedPaths(null));
    }

    @Test
    void anUnknownClosedPathIsReportedAndIgnored() {
        LegacyServletTelemetryFilter filter = filter("/ListUserAccounts", "/NoSuchScreen", "/pages/ListUserAccounts");

        assertEquals(java.util.Set.of("/ListUserAccounts"), filter.closedPaths());
        List<ILoggingEvent> errors = logged.list.stream().filter(e -> e.getLevel() == Level.ERROR).toList();
        assertEquals(2, errors.size());
        assertTrue(errors.get(0).getFormattedMessage().contains("'/NoSuchScreen'"),
                errors.get(0).getFormattedMessage());
    }

    @Test
    void nonHttpRequestPassesThrough() throws Exception {
        jakarta.servlet.ServletRequest req = Mockito.mock(jakarta.servlet.ServletRequest.class);
        jakarta.servlet.ServletResponse resp = Mockito.mock(jakarta.servlet.ServletResponse.class);
        FilterChain chain = Mockito.mock(FilterChain.class);

        filter("/ListUserAccounts").doFilter(req, resp, chain);

        verify(chain, times(1)).doFilter(req, resp);
    }

    // --- the 410 page ----------------------------------------------------

    @Test
    void goneHtmlLinksAParameterisedRouteToTheSpaStartPage() {
        String html = LegacyServletTelemetryFilter.goneHtml(CONTEXT,
                catalog.entry("/ViewStudySubject").orElseThrow());
        assertTrue(html.contains("href=\"/LibreClinica/app/\""), html);
    }

    @Test
    void goneHtmlWithoutAnSpaRouteSaysSo() {
        String html = LegacyServletTelemetryFilter.goneHtml(CONTEXT, catalog.entry("/Configure").orElseThrow());
        assertTrue(html.contains("noch keinen Ersatz"), html);
        assertTrue(html.contains("href=\"/LibreClinica/app/\""), html);
    }

    @Test
    void jsonIsChosenOnlyWhenAskedFor() {
        MockHttpServletRequest browser = request("GET", "/ListUserAccounts", null);
        browser.addHeader("Accept", "text/html,*/*;q=0.8");
        MockHttpServletRequest xhr = request("GET", "/ListUserAccounts", null);
        xhr.addHeader("Accept", "application/json, text/javascript, */*; q=0.01");
        MockHttpServletRequest none = request("GET", "/ListUserAccounts", null);

        assertTrue(!LegacyServletTelemetryFilter.prefersJson(browser));
        assertTrue(LegacyServletTelemetryFilter.prefersJson(xhr));
        assertTrue(!LegacyServletTelemetryFilter.prefersJson(none));
        assertNull(none.getHeader("Accept"));
    }
}
