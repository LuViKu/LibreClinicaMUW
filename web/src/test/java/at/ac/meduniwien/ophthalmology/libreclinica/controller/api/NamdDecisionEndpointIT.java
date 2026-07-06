/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.UserType;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;
import at.ac.meduniwien.ophthalmology.libreclinica.service.crf.CrfFileStorageService;
import at.ac.meduniwien.ophthalmology.libreclinica.service.crf.EventCrfPresenceRegistry;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RemoteRetinalInferenceClient;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RetinalArtifactStorageService;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RetinalJobStatusBroadcaster;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RetinalResultItemDataPopulator;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.StudySubjectFinder;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 2026-06-30 — IT for the nAMD treat-and-extend treatment-decision
 * write path + the new clinical-flags timeline endpoint.
 *
 * <p>Seeds one event_crf row (event_crf_id=9001) on
 * crf_version_id=21 (the F_NAMD_VISIT seed) for study_subject 1
 * (M-001) on a freshly-minted study_event (study_event_id=9001).
 * Each test exercises a POST against
 * {@code /api/v1/eventCrfs/{id}/items} carrying a slice of the
 * new I_NAMD_DECISION_* / I_NAMD_AI_* / NAMD clinical-flag OIDs
 * and asserts:
 *
 * <ul>
 *   <li>Item-data rows are created/updated.</li>
 *   <li>One TREATMENT_DECISION_RECORDED audit_log_event row is
 *       emitted (id=124) ONLY when at least one decision-significant
 *       item changed.</li>
 *   <li>The /namd-clinical-flags-timeline GET surfaces the seeded
 *       per-eye hemorrhage / BCVA-loss-attribution flags grouped by
 *       study_event_id.</li>
 * </ul>
 */
class NamdDecisionEndpointIT extends AbstractApiControllerDatabaseIT {

    private static final int EVENT_CRF_ID = 9001;
    private static final int STUDY_EVENT_ID = 9001;
    private static final int STUDY_SUBJECT_ID = 1;
    private static final int CRF_VERSION_ID = 21;

    @BeforeEach
    void seedEventCrf() throws Exception {
        // The base seed (lc-muw-2026-06-01-seed-demo-data) attaches
        // study_event #21 to subject 1 already (via study_event_definition
        // #1 — the Demographics V3). We reuse subject 1's existing
        // event_definition by minting a fresh study_event on
        // study_event_definition_id=2 (V2) and pinning a new
        // event_crf to F_NAMD_VISIT v1 (crf_version_id=21).
        Timestamp now = Timestamp.from(java.time.Instant.now());
        try (Connection c = DATA_SOURCE.getConnection()) {
            c.setAutoCommit(true);
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO study_event ("
                            + "study_event_id, study_event_definition_id, study_subject_id, "
                            + "sample_ordinal, location, date_start, status_id, owner_id, "
                            + "date_created, subject_event_status_id) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                ps.setInt(1, STUDY_EVENT_ID);
                ps.setInt(2, 2); // V2 definition (any active def is fine — we attach our own event_crf)
                ps.setInt(3, STUDY_SUBJECT_ID);
                ps.setInt(4, 99); // sentinel ordinal — well clear of the seeded 1..3 range
                ps.setString(5, "");
                ps.setTimestamp(6, now);
                ps.setInt(7, 1);
                ps.setInt(8, 1);
                ps.setTimestamp(9, now);
                ps.setInt(10, 1); // scheduled
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO event_crf ("
                            + "event_crf_id, study_event_id, crf_version_id, "
                            + "completion_status_id, status_id, owner_id, "
                            + "date_created, study_subject_id, "
                            + "electronic_signature_status, sdv_status) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, false, false)")) {
                ps.setInt(1, EVENT_CRF_ID);
                ps.setInt(2, STUDY_EVENT_ID);
                ps.setInt(3, CRF_VERSION_ID);
                ps.setInt(4, 1);
                ps.setInt(5, 1);
                ps.setInt(6, 1);
                ps.setTimestamp(7, now);
                ps.setInt(8, STUDY_SUBJECT_ID);
                ps.executeUpdate();
            }
        }
    }

    @AfterEach
    void cleanup() throws Exception {
        try (Connection c = DATA_SOURCE.getConnection()) {
            // Order: audit_log_event -> item_data -> event_crf -> study_event
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM audit_log_event WHERE audit_table = 'event_crf' AND entity_id = ?")) {
                ps.setInt(1, EVENT_CRF_ID);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM audit_log_event WHERE audit_table = 'item_data' "
                            + "AND entity_id IN (SELECT item_data_id FROM item_data WHERE event_crf_id = ?)")) {
                ps.setInt(1, EVENT_CRF_ID);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM item_data WHERE event_crf_id = ?")) {
                ps.setInt(1, EVENT_CRF_ID);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM event_crf WHERE event_crf_id = ?")) {
                ps.setInt(1, EVENT_CRF_ID);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM study_event WHERE study_event_id = ?")) {
                ps.setInt(1, STUDY_EVENT_ID);
                ps.executeUpdate();
            }
        }
    }

    private MockMvc buildEventCrfsMockMvc() {
        return MockMvcBuilders.standaloneSetup(
                new EventCrfsApiController(
                        DATA_SOURCE,
                        new SiteVisibilityFilter(DATA_SOURCE),
                        Mockito.mock(CrfFileStorageService.class),
                        new EventCrfPresenceRegistry(),
                        new RetinalResultItemDataPopulator(DATA_SOURCE)))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private MockMvc buildRetinalResultsMockMvc() {
        RemoteRetinalInferenceClient remoteClient = Mockito.mock(RemoteRetinalInferenceClient.class);
        Mockito.when(remoteClient.isConfigured()).thenReturn(false);
        return MockMvcBuilders.standaloneSetup(
                new RetinalResultsApiController(
                        DATA_SOURCE,
                        new SiteVisibilityFilter(DATA_SOURCE),
                        new RetinalArtifactStorageService(),
                        new StudySubjectFinder(DATA_SOURCE),
                        remoteClient,
                        new RetinalJobStatusBroadcaster(),
                        null))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private MockHttpSession sysadminSession() {
        MockHttpSession s = new MockHttpSession();
        UserAccountBean ub = new UserAccountBean();
        ub.setId(1);
        ub.setName("root");
        ub.addUserType(UserType.SYSADMIN);
        s.setAttribute("userBean", ub);
        StudyBean study = new StudyBean();
        study.setId(1);
        study.setOid("default-study");
        s.setAttribute("study", study);
        StudyUserRoleBean role = new StudyUserRoleBean();
        role.setRole(Role.STUDYDIRECTOR);
        s.setAttribute("userRole", role);
        return s;
    }

    /**
     * Decision items land → one TREATMENT_DECISION_RECORDED audit row
     * (type 124) is emitted with the packed summary string AND the
     * matching item_data rows exist.
     */
    @Test
    void saveDecisionItems_emitsSummaryAuditRowOnly() throws Exception {
        String body = "{"
                + "\"values\": {"
                + "  \"I_NAMD_DECISION_ACTION\": \"TREAT\","
                + "  \"I_NAMD_DECISION_DRUG\": \"AFLIBERCEPT\","
                + "  \"I_NAMD_DECISION_INTERVAL_WEEKS\": \"8\","
                + "  \"I_NAMD_DECISION_RATIONALE_CODE\": \"ACTIVE_DISEASE_TREAT\","
                + "  \"I_NAMD_AI_AGREED\": \"true\""
                + "}}";

        buildEventCrfsMockMvc().perform(post("/api/v1/eventCrfs/" + EVENT_CRF_ID + "/items")
                .session(sysadminSession())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.savedItemCount").value(5));

        try (Connection c = DATA_SOURCE.getConnection()) {
            // item_data — five rows for the five OIDs.
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COUNT(*) FROM item_data WHERE event_crf_id = ?"
                            + " AND value <> ''")) {
                ps.setInt(1, EVENT_CRF_ID);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    assertEquals(5, rs.getInt(1), "expected 5 item_data rows");
                }
            }
            // audit — exactly one type-124 summary row keyed on event_crf.
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT new_value FROM audit_log_event "
                            + "WHERE audit_table = 'event_crf' AND entity_id = ? "
                            + "AND audit_log_event_type_id = 124")) {
                ps.setInt(1, EVENT_CRF_ID);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "expected one TREATMENT_DECISION_RECORDED row");
                    String summary = rs.getString(1);
                    assertNotNull(summary);
                    assertTrue(summary.contains("action:TREAT"),
                            "summary should contain action:TREAT, was: " + summary);
                    assertTrue(summary.contains("drug:AFLIBERCEPT"),
                            "summary should contain drug:AFLIBERCEPT, was: " + summary);
                    assertTrue(summary.contains("interval:8"),
                            "summary should contain interval:8, was: " + summary);
                    assertTrue(summary.contains("agreedWithAi:true"),
                            "summary should contain agreedWithAi:true, was: " + summary);
                    assertTrue(summary.contains("rationale:ACTIVE_DISEASE_TREAT"),
                            "summary should contain the rationale code, was: " + summary);
                    assertEquals(false, rs.next(), "expected exactly one type-124 row");
                }
            }
        }
    }

    /**
     * No NAMD_DECISION_* items in the request body → NO summary
     * audit row emitted (the per-item type-1 rows are still emitted
     * by the existing path; this test only asserts the type-124
     * row is absent).
     */
    @Test
    void saveNonDecisionItems_doesNotEmitSummaryAuditRow() throws Exception {
        // Use the clinical-flag OIDs from the new section; they're not
        // part of the decision summary, so no type-124 row should
        // surface.
        String body = "{"
                + "\"values\": {"
                + "  \"I_NAMD_OD_NEW_HEMORRHAGE\": \"true\""
                + "}}";

        buildEventCrfsMockMvc().perform(post("/api/v1/eventCrfs/" + EVENT_CRF_ID + "/items")
                .session(sysadminSession())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andDo(print())
                .andExpect(status().isOk());

        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM audit_log_event "
                             + "WHERE audit_table = 'event_crf' AND entity_id = ? "
                             + "AND audit_log_event_type_id = 124")) {
            ps.setInt(1, EVENT_CRF_ID);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertEquals(0, rs.getInt(1),
                        "non-decision save must NOT emit a type-124 row");
            }
        }
    }

    /**
     * Clinical-flags timeline endpoint folds the per-eye boolean
     * item_data rows into a {studyEventId, od, os} shape.
     */
    @Test
    void clinicalFlagsTimeline_returnsPerEyeBooleans() throws Exception {
        // Seed: hemorrhage on OD, BCVA-loss-attributed on OS at the
        // freshly-minted study_event.
        String body = "{"
                + "\"values\": {"
                + "  \"I_NAMD_OD_NEW_HEMORRHAGE\": \"true\","
                + "  \"I_NAMD_OS_BCVA_LOSS_NAMD_ATTRIBUTED\": \"true\""
                + "}}";

        buildEventCrfsMockMvc().perform(post("/api/v1/eventCrfs/" + EVENT_CRF_ID + "/items")
                .session(sysadminSession())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andDo(print())
                .andExpect(status().isOk());

        buildRetinalResultsMockMvc().perform(
                get("/api/v1/study-subjects/" + STUDY_SUBJECT_ID + "/namd-clinical-flags")
                        .session(sysadminSession()))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].studyEventId").value(STUDY_EVENT_ID))
                .andExpect(jsonPath("$[0].od.hemorrhage").value(true))
                .andExpect(jsonPath("$[0].od.bcvaLossAttributedToNamd").value(false))
                .andExpect(jsonPath("$[0].os.hemorrhage").value(false))
                .andExpect(jsonPath("$[0].os.bcvaLossAttributedToNamd").value(true));
    }
}
