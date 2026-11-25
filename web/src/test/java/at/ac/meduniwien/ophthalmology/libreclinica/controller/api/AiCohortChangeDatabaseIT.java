/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import at.ac.meduniwien.ophthalmology.libreclinica.testsupport.ProductionMvc;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.core.SecurityManager;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Who may move a participant between the AI arms ({@code AI_SHOWN} /
 * {@code AI_HIDDEN}) through {@code PUT /subjects/{oid}/groups}.
 *
 * <p>Every role the caller holds on the study counts (DR-042). A treating
 * role refuses whatever else the account holds, as it blinds whatever else
 * the account holds: a treating clinician who could move a patient out of
 * the hidden arm would see the AI output after all. The Data Manager may
 * change the arm before the first completed visit, an administrator always.
 *
 * <p>Builds its own arm class on Default Study, which the test database does
 * not seed, and a participant without a completed visit, and removes both
 * afterwards.
 */
class AiCohortChangeDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static final String TREATING = "A treating role";

    private int armClassId;
    private int shownGroupId;
    private int hiddenGroupId;
    private String subjectOid;
    private int studySubjectId;

    @BeforeEach
    void seedArms() throws Exception {
        try (Connection c = DATA_SOURCE.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT ss.study_subject_id, ss.oc_oid FROM study_subject ss "
                            + " WHERE ss.study_id = 1 AND ss.status_id NOT IN (5, 7) "
                            + "   AND NOT EXISTS (SELECT 1 FROM study_event se "
                            + "                    WHERE se.study_subject_id = ss.study_subject_id "
                            + "                      AND se.subject_event_status_id IN (4, 7, 8)) "
                            + " ORDER BY ss.study_subject_id LIMIT 1");
                 ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "the seed should provide a participant without a completed visit");
                studySubjectId = rs.getInt(1);
                subjectOid = rs.getString(2);
            }
            armClassId = insertReturningId(c,
                    "INSERT INTO study_group_class (name, study_id, owner_id, date_created, "
                            + "group_class_type_id, status_id, subject_assignment) "
                            + "VALUES ('AI cohort IT', 1, 1, NOW(), 3, 1, 'Optional') "
                            + "RETURNING study_group_class_id");
            shownGroupId = insertReturningId(c,
                    "INSERT INTO study_group (name, description, study_group_class_id, allocation_weight) "
                            + "VALUES ('AI_SHOWN', 'AI cohort IT', " + armClassId + ", 1) "
                            + "RETURNING study_group_id");
            hiddenGroupId = insertReturningId(c,
                    "INSERT INTO study_group (name, description, study_group_class_id, allocation_weight) "
                            + "VALUES ('AI_HIDDEN', 'AI cohort IT', " + armClassId + ", 1) "
                            + "RETURNING study_group_id");
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO subject_group_map (study_group_class_id, study_subject_id, study_group_id, "
                            + "status_id, owner_id, date_created) VALUES (?, ?, ?, 1, 1, NOW())")) {
                ps.setInt(1, armClassId);
                ps.setInt(2, studySubjectId);
                ps.setInt(3, hiddenGroupId);
                ps.executeUpdate();
            }
        }
    }

    @AfterEach
    void removeArms() throws Exception {
        ClinicalWriteFixtures.execute(DATA_SOURCE,
                "DELETE FROM subject_group_map WHERE study_group_class_id = " + armClassId);
        ClinicalWriteFixtures.execute(DATA_SOURCE,
                "DELETE FROM study_group WHERE study_group_class_id = " + armClassId);
        ClinicalWriteFixtures.execute(DATA_SOURCE,
                "DELETE FROM study_group_class WHERE study_group_class_id = " + armClassId);
        ClinicalWriteFixtures.execute(DATA_SOURCE,
                "DELETE FROM study_user_role WHERE user_name = 'manual_dm' AND role_name = 'Investigator'");
    }

    @Test
    void theDataManagerMovesAParticipantBeforeTheFirstCompletedVisit() throws Exception {
        moveToTheShownArm(boundAs("manual_dm", Role.STUDYDIRECTOR))
                .andExpect(status().isOk());
        assertEquals(shownGroupId, activeArm());
    }

    @Test
    void aDataManagerWhoIsAlsoAnInvestigatorDoesNotMoveAParticipant() throws Exception {
        ClinicalWriteFixtures.execute(DATA_SOURCE,
                "INSERT INTO study_user_role (role_name, study_id, status_id, owner_id, date_created, "
                        + "user_name) VALUES ('Investigator', 1, 1, 1, now(), 'manual_dm')");
        // Bound as Data Manager, the higher-ranked role; the Investigator
        // role beside it refuses.
        moveToTheShownArm(boundAs("manual_dm", Role.STUDYDIRECTOR))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(containsString(TREATING)));
        assertEquals(hiddenGroupId, activeArm());
    }

    @Test
    void anInvestigatorDoesNotMoveAParticipant() throws Exception {
        moveToTheShownArm(ClinicalWriteFixtures.sessionAs(DATA_SOURCE, "manual_investigator"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(containsString(TREATING)));
        assertEquals(hiddenGroupId, activeArm());
    }

    @Test
    void aCoordinatorDoesNotMoveAParticipant() throws Exception {
        moveToTheShownArm(ClinicalWriteFixtures.sessionAs(DATA_SOURCE, "manual_crc"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(containsString(TREATING)));
        assertEquals(hiddenGroupId, activeArm());
    }

    @Test
    void anAdministratorMovesAParticipant() throws Exception {
        moveToTheShownArm(ClinicalWriteFixtures.sessionAs(DATA_SOURCE, "manual_admin"))
                .andExpect(status().isOk());
        assertEquals(shownGroupId, activeArm());
    }

    /* ------------------------------------------------------------------ */

    private org.springframework.test.web.servlet.ResultActions moveToTheShownArm(MockHttpSession session)
            throws Exception {
        return mvc().perform(put("/api/v1/subjects/" + subjectOid + "/groups")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"assignments\":[{\"groupClassId\":" + armClassId
                        + ",\"groupId\":" + shownGroupId + "}]}")
                .session(session));
    }

    private MockMvc mvc() {
        return ProductionMvc.standalone(new SubjectsApiController(DATA_SOURCE,
                        Mockito.mock(SecurityManager.class), new SiteVisibilityFilter(DATA_SOURCE)))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    /** {@code userName}'s session bound as {@code role}, as the study picker binds the highest-ranked. */
    private static MockHttpSession boundAs(String userName, Role role) {
        MockHttpSession session = ClinicalWriteFixtures.sessionAs(DATA_SOURCE, userName);
        StudyUserRoleBean binding = new StudyUserRoleBean();
        binding.setRole(role);
        binding.setStudyId(1);
        binding.setUserName(userName);
        session.setAttribute("userRole", binding);
        return session;
    }

    /** The participant's active group in the arm class; 0 when none. */
    private int activeArm() throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT study_group_id FROM subject_group_map "
                             + " WHERE study_subject_id = ? AND study_group_class_id = ? AND status_id = 1")) {
            ps.setInt(1, studySubjectId);
            ps.setInt(2, armClassId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private static int insertReturningId(Connection c, String sql) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
