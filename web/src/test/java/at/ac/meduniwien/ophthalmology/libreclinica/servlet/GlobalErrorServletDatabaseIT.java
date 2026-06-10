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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import javax.sql.DataSource;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.AbstractApiControllerDatabaseIT;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.GenericWebApplicationContext;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;

/**
 * Phase A2 (2026-06-10) — pins the global error-page dispatcher.
 *
 * <p>Three test cases mirror the brief's verification matrix:
 * <ol>
 *   <li>{@link #jsonRequestReturnsJsonBody()} — when the inbound
 *       request advertises {@code Accept: application/json}, the
 *       servlet emits a {@code { message, reqId }} JSON payload
 *       with HTTP 500 (mirrors the SPA's {@code ApiError} wire
 *       contract).</li>
 *   <li>{@link #htmlRequestForwardsToGermanJsp()} — when the
 *       inbound request advertises {@code Accept: text/html}, the
 *       servlet forwards to {@code /WEB-INF/jsp/error-page.jsp}
 *       (a German-localized minimal page). We assert on the
 *       forward target rather than rendering the JSP because the
 *       lightweight {@link MockServletContext} doesn't run the
 *       Jasper compiler — checking the forward path is the
 *       deterministic equivalent that doesn't pull in a full
 *       Tomcat container.</li>
 *   <li>{@link #auditRowWritten()} — after a dispatch, exactly one
 *       {@code audit_log_event} row exists with
 *       {@code audit_log_event_type_id=61} (OPERATION_FAILED)
 *       carrying the simulated origin URI in its
 *       {@code entity_name} column.</li>
 * </ol>
 *
 * <p><strong>Why MockMvc isn't a fit here.</strong> Spring MVC's
 * {@code MockMvc} drives {@code @RestController} handlers via the
 * dispatcher, not the raw {@code HttpServlet#service} method.
 * {@code GlobalErrorServlet} is a bare jakarta servlet declared in
 * {@code web.xml}, not a Spring MVC controller; invoking
 * {@code service(req, resp)} directly with a
 * {@link MockHttpServletRequest} that pre-populates the standard
 * error-dispatch attributes (the very attributes Tomcat sets when
 * it dispatches to {@code <error-page>}) is the closest faithful
 * harness without spinning up a real container.
 *
 * <p>The test extends {@link AbstractApiControllerDatabaseIT} to
 * inherit the Testcontainers Postgres bootstrap + Liquibase
 * migration — the audit-row assertion needs the
 * {@code audit_log_event} table + the OPERATION_FAILED lookup row
 * the A1 changeset seeds.
 */
class GlobalErrorServletDatabaseIT extends AbstractApiControllerDatabaseIT {

    /* ====================================================================== */
    /* Helpers                                                                */
    /* ====================================================================== */

    /**
     * Build a {@link MockServletContext} whose {@link WebApplicationContext}
     * binding exposes the IT's live {@link DataSource} as a
     * {@code dataSource} bean. The servlet's lookup is identical to the
     * runtime production path
     * ({@code WebApplicationContextUtils#getWebApplicationContext}); we
     * just give it a context to find.
     */
    private static MockServletContext servletContextWithDataSource(DataSource ds) {
        MockServletContext sc = new MockServletContext();
        GenericWebApplicationContext wac = new GenericWebApplicationContext();
        wac.getBeanFactory().registerSingleton("dataSource", ds);
        wac.setServletContext(sc);
        wac.refresh();
        sc.setAttribute(
                WebApplicationContext.ROOT_WEB_APPLICATION_CONTEXT_ATTRIBUTE,
                wac);
        return sc;
    }

    /**
     * Build an error-dispatch request — populates the same attributes
     * Tomcat sets when it forwards to an {@code <error-page>} location.
     * Accepts a marker string that gets baked into the simulated
     * exception's message + the origin URI so per-test assertions can
     * scope to their own row.
     */
    private static MockHttpServletRequest errorDispatchRequest(MockServletContext sc,
                                                               String accept,
                                                               String marker,
                                                               int userId) {
        MockHttpServletRequest req = new MockHttpServletRequest(sc);
        req.setMethod("GET");
        req.setRequestURI("/error");
        if (accept != null) {
            req.addHeader("Accept", accept);
        }
        req.setAttribute(RequestDispatcher.ERROR_EXCEPTION,
                new RuntimeException("simulated " + marker));
        req.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 500);
        req.setAttribute(RequestDispatcher.ERROR_REQUEST_URI,
                "/LegacyServlet?marker=" + marker);
        if (userId > 0) {
            MockHttpSession session = new MockHttpSession(sc);
            UserAccountBean ub = new UserAccountBean();
            ub.setId(userId);
            ub.setName("root");
            session.setAttribute("userBean", ub);
            req.setSession(session);
        }
        return req;
    }

    /**
     * Count OPERATION_FAILED audit rows containing the given marker in
     * {@code new_value}. The DAO pipe-encodes
     * {@code errorClass|errorMessage|reqId} into {@code new_value}, so a
     * unique marker string baked into the simulated exception's message
     * scopes each assertion to its own test.
     */
    private static int countFailureRowsWithMarker(String marker) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM audit_log_event "
                             + "WHERE audit_log_event_type_id = 61 "
                             + "AND new_value LIKE ?")) {
            ps.setString(1, "%" + marker + "%");
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static void invokeService(GlobalErrorServlet servlet,
                                      MockHttpServletRequest req,
                                      MockHttpServletResponse resp)
            throws ServletException, IOException {
        servlet.service(req, resp);
    }

    /* ====================================================================== */
    /* Case 1 — JSON request                                                  */
    /* ====================================================================== */

    @Test
    void jsonRequestReturnsJsonBody() throws Exception {
        String marker = "A2-JSON-" + System.nanoTime();
        MockServletContext sc = servletContextWithDataSource(DATA_SOURCE);
        GlobalErrorServlet servlet = new GlobalErrorServlet();
        servlet.init(new org.springframework.mock.web.MockServletConfig(sc));

        MockHttpServletRequest req = errorDispatchRequest(
                sc, "application/json", marker, /* userId */ 1);
        MockHttpServletResponse resp = new MockHttpServletResponse();

        MDC.put("reqId", "test-reqId-json");
        try {
            invokeService(servlet, req, resp);
        } finally {
            MDC.remove("reqId");
        }

        Assertions.assertEquals(500, resp.getStatus(),
                "Expected HTTP 500");
        String contentType = resp.getContentType();
        Assertions.assertNotNull(contentType, "Expected a Content-Type header");
        Assertions.assertTrue(contentType.startsWith("application/json"),
                "Expected JSON Content-Type; got " + contentType);

        String body = resp.getContentAsString();
        Assertions.assertTrue(body.contains("\"message\":\"Internal server error.\""),
                "Expected the SPA-compatible message field; got: " + body);
        Assertions.assertTrue(body.contains("\"reqId\":\"test-reqId-json\""),
                "Expected the populated reqId field; got: " + body);

        // Audit-write was also issued by this dispatch.
        Assertions.assertEquals(1, countFailureRowsWithMarker(marker),
                "Expected one OPERATION_FAILED row for marker " + marker);
    }

    /* ====================================================================== */
    /* Case 2 — HTML request                                                  */
    /* ====================================================================== */

    @Test
    void htmlRequestForwardsToGermanJsp() throws Exception {
        String marker = "A2-HTML-" + System.nanoTime();
        MockServletContext sc = servletContextWithDataSource(DATA_SOURCE);
        GlobalErrorServlet servlet = new GlobalErrorServlet();
        servlet.init(new org.springframework.mock.web.MockServletConfig(sc));

        MockHttpServletRequest req = errorDispatchRequest(
                sc, "text/html,application/xhtml+xml", marker, /* userId */ 1);
        MockHttpServletResponse resp = new MockHttpServletResponse();

        MDC.put("reqId", "test-reqId-html");
        try {
            invokeService(servlet, req, resp);
        } finally {
            MDC.remove("reqId");
        }

        Assertions.assertEquals(500, resp.getStatus(),
                "Expected HTTP 500");
        Assertions.assertTrue(resp.getContentType() != null
                        && resp.getContentType().startsWith("text/html"),
                "Expected text/html Content-Type; got " + resp.getContentType());

        // MockServletContext doesn't render JSPs; we assert on the
        // forward target instead. That + the reqId attribute being
        // pre-set on the request is the contract — the JSP renders
        // ${reqId} verbatim.
        Assertions.assertEquals(GlobalErrorServlet.ERROR_JSP,
                resp.getForwardedUrl(),
                "Expected the German error-page.jsp to receive the forward");
        Assertions.assertEquals("test-reqId-html",
                req.getAttribute(GlobalErrorServlet.ATTR_REQ_ID),
                "Expected the reqId attribute to be staged for the JSP");

        // Audit-write was also issued.
        Assertions.assertEquals(1, countFailureRowsWithMarker(marker),
                "Expected one OPERATION_FAILED row for marker " + marker);
    }

    /* ====================================================================== */
    /* Case 3 — audit row carries the simulated request URI                   */
    /* ====================================================================== */

    @Test
    void auditRowWritten() throws Exception {
        String marker = "A2-AUDIT-" + System.nanoTime();
        String origUri = "/LegacyServlet?marker=" + marker;
        MockServletContext sc = servletContextWithDataSource(DATA_SOURCE);
        GlobalErrorServlet servlet = new GlobalErrorServlet();
        servlet.init(new org.springframework.mock.web.MockServletConfig(sc));

        MockHttpServletRequest req = errorDispatchRequest(
                sc, "application/json", marker, /* userId */ 1);
        MockHttpServletResponse resp = new MockHttpServletResponse();

        MDC.put("reqId", "test-reqId-audit");
        try {
            invokeService(servlet, req, resp);
        } finally {
            MDC.remove("reqId");
        }

        // Exactly one row + the row's payload carries the expected
        // actor + audit_table + entity_name (origUri) + reqId.
        Assertions.assertEquals(1, countFailureRowsWithMarker(marker),
                "Expected exactly one OPERATION_FAILED row for marker " + marker);
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT user_id, audit_table, entity_name, new_value "
                             + "FROM audit_log_event "
                             + "WHERE audit_log_event_type_id = 61 "
                             + "AND new_value LIKE ?")) {
            ps.setString(1, "%" + marker + "%");
            try (ResultSet rs = ps.executeQuery()) {
                Assertions.assertTrue(rs.next(), "Row should exist");
                Assertions.assertEquals(1, rs.getInt("user_id"));
                Assertions.assertEquals("legacy_servlet", rs.getString("audit_table"));
                Assertions.assertEquals(origUri, rs.getString("entity_name"),
                        "entity_name should carry the simulated origin URI");
                String newValue = rs.getString("new_value");
                Assertions.assertTrue(newValue.startsWith("java.lang.RuntimeException|"),
                        "new_value should start with throwable class FQN; got: " + newValue);
                Assertions.assertTrue(newValue.endsWith("|test-reqId-audit"),
                        "new_value should end with reqId; got: " + newValue);
            }
        }
    }
}
