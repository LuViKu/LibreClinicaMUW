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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RemoteRetinalInferenceClient;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.StudySubjectFinder;

/**
 * "Auswertung starten" on one filed scan: {@code POST /ingest/{id}/analyses}.
 *
 * <p>A filed OCT volume the visit's imaging plan did not cover had no way to
 * be analysed. These pin what the endpoint starts (one job row on the scan,
 * carrying its ingest item and visit, audited) and every refusal in front of
 * it. The GPU host is not configured here, as on a single-host install: the
 * job lands {@code queued} for the local worker and nothing is dispatched.
 */
@SuppressWarnings("null")
class IngestStartAnalysisDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final ObjectMapper JSON = new ObjectMapper();

    private static Path dir;

    @BeforeAll
    static void files() throws Exception {
        dir = Files.createTempDirectory("start-analysis-it-");
    }

    private static MockMvc mvc(boolean withDispatcher) {
        SiteVisibilityFilter filter = new SiteVisibilityFilter(DATA_SOURCE);
        StudySubjectFinder finder = new StudySubjectFinder(DATA_SOURCE);
        IngestInboxApiController controller;
        if (withDispatcher) {
            RemoteRetinalInferenceClient remote = Mockito.mock(RemoteRetinalInferenceClient.class);
            Mockito.when(remote.isConfigured()).thenReturn(false);
            controller = new IngestInboxApiController(DATA_SOURCE, filter, finder, remote,
                    Mockito.mock(RetinalInferenceApiController.class));
        } else {
            controller = new IngestInboxApiController(DATA_SOURCE, filter, finder);
        }
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private static MockHttpSession investigator() {
        return ClinicalWriteFixtures.sessionAs(DATA_SOURCE, "manual_investigator");
    }

    /* ------------------------------------------------------------------ */
    /* Fixtures                                                            */
    /* ------------------------------------------------------------------ */

    /** A fresh subject of Default Study with one visit. */
    private record Visit(int subject, int event) {}

    private static Visit visit(int studyId) throws Exception {
        int n = SEQ.incrementAndGet();
        try (Connection c = DATA_SOURCE.getConnection()) {
            int ss = LifecycleFixtures.insertStudySubject(c, "SA-" + studyId + "-" + n + "-" + System.nanoTime() % 100000,
                    studyId, 1);
            int event = LifecycleFixtures.insertOne(c,
                    "INSERT INTO study_event (study_event_definition_id, study_subject_id, sample_ordinal, "
                            + "date_start, owner_id, status_id, date_created, subject_event_status_id, "
                            + "start_time_flag, end_time_flag) VALUES (2, " + ss + ", 1, now(), 1, 1, now(), "
                            + "1, false, false) RETURNING study_event_id");
            return new Visit(ss, event);
        }
    }

    /** A file of {@code kind}, BOUND to the visit (or UNBOUND when {@code v} is null). */
    private static long item(String kind, Visit v, boolean fileOnDisk) throws Exception {
        int n = SEQ.incrementAndGet();
        Path p = dir.resolve("scan-" + n + "-" + System.nanoTime() + ".e2e");
        if (fileOnDisk) Files.write(p, ("scan " + n).getBytes(StandardCharsets.UTF_8));
        String sha = String.format("d%063d", System.nanoTime() % 1_000_000_000_000L + n);
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO ingest_item (kind, source_kind, stored_path, original_filename, laterality, "
                             + "received_at, status, bound_study_subject_id, bound_study_event_id, sha256, "
                             + "scan_index, byte_size) VALUES (?, 'upload', ?, ?, 'OD', now(), ?, ?, ?, ?, 0, 7) "
                             + "RETURNING ingest_item_id")) {
            ps.setString(1, kind);
            ps.setString(2, p.toString());
            ps.setString(3, "start-it-" + n);
            ps.setString(4, v == null ? "UNBOUND" : "BOUND");
            if (v == null) {
                ps.setNull(5, java.sql.Types.INTEGER);
                ps.setNull(6, java.sql.Types.INTEGER);
            } else {
                ps.setInt(5, v.subject());
                ps.setInt(6, v.event());
            }
            ps.setString(7, sha);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static long existingJob(long itemId, String task, String status) throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO retinal_inference_job (task, e2e_path, eye_laterality, status, enqueued_at, "
                             + "scan_index, ingest_item_id, study_event_id) "
                             + "SELECT ?, '/nowhere.e2e', 'OD', ?, now(), 0, ingest_item_id, bound_study_event_id "
                             + "  FROM ingest_item WHERE ingest_item_id = ? RETURNING job_id")) {
            ps.setString(1, task);
            ps.setString(2, status);
            ps.setLong(3, itemId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static int jobsOf(long itemId) throws Exception {
        return LifecycleFixtures.intQuery("SELECT count(*) FROM retinal_inference_job WHERE ingest_item_id = " + itemId);
    }

    private record Answer(int status, JsonNode body) {}

    private static Answer start(MockMvc mvc, long itemId, String task, MockHttpSession session) throws Exception {
        MockHttpServletResponse r = mvc.perform(post("/api/v1/ingest/" + itemId + "/analyses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"task\":\"" + task + "\"}")
                        .session(session))
                .andReturn().getResponse();
        String text = r.getContentAsString();
        return new Answer(r.getStatus(), text.isEmpty() ? JSON.createObjectNode() : JSON.readTree(text));
    }

    /* ------------------------------------------------------------------ */
    /* Started                                                             */
    /* ------------------------------------------------------------------ */

    @Test
    void aFiledScanGetsOneJobOnItsItemAndVisit() throws Exception {
        Visit v = visit(1);
        long item = item("e2e", v, true);

        Answer a = start(mvc(true), item, "layers", investigator());

        assertEquals(202, a.status(), a.body().toString());
        long jobId = a.body().get("jobId").asLong();
        assertEquals(1, a.body().get("subjectSeq").asInt(), "the subject's first job: " + a.body());
        assertTrue(a.body().get("subjectLabel").asText().startsWith("SA-"), a.body().toString());
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT task, status, ingest_item_id, study_event_id, scan_index, e2e_sha256 "
                             + "  FROM retinal_inference_job WHERE job_id = ?")) {
            ps.setLong(1, jobId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "the job row exists");
                assertEquals("layers", rs.getString("task"));
                assertEquals("queued", rs.getString("status"), "no GPU host configured: the local queue");
                assertEquals(item, rs.getLong("ingest_item_id"));
                assertEquals(v.event(), rs.getInt("study_event_id"));
                assertEquals(0, rs.getInt("scan_index"));
                assertNotNull(rs.getString("e2e_sha256"));
            }
        }
        assertEquals(1, LifecycleFixtures.intQuery("SELECT count(*) FROM audit_log_event "
                + "WHERE audit_log_event_type_id = " + AuditTypeIds.RETINAL_INFERENCE_ENQUEUED
                + " AND audit_table = 'retinal_inference_job' AND entity_id = " + jobId
                + " AND new_value = 'layers' AND study_event_id = " + v.event()), "one audit row on the job");
    }

    @Test
    void theVisitsImageListShowsTheScansAnalyses() throws Exception {
        Visit v = visit(1);
        long item = item("e2e", v, true);
        long photo = item("image", v, true);
        long done = existingJob(item, "fluid", "done");
        existingJob(item, "ga", "cancelled");

        String body = mvc(true).perform(get("/api/v1/ingest/by-event/" + v.event()).session(investigator()))
                .andReturn().getResponse().getContentAsString();
        JsonNode items = JSON.readTree(body).get("items");
        JsonNode scan = null;
        JsonNode image = null;
        for (JsonNode n : items) {
            if (n.get("id").asLong() == item) scan = n;
            if (n.get("id").asLong() == photo) image = n;
        }
        assertNotNull(scan, body);
        assertNotNull(image, body);
        assertTrue(scan.get("analysable").asBoolean());
        assertEquals(1, scan.get("analyses").size(), "the cancelled job is not listed: " + scan);
        assertEquals(done, scan.get("analyses").get(0).get("jobId").asLong());
        assertEquals(1, scan.get("analyses").get(0).get("subjectSeq").asInt());
        assertEquals("fluid", scan.get("analyses").get(0).get("task").asText());
        assertEquals("done", scan.get("analyses").get(0).get("status").asText());
        assertEquals(false, image.get("analysable").asBoolean());
        assertTrue(image.get("analyses").isNull(), "a photograph has no analyses to list");
    }

    /* ------------------------------------------------------------------ */
    /* Refused                                                             */
    /* ------------------------------------------------------------------ */

    @Test
    void anUnfiledScanIsRefused() throws Exception {
        long item = item("e2e", null, true);
        Answer a = start(mvc(true), item, "fluid", investigator());
        assertEquals(409, a.status(), a.body().toString());
        assertEquals("NOT_BOUND", a.body().get("code").asText());
        assertEquals(0, jobsOf(item));
    }

    @Test
    void aFileThatIsNotAnOctVolumeIsRefused() throws Exception {
        long item = item("image", visit(1), true);
        Answer a = start(mvc(true), item, "fluid", investigator());
        assertEquals(409, a.status(), a.body().toString());
        assertEquals("NOT_ANALYSABLE", a.body().get("code").asText());
        assertEquals(0, jobsOf(item));
    }

    @Test
    void aScanWhoseFileIsGoneIsRefused() throws Exception {
        long item = item("e2e", visit(1), false);
        Answer a = start(mvc(true), item, "fluid", investigator());
        assertEquals(409, a.status(), a.body().toString());
        assertEquals("SCAN_FILE_MISSING", a.body().get("code").asText());
        assertEquals(RetinalJobAccess.SCAN_FILE_MISSING, a.body().get("message").asText());
        assertEquals(0, jobsOf(item));
    }

    @Test
    void aTaskTheScanAlreadyHasNamesTheExistingJob() throws Exception {
        long item = item("e2e", visit(1), true);
        long existing = existingJob(item, "fluid", "failed");
        Answer a = start(mvc(true), item, "fluid", investigator());
        assertEquals(409, a.status(), a.body().toString());
        assertEquals(existing, a.body().get("existingJobId").asLong());
        assertEquals(1, jobsOf(item));
    }

    @Test
    void aCancelledJobDoesNotBlockANewOne() throws Exception {
        long item = item("e2e", visit(1), true);
        existingJob(item, "fluid", "cancelled");
        Answer a = start(mvc(true), item, "fluid", investigator());
        assertEquals(202, a.status(), a.body().toString());
        assertEquals(2, jobsOf(item));
    }

    @Test
    void aScanOfAStudyTheCallerCannotSeeIsNotFound() throws Exception {
        int other;
        try (Connection c = DATA_SOURCE.getConnection()) {
            other = LifecycleFixtures.insertStudy(c, null, "SAOTHER" + SEQ.incrementAndGet(),
                    "Other study", "S_SAOTHER" + SEQ.get(), 1);
        }
        long item = item("e2e", visit(other), true);
        Answer a = start(mvc(true), item, "fluid", investigator());
        assertEquals(404, a.status(), "a foreign scan is not even acknowledged: " + a.body());
        assertTrue(a.body().get("code") == null, "no state leaks: " + a.body());
        assertEquals(0, jobsOf(item));
    }

    @Test
    void aMonitorIsRefused() throws Exception {
        long item = item("e2e", visit(1), true);
        Answer a = start(mvc(true), item, "fluid", ClinicalWriteFixtures.sessionAs(DATA_SOURCE, "manual_monitor"));
        assertEquals(403, a.status(), a.body().toString());
        assertTrue(a.body().get("message").asText().startsWith("Your role does not permit"));
        assertEquals(0, jobsOf(item));
    }

    @Test
    void aClosedRecordIsRefused() throws Exception {
        Visit v = visit(1);
        long item = item("e2e", v, true);
        try (Connection c = DATA_SOURCE.getConnection()) {
            CrossSiteIsolationSupport.exec(c, "UPDATE study_subject SET status_id = 5 WHERE study_subject_id = " + v.subject());
        }
        Answer a = start(mvc(true), item, "fluid", investigator());
        assertEquals(409, a.status(), a.body().toString());
        assertEquals("SUBJECT_REMOVED", a.body().get("code").asText());
        assertEquals(0, jobsOf(item));
    }

    @Test
    void aTaskThatIsNotOfferedIsABadRequest() throws Exception {
        long item = item("e2e", visit(1), true);
        Answer a = start(mvc(true), item, "bm", investigator());
        assertEquals(400, a.status(), a.body().toString());
        assertEquals(0, jobsOf(item));
    }

    @Test
    void withoutADispatcherNothingIsQueued() throws Exception {
        long item = item("e2e", visit(1), true);
        Answer a = start(mvc(false), item, "fluid", investigator());
        assertEquals(503, a.status(), a.body().toString());
        assertEquals(0, jobsOf(item));
    }

    /* ------------------------------------------------------------------ */
    /* rerun-as keeps the scan                                             */
    /* ------------------------------------------------------------------ */

    private static MockMvc rerunMvc() {
        SiteVisibilityFilter filter = new SiteVisibilityFilter(DATA_SOURCE);
        RemoteRetinalInferenceClient remote = Mockito.mock(RemoteRetinalInferenceClient.class);
        Mockito.when(remote.isConfigured()).thenReturn(true);
        return MockMvcBuilders.standaloneSetup(new RetinalResultsApiController(DATA_SOURCE, filter,
                        Mockito.mock(at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RetinalArtifactStorageService.class),
                        null, remote,
                        new at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RetinalJobStatusBroadcaster(),
                        Mockito.mock(RetinalInferenceApiController.class)))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    /**
     * A re-run is a job of the same scan: it carries the scan's ingest item,
     * so unbinding the scan detaches it and the visit page lists it.
     */
    @Test
    void aRerunCarriesTheScansIngestItem() throws Exception {
        Visit v = visit(1);
        long item = item("e2e", v, true);
        long source;
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO retinal_inference_job (study_event_id, task, e2e_path, eye_laterality, status, "
                             + "enqueued_at, scan_index, e2e_sha256, ingest_item_id) "
                             + "SELECT ?, 'fluid', stored_path, 'OD', 'done', now(), 0, sha256, ingest_item_id "
                             + "  FROM ingest_item WHERE ingest_item_id = ? RETURNING job_id")) {
            ps.setInt(1, v.event());
            ps.setLong(2, item);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                source = rs.getLong(1);
            }
        }
        MockHttpServletResponse r = rerunMvc().perform(post("/api/v1/retinal-jobs/" + source + "/rerun-as")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"task\":\"ga\"}")
                        .session(investigator()))
                .andReturn().getResponse();
        assertEquals(202, r.getStatus(), r.getContentAsString());
        long rerun = JSON.readTree(r.getContentAsString()).get("jobId").asLong();
        assertEquals(item, LifecycleFixtures.intQuery(
                "SELECT COALESCE(ingest_item_id, -1) FROM retinal_inference_job WHERE job_id = " + rerun),
                "the re-run belongs to the scan");

        // The same task again is the job just made, by the scan's own key.
        MockHttpServletResponse again = rerunMvc().perform(post("/api/v1/retinal-jobs/" + source + "/rerun-as")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"task\":\"ga\"}")
                        .session(investigator()))
                .andReturn().getResponse();
        assertEquals(409, again.getStatus(), again.getContentAsString());
        JsonNode twin = JSON.readTree(again.getContentAsString());
        assertEquals(rerun, twin.get("existingJobId").asLong());
        assertEquals(2, twin.get("subjectSeq").asInt(), "the re-run is the subject's second job: " + twin);
    }
}
