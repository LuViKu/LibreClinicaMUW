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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.UserType;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * The administrators' {@code /legacy/} alias: who is served, what is
 * forwarded where, and what is logged.
 */
class LegacyAliasServletTest {

    private final LegacyAliasServlet alias = new LegacyAliasServlet(new LegacyServletDeprecationCatalog());

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
        return logged.list.stream().map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.startsWith("legacy-hit")).toList();
    }

    private static MockHttpServletRequest aliasRequest(String method, String pathInfo, MockHttpSession session) {
        MockHttpServletRequest req = new MockHttpServletRequest(method, "/LibreClinica/legacy" + pathInfo);
        req.setContextPath("/LibreClinica");
        req.setServletPath("/legacy");
        req.setPathInfo(pathInfo);
        if (session != null) {
            req.setSession(session);
        }
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

    private MockHttpServletResponse serve(MockHttpServletRequest req) throws Exception {
        MockHttpServletResponse resp = new MockHttpServletResponse();
        alias.service(req, resp);
        return resp;
    }

    @Test
    void administratorIsForwardedToTheScreen() throws Exception {
        MockHttpServletResponse resp = serve(aliasRequest("GET", "/ListUserAccounts",
                sessionOf(1, "root", UserType.SYSADMIN)));

        assertEquals("/ListUserAccounts", resp.getForwardedUrl());
        assertEquals(List.of("legacy-hit path=/ListUserAccounts bucket=USER_ACCOUNTS spaRoute=/app/manage-users"
                + " method=GET user=root alias=true action=forward"), hits());
    }

    @Test
    void techAdministratorIsForwardedToo() throws Exception {
        MockHttpServletResponse resp = serve(aliasRequest("GET", "/Configure",
                sessionOf(3, "tech", UserType.TECHADMIN)));

        assertEquals("/Configure", resp.getForwardedUrl());
    }

    @Test
    void administratorIsForwardedToAPagesRouteSubPath() throws Exception {
        MockHttpServletResponse resp = serve(aliasRequest("POST", "/pages/studymodule/S_DEFAULTS1/deactivate",
                sessionOf(1, "root", UserType.SYSADMIN)));

        assertEquals("/pages/studymodule/S_DEFAULTS1/deactivate", resp.getForwardedUrl());
        assertTrue(hits().get(0).startsWith("legacy-hit path=/pages/studymodule "), hits().get(0));
    }

    @Test
    void administratorGetsTheLegacyAssetsThatPagesLinkRelatively() throws Exception {
        MockHttpSession admin = sessionOf(1, "root", UserType.SYSADMIN);

        assertEquals("/images/bt_View.gif", serve(aliasRequest("GET", "/images/bt_View.gif", admin)).getForwardedUrl());
        assertEquals("/includes/styles.css", serve(aliasRequest("GET", "/includes/styles.css", admin)).getForwardedUrl());
        assertEquals(List.of(), hits(), "assets are not logged");
    }

    @Test
    void nonAdministratorIsRefusedAndLogged() throws Exception {
        MockHttpServletResponse resp = serve(aliasRequest("GET", "/ListUserAccounts",
                sessionOf(7, "manual_dm", UserType.USER)));

        assertEquals(404, resp.getStatus());
        assertNull(resp.getForwardedUrl());
        assertEquals(List.of("legacy-hit path=/ListUserAccounts bucket=USER_ACCOUNTS spaRoute=/app/manage-users"
                + " method=GET user=manual_dm alias=true action=refuse"), hits());
    }

    @Test
    void requestWithoutASessionUserIsRefused() throws Exception {
        MockHttpServletResponse resp = serve(aliasRequest("GET", "/ListUserAccounts", null));

        assertEquals(404, resp.getStatus());
        assertNull(resp.getForwardedUrl());
        assertTrue(hits().get(0).endsWith("user=anonymous alias=true action=refuse"), hits().get(0));
    }

    @Test
    void nonAdministratorGetsNoAssetsEither() throws Exception {
        MockHttpServletResponse resp = serve(aliasRequest("GET", "/images/bt_View.gif",
                sessionOf(7, "manual_dm", UserType.USER)));

        assertEquals(404, resp.getStatus());
        assertNull(resp.getForwardedUrl());
    }

    @Test
    void anythingButALegacyScreenOrAssetIsNotFoundEvenForAnAdministrator() throws Exception {
        MockHttpSession admin = sessionOf(1, "root", UserType.SYSADMIN);
        for (String path : List.of("/WEB-INF/web.xml", "/META-INF/context.xml", "/pages/api/v1/subjects",
                "/app/subjects", "/includes/allIcons.jsp", "/images/", "/legacy/ListUserAccounts",
                "/images/../WEB-INF/web.xml", "/ListUserAccounts;x=1", "/ListUserAccounts%3F", "/")) {
            MockHttpServletResponse resp = serve(aliasRequest("GET", path, admin));
            assertEquals(404, resp.getStatus(), path);
            assertNull(resp.getForwardedUrl(), path);
        }
        MockHttpServletRequest bare = aliasRequest("GET", "", admin);
        bare.setPathInfo(null);
        assertEquals(404, serve(bare).getStatus());
        assertEquals(List.of(), hits());
    }

    @Test
    void plainPathRules() {
        assertTrue(LegacyAliasServlet.isPlainPath("/includes/jmesa/jquery.jmesa.js"));
        assertFalse(LegacyAliasServlet.isPlainPath("/images/a b.gif"));
        assertFalse(LegacyAliasServlet.isPlainPath("//ListUserAccounts"));
        assertFalse(LegacyAliasServlet.isPlainPath("/images/..hidden"));
        assertFalse(LegacyAliasServlet.isPlainPath("ListUserAccounts"));
        assertFalse(LegacyAliasServlet.isPlainPath(null));
    }
}
