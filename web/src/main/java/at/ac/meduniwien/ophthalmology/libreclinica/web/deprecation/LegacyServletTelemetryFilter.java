/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.web.deprecation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.web.util.HtmlUtils;
import org.springframework.web.util.UriUtils;

import com.fasterxml.jackson.databind.ObjectMapper;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.web.deprecation.LegacyAccessLog.Action;
import at.ac.meduniwien.ophthalmology.libreclinica.web.deprecation.LegacyServletDeprecationCatalog.Entry;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Legacy-retirement gate (DR-018; plan R0.3 and R0.4): logs every request for
 * a legacy screen, and closes the screens that configuration lists as closed.
 *
 * <h2>What it sees</h2>
 *
 * <p>Registered on {@code /*} for {@code REQUEST} dispatches
 * ({@code ServletInfraConfig}). For each request it looks the container's own
 * mapping — {@code getServletPath()} and {@code getPathInfo()}, which exclude
 * the context path — up in {@link LegacyServletDeprecationCatalog}: every
 * servlet registered by {@code LegacyServletRegistry} and every Spring MVC
 * route under {@code /pages} that renders a JSP. A request for anything else
 * costs one hash probe and passes untouched.
 *
 * <h2>What it does with a legacy request</h2>
 *
 * <ul>
 *   <li>It logs one {@code legacy-hit} line on the {@code legacy-access}
 *       logger (format in {@link LegacyAccessLog}).</li>
 *   <li><strong>Screen not closed:</strong> the request passes on
 *       ({@code action=pass}).</li>
 *   <li><strong>Screen closed, user a system administrator</strong>
 *       ({@link UserAccountBean#isSysAdmin()}, which tech admins also
 *       satisfy): {@code 307 Temporary Redirect} to the same path under
 *       {@code /legacy/}, query string included. A 307 makes the browser
 *       repeat the method and body, so a form posted to a closed screen
 *       arrives at the alias intact ({@code action=redirect}).
 *       {@link LegacyAliasServlet} serves it from there.</li>
 *   <li><strong>Screen closed, anyone else</strong>, signed in or not:
 *       {@code 410 Gone} ({@code action=gone}). The body is JSON when the
 *       {@code Accept} header asks for {@code application/json} (the SPA's and
 *       the legacy pages' XHRs), otherwise a minimal German HTML page, the
 *       convention of {@code error-page.jsp}. Both name the SPA route that
 *       replaces the screen, when the catalogue records one. The response is
 *       {@code no-store}, so reopening a screen takes effect at once.</li>
 * </ul>
 *
 * <h2>Configuration</h2>
 *
 * <p>{@code libreclinica.legacy.closedPaths} ({@code application.yml}, from
 * the environment variable {@code LIBRECLINICA_LEGACY_CLOSED_PATHS}): catalogue
 * keys, separated by commas or white space, e.g.
 * {@code /ListUserAccounts,/pages/studymodule}. Empty by default: nothing is
 * closed and everything is logged. A key closes that screen; a {@code /pages}
 * key also closes the paths below it. An entry that is not a catalogue key is
 * ignored and reported at startup at ERROR level on the {@code legacy-access}
 * logger, next to an INFO line listing what is closed. A typo therefore
 * leaves the screen open, visibly, instead of stopping the application.
 *
 * <p>The earlier switches are gone. {@code libreclinica.legacy.servletsEnabled}
 * closed every catalogued screen at once; it never took effect (the filter
 * never matched a request), and closing everything would now also close the
 * screens the SPA still calls ({@code GET /Logout}) or lands on after login
 * ({@code /MainMenu}). {@code libreclinica.legacy.banner} and
 * {@code libreclinica.legacy.sunsetDate} fed a banner in the SiteMesh
 * decorator, which has not run since SiteMesh left the build. Setting any of
 * the three has no effect.
 *
 * <h2>Where it sits</h2>
 *
 * <p>Second in the chain, after {@code RequestIdFilter} (so each line carries
 * the {@code reqId}) and ahead of Spring Security. Running ahead of security
 * is what lets an unauthenticated request for a closed screen get the 410
 * rather than a login page, and it is why the user comes from the session
 * attribute {@code userBean} directly: that is where the login filter puts
 * the signed-in user, and where the legacy servlets and SPA controllers read
 * it from. The gate never serves a page on that basis. It only answers 410,
 * redirects to the alias, or passes the request on to the security chain, and
 * the alias is behind that chain.
 *
 * <p>An SSO session that has not yet reached the SPA or a legacy page has no
 * {@code userBean}; until it does, the gate treats it as a non-administrator.
 *
 * <h2>Dispatcher types</h2>
 *
 * <p>{@code REQUEST} only. The alias reaches a closed screen by an internal
 * forward, which this filter therefore never sees, so the forward is not
 * closed a second time. For the same reason a closed screen is still reached
 * by a legacy page that forwards or includes it server-side: closing applies
 * to URLs a browser requests. Close a screen together with the open screens
 * that redirect or link the browser to it; otherwise their users meet the 410.
 */
public class LegacyServletTelemetryFilter implements Filter {

    /** Path prefix of the administrators' alias; see {@link LegacyAliasServlet}. */
    static final String ALIAS_PREFIX = "/legacy/";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final LegacyServletDeprecationCatalog catalog;
    private final Set<String> closedPaths;
    private final boolean internetFacing;

    /**
     * Internet-facing deployment ({@code libreclinica.deployment.internet-facing}):
     * external site staff use the SPA only, so every legacy screen is closed
     * for anyone but a system administrator, except what is listed here.
     *
     * <p>Servlets (catalogue keys): the SPA calls no legacy servlet. Its login
     * is {@code POST /j_spring_security_check} (a filter, not a servlet), its
     * logout is {@code POST /pages/api/v1/auth/logout}, and the active study
     * and password change go through {@code /pages/api/v1}. So no servlet is
     * left open, with the one conditional exception below.
     */
    public static final Set<String> INTERNET_FACING_OPEN_SERVLETS = Set.of();

    /**
     * Open only while the request has no signed-in user. {@code /MainMenu} is
     * where the concurrent-session filter sends a session that was replaced
     * by a second login, and where a legacy form login lands; it then sends an
     * anonymous caller on to the login page. For a signed-in non-administrator
     * it would render the legacy home page, so it is closed for them.
     */
    public static final Set<String> INTERNET_FACING_ANONYMOUS_ONLY = Set.of("/MainMenu");

    /**
     * Paths below {@code /pages} (the Spring MVC dispatcher) that stay open in
     * that mode; every other {@code /pages} path, catalogued or not, is
     * closed. A prefix matches itself and everything below it.
     */
    public static final Map<String, String> INTERNET_FACING_OPEN_PAGES = openPages();

    private static Map<String, String> openPages() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("/api/", "the SPA's JSON API (/pages/api/v1, v2) and the portals and device endpoints, which have their own gates");
        m.put("/login/", "the legacy login page: the login entry point and the target of a failed form login");
        m.put("/sso/reauth", "SSO re-authentication redirect; denied separately when SSO is off");
        m.put("/v3/", "springdoc spec; denied separately on this deployment by the path block");
        m.put("/swagger-ui", "swagger UI; denied separately on this deployment by the path block");
        return Collections.unmodifiableMap(m);
    }

    /**
     * @param catalog     the legacy screens
     * @param closedPaths catalogue keys to close; other entries are reported
     *                    and ignored
     */
    public LegacyServletTelemetryFilter(LegacyServletDeprecationCatalog catalog, Collection<String> closedPaths) {
        this(catalog, closedPaths, false);
    }

    /**
     * @param internetFacing close every catalogued screen except the open
     *                       lists above, and every other {@code /pages} path
     *                       outside {@link #INTERNET_FACING_OPEN_PAGES}, for
     *                       non-administrators
     */
    public LegacyServletTelemetryFilter(LegacyServletDeprecationCatalog catalog, Collection<String> closedPaths,
            boolean internetFacing) {
        this.catalog = catalog;
        this.internetFacing = internetFacing;
        Set<String> closed = new LinkedHashSet<>();
        if (internetFacing) {
            for (String key : catalog.all().keySet()) {
                if (!INTERNET_FACING_OPEN_SERVLETS.contains(key)) {
                    closed.add(key);
                }
            }
        }
        for (String path : closedPaths) {
            if (catalog.entry(path).isPresent()) {
                closed.add(path);
            } else {
                LegacyAccessLog.logger().error("legacy-retirement: libreclinica.legacy.closedPaths lists '{}',"
                        + " which is not a legacy screen in the catalogue; ignored", path);
            }
        }
        this.closedPaths = Set.copyOf(closed);
        LegacyAccessLog.logger().info("legacy-retirement: {} of {} legacy screens closed {}",
                closed.size(), catalog.all().size(), closed);
    }

    /**
     * Split the {@code libreclinica.legacy.closedPaths} value on commas and
     * white space.
     */
    public static List<String> parseClosedPaths(String value) {
        List<String> paths = new ArrayList<>();
        if (value == null) {
            return paths;
        }
        for (String token : value.split("[,\\s]+")) {
            if (!token.isEmpty()) {
                paths.add(token);
            }
        }
        return paths;
    }

    /** The closed catalogue keys, after validation. */
    public Set<String> closedPaths() {
        return closedPaths;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest httpReq) || !(response instanceof HttpServletResponse httpResp)) {
            chain.doFilter(request, response);
            return;
        }
        Entry entry = catalog.lookup(httpReq.getServletPath(), httpReq.getPathInfo()).orElse(null);
        // A /pages route the catalogue does not know, closed by the
        // internet-facing mode: no alias exists for it, so an administrator
        // is passed on instead of redirected.
        boolean uncatalogued = false;
        if (entry == null && internetFacing && isClosedUncataloguedPagesPath(httpReq)) {
            String info = httpReq.getPathInfo();
            entry = new Entry(LegacyServletDeprecationCatalog.PAGES_SERVLET_PATH + (info == null ? "" : info),
                    null, LegacyServletDeprecationCatalog.Bucket.HERITAGE_API);
            uncatalogued = true;
        }
        if (entry == null) {
            chain.doFilter(request, response);
            return;
        }
        UserAccountBean user = LegacyAccessLog.sessionUser(httpReq);
        boolean closed = uncatalogued || closedPaths.contains(entry.legacyPath());
        if (closed && internetFacing && user == null
                && INTERNET_FACING_ANONYMOUS_ONLY.contains(entry.legacyPath())) {
            closed = false;
        }
        if (!closed) {
            LegacyAccessLog.hit(entry, httpReq, user, false, Action.PASS);
            chain.doFilter(request, response);
            return;
        }
        if (uncatalogued && LegacyAccessLog.isSysAdmin(user)) {
            LegacyAccessLog.hit(entry, httpReq, user, false, Action.PASS);
            chain.doFilter(request, response);
            return;
        }
        if (LegacyAccessLog.isSysAdmin(user)) {
            LegacyAccessLog.hit(entry, httpReq, user, false, Action.REDIRECT);
            httpResp.setStatus(HttpServletResponse.SC_TEMPORARY_REDIRECT);
            httpResp.setHeader("Location", aliasLocation(httpReq));
            httpResp.setHeader("Cache-Control", "no-store");
            return;
        }
        LegacyAccessLog.hit(entry, httpReq, user, false, Action.GONE);
        gone(httpReq, httpResp, entry);
    }

    /** A request to the {@code /pages} dispatcher whose path is not on the open list. */
    private static boolean isClosedUncataloguedPagesPath(HttpServletRequest req) {
        if (!LegacyServletDeprecationCatalog.PAGES_SERVLET_PATH.equals(req.getServletPath())) {
            return false;
        }
        String info = req.getPathInfo();
        if (info == null || info.isEmpty() || "/".equals(info)) {
            return true;
        }
        for (String prefix : INTERNET_FACING_OPEN_PAGES.keySet()) {
            if (info.startsWith(prefix)) {
                return false;
            }
        }
        return true;
    }

    /**
     * The request's own path under the alias: always the context path plus
     * {@code /legacy/}, so the target stays on this origin and inside the
     * application. The path is re-encoded (the container decoded it); the
     * query string is passed on as received.
     */
    static String aliasLocation(HttpServletRequest req) {
        String pathInfo = req.getPathInfo();
        String path = req.getServletPath() + (pathInfo == null ? "" : pathInfo);
        String query = req.getQueryString();
        String suffix = query == null || query.isEmpty() ? "" : "?" + query.replaceAll("[\r\n]", "");
        return req.getContextPath() + ALIAS_PREFIX
                + UriUtils.encodePath(path.substring(1), StandardCharsets.UTF_8) + suffix;
    }

    @SuppressWarnings("resource") // the servlet container owns and closes the response stream/writer
    private static void gone(HttpServletRequest req, HttpServletResponse resp, Entry entry) throws IOException {
        resp.setStatus(HttpServletResponse.SC_GONE);
        resp.setHeader("Cache-Control", "no-store");
        resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
        if (prefersJson(req)) {
            resp.setContentType("application/json");
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("message", "This page has been retired.");
            body.put("legacyPath", entry.legacyPath());
            body.put("spaRoute", entry.spaRoute());
            body.put("bucket", entry.bucket().name());
            resp.getWriter().write(JSON.writeValueAsString(body));
        } else {
            resp.setContentType("text/html");
            resp.getWriter().write(goneHtml(req.getContextPath(), entry));
        }
    }

    /** Same rule as {@code GlobalErrorServlet}: JSON when asked for, HTML otherwise. */
    static boolean prefersJson(HttpServletRequest req) {
        String accept = req.getHeader("Accept");
        return accept != null && accept.toLowerCase(Locale.ROOT).contains("application/json");
    }

    static String goneHtml(String contextPath, Entry entry) {
        String base = HtmlUtils.htmlEscape(contextPath == null ? "" : contextPath);
        StringBuilder html = new StringBuilder(1400);
        html.append("<!DOCTYPE html><html lang=\"de\"><head><meta charset=\"UTF-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
                .append("<title>Seite abgeschaltet - LibreClinica</title><style>")
                .append("body{font-family:-apple-system,BlinkMacSystemFont,\"Segoe UI\",Roboto,sans-serif;")
                .append("margin:0;padding:3rem 1rem;background:#f5f5f5;color:#222}")
                .append(".card{max-width:32rem;margin:0 auto;padding:2rem;background:#fff;")
                .append("border-radius:.5rem;box-shadow:0 1px 3px rgba(0,0,0,.08)}")
                .append("h1{margin:0 0 1rem;font-size:1.5rem}p{line-height:1.5;margin:0 0 1rem}")
                .append("a.go{display:inline-block;margin-top:.5rem;padding:.5rem 1rem;background:#2d6cb3;")
                .append("color:#fff;text-decoration:none;border-radius:.25rem}")
                .append("</style></head><body><main class=\"card\">")
                .append("<h1>Diese Seite wurde abgeschaltet</h1>");
        if (entry.hasSpaRoute()) {
            html.append("<p>Diese Funktion finden Sie in der neuen Oberfläche.</p>")
                    .append("<a class=\"go\" href=\"").append(base)
                    .append(HtmlUtils.htmlEscape(linkTarget(entry.spaRoute())))
                    .append("\">Zur neuen Oberfläche</a>");
        } else {
            html.append("<p>Die neue Oberfläche bietet dafür noch keinen Ersatz.")
                    .append(" Bitte wenden Sie sich an den Systemadministrator.</p>")
                    .append("<a class=\"go\" href=\"").append(base).append("/app/\">Zur Startseite</a>");
        }
        return html.append("</main></body></html>").toString();
    }

    /**
     * A route with a parameter ({@code /app/subjects/:subjectId}) cannot be
     * followed as written; the link then goes to the SPA's start page.
     */
    static String linkTarget(String spaRoute) {
        return spaRoute.indexOf(':') >= 0 ? "/app/" : spaRoute;
    }
}
