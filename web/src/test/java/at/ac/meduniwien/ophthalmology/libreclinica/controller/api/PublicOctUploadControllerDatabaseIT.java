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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import at.ac.meduniwien.ophthalmology.libreclinica.dao.core.CoreResources;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.StudySubjectFinder;

/**
 * Wave A backend ITs for the public OCT-upload portal —
 * see plan at /Users/lukas/.claude/plans/robust-jumping-eich.md.
 *
 * <p>Boots a Testcontainers Postgres via
 * {@link AbstractApiControllerDatabaseIT}, then mounts
 * {@link PublicOctUploadController} in a standalone MockMvc context.
 * The seeded demo data (M-001 .. M-007 across study_id=1) is enough to
 * exercise the resolve algorithm; commit ITs INSERT minimal extra rows
 * directly via JDBC where needed.
 *
 * <p>Tests are deliberately self-contained: each test that inserts
 * rows cleans up after itself so sibling tests still see the seeded
 * state.
 */
class PublicOctUploadControllerDatabaseIT extends AbstractApiControllerDatabaseIT {

    /** Per-class on-disk upload root; mirrors the production e2eUploadsPath. */
    @TempDir
    static Path UPLOADS_ROOT;

    /** Snapshot of the CoreResources DATAINFO bag so we can mutate + restore. */
    private static java.util.Properties SAVED_DATAINFO;

    @BeforeAll
    static void overrideUploadsPath() throws Exception {
        // Reuse the existing CoreResources DATAINFO bag the base class
        // populated; just inject our temp e2eUploadsPath into it so the
        // controller's uploadsDir() resolves to a writable test path
        // instead of /var/lib/libreclinica/e2e-uploads.
        java.lang.reflect.Field f = CoreResources.class.getDeclaredField("DATAINFO");
        f.setAccessible(true);
        java.util.Properties live = (java.util.Properties) f.get(null);
        assertNotNull(live, "DATAINFO must be set by AbstractApiControllerDatabaseIT");
        SAVED_DATAINFO = new java.util.Properties();
        SAVED_DATAINFO.putAll(live);
        live.setProperty("core.retinalInference.e2eUploadsPath", UPLOADS_ROOT.toString());
    }

    @AfterAll
    static void restoreUploadsPath() throws Exception {
        java.lang.reflect.Field f = CoreResources.class.getDeclaredField("DATAINFO");
        f.setAccessible(true);
        java.util.Properties live = (java.util.Properties) f.get(null);
        if (live != null && SAVED_DATAINFO != null) {
            live.clear();
            live.putAll(SAVED_DATAINFO);
        }
    }

    private MockMvc mockMvc() {
        StudySubjectFinder finder = new StudySubjectFinder(DATA_SOURCE);
        PublicOctUploadController c = new PublicOctUploadController(DATA_SOURCE, finder);
        return MockMvcBuilders.standaloneSetup(c)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    /* ====================================================================== */
    /* /resolve                                                               */
    /* ====================================================================== */

    /**
     * M-001 has study_event_id=1 with date_start=2020-10-06 + event_crf_id=1.
     * /resolve with that PatientId + date returns state='suggested' and the
     * matched event_crf_id.
     */
    @Test
    void resolve_returnsSuggested_whenPatientHasEventOnDate() throws Exception {
        mockMvc().perform(post("/api/v1/public/oct-upload/resolve")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"scans\":[{\"patientId\":\"M-001\","
                        + "\"scanDate\":\"2020-10-06\",\"laterality\":\"OD\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scans.length()").value(1))
                .andExpect(jsonPath("$.scans[0].patientId").value("M-001"))
                .andExpect(jsonPath("$.scans[0].state").value("suggested"))
                .andExpect(jsonPath("$.scans[0].candidates.length()").value(1))
                .andExpect(jsonPath("$.scans[0].candidates[0].studyId").value(1))
                .andExpect(jsonPath("$.scans[0].candidates[0].subjectLabel").value("M-001"))
                .andExpect(jsonPath("$.scans[0].candidates[0].matchingEvent.eventCrfId").value(1))
                .andExpect(jsonPath("$.scans[0].candidates[0].matchingEvent.matchPolicy").value("same-day"));
    }

    /** No event on the supplied date for an existing patient → state='novisit'. */
    @Test
    void resolve_returnsNovisit_whenNoEventOnDate() throws Exception {
        mockMvc().perform(post("/api/v1/public/oct-upload/resolve")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"scans\":[{\"patientId\":\"M-001\","
                        + "\"scanDate\":\"2099-01-01\",\"laterality\":\"OD\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scans[0].state").value("novisit"))
                .andExpect(jsonPath("$.scans[0].candidates.length()").value(1))
                .andExpect(jsonPath("$.scans[0].candidates[0].matchingEvent").doesNotExist());
    }

    /** Unknown label → state='nopatient'. */
    @Test
    void resolve_returnsNopatient_whenLabelMissing() throws Exception {
        mockMvc().perform(post("/api/v1/public/oct-upload/resolve")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"scans\":[{\"patientId\":\"NO-SUCH-PATIENT\","
                        + "\"scanDate\":\"2020-10-06\",\"laterality\":\"OD\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scans[0].state").value("nopatient"))
                .andExpect(jsonPath("$.scans[0].candidates.length()").value(0));
    }

    /* ====================================================================== */
    /* /commit                                                                */
    /* ====================================================================== */

    /**
     * Happy path: bind to event_crf_id=1 (M-001 V1 Inclusion). Assert the
     * job row and the OCT_UPLOAD_PUBLIC audit row with user_id IS NULL.
     */
    @Test
    void commit_unauthenticated_insertsJobAndAuditRow() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "scan.e2e", "application/octet-stream", new byte[1024]);

        MvcResult res = mockMvc().perform(multipart("/api/v1/public/oct-upload/commit")
                .file(file)
                .param("patientId", "M-001")
                .param("scanDate", "2020-10-06")
                .param("laterality", "OD")
                .param("scanIndex", "0")
                .param("eventCrfId", "1"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("queued"))
                .andExpect(jsonPath("$.jobId").exists())
                .andReturn();

        long jobId = extractJobId(res);

        // Job row check
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT event_crf_id, status, eye_laterality, task, e2e_path "
                             + "FROM retinal_inference_job WHERE job_id = ?")) {
            ps.setLong(1, jobId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "job row should exist");
                assertEquals(1, rs.getInt("event_crf_id"));
                assertEquals("queued", rs.getString("status"));
                assertEquals("OD", rs.getString("eye_laterality"));
                assertEquals("fluid", rs.getString("task"));
                String path = rs.getString("e2e_path");
                assertNotNull(path);
                assertTrue(Files.exists(Path.of(path)), ".e2e file should be on disk at " + path);
            }
        }

        // Audit row check — user_id IS NULL is the load-bearing assertion
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT user_id, audit_table, entity_id, new_value "
                             + "FROM audit_log_event "
                             + "WHERE audit_log_event_type_id = ? AND entity_id = ?")) {
            ps.setInt(1, AuditTypeIds.OCT_UPLOAD_PUBLIC);
            ps.setInt(2, (int) jobId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "audit row should exist");
                int userId = rs.getInt("user_id");
                assertTrue(rs.wasNull(), "user_id must be NULL on a public-portal audit row");
                assertEquals("retinal_inference_job", rs.getString("audit_table"));
                assertEquals("queued", rs.getString("new_value"));
            }
        }

        cleanupJob(jobId);
    }

    /** park=true with no eventCrfId leaves event_crf_id NULL + status='parked'. */
    @Test
    void commit_park_acceptsNullEventCrfId() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "scan.e2e", "application/octet-stream", new byte[256]);

        MvcResult res = mockMvc().perform(multipart("/api/v1/public/oct-upload/commit")
                .file(file)
                .param("patientId", "UNKNOWN-XYZ")
                .param("scanDate", "2020-10-06")
                .param("laterality", "OS")
                .param("park", "true"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("parked"))
                .andReturn();

        long jobId = extractJobId(res);

        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT event_crf_id, status FROM retinal_inference_job WHERE job_id = ?")) {
            ps.setLong(1, jobId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                rs.getInt("event_crf_id");
                assertTrue(rs.wasNull(), "event_crf_id should be NULL on a parked row");
                assertEquals("parked", rs.getString("status"));
            }
        }
        cleanupJob(jobId);
    }

    /** Bad multipart: missing both eventCrfId and park → 400. */
    @Test
    void commit_returns400_whenNeitherEventCrfIdNorPark() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "scan.e2e", "application/octet-stream", new byte[16]);
        mockMvc().perform(multipart("/api/v1/public/oct-upload/commit")
                .file(file)
                .param("patientId", "M-001")
                .param("scanDate", "2020-10-06")
                .param("laterality", "OD"))
                .andExpect(status().isBadRequest());
    }

    /* ====================================================================== */
    /* DELETE /{jobId} — undo                                                 */
    /* ====================================================================== */

    /** Job freshly created → DELETE returns 204 + cleans up row and file. */
    @Test
    void undo_deletesJobAndFile_within60s() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "scan.e2e", "application/octet-stream", new byte[64]);
        MvcResult res = mockMvc().perform(multipart("/api/v1/public/oct-upload/commit")
                .file(file)
                .param("patientId", "M-002")
                .param("scanDate", "2020-10-09")
                .param("laterality", "OD")
                .param("eventCrfId", "4"))
                .andExpect(status().isCreated())
                .andReturn();
        long jobId = extractJobId(res);
        String path = jobE2ePath(jobId);

        mockMvc().perform(delete("/api/v1/public/oct-upload/" + jobId))
                .andExpect(status().isNoContent());

        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT 1 FROM retinal_inference_job WHERE job_id = ?")) {
            ps.setLong(1, jobId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(!rs.next(), "row should be deleted");
            }
        }
        assertTrue(!Files.exists(Path.of(path)), "file should be unlinked");
    }

    /**
     * Old job (enqueued_at backdated 120 s) → DELETE returns 410 Gone +
     * the row is left intact.
     */
    @Test
    void undo_returns410_after60s() throws Exception {
        // INSERT a job directly with an old enqueued_at so we don't have
        // to actually wait 60 s in the test.
        long jobId;
        Path stub = UPLOADS_ROOT.resolve("old-undo.e2e");
        Files.write(stub, new byte[]{0x1});
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO retinal_inference_job ("
                             + "event_crf_id, task, e2e_path, eye_laterality, "
                             + "status, enqueued_at) VALUES (?, ?, ?, ?, ?, ?) "
                             + "RETURNING job_id")) {
            ps.setInt(1, 1);
            ps.setString(2, "fluid");
            ps.setString(3, stub.toString());
            ps.setString(4, "OD");
            ps.setString(5, "queued");
            ps.setTimestamp(6, Timestamp.from(Instant.now().minusSeconds(120)));
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                jobId = rs.getLong(1);
            }
        }

        mockMvc().perform(delete("/api/v1/public/oct-upload/" + jobId))
                .andExpect(status().isGone());

        // Row should still be there
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT 1 FROM retinal_inference_job WHERE job_id = ?")) {
            ps.setLong(1, jobId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "row should NOT be deleted outside undo window");
            }
        }
        cleanupJob(jobId);
    }

    /* ====================================================================== */
    /* helpers                                                                */
    /* ====================================================================== */

    private static long extractJobId(MvcResult res) throws Exception {
        String json = res.getResponse().getContentAsString();
        // Tiny ad-hoc extractor — full JSON parser would just add dependencies.
        int i = json.indexOf("\"jobId\":");
        if (i < 0) throw new IllegalStateException("no jobId in " + json);
        int start = i + "\"jobId\":".length();
        int end = start;
        while (end < json.length() && Character.isDigit(json.charAt(end))) end++;
        return Long.parseLong(json.substring(start, end));
    }

    private static String jobE2ePath(long jobId) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT e2e_path FROM retinal_inference_job WHERE job_id = ?")) {
            ps.setLong(1, jobId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getString(1);
            }
        }
    }

    /** Hard delete: rows + file, no audit-row cleanup needed (test data). */
    private static void cleanupJob(long jobId) throws SQLException, IOException {
        String path = null;
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT e2e_path FROM retinal_inference_job WHERE job_id = ?")) {
            ps.setLong(1, jobId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) path = rs.getString(1);
            }
        }
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM audit_log_event "
                             + "WHERE audit_log_event_type_id = ? AND entity_id = ?")) {
            ps.setInt(1, AuditTypeIds.OCT_UPLOAD_PUBLIC);
            ps.setLong(2, jobId);
            ps.executeUpdate();
        }
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM retinal_inference_job WHERE job_id = ?")) {
            ps.setLong(1, jobId);
            ps.executeUpdate();
        }
        if (path != null) {
            Files.deleteIfExists(Path.of(path));
        }
    }
}
