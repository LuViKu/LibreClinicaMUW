/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RemoteRetinalInferenceClient;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RetinalArtifactStorageService;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RetinalJobStatusBroadcaster;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.StudySubjectFinder;

/**
 * P2-7 — trial blinding, enforced at the endpoint.
 *
 * <p>The predicate's truth table is pinned next door. This asserts the thing
 * that actually protects the study: that the response body a treating
 * clinician receives for a control-arm subject contains no AI-derived numbers.
 * The SPA hides those panels too, but a pasted URL bypasses the SPA entirely,
 * so the server is the enforcement point and has to be tested as one.
 *
 * <p>What must survive the masking matters as much: the unannotated scan stays
 * available, because the clinician is still treating this patient and the
 * blinding is about the AI's opinion, not about the images.
 */
class RetinalBlindingEndpointDatabaseIT extends AbstractApiControllerDatabaseIT {

    private long jobId;
    private int studySubjectId;
    private int eventCrfId;
    private int studyId;
    private int hiddenGroupId;
    private int hiddenGroupClassId;
    private boolean seeded;

    /**
     * Builds the arm fixture rather than relying on a seed.
     *
     * <p>The AI arms ship in a demo-context seed on a study that a fresh test
     * database need not have, and a blinding test that silently skips when its
     * fixture is absent protects nothing. So it creates its own group class and
     * control arm on the seeded default study, and removes them afterwards.
     */
    @BeforeEach
    void seedFixture() throws Exception {
        try (Connection c = DATA_SOURCE.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT ss.study_id, ss.study_subject_id, ec.event_crf_id "
                            + "  FROM study_subject ss "
                            + "  JOIN study_event se ON se.study_subject_id = ss.study_subject_id "
                            + "  JOIN event_crf ec ON ec.study_event_id = se.study_event_id "
                            + " WHERE ss.status_id NOT IN (5, 7) AND ec.status_id NOT IN (5, 7) "
                            + " ORDER BY ec.event_crf_id LIMIT 1");
                 ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "the seed should provide a subject with a CRF instance");
                studyId = rs.getInt(1);
                studySubjectId = rs.getInt(2);
                eventCrfId = rs.getInt(3);
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO study_group_class "
                            + "(name, study_id, owner_id, date_created, group_class_type_id, status_id, "
                            + " subject_assignment) "
                            + "VALUES ('Blinding IT arm', ?, 1, NOW(), 3, 1, 'Required') "
                            + "RETURNING study_group_class_id")) {
                ps.setInt(1, studyId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    hiddenGroupClassId = rs.getInt(1);
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO study_group (name, description, study_group_class_id, allocation_weight) "
                            + "VALUES ('AI_HIDDEN', 'Blinding IT control arm', ?, 1) "
                            + "RETURNING study_group_id")) {
                ps.setInt(1, hiddenGroupClassId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    hiddenGroupId = rs.getInt(1);
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO retinal_inference_job "
                            + "(event_crf_id, task, e2e_path, eye_laterality, status, enqueued_at, completed_at) "
                            + "VALUES (?, 'fluid', '/tmp/blind-it.e2e', 'OD', 'done', NOW(), NOW()) "
                            + "RETURNING job_id")) {
                ps.setInt(1, eventCrfId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    jobId = rs.getLong(1);
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO retinal_inference_result "
                            + "(job_id, task, output_payload, primary_metric_value, primary_metric_unit, "
                            + " confidence, created_at) "
                            + "VALUES (?, 'fluid', ?::jsonb, 0.05, 'mm3', 0.91, NOW())")) {
                ps.setLong(1, jobId);
                ps.setString(2, "{\"irf_mm3\": 0.05}");
                ps.executeUpdate();
            }
        }
        seeded = true;
    }

    @AfterEach
    void cleanUp() throws Exception {
        if (!seeded) return;
        exec("DELETE FROM subject_group_map WHERE study_group_id = " + hiddenGroupId);
        exec("DELETE FROM retinal_inference_result WHERE job_id = " + jobId);
        exec("DELETE FROM retinal_inference_job WHERE job_id = " + jobId);
        exec("DELETE FROM study_group WHERE study_group_id = " + hiddenGroupId);
        exec("DELETE FROM study_group_class WHERE study_group_class_id = " + hiddenGroupClassId);
        seeded = false;
    }

    private void exec(String sql) throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }

    /** Puts the fixture subject in the control arm. */
    private void assignToHiddenArm() throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO subject_group_map "
                             + "(study_group_class_id, study_subject_id, study_group_id, status_id, "
                             + " owner_id, date_created) VALUES (?, ?, ?, 1, 1, NOW())")) {
            ps.setInt(1, hiddenGroupClassId);
            ps.setInt(2, studySubjectId);
            ps.setInt(3, hiddenGroupId);
            ps.executeUpdate();
        }
    }

    private MockMvc mockMvc() {
        SiteVisibilityFilter filter = Mockito.mock(SiteVisibilityFilter.class);
        Mockito.when(filter.visibleStudyIds(Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(java.util.Set.of(studyId));
        RemoteRetinalInferenceClient remote = Mockito.mock(RemoteRetinalInferenceClient.class);
        Mockito.when(remote.isConfigured()).thenReturn(false);
        return MockMvcBuilders.standaloneSetup(
                new RetinalResultsApiController(
                        DATA_SOURCE, filter, Mockito.mock(RetinalArtifactStorageService.class),
                        new StudySubjectFinder(DATA_SOURCE), remote,
                        new RetinalJobStatusBroadcaster(), null))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private MockHttpSession sessionAs(Role role) {
        MockHttpSession session = new MockHttpSession();
        UserAccountBean ub = new UserAccountBean();
        ub.setId(1);
        ub.setName("root");
        session.setAttribute("userBean", ub);
        StudyBean study = new StudyBean();
        study.setId(studyId);
        study.setOid("S_ARM_IT");
        session.setAttribute("study", study);
        StudyUserRoleBean r = new StudyUserRoleBean();
        r.setRole(role);
        session.setAttribute("userRole", r);
        return session;
    }

    /* ---------------- the enforcement ---------------- */

    @Test
    void aTreatingClinicianOnAControlArmSubjectGetsNoAiOutput() throws Exception {
        assignToHiddenArm();

        mockMvc().perform(get("/api/v1/retinal-jobs/" + jobId).session(sessionAs(Role.INVESTIGATOR)))
                .andExpect(status().isOk())
                // The quantification is the thing the trial is blinding.
                .andExpect(jsonPath("$.outputPayload").isEmpty())
                .andExpect(jsonPath("$.primaryMetric").doesNotExist())
                .andExpect(jsonPath("$.confidence").doesNotExist())
                .andExpect(jsonPath("$.artifactNames").isEmpty());
    }

    /**
     * The clinician is still treating this patient. Blinding withholds the
     * AI's opinion, not the images, so the raw scan has to survive the masking.
     */
    @Test
    void theUnannotatedScanSurvivesTheMasking() throws Exception {
        assignToHiddenArm();

        mockMvc().perform(get("/api/v1/retinal-jobs/" + jobId).session(sessionAs(Role.INVESTIGATOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value((int) jobId))
                .andExpect(jsonPath("$.status").value("done"))
                .andExpect(jsonPath("$.laterality").value("OD"));
    }

    /** A coordinator treats patients too, so the same masking applies. */
    @Test
    void aCoordinatorIsBlindedAsWell() throws Exception {
        assignToHiddenArm();

        mockMvc().perform(get("/api/v1/retinal-jobs/" + jobId).session(sessionAs(Role.COORDINATOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outputPayload").isEmpty());
    }

    /**
     * A data manager is not treating the patient, so seeing the output cannot
     * influence a treatment decision — and they need it to monitor the study.
     */
    @Test
    void aDataManagerSeesTheOutput() throws Exception {
        assignToHiddenArm();

        mockMvc().perform(get("/api/v1/retinal-jobs/" + jobId).session(sessionAs(Role.STUDYDIRECTOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outputPayload").isNotEmpty())
                .andExpect(jsonPath("$.confidence").value(0.91));
    }

    /** Without an arm assignment there is nothing to blind. */
    @Test
    void anUnassignedSubjectIsNotMasked() throws Exception {
        mockMvc().perform(get("/api/v1/retinal-jobs/" + jobId).session(sessionAs(Role.INVESTIGATOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outputPayload").isNotEmpty());
    }

    @Test
    void theFixtureItselfIsSound() {
        assertNotNull(Long.valueOf(jobId));
        assertTrue(jobId > 0 && studySubjectId > 0 && eventCrfId > 0,
                "fixture ids should all resolve");
    }
}
