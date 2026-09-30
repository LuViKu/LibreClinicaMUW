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
import java.util.ArrayList;
import java.util.List;

import at.ac.meduniwien.ophthalmology.libreclinica.audit.FailureAuditTemplate;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.UserType;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.admin.AuditEventDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase E hardening B (sysadmin audit UI) — pins the new
 * {@code GET /api/v1/audit/system} endpoint:
 *
 * <ol>
 *   <li>{@code nonAdministratorIsForbidden} — a user without sysadmin /
 *       techadmin flag receives 403; the per-study endpoint would
 *       have rendered (a subset of) the same rows.</li>
 *   <li>{@code administratorSeesFailureRow} — a sysadmin caller
 *       receives 200 + the payload contains an
 *       {@code audit_log_event_type_id=61} (OPERATION_FAILED) row that
 *       the per-study endpoint elides via its
 *       {@code is_user_visible=true} filter, naming the operation that
 *       failed and its error.</li>
 *   <li>{@code rowsAreLabelledByWhatTheyRecord} — a visit's start date
 *       change reads as one, with its subject and visit, and rows written
 *       under an id that meant something else at the time read as what
 *       they record (2026-09-27).</li>
 * </ol>
 */
class SystemAuditLogIT extends AbstractApiControllerDatabaseIT {

    private static AuditApiController controller() {
        return new AuditApiController(
                DATA_SOURCE, new SiteVisibilityFilter(DATA_SOURCE));
    }

    private static AuditEventDAO dao() {
        return new AuditEventDAO(DATA_SOURCE);
    }

    /**
     * Session bound to user_account #1 (seeded "root") but with the
     * sysadmin flag intentionally cleared so the gate denies access.
     * Mirrors the non-admin path the SPA's auth.bootstrap() would
     * produce when an Investigator hits the system-audit endpoint
     * directly.
     */
    private static MockHttpSession nonAdminSession() {
        MockHttpSession session = new MockHttpSession();
        UserAccountBean ub = new UserAccountBean();
        ub.setId(1);
        ub.setName("non-admin");
        // No SYSADMIN / TECHADMIN user types — isSysAdmin() returns false.
        session.setAttribute("userBean", ub);
        StudyBean study = new StudyBean();
        study.setId(1);
        study.setOid("default-study");
        session.setAttribute("study", study);
        return session;
    }

    /**
     * Session carrying the SYSADMIN user-type, which flips
     * {@link UserAccountBean#isSysAdmin()} to true and lets the
     * {@link UserAdminAuthorization#roleMayAdministerUsers} gate pass.
     */
    private static MockHttpSession sysAdminSession() {
        MockHttpSession session = new MockHttpSession();
        UserAccountBean ub = new UserAccountBean();
        ub.setId(1);
        ub.setName("root");
        ub.addUserType(UserType.SYSADMIN);
        session.setAttribute("userBean", ub);
        // The system endpoint deliberately ignores `study` — present
        // here only so any future code path that reads it doesn't NPE.
        StudyBean study = new StudyBean();
        study.setId(1);
        study.setOid("default-study");
        session.setAttribute("study", study);
        return session;
    }

    @Test
    void nonAdministratorIsForbidden() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller()).build();
        mockMvc.perform(get("/api/v1/audit/system").session(nonAdminSession()))
                .andExpect(status().isForbidden());
    }

    @Test
    void administratorSeesFailureRow() throws Exception {
        // Seed a failure-audit row tagged with a unique marker.
        String marker = "B-SYSADMIN-CASE2-" + System.nanoTime();
        try {
            FailureAuditTemplate.runOrAudit(
                    dao(),
                    /* userId */ 1,
                    "study_subject",
                    /* entityId — M-001 study_subject_id */ 1,
                    "SystemAuditLogIT.case2",
                    "test-req-B-sysadmin",
                    () -> {
                        throw new SQLException("simulated " + marker);
                    });
        } catch (SQLException expected) {
            // Template rethrew — contract.
        }

        // Sanity: the failure row landed.
        assertOperationFailureRowExists(marker);

        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller()).build();
        mockMvc.perform(get("/api/v1/audit/system").session(sysAdminSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events").isArray())
                // The marker is unique so any occurrence as a substring
                // of any `after` field is conclusive — the
                // OPERATION_FAILED row's new_value carries the
                // exception class + message + reqId triple per
                // FailureAuditTemplate.
                .andExpect(jsonPath("$.events[?(@.after =~ /.*" + marker + ".*/)]")
                        .exists())
                // Which operation failed, next to the title.
                .andExpect(jsonPath("$.events[?(@.after =~ /.*" + marker + ".*/)].details")
                        .value(hasItem("SystemAuditLogIT.case2")))
                // The error as one line: class, message, request id.
                .andExpect(jsonPath("$.events[?(@.after =~ /^java.sql.SQLException: simulated " + marker
                        + " .+ request test-req-B-sysadmin$/)]").exists());
    }

    @Test
    void rowsAreLabelledByWhatTheyRecord() throws Exception {
        int visit = insertVisit();
        List<Long> rows = new ArrayList<>();
        try {
            // The heritage trigger writes 24 when a visit's start date moves.
            // The log used to call that "Study event reset".
            exec("UPDATE study_event SET date_start = date_start + interval '1 day', update_id = 1 "
                    + "WHERE study_event_id = " + visit);
            long dateChange = latestAuditId(24, "study_event", visit);

            // Rows written under an id that meant something else at the time.
            long reopen = insertAudit(11, "event_crf", 1, "date_completed", "2026-09-01T10:00:00Z", "");
            long crfRestore = insertAudit(11, "event_crf", 1, "status_id", "AUTO_DELETED", "AVAILABLE");
            long ddeComplete = insertAudit(11, "event_crf", 1, "Status", "4", "2");
            long rfc = insertAudit(27, "item_data", 1, "I_AUDIT_IT", "a", "b");
            long siteMove = insertAudit(27, "study_subject", 1, "Study id", "S_A", "S_B");
            long fileRestore = insertAudit(128, "ingest_item", 0, "status", "DISMISSED;reason=x", "UNBOUND");
            rows.addAll(List.of(reopen, crfRestore, ddeComplete, rfc, siteMove, fileRestore));

            MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller()).build();
            mockMvc.perform(get("/api/v1/audit/system").session(sysAdminSession()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath(at(dateChange, "title")).value(hasItem("Study event start date changed")))
                    .andExpect(jsonPath(at(dateChange, "subjectId")).value(hasItem("M-001")))
                    .andExpect(jsonPath(at(dateChange, "scope")).value(hasItem(visitLabel(visit))))
                    .andExpect(jsonPath(at(reopen, "title")).value(hasItem("CRF reopened")))
                    .andExpect(jsonPath(at(crfRestore, "title")).value(hasItem("CRF restored")))
                    .andExpect(jsonPath(at(ddeComplete, "title")).value(hasItem("CRF double data entry completed")))
                    .andExpect(jsonPath(at(rfc, "title")).value(hasItem("Reason for change recorded")))
                    .andExpect(jsonPath(at(rfc, "variant")).value(hasItem("reason-for-change")))
                    .andExpect(jsonPath(at(siteMove, "title")).value(hasItem("Subject reassigned to another site")))
                    .andExpect(jsonPath(at(siteMove, "variant")).value(hasItem("admin")))
                    .andExpect(jsonPath(at(fileRestore, "title")).value(hasItem("Ingested file restored")));
        } finally {
            for (long id : rows) exec("DELETE FROM audit_log_event WHERE audit_id = " + id);
            exec("DELETE FROM audit_log_event WHERE audit_table = 'study_event' AND entity_id = " + visit);
            exec("DELETE FROM study_event WHERE study_event_id = " + visit);
        }
    }

    /* ------------------------------------------------------------------ */

    private static String at(long auditId, String field) {
        return "$.events[?(@.id == '" + auditId + "')]." + field;
    }

    private static void exec(String sql) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }

    private static long insertAudit(int type, String table, int entityId, String entityName,
                                    String oldValue, String newValue) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO audit_log_event (audit_log_event_type_id, audit_date, user_id, "
                             + "audit_table, entity_id, entity_name, old_value, new_value) "
                             + "VALUES (?, now(), 1, ?, ?, ?, ?, ?) RETURNING audit_id")) {
            ps.setInt(1, type);
            ps.setString(2, table);
            ps.setInt(3, entityId);
            ps.setString(4, entityName);
            ps.setString(5, oldValue);
            ps.setString(6, newValue);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static long latestAuditId(int type, String table, int entityId) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT MAX(audit_id) FROM audit_log_event "
                             + "WHERE audit_log_event_type_id = ? AND audit_table = ? AND entity_id = ?")) {
            ps.setInt(1, type);
            ps.setString(2, table);
            ps.setInt(3, entityId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                long id = rs.getLong(1);
                if (rs.wasNull()) throw new IllegalStateException("the trigger wrote no type " + type + " row");
                return id;
            }
        }
    }

    /** A visit of the demo study's first definition for M-001. */
    private static int insertVisit() throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO study_event (study_event_definition_id, study_subject_id, location, "
                             + " sample_ordinal, date_start, owner_id, status_id, "
                             + " subject_event_status_id, date_created, start_time_flag, end_time_flag) "
                             + "SELECT sed.study_event_definition_id, ss.study_subject_id, '', "
                             + "       COALESCE((SELECT MAX(se2.sample_ordinal) FROM study_event se2 "
                             + "                  WHERE se2.study_subject_id = ss.study_subject_id "
                             + "                    AND se2.study_event_definition_id = sed.study_event_definition_id), 0) + 1, "
                             + "       DATE '2026-09-23', 1, 1, 1, NOW(), false, false "
                             + "  FROM study_subject ss, "
                             + "       (SELECT study_event_definition_id FROM study_event_definition "
                             + "         WHERE study_id = 1 ORDER BY ordinal, study_event_definition_id LIMIT 1) sed "
                             + " WHERE ss.label = 'M-001' AND ss.study_id = 1 "
                             + "RETURNING study_event_id");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /** How the audit view should name that visit. */
    private static String visitLabel(int studyEventId) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT sed.name, se.sample_ordinal, sed.repeating FROM study_event se "
                             + "JOIN study_event_definition sed "
                             + "  ON sed.study_event_definition_id = se.study_event_definition_id "
                             + "WHERE se.study_event_id = ?")) {
            ps.setInt(1, studyEventId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return AuditRowLabels.visitLabel(rs.getString(1), rs.getInt(2), rs.getBoolean(3));
            }
        }
    }

    private static void assertOperationFailureRowExists(String marker) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM audit_log_event "
                             + "WHERE audit_log_event_type_id = 61 "
                             + "AND new_value LIKE ?")) {
            ps.setString(1, "%" + marker + "%");
            try (var rs = ps.executeQuery()) {
                rs.next();
                if (rs.getInt(1) != 1) {
                    throw new IllegalStateException(
                            "Expected exactly one OPERATION_FAILED row for marker "
                                    + marker + "; got " + rs.getInt(1));
                }
            }
        }
    }
}
