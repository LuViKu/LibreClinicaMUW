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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.UserType;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RemoteRetinalInferenceClient;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RetinalArtifactStorageService;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RetinalJobStatusBroadcaster;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.StudySubjectFinder;

import org.springframework.mock.web.MockHttpSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Phase E.7 Wave 3 — happy-path IT against Testcontainers Postgres
 * for the read-side retinal API.
 *
 * <p>Seeds {@code retinal_inference_job} + {@code retinal_inference_result}
 * rows directly via JDBC (the upload pipeline is exercised separately
 * by {@code RetinalInferenceApiController}); these tests pin the
 * SPA-facing shape of the GET endpoints.
 *
 * <p>The fixture artifact directory lives under a JUnit-managed temp
 * path; an override of
 * {@link RetinalArtifactStorageService#bscanStorePath()} parks the
 * companion files (bscan.dcm/fundus.png/geometry.json) where the
 * resolver expects them.
 */
@SuppressWarnings("null")
class RetinalResultsApiControllerDatabaseIT extends AbstractApiControllerDatabaseIT {

    /** Stable seg-dir + bscan-dir roots created per test method. */
    private Path tmpRoot;
    private Path segDir;
    private Path bscanRoot;
    private static final String E2E_UUID = "11111111-2222-3333-4444-555555555555";

    private RetinalArtifactStorageService artifactStore;
    private SiteVisibilityFilter visibilityFilter;

    @BeforeEach
    void seed() throws Exception {
        tmpRoot = Files.createTempDirectory("retinal-it-");
        segDir = Files.createDirectories(tmpRoot.resolve("seg"));
        bscanRoot = Files.createDirectories(tmpRoot.resolve("bscan-store"));

        // Seg artifacts: one csv, one npz placeholder. The CSV gets streamed
        // by streamArtifact_servesCsvWithCorrectContentType.
        Files.writeString(segDir.resolve("retina-thickness.csv"),
                "x,y,thickness_um\n0,0,260.5\n", StandardCharsets.UTF_8);
        Files.write(segDir.resolve("fluidseg.npz"), new byte[]{0x50, 0x4B, 0x03, 0x04});

        // Companion files under <bscanRoot>/<e2eUuid>/.
        Path companionDir = Files.createDirectories(bscanRoot.resolve(E2E_UUID));
        Files.write(companionDir.resolve("bscan.dcm"), new byte[]{(byte) 0xDE, (byte) 0xAD});
        Files.write(companionDir.resolve("fundus.png"), new byte[]{(byte) 0x89, 'P', 'N', 'G'});
        Files.writeString(companionDir.resolve("geometry.json"),
                "{\"axial_mm\":0.004,\"lateral_mm\":0.011}", StandardCharsets.UTF_8);

        // Override the artifact-store bscan-root so the IT doesn't touch
        // CoreResources / production /var/lib paths.
        final String bscanRootStr = bscanRoot.toString();
        artifactStore = new RetinalArtifactStorageService() {
            @Override
            protected String bscanStorePath() {
                return bscanRootStr;
            }
        };

        visibilityFilter = new SiteVisibilityFilter(DATA_SOURCE);

        seedJobAndResult(/* jobId */ 9001L, /* eventCrfId */ 1,
                "fluid", "OD", "done", "retinal-fluid-v1",
                /* primary */ new BigDecimal("12.3400"), "mm³",
                /* payload */ "{\"biomarkers\":{\"irf_mm3\":1.5,\"srf_mm3\":2.2,"
                        + "\"ped_mm3\":0.6,\"total_mm3\":4.3},\"geometry\":\"present\"}",
                /* e2eUuidPath */ "/tmp/" + E2E_UUID + ".e2e",
                /* bscanMasksDir */ segDir.toString(),
                /* confidence */ 0.87);
        // Older job on the same event_crf so the list endpoint can verify
        // enqueue-desc ordering (older first INSERT → smaller enqueued_at).
        seedJobAndResult(/* jobId */ 8999L, /* eventCrfId */ 1,
                "onl", "OS", "done", "retinal-onl-v1",
                new BigDecimal("84.5000"), "µm",
                "{\"biomarkers\":{\"onl_um_mean\":84.5}}",
                "/tmp/older.e2e",
                segDir.toString(),
                0.78);
    }

    @AfterEach
    void cleanup() throws Exception {
        // Drop the seeded rows so later tests in the same class don't see
        // stale data through the unique (job_id) result FK.
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM retinal_inference_result WHERE job_id IN (?, ?, ?)")) {
            ps.setLong(1, 9001L);
            ps.setLong(2, 8999L);
            ps.setLong(3, 9100L);
            ps.executeUpdate();
        }
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM retinal_inference_job WHERE job_id IN (?, ?, ?)")) {
            ps.setLong(1, 9001L);
            ps.setLong(2, 8999L);
            ps.setLong(3, 9100L);
            ps.executeUpdate();
        }
        if (tmpRoot != null) {
            // Recursive delete — fine for test fixtures.
            try {
                Files.walk(tmpRoot)
                        .sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) { } });
            } catch (IOException ignored) { /* best-effort */ }
        }
    }

    private void seedJobAndResult(long jobId, int eventCrfId, String task,
                                  String laterality, String status, String modelVersion,
                                  BigDecimal primaryValue, String primaryUnit,
                                  String payloadJson, String e2ePath,
                                  String bscanMasksDir, double confidence) throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO retinal_inference_job ("
                             + "job_id, event_crf_id, task, e2e_path, eye_laterality, "
                             + "status, enqueued_at, completed_at, model_version) "
                             + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setLong(1, jobId);
            ps.setInt(2, eventCrfId);
            ps.setString(3, task);
            ps.setString(4, e2ePath);
            ps.setString(5, laterality);
            ps.setString(6, status);
            // Stagger enqueued_at deterministically by jobId — the previous
            // System.currentTimeMillis() basis raced when two seedJobAndResult
            // calls happened in the same ms, flipping the ordering in CI.
            // Anchor to a fixed past epoch + jobId so higher jobId always
            // means more-recent enqueued_at and the DESC order is stable.
            long anchorMs = 1_750_000_000_000L; // arbitrary fixed past instant
            ps.setTimestamp(7, new Timestamp(anchorMs + jobId));
            ps.setTimestamp(8, new Timestamp(anchorMs + jobId + 100L));
            ps.setString(9, modelVersion);
            ps.executeUpdate();
        }
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO retinal_inference_result ("
                             + "job_id, task, output_payload, primary_metric_value, "
                             + "primary_metric_unit, bscan_masks_dir, confidence) "
                             + "VALUES (?, ?, ?::jsonb, ?, ?, ?, ?)")) {
            ps.setLong(1, jobId);
            ps.setString(2, task);
            ps.setString(3, payloadJson);
            ps.setBigDecimal(4, primaryValue);
            ps.setString(5, primaryUnit);
            ps.setString(6, bscanMasksDir);
            ps.setBigDecimal(7, BigDecimal.valueOf(confidence));
            ps.executeUpdate();
        }
    }

    private MockMvc buildMockMvc() {
        // Wave 1B (2026-06-18) — wire the full constructor so the new
        // bind / search paths can be exercised end-to-end. The remote
        // client is mocked to "not configured" so the bind path picks
        // the local-queue branch (status=queued, not remote_pending).
        RemoteRetinalInferenceClient remoteClient = Mockito.mock(RemoteRetinalInferenceClient.class);
        Mockito.when(remoteClient.isConfigured()).thenReturn(false);
        return MockMvcBuilders.standaloneSetup(
                new RetinalResultsApiController(
                        DATA_SOURCE, visibilityFilter, artifactStore,
                        new StudySubjectFinder(DATA_SOURCE),
                        remoteClient,
                        new RetinalJobStatusBroadcaster(),
                        // 2026-06-18: inferenceController is the bind→remote
                        // dispatch path; the local-queue branch in this IT
                        // (remoteClient.isConfigured()=false) never reaches
                        // the dispatch, so null is safe.
                        null))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    /**
     * 2026-06-20 B2 — MockMvc variant whose {@link SiteVisibilityFilter}
     * returns an empty visible-set, simulating an operator outside the
     * event_crf's site. Used by the bulk-bind FORBIDDEN-per-row test
     * to confirm the bulk endpoint surfaces per-row visibility refusals
     * instead of failing the whole batch with 403.
     */
    private MockMvc buildMockMvcWithEmptyVisibility() {
        RemoteRetinalInferenceClient remoteClient = Mockito.mock(RemoteRetinalInferenceClient.class);
        Mockito.when(remoteClient.isConfigured()).thenReturn(false);
        SiteVisibilityFilter emptyFilter = Mockito.mock(SiteVisibilityFilter.class);
        Mockito.when(emptyFilter.visibleStudyIds(Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(java.util.Set.of());
        return MockMvcBuilders.standaloneSetup(
                new RetinalResultsApiController(
                        DATA_SOURCE, emptyFilter, artifactStore,
                        new StudySubjectFinder(DATA_SOURCE),
                        remoteClient,
                        new RetinalJobStatusBroadcaster(),
                        null))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    /* ====================================================================== */
    /* GET /retinal-jobs/{jobId}                                              */
    /* ====================================================================== */

    @Test
    void getRetinalJob_returnsPopulatedDtoWhenJobAndResultExist() throws Exception {
        buildMockMvc().perform(get("/api/v1/retinal-jobs/9001")
                .session(authenticatedSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value(9001))
                .andExpect(jsonPath("$.eventCrfId").value(1))
                .andExpect(jsonPath("$.task").value("fluid"))
                .andExpect(jsonPath("$.laterality").value("OD"))
                .andExpect(jsonPath("$.status").value("done"))
                .andExpect(jsonPath("$.modelVersion").value("retinal-fluid-v1"))
                .andExpect(jsonPath("$.e2eUuid").value(E2E_UUID))
                .andExpect(jsonPath("$.primaryMetric.value").value(12.34))
                .andExpect(jsonPath("$.primaryMetric.unit").value("mm³"))
                .andExpect(jsonPath("$.outputPayload.biomarkers.irf_mm3").value(1.5))
                .andExpect(jsonPath("$.confidence").value(0.87))
                .andExpect(jsonPath("$.artifactNames").isArray())
                .andExpect(jsonPath("$.artifactNames[0]").value("fluidseg.npz"))
                .andExpect(jsonPath("$.artifactNames[1]").value("retina-thickness.csv"))
                .andExpect(jsonPath("$.companionNames[0]").value("bscan.dcm"))
                .andExpect(jsonPath("$.companionNames[1]").value("fundus.png"))
                .andExpect(jsonPath("$.companionNames[2]").value("geometry.json"))
                .andExpect(jsonPath("$.fundusUrl").value(
                        "/pages/api/v1/retinal-jobs/9001/artifacts/fundus.png"))
                .andExpect(jsonPath("$.geometryUrl").value(
                        "/pages/api/v1/retinal-jobs/9001/artifacts/geometry.json"))
                .andExpect(jsonPath("$.bscanDcmUrl").value(
                        "/pages/api/v1/retinal-jobs/9001/artifacts/bscan.dcm"));
    }

    @Test
    void getRetinalJob_404OnMissingJob() throws Exception {
        buildMockMvc().perform(get("/api/v1/retinal-jobs/87654321")
                .session(authenticatedSession()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("No retinal_inference_job")));
    }

    @Test
    void getRetinalJob_403WhenSiteVisibilityDenies() throws Exception {
        // Insert a job with NULL event_crf_id — exercises the controller's
        // "no study chain" branch which treats an unresolvable study as
        // out-of-visibility (403). The column was relaxed from NOT NULL by
        // the 2026-06-18 changeset specifically for this and for the
        // upcoming OCT-upload-portal "parked" status.
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO retinal_inference_job ("
                             + "job_id, event_crf_id, task, e2e_path, eye_laterality, "
                             + "status, enqueued_at) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            ps.setLong(1, 9100L);
            ps.setNull(2, java.sql.Types.INTEGER);
            ps.setString(3, "fluid");
            ps.setString(4, "/tmp/dangling.e2e");
            ps.setString(5, "OD");
            ps.setString(6, "queued");
            ps.setTimestamp(7, new Timestamp(System.currentTimeMillis()));
            ps.executeUpdate();
        }
        buildMockMvc().perform(get("/api/v1/retinal-jobs/9100")
                .session(authenticatedSession()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("belongs to a different study")));
    }

    /* ====================================================================== */
    /* GET /event-crfs/{id}/retinal-jobs                                      */
    /* ====================================================================== */

    @Test
    void listJobsForEventCrf_emptyOK_andOrderedByEnqueuedAtDesc() throws Exception {
        // event_crf 1 has both seeded jobs; verify ordering: 9001 > 8999.
        buildMockMvc().perform(get("/api/v1/event-crfs/1/retinal-jobs")
                .session(authenticatedSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].jobId").value(9001))
                .andExpect(jsonPath("$[1].jobId").value(8999))
                .andExpect(jsonPath("$[0].primaryMetric.unit").value("mm³"))
                .andExpect(jsonPath("$[1].primaryMetric.unit").value("µm"));

        // event_crf 2 has no jobs → empty list, still 200.
        buildMockMvc().perform(get("/api/v1/event-crfs/2/retinal-jobs")
                .session(authenticatedSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    /* ====================================================================== */
    /* GET /study-subjects/{id}/retinal-jobs                                  */
    /* ====================================================================== */

    @Test
    void listJobsForStudySubject_aggregatesAcrossEventCrfs() throws Exception {
        // study_subject 1 owns study_event 1/2/3 → event_crf 1/2/3.
        // The two seeded jobs both belong to event_crf 1, which belongs
        // to study_subject 1 — the subject view should aggregate them.
        buildMockMvc().perform(get("/api/v1/study-subjects/1/retinal-jobs")
                .session(authenticatedSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].jobId").value(9001))
                .andExpect(jsonPath("$[1].jobId").value(8999));
    }

    /* ====================================================================== */
    /* GET /retinal-jobs/{jobId}/artifacts/{name}                             */
    /* ====================================================================== */

    @Test
    void streamArtifact_servesCsvWithCorrectContentType() throws Exception {
        buildMockMvc().perform(get("/api/v1/retinal-jobs/9001/artifacts/retina-thickness.csv")
                .session(authenticatedSession()))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/csv"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("thickness_um")));
    }

    @Test
    void streamArtifact_404OnMissingFile() throws Exception {
        buildMockMvc().perform(get("/api/v1/retinal-jobs/9001/artifacts/nonexistent.csv")
                .session(authenticatedSession()))
                .andExpect(status().isNotFound());
    }

    @Test
    void streamArtifact_400OnPathTraversal() throws Exception {
        buildMockMvc().perform(get("/api/v1/retinal-jobs/9001/artifacts/..%2Fetc%2Fpasswd")
                .session(authenticatedSession()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void streamArtifact_servesBscanDcmFromBscanStore() throws Exception {
        // bscan.dcm is NOT in segDir — it lives under <bscanRoot>/<e2eUuid>/.
        // The controller must dispatch the companion-name to the artifact-store
        // resolver, not look in bscan_masks_dir.
        buildMockMvc().perform(get("/api/v1/retinal-jobs/9001/artifacts/bscan.dcm")
                .session(authenticatedSession()))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/dicom"));
    }

    @Test
    void streamArtifact_servesFundusPngWithCacheControl() throws Exception {
        buildMockMvc().perform(get("/api/v1/retinal-jobs/9001/artifacts/fundus.png")
                .session(authenticatedSession()))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(header().string("Cache-Control",
                        org.hamcrest.Matchers.containsString("max-age=3600")));
    }

    /* ====================================================================== */
    /* PATCH /retinal-jobs/{jobId}/bind  — Wave 1B                            */
    /* ====================================================================== */

    /** Happy path: bind a parked job to event_crf 1 → status flips to queued. */
    @Test
    void bind_happyPath_flipsToQueuedAndWritesAudit() throws Exception {
        long jobId = seedParkedJob();
        try {
            buildMockMvc().perform(patch("/api/v1/retinal-jobs/" + jobId + "/bind")
                    .session(authenticatedSession())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"eventCrfId\":1}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.jobId").value((int) jobId))
                    .andExpect(jsonPath("$.status").value("queued"));

            // Verify the row was actually updated.
            try (Connection c = DATA_SOURCE.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                         "SELECT event_crf_id, status FROM retinal_inference_job WHERE job_id = ?")) {
                ps.setLong(1, jobId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(1, rs.getInt("event_crf_id"));
                    assertEquals("queued", rs.getString("status"));
                }
            }

            // Audit row check
            try (Connection c = DATA_SOURCE.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                         "SELECT new_value, old_value FROM audit_log_event "
                                 + "WHERE audit_log_event_type_id = ? AND entity_id = ?")) {
                ps.setInt(1, AuditTypeIds.RETINAL_PARK_BIND);
                ps.setInt(2, (int) jobId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "RETINAL_PARK_BIND audit row should exist");
                    assertEquals("queued", rs.getString("new_value"));
                    assertEquals("parked", rs.getString("old_value"));
                }
            }
        } finally {
            cleanupAuditAndJob(jobId, AuditTypeIds.RETINAL_PARK_BIND);
        }
    }

    /** 409 when the job is in any non-parked status. */
    @Test
    void bind_returns409WhenJobNotParked() throws Exception {
        // 9001 is seeded as 'done' by the @BeforeEach.
        buildMockMvc().perform(patch("/api/v1/retinal-jobs/9001/bind")
                .session(authenticatedSession())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"eventCrfId\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("Job is not parked")));
    }

    /** 404 when the eventCrfId references a missing event_crf. */
    @Test
    void bind_returns404OnMissingEventCrf() throws Exception {
        long jobId = seedParkedJob();
        try {
            buildMockMvc().perform(patch("/api/v1/retinal-jobs/" + jobId + "/bind")
                    .session(authenticatedSession())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"eventCrfId\":987654321}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value(
                            org.hamcrest.Matchers.containsString("event_crf")));
        } finally {
            cleanupJobOnly(jobId);
        }
    }

    /* ====================================================================== */
    /* POST /retinal-jobs/bulk-bind — B2 bulk park-bind                       */
    /* ====================================================================== */

    /**
     * Happy path: every job in the batch is parked → every row comes
     * back with status BOUND and the summary counts match.
     */
    @Test
    void bulkBind_happyPath_allBound() throws Exception {
        long jobA = seedParkedJob();
        long jobB = seedParkedJob();
        try {
            String body = "{\"jobIds\":[" + jobA + "," + jobB + "],\"eventCrfId\":1}";
            buildMockMvc().perform(post("/api/v1/retinal-jobs/bulk-bind")
                    .session(authenticatedSession())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.results").isArray())
                    .andExpect(jsonPath("$.results.length()").value(2))
                    .andExpect(jsonPath(
                            "$.results[?(@.jobId == " + jobA + ")].status")
                            .value("BOUND"))
                    .andExpect(jsonPath(
                            "$.results[?(@.jobId == " + jobB + ")].status")
                            .value("BOUND"))
                    .andExpect(jsonPath("$.summary.bound").value(2))
                    .andExpect(jsonPath("$.summary.alreadyBound").value(0))
                    .andExpect(jsonPath("$.summary.forbidden").value(0))
                    .andExpect(jsonPath("$.summary.invalidState").value(0));

            // Verify both rows actually flipped.
            try (Connection c = DATA_SOURCE.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                         "SELECT job_id, event_crf_id, status FROM retinal_inference_job "
                                 + "WHERE job_id IN (?, ?) ORDER BY job_id")) {
                ps.setLong(1, jobA);
                ps.setLong(2, jobB);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(1, rs.getInt("event_crf_id"));
                    assertEquals("queued", rs.getString("status"));
                    assertTrue(rs.next());
                    assertEquals(1, rs.getInt("event_crf_id"));
                    assertEquals("queued", rs.getString("status"));
                }
            }
        } finally {
            cleanupAuditAndJob(jobA, AuditTypeIds.RETINAL_PARK_BIND);
            cleanupAuditAndJob(jobB, AuditTypeIds.RETINAL_PARK_BIND);
        }
    }

    /**
     * Mixed batch: one parked + one already-done job → the parked row
     * is BOUND, the done row is ALREADY_BOUND. The whole batch still
     * returns 200 + a populated summary so the SPA can render the
     * "1 zugewiesen · 1 bereits gebunden" toast.
     */
    @Test
    void bulkBind_mixedBoundAndAlreadyBound() throws Exception {
        long jobParked = seedParkedJob();
        // jobId 9001 is seeded as 'done' in @BeforeEach — non-parked
        // jobs map to ALREADY_BOUND in the new bulk semantics.
        try {
            String body = "{\"jobIds\":[" + jobParked + ",9001],\"eventCrfId\":1}";
            buildMockMvc().perform(post("/api/v1/retinal-jobs/bulk-bind")
                    .session(authenticatedSession())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.results.length()").value(2))
                    .andExpect(jsonPath(
                            "$.results[?(@.jobId == " + jobParked + ")].status")
                            .value("BOUND"))
                    .andExpect(jsonPath("$.results[?(@.jobId == 9001)].status")
                            .value("ALREADY_BOUND"))
                    .andExpect(jsonPath("$.summary.bound").value(1))
                    .andExpect(jsonPath("$.summary.alreadyBound").value(1));

            // The parked row flipped to queued; the done row stayed put.
            try (Connection c = DATA_SOURCE.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                         "SELECT status FROM retinal_inference_job WHERE job_id = ?")) {
                ps.setLong(1, jobParked);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals("queued", rs.getString("status"));
                }
                ps.setLong(1, 9001L);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals("done", rs.getString("status"));
                }
            }
        } finally {
            cleanupAuditAndJob(jobParked, AuditTypeIds.RETINAL_PARK_BIND);
        }
    }

    /**
     * When the operator can't see the event_crf, the bulk endpoint
     * surfaces per-row FORBIDDEN instead of failing the whole batch
     * with 403. The single-bind endpoint still returns 403 (covered by
     * its own contract) — bulk is lenient by design so the SPA can
     * show the operator a summary toast instead of dropping the
     * entire selection.
     */
    @Test
    void bulkBind_allForbiddenStillReturns200WithPerRowReasons() throws Exception {
        long jobA = seedParkedJob();
        long jobB = seedParkedJob();
        try {
            // 2026-06-20 — root has SYSADMIN, so the bulk-bind
            // visibility check on any event_crf passes. To exercise
            // the per-row FORBIDDEN branch we mount a separate
            // controller with a SiteVisibilityFilter override that
            // returns an empty visible set, simulating an operator
            // outside the event's site.
            MockMvc mvc = buildMockMvcWithEmptyVisibility();
            String body = "{\"jobIds\":[" + jobA + "," + jobB + "],\"eventCrfId\":1}";
            mvc.perform(post("/api/v1/retinal-jobs/bulk-bind")
                    .session(authenticatedSession())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.results.length()").value(2))
                    .andExpect(jsonPath(
                            "$.results[?(@.jobId == " + jobA + ")].status")
                            .value("FORBIDDEN"))
                    .andExpect(jsonPath(
                            "$.results[?(@.jobId == " + jobB + ")].status")
                            .value("FORBIDDEN"))
                    .andExpect(jsonPath("$.summary.forbidden").value(2))
                    .andExpect(jsonPath("$.summary.bound").value(0));

            // No row should have flipped status.
            try (Connection c = DATA_SOURCE.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                         "SELECT status FROM retinal_inference_job WHERE job_id IN (?, ?)")) {
                ps.setLong(1, jobA);
                ps.setLong(2, jobB);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        assertEquals("parked", rs.getString("status"));
                    }
                }
            }
        } finally {
            cleanupJobOnly(jobA);
            cleanupJobOnly(jobB);
        }
    }

    /** Empty jobIds → 400 validation error before reaching the per-job loop. */
    @Test
    void bulkBind_returns400OnEmptyJobIds() throws Exception {
        buildMockMvc().perform(post("/api/v1/retinal-jobs/bulk-bind")
                .session(authenticatedSession())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"jobIds\":[],\"eventCrfId\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("jobIds")));
    }

    /** Missing eventCrfId → 400 before reaching the per-job loop. */
    @Test
    void bulkBind_returns400OnZeroEventCrfId() throws Exception {
        buildMockMvc().perform(post("/api/v1/retinal-jobs/bulk-bind")
                .session(authenticatedSession())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"jobIds\":[42],\"eventCrfId\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("eventCrfId")));
    }

    /* ====================================================================== */
    /* GET /retinal-jobs?status=PARKED — cross-study admin browser            */
    /* ====================================================================== */

    /**
     * Happy path: the endpoint returns one row per parked job with
     * patientId + candidateStudySubjectId parsed from the OCT-UPLOAD
     * audit row's {@code old_value}.
     */
    @Test
    void listParkedJobs_happyPath_returnsRowsWithParsedMetadata() throws Exception {
        long jobIdWithCandidate = seedParkedJob();
        long jobIdNopatient = seedParkedJob();
        try {
            // Seed two OCT_UPLOAD_PUBLIC audit rows — one with studySubjectId
            // (novisit/ambiguous park), one without (nopatient park).
            writeOctUploadAuditRow(jobIdWithCandidate,
                    "patientId=EIAMD139;laterality=OD;studySubjectId=9", "parked");
            writeOctUploadAuditRow(jobIdNopatient,
                    "patientId=UNKNOWN_42;laterality=OS", "parked");

            buildMockMvc().perform(get("/api/v1/retinal-jobs")
                    .param("status", "PARKED")
                    .session(sysadminSession()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray())
                    // both seeded jobs surface — order is enqueued_at desc
                    // so the later-seeded nopatient row appears first.
                    .andExpect(jsonPath(
                            "$[?(@.jobId == " + jobIdWithCandidate + ")].patientId")
                            .value("EIAMD139"))
                    .andExpect(jsonPath(
                            "$[?(@.jobId == " + jobIdWithCandidate + ")].candidateStudySubjectId")
                            .value(9))
                    .andExpect(jsonPath(
                            "$[?(@.jobId == " + jobIdNopatient + ")].patientId")
                            .value("UNKNOWN_42"))
                    // nopatient parks have no candidate FK — the
                    // filter returns a singleton list containing the
                    // serialised null, so `everyItem(nullValue())`
                    // matches whether jackson emits `null` or omits
                    // the field entirely.
                    .andExpect(jsonPath(
                            "$[?(@.jobId == " + jobIdNopatient + ")].candidateStudySubjectId")
                            .value(org.hamcrest.Matchers.everyItem(
                                    org.hamcrest.Matchers.nullValue())));
        } finally {
            cleanupAuditAndJob(jobIdWithCandidate, AuditTypeIds.OCT_UPLOAD_PUBLIC);
            cleanupAuditAndJob(jobIdNopatient, AuditTypeIds.OCT_UPLOAD_PUBLIC);
        }
    }

    /**
     * The endpoint MUST reject status filters it doesn't recognize — the
     * v1 endpoint is parked-only by design, no surprises.
     */
    @Test
    void listParkedJobs_returns400OnUnknownStatusFilter() throws Exception {
        buildMockMvc().perform(get("/api/v1/retinal-jobs")
                .param("status", "DONE")
                .session(sysadminSession()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("PARKED")));
    }

    /* ====================================================================== */
    /* GET /study-subjects/search                                              */
    /* ====================================================================== */

    @Test
    void searchSubjects_returnsHits_forKnownPrefix() throws Exception {
        buildMockMvc().perform(get("/api/v1/study-subjects/search")
                .param("q", "M-00")
                .param("limit", "10")
                .session(authenticatedSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                // 7 seeded subjects M-001 .. M-007 all visible to root.
                .andExpect(jsonPath("$.length()").value(7))
                .andExpect(jsonPath("$[0].label").value("M-001"))
                .andExpect(jsonPath("$[0].studyId").value(1));
    }

    @Test
    void searchSubjects_returnsEmpty_forNonexistentPrefix() throws Exception {
        buildMockMvc().perform(get("/api/v1/study-subjects/search")
                .param("q", "ZZZ-NO-SUCH")
                .session(authenticatedSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void searchSubjects_clampsLimitOutOfRange() throws Exception {
        // limit=99 → clamped to 50 internally; with only 7 rows the assert
        // is just "no error, returns the 7 matches".
        buildMockMvc().perform(get("/api/v1/study-subjects/search")
                .param("q", "M-")
                .param("limit", "99")
                .session(authenticatedSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(7));
    }

    @Test
    void searchSubjects_returnsEmpty_forBlankQuery() throws Exception {
        buildMockMvc().perform(get("/api/v1/study-subjects/search")
                .param("q", "  ")
                .session(authenticatedSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    /* ---- bind / search helpers --------------------------------------- */

    private static long seedParkedJob() throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO retinal_inference_job ("
                             + "event_crf_id, task, e2e_path, eye_laterality, "
                             + "status, enqueued_at) VALUES (?, ?, ?, ?, ?, ?) "
                             + "RETURNING job_id")) {
            ps.setNull(1, java.sql.Types.INTEGER);
            ps.setString(2, "fluid");
            ps.setString(3, "/tmp/wave1b-parked.e2e");
            ps.setString(4, "OD");
            ps.setString(5, "parked");
            ps.setTimestamp(6, new Timestamp(System.currentTimeMillis()));
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getLong(1);
            }
        }
    }

    private static void cleanupJobOnly(long jobId) throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM retinal_inference_job WHERE job_id = ?")) {
            ps.setLong(1, jobId);
            ps.executeUpdate();
        }
    }

    /**
     * Insert one OCT_UPLOAD_PUBLIC audit row for a parked job. The
     * production code path emits this from {@code PublicOctUploadController.writePublicOctUploadAuditRow}
     * at park-commit time; the IT writes it directly to keep the
     * seeding self-contained.
     */
    private static void writeOctUploadAuditRow(long jobId, String oldValue, String newValue)
            throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO audit_log_event ("
                             + "audit_date, audit_table, entity_id, "
                             + "audit_log_event_type_id, old_value, new_value) "
                             + "VALUES (?, 'retinal_inference_job', ?, ?, ?, ?)")) {
            ps.setTimestamp(1, new Timestamp(System.currentTimeMillis()));
            ps.setInt(2, (int) jobId);
            ps.setInt(3, AuditTypeIds.OCT_UPLOAD_PUBLIC);
            ps.setString(4, oldValue);
            ps.setString(5, newValue);
            ps.executeUpdate();
        }
    }

    private static void cleanupAuditAndJob(long jobId, int auditTypeId) throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM audit_log_event "
                             + "WHERE audit_log_event_type_id = ? AND entity_id = ?")) {
            ps.setInt(1, auditTypeId);
            ps.setInt(2, (int) jobId);
            ps.executeUpdate();
        }
        cleanupJobOnly(jobId);
    }

    /**
     * 2026-06-19 — session variant where {@code userBean.isSysAdmin()}
     * returns true. The cross-study parked-jobs admin endpoint
     * (GET /retinal-jobs?status=PARKED) is sysadmin-only; without this
     * flag the endpoint returns 403 before the test reaches its
     * assertions. Mirrors the per-IT helper pattern used in
     * ImportApiControllerBulkImportDatabaseIT et al.
     */
    private MockHttpSession sysadminSession() {
        MockHttpSession session = new MockHttpSession();
        UserAccountBean ub = new UserAccountBean();
        ub.setId(1);
        ub.setName("root");
        ub.addUserType(UserType.SYSADMIN);
        session.setAttribute("userBean", ub);
        StudyBean study = new StudyBean();
        study.setId(1);
        study.setOid("default-study");
        study.setName("default-study");
        session.setAttribute("study", study);
        return session;
    }
}
