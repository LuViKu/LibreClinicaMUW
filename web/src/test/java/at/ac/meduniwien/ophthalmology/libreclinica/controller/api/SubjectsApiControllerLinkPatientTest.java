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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.sql.DataSource;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

/**
 * App-feedback Wave 1B (2026-06-19) — MockMvc IT for
 * {@code POST /api/v1/study-subjects/{id}/link-patient}.
 *
 * <p>Covers the structural session/role guards (401, 403, 400) plus
 * the four UUID-resolution branches (both null, source-only, target-only,
 * same, different — 200 × 3 + 409 × 1) by mocking the JDBC chain so
 * each call returns deterministic row state for the two {@code SELECT}s
 * and we count the INSERT prepareStatement requests to confirm an audit
 * row per updated study_subject.
 *
 * <p>The pattern uses two {@link PreparedStatement} mocks indexed by
 * the SQL prefix the controller hands in — the first is the SELECT for
 * row identity + current patient_uuid; the second is the per-UPDATE +
 * per-audit-insert sequence. We capture every {@code prepareStatement}
 * call and route it to the matching mock by sniffing the SQL.
 */
class SubjectsApiControllerLinkPatientTest extends AbstractApiControllerTest {

    /* ---------------------------------------------------------------- */
    /* Test fixtures                                                    */
    /* ---------------------------------------------------------------- */

    /**
     * Build the controller against a DataSource pre-wired to return the
     * supplied {@code (sourcePatientUuid, targetPatientUuid)} pair on
     * the two SELECT lookups (path id then target id). Subsequent
     * {@code prepareStatement} calls (UPDATE + audit INSERT) are routed
     * to a generic mock whose {@code executeUpdate} returns 1.
     *
     * <p>{@link Capture} collects the SQL strings handed to the
     * connection so the tests can count INSERT / UPDATE calls.
     *
     * @param sourceUuid current patient_uuid for the path id row
     *                    (null when the row has no link yet)
     * @param targetUuid current patient_uuid for the target id row
     */
    private MockMvcWithCapture mockMvcWith(String sourceUuid, String targetUuid,
                                            int sourceStudyId, int targetStudyId,
                                            Set<Integer> visibleStudyIds) throws Exception {
        DataSource ds = Mockito.mock(DataSource.class);
        Connection conn = Mockito.mock(Connection.class);
        Mockito.when(ds.getConnection()).thenReturn(conn);

        // Capture every SQL string the controller hands to the
        // connection so the test can assert INSERT counts after the
        // request. A separate mock PreparedStatement per call ensures
        // independent ResultSet state per SELECT.
        Capture capture = new Capture();
        Mockito.when(conn.prepareStatement(Mockito.anyString()))
                .thenAnswer(inv -> {
                    String sql = inv.getArgument(0);
                    capture.statements.add(sql);
                    PreparedStatement ps = Mockito.mock(PreparedStatement.class);
                    if (sql.startsWith("SELECT")) {
                        ResultSet rs = Mockito.mock(ResultSet.class);
                        Mockito.when(ps.executeQuery()).thenReturn(rs);
                        // Index: which SELECT this is — first wired to
                        // the source row, second to the target.
                        int selectIndex = capture.selectCount++;
                        Mockito.when(rs.next()).thenReturn(true).thenReturn(false);
                        if (selectIndex == 0) {
                            Mockito.when(rs.getInt("study_subject_id")).thenReturn(11);
                            Mockito.when(rs.getInt("study_id")).thenReturn(sourceStudyId);
                            Mockito.when(rs.getString("label")).thenReturn("M-001");
                            Mockito.when(rs.getInt("status_id")).thenReturn(1);
                            Mockito.when(rs.getString("patient_uuid")).thenReturn(sourceUuid);
                        } else {
                            Mockito.when(rs.getInt("study_subject_id")).thenReturn(22);
                            Mockito.when(rs.getInt("study_id")).thenReturn(targetStudyId);
                            Mockito.when(rs.getString("label")).thenReturn("GA-008");
                            Mockito.when(rs.getInt("status_id")).thenReturn(1);
                            Mockito.when(rs.getString("patient_uuid")).thenReturn(targetUuid);
                        }
                    } else {
                        // UPDATE or INSERT — executeUpdate returns 1.
                        Mockito.when(ps.executeUpdate()).thenReturn(1);
                    }
                    return ps;
                });

        SiteVisibilityFilter visibility = Mockito.mock(SiteVisibilityFilter.class);
        Mockito.when(visibility.visibleStudyIds(
                        Mockito.any(),
                        Mockito.any(),
                        Mockito.any()))
                .thenReturn(visibleStudyIds);

        MockMvc mvc = mockMvcFor(
                new StudySubjectLinkPatientController(ds, visibility));
        return new MockMvcWithCapture(mvc, capture, ds);
    }

    /** Default — both rows visible under study_id=1. */
    private MockMvcWithCapture mockMvcWith(String sourceUuid, String targetUuid) throws Exception {
        return mockMvcWith(sourceUuid, targetUuid,
                /* sourceStudyId */ 1,
                /* targetStudyId */ 1,
                Set.of(1));
    }

    private MockHttpSession dmSession() {
        return (MockHttpSession) authenticatedSessionWithRole(
                7, "dm", 1, "S_DEFAULTS1", "Default Study",
                Role.STUDYDIRECTOR, /* roleStudyId */ 1);
    }

    /* ---------------------------------------------------------------- */
    /* Auth + body validation                                           */
    /* ---------------------------------------------------------------- */

    @Test
    void linkReturns401WhenAnonymous() throws Exception {
        MockMvcWithCapture h = mockMvcWith(null, null);
        h.mvc.perform(post("/api/v1/study-subjects/11/link-patient")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetSubjectId\":22}")
                        .session((MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void linkReturns403WhenInvestigatorAttempts() throws Exception {
        MockMvcWithCapture h = mockMvcWith(null, null);
        h.mvc.perform(post("/api/v1/study-subjects/11/link-patient")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetSubjectId\":22}")
                        .session((MockHttpSession) authenticatedSessionWithRole(
                                2, "phys", 1, "S_DEFAULTS1", "Default Study",
                                Role.INVESTIGATOR, 1)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message")
                        .value(containsString("does not permit link-patient")));
    }

    @Test
    void linkReturns400WhenBodyMissing() throws Exception {
        MockMvcWithCapture h = mockMvcWith(null, null);
        h.mvc.perform(post("/api/v1/study-subjects/11/link-patient")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .session(dmSession()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("targetSubjectId is required")));
    }

    @Test
    void linkReturns400WhenTargetSubjectIdBlank() throws Exception {
        MockMvcWithCapture h = mockMvcWith(null, null);
        h.mvc.perform(post("/api/v1/study-subjects/11/link-patient")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetSubjectId\":0}")
                        .session(dmSession()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("targetSubjectId is required")));
    }

    @Test
    void linkReturns400WhenSelfLink() throws Exception {
        MockMvcWithCapture h = mockMvcWith(null, null);
        h.mvc.perform(post("/api/v1/study-subjects/11/link-patient")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetSubjectId\":11}")
                        .session(dmSession()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("must differ from the path id")));
    }

    @Test
    void linkReturns403WhenTargetOutsideVisibleStudies() throws Exception {
        // Source in visible study 1; target in study 2 which is hidden.
        MockMvcWithCapture h = mockMvcWith(null, null,
                /* sourceStudyId */ 1,
                /* targetStudyId */ 2,
                /* visibleStudyIds */ Set.of(1));
        h.mvc.perform(post("/api/v1/study-subjects/11/link-patient")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetSubjectId\":22}")
                        .session(dmSession()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message")
                        .value(containsString("Target study_subject 22")));
    }

    /* ---------------------------------------------------------------- */
    /* UUID-resolution branches                                         */
    /* ---------------------------------------------------------------- */

    @Test
    void link200GeneratesFreshUuidWhenBothNull() throws Exception {
        MockMvcWithCapture h = mockMvcWith(null, null);
        h.mvc.perform(post("/api/v1/study-subjects/11/link-patient")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetSubjectId\":22}")
                        .session(dmSession()))
                .andExpect(status().isOk())
                // Body shape: { patientUuid: "<36-char uuid>" }
                .andExpect(jsonPath("$.patientUuid").isString());
        // Both rows updated + one audit row each.
        assertCounts(h.capture, /* updates */ 2, /* auditInserts */ 2);
    }

    @Test
    void link200CopiesSourceUuidWhenTargetNull() throws Exception {
        String src = "11111111-1111-1111-1111-111111111111";
        MockMvcWithCapture h = mockMvcWith(src, null);
        h.mvc.perform(post("/api/v1/study-subjects/11/link-patient")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetSubjectId\":22}")
                        .session(dmSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.patientUuid").value(src));
        // Only the target row was updated; one audit row.
        assertCounts(h.capture, /* updates */ 1, /* auditInserts */ 1);
    }

    @Test
    void link200CopiesTargetUuidWhenSourceNull() throws Exception {
        String tgt = "22222222-2222-2222-2222-222222222222";
        MockMvcWithCapture h = mockMvcWith(null, tgt);
        h.mvc.perform(post("/api/v1/study-subjects/11/link-patient")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetSubjectId\":22}")
                        .session(dmSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.patientUuid").value(tgt));
        // Only the source row was updated; one audit row.
        assertCounts(h.capture, /* updates */ 1, /* auditInserts */ 1);
    }

    @Test
    void link200IdempotentWhenBothShareUuid() throws Exception {
        String shared = "33333333-3333-3333-3333-333333333333";
        MockMvcWithCapture h = mockMvcWith(shared, shared);
        h.mvc.perform(post("/api/v1/study-subjects/11/link-patient")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetSubjectId\":22}")
                        .session(dmSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.patientUuid").value(shared));
        // No UPDATEs, no audit inserts — idempotent.
        assertCounts(h.capture, /* updates */ 0, /* auditInserts */ 0);
    }

    @Test
    void linkReturns409WhenBothCarryDifferentUuids() throws Exception {
        MockMvcWithCapture h = mockMvcWith(
                "44444444-4444-4444-4444-444444444444",
                "55555555-5555-5555-5555-555555555555");
        h.mvc.perform(post("/api/v1/study-subjects/11/link-patient")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetSubjectId\":22}")
                        .session(dmSession()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value(containsString("already linked to different patients")));
        // 409 short-circuits before any write.
        assertCounts(h.capture, /* updates */ 0, /* auditInserts */ 0);
    }

    /* ---------------------------------------------------------------- */
    /* Helpers                                                          */
    /* ---------------------------------------------------------------- */

    private static void assertCounts(Capture cap, int expectedUpdates, int expectedAudits) {
        int updates = 0;
        int audits  = 0;
        for (String sql : cap.statements) {
            if (sql.startsWith("UPDATE study_subject SET patient_uuid")) updates++;
            else if (sql.startsWith("INSERT INTO audit_log_event")) audits++;
        }
        org.junit.jupiter.api.Assertions.assertEquals(expectedUpdates, updates,
                "UPDATE count mismatch — captured SQL: " + cap.statements);
        org.junit.jupiter.api.Assertions.assertEquals(expectedAudits, audits,
                "audit INSERT count mismatch — captured SQL: " + cap.statements);
    }

    /** Collects every SQL string the controller prepareStatement-ed. */
    private static final class Capture {
        final List<String> statements = new ArrayList<>();
        int selectCount = 0;
    }

    /** Test fixture wrapper — MockMvc paired with the captured SQL log. */
    private static final class MockMvcWithCapture {
        final MockMvc mvc;
        final Capture capture;
        @SuppressWarnings("unused")
        final DataSource ds;

        MockMvcWithCapture(MockMvc mvc, Capture capture, DataSource ds) {
            this.mvc = mvc;
            this.capture = capture;
            this.ds = ds;
        }
    }
}
