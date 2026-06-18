/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudySubjectBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.EventCRFBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.admin.AuditEventDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudySubjectDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.EventCRFDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RemoteRetinalInferenceClient;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RetinalArtifactStorageService;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RetinalJobStatusBroadcaster;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.StudySubjectFinder;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.StudySubjectMatch;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.time.Duration;

/**
 * Phase E.7 — Retinal inference read-side API.
 *
 * <p>The Wave 4 SPA viewer consumes four endpoints:
 *
 * <ul>
 *   <li>{@code GET /api/v1/retinal-jobs/{jobId}} — fat DTO joining
 *       {@code retinal_inference_job} + {@code retinal_inference_result}
 *       with shorthand companion-file URLs.</li>
 *   <li>{@code GET /api/v1/event-crfs/{eventCrfId}/retinal-jobs} —
 *       per-event-CRF summary list, ordered by enqueue time desc.</li>
 *   <li>{@code GET /api/v1/study-subjects/{studySubjectId}/retinal-jobs}
 *       — per-subject summary list across all event-CRFs.</li>
 *   <li>{@code GET /api/v1/retinal-jobs/{jobId}/artifacts/{name}} —
 *       streams a single seg or companion file with the right content
 *       type. Path-traversal guarded.</li>
 * </ul>
 *
 * <p>Authorization mirrors {@link RetinalInferenceApiController}:
 * session-bound userBean + study, a {@link SiteVisibilityFilter} check
 * against the job's owning study. 401 / 403 / 404 mirror the rest of
 * the API.
 *
 * <p>Companion files ({@code bscan.dcm}, {@code fundus.png},
 * {@code geometry.json}) live under
 * {@code <bscanStorePath>/<e2eUuid>/} — the preprocess sidecar's
 * persistence path; they are looked up by basename-of(e2e_path) minus
 * the {@code .e2e} suffix. Segmentation artifacts live under the
 * result row's {@code bscan_masks_dir}.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Retinal results",
     description = "Read-side endpoints for the SPA viewer (Wave 4).")
public class RetinalResultsApiController {

    private static final Logger LOG = LoggerFactory.getLogger(RetinalResultsApiController.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Companion files the preprocess sidecar writes per e2eUuid.
     * Order matters — kept stable so the SPA can iterate without
     * sorting client-side.
     */
    private static final List<String> COMPANION_NAMES =
            List.of("bscan.dcm", "fundus.png", "geometry.json");

    /**
     * Path-traversal guard for {@code GET …/artifacts/{name}}.
     * Allows only alphanumerics + a few common punctuation chars seg
     * runners actually emit ({@code .}, {@code _}, {@code -}, {@code (},
     * {@code )}, {@code #}, space).
     */
    private static final java.util.regex.Pattern SAFE_ARTIFACT_NAME =
            java.util.regex.Pattern.compile("[A-Za-z0-9_.()# -]+");

    private final DataSource dataSource;
    private final SiteVisibilityFilter siteVisibilityFilter;
    private final RetinalArtifactStorageService artifactStore;
    private final StudySubjectFinder studySubjectFinder;
    private final RemoteRetinalInferenceClient remoteClient;
    private final RetinalJobStatusBroadcaster broadcaster;

    @Autowired
    public RetinalResultsApiController(@Qualifier("dataSource") DataSource dataSource,
                                       SiteVisibilityFilter siteVisibilityFilter,
                                       RetinalArtifactStorageService artifactStore,
                                       StudySubjectFinder studySubjectFinder,
                                       RemoteRetinalInferenceClient remoteClient,
                                       RetinalJobStatusBroadcaster broadcaster) {
        this.dataSource = dataSource;
        this.siteVisibilityFilter = siteVisibilityFilter;
        this.artifactStore = artifactStore;
        this.studySubjectFinder = studySubjectFinder;
        this.remoteClient = remoteClient;
        this.broadcaster = broadcaster;
    }

    /**
     * Wave 1B (2026-06-18): IT-friendly back-compat constructor used by
     * the Wave-3 happy-path IT and its sibling sliced tests. The bind +
     * search + SSE-broadcast paths are exercised via the
     * full-arg constructor; tests that exercise only the read-side
     * endpoints can keep the existing 3-arg ctor and pass null for the
     * new collaborators (the call paths guard for null).
     */
    public RetinalResultsApiController(DataSource dataSource,
                                       SiteVisibilityFilter siteVisibilityFilter,
                                       RetinalArtifactStorageService artifactStore) {
        this(dataSource, siteVisibilityFilter, artifactStore, null, null, null);
    }

    /* ====================================================================== */
    /* DTOs                                                                   */
    /* ====================================================================== */

    public record PrimaryMetric(BigDecimal value, String unit) { }

    public record RetinalJobDetailDto(
            long jobId,
            int eventCrfId,
            String task,
            String laterality,
            String status,
            String modelVersion,
            String enqueuedAt,
            String completedAt,
            String e2eUuid,
            PrimaryMetric primaryMetric,
            Map<String, Object> outputPayload,
            Double confidence,
            List<String> artifactNames,
            List<String> companionNames,
            String fundusUrl,
            String geometryUrl,
            String bscanDcmUrl) { }

    public record RetinalJobSummaryDto(
            long jobId,
            String task,
            String laterality,
            String status,
            String modelVersion,
            String completedAt,
            PrimaryMetric primaryMetric) { }

    /* ====================================================================== */
    /* GET /retinal-jobs/{jobId}                                              */
    /* ====================================================================== */

    @GetMapping(path = "/retinal-jobs/{jobId:[0-9]+}",
                produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> getJob(@PathVariable("jobId") long jobId,
                                    HttpSession session) {
        ResponseEntity<?> guard = guardSession(session);
        if (guard != null) return guard;

        JobRow row;
        try (Connection c = dataSource.getConnection()) {
            row = fetchJobDetail(c, jobId);
        } catch (SQLException sqlEx) {
            LOG.error("Failed to fetch retinal job {}: {}", jobId, sqlEx.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "message", "Failed to fetch retinal job: " + sqlEx.getMessage()));
        }
        if (row == null) {
            return ResponseEntity.status(404).body(Map.of(
                    "message", "No retinal_inference_job with id " + jobId));
        }
        ResponseEntity<?> visGuard = guardJobVisibility(row, session);
        if (visGuard != null) return visGuard;

        String e2eUuid = e2eUuidFromPath(row.e2ePath);
        List<String> artifactNames = listArtifactNames(row.bscanMasksDir);
        List<String> companions = listCompanionNames(e2eUuid);

        String fundusUrl   = companions.contains("fundus.png")
                ? "/pages/api/v1/retinal-jobs/" + jobId + "/artifacts/fundus.png" : null;
        String geometryUrl = companions.contains("geometry.json")
                ? "/pages/api/v1/retinal-jobs/" + jobId + "/artifacts/geometry.json" : null;
        String bscanDcmUrl = companions.contains("bscan.dcm")
                ? "/pages/api/v1/retinal-jobs/" + jobId + "/artifacts/bscan.dcm" : null;

        Map<String, Object> outputPayload = parsePayload(row.outputPayloadJson);
        PrimaryMetric pm = primaryMetric(row.primaryMetricValue, row.primaryMetricUnit);

        RetinalJobDetailDto dto = new RetinalJobDetailDto(
                row.jobId,
                row.eventCrfId,
                row.task,
                row.eyeLaterality,
                row.status,
                row.modelVersion,
                toIso(row.enqueuedAt),
                toIso(row.completedAt),
                e2eUuid,
                pm,
                outputPayload,
                row.confidence,
                artifactNames,
                companions,
                fundusUrl,
                geometryUrl,
                bscanDcmUrl);
        return ResponseEntity.ok(dto);
    }

    /* ====================================================================== */
    /* GET /event-crfs/{eventCrfId}/retinal-jobs                              */
    /* ====================================================================== */

    @GetMapping(path = "/event-crfs/{eventCrfId:[0-9]+}/retinal-jobs",
                produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> listByEventCrf(@PathVariable("eventCrfId") int eventCrfId,
                                            HttpSession session) {
        ResponseEntity<?> guard = guardSession(session);
        if (guard != null) return guard;

        // Visibility: derive the job's owning study via the event_crf →
        // study_event → study_subject path. Empty list is fine — the SPA
        // simply renders an empty viewer. But the visibility check has
        // to gate on at least one matching job; we do the check once on
        // the event_crf itself, then list all the jobs for it.
        Integer studyId;
        try (Connection c = dataSource.getConnection()) {
            studyId = fetchStudyIdForEventCrf(c, eventCrfId);
        } catch (SQLException sqlEx) {
            LOG.error("Failed to resolve study for event_crf {}: {}", eventCrfId, sqlEx.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "message", "Failed to resolve study for event_crf: " + sqlEx.getMessage()));
        }
        if (studyId == null) {
            return ResponseEntity.status(404).body(Map.of(
                    "message", "No event_crf with id " + eventCrfId));
        }
        ResponseEntity<?> visGuard = guardStudyVisibility(studyId, session,
                "event_crf " + eventCrfId + " belongs to a different study");
        if (visGuard != null) return visGuard;

        List<RetinalJobSummaryDto> out = new ArrayList<>();
        String sql = "SELECT j.job_id, j.task, j.eye_laterality, j.status, j.model_version, "
                + "       j.completed_at, "
                + "       r.primary_metric_value, r.primary_metric_unit "
                + "  FROM retinal_inference_job j "
                + "  LEFT JOIN retinal_inference_result r ON r.job_id = j.job_id "
                + " WHERE j.event_crf_id = ? "
                + " ORDER BY j.enqueued_at DESC";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, eventCrfId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(summaryFromRow(rs));
                }
            }
        } catch (SQLException sqlEx) {
            LOG.error("Failed to list retinal jobs for event_crf {}: {}",
                    eventCrfId, sqlEx.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "message", "Failed to list retinal jobs: " + sqlEx.getMessage()));
        }
        return ResponseEntity.ok(out);
    }

    /* ====================================================================== */
    /* GET /study-subjects/{studySubjectId}/retinal-jobs                      */
    /* ====================================================================== */

    @GetMapping(path = "/study-subjects/{studySubjectId:[0-9]+}/retinal-jobs",
                produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> listByStudySubject(@PathVariable("studySubjectId") int studySubjectId,
                                                HttpSession session) {
        ResponseEntity<?> guard = guardSession(session);
        if (guard != null) return guard;

        Integer studyId;
        try (Connection c = dataSource.getConnection()) {
            studyId = fetchStudyIdForStudySubject(c, studySubjectId);
        } catch (SQLException sqlEx) {
            LOG.error("Failed to resolve study for study_subject {}: {}",
                    studySubjectId, sqlEx.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "message", "Failed to resolve study for study_subject: " + sqlEx.getMessage()));
        }
        if (studyId == null) {
            return ResponseEntity.status(404).body(Map.of(
                    "message", "No study_subject with id " + studySubjectId));
        }
        ResponseEntity<?> visGuard = guardStudyVisibility(studyId, session,
                "study_subject " + studySubjectId + " belongs to a different study");
        if (visGuard != null) return visGuard;

        List<RetinalJobSummaryDto> out = new ArrayList<>();
        String sql = "SELECT j.job_id, j.task, j.eye_laterality, j.status, j.model_version, "
                + "       j.completed_at, "
                + "       r.primary_metric_value, r.primary_metric_unit "
                + "  FROM retinal_inference_job j "
                + "  JOIN event_crf ec ON ec.event_crf_id = j.event_crf_id "
                + "  JOIN study_event se ON se.study_event_id = ec.study_event_id "
                + "  LEFT JOIN retinal_inference_result r ON r.job_id = j.job_id "
                + " WHERE se.study_subject_id = ? "
                + " ORDER BY j.enqueued_at DESC";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, studySubjectId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(summaryFromRow(rs));
                }
            }
        } catch (SQLException sqlEx) {
            LOG.error("Failed to list retinal jobs for study_subject {}: {}",
                    studySubjectId, sqlEx.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "message", "Failed to list retinal jobs: " + sqlEx.getMessage()));
        }
        return ResponseEntity.ok(out);
    }

    /* ====================================================================== */
    /* GET /retinal-jobs/{jobId}/artifacts/{name}                             */
    /* ====================================================================== */

    @GetMapping(path = "/retinal-jobs/{jobId:[0-9]+}/artifacts/{name:.+}")
    public ResponseEntity<?> streamArtifact(@PathVariable("jobId") long jobId,
                                            @PathVariable("name") String name,
                                            HttpSession session,
                                            HttpServletResponse response) {
        ResponseEntity<?> guard = guardSession(session);
        if (guard != null) return guard;

        if (name == null || name.isBlank() || !SAFE_ARTIFACT_NAME.matcher(name).matches()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "Artifact name '" + name + "' contains disallowed characters"));
        }

        JobRow row;
        try (Connection c = dataSource.getConnection()) {
            row = fetchJobDetail(c, jobId);
        } catch (SQLException sqlEx) {
            LOG.error("Failed to fetch retinal job {}: {}", jobId, sqlEx.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "message", "Failed to fetch retinal job: " + sqlEx.getMessage()));
        }
        if (row == null) {
            return ResponseEntity.status(404).body(Map.of(
                    "message", "No retinal_inference_job with id " + jobId));
        }
        ResponseEntity<?> visGuard = guardJobVisibility(row, session);
        if (visGuard != null) return visGuard;

        Path target;
        boolean isCompanion = COMPANION_NAMES.contains(name);
        try {
            if (isCompanion) {
                String e2eUuid = e2eUuidFromPath(row.e2ePath);
                target = switch (name) {
                    case "bscan.dcm"     -> artifactStore.resolveBscanDcm(e2eUuid);
                    case "fundus.png"    -> artifactStore.resolveFundus(e2eUuid);
                    case "geometry.json" -> artifactStore.resolveGeometry(e2eUuid);
                    default -> throw new NoSuchFileException(name);
                };
            } else {
                if (row.bscanMasksDir == null || row.bscanMasksDir.isBlank()) {
                    return ResponseEntity.status(404).body(Map.of(
                            "message", "No segmentation directory for job " + jobId));
                }
                Path dir = Paths.get(row.bscanMasksDir).toAbsolutePath().normalize();
                Path resolved = dir.resolve(name).normalize();
                if (!resolved.startsWith(dir) || !Files.isRegularFile(resolved)) {
                    return ResponseEntity.status(404).body(Map.of(
                            "message", "No artifact '" + name + "' for job " + jobId));
                }
                target = resolved;
            }
        } catch (NoSuchFileException nfe) {
            return ResponseEntity.status(404).body(Map.of(
                    "message", "No artifact '" + name + "' for job " + jobId));
        } catch (IllegalArgumentException iae) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", iae.getMessage()));
        } catch (IOException ioEx) {
            LOG.error("Failed to resolve artifact '{}' for job {}: {}",
                    name, jobId, ioEx.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "message", "Failed to resolve artifact: " + ioEx.getMessage()));
        }

        // Stream the bytes directly to the response — the application's
        // configured HttpMessageConverter chain doesn't include
        // ResourceHttpMessageConverter, so a ResponseEntity<Resource> would
        // be picked up by Jackson and serialised as JSON (then fail on the
        // ChannelInputStream property). Direct streaming sidesteps the
        // converter selection entirely.
        MediaType mediaType = mediaTypeForName(name);
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(mediaType.toString());
        long size;
        try {
            size = Files.size(target);
            response.setContentLengthLong(size);
        } catch (IOException sizeEx) {
            LOG.warn("Could not stat {} for Content-Length: {}", target, sizeEx.getMessage());
        }
        if ("fundus.png".equals(name) || "geometry.json".equals(name)) {
            response.setHeader(HttpHeaders.CACHE_CONTROL,
                    CacheControl.maxAge(Duration.ofHours(1)).cachePrivate().getHeaderValue());
        }
        try {
            Files.copy(target, response.getOutputStream());
            response.getOutputStream().flush();
        } catch (IOException copyEx) {
            LOG.error("Failed to stream artifact '{}' for job {}: {}",
                    name, jobId, copyEx.getMessage());
            // Headers were already sent — best we can do is abort the body.
        }
        return null;
    }

    /* ====================================================================== */
    /* PATCH /retinal-jobs/{jobId}/bind — Wave 1B park-bind                   */
    /* ====================================================================== */

    /**
     * Request body for {@link #bindParkedJob(long, BindRequest, HttpSession)}.
     */
    public record BindRequest(int eventCrfId) { }

    /**
     * Wave 1B — bind a previously parked retinal_inference_job (commit
     * via the public OCT-upload portal with no scheduled visit) to a
     * concrete event_crf. The job's status flips from {@code parked} to
     * {@code remote_pending} (when the remote GPU sidecar is
     * configured) or {@code queued} so the worker / sidecar picks it up
     * immediately.
     *
     * <p>409 Conflict when the job is not currently {@code parked}; 403
     * when the requested event_crf is outside the session user's
     * site-visibility scope; 404 when either the job or the event_crf
     * is missing.
     *
     * <p>One audit_log_event row of type
     * {@link AuditTypeIds#RETINAL_PARK_BIND} is emitted on success;
     * old_value carries {@code "parked"} and new_value carries the new
     * status so the timeline shows the bind + the transition in a
     * single row.
     */
    @PatchMapping(path = "/retinal-jobs/{jobId:[0-9]+}/bind",
                  produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> bindParkedJob(@PathVariable("jobId") long jobId,
                                           @RequestBody BindRequest body,
                                           HttpSession session) {
        ResponseEntity<?> guard = guardSession(session);
        if (guard != null) return guard;

        if (body == null || body.eventCrfId() <= 0) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "eventCrfId is required"));
        }
        int eventCrfId = body.eventCrfId();

        // ---- load the parked job ------------------------------------
        ParkedJob job;
        try (Connection c = dataSource.getConnection()) {
            job = fetchParkedJob(c, jobId);
        } catch (SQLException sqlEx) {
            LOG.error("Failed to fetch retinal job {} for bind: {}",
                    jobId, sqlEx.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "message", "Failed to fetch retinal job: " + sqlEx.getMessage()));
        }
        if (job == null) {
            return ResponseEntity.status(404).body(Map.of(
                    "message", "No retinal_inference_job with id " + jobId));
        }
        if (!"parked".equals(job.status)) {
            return ResponseEntity.status(409).body(Map.of(
                    "message", "Job is not parked (status=" + job.status + ")"));
        }

        // ---- visibility on the target event_crf ---------------------
        EventCRFDAO eventCrfDAO = new EventCRFDAO(dataSource);
        EventCRFBean ecb = eventCrfDAO.findByPK(eventCrfId);
        if (ecb == null || ecb.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of(
                    "message", "No event_crf with id " + eventCrfId));
        }
        StudySubjectDAO ssDAO = new StudySubjectDAO(dataSource);
        StudySubjectBean ss = (StudySubjectBean) ssDAO.findByPK(ecb.getStudySubjectId());
        if (ss == null || ss.getStudyId() == 0) {
            return ResponseEntity.status(404).body(Map.of(
                    "message", "event_crf " + eventCrfId + " has no resolvable study"));
        }
        UserAccountBean currentUser = (UserAccountBean) session.getAttribute("userBean");
        StudyBean currentStudy = (StudyBean) session.getAttribute("study");
        StudyUserRoleBean currentRole = (StudyUserRoleBean) session.getAttribute("userRole");
        Set<Integer> visibleStudyIds = siteVisibilityFilter.visibleStudyIds(
                currentUser, currentStudy, currentRole);
        if (!visibleStudyIds.contains(ss.getStudyId())) {
            return ResponseEntity.status(403).body(Map.of(
                    "message", "event_crf " + eventCrfId + " belongs to a different study"));
        }

        // ---- flip the job + emit audit + broadcast ------------------
        String newStatus = (remoteClient != null && remoteClient.isConfigured())
                ? "remote_pending"
                : "queued";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE retinal_inference_job "
                             + "   SET event_crf_id = ?, status = ?, "
                             + "       screened_at = NULL, segmenting_at = NULL "
                             + " WHERE job_id = ?")) {
            ps.setInt(1, eventCrfId);
            ps.setString(2, newStatus);
            ps.setLong(3, jobId);
            int updated = ps.executeUpdate();
            if (updated == 0) {
                return ResponseEntity.status(404).body(Map.of(
                        "message", "No retinal_inference_job with id " + jobId));
            }
        } catch (SQLException sqlEx) {
            LOG.error("Failed to bind retinal job {} to event_crf {}: {}",
                    jobId, eventCrfId, sqlEx.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "message", "Failed to bind retinal job: " + sqlEx.getMessage()));
        }

        AuditEventDAO auditDAO = new AuditEventDAO(dataSource);
        EventCrfsApiController.writeAuditEvent(
                auditDAO, AuditTypeIds.RETINAL_PARK_BIND,
                currentUser, currentStudy, ss,
                "Retinal job park-bind — eventCrfId=" + eventCrfId,
                /* auditTable */ "retinal_inference_job",
                /* entityId   */ (int) jobId,
                /* columnName */ "status",
                /* oldValue   */ "parked",
                /* newValue   */ newStatus);

        if (broadcaster != null) {
            broadcaster.publish(jobId, newStatus);
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("jobId", jobId);
        resp.put("status", newStatus);
        LOG.info("Retinal park-bind: job {} -> event_crf {} (status={})",
                jobId, eventCrfId, newStatus);
        return ResponseEntity.ok(resp);
    }

    /* ====================================================================== */
    /* GET /study-subjects/search — Wave 1B patient search                    */
    /* ====================================================================== */

    /**
     * Wave 1B — staff-portal label prefix search. Backs the Wave 2B
     * "Patient suchen" modal. Always filtered to the session user's
     * site-visibility scope; never leaks subjects from studies the
     * user can't see.
     *
     * @param q     label prefix (case-insensitive); blank → empty list
     * @param limit hard ceiling on rows returned; clamped to [1, 50]
     */
    @GetMapping(path = "/study-subjects/search",
                produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> searchSubjects(@RequestParam("q") String q,
                                            @RequestParam(value = "limit", defaultValue = "10") int limit,
                                            HttpSession session) {
        ResponseEntity<?> guard = guardSession(session);
        if (guard != null) return guard;

        if (studySubjectFinder == null) {
            // Defensive: legacy 3-arg ctor (read-only IT path). The
            // production wiring always populates the finder.
            return ResponseEntity.ok(List.of());
        }

        int clamped = Math.max(1, Math.min(50, limit));
        String prefix = (q == null) ? "" : q.trim();
        if (prefix.isBlank()) {
            return ResponseEntity.ok(List.of());
        }

        UserAccountBean currentUser = (UserAccountBean) session.getAttribute("userBean");
        StudyBean currentStudy = (StudyBean) session.getAttribute("study");
        StudyUserRoleBean currentRole = (StudyUserRoleBean) session.getAttribute("userRole");
        Set<Integer> visibleStudyIds = siteVisibilityFilter.visibleStudyIds(
                currentUser, currentStudy, currentRole);

        List<StudySubjectMatch> matches = studySubjectFinder.findByLabelPrefix(prefix, clamped);
        List<Map<String, Object>> out = new ArrayList<>(matches.size());
        for (StudySubjectMatch m : matches) {
            if (!visibleStudyIds.contains(m.studyId())) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("studySubjectId", m.studySubjectId());
            row.put("label", m.subjectLabel());
            row.put("studyId", m.studyId());
            row.put("studyName", m.studyName());
            row.put("siteName", m.siteName());
            out.add(row);
        }
        return ResponseEntity.ok(out);
    }

    /* ====================================================================== */
    /* helpers                                                                */
    /* ====================================================================== */

    /** Inline state-only row carrier — bridges JDBC ResultSet to DTO assembly. */
    private static final class JobRow {
        long jobId;
        int eventCrfId;
        String task;
        String e2ePath;
        String eyeLaterality;
        String status;
        Timestamp enqueuedAt;
        Timestamp completedAt;
        String modelVersion;
        // result-side (nullable for jobs without a result row)
        String outputPayloadJson;
        BigDecimal primaryMetricValue;
        String primaryMetricUnit;
        String bscanMasksDir;
        Double confidence;
        // visibility — derived via the event_crf → study_event → study_subject chain
        Integer studyId;
    }

    /** Slim row used by the bind endpoint — only the bits the flip needs. */
    private static final class ParkedJob {
        long jobId;
        String status;
    }

    private ParkedJob fetchParkedJob(Connection c, long jobId) throws SQLException {
        String sql = "SELECT job_id, status FROM retinal_inference_job WHERE job_id = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, jobId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                ParkedJob job = new ParkedJob();
                job.jobId = rs.getLong("job_id");
                job.status = rs.getString("status");
                return job;
            }
        }
    }

    private JobRow fetchJobDetail(Connection c, long jobId) throws SQLException {
        String sql = "SELECT j.job_id, j.event_crf_id, j.task, j.e2e_path, "
                + "       j.eye_laterality, j.status, j.enqueued_at, j.completed_at, j.model_version, "
                + "       r.output_payload, r.primary_metric_value, r.primary_metric_unit, "
                + "       r.bscan_masks_dir, r.confidence, ss.study_id "
                + "  FROM retinal_inference_job j "
                + "  LEFT JOIN retinal_inference_result r ON r.job_id = j.job_id "
                + "  LEFT JOIN event_crf ec ON ec.event_crf_id = j.event_crf_id "
                + "  LEFT JOIN study_event se ON se.study_event_id = ec.study_event_id "
                + "  LEFT JOIN study_subject ss ON ss.study_subject_id = se.study_subject_id "
                + " WHERE j.job_id = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, jobId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                JobRow row = new JobRow();
                row.jobId        = rs.getLong("job_id");
                row.eventCrfId   = rs.getInt("event_crf_id");
                row.task         = rs.getString("task");
                row.e2ePath      = rs.getString("e2e_path");
                row.eyeLaterality= rs.getString("eye_laterality");
                row.status       = rs.getString("status");
                row.enqueuedAt   = rs.getTimestamp("enqueued_at");
                row.completedAt  = rs.getTimestamp("completed_at");
                row.modelVersion = rs.getString("model_version");
                row.outputPayloadJson = rs.getString("output_payload");
                row.primaryMetricValue = rs.getBigDecimal("primary_metric_value");
                row.primaryMetricUnit  = rs.getString("primary_metric_unit");
                row.bscanMasksDir      = rs.getString("bscan_masks_dir");
                double cf = rs.getDouble("confidence");
                row.confidence = rs.wasNull() ? null : cf;
                int sid = rs.getInt("study_id");
                row.studyId = rs.wasNull() ? null : sid;
                return row;
            }
        }
    }

    private Integer fetchStudyIdForEventCrf(Connection c, int eventCrfId) throws SQLException {
        String sql = "SELECT ss.study_id "
                + "  FROM event_crf ec "
                + "  JOIN study_event se ON se.study_event_id = ec.study_event_id "
                + "  JOIN study_subject ss ON ss.study_subject_id = se.study_subject_id "
                + " WHERE ec.event_crf_id = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, eventCrfId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                int sid = rs.getInt("study_id");
                return rs.wasNull() ? null : sid;
            }
        }
    }

    private Integer fetchStudyIdForStudySubject(Connection c, int studySubjectId) throws SQLException {
        String sql = "SELECT study_id FROM study_subject WHERE study_subject_id = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, studySubjectId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                int sid = rs.getInt("study_id");
                return rs.wasNull() ? null : sid;
            }
        }
    }

    private RetinalJobSummaryDto summaryFromRow(ResultSet rs) throws SQLException {
        long jobId = rs.getLong("job_id");
        String task = rs.getString("task");
        String laterality = rs.getString("eye_laterality");
        String status = rs.getString("status");
        String modelVersion = rs.getString("model_version");
        Timestamp completedAt = rs.getTimestamp("completed_at");
        BigDecimal pv = rs.getBigDecimal("primary_metric_value");
        String pu = rs.getString("primary_metric_unit");
        return new RetinalJobSummaryDto(
                jobId, task, laterality, status, modelVersion,
                toIso(completedAt), primaryMetric(pv, pu));
    }

    /** 401 if no authenticated user, 400 if no active study. */
    private ResponseEntity<?> guardSession(HttpSession session) {
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
        return null;
    }

    /** 403 when the supplied study_id is outside the user's site visibility. */
    private ResponseEntity<?> guardStudyVisibility(Integer studyId, HttpSession session, String denyMessage) {
        if (studyId == null) {
            return ResponseEntity.status(403).body(Map.of("message", denyMessage));
        }
        UserAccountBean currentUser = (UserAccountBean) session.getAttribute("userBean");
        StudyBean currentStudy = (StudyBean) session.getAttribute("study");
        StudyUserRoleBean currentRole = (StudyUserRoleBean) session.getAttribute("userRole");
        Set<Integer> visibleStudyIds = siteVisibilityFilter.visibleStudyIds(
                currentUser, currentStudy, currentRole);
        if (!visibleStudyIds.contains(studyId)) {
            return ResponseEntity.status(403).body(Map.of("message", denyMessage));
        }
        return null;
    }

    private ResponseEntity<?> guardJobVisibility(JobRow row, HttpSession session) {
        return guardStudyVisibility(row.studyId, session,
                "retinal_inference_job " + row.jobId + " belongs to a different study");
    }

    /** Trim a single trailing ".e2e" — match what the upload controller saves. */
    private static String e2eUuidFromPath(String e2ePath) {
        if (e2ePath == null) return null;
        String base = Paths.get(e2ePath).getFileName().toString();
        if (base.toLowerCase().endsWith(".e2e")) {
            base = base.substring(0, base.length() - 4);
        }
        return base;
    }

    private List<String> listArtifactNames(String dir) {
        if (dir == null || dir.isBlank()) return List.of();
        Path p = Paths.get(dir);
        if (!Files.isDirectory(p)) return List.of();
        try (var stream = Files.list(p)) {
            List<String> names = new ArrayList<>();
            stream.filter(Files::isRegularFile)
                  .map(f -> f.getFileName().toString())
                  .sorted()
                  .forEach(names::add);
            return names;
        } catch (IOException ioEx) {
            LOG.warn("Failed to list artifacts in {}: {}", dir, ioEx.getMessage());
            return List.of();
        }
    }

    private List<String> listCompanionNames(String e2eUuid) {
        if (e2eUuid == null || e2eUuid.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String name : COMPANION_NAMES) {
            try {
                switch (name) {
                    case "bscan.dcm"     -> artifactStore.resolveBscanDcm(e2eUuid);
                    case "fundus.png"    -> artifactStore.resolveFundus(e2eUuid);
                    case "geometry.json" -> artifactStore.resolveGeometry(e2eUuid);
                    default -> { }
                }
                out.add(name);
            } catch (NoSuchFileException nfe) {
                // companion absent — skip
            } catch (IllegalArgumentException iae) {
                // bad UUID — none of the companions can be resolved.
                return List.of();
            } catch (IOException ioEx) {
                LOG.warn("Failed to resolve companion {} for e2eUuid {}: {}",
                        name, e2eUuid, ioEx.getMessage());
            }
        }
        return out;
    }

    private static PrimaryMetric primaryMetric(BigDecimal value, String unit) {
        if (value == null && (unit == null || unit.isBlank())) return null;
        return new PrimaryMetric(value, unit);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parsePayload(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return JSON.readValue(json, Map.class);
        } catch (Exception jsonEx) {
            LOG.warn("Failed to parse output_payload JSON: {}", jsonEx.getMessage());
            return Map.of();
        }
    }

    private static String toIso(Timestamp ts) {
        return ts == null ? null : ts.toInstant().toString();
    }

    private static MediaType mediaTypeForName(String name) {
        String lower = name.toLowerCase();
        if (lower.endsWith(".csv"))  return MediaType.parseMediaType("text/csv");
        if (lower.endsWith(".npy"))  return MediaType.APPLICATION_OCTET_STREAM;
        if (lower.endsWith(".npz"))  return MediaType.APPLICATION_OCTET_STREAM;
        if (lower.endsWith(".dcm"))  return MediaType.parseMediaType("application/dicom");
        if (lower.endsWith(".png"))  return MediaType.IMAGE_PNG;
        if (lower.endsWith(".json")) return MediaType.APPLICATION_JSON;
        return MediaType.APPLICATION_OCTET_STREAM;
    }
}
