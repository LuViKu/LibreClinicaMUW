/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import java.util.Locale;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.i18n.util.ResourceBundleProvider;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 2026-06-11 — Testcontainers IT for the per-study
 * {@link AuditApiController#list} eye-cohort-transition branch.
 *
 * <p>Pins the GxP audit-trail contract that an
 * {@code eye_cohort_transition} row written by
 * {@link EyeCohortTransitionsApiController#emitTransitionAudit} surfaces
 * in BOTH the source-study AND the target-study per-study audit log
 * (a transition affects both studies, so per-study reviewers must see
 * the move from either side).
 *
 * <p>The {@link AuditApiController#listSystem} sysadmin endpoint
 * already surfaces these rows (no per-study scoping, no
 * {@code is_user_visible} filter). This IT covers the per-study
 * scoping that was the bug — pre-fix the row existed in the DB but
 * never reached the SPA Audit Log view.
 */
class AuditApiControllerDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static final String SOURCE_STUDY_OID = "S_ECTSRC";
    private static final String TARGET_STUDY_OID = "S_ECTTGT";
    private static int sourceStudyId;
    private static int targetStudyId;

    @BeforeAll
    static void seedTransitionStudies() throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection()) {
            sourceStudyId = insertStudy(c, "ect-src", "ECT source study", SOURCE_STUDY_OID);
            targetStudyId = insertStudy(c, "ect-tgt", "ECT target study", TARGET_STUDY_OID);
        }
    }

    private static int insertStudy(Connection c, String uniqueId, String name, String ocOid)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO study (parent_study_id, unique_identifier, secondary_identifier, "
                        + "name, summary, date_planned_start, date_planned_end, date_created, "
                        + "owner_id, type_id, status_id, principal_investigator, facility_name, "
                        + "facility_city, facility_state, facility_zip, facility_country, "
                        + "facility_recruitment_status, facility_contact_name, facility_contact_degree, "
                        + "facility_contact_phone, facility_contact_email, protocol_type, "
                        + "protocol_description, protocol_date_verification, phase, "
                        + "expected_total_enrollment, sponsor, collaborators, medline_identifier, "
                        + "url, url_description, conditions, keywords, eligibility, gender, "
                        + "age_max, age_min, healthy_volunteer_accepted, purpose, allocation, "
                        + "masking, control, assignment, endpoint, interventions, duration, "
                        + "selection, timing, official_title, results_reference, oc_oid) "
                        + "VALUES (?, ?, ?, ?, '', NOW(), NOW(), NOW(), 1, 1, 1, 'default', "
                        + "'', '', '', '', '', '', '', '', '', '', 'observational', '', NOW(), "
                        + "'default', 0, 'default', '', '', '', '', '', '', '', 'both', '', '', "
                        + "false, 'Natural History', '', '', '', '', '', '', 'longitudinal', "
                        + "'Convenience Sample', 'Retrospective', '', false, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, 1);
            ps.setString(2, uniqueId);
            ps.setString(3, uniqueId);
            ps.setString(4, name);
            ps.setString(5, ocOid);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("Transition-study insert produced no PK.");
                }
                return keys.getInt(1);
            }
        }
    }

    private MockMvc mockMvc() {
        AuditApiController controller = new AuditApiController(
                DATA_SOURCE,
                new SiteVisibilityFilter(DATA_SOURCE));
        return MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void studyAuditLogIncludesEyeTransitionAsSource() throws Exception {
        // Seed the transition row + its audit-log edge. Source = the
        // study bound in the session. The per-study audit query must
        // surface the row when the source side is active.
        int transitionId = insertTransitionRow(sourceStudyId, targetStudyId);
        insertTransitionAuditRow(transitionId, "S_AUDIT_SRC");

        mockMvc().perform(
                get("/api/v1/audit")
                        .session(adminSession(sourceStudyId, SOURCE_STUDY_OID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItem(String.valueOf(rowAuditId("S_AUDIT_SRC")))));
    }

    @Test
    void studyAuditLogIncludesEyeTransitionAsTarget() throws Exception {
        // Same row shape — this time the SESSION holds the TARGET
        // study. The reviewer in the receiving study must see the
        // inbound transition just as the source-side reviewer sees
        // the outbound one.
        int transitionId = insertTransitionRow(sourceStudyId, targetStudyId);
        insertTransitionAuditRow(transitionId, "S_AUDIT_TGT");

        mockMvc().perform(
                get("/api/v1/audit")
                        .session(adminSession(targetStudyId, TARGET_STUDY_OID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItem(String.valueOf(rowAuditId("S_AUDIT_TGT")))));
    }

    /* ====================================================================== */
    /* Helpers                                                                */
    /* ====================================================================== */

    /**
     * Insert a minimal {@code eye_cohort_transition} row reusing the
     * demo-seed subject_id=1 + study_subject_id=1 in the source study,
     * and a fresh study_subject row in the target study so the FKs
     * resolve. Returns the generated {@code transition_id}.
     */
    private int insertTransitionRow(int srcStudyId, int tgtStudyId) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection()) {
            int targetSsId = insertStudySubject(c, tgtStudyId, /*subjectId=*/1,
                    "ECT-TGT-" + System.nanoTime());
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO eye_cohort_transition (subject_id, eye, "
                            + "source_study_subject_id, source_study_id, "
                            + "target_study_subject_id, target_study_id, "
                            + "transitioned_at, actor_user_id, reason) "
                            + "VALUES (?, 'OD', ?, ?, ?, ?, NOW(), 1, 'IT seed')",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setInt(1, 1);
                ps.setInt(2, 1);
                ps.setInt(3, srcStudyId);
                ps.setInt(4, targetSsId);
                ps.setInt(5, tgtStudyId);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (!keys.next()) {
                        throw new SQLException("eye_cohort_transition insert produced no PK.");
                    }
                    return keys.getInt(1);
                }
            }
        }
    }

    private int insertStudySubject(Connection c, int studyId, int subjectId, String label)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO study_subject (label, subject_id, study_id, status_id, "
                        + "date_created, owner_id, oc_oid, study_eye) "
                        + "VALUES (?, ?, ?, 1, NOW(), 1, ?, 'OD')",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, label);
            ps.setInt(2, subjectId);
            ps.setInt(3, studyId);
            ps.setString(4, "SS_ECT_" + System.nanoTime());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("study_subject insert produced no PK.");
                }
                return keys.getInt(1);
            }
        }
    }

    /**
     * Insert the {@code audit_log_event} row of type 57 that mirrors
     * what {@link EyeCohortTransitionsApiController#emitTransitionAudit}
     * writes during a real transition. The {@code marker} string is
     * stashed in {@code entity_name} so the test can look up the
     * generated audit_id without depending on row ordering.
     */
    private void insertTransitionAuditRow(int transitionId, String marker) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO audit_log_event (audit_log_event_type_id, audit_date, "
                             + "user_id, audit_table, entity_id, entity_name, old_value, new_value) "
                             + "VALUES (57, NOW(), 1, 'eye_cohort_transition', ?, ?, "
                             + "'" + SOURCE_STUDY_OID + "|OD|OD', "
                             + "'" + TARGET_STUDY_OID + "|OD|OU|IT seed')")) {
            ps.setInt(1, transitionId);
            ps.setString(2, marker);
            ps.executeUpdate();
        }
    }

    /**
     * Resolve the {@code audit_id} of the row identified by its
     * {@code entity_name} marker. Returns the most recent matching row
     * so re-runs against the same container do not collide.
     */
    private int rowAuditId(String marker) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT audit_id FROM audit_log_event "
                             + "WHERE audit_table = 'eye_cohort_transition' "
                             + "AND entity_name = ? "
                             + "ORDER BY audit_id DESC LIMIT 1")) {
            ps.setString(1, marker);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("No audit row with marker=" + marker);
                }
                return rs.getInt(1);
            }
        }
    }

    private MockHttpSession adminSession(int studyId, String studyOid) {
        ResourceBundleProvider.updateLocale(Locale.ENGLISH);
        MockHttpSession session = new MockHttpSession();
        UserAccountBean ub = new UserAccountBean();
        ub.setId(1);
        ub.setName("root");
        session.setAttribute("userBean", ub);
        StudyBean study = new StudyBean();
        study.setId(studyId);
        study.setOid(studyOid);
        study.setName("study-" + studyId);
        session.setAttribute("study", study);

        StudyUserRoleBean role = new StudyUserRoleBean();
        role.setRole(Role.ADMIN);
        role.setStudyId(studyId);
        session.setAttribute("userRole", role);
        return session;
    }
}
