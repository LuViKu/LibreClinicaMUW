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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.sql.DataSource;
import jakarta.servlet.http.HttpSession;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudySubjectBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.EventCRFBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.admin.AuditEventDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.core.CoreResources;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudySubjectDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.EventCRFDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RetinalInferenceClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Phase E.7 — Retinal inference sidecar upload endpoint.
 *
 * <p>One endpoint: {@code POST /pages/api/v1/event-crfs/{eventCrfId}/oct-upload}
 * (multipart). Accepts an E2E binary plus {@code task} + {@code laterality}
 * form fields, persists the file to the configured upload directory,
 * INSERTs a row into {@code retinal_inference_job}, and calls the sidecar's
 * synchronous {@code POST /screen} via {@link RetinalInferenceClient}.
 *
 * <p>v1 enables {@code task='ga'} only. Anything else is rejected with 400;
 * the queue + sidecar schema already carry the task discriminator so adding
 * future tasks (fluid, layers, ...) is decoder-only with no API change.
 *
 * <p>Authorization mirrors {@link EventCrfsApiController}: session-bound
 * userBean + study, and a {@link SiteVisibilityFilter} check against the
 * resolved event_crf's study_subject. 401 / 400 / 403 / 404 / 409 mirror
 * the rest of the API.
 *
 * <p>Fallback semantics: on RestClient timeout / connection failure /
 * non-2xx response, the row is left at {@code status='queued'} and the
 * caller is told 202 so the SPA polls; the background worker (separate
 * sidecar process) eventually picks up the row via FOR UPDATE SKIP LOCKED.
 *
 * <p>One {@code audit_log_event} row is emitted on enqueue, matching the
 * packed-actionMessage convention from
 * {@link EventCrfsApiController#writeAuditEvent}.
 */
@RestController
@RequestMapping("/api/v1/event-crfs")
@Tag(name = "Retinal inference",
     description = "OCT upload → retinal_inference_job enqueue + sync sidecar screen.")
public class RetinalInferenceApiController {

    private static final Logger LOG = LoggerFactory.getLogger(RetinalInferenceApiController.class);

    /** Filesystem fallback when {@code core.retinalInference.e2eUploadsPath} is unset. */
    public static final String DEFAULT_UPLOADS_PATH = "/var/lib/libreclinica/e2e-uploads";

    /** v1 task allow-list. Extend in lock-step with the sidecar's {@code SUPPORTED_TASKS}. */
    private static final Set<String> SUPPORTED_TASKS = Set.of("ga");

    /** Laterality must be one of the OD/OS pair (no OU for the placeholder GA path). */
    private static final Set<String> SUPPORTED_LATERALITIES = Set.of("OD", "OS");

    private final DataSource dataSource;
    private final SiteVisibilityFilter siteVisibilityFilter;
    private final RetinalInferenceClient inferenceClient;

    @Autowired
    public RetinalInferenceApiController(@Qualifier("dataSource") DataSource dataSource,
                                         SiteVisibilityFilter siteVisibilityFilter,
                                         RetinalInferenceClient inferenceClient) {
        this.dataSource = dataSource;
        this.siteVisibilityFilter = siteVisibilityFilter;
        this.inferenceClient = inferenceClient;
    }

    @PostMapping(path = "/{eventCrfId:[0-9]+}/oct-upload",
                 consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> octUpload(@PathVariable("eventCrfId") int eventCrfId,
                                       @RequestPart("file") MultipartFile file,
                                       @RequestPart("task") String task,
                                       @RequestPart("laterality") String laterality,
                                       HttpSession session) {

        // ---- auth + study guards ------------------------------------------------
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

        // ---- request-shape gates ------------------------------------------------
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "file part is required"));
        }
        String taskClean = task == null ? "" : task.trim();
        if (!SUPPORTED_TASKS.contains(taskClean)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "Unsupported task '" + taskClean + "' — v1 enables: " + SUPPORTED_TASKS));
        }
        String lat = laterality == null ? "" : laterality.trim().toUpperCase();
        if (!SUPPORTED_LATERALITIES.contains(lat)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "laterality must be one of " + SUPPORTED_LATERALITIES + " (got '" + laterality + "')"));
        }

        // ---- resolve event_crf + site-visibility guard --------------------------
        EventCRFDAO eventCrfDAO = new EventCRFDAO(dataSource);
        EventCRFBean ecb = eventCrfDAO.findByPK(eventCrfId);
        if (ecb == null || ecb.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No event_crf with id " + eventCrfId));
        }
        StudySubjectDAO ssDAO = new StudySubjectDAO(dataSource);
        StudySubjectBean ss = (StudySubjectBean) ssDAO.findByPK(ecb.getStudySubjectId());
        StudyUserRoleBean currentRole = (StudyUserRoleBean) session.getAttribute("userRole");
        Set<Integer> visibleStudyIds = siteVisibilityFilter.visibleStudyIds(
                currentUser, currentStudy, currentRole);
        if (ss == null || !visibleStudyIds.contains(ss.getStudyId())) {
            return ResponseEntity.status(403).body(Map.of("message",
                    "event_crf " + eventCrfId + " belongs to a different study"));
        }

        // ---- persist the upload to disk ----------------------------------------
        Path savedPath;
        try {
            Path dir = Paths.get(uploadsDir());
            Files.createDirectories(dir);
            String filename = UUID.randomUUID() + ".e2e";
            savedPath = dir.resolve(filename);
            try (var in = file.getInputStream()) {
                Files.copy(in, savedPath);
            }
        } catch (IOException ioEx) {
            LOG.error("Failed to persist E2E upload for event_crf {}: {}", eventCrfId, ioEx.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "message", "Failed to persist E2E: " + ioEx.getMessage()));
        }
        String absolutePath = savedPath.toString();

        // ---- INSERT retinal_inference_job (status='queued') --------------------
        long jobId;
        try (Connection c = dataSource.getConnection()) {
            jobId = insertJob(c, eventCrfId, taskClean, absolutePath, lat);
        } catch (SQLException sqlEx) {
            LOG.error("Failed to enqueue retinal_inference_job for event_crf {}: {}",
                    eventCrfId, sqlEx.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "message", "Failed to enqueue job: " + sqlEx.getMessage()));
        }

        // ---- audit row on enqueue ----------------------------------------------
        AuditEventDAO auditDAO = new AuditEventDAO(dataSource);
        EventCrfsApiController.writeAuditEvent(
                auditDAO, currentUser, currentStudy, ss,
                "Retinal inference enqueued — task=" + taskClean,
                /* auditTable */ "retinal_inference_job",
                /* entityId   */ eventCrfId,
                /* columnName */ "status",
                /* oldValue   */ "",
                /* newValue   */ "queued");

        // ---- flip to 'screening' + call sidecar /screen ------------------------
        try (Connection c = dataSource.getConnection()) {
            updateStatus(c, jobId, "screening", /* setScreenedAt */ false, /* modelVersion */ null);
        } catch (SQLException sqlEx) {
            LOG.warn("Failed to flip job {} to 'screening' (continuing to call sidecar): {}",
                    jobId, sqlEx.getMessage());
        }

        RetinalInferenceClient.ScreenResult result;
        try {
            result = inferenceClient.screenFast(jobId, taskClean, absolutePath, lat);
        } catch (Exception e) {
            // The client already catches everything internally and returns null,
            // but a belt-and-braces guard keeps the controller path stable even
            // if a future client refactor surfaces an unchecked exception.
            LOG.warn("Sidecar call threw for job {}: {}", jobId, e.getMessage());
            result = null;
        }

        if (result == null) {
            // Sidecar offline / timed out / bad body — leave it for the worker.
            try (Connection c = dataSource.getConnection()) {
                updateStatus(c, jobId, "queued", false, null);
            } catch (SQLException ignored) { /* best-effort revert */ }

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("jobId", jobId);
            body.put("status", "queued");
            LOG.info("Retinal inference job {} queued for worker (sidecar /screen unavailable)", jobId);
            return ResponseEntity.status(202).body(body);
        }

        // ---- success: mark 'screened' + return sidecar payload -----------------
        try (Connection c = dataSource.getConnection()) {
            updateStatus(c, jobId, "screened", /* setScreenedAt */ true,
                    result.modelVersion() == null ? "" : result.modelVersion());
        } catch (SQLException sqlEx) {
            LOG.warn("Sidecar succeeded but failed to mark job {} 'screened': {}",
                    jobId, sqlEx.getMessage());
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jobId", jobId);
        body.put("status", "screened");
        body.put("task", taskClean);
        body.put("laterality", lat);
        body.put("approxAreaMm2", result.approxAreaMm2());
        body.put("confidence", result.confidence());
        body.put("modelVersion", result.modelVersion());
        body.put("fovealBscanIndex", result.foveaBscanIndex());

        LOG.info("Retinal inference: event_crf {} → job {} screened (task={}, model={}, area={})",
                eventCrfId, jobId, taskClean, result.modelVersion(), result.approxAreaMm2());
        return ResponseEntity.ok(body);
    }

    /* ====================================================================== */
    /* helpers                                                                */
    /* ====================================================================== */

    private long insertJob(Connection c, int eventCrfId, String task, String e2ePath,
                           String eyeLaterality) throws SQLException {
        // enqueued_at carries a DB-default of CURRENT_TIMESTAMP per the changeset
        // but we set it explicitly here so the test-fixture path (which may use
        // an older driver) does not surface a NOT NULL violation.
        String sql = "INSERT INTO retinal_inference_job ("
                + "event_crf_id, task, e2e_path, eye_laterality, status, enqueued_at"
                + ") VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, eventCrfId);
            ps.setString(2, task);
            ps.setString(3, e2ePath);
            ps.setString(4, eyeLaterality);
            ps.setString(5, "queued");
            ps.setTimestamp(6, Timestamp.from(Instant.now()));
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) return keys.getLong(1);
                throw new SQLException("retinal_inference_job INSERT returned no PK");
            }
        }
    }

    /**
     * Update the job's {@code status} (+ optional {@code screened_at} and
     * {@code model_version}). Used for both the optimistic flip to
     * {@code 'screening'} before the sidecar call and the terminal
     * transitions afterwards.
     */
    private void updateStatus(Connection c, long jobId, String newStatus,
                              boolean setScreenedAt, String modelVersion) throws SQLException {
        StringBuilder sql = new StringBuilder(
                "UPDATE retinal_inference_job SET status = ?");
        if (setScreenedAt) sql.append(", screened_at = ?");
        if (modelVersion != null) sql.append(", model_version = ?");
        sql.append(" WHERE job_id = ?");
        try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
            int i = 1;
            ps.setString(i++, newStatus);
            if (setScreenedAt) ps.setTimestamp(i++, Timestamp.from(Instant.now()));
            if (modelVersion != null) ps.setString(i++, modelVersion);
            ps.setLong(i, jobId);
            ps.executeUpdate();
        }
    }

    /**
     * Resolve the on-disk uploads directory. Reads
     * {@code core.retinalInference.e2eUploadsPath} via {@link CoreResources};
     * falls back to {@link #DEFAULT_UPLOADS_PATH} when unset / blank /
     * unreachable (the latter happens in some unit-test contexts where
     * {@code CoreResources} hasn't been initialised).
     */
    private static String uploadsDir() {
        try {
            String raw = CoreResources.getField("core.retinalInference.e2eUploadsPath");
            if (raw != null && !raw.isBlank()) return raw.trim();
        } catch (Exception ignored) {
            // CoreResources unavailable -- fall back.
        }
        return DEFAULT_UPLOADS_PATH;
    }
}
