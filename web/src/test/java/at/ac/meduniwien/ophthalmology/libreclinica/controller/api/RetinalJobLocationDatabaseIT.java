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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RetinalArtifactStorageService;

/**
 * The job page's location: where a job lives (subject, its number under the
 * subject, visit) and the other analyses of the same scan, on
 * {@code GET /retinal-jobs/{id}}. The number is the one the subject-scoped
 * address resolves, so the SPA can link every job canonically; a caller who
 * may not see the job learns none of it.
 */
@SuppressWarnings("null")
class RetinalJobLocationDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static RetinalArtifactStorageService store;

    @BeforeAll
    static void store() throws Exception {
        String root = Files.createTempDirectory("job-location-it-").toString();
        store = new RetinalArtifactStorageService() {
            @Override
            protected String bscanStorePath() {
                return root;
            }
        };
    }

    private static MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(new RetinalResultsApiController(DATA_SOURCE,
                        new SiteVisibilityFilter(DATA_SOURCE), store))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private static MockHttpSession investigator() {
        return ClinicalWriteFixtures.sessionAs(DATA_SOURCE, "manual_investigator");
    }

    private static JsonNode getJson(String path, MockHttpSession s, int expected) throws Exception {
        MockHttpServletResponse r = mvc().perform(get(path).session(s)).andReturn().getResponse();
        assertEquals(expected, r.getStatus(), r.getContentAsString());
        return JSON.readTree(r.getContentAsString());
    }

    /* ------------------------------------------------------------------ */
    /* Fixtures                                                            */
    /* ------------------------------------------------------------------ */

    private record Scan(String label, int subject, int event, long item, String sha) {}

    private static Scan scan(int studyId) throws Exception {
        int n = SEQ.incrementAndGet();
        String label = "LOC-" + n + "-" + System.nanoTime() % 1_000_000;
        try (Connection c = DATA_SOURCE.getConnection()) {
            int ss = LifecycleFixtures.insertStudySubject(c, label, studyId, 1);
            int event = LifecycleFixtures.insertOne(c,
                    "INSERT INTO study_event (study_event_definition_id, study_subject_id, sample_ordinal, "
                            + "date_start, owner_id, status_id, date_created, subject_event_status_id, "
                            + "start_time_flag, end_time_flag) VALUES (2, " + ss + ", 1, '2026-03-04 09:30', 1, 1, "
                            + "now(), 1, false, false) RETURNING study_event_id");
            String sha = String.format("e%063d", System.nanoTime() % 1_000_000_000_000L + n);
            int item = LifecycleFixtures.insertOne(c,
                    "INSERT INTO ingest_item (kind, source_kind, stored_path, original_filename, laterality, "
                            + "received_at, status, bound_study_subject_id, bound_study_event_id, sha256, scan_index) "
                            + "VALUES ('e2e', 'upload', '/nowhere/" + label + ".e2e', '" + label + ".e2e', 'OD', now(), "
                            + "'BOUND', " + ss + ", " + event + ", '" + sha + "', 0) RETURNING ingest_item_id");
            return new Scan(label, ss, event, item, sha);
        }
    }

    /** A job; {@code onItem} false writes a legacy row without the ingest item. */
    private static long job(Scan s, String task, String status, boolean onItem, boolean onVisit, int minutesAgo)
            throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO retinal_inference_job (study_event_id, task, e2e_path, eye_laterality, status, "
                             + "enqueued_at, scan_index, e2e_sha256, ingest_item_id) "
                             + "VALUES (?, ?, '/nowhere.e2e', 'OD', ?, now() - (? * interval '1 minute'), 0, ?, ?) "
                             + "RETURNING job_id")) {
            if (onVisit) ps.setInt(1, s.event()); else ps.setNull(1, java.sql.Types.INTEGER);
            ps.setString(2, task);
            ps.setString(3, status);
            ps.setInt(4, minutesAgo);
            ps.setString(5, s.sha());
            if (onItem) ps.setLong(6, s.item()); else ps.setNull(6, java.sql.Types.BIGINT);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static List<Long> ids(JsonNode siblings) {
        List<Long> out = new ArrayList<>();
        for (JsonNode n : siblings) out.add(n.get("jobId").asLong());
        return out;
    }

    private static String visitName() throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT name, repeating FROM study_event_definition WHERE study_event_definition_id = 2");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getString(1) + (rs.getBoolean(2) ? " #1" : "");
        }
    }

    /* ------------------------------------------------------------------ */
    /* Tests                                                               */
    /* ------------------------------------------------------------------ */

    @Test
    void aFiledJobSaysWhereItLivesAndListsTheScansOtherAnalyses() throws Exception {
        Scan s = scan(1);
        long fluid = job(s, "fluid", "done", true, true, 40);
        long layers = job(s, "layers", "done", true, true, 30);
        long cancelled = job(s, "ga", "cancelled", true, true, 20);
        long legacy = job(s, "onl", "failed", false, true, 10);

        JsonNode d = getJson("/api/v1/retinal-jobs/" + layers, investigator(), 200);

        assertEquals(s.label(), d.get("subjectLabel").asText());
        assertEquals(2, d.get("subjectSeq").asInt(), "the second job filed for this subject");
        assertEquals(s.event(), d.get("studyEventId").asInt());
        assertEquals(visitName(), d.get("visitName").asText());
        assertEquals("2026-03-04", d.get("visitDate").asText());
        assertEquals(List.of(fluid, layers, legacy), ids(d.get("siblings")),
                "the same scan's live analyses, legacy row included, cancelled one left out");
        assertFalse(ids(d.get("siblings")).contains(cancelled));
        for (JsonNode sib : d.get("siblings")) {
            // Each sibling's number is the one its own address resolves.
            JsonNode resolved = getJson("/api/v1/subjects/" + s.label() + "/retinal-jobs/"
                    + sib.get("subjectSeq").asInt(), investigator(), 200);
            assertEquals(sib.get("jobId").asLong(), resolved.get("jobId").asLong());
            assertEquals(sib.get("task").asText(), resolved.get("task").asText());
        }
    }

    @Test
    void theVisitsJobListNumbersEachJobForItsAddress() throws Exception {
        Scan s = scan(1);
        job(s, "fluid", "done", true, true, 30);
        int ecrf;
        try (Connection c = DATA_SOURCE.getConnection()) {
            ecrf = LifecycleFixtures.insertEventCrf(c, s.event(), s.subject(), 1, 1);
        }
        long onCrf;
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO retinal_inference_job (event_crf_id, task, e2e_path, eye_laterality, status, "
                             + "enqueued_at, scan_index) VALUES (?, 'layers', '/nowhere.e2e', 'OS', 'done', now(), 1) "
                             + "RETURNING job_id")) {
            ps.setInt(1, ecrf);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                onCrf = rs.getLong(1);
            }
        }
        JsonNode list = getJson("/api/v1/event-crfs/" + ecrf + "/retinal-jobs", investigator(), 200);
        assertEquals(1, list.size(), list.toString());
        assertEquals(onCrf, list.get(0).get("jobId").asLong());
        assertEquals(2, list.get(0).get("subjectSeq").asInt(), "second of the subject's jobs: " + list);
    }

    @Test
    void aCancelledJobStillListsItself() throws Exception {
        Scan s = scan(1);
        long done = job(s, "fluid", "done", true, true, 20);
        long cancelled = job(s, "ga", "cancelled", true, true, 10);
        JsonNode d = getJson("/api/v1/retinal-jobs/" + cancelled, investigator(), 200);
        assertEquals(List.of(done, cancelled), ids(d.get("siblings")));
    }

    /**
     * A job taken off its visit has no address, trail or siblings. The detail
     * endpoint refuses such a job to everyone (it has no study), so the
     * lookups are asserted directly.
     */
    @Test
    void aJobWithNoVisitHasNoAddressTrailOrSiblings() throws Exception {
        Scan s = scan(1);
        long filed = job(s, "fluid", "done", true, true, 20);
        long detached = job(s, "layers", "done", true, false, 10);
        try (Connection c = DATA_SOURCE.getConnection()) {
            assertEquals(null, RetinalJobAccess.addressOf(c, detached));
            assertEquals(null, RetinalJobAccess.visitOf(c, detached));
            assertEquals(List.of(), RetinalJobAccess.siblingsOf(c, detached));
            // And the filed job does not list the detached one: it has no visit to be seen by.
            assertEquals(List.of(filed), RetinalJobAccess.siblingsOf(c, filed).stream()
                    .map(RetinalJobAccess.SiblingJob::jobId).toList());
        }
    }

    @Test
    void aCallerWhoMayNotSeeTheJobLearnsNoneOfIt() throws Exception {
        int other;
        try (Connection c = DATA_SOURCE.getConnection()) {
            other = LifecycleFixtures.insertStudy(c, null, "LOCOTHER" + SEQ.incrementAndGet(),
                    "Location other", "S_LOCOTHER" + SEQ.get(), 1);
        }
        Scan s = scan(other);
        long a = job(s, "fluid", "done", true, true, 20);
        job(s, "layers", "done", true, true, 10);

        MockHttpServletResponse r = mvc().perform(get("/api/v1/retinal-jobs/" + a).session(investigator()))
                .andReturn().getResponse();
        assertTrue(r.getStatus() == 403 || r.getStatus() == 404, r.getContentAsString());
        String body = r.getContentAsString();
        assertFalse(body.contains(s.label()), "no subject label: " + body);
        assertFalse(body.contains("siblings"), "no sibling list: " + body);
        assertFalse(body.contains("visitName"), "no visit: " + body);
    }
}
