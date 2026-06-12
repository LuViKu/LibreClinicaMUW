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

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Phase E.6 study-nurse polish — Testcontainers IT for
 * {@link ModalityBaselinesApiController}.
 *
 * <p>Pins the per-eye baseline aggregator across global + per-study
 * scope. Seeds item_data rows on top of the demo-data fixtures and
 * asserts the earliest-observation date + value bubble up correctly.
 */
class ModalityBaselinesApiControllerDatabaseIT extends AbstractApiControllerDatabaseIT {

    private MockMvc mockMvc() {
        ModalityBaselinesApiController controller = new ModalityBaselinesApiController(
                DATA_SOURCE, new SiteVisibilityFilter(DATA_SOURCE));
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
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

    /* =============================================================== */
    /* 401                                                              */
    /* =============================================================== */

    @Test
    void returns401WhenAnonymous() throws Exception {
        mockMvc().perform(get("/api/v1/subjects/M-001/eyes/OD/modality-baselines"))
                .andExpect(status().isUnauthorized());
    }

    /* =============================================================== */
    /* 404 — unknown label                                              */
    /* =============================================================== */

    @Test
    void returns404ForUnknownLabel() throws Exception {
        mockMvc().perform(get("/api/v1/subjects/MNOEX99/eyes/OD/modality-baselines")
                .session(adminSession(1)))
                .andExpect(status().isNotFound());
    }

    /* =============================================================== */
    /* 200 — happy: global = earliest, per-study = earliest in active   */
    /* =============================================================== */

    @Test
    void returns200WithEarliestObservationGlobalAndPerStudy() throws Exception {
        // M-002 (study_subject_id=2, subject_id=2) — set OU so OD is in scope.
        setStudyEye(2, "OU");
        // Seed two item_data observations on event_crf #4 (study_subject 2)
        // and event_crf #5 (study_subject 2) — different dates.
        // Use item I_IOP_OD (item_id=10).
        clearItemDataForItem(10, 2);
        int idA = seedItemData(/* event_crf_id */ 4, /* item_id */ 10, "14.5");
        int idB = seedItemData(/* event_crf_id */ 5, /* item_id */ 10, "16.0");
        // event_crf 4 has date_completed 2020-10-09, event_crf 5 has 2020-11-08
        // (per the demo seed), so 14.5 / 2020-10-09 is the earliest.

        mockMvc().perform(get("/api/v1/subjects/M-002/eyes/OD/modality-baselines")
                .session(adminSession(1)))
                .andExpect(status().isOk())
                // IOP row carries the seeded observations.
                .andExpect(jsonPath("$[?(@.modalityCode=='IOP')].global.value").value(
                        Matchers.hasItem("14.5")))
                .andExpect(jsonPath("$[?(@.modalityCode=='IOP')].global.observationCount").value(
                        Matchers.hasItem(2)))
                .andExpect(jsonPath("$[?(@.modalityCode=='IOP')].perStudy.value").value(
                        Matchers.hasItem("14.5")))
                .andExpect(jsonPath("$[?(@.modalityCode=='IOP')].itemOid").value(
                        Matchers.hasItem("I_IOP_OD")));
    }

    /* =============================================================== */
    /* 200 — transitioned eye: global includes prior study, per-study   */
    /*        excludes it                                                */
    /* =============================================================== */

    @Test
    void returns200WithTransitionedEyeSurfacingInGlobalButNotPerStudy() throws Exception {
        // Build a second study (target of the transition) + a study_subject
        // for the same human there.
        int targetStudyId = insertSecondStudy("S_GA_BASELINE");
        // Use M-004 (study_subject_id=4, subject_id=4) — set OD on the source.
        setStudyEye(4, "OD");
        unenrollFromStudy(targetStudyId, 4);
        int targetSsId = insertStudySubject(targetStudyId, 4, "M-004-GA", "OD");

        // Seed a measurement on the SOURCE side (study #1).
        clearItemDataForItem(10, 4);
        int sourceObsId = seedItemData(9, 10, "12.0");
        // event_crf 9 has study_subject_id=4 + date_completed=null (per seed),
        // so the date will be null. Let's instead update its date_completed.
        setEventCrfDateCompleted(9, "2019-05-01");
        // Seed a measurement on the TARGET side via a fresh event_crf row.
        int targetEventId = insertStudyEvent(targetSsId);
        int targetEventCrfId = insertEventCrf(targetEventId, targetSsId, "2024-08-15");
        int targetObsId = seedItemData(targetEventCrfId, 10, "22.0");

        // Persist the eye_cohort_transition row (source row already has OD,
        // we lock-in the transition for the OD eye).
        insertEyeTransition(/* subject */ 4, "OD",
                /* source_ss */ 4, /* source_study */ 1,
                /* target_ss */ targetSsId, /* target_study */ targetStudyId);

        // Now session bound to study #1 (the SOURCE):
        // global should see BOTH observations + earliest (the 12.0 from 2019).
        // per-study should only see the source (12.0 from 2019).
        mockMvc().perform(get("/api/v1/subjects/M-004/eyes/OD/modality-baselines")
                .session(adminSession(1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.modalityCode=='IOP')].global.value").value(
                        Matchers.hasItem("12.0")))
                .andExpect(jsonPath("$[?(@.modalityCode=='IOP')].global.observationCount").value(
                        Matchers.hasItem(2)))
                .andExpect(jsonPath("$[?(@.modalityCode=='IOP')].perStudy.value").value(
                        Matchers.hasItem("12.0")))
                .andExpect(jsonPath("$[?(@.modalityCode=='IOP')].perStudy.observationCount").value(
                        Matchers.hasItem(1)));
    }

    /* =============================================================== */
    /* 200 — empty rows when no data                                    */
    /* =============================================================== */

    @Test
    void returns200WithEmptyAggregatesWhenNoData() throws Exception {
        // M-007 (study_subject_id=7) — set OD, then make sure no item_data
        // exists for the IOP OD item on this subject.
        setStudyEye(7, "OD");
        clearItemDataForItem(10, 7);

        mockMvc().perform(get("/api/v1/subjects/M-007/eyes/OD/modality-baselines")
                .session(adminSession(1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.modalityCode=='IOP')].global.value").value(
                        Matchers.hasItem(Matchers.nullValue())))
                .andExpect(jsonPath("$[?(@.modalityCode=='IOP')].global.observationCount").value(
                        Matchers.hasItem(0)));
    }

    /* =============================================================== */
    /* Helpers                                                          */
    /* =============================================================== */

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
        // Clear ALL item_data rows for the item across ALL event_crfs the
        // subject's study_subjects own, so per-test fixtures stay isolated.
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

    private void setEventCrfDateCompleted(int eventCrfId, String isoDate) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE event_crf SET date_completed = ?::timestamp WHERE event_crf_id = ?")) {
            ps.setString(1, isoDate);
            ps.setInt(2, eventCrfId);
            ps.executeUpdate();
        }
    }

    private int insertSecondStudy(String oid) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
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
            ps.setString(1, "ga-baseline-" + oid);
            ps.setString(2, "ga-baseline-" + oid);
            ps.setString(3, "Baseline target " + oid);
            ps.setString(4, oid);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private void unenrollFromStudy(int studyId, int subjectId) throws SQLException {
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
                    "DELETE FROM item_data WHERE event_crf_id IN ("
                            + " SELECT event_crf_id FROM event_crf "
                            + "  WHERE study_subject_id IN ("
                            + "    SELECT study_subject_id FROM study_subject "
                            + "     WHERE study_id = ? AND subject_id = ?))")) {
                ps.setInt(1, studyId);
                ps.setInt(2, subjectId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM event_crf WHERE study_subject_id IN "
                            + "(SELECT study_subject_id FROM study_subject "
                            + " WHERE study_id = ? AND subject_id = ?)")) {
                ps.setInt(1, studyId);
                ps.setInt(2, subjectId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM study_event WHERE study_subject_id IN "
                            + "(SELECT study_subject_id FROM study_subject "
                            + " WHERE study_id = ? AND subject_id = ?)")) {
                ps.setInt(1, studyId);
                ps.setInt(2, subjectId);
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

    private int insertStudySubject(int studyId, int subjectId, String label, String eye)
            throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO study_subject (label, subject_id, study_id, status_id, "
                             + "date_created, owner_id, oc_oid, study_eye) "
                             + "VALUES (?, ?, ?, 1, NOW(), 1, ?, ?) "
                             + "RETURNING study_subject_id")) {
            ps.setString(1, label);
            ps.setInt(2, subjectId);
            ps.setInt(3, studyId);
            ps.setString(4, "SS_BASE_" + label);
            ps.setString(5, eye);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private int insertStudyEvent(int studySubjectId) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO study_event (study_event_definition_id, study_subject_id, "
                             + "location, sample_ordinal, date_start, owner_id, status_id, "
                             + "date_created, subject_event_status_id, start_time_flag, end_time_flag) "
                             + "VALUES (1, ?, '', 1, NOW(), 1, 1, NOW(), 1, false, false) "
                             + "RETURNING study_event_id")) {
            ps.setInt(1, studySubjectId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private int insertEventCrf(int studyEventId, int studySubjectId, String dateCompleted)
            throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO event_crf (study_event_id, crf_version_id, completion_status_id, "
                             + "status_id, date_completed, owner_id, date_created, study_subject_id, "
                             + "electronic_signature_status, sdv_status) "
                             + "VALUES (?, 1, 1, 1, ?::timestamp, 1, NOW(), ?, false, false) "
                             + "RETURNING event_crf_id")) {
            ps.setInt(1, studyEventId);
            ps.setString(2, dateCompleted);
            ps.setInt(3, studySubjectId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
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
}
