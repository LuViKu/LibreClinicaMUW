/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;
import jakarta.servlet.http.HttpSession;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RetinalJobStatusBroadcaster;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Wave 1B — Server-Sent Events stream of {@code retinal_inference_job}
 * status transitions, keyed by jobId.
 *
 * <p>The SPA's RetinalMetricsView subscribes to
 * {@code GET /pages/api/v1/retinal-jobs/{jobId}/status/stream} while a job
 * is in flight (status ∈ {remote_pending, queued, screening, screened,
 * segmenting}); the connection stays open until the job hits a terminal
 * state ({@code done} / {@code failed}) or the 5-minute idle timeout
 * fires. {@link RetinalJobStatusBroadcaster} provides the fan-out;
 * {@code RetinalInferenceApiController.updateStatus(...)} is the only
 * publisher.
 *
 * <p>Authorization mirrors the rest of the retinal API: session-bound
 * userBean + study, plus a {@link SiteVisibilityFilter} check against
 * the job's owning study. A 401 / 400 / 403 / 404 ResponseEntity is
 * returned synchronously when the guard fails; only once the visibility
 * check passes does the controller install an emitter.
 *
 * <p><strong>Per-request timeout:</strong> 5 minutes. Long-running jobs
 * (segmentation can take 20-60 s on a contended GPU host) finish well
 * within that window; the heartbeat keeps proxies happy. The SPA's
 * {@code useJobStatusStream} composable handles re-subscription with
 * exponential backoff on the close event so a longer-than-expected job
 * still surfaces its terminal transition.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Retinal job status SSE",
     description = "Server-Sent Events fan-out for retinal_inference_job status transitions.")
public class RetinalJobStatusSseController {

    private static final Logger LOG = LoggerFactory.getLogger(RetinalJobStatusSseController.class);

    /** Per-emitter idle timeout. Matches the broadcaster's eviction window. */
    private static final long IDLE_TIMEOUT_MS = 300_000L;

    private final DataSource dataSource;
    private final SiteVisibilityFilter siteVisibilityFilter;
    private final RetinalJobStatusBroadcaster broadcaster;

    @Autowired
    public RetinalJobStatusSseController(@Qualifier("dataSource") DataSource dataSource,
                                         SiteVisibilityFilter siteVisibilityFilter,
                                         RetinalJobStatusBroadcaster broadcaster) {
        this.dataSource = dataSource;
        this.siteVisibilityFilter = siteVisibilityFilter;
        this.broadcaster = broadcaster;
    }

    @GetMapping("/retinal-jobs/{jobId:[0-9]+}/status/stream")
    public Object stream(@PathVariable("jobId") long jobId, HttpSession session) {
        // ---- auth + study guards -------------------------------------
        UserAccountBean currentUser = (UserAccountBean) session.getAttribute("userBean");
        if (currentUser == null || currentUser.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        StudyBean currentStudy = (StudyBean) session.getAttribute("study");
        if (currentStudy == null || currentStudy.getId() == 0) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "No active study bound to the session — POST /pages/api/v1/me/activeStudy first."
            ));
        }

        // ---- resolve owning study via the job → ec → se → ss chain ---
        JobLookup lookup;
        try (Connection c = dataSource.getConnection()) {
            lookup = fetchStudyIdForJob(c, jobId);
        } catch (SQLException sqlEx) {
            LOG.error("SSE stream: failed to resolve study for job {}: {}",
                    jobId, sqlEx.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "message", "Failed to resolve study for retinal_inference_job: " + sqlEx.getMessage()));
        }
        if (lookup == null) {
            return ResponseEntity.status(404).body(Map.of(
                    "message", "No retinal_inference_job with id " + jobId));
        }

        // ---- site-visibility check -----------------------------------
        StudyUserRoleBean currentRole = (StudyUserRoleBean) session.getAttribute("userRole");
        Set<Integer> visibleStudyIds = siteVisibilityFilter.visibleStudyIds(
                currentUser, currentStudy, currentRole);
        if (lookup.studyId == null || !visibleStudyIds.contains(lookup.studyId)) {
            return ResponseEntity.status(403).body(Map.of(
                    "message", "retinal_inference_job " + jobId + " belongs to a different study"));
        }

        // ---- install the emitter -------------------------------------
        SseEmitter emitter = new SseEmitter(IDLE_TIMEOUT_MS);
        return broadcaster.subscribe(jobId, emitter);
    }

    /**
     * Result of the job → ec → se → ss study lookup. Distinguishes
     * "row missing" (return null → 404) from "row found, no study chain"
     * (studyId field is null → 403 via the visibility check).
     */
    private static final class JobLookup {
        final Integer studyId;
        JobLookup(Integer studyId) { this.studyId = studyId; }
    }

    /**
     * Same query shape as {@code RetinalResultsApiController.fetchJobDetail},
     * trimmed to the study_id lookup. Parked jobs (event_crf_id NULL)
     * surface a present row with NULL study_id; the controller then
     * returns 403 — matching the read-side controller's behaviour for
     * that branch.
     */
    private JobLookup fetchStudyIdForJob(Connection c, long jobId) throws SQLException {
        String sql = "SELECT ss.study_id "
                + "  FROM retinal_inference_job j "
                + "  LEFT JOIN event_crf ec ON ec.event_crf_id = j.event_crf_id "
                + "  LEFT JOIN study_event se ON se.study_event_id = ec.study_event_id "
                + "  LEFT JOIN study_subject ss ON ss.study_subject_id = se.study_subject_id "
                + " WHERE j.job_id = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, jobId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                int sid = rs.getInt("study_id");
                return new JobLookup(rs.wasNull() ? null : sid);
            }
        }
    }
}
