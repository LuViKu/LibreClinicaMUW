/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.servlet;

import java.io.IOException;
import java.io.PrintWriter;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.ApplicationContext;
import org.springframework.web.context.support.WebApplicationContextUtils;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.admin.AuditEventDAO;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * Phase A2 (2026-06-10) — global error dispatcher backing the
 * {@code <error-page>} entries in {@code web.xml}.
 *
 * <p>Today the ~222 legacy servlets registered via
 * {@code LegacyServletRegistry} throw their failures into Tomcat's
 * default HTML 500 page, bypassing both the §11.10(e) audit trail
 * and the SPA's {@code { message }} JSON contract. This servlet
 * closes the bypass:
 *
 * <ol>
 *   <li><strong>Audit.</strong> Calls
 *       {@link AuditEventDAO#insertOperationFailure} (the REQUIRES_NEW-
 *       equivalent helper introduced in A1) tagging the failure with
 *       {@code entityType="legacy_servlet"}, the original request URI as
 *       {@code operation}, and the throwable class + message.</li>
 *   <li><strong>Content negotiation.</strong> JSON requests get the
 *       same shape {@code ApiExceptionHandler} produces (just
 *       {@code { message, reqId }}) so the SPA's existing
 *       {@code ApiError} parser handles error-page-served bodies
 *       identically. HTML requests forward to
 *       {@code /WEB-INF/jsp/error-page.jsp}, a self-contained
 *       German-localized minimal page.</li>
 * </ol>
 *
 * <p><strong>Why JSON has highest priority.</strong> The SPA's
 * {@code client.ts} sends every request with
 * {@code Accept: application/json}. A bare browser navigation has
 * {@code Accept: text/html, ...}. If the legacy chain hits an
 * unhandled exception inside a {@code /pages/api/v1/**} call, the
 * {@code ApiExceptionHandler} catches it first; this servlet only
 * fires when that advice was absent or when a request slipped
 * past Spring MVC entirely (raw servlet path, e.g. legacy
 * {@code /ListSubject}).
 *
 * <p><strong>Why a separate JSP.</strong> The existing
 * {@code /WEB-INF/jsp/error.jsp} is the legacy session-aware error
 * page wired to bundles + a sidebar; we want a self-contained
 * minimal copy that works even when the session is dead or the
 * configured locale bundle fails to resolve. The new
 * {@code error-page.jsp} therefore lives next to (not on top of)
 * the legacy one — bookmarks pointing at the legacy path keep
 * resolving, and the new error-page dispatcher always lands on a
 * page that can render without bundle access.
 *
 * <p><strong>Audit-failure swallowing.</strong> The audit insert is
 * wrapped in its own try/catch; a transient DB error during the
 * audit-write never blocks the response. Losing the user-facing
 * 500 response in favour of an audit cascade would mean the
 * operator sees "browser is hung" instead of "something went
 * wrong" — strictly worse for UX, and the original throwable is
 * already in the server log via SLF4J.
 */
public class GlobalErrorServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    private static final Logger LOG = LoggerFactory.getLogger(GlobalErrorServlet.class);

    /** German-localized error page forwarded to for HTML requests. */
    static final String ERROR_JSP = "/WEB-INF/jsp/error-page.jsp";

    /** Marker used by the JSP forward + the JSON branch alike. */
    static final String ATTR_REQ_ID = "reqId";

    /**
     * Standard Servlet 6 (jakarta) error-dispatch attribute names.
     * Mirrored as static finals so the IT can populate them by name
     * without depending on the {@link RequestDispatcher} constants
     * directly.
     */
    static final String ATTR_EXCEPTION = RequestDispatcher.ERROR_EXCEPTION;
    static final String ATTR_STATUS_CODE = RequestDispatcher.ERROR_STATUS_CODE;
    static final String ATTR_REQUEST_URI = RequestDispatcher.ERROR_REQUEST_URI;

    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {

        Throwable t = (Throwable) req.getAttribute(ATTR_EXCEPTION);
        Integer status = (Integer) req.getAttribute(ATTR_STATUS_CODE);
        String origUri = (String) req.getAttribute(ATTR_REQUEST_URI);

        // Pull the request-correlation id from MDC; A4 populates it
        // on inbound requests, A1's helper writes it into the audit
        // row's new_value triple. Until A4 lands the value is null
        // for direct requests; the IT can pre-seed MDC to exercise
        // the populated path.
        String reqId = MDC.get(ATTR_REQ_ID);

        // Audit-write — never blocks the response. The DAO uses
        // its own connection (REQUIRES_NEW equivalent — see
        // AuditEventDAO.insertOperationFailure) so we don't need
        // a Spring transaction here.
        writeAuditRow(req, t, origUri, reqId);

        int statusCode = (status != null) ? status : HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
        resp.setStatus(statusCode);

        if (prefersJson(req)) {
            writeJsonBody(resp, reqId);
        } else {
            // Surface the reqId on the request so the JSP can render it.
            req.setAttribute(ATTR_REQ_ID, reqId == null ? "" : reqId);
            forwardToJsp(req, resp);
        }
    }

    /* ====================================================================== */
    /* Internals                                                              */
    /* ====================================================================== */

    /**
     * Best-effort write of an OPERATION_FAILED audit row. Catches any
     * Throwable from the DAO + logs at ERROR; never propagates so the
     * outer response always reaches the client.
     */
    private void writeAuditRow(HttpServletRequest req,
                               Throwable t,
                               String origUri,
                               String reqId) {
        try {
            DataSource ds = resolveDataSource();
            if (ds == null) {
                LOG.warn("GlobalErrorServlet: no DataSource bean available; skipping audit row "
                        + "(reqId={}, origUri={})", reqId, origUri);
                return;
            }
            int userId = resolveUserId(req);
            String operation = origUri == null ? "(unknown)" : origUri;
            String errorClass = t == null ? "(no throwable)" : t.getClass().getName();
            String errorMessage = (t == null || t.getMessage() == null) ? "" : t.getMessage();

            new AuditEventDAO(ds).insertOperationFailure(
                    userId,
                    "legacy_servlet",
                    null,
                    operation,
                    errorClass,
                    errorMessage,
                    reqId);
        } catch (Throwable auditFailure) {
            LOG.error("GlobalErrorServlet: audit-write itself failed for origUri={}: {}",
                    origUri, auditFailure.getMessage(), auditFailure);
        }
    }

    /**
     * Resolve the application's primary DataSource bean. Mirrors the
     * lookup pattern used by {@code RestODMFilter} +
     * {@code ExportScheduleRegistrar} elsewhere in the legacy code
     * path. Returns null when the Spring context isn't available
     * (e.g. inside a {@code MockServletContext} that didn't bind a
     * WebApplicationContext) — the IT exercises that fallback as well.
     */
    private DataSource resolveDataSource() {
        try {
            ApplicationContext ctx =
                    WebApplicationContextUtils.getWebApplicationContext(getServletContext());
            if (ctx == null) {
                return null;
            }
            if (ctx.containsBean("dataSource")) {
                return ctx.getBean("dataSource", DataSource.class);
            }
            return ctx.getBean(DataSource.class);
        } catch (Throwable lookupFailure) {
            LOG.debug("GlobalErrorServlet: DataSource lookup failed: {}",
                    lookupFailure.getMessage(), lookupFailure);
            return null;
        }
    }

    /**
     * Pull the acting user id from the session's {@code userBean}
     * attribute, the same key the rest of the legacy servlet code
     * path uses. Returns 0 when unauthenticated or session-less —
     * matches the helper's contract documented on
     * {@link AuditEventDAO#insertOperationFailure}.
     */
    private static int resolveUserId(HttpServletRequest req) {
        HttpSession session = req.getSession(false);
        if (session == null) {
            return 0;
        }
        Object userBean = session.getAttribute("userBean");
        if (userBean instanceof UserAccountBean uab) {
            return uab.getId();
        }
        return 0;
    }

    /**
     * Decide JSON vs HTML branch. JSON wins when the
     * {@code Accept} header contains {@code application/json};
     * the SPA's {@code client.ts} sets that on every request and
     * never on a plain navigation, so the conservative substring
     * check matches behaviour without parsing the full media-range
     * grammar. HTML is the default fallback.
     */
    private static boolean prefersJson(HttpServletRequest req) {
        String accept = req.getHeader("Accept");
        if (accept == null) {
            return false;
        }
        return accept.toLowerCase().contains("application/json");
    }

    private static void writeJsonBody(HttpServletResponse resp, String reqId) throws IOException {
        resp.setContentType("application/json;charset=UTF-8");
        resp.setCharacterEncoding("UTF-8");
        String safeReqId = reqId == null ? "" : reqId;
        // Hand-assembled JSON keeps the servlet free of Jackson at
        // runtime — same body shape as ApiExceptionHandler.handleUnexpected.
        // Both fields are quoted strings; the only character we need to
        // escape in safeReqId is the double quote, since UUIDv4 + the
        // X-Request-Id grammar (RFC 9457) restrict the value otherwise.
        String escapedReqId = safeReqId.replace("\\", "\\\\").replace("\"", "\\\"");
        try (PrintWriter w = resp.getWriter()) {
            w.write("{\"message\":\"Internal server error.\",\"reqId\":\"" + escapedReqId + "\"}");
        }
    }

    private static void forwardToJsp(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        resp.setContentType("text/html;charset=UTF-8");
        resp.setCharacterEncoding("UTF-8");
        RequestDispatcher dispatcher = req.getRequestDispatcher(ERROR_JSP);
        if (dispatcher == null) {
            // Defensive: the JSP shipped alongside the servlet, but if a
            // packaging mishap drops it we still want to return a stable
            // German body rather than a Tomcat-default page that leaks
            // a stack trace.
            try (PrintWriter w = resp.getWriter()) {
                w.write("<!DOCTYPE html><html lang=\"de\"><head><meta charset=\"UTF-8\">"
                        + "<title>Ein Fehler ist aufgetreten</title></head>"
                        + "<body><h1>Ein Fehler ist aufgetreten</h1>"
                        + "<p>Bitte versuchen Sie es später erneut.</p></body></html>");
            }
            return;
        }
        dispatcher.forward(req, resp);
    }
}
