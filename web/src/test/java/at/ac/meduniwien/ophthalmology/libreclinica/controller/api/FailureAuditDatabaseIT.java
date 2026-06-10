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
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.admin.AuditEventDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase A1 (2026-06-10) — pins the failure-audit foundation.
 *
 * <p>Three cases:
 * <ol>
 *   <li>{@code failureRowWritten} — a {@link FailureAuditTemplate#runOrAudit}
 *       wrap around a body that throws lands exactly one row in
 *       {@code audit_log_event} with {@code audit_log_event_type_id=61}
 *       (OPERATION_FAILED) tagged with the actor user + entity + operation
 *       label the caller supplied.</li>
 *   <li>{@code failureRowHiddenFromStudyView} — calling
 *       {@code GET /api/v1/audit} (the per-study investigator view)
 *       after the failure does NOT surface the OPERATION_FAILED row,
 *       because the SELECT joins {@code audit_log_event_type} and
 *       gates on {@code is_user_visible=true}.</li>
 *   <li>{@code failureRowSurvivesOuterRollback} — when a caller starts a
 *       JDBC transaction, writes a row, calls the audit DAO, then
 *       rolls back, the partial write is gone AND the OPERATION_FAILED
 *       row remains. Confirms the DAO uses its own connection +
 *       autoCommit=true, the REQUIRES_NEW equivalent without Spring
 *       transactions.</li>
 * </ol>
 *
 * <p>Why direct DAO + template tests vs invoking a controller's wrapped
 * path: the controllers' guard rails (validation, FOR UPDATE row locks,
 * 400 / 403 / 404 early returns) make a controlled exception inside the
 * wrapped block hard to induce deterministically without monkey-patching
 * the DataSource. The DAO + template are the load-bearing units; the
 * controllers' retrofits delegate to them, so pinning the units in
 * isolation gives us the §11.10(e) coverage the plan asks for without
 * a fragile "force the controller to throw on demand" harness.
 *
 * <p>The third test does exercise the rollback contract end-to-end:
 * an outer transaction with a real INSERT against {@code study_subject},
 * a synchronously triggered exception, and assertions on BOTH the
 * rolled-back row's absence AND the audit row's presence.
 */
class FailureAuditDatabaseIT extends AbstractApiControllerDatabaseIT {

    /* ====================================================================== */
    /* Helpers                                                                */
    /* ====================================================================== */

    private static AuditEventDAO dao() {
        return new AuditEventDAO(DATA_SOURCE);
    }

    /**
     * Count rows of {@code audit_log_event_type_id=61} (OPERATION_FAILED)
     * carrying the given marker text in {@code new_value}. Used so each
     * test can assert its own write in isolation without colliding with
     * sibling tests' audit rows.
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

    /* ====================================================================== */
    /* Case 1 — failure row is written                                        */
    /* ====================================================================== */

    @Test
    void failureRowWritten() throws Exception {
        // Trigger: any wrapped body that throws lands one audit row. We
        // pick a marker string that becomes part of the error message
        // so the assertion can scope to THIS test's row only.
        String marker = "A1-CASE1-MARKER-" + System.nanoTime();

        Exception captured = null;
        try {
            FailureAuditTemplate.runOrAudit(
                    dao(),
                    /* userId */ 1,
                    "study_subject",
                    /* entityId */ 42,
                    "FailureAuditDatabaseIT.case1",
                    /* reqId */ "test-req-A1-1",
                    () -> {
                        throw new SQLException("simulated " + marker);
                    });
        } catch (Exception e) {
            captured = e;
        }

        // The template must rethrow the original.
        Assertions.assertNotNull(captured, "Expected runOrAudit to rethrow");
        Assertions.assertTrue(captured instanceof SQLException,
                "Expected original SQLException; got " + captured.getClass());
        Assertions.assertTrue(captured.getMessage().contains(marker),
                "Expected original message intact");

        // Exactly one OPERATION_FAILED row exists for our marker.
        int count = countFailureRowsWithMarker(marker);
        Assertions.assertEquals(1, count,
                "Expected exactly one OPERATION_FAILED row for marker "
                        + marker + "; got " + count);

        // The row carries the expected actor + entity + operation triple.
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT user_id, audit_table, entity_id, entity_name, new_value "
                             + "FROM audit_log_event "
                             + "WHERE audit_log_event_type_id = 61 "
                             + "AND new_value LIKE ?")) {
            ps.setString(1, "%" + marker + "%");
            try (ResultSet rs = ps.executeQuery()) {
                Assertions.assertTrue(rs.next(), "Row should exist");
                Assertions.assertEquals(1, rs.getInt("user_id"));
                Assertions.assertEquals("study_subject", rs.getString("audit_table"));
                Assertions.assertEquals(42, rs.getInt("entity_id"));
                Assertions.assertEquals("FailureAuditDatabaseIT.case1",
                        rs.getString("entity_name"));
                String newValue = rs.getString("new_value");
                Assertions.assertTrue(newValue.startsWith("java.sql.SQLException|"),
                        "new_value should start with throwable class FQN; got: " + newValue);
                Assertions.assertTrue(newValue.endsWith("|test-req-A1-1"),
                        "new_value should end with reqId; got: " + newValue);
            }
        }
    }

    /* ====================================================================== */
    /* Case 2 — failure row is hidden from the per-study view                 */
    /* ====================================================================== */

    @Test
    void failureRowHiddenFromStudyView() throws Exception {
        // Step 1: write a failure-audit row tagged against study #1's
        // study_subject domain. The marker keeps the assertion local
        // to this test.
        String marker = "A1-CASE2-MARKER-" + System.nanoTime();
        try {
            FailureAuditTemplate.runOrAudit(
                    dao(),
                    /* userId */ 1,
                    "study_subject",
                    /* entityId — M-001 study_subject_id */ 1,
                    "FailureAuditDatabaseIT.case2",
                    "test-req-A1-2",
                    () -> {
                        throw new SQLException("simulated " + marker);
                    });
        } catch (SQLException expected) {
            // Template rethrew — that's the contract.
        }

        // Sanity check: the row exists in the table.
        Assertions.assertEquals(1, countFailureRowsWithMarker(marker),
                "Audit row should be present in audit_log_event");

        // Step 2: call the study-scoped audit-log endpoint. The
        // SiteVisibilityFilter constructor takes the live DataSource.
        AuditApiController controller = new AuditApiController(
                DATA_SOURCE, new SiteVisibilityFilter(DATA_SOURCE));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        MockHttpSession session = adminSessionForStudy(1);

        // Assert the response payload does NOT contain our failure
        // row's marker. The endpoint returns an array of AuditEventDto
        // — we scan for the marker as a substring of any 'details'
        // (the SPA's rendered new_value) field. The marker is unique
        // enough that any hit is conclusive.
        mockMvc.perform(get("/api/v1/audit").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[?(@.details =~ /.*" + marker + ".*/)]")
                        .doesNotExist());
    }

    /* ====================================================================== */
    /* Case 3 — failure row survives outer transaction rollback               */
    /* ====================================================================== */

    @Test
    void failureRowSurvivesOuterRollback() throws Exception {
        // Construction: open our own outer JDBC tx, do INSERT A (a
        // synthetic study_subject row), call the audit DAO, then
        // induce a controlled exception + roll back. Assert the
        // outer-tx row vanished AND the audit row stayed.
        String marker = "A1-CASE3-MARKER-" + System.nanoTime();
        // Use a label that won't collide with the seeded M-* labels;
        // also strip after the test.
        String synthLabel = "A1C3-" + System.nanoTime();
        int outerInsertedSsId = -1;

        try (Connection c = DATA_SOURCE.getConnection()) {
            c.setAutoCommit(false);
            try {
                // INSERT A — outer transaction's own write.
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO study_subject (label, subject_id, study_id, status_id, "
                                + "date_created, owner_id, oc_oid) "
                                + "VALUES (?, 1, 1, 1, NOW(), 1, ?) RETURNING study_subject_id")) {
                    ps.setString(1, synthLabel);
                    ps.setString(2, "SS_A1C3_" + System.nanoTime());
                    try (ResultSet rs = ps.executeQuery()) {
                        Assertions.assertTrue(rs.next());
                        outerInsertedSsId = rs.getInt(1);
                    }
                }

                // Audit DAO uses its own connection — survives our
                // rollback below. Mirrors what FailureAuditTemplate
                // does internally; calling the DAO directly here so
                // the rollback semantics are unambiguous.
                dao().insertOperationFailure(
                        1,
                        "study_subject",
                        outerInsertedSsId,
                        "FailureAuditDatabaseIT.case3",
                        SQLException.class.getName(),
                        "simulated " + marker,
                        "test-req-A1-3");

                // Trigger: simulate a downstream failure (INSERT B
                // throws). We roll back to ensure the outer-tx row
                // disappears.
                throw new SQLException("simulated downstream failure " + marker);
            } catch (SQLException expected) {
                c.rollback();
            } finally {
                c.setAutoCommit(true);
            }
        }

        // Outer-tx INSERT must have been rolled back.
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM study_subject WHERE label = ?")) {
            ps.setString(1, synthLabel);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                Assertions.assertEquals(0, rs.getInt(1),
                        "Outer transaction's study_subject row should have rolled back");
            }
        }

        // Audit row must STILL be present.
        Assertions.assertEquals(1, countFailureRowsWithMarker(marker),
                "Failure-audit row should survive the outer rollback");
    }

    /* ====================================================================== */
    /* Sanity — the new column + lookup rows landed                           */
    /* ====================================================================== */

    /**
     * Belt-and-braces guard against a regression where the Liquibase
     * changeset doesn't apply: the lookup rows + column must be there
     * for the rest of the suite to mean anything. Fails fast with a
     * clear message rather than masking as a NullPointerException
     * deep in the audit-log read path.
     */
    @Test
    void liquibaseAddsLookupRowsAndColumn() throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT audit_log_event_type_id, name, is_user_visible "
                             + "FROM audit_log_event_type "
                             + "WHERE audit_log_event_type_id IN (61, 62) "
                             + "ORDER BY audit_log_event_type_id")) {
            try (ResultSet rs = ps.executeQuery()) {
                Assertions.assertTrue(rs.next(), "Row 61 (OPERATION_FAILED) should exist");
                Assertions.assertEquals(61, rs.getInt(1));
                Assertions.assertEquals("OPERATION_FAILED", rs.getString(2));
                Assertions.assertFalse(rs.getBoolean(3),
                        "OPERATION_FAILED should be is_user_visible=false");

                Assertions.assertTrue(rs.next(), "Row 62 (JOB_FAILED) should exist");
                Assertions.assertEquals(62, rs.getInt(1));
                Assertions.assertEquals("JOB_FAILED", rs.getString(2));
                Assertions.assertFalse(rs.getBoolean(3),
                        "JOB_FAILED should be is_user_visible=false");
            }
        }
    }

    /* ====================================================================== */
    /* Session helper                                                         */
    /* ====================================================================== */

    /**
     * Session bound to user #1 (seeded "root") with study #1 active +
     * an Admin role on it. {@link SiteVisibilityFilter#visibleStudyIds}
     * returns the full top-level tree, so the audit-log list endpoint
     * is willing to render rows for study #1.
     */
    private static MockHttpSession adminSessionForStudy(int studyId) {
        MockHttpSession session = new MockHttpSession();
        UserAccountBean ub = new UserAccountBean();
        ub.setId(1);
        ub.setName("root");
        session.setAttribute("userBean", ub);
        StudyBean study = new StudyBean();
        study.setId(studyId);
        study.setOid("S_DEFAULTS1");
        study.setName("default-study");
        session.setAttribute("study", study);
        // No userRole bound — the audit-log endpoint reads it with a
        // null-tolerant downstream visibility check.
        return session;
    }
}
