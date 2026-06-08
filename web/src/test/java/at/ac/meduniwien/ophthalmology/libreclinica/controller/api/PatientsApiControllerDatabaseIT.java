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
import java.sql.Types;
import java.util.Locale;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.i18n.util.ResourceBundleProvider;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Phase E.6 study-nurse polish — Testcontainers IT for
 * {@link PatientsApiController}.
 *
 * <p>Pins list dedupe across studies, detail with enrolments + cross-
 * study transitions, and measurement series ordering + numeric
 * coercion.
 */
class PatientsApiControllerDatabaseIT extends AbstractApiControllerDatabaseIT {

    /** Second top-level study used for cross-study dedupe + transition tests. */
    private static int secondStudyId;

    private static final String SECOND_STUDY_OID = "S_PATIENTS_GA";

    @BeforeAll
    static void seedSecondStudyAndRole() throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection()) {
            // Insert second study as a SITE under study #1 (so the
            // Admin-roled visibility set walks parent → site and covers
            // both — same trick the eye-cohort transition IT uses).
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
                            + "VALUES (1, ?, ?, ?, '', NOW(), NOW(), NOW(), 1, 1, 1, 'default', "
                            + "'', '', '', '', '', '', '', '', '', '', 'observational', '', NOW(), "
                            + "'default', 0, 'default', '', '', '', '', '', '', '', 'both', '', '', "
                            + "false, 'Natural History', '', '', '', '', '', '', 'longitudinal', "
                            + "'Convenience Sample', 'Retrospective', '', false, ?) "
                            + "RETURNING study_id")) {
                ps.setString(1, "patients-ga");
                ps.setString(2, "patients-ga");
                ps.setString(3, "Patients GA Study");
                ps.setString(4, SECOND_STUDY_OID);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    secondStudyId = rs.getInt(1);
                }
            }

            // Seed a study_user_role binding for the root user on both
            // study #1 and the new site so visibleStudyIdsForUser
            // (which iterates active grants) returns BOTH ids.
            insertStudyUserRole(c, "root", 1, "admin");
            insertStudyUserRole(c, "root", secondStudyId, "admin");
        }
    }

    private static void insertStudyUserRole(Connection c, String userName, int studyId, String role)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO study_user_role (role_name, study_id, status_id, owner_id, "
                        + "date_created, user_name) "
                        + "VALUES (?, ?, 1, 1, NOW(), ?) "
                        + "ON CONFLICT DO NOTHING")) {
            ps.setString(1, role);
            ps.setInt(2, studyId);
            ps.setString(3, userName);
            ps.executeUpdate();
        }
    }

    private MockMvc mockMvc() {
        PatientsApiController controller = new PatientsApiController(
                DATA_SOURCE, new SiteVisibilityFilter(DATA_SOURCE));
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private MockHttpSession adminSession() {
        ResourceBundleProvider.updateLocale(Locale.ENGLISH);
        MockHttpSession session = new MockHttpSession();
        UserAccountBean ub = new UserAccountBean();
        ub.setId(1);
        ub.setName("root");
        session.setAttribute("userBean", ub);
        StudyBean study = new StudyBean();
        study.setId(1);
        study.setOid("S_DEFAULTS1");
        study.setName("Default Study");
        session.setAttribute("study", study);
        StudyUserRoleBean role = new StudyUserRoleBean();
        role.setRole(Role.ADMIN);
        role.setStudyId(1);
        session.setAttribute("userRole", role);
        return session;
    }

    /* =============================================================== */
    /* 401                                                              */
    /* =============================================================== */

    @Test
    void listReturns401WhenAnonymous() throws Exception {
        mockMvc().perform(get("/api/v1/patients"))
                .andExpect(status().isUnauthorized());
    }

    /* =============================================================== */
    /* 200 — list dedupes a patient in two studies into one row         */
    /* =============================================================== */

    @Test
    void listReturns200AndDedupesPatientAcrossStudies() throws Exception {
        // Add a second enrolment for M-001 (subject_id=1) in the second
        // study; the list should fold both into ONE patient row.
        addEnrolment(secondStudyId, /* subjectId */ 1, "M-001-GA", "OU");

        mockMvc().perform(get("/api/v1/patients?pageSize=100").session(adminSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(Matchers.greaterThanOrEqualTo(7)))
                // Find the row for subject_id=1 — has exactly two enrolments now.
                .andExpect(jsonPath("$.patients[?(@.subjectId==1)].enrolments.length()").value(
                        Matchers.hasItem(2)))
                .andExpect(jsonPath("$.patients[?(@.subjectId==1)].uniqueIdentifier").exists());
    }

    /* =============================================================== */
    /* 200 — detail returns merged enrolments + transitions             */
    /* =============================================================== */

    @Test
    void detailReturns200WithMergedEnrolmentsAndTransitions() throws Exception {
        // M-002 (subject_id=2): add a second enrolment in the second study.
        addEnrolment(secondStudyId, 2, "M-002-GA", "OD");
        // Add a transition row connecting source(M-002 study#1) to
        // target(M-002-GA new study).
        int sourceSsId = findStudySubjectId(1, 2);
        int targetSsId = findStudySubjectId(secondStudyId, 2);
        clearTransitionsForSubject(2);
        insertEyeTransition(2, "OD", sourceSsId, 1, targetSsId, secondStudyId);

        mockMvc().perform(get("/api/v1/patients/2").session(adminSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subjectId").value(2))
                .andExpect(jsonPath("$.enrolments.length()").value(Matchers.greaterThanOrEqualTo(2)))
                .andExpect(jsonPath("$.eyeTransitions.length()").value(Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.eyeTransitions[0].eye").value("OD"))
                // The legacy default study's unique_identifier is
                // 'default-study' (the OID-column in the legacy schema);
                // EyeCohortTransitionsApiController surfaces this same
                // column as `partner_study_oid` so we match its convention.
                .andExpect(jsonPath("$.eyeTransitions[0].fromStudyOid").value("default-study"))
                .andExpect(jsonPath("$.eyeTransitions[0].toStudyOid").value("patients-ga"));
    }

    /* =============================================================== */
    /* 200 — measurements ordered by date with numericValue coerced     */
    /* =============================================================== */

    @Test
    void measurementsReturns200OrderedByDateWithNumericCoercion() throws Exception {
        // M-003 (subject_id=3, study_subject_id=3) — set OU on source so OD
        // is in scope.
        setStudyEye(3, "OU");
        // Seed two observations on event_crf #6, #7 (study_subject 3). These
        // event_crfs have date_completed 2020-10-15 + 2020-11-14 respectively
        // (per the demo seed).
        clearItemDataForItem(10, 3);
        seedItemData(6, 10, "15.5");
        seedItemData(7, 10, "17,2");  // EU decimal — assert coercion.

        mockMvc().perform(get("/api/v1/patients/3/measurements?modalityCode=IOP&eye=OD")
                .session(adminSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modalityCode").value("IOP"))
                .andExpect(jsonPath("$.dataType").value("numeric"))
                .andExpect(jsonPath("$.unit").value("mmHg"))
                .andExpect(jsonPath("$.series.length()").value(Matchers.greaterThanOrEqualTo(2)))
                // Ordered by date_completed ASC.
                .andExpect(jsonPath("$.series[0].value").value("15.5"))
                .andExpect(jsonPath("$.series[0].numericValue").value(15.5))
                .andExpect(jsonPath("$.series[1].value").value("17,2"))
                .andExpect(jsonPath("$.series[1].numericValue").value(17.2));
    }

    /* =============================================================== */
    /* 400 — unknown modalityCode                                       */
    /* =============================================================== */

    @Test
    void measurementsReturns400ForUnknownModalityCode() throws Exception {
        mockMvc().perform(get("/api/v1/patients/1/measurements?modalityCode=NOTREAL&eye=OD")
                .session(adminSession()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(Matchers.containsString("NOTREAL")));
    }

    /* =============================================================== */
    /* Helpers                                                          */
    /* =============================================================== */

    private void addEnrolment(int studyId, int subjectId, String label, String eye)
            throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO study_subject (label, subject_id, study_id, status_id, "
                             + "date_created, owner_id, oc_oid, study_eye, enrollment_date) "
                             + "VALUES (?, ?, ?, 1, NOW(), 1, ?, ?, '2024-01-01') "
                             + "ON CONFLICT DO NOTHING")) {
            ps.setString(1, label);
            ps.setInt(2, subjectId);
            ps.setInt(3, studyId);
            ps.setString(4, "SS_PAT_" + label);
            ps.setString(5, eye);
            ps.executeUpdate();
        }
    }

    private int findStudySubjectId(int studyId, int subjectId) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT study_subject_id FROM study_subject "
                             + "WHERE study_id = ? AND subject_id = ?")) {
            ps.setInt(1, studyId);
            ps.setInt(2, subjectId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
                throw new SQLException("No study_subject for (study=" + studyId
                        + ", subject=" + subjectId + ")");
            }
        }
    }

    private void clearTransitionsForSubject(int subjectId) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM eye_cohort_transition WHERE subject_id = ?")) {
            ps.setInt(1, subjectId);
            ps.executeUpdate();
        }
    }

    private void insertEyeTransition(int subjectId, String eye,
                                     int sourceSs, int sourceStudy,
                                     int targetSs, int targetStudy) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO eye_cohort_transition (subject_id, eye, "
                             + "source_study_subject_id, source_study_id, "
                             + "target_study_subject_id, target_study_id, "
                             + "actor_user_id, reason) "
                             + "VALUES (?, ?, ?, ?, ?, ?, 1, 'IT seed')")) {
            ps.setInt(1, subjectId);
            ps.setString(2, eye);
            ps.setInt(3, sourceSs);
            ps.setInt(4, sourceStudy);
            ps.setInt(5, targetSs);
            ps.setInt(6, targetStudy);
            ps.executeUpdate();
        }
    }

    private void setStudyEye(int studySubjectId, String eye) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE study_subject SET study_eye = ? WHERE study_subject_id = ?")) {
            if (eye == null) ps.setNull(1, Types.VARCHAR); else ps.setString(1, eye);
            ps.setInt(2, studySubjectId);
            ps.executeUpdate();
        }
    }

    private void clearItemDataForItem(int itemId, int studySubjectIdHint) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM item_data WHERE item_id = ? "
                             + "AND event_crf_id IN (SELECT event_crf_id FROM event_crf "
                             + "  WHERE study_subject_id IN (SELECT study_subject_id "
                             + "    FROM study_subject WHERE subject_id = "
                             + "      (SELECT subject_id FROM study_subject WHERE study_subject_id = ?)))")) {
            ps.setInt(1, itemId);
            ps.setInt(2, studySubjectIdHint);
            ps.executeUpdate();
        }
    }

    private int seedItemData(int eventCrfId, int itemId, String value) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO item_data (item_id, event_crf_id, status_id, value, "
                             + "date_created, owner_id, ordinal) "
                             + "VALUES (?, ?, 1, ?, now(), 1, 1) RETURNING item_data_id")) {
            ps.setInt(1, itemId);
            ps.setInt(2, eventCrfId);
            ps.setString(3, value);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }
}
