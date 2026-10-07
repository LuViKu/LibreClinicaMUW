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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.UserType;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;

/**
 * {@code libreclinica.deployment.internet-facing=true} on the legacy gate:
 * every legacy servlet and every {@code /pages} MVC screen is closed for a
 * non-administrator, the SPA's own paths stay open, an administrator is not
 * affected, and with the flag off nothing changes.
 */
class LegacyInternetFacingGateTest {

    private static final String CONTEXT = "/LibreClinica";

    private final LegacyServletDeprecationCatalog catalog = new LegacyServletDeprecationCatalog();

    private static MockHttpServletRequest request(String servletPath, String pathInfo) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET",
                CONTEXT + servletPath + (pathInfo == null ? "" : pathInfo));
        req.setContextPath(CONTEXT);
        req.setServletPath(servletPath);
        req.setPathInfo(pathInfo);
        return req;
    }

    private static MockHttpSession sessionOf(UserType type) {
        UserAccountBean user = new UserAccountBean();
        user.setId(type == UserType.SYSADMIN ? 1 : 7);
        user.setName(type == UserType.SYSADMIN ? "root" : "site.crc");
        user.addUserType(type);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("userBean", user);
        return session;
    }

    private static final class Result {
        final int status;
        final boolean reachedApp;
        final String location;

        Result(int status, boolean reachedApp, String location) {
            this.status = status;
            this.reachedApp = reachedApp;
            this.location = location;
        }
    }

    private Result call(boolean internetFacing, MockHttpSession session, String servletPath, String pathInfo)
            throws Exception {
        LegacyServletTelemetryFilter filter = new LegacyServletTelemetryFilter(catalog, List.of(), internetFacing);
        MockHttpServletRequest req = request(servletPath, pathInfo);
        if (session != null) {
            req.setSession(session);
        }
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(req, resp, chain);
        return new Result(resp.getStatus(), chain.getRequest() != null, resp.getHeader("Location"));
    }

    private static final List<String[]> SAMPLE_CLOSED = List.of(
            new String[] {"/ViewEventCRF", null},
            new String[] {"/RemoveStudySubject", null},
            new String[] {"/PrintDataEntry", null},
            new String[] {"/ChangeStudy", null},
            new String[] {"/ViewStudySubject", null},
            new String[] {"/Logout", null},
            new String[] {"/pages", "/extract"},
            new String[] {"/pages", "/studymodule/S_1/deactivate"},
            // not in the catalogue: caught by the /pages catch-all
            new String[] {"/pages", "/forms/migrate/preview"},
            new String[] {"/pages", "/rule/runRuleSet"},
            new String[] {"/pages", "/odmss/studies"},
            new String[] {"/pages", "/viewSubjectAggregateX"});

    @Test
    void flagOn_nonSysadminGets410OnLegacyDataScreens() throws Exception {
        for (String[] p : SAMPLE_CLOSED) {
            Result r = call(true, sessionOf(UserType.USER), p[0], p[1]);
            assertEquals(410, r.status, p[0] + (p[1] == null ? "" : p[1]));
            assertTrue(!r.reachedApp, p[0]);
        }
    }

    @Test
    void flagOn_anonymousCallerAlsoGets410() throws Exception {
        assertEquals(410, call(true, null, "/ViewEventCRF", null).status);
        assertEquals(410, call(true, null, "/pages", "/extract").status);
    }

    @Test
    void flagOn_everyCataloguedScreenIsClosedExceptTheAnonymousOnlyOne() throws Exception {
        for (String key : catalog.all().keySet()) {
            if (LegacyServletTelemetryFilter.INTERNET_FACING_ANONYMOUS_ONLY.contains(key)) {
                continue;
            }
            String servletPath = key.startsWith("/pages/") ? "/pages" : key;
            String pathInfo = key.startsWith("/pages/") ? key.substring("/pages".length()) : null;
            Result r = call(true, sessionOf(UserType.USER), servletPath, pathInfo);
            assertEquals(410, r.status, key);
        }
    }

    @Test
    void flagOn_theSpasOwnPathsStayOpen() throws Exception {
        MockHttpSession user = sessionOf(UserType.USER);
        for (String info : List.of("/api/v1/me", "/api/v1/auth/logout", "/api/v1/subjects/1", "/api/v2/anonymousform/x",
                "/login/login", "/sso/reauth", "/v3/api-docs/spa-api", "/swagger-ui.html")) {
            Result r = call(true, user, "/pages", info);
            assertEquals(200, r.status, info);
            assertTrue(r.reachedApp, info);
        }
    }

    @Test
    void flagOn_mainMenuIsOpenWithoutAUserAndSendsASignedInUserToTheSpa() throws Exception {
        assertTrue(call(true, null, "/MainMenu", null).reachedApp);
        Result signedIn = call(true, sessionOf(UserType.USER), "/MainMenu", null);
        assertEquals(302, signedIn.status);
        assertEquals(CONTEXT + "/app/", signedIn.location);
        assertTrue(!signedIn.reachedApp);
    }

    @Test
    void flagOn_sysadminGetsThrough() throws Exception {
        MockHttpSession admin = sessionOf(UserType.SYSADMIN);
        // Catalogued: redirected to the /legacy/ alias, as for any closed screen.
        Result catalogued = call(true, admin, "/ViewEventCRF", null);
        assertEquals(307, catalogued.status);
        assertNotNull(catalogued.location);
        assertTrue(catalogued.location.startsWith(CONTEXT + "/legacy/"), catalogued.location);
        // Not catalogued: passed on, no alias exists.
        Result pages = call(true, admin, "/pages", "/rule/runRuleSet");
        assertEquals(200, pages.status);
        assertTrue(pages.reachedApp);
        Result menu = call(true, admin, "/MainMenu", null);
        assertEquals(307, menu.status);
    }

    @Test
    void flagOff_nothingIsClosedByTheSwitch() throws Exception {
        MockHttpSession user = sessionOf(UserType.USER);
        for (String[] p : SAMPLE_CLOSED) {
            Result r = call(false, user, p[0], p[1]);
            assertEquals(200, r.status, p[0] + (p[1] == null ? "" : p[1]));
            assertTrue(r.reachedApp);
        }
        assertTrue(call(false, user, "/MainMenu", null).reachedApp);
    }

    @Test
    void flagOn_configuredClosedPathsStillApplyOnTop() throws Exception {
        LegacyServletTelemetryFilter filter = new LegacyServletTelemetryFilter(catalog, List.of("/MainMenu"), true);
        MockHttpServletRequest req = request("/MainMenu", null);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(req, resp, new MockFilterChain());
        // Anonymous-only exemption is for the open lists; an explicit closure on the key still lets
        // the anonymous caller reach the login redirect.
        assertEquals(200, resp.getStatus());
        assertNull(resp.getHeader("Location"));
    }
}
