/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 2026-06-10 — Testcontainers IT for the explicit-targetLabel collision
 * branch of {@link EyeCohortTransitionsApiController#transition}.
 *
 * <p>The legacy "no targetLabel supplied" path randomises the new
 * target row's OID on collision. With operator-typed labels (the
 * Phase E.6 study-nurse polish UX), submission-time collisions must
 * surface as a structured 409 carrying the canonical
 * {@link at.ac.meduniwien.ophthalmology.libreclinica.controller.api.dto.ValidationErrorBody}
 * shape so the SPA can red-ring the targetLabel input.
 */
class EyeTransitionLabelCollisionDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static final String TARGET_STUDY_OID = "S_GA03";
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
                ps.setString(2, "ga-collision");
                ps.setString(3, "ga-collision");
                ps.setString(4, "GA Collision Study");
                ps.setString(5, TARGET_STUDY_OID);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (!keys.next()) {
                        throw new SQLException("Collision target study insert produced no PK.");
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

    /**
     * Operator typed a {@code targetLabel} that is already taken by
     * ANOTHER subject in the target study. Submitting the transition
     * must 409 + return a {@link
     * at.ac.meduniwien.ophthalmology.libreclinica.controller.api.dto.ValidationErrorBody}
     * with a {@code FieldError("targetLabel", "duplicate")} entry so the
     * SPA's per-field error wiring can highlight the input.
     */
    @Test
    void transitionReturns409WithStructuredErrorWhenTargetLabelCollidesWithSibling()
            throws Exception {
        // M-001 lives in study #1 with OD assigned. Seed a *sibling*
        // enrolment in the target study (different subject_id) under the
        // label "M-NEW" — this is the row M-001's transition will collide
        // with.
        setStudyEye(1, "OD");
        unenrollSubjectFromStudy(targetStudyId, 1);
        insertCollisionRow(targetStudyId, /*subjectId=*/77, "M-NEW");

        mockMvc().perform(post("/api/v1/subjects/M-001/eyes/OD/transition")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetStudyOid\":\"" + TARGET_STUDY_OID + "\","
                        + "\"targetLabel\":\"M-NEW\","
                        + "\"reason\":\"Operator picked a taken ID\"}")
                .session(adminSession(1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value(containsString("M-NEW")))
                .andExpect(jsonPath("$.errors[0].field").value("targetLabel"))
                .andExpect(jsonPath("$.errors[0].message").value("duplicate"));
    }

    /* ====================================================================== */
    /* Helpers (mirrored from EyeCohortTransitionApiControllerDatabaseIT)     */
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

    private void insertCollisionRow(int studyId, int subjectId, String label) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO subject (subject_id, date_of_birth, dob_collected, gender, "
                            + "unique_identifier, charity_required, status_id, date_created, owner_id) "
                            + "VALUES (?, NULL, false, 'm', ?, false, 1, NOW(), 1) "
                            + "ON CONFLICT (subject_id) DO NOTHING")) {
                ps.setInt(1, subjectId);
                ps.setString(2, "person-collision-" + subjectId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO study_subject (label, subject_id, study_id, status_id, "
                            + "date_created, owner_id, oc_oid, study_eye) "
                            + "VALUES (?, ?, ?, 1, NOW(), 1, ?, NULL)")) {
                ps.setString(1, label);
                ps.setInt(2, subjectId);
                ps.setInt(3, studyId);
                ps.setString(4, "SS_COL_" + System.nanoTime());
                ps.executeUpdate();
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
