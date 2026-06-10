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
 * Phase B2 (2026-06-10) — pins {@link DiscrepancyApiController}'s
 * parent-create retrofit. The thread-append endpoint
 * ({@code POST /{parentId}/thread}) is intentionally NOT in scope —
 * future B2 batches will retrofit it.
 *
 * <p>{@code entityType} is {@code discrepancy_note}; {@code entity_id}
 * carries the attached {@code item_data.id} since the note row's PK
 * isn't known until after insert.
 */
class DiscrepancyFailureAuditDatabaseIT extends AbstractApiControllerDatabaseIT {

    @Test
    void createDiscrepancyFailureWritesAuditRow() throws Exception {
        String marker = "B2-CREATE-DISCREPANCY-" + System.nanoTime();
        String reqId = "test-req-B2-discrepancy";
        AuditEventDAO dao = new AuditEventDAO(DATA_SOURCE);

        try {
            FailureAuditTemplate.runOrAudit(
                    dao, 1, "discrepancy_note", 9999, "CREATE_DISCREPANCY", reqId,
                    () -> { throw new SQLException("simulated " + marker); });
            Assertions.fail("runOrAudit should have rethrown");
        } catch (SQLException expected) {
            // Contract.
        }

        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT user_id, audit_table, entity_id, new_value "
                             + "FROM audit_log_event "
                             + "WHERE audit_log_event_type_id = 61 "
                             + "AND entity_name = 'CREATE_DISCREPANCY' "
                             + "AND new_value LIKE ?")) {
            ps.setString(1, "%" + marker + "%");
            try (ResultSet rs = ps.executeQuery()) {
                Assertions.assertTrue(rs.next(), "Expected OPERATION_FAILED row to exist");
                Assertions.assertEquals(1, rs.getInt("user_id"));
                Assertions.assertEquals("discrepancy_note", rs.getString("audit_table"));
                Assertions.assertEquals(9999, rs.getInt("entity_id"));
                Assertions.assertTrue(rs.getString("new_value").endsWith("|" + reqId),
                        "new_value should end with reqId");
                Assertions.assertFalse(rs.next(),
                        "Expected exactly one row for this marker");
            }
        }
    }
}
