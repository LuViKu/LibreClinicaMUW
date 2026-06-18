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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RetinalJobStatusBroadcaster;

/**
 * Wave 1B IT — drives a real
 * {@link RetinalJobStatusSseController#stream(long, jakarta.servlet.http.HttpSession)}
 * call against the Testcontainers Postgres, then asserts the broadcaster's
 * publish path emits one SSE event per subscriber.
 *
 * <p>MockMvc's {@code getAsyncResult()} hangs for the full SseEmitter
 * timeout because nothing ever calls {@code complete()} on the emitter
 * (it's a long-lived streaming construct). So we directly inspect the
 * broadcaster's registry after the request: by the time
 * {@code perform(get(...))} returns, the controller has installed the
 * emitter, which is what the SPA cares about. The fan-out itself is
 * unit-tested separately in {@code RetinalJobStatusBroadcasterTest}.
 */
class RetinalJobStatusSseControllerDatabaseIT extends AbstractApiControllerDatabaseIT {

    private RetinalJobStatusBroadcaster broadcaster;
    private SiteVisibilityFilter visibilityFilter;

    @BeforeEach
    void wireBroadcaster() throws Exception {
        broadcaster = new RetinalJobStatusBroadcaster();
        visibilityFilter = new SiteVisibilityFilter(DATA_SOURCE);

        // Seed a fluid retinal_inference_job bound to event_crf 1 (which
        // belongs to study_subject 1 / study 1 — visible to the demo
        // "root" user).
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO retinal_inference_job ("
                             + "job_id, event_crf_id, task, e2e_path, eye_laterality, "
                             + "status, enqueued_at) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            ps.setLong(1, 7777L);
            ps.setInt(2, 1);
            ps.setString(3, "fluid");
            ps.setString(4, "/tmp/sse-it.e2e");
            ps.setString(5, "OD");
            ps.setString(6, "queued");
            ps.setTimestamp(7, new Timestamp(System.currentTimeMillis()));
            ps.executeUpdate();
        }
    }

    @AfterEach
    void cleanup() throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM retinal_inference_job WHERE job_id = ?")) {
            ps.setLong(1, 7777L);
            ps.executeUpdate();
        }
    }

    private MockMvc mockMvc() {
        return MockMvcBuilders.standaloneSetup(
                new RetinalJobStatusSseController(DATA_SOURCE, visibilityFilter, broadcaster))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void stream_registersEmitter_andBroadcastReachesIt() throws Exception {
        // perform() returns once the controller method returns the
        // SseEmitter; the broadcaster sees the subscribe before then.
        mockMvc().perform(get("/api/v1/retinal-jobs/7777/status/stream")
                .session(authenticatedSession()));

        // The broadcaster's registry now has one subscriber for job 7777.
        assertEquals(1, broadcaster.subscriberCount(7777L));

        // Publish a transition; if the wiring is right the emitter sees it.
        // We can't easily intercept the bytes off the SseEmitter from MockMvc
        // — the happy-path assertion is "the broadcaster routed the event
        // without throwing and the subscriber count is unchanged" (i.e. the
        // send succeeded so the emitter wasn't evicted).
        broadcaster.publish(7777L, "segmenting");

        assertEquals(1, broadcaster.subscriberCount(7777L),
                "successful publish should not evict the subscriber");
    }

    @Test
    void stream_returns403_whenJobNotVisible() throws Exception {
        // Insert a job with NULL event_crf_id — the row exists but its
        // study chain doesn't resolve → 403 (visibility deny). 404 would
        // imply the row didn't exist, which would be misleading.
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO retinal_inference_job ("
                             + "job_id, event_crf_id, task, e2e_path, eye_laterality, "
                             + "status, enqueued_at) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            ps.setLong(1, 7778L);
            ps.setNull(2, java.sql.Types.INTEGER);
            ps.setString(3, "fluid");
            ps.setString(4, "/tmp/sse-it-noec.e2e");
            ps.setString(5, "OD");
            ps.setString(6, "parked");
            ps.setTimestamp(7, new Timestamp(System.currentTimeMillis()));
            ps.executeUpdate();
        }
        try {
            mockMvc().perform(get("/api/v1/retinal-jobs/7778/status/stream")
                    .session(authenticatedSession()))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                            .status().isForbidden());
        } finally {
            try (Connection c = DATA_SOURCE.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                         "DELETE FROM retinal_inference_job WHERE job_id = ?")) {
                ps.setLong(1, 7778L);
                ps.executeUpdate();
            }
        }
    }

    @Test
    void stream_returns404_whenJobDoesNotExist() throws Exception {
        mockMvc().perform(get("/api/v1/retinal-jobs/987654321/status/stream")
                .session(authenticatedSession()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isNotFound());
    }
}
