/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import at.ac.meduniwien.ophthalmology.libreclinica.audit.FailureAuditTemplate;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.admin.AuditEventDAO;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Phase B2 (2026-06-10) — pins {@link EventCrfsApiController}'s two
 * failure-audit retrofits ({@code markComplete} + {@code ddeCommit}).
 *
 * <p>Follows the A1 unit-style pattern in
 * {@link FailureAuditDatabaseIT}: the controller's guard rails (session,
 * lock-state, visibility) make a clean controlled exception inside the
 * wrapped block hard to induce deterministically through MockMvc; the
 * load-bearing contract is the {@link FailureAuditTemplate#runOrAudit}
 * call shape — entityType, operation label, and reqId carry through to
 * the {@code audit_log_event} row exactly as the controller wrapper
 * declares them.
 *
 * <p>Each test wraps a body that throws synchronously then asserts a
 * single OPERATION_FAILED row carrying the controller's exact
 * (entityType, operation, reqId) triple.
 */
class EventCrfsFailureAuditDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static AuditEventDAO dao() {
        return new AuditEventDAO(DATA_SOURCE);
    }

    private static int countFailureRowsForOperationWithMarker(String operation, String marker)
            throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM audit_log_event "
                             + "WHERE audit_log_event_type_id = 61 "
                             + "AND entity_name = ? "
                             + "AND new_value LIKE ?")) {
            ps.setString(1, operation);
            ps.setString(2, "%" + marker + "%");
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    @Test
    void markCompleteFailureWritesAuditRow() throws Exception {
        String marker = "B2-MARK-COMPLETE-" + System.nanoTime();
        String reqId = "test-req-B2-mark-complete";

        Exception captured = null;
        try {
            FailureAuditTemplate.runOrAudit(
                    dao(),
                    /* userId */ 1,
                    "event_crf",
                    /* entityId */ 4242,
                    "MARK_COMPLETE",
                    reqId,
                    () -> { throw new SQLException("simulated " + marker); });
        } catch (Exception e) {
            captured = e;
        }
        Assertions.assertNotNull(captured, "runOrAudit must rethrow");

        Assertions.assertEquals(1,
                countFailureRowsForOperationWithMarker("MARK_COMPLETE", marker),
                "Expected exactly one OPERATION_FAILED row for MARK_COMPLETE with marker " + marker);

        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT user_id, audit_table, entity_id, new_value "
                             + "FROM audit_log_event "
                             + "WHERE audit_log_event_type_id = 61 "
                             + "AND entity_name = 'MARK_COMPLETE' "
                             + "AND new_value LIKE ?")) {
            ps.setString(1, "%" + marker + "%");
            try (ResultSet rs = ps.executeQuery()) {
                Assertions.assertTrue(rs.next());
                Assertions.assertEquals(1, rs.getInt("user_id"));
                Assertions.assertEquals("event_crf", rs.getString("audit_table"));
                Assertions.assertEquals(4242, rs.getInt("entity_id"));
                Assertions.assertTrue(rs.getString("new_value").endsWith("|" + reqId));
            }
        }
    }

    @Test
    void ddeCommitFailureWritesAuditRow() throws Exception {
        String marker = "B2-DDE-COMMIT-" + System.nanoTime();
        String reqId = "test-req-B2-dde-commit";

        try {
            FailureAuditTemplate.runOrAudit(
                    dao(), 1, "event_crf", 5151, "DDE_COMMIT", reqId,
                    () -> { throw new IllegalStateException("simulated " + marker); });
            Assertions.fail("runOrAudit should have rethrown");
        } catch (IllegalStateException expected) {
            // Contract: rethrow.
        }

        Assertions.assertEquals(1,
                countFailureRowsForOperationWithMarker("DDE_COMMIT", marker),
                "Expected exactly one OPERATION_FAILED row for DDE_COMMIT with marker " + marker);
    }
}
