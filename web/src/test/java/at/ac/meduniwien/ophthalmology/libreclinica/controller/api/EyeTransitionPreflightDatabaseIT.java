/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;

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
 * 2026-06-10 — Testcontainers IT for
 * {@link EyeCohortTransitionsApiController#transitionPreflight}.
 *
 * <p>Pins the SPA-facing contract the TransitionEyeDialog relies on:
 *
 * <ul>
 *   <li><strong>not-enrolled, no candidate label</strong> → preflight
 *       returns {@code alreadyEnrolled=false} +
 *       {@code labelAvailable=true} (no candidate to check).</li>
 *   <li><strong>not-enrolled + colliding candidate label</strong> →
 *       {@code alreadyEnrolled=false} + {@code labelAvailable=false}
 *       so the dialog can red-ring the input and keep submit
 *       disabled.</li>
 *   <li><strong>already-enrolled</strong> → {@code alreadyEnrolled=true}
 *       + the existing target row's OID/label so the dialog can show
 *       the "Patient ist bereits als {label} angelegt" info line.</li>
 * </ul>
 */
class EyeTransitionPreflightDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static final String TARGET_STUDY_OID = "S_GA02";
    private static int targetStudyId;

    @BeforeAll
    static void seedTargetStudy() throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection()) {
            int id;
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
                ps.setString(2, "ga-preflight");
                ps.setString(3, "ga-preflight");
                ps.setString(4, "GA Preflight Study");
                ps.setString(5, TARGET_STUDY_OID);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (!keys.next()) {
                        throw new SQLException("Preflight target study insert produced no PK.");
                    }
                    id = keys.getInt(1);
                }
            }
            targetStudyId = id;
        }
    }

    private MockMvc mockMvc() {
        EyeCohortTransitionsApiController controller = new EyeCohortTransitionsApiController(
                DATA_SOURCE,
                new SiteVisibilityFilter(DATA_SOURCE));
        return MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void preflightReturnsNotEnrolledWithLabelAvailableTrueWhenSubjectAbsentFromTarget()
            throws Exception {
        // M-001 is in study #1 only; no target row yet. No candidate label
        // supplied → labelAvailable defaults to true.
        setStudyEye(1, "OD");
        unenrollSubjectFromStudy(targetStudyId, 1);

        mockMvc().perform(
                get("/api/v1/subjects/M-001/eyes/OD/transition/preflight")
                        .param("targetStudyOid", TARGET_STUDY_OID)
                        .session(adminSession(1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alreadyEnrolled").value(false))
                .andExpect(jsonPath("$.existingTargetOid").doesNotExist())
                .andExpect(jsonPath("$.existingTargetLabel").doesNotExist())
                .andExpect(jsonPath("$.labelAvailable").value(true));
    }

    @Test
    void preflightReturnsLabelAvailableFalseWhenCandidateLabelTakenInTarget()
            throws Exception {
        // M-002 is in study #1 only. A SIBLING enrolment (different
        // subject) named "M-100" already lives in the target study; the
        // candidate label "M-100" must therefore collide.
        setStudyEye(2, "OD");
        unenrollSubjectFromStudy(targetStudyId, 2);
        // Pre-existing collision row uses subject_id=99 (some other
        // person) so it's NOT the same subject and the dialog should
        // surface the collision verbatim.
        insertCollisionRow(targetStudyId, /*subjectId=*/99, "M-100");

        mockMvc().perform(
                get("/api/v1/subjects/M-002/eyes/OD/transition/preflight")
                        .param("targetStudyOid", TARGET_STUDY_OID)
                        .param("targetLabel", "M-100")
                        .session(adminSession(1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alreadyEnrolled").value(false))
                .andExpect(jsonPath("$.labelAvailable").value(false));
    }

    @Test
    void preflightReturnsAlreadyEnrolledTrueWhenSubjectHasTargetRow() throws Exception {
        // M-003 lives in study #1 + has been (re-)enrolled in the target
        // study under the same label "M-003" — the existing target row.
        setStudyEye(3, "OU");
        unenrollSubjectFromStudy(targetStudyId, 3);
        int targetSsId = insertTargetEnrollment(targetStudyId, /*subjectId=*/3, "M-003");

        mockMvc().perform(
                get("/api/v1/subjects/M-003/eyes/OD/transition/preflight")
                        .param("targetStudyOid", TARGET_STUDY_OID)
                        .session(adminSession(1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alreadyEnrolled").value(true))
                .andExpect(jsonPath("$.existingTargetLabel").value("M-003"))
                .andExpect(jsonPath("$.existingTargetOid")
                        .value("SS_PRE_M-003_" + targetSsId));
    }

    /* ====================================================================== */
    /* Helpers                                                                */
    /* ====================================================================== */

    private void setStudyEye(int studySubjectId, String eye) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE study_subject SET study_eye = ? WHERE study_subject_id = ?")) {
            if (eye == null) {
                ps.setNull(1, Types.VARCHAR);
            } else {
                ps.setString(1, eye);
            }
            ps.setInt(2, studySubjectId);
            ps.executeUpdate();
        }
    }

    private void unenrollSubjectFromStudy(int studyId, int subjectId) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM eye_cohort_transition "
                            + "WHERE subject_id = ? AND (source_study_id = ? OR target_study_id = ?)")) {
                ps.setInt(1, subjectId);
                ps.setInt(2, studyId);
                ps.setInt(3, studyId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM study_subject WHERE study_id = ? AND subject_id = ?")) {
                ps.setInt(1, studyId);
                ps.setInt(2, subjectId);
                ps.executeUpdate();
            }
        }
    }

    private int insertTargetEnrollment(int studyId, int subjectId, String label)
            throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection()) {
            // Use a placeholder OID first to obtain the generated key,
            // then UPDATE so the OID encodes the assigned ID — this gives
            // the test deterministic OID assertions without relying on
            // sequence wall-clock guesses.
            int id;
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO study_subject (label, subject_id, study_id, status_id, "
                            + "date_created, owner_id, oc_oid, study_eye) "
                            + "VALUES (?, ?, ?, 1, NOW(), 1, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, label);
                ps.setInt(2, subjectId);
                ps.setInt(3, studyId);
                ps.setString(4, "TMP_" + System.nanoTime());
                ps.setString(5, "OD");
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (!keys.next()) {
                        throw new SQLException("Target enrollment insert produced no PK.");
                    }
                    id = keys.getInt(1);
                }
            }
            try (PreparedStatement upd = c.prepareStatement(
                    "UPDATE study_subject SET oc_oid = ? WHERE study_subject_id = ?")) {
                upd.setString(1, "SS_PRE_" + label + "_" + id);
                upd.setInt(2, id);
                upd.executeUpdate();
            }
            return id;
        }
    }

    /**
     * Insert a study_subject row with the given label using the seeded
     * subject_id (must exist in {@code subject}). Used to seed the
     * label-collision scenario without invoking the controller.
     */
    private int insertCollisionRow(int studyId, int subjectId, String label) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection()) {
            // Ensure a subject row exists for the synthetic subject_id;
            // the unique_identifier column is required + must be unique.
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO subject (subject_id, date_of_birth, dob_collected, gender, "
                            + "unique_identifier, status_id, date_created, owner_id) "
                            + "VALUES (?, NULL, false, 'm', ?, 1, NOW(), 1) "
                            + "ON CONFLICT (subject_id) DO NOTHING")) {
                ps.setInt(1, subjectId);
                ps.setString(2, "person-" + subjectId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO study_subject (label, subject_id, study_id, status_id, "
                            + "date_created, owner_id, oc_oid, study_eye) "
                            + "VALUES (?, ?, ?, 1, NOW(), 1, ?, NULL)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, label);
                ps.setInt(2, subjectId);
                ps.setInt(3, studyId);
                ps.setString(4, "SS_COL_" + System.nanoTime());
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (!keys.next()) {
                        throw new SQLException("Collision-row insert produced no PK.");
                    }
                    return keys.getInt(1);
                }
            }
        }
    }

    private MockHttpSession adminSession(int studyId) {
        ResourceBundleProvider.updateLocale(Locale.ENGLISH);
        MockHttpSession session = new MockHttpSession();
        UserAccountBean ub = new UserAccountBean();
        ub.setId(1);
        ub.setName("root");
        session.setAttribute("userBean", ub);
        StudyBean study = new StudyBean();
        study.setId(studyId);
        study.setOid(studyId == 1 ? "S_DEFAULTS1" : "synthetic-" + studyId);
        study.setName("study-" + studyId);
        session.setAttribute("study", study);

        StudyUserRoleBean role = new StudyUserRoleBean();
        role.setRole(Role.ADMIN);
        role.setStudyId(studyId);
        session.setAttribute("userRole", role);
        return session;
    }
}
