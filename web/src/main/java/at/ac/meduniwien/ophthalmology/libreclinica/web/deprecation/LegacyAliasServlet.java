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

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.web.deprecation.LegacyAccessLog.Action;
import at.ac.meduniwien.ophthalmology.libreclinica.web.deprecation.LegacyServletDeprecationCatalog.Entry;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * The administrators' way back into a closed legacy screen: DR-018 point 2.
 * {@code /legacy/<path>} renders the legacy screen at {@code <path>} for a
 * system administrator, and answers {@code 404 Not Found} to everyone else.
 *
 * <p>Mapped at {@code /legacy/*} ({@code ServletInfraConfig}). Being a
 * servlet, it runs after the whole filter chain, Spring Security included:
 * an unauthenticated request never gets here, because
 * {@code anyRequest().hasRole("USER")} in {@code SecurityConfig} sends it to
 * the login page like any other non-public URL. That is why
 * {@code /legacy/**} needs no entry of its own in {@code SecurityConfig}.
 *
 * <h2>Behaviour</h2>
 *
 * <ul>
 *   <li><strong>A legacy screen</strong> — any key of
 *       {@link LegacyServletDeprecationCatalog}, closed or not — requested by
 *       a system administrator ({@link UserAccountBean#isSysAdmin()}) is
 *       forwarded to its original path, method, parameters and body
 *       unchanged, and logged ({@code alias=true action=forward}).</li>
 *   <li>The same request by anyone else gets {@code 404} and is logged
 *       ({@code action=refuse}). A 404 rather than a 403: to a non-administrator
 *       the alias does not exist, and the answer is the one any unknown URL
 *       gets.</li>
 *   <li><strong>Static assets</strong> under {@code /images/} and
 *       {@code /includes/} (never a JSP) are forwarded for an administrator
 *       too, without a log line.</li>
 *   <li>Anything else, {@code /WEB-INF/…} and the APIs included, is
 *       {@code 404} for everyone.</li>
 * </ul>
 *
 * <h2>Relative links</h2>
 *
 * <p>The legacy pages link relatively ({@code ViewStudySubject?id=1},
 * {@code images/bt_View.gif}, {@code includes/styles.css}). A page shown at
 * {@code /legacy/X} therefore resolves them under {@code /legacy/}, which is
 * why the alias serves every legacy screen and those two asset directories,
 * not only the closed screens: an administrator can work through the alias
 * without being dropped out of it. A link that does leave it (an absolute URL,
 * or {@code ../}) reaches the original path, which serves an open screen
 * directly and sends the administrator back under {@code /legacy/} for a
 * closed one.
 *
 * <p>The forward is an internal {@code FORWARD} dispatch, which the
 * retirement filter, registered for {@code REQUEST} only, does not see. Spring
 * Security is registered for every dispatcher type and authorises the
 * forwarded path as it would a direct request.
 */
public class LegacyAliasServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    /** Servlet mapping of the alias. */
    public static final String URL_PATTERN = "/legacy/*";

    private final transient LegacyServletDeprecationCatalog catalog;

    public LegacyAliasServlet(LegacyServletDeprecationCatalog catalog) {
        this.catalog = catalog;
    }

    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String path = req.getPathInfo();
        if (!isPlainPath(path)) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        Entry entry = catalog.lookup(path).orElse(null);
        String target = entry != null ? screenTarget(entry, path) : assetTarget(path);
        if (target == null) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        UserAccountBean user = LegacyAccessLog.sessionUser(req);
        boolean admin = LegacyAccessLog.isSysAdmin(user);
        if (entry != null) {
            LegacyAccessLog.hit(entry, req, user, true, admin ? Action.FORWARD : Action.REFUSE);
        }
        if (!admin) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        RequestDispatcher dispatcher = req.getRequestDispatcher(target);
        if (dispatcher == null) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        dispatcher.forward(req, resp);
    }

    /**
     * A path of letters, digits, {@code _ - . /} only, with no empty or
     * {@code ..} segment. The container has already decoded and normalised
     * it; this refuses whatever else could reach a dispatcher as a query
     * ({@code ?}), a path parameter ({@code ;}) or an escape.
     */
    static boolean isPlainPath(String path) {
        if (path == null || path.length() < 2 || path.charAt(0) != '/') {
            return false;
        }
        if (path.contains("..") || path.contains("//")) {
            return false;
        }
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '-' || c == '.' || c == '/';
            if (!allowed) {
                return false;
            }
        }
        return true;
    }

    /**
     * The forward target for a legacy screen: the servlet's own path, or the
     * requested path below {@code /pages} for a Spring MVC route (whose key
     * also covers the paths under it).
     */
    static String screenTarget(Entry entry, String path) {
        if (!entry.isPagesRoute()) {
            return entry.legacyPath();
        }
        return "/pages/" + path.substring("/pages/".length());
    }

    /** The forward target for a static asset the legacy pages link relatively, or {@code null}. */
    static String assetTarget(String path) {
        if (path.endsWith(".jsp") || path.endsWith(".jspx")) {
            return null;
        }
        if (path.startsWith("/images/") && path.length() > "/images/".length()) {
            return "/images/" + path.substring("/images/".length());
        }
        if (path.startsWith("/includes/") && path.length() > "/includes/".length()) {
            return "/includes/" + path.substring("/includes/".length());
        }
        return null;
    }
}
