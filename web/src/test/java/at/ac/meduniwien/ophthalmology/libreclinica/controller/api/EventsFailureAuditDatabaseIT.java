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
 * Phase B2 (2026-06-10) — pins {@link EventsApiController}'s three
 * failure-audit retrofits ({@code CREATE_EVENT}, {@code UPDATE_EVENT},
 * {@code DELETE_EVENT}).
 *
 * <p>One test runs all three operation labels through
 * {@link FailureAuditTemplate#runOrAudit} with the controller's exact
 * entityType and reqId-shape contract; asserts one OPERATION_FAILED
 * row per call. See {@link EventCrfsFailureAuditDatabaseIT} for the rationale.
 */
class EventsFailureAuditDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static AuditEventDAO dao() {
        return new AuditEventDAO(DATA_SOURCE);
    }

    private static int countFailureRows(String operation, String marker) throws SQLException {
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
    void eventMutationsFailuresWriteAuditRows() throws Exception {
        String createMarker = "B2-CREATE-EVENT-" + System.nanoTime();
        String updateMarker = "B2-UPDATE-EVENT-" + System.nanoTime();
        String deleteMarker = "B2-DELETE-EVENT-" + System.nanoTime();
        String reqId = "test-req-B2-events";

        // CREATE — entityId null at entry (the row doesn't exist yet).
        try {
            FailureAuditTemplate.runOrAudit(
                    dao(), 1, "study_event", null, "CREATE_EVENT", reqId,
                    () -> { throw new SQLException("simulated " + createMarker); });
        } catch (SQLException ignored) {}
        Assertions.assertEquals(1, countFailureRows("CREATE_EVENT", createMarker));

        // Verify the entity_id NULL contract for create-path failures.
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT entity_id FROM audit_log_event "
                             + "WHERE audit_log_event_type_id = 61 "
                             + "AND entity_name = 'CREATE_EVENT' "
                             + "AND new_value LIKE ?")) {
            ps.setString(1, "%" + createMarker + "%");
            try (ResultSet rs = ps.executeQuery()) {
                Assertions.assertTrue(rs.next());
                rs.getInt("entity_id");
                Assertions.assertTrue(rs.wasNull(),
                        "CREATE_EVENT failure entity_id should be SQL NULL");
            }
        }

        // UPDATE — entityId is the study_event being updated.
        try {
            FailureAuditTemplate.runOrAudit(
                    dao(), 1, "study_event", 7777, "UPDATE_EVENT", reqId,
                    () -> { throw new RuntimeException("simulated " + updateMarker); });
        } catch (RuntimeException ignored) {}
        Assertions.assertEquals(1, countFailureRows("UPDATE_EVENT", updateMarker));

        // DELETE — entityId is the study_event being cancelled.
        try {
            FailureAuditTemplate.runOrAudit(
                    dao(), 1, "study_event", 8888, "DELETE_EVENT", reqId,
                    () -> { throw new IllegalArgumentException("simulated " + deleteMarker); });
        } catch (IllegalArgumentException ignored) {}
        Assertions.assertEquals(1, countFailureRows("DELETE_EVENT", deleteMarker));
    }
}
