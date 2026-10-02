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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.apache.catalina.Context;
import org.apache.catalina.servlets.DefaultServlet;
import org.apache.catalina.session.StandardManager;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.UserType;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.config.ServletInfraConfig;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRegistration;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Legacy retirement end to end, in a real servlet container: an embedded
 * Tomcat serving the application at {@code /LibreClinica}, with the filter
 * and the alias registered by the production {@link ServletInfraConfig} bean
 * methods, so the URL pattern, dispatcher types and servlet mapping under test
 * are the ones that ship.
 *
 * <p>The legacy servlets themselves need the whole application context, so a
 * stand-in that reports how it was reached is mapped at every catalogued
 * servlet path — the exact paths {@code LegacyServletRegistry} maps, which
 * {@code LegacyServletDeprecationCatalogTest} checks — and at {@code /pages/*}
 * for the Spring MVC routes. {@code /test/sign-in} plays the login filter's
 * part and puts a {@code userBean} into the session.
 *
 * <p>Spring Security is not in this chain. In production it sits between the
 * filter and the alias and sends an unauthenticated {@code /legacy/…} request
 * to the login page before the alias sees it; the alias's own 404 for a
 * request without a user is the second line, and is what this test sees.
 */
class LegacyRetirementEndToEndTest {

    private static final String CONTEXT = "/LibreClinica";

    /** The configured closed list: one servlet screen and one Spring MVC screen. */
    private static final String CLOSED_PATHS = "/ListUserAccounts, /pages/studymodule";

    private static Tomcat tomcat;
    private static String baseUrl;
    private static Path workDir;
    private static String savedCatalinaHome;
    private static String savedCatalinaBase;

    private final Logger accessLogger = (Logger) LoggerFactory.getLogger("legacy-access");
    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();
    private Level savedLevel;

    @BeforeAll
    static void startTomcat() throws Exception {
        // Initialise logging before Tomcat points catalina.home at a temp
        // directory, so logback.xml resolves its file paths as in every other test.
        LoggerFactory.getILoggerFactory();
        // Tomcat sets both properties for the JVM; other tests read catalina.home.
        savedCatalinaHome = System.getProperty("catalina.home");
        savedCatalinaBase = System.getProperty("catalina.base");

        workDir = Files.createTempDirectory("legacy-retirement-e2e");
        Path docBase = Files.createDirectories(workDir.resolve("webapp"));
        Files.createDirectories(docBase.resolve("images"));
        Files.writeString(docBase.resolve("images/probe.gif"), "GIF-PROBE");
        Files.createDirectories(docBase.resolve("WEB-INF"));
        Files.writeString(docBase.resolve("WEB-INF/secret.txt"), "WEB-INF-CONTENT");

        tomcat = new Tomcat();
        tomcat.setBaseDir(workDir.resolve("tomcat").toString());
        tomcat.setPort(0);
        tomcat.getConnector();
        Context context = tomcat.addContext(CONTEXT, docBase.toString());
        StandardManager sessions = new StandardManager();
        sessions.setPathname(null); // no session persistence across the stop
        context.setManager(sessions);
        context.addServletContainerInitializer((_, servletContext) -> register(servletContext), null);
        tomcat.start();
        baseUrl = "http://127.0.0.1:" + tomcat.getConnector().getLocalPort();
    }

    /** What the application registers for retirement, plus the stand-ins. */
    private static void register(ServletContext servletContext) throws ServletException {
        LegacyServletDeprecationCatalog catalog = new LegacyServletDeprecationCatalog();
        ServletInfraConfig config = new ServletInfraConfig();
        config.legacyServletTelemetryFilter(catalog, CLOSED_PATHS).onStartup(servletContext);
        config.legacyAliasServlet(catalog).onStartup(servletContext);

        ServletRegistration.Dynamic screens = servletContext.addServlet("legacyScreens", new ScreenStandIn());
        catalog.all().values().stream()
                .filter(e -> !e.isPagesRoute())
                .forEach(e -> screens.addMapping(e.legacyPath()));
        servletContext.addServlet("pages", new ScreenStandIn()).addMapping("/pages/*");
        servletContext.addServlet("signIn", new SignIn()).addMapping("/test/sign-in");
        servletContext.addServlet("default", new DefaultServlet()).addMapping("/");
    }

    @AfterAll
    static void stopTomcat() throws Exception {
        if (tomcat != null) {
            tomcat.stop();
            tomcat.destroy();
        }
        restore("catalina.home", savedCatalinaHome);
        restore("catalina.base", savedCatalinaBase);
        if (workDir != null) {
            try (Stream<Path> files = Files.walk(workDir)) {
                files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    private static void restore(String property, String value) {
        if (value == null) {
            System.clearProperty(property);
        } else {
            System.setProperty(property, value);
        }
    }

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

    // --- clients ---------------------------------------------------------

    /** A browser with its own cookie jar that does not follow redirects. */
    private static HttpClient browser() {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .cookieHandler(new CookieManager())
                .build();
    }

    private static HttpClient signedIn(String as) throws Exception {
        HttpClient client = browser();
        HttpResponse<String> resp = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + CONTEXT + "/test/sign-in?as=" + as)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(204, resp.statusCode());
        return client;
    }

    private static HttpResponse<String> get(HttpClient client, String path, String accept) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(baseUrl + path)).header("Accept", accept).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> postForm(HttpClient client, String path, String form) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(baseUrl + path))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .header("Accept", "text/html")
                        .POST(HttpRequest.BodyPublishers.ofString(form))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static final String HTML = "text/html,application/xhtml+xml,*/*;q=0.8";

    // --- the four properties -----------------------------------------------

    @Test
    void legacyScreenRequestsAreLoggedAndServed() throws Exception {
        HttpClient anonymous = browser();

        HttpResponse<String> servlet = get(anonymous, CONTEXT + "/ViewStudy?id=1", HTML);
        HttpResponse<String> pagesRoute = get(anonymous, CONTEXT + "/pages/viewAllSubjectSDVtmp?studyId=1", HTML);
        HttpResponse<String> api = get(anonymous, CONTEXT + "/pages/api/v1/me", "application/json");

        assertEquals(200, servlet.statusCode());
        assertTrue(servlet.body().contains("servletPath=/ViewStudy pathInfo=null dispatcher=REQUEST"), servlet.body());
        assertEquals(200, pagesRoute.statusCode());
        assertEquals(200, api.statusCode());
        assertEquals(List.of(
                "legacy-hit path=/ViewStudy bucket=STUDY_ADMIN_AND_BUILD spaRoute=none method=GET"
                        + " user=anonymous alias=false action=pass",
                "legacy-hit path=/pages/viewAllSubjectSDVtmp bucket=SOURCE_DATA_VERIFICATION spaRoute=/app/sdv"
                        + " method=GET user=anonymous alias=false action=pass"), hits());
    }

    @Test
    void closedScreenIsGoneForANonAdministrator() throws Exception {
        HttpClient dataManager = signedIn("user");

        HttpResponse<String> page = get(dataManager, CONTEXT + "/ListUserAccounts", HTML);
        HttpResponse<String> json = get(dataManager, CONTEXT + "/ListUserAccounts", "application/json");
        HttpResponse<String> post = postForm(dataManager, CONTEXT + "/pages/studymodule/S_DEFAULTS1/deactivate", "x=1");
        HttpResponse<String> anonymous = get(browser(), CONTEXT + "/ListUserAccounts", HTML);

        assertEquals(410, page.statusCode());
        assertTrue(page.body().contains("href=\"/LibreClinica/app/manage-users\""), page.body());
        assertFalse(page.body().contains("servletPath="), "the screen did not run");
        assertEquals(410, json.statusCode());
        assertTrue(json.body().contains("\"spaRoute\":\"/app/manage-users\""), json.body());
        assertEquals(410, post.statusCode());
        assertEquals(410, anonymous.statusCode());
        assertEquals(4, hits().size());
        assertTrue(hits().stream().allMatch(h -> h.endsWith("alias=false action=gone")), hits().toString());
        assertTrue(hits().get(0).contains("user=manual_dm"), hits().get(0));
    }

    @Test
    void administratorIsSentToTheAliasAndServedThere() throws Exception {
        HttpClient admin = signedIn("admin");

        HttpResponse<String> redirect = get(admin, CONTEXT + "/ListUserAccounts?module=admin", HTML);
        assertEquals(307, redirect.statusCode());
        String location = redirect.headers().firstValue("Location").orElseThrow();
        assertEquals("/LibreClinica/legacy/ListUserAccounts?module=admin", location);

        HttpResponse<String> served = get(admin, location, HTML);
        assertEquals(200, served.statusCode());
        assertTrue(served.body().contains(
                "servletPath=/ListUserAccounts pathInfo=null dispatcher=FORWARD method=GET query=module=admin"),
                served.body());

        // A form posted to a closed screen: the 307 is repeated as a POST with its body.
        HttpResponse<String> postRedirect = postForm(admin, CONTEXT + "/pages/studymodule/S_DEFAULTS1/deactivate",
                "reason=done&confirm=yes");
        assertEquals(307, postRedirect.statusCode());
        String postLocation = postRedirect.headers().firstValue("Location").orElseThrow();
        assertEquals("/LibreClinica/legacy/pages/studymodule/S_DEFAULTS1/deactivate", postLocation);
        HttpResponse<String> posted = postForm(admin, postLocation, "reason=done&confirm=yes");
        assertEquals(200, posted.statusCode());
        assertTrue(posted.body().contains("servletPath=/pages pathInfo=/studymodule/S_DEFAULTS1/deactivate"
                + " dispatcher=FORWARD method=POST"), posted.body());
        assertTrue(posted.body().endsWith("body=reason=done&confirm=yes"), posted.body());

        assertEquals(List.of(
                "legacy-hit path=/ListUserAccounts bucket=USER_ACCOUNTS spaRoute=/app/manage-users method=GET"
                        + " user=root alias=false action=redirect",
                "legacy-hit path=/ListUserAccounts bucket=USER_ACCOUNTS spaRoute=/app/manage-users method=GET"
                        + " user=root alias=true action=forward",
                "legacy-hit path=/pages/studymodule bucket=STUDY_ADMIN_AND_BUILD spaRoute=/app/build-study"
                        + " method=POST user=root alias=false action=redirect",
                "legacy-hit path=/pages/studymodule bucket=STUDY_ADMIN_AND_BUILD spaRoute=/app/build-study"
                        + " method=POST user=root alias=true action=forward"), hits());
    }

    @Test
    void relativeLinksFromAnAliasedPageStayServedUnderTheAlias() throws Exception {
        HttpClient admin = signedIn("admin");

        // From /legacy/ListUserAccounts the page's links resolve under /legacy/:
        // an open screen, an image, and nothing outside the legacy surface.
        HttpResponse<String> openScreen = get(admin, CONTEXT + "/legacy/ViewUserAccount?userId=1", HTML);
        HttpResponse<String> image = get(admin, CONTEXT + "/legacy/images/probe.gif", "image/*");
        HttpResponse<String> webInf = get(admin, CONTEXT + "/legacy/WEB-INF/secret.txt", HTML);

        assertEquals(200, openScreen.statusCode());
        assertTrue(openScreen.body().contains("servletPath=/ViewUserAccount pathInfo=null dispatcher=FORWARD"),
                openScreen.body());
        assertEquals(200, image.statusCode());
        assertEquals("GIF-PROBE", image.body());
        assertEquals(404, webInf.statusCode());
        assertFalse(webInf.body().contains("WEB-INF-CONTENT"));
    }

    @Test
    void nonAdministratorIsRefusedOnTheAlias() throws Exception {
        HttpClient dataManager = signedIn("user");

        HttpResponse<String> closed = get(dataManager, CONTEXT + "/legacy/ListUserAccounts", HTML);
        HttpResponse<String> open = get(dataManager, CONTEXT + "/legacy/ViewUserAccount", HTML);
        HttpResponse<String> image = get(dataManager, CONTEXT + "/legacy/images/probe.gif", "image/*");
        HttpResponse<String> anonymous = get(browser(), CONTEXT + "/legacy/ListUserAccounts", HTML);

        for (HttpResponse<String> resp : List.of(closed, open, image, anonymous)) {
            assertEquals(404, resp.statusCode(), resp.uri().toString());
            assertFalse(resp.body().contains("servletPath="), resp.uri().toString());
            assertFalse(resp.body().contains("GIF-PROBE"), resp.uri().toString());
        }
        assertEquals(List.of(
                "legacy-hit path=/ListUserAccounts bucket=USER_ACCOUNTS spaRoute=/app/manage-users method=GET"
                        + " user=manual_dm alias=true action=refuse",
                "legacy-hit path=/ViewUserAccount bucket=USER_ACCOUNTS spaRoute=/app/manage-users method=GET"
                        + " user=manual_dm alias=true action=refuse",
                "legacy-hit path=/ListUserAccounts bucket=USER_ACCOUNTS spaRoute=/app/manage-users method=GET"
                        + " user=anonymous alias=true action=refuse"), hits());
    }

    // --- stand-ins ---------------------------------------------------------

    /** Answers with how it was reached; stands in for a legacy servlet or the pages dispatcher. */
    static final class ScreenStandIn extends HttpServlet {
        private static final long serialVersionUID = 1L;

        @Override
        protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            String body = new String(req.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            resp.setContentType("text/plain;charset=UTF-8");
            resp.getWriter().write("screen servletPath=" + req.getServletPath() + " pathInfo=" + req.getPathInfo()
                    + " dispatcher=" + req.getDispatcherType() + " method=" + req.getMethod()
                    + " query=" + req.getQueryString() + " body=" + body);
        }
    }

    /** Puts a user into the session, as the login filter does at sign-in. */
    static final class SignIn extends HttpServlet {
        private static final long serialVersionUID = 1L;

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) {
            boolean admin = "admin".equals(req.getParameter("as"));
            UserAccountBean user = new UserAccountBean();
            user.setId(admin ? 1 : 7);
            user.setName(admin ? "root" : "manual_dm");
            user.addUserType(admin ? UserType.SYSADMIN : UserType.USER);
            req.getSession(true).setAttribute("userBean", user);
            resp.setStatus(HttpServletResponse.SC_NO_CONTENT);
        }
    }
}
