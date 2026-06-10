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
 * Phase B2 (2026-06-10) — pins {@link UsersApiController#grantRole}'s
 * retrofit. {@code entityType = "study_user_role"};
 * {@code entity_id} carries the target user's account id (the
 * study_user_role row's PK isn't known until after insert).
 *
 * <p>Access-control mutations are GxP-critical — a silent failure
 * here means a role grant request looked successful but didn't land,
 * or landed without the matching success audit row. The wrap closes
 * that hole; this test pins the contract.
 */
class UsersAssignRoleFailureAuditDatabaseIT extends AbstractApiControllerDatabaseIT {

    @Test
    void assignRoleFailureWritesAuditRow() throws Exception {
        String marker = "B2-ASSIGN-ROLE-" + System.nanoTime();
        String reqId = "test-req-B2-assign-role";
        AuditEventDAO dao = new AuditEventDAO(DATA_SOURCE);

        try {
            FailureAuditTemplate.runOrAudit(
                    dao, 1, "study_user_role", 1234, "ASSIGN_ROLE", reqId,
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
                             + "AND entity_name = 'ASSIGN_ROLE' "
                             + "AND new_value LIKE ?")) {
            ps.setString(1, "%" + marker + "%");
            try (ResultSet rs = ps.executeQuery()) {
                Assertions.assertTrue(rs.next(), "Expected OPERATION_FAILED row to exist");
                Assertions.assertEquals(1, rs.getInt("user_id"));
                Assertions.assertEquals("study_user_role", rs.getString("audit_table"));
                Assertions.assertEquals(1234, rs.getInt("entity_id"));
                Assertions.assertTrue(rs.getString("new_value").endsWith("|" + reqId));
                Assertions.assertFalse(rs.next(),
                        "Expected exactly one row for this marker");
            }
        }
    }
}
