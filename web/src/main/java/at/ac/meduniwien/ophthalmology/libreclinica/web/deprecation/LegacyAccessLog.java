/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.web.deprecation;

import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.web.deprecation.LegacyServletDeprecationCatalog.Entry;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

/**
 * The {@code legacy-access} log, and the session lookup that the retirement
 * filter and the {@code /legacy/} alias share.
 *
 * <p>One line per request for a legacy screen:
 *
 * <pre>
 * legacy-hit path=/ListUserAccounts bucket=USER_ACCOUNTS spaRoute=/app/manage-users method=GET user=root alias=false action=pass
 * </pre>
 *
 * <p>{@code path} is the catalogue key, never the raw request path, and no
 * query string is logged, so the line carries no identifiers from the URL.
 * {@code user} is the account in the session, or {@code anonymous}. The logger
 * is not under the application's package, so it goes to the root logger's
 * appenders: the console ({@code docker logs}) and the JSON feed.
 */
final class LegacyAccessLog {

    /** Name of the logger every line goes to. */
    static final String LOGGER_NAME = "legacy-access";

    /**
     * Session attribute that holds the signed-in user: set by the login
     * filter, and by {@code SecureController} and {@code SetUpUserInterceptor}
     * when a session reaches them without it.
     */
    static final String SESSION_USER = "userBean";

    private static final Logger LOG = LoggerFactory.getLogger(LOGGER_NAME);

    /** What was done with a request for a legacy screen. */
    enum Action {
        /** Not closed: passed on to the screen. */
        PASS,
        /** Closed: answered {@code 410 Gone}. */
        GONE,
        /** Closed, and the user is a system administrator: {@code 307} to the alias. */
        REDIRECT,
        /** Through the alias, by a system administrator: forwarded to the screen. */
        FORWARD,
        /** Through the alias, by anyone else: answered {@code 404}. */
        REFUSE;

        String label() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private LegacyAccessLog() {
    }

    static Logger logger() {
        return LOG;
    }

    static void hit(Entry entry, HttpServletRequest request, UserAccountBean user, boolean alias, Action action) {
        LOG.info("legacy-hit path={} bucket={} spaRoute={} method={} user={} alias={} action={}",
                entry.legacyPath(), entry.bucket(), entry.hasSpaRoute() ? entry.spaRoute() : "none",
                method(request), user == null ? "anonymous" : user.getName(), alias, action.label());
    }

    /**
     * The signed-in user from the session, or {@code null}. Reads the session
     * only; it never creates one, so an anonymous request stays sessionless.
     * The placeholder bean {@code SetUpUserInterceptor} stores for anonymous
     * requests has id 0 and counts as no user.
     */
    static UserAccountBean sessionUser(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return null;
        }
        Object user = session.getAttribute(SESSION_USER);
        return user instanceof UserAccountBean account && account.getId() > 0 ? account : null;
    }

    static boolean isSysAdmin(UserAccountBean user) {
        return user != null && user.isSysAdmin();
    }

    /** The request method, from a fixed set: the client's own string never reaches the log. */
    static String method(HttpServletRequest request) {
        String method = request.getMethod();
        if (method == null) {
            return "OTHER";
        }
        switch (method) {
            case "GET":
                return "GET";
            case "POST":
                return "POST";
            case "HEAD":
                return "HEAD";
            case "PUT":
                return "PUT";
            case "DELETE":
                return "DELETE";
            case "PATCH":
                return "PATCH";
            case "OPTIONS":
                return "OPTIONS";
            default:
                return "OTHER";
        }
    }
}
