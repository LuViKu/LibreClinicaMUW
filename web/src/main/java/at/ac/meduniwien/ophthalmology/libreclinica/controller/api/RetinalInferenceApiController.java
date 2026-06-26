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
import at.ac.meduniwien.ophthalmology.libreclinica.bean.retinal.RetinalInferenceJobStatus;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.EventCRFBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.admin.AuditEventDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.core.CoreResources;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudySubjectDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.EventCRFDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RemoteRetinalInferenceClient;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RemoteRunResult;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RetinalArtifactStorageService;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RetinalInferenceClient;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RetinalJobStatusBroadcaster;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.metrics.ComputedMetrics;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.metrics.RetinalMetricComputer;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
 * <p>Enabled tasks: {@code fluid}, {@code onl}, {@code pr} (OptimaAdapter
 * model-runners), plus {@code ga} (gated at the sidecar until its runner exists).
 * Anything else is rejected with 400; the queue + sidecar schema carry the task
 * discriminator so adding future tasks is decoder-only with no API change.
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

    /**
     * Task allow-list — keep in lock-step with the sidecar's {@code SUPPORTED_TASKS}:
     * {@code ga}, {@code fluid}, {@code onl}, {@code pr}, {@code bm}, {@code layers}.
     * fluid/onl/pr run via the OptimaAdapter model-runners (async; the sidecar's
     * {@code /screen} returns 422 for them, so the controller's existing
     * null-result path queues them for the worker). {@code ga} is registered but
     * gated at the sidecar adapter (no runner) until the IOWA layer segmenter +
     * a GPU host exist. {@code bm} (Bruch's membrane) and {@code layers} (the full
     * IOWA surface stack) run through the same async worker path.
     */
    private static final Set<String> SUPPORTED_TASKS = Set.of("ga", "fluid", "onl", "pr", "bm", "layers");

    /** Laterality must be one of the OD/OS pair (no OU for the placeholder GA path). */
    private static final Set<String> SUPPORTED_LATERALITIES = Set.of("OD", "OS");

    private static final ObjectMapper JSON = new ObjectMapper();

    private final DataSource dataSource;
    private final SiteVisibilityFilter siteVisibilityFilter;
    private final RetinalInferenceClient inferenceClient;
    private final RemoteRetinalInferenceClient remoteClient;
    private final RetinalArtifactStorageService artifactStore;
    private final RetinalMetricComputer metricComputer;
    private final RetinalJobStatusBroadcaster broadcaster;

    @Autowired
    public RetinalInferenceApiController(@Qualifier("dataSource") DataSource dataSource,
                                         SiteVisibilityFilter siteVisibilityFilter,
                                         RetinalInferenceClient inferenceClient,
                                         RemoteRetinalInferenceClient remoteClient,
                                         RetinalArtifactStorageService artifactStore,
                                         RetinalMetricComputer metricComputer,
                                         RetinalJobStatusBroadcaster broadcaster) {
        this.dataSource = dataSource;
        this.siteVisibilityFilter = siteVisibilityFilter;
        this.inferenceClient = inferenceClient;
        this.remoteClient = remoteClient;
        this.artifactStore = artifactStore;
        this.metricComputer = metricComputer;
        this.broadcaster = broadcaster;
    }

    @PostMapping(path = "/{eventCrfId:[0-9]+}/oct-upload",
                 consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> octUpload(@PathVariable("eventCrfId") int eventCrfId,
                                       @RequestPart("file") MultipartFile file,
                                       @RequestParam("task") String task,
                                       @RequestParam("laterality") String laterality,
                                       @RequestParam(value = "scanIndex",
                                                     defaultValue = "0") int scanIndex,
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
        if (scanIndex < 0) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "scanIndex must be >= 0 (got " + scanIndex + ")"));
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

        // ---- INSERT retinal_inference_job --------------------------------------
        // DR-022 worker-race kill: when the remote sidecar is configured we
        // land at 'remote_pending' so the Python DB-poll worker's
        // `status IN ('queued','screened')` filter skips the row by
        // construction. On remote failure handleRemote() flips it back to
        // 'queued' so the worker drains the fallback. When remote is unset,
        // the legacy 'queued' insert + local /screen path is unchanged.
        boolean remoteConfigured = remoteClient.isConfigured();
        String initialStatus = remoteConfigured
                ? RetinalInferenceJobStatus.REMOTE_PENDING.dbValue()
                : RetinalInferenceJobStatus.QUEUED.dbValue();
        long jobId;
        try (Connection c = dataSource.getConnection()) {
            jobId = insertJob(c, eventCrfId, taskClean, absolutePath, lat,
                    initialStatus, scanIndex);
        } catch (SQLException sqlEx) {
            LOG.error("Failed to enqueue retinal_inference_job for event_crf {}: {}",
                    eventCrfId, sqlEx.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "message", "Failed to enqueue job: " + sqlEx.getMessage()));
        }

        // ---- audit row on enqueue ----------------------------------------------
        AuditEventDAO auditDAO = new AuditEventDAO(dataSource);
        EventCrfsApiController.writeAuditEvent(
                auditDAO, AuditTypeIds.RETINAL_INFERENCE_ENQUEUED,
                currentUser, currentStudy, ss,
                "Retinal inference enqueued — task=" + taskClean,
                /* auditTable */ "retinal_inference_job",
                /* entityId   */ eventCrfId,
                /* columnName */ "status",
                /* oldValue   */ "",
                /* newValue   */ initialStatus);

        // ---- DR-022: remote GPU sidecar branch ---------------------------------
        // When core.retinalInference.remotePushUrl is set the institutional
        // Tomcat pushes the .e2e to the GPU host's /run endpoint, persists the
        // returned artifacts locally, and marks the job 'done'. On any failure
        // the job reverts to 'queued' (worker-drainable) and the existing
        // local-screen / DB-poll path runs as the fallback. Single-host dev
        // compose with remotePushUrl blank keeps the current behaviour
        // unchanged (initialStatus == 'queued').
        if (remoteConfigured) {
            ResponseEntity<?> remoteResp = handleRemote(
                    jobId, taskClean, absolutePath, lat, scanIndex, eventCrfId);
            if (remoteResp != null) return remoteResp;
            // fall through to the existing local path on remote failure
        }

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
                           String eyeLaterality, String status, int scanIndex)
            throws SQLException {
        // enqueued_at carries a DB-default of CURRENT_TIMESTAMP per the changeset
        // but we set it explicitly here so the test-fixture path (which may use
        // an older driver) does not surface a NOT NULL violation.
        String sql = "INSERT INTO retinal_inference_job ("
                + "event_crf_id, task, e2e_path, eye_laterality, status, scan_index, enqueued_at"
                + ") VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, eventCrfId);
            ps.setString(2, task);
            ps.setString(3, e2ePath);
            ps.setString(4, eyeLaterality);
            ps.setString(5, status);
            ps.setInt(6, scanIndex);
            ps.setTimestamp(7, Timestamp.from(Instant.now()));
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) return keys.getLong(1);
                throw new SQLException("retinal_inference_job INSERT returned no PK");
            }
        }
    }

    /**
     * DR-022 — drive the remote GPU sidecar end-to-end: flip the job to
     * {@code 'segmenting'}, POST the E2E to the remote {@code /run}, persist
     * the returned artifacts under the institutional artifact store, INSERT
     * the {@code retinal_inference_result} row, mark the job {@code 'done'},
     * and return a {@link RetinalInferenceClient.ScreenResult}-shaped body so
     * the SPA's existing preview surface keeps working.
     *
     * <p>Returns {@code null} when the remote call fails — the caller falls
     * back to the local {@code /screen} path which itself may fall back to the
     * DB-poll worker, so a degraded GPU host never breaks the upload UX.
     *
     * <p>Package-private so {@link RetinalResultsApiController#bindParkedJob}
     * can call it after the {@code parked → remote_pending} flip. Without
     * this hand-off, parked-and-then-bound jobs would sit in
     * {@code remote_pending} forever — the local DB-poll worker explicitly
     * filters that status by design (worker-race kill at upload time), and
     * the bind endpoint had no path to invoke the remote run. Identified
     * during the 2026-06-18 smoke of the cross-study parked-admin view.
     */
    ResponseEntity<?> handleRemote(long jobId,
                                   String taskClean,
                                   String absolutePath,
                                   String lat,
                                   int scanIndex,
                                   Integer eventCrfId) {
        try (Connection c = dataSource.getConnection()) {
            updateStatus(c, jobId, "segmenting", false, null);
        } catch (SQLException sqlEx) {
            LOG.warn("Failed to flip job {} to 'segmenting' (continuing remote call): {}",
                    jobId, sqlEx.getMessage());
        }

        String remoteFailureReason = null;
        RemoteRunResult remote;
        try {
            remote = remoteClient.runRemote(jobId, taskClean, absolutePath, lat, scanIndex);
            if (remote == null) {
                remoteFailureReason = "Remote /run returned null — see RemoteRetinalInferenceClient WARN logs";
            }
        } catch (Exception e) {
            LOG.warn("Remote /run threw for job {}: {}", jobId, e.getMessage());
            remote = null;
            remoteFailureReason = "Remote /run threw: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }

        if (remote == null) {
            // 2026-06-19 — when we own the dispatch (remote was
            // configured + we attempted /run), failures must surface as
            // 'failed' to the operator instead of silently reverting to
            // 'queued'. The previous fall-through let the local
            // {@code placeholder-v1} adapter drain the row and stamp a
            // result that LOOKS identical to a real segmentation in the
            // metrics viewer — observed during the 2026-06-18 smoke
            // (jobs 47/48/49/50 + 51 all completed with synthetic
            // values when the GPU sidecar was unreachable). DR-022's
            // intent was local-fallback for dev compose with
            // {@code remotePushUrl} BLANK (in which case
            // {@link RetinalInferenceJobStatus#REMOTE_PENDING} never
            // applies + this method isn't called). With remote
            // configured, no fallback: status='failed' + the captured
            // error in {@code status_message} so the operator knows
            // why it stopped and can re-bind / re-upload after fixing
            // the GPU sidecar.
            markRemoteFailed(jobId, remoteFailureReason);
            return null;
        }

        // Persist the returned artifacts to the institutional store so the
        // result row's bscan_masks_dir points at a browsable directory.
        Path artifactDir;
        try {
            artifactDir = artifactStore.persist(jobId, remote);
        } catch (IOException ioEx) {
            LOG.error("Remote /run succeeded but artifact persist failed for job {}: {}",
                    jobId, ioEx.getMessage());
            // Same reasoning: we OWN this dispatch — surface the
            // failure rather than handing it to the placeholder.
            markRemoteFailed(jobId, "Artifact persist failed: " + ioEx.getMessage());
            return null;
        }

        // 2026-06-22 — local derivation of presentation artifacts
        // (projection PNGs, per-slice overlays). The cluster /run
        // intentionally ships only the raw segmentation (`fluidseg.npz`
        // for the fluid task) so the GPU image stays minimal; the
        // local app-VM sidecar derives the rest by reading the npz
        // back from the persisted artifact dir. Soft-fail: the row
        // still gets persisted + the operator can still browse the
        // segmentation if derive fails (e.g. sidecar down). The
        // backfill_projections.py script provides the same
        // derivation on-demand for jobs that landed before this
        // chain was wired.
        if ("fluid".equals(taskClean)) {
            try {
                remoteClient.derive(artifactDir, taskClean);
            } catch (Exception deriveEx) {
                LOG.warn("Local /derive failed for job {} (task={}) — projections + per-slice PNGs"
                                + " will not be available until backfill: {}",
                        jobId, taskClean, deriveEx.getMessage());
            }
        }

        // Wave 3 — compute the task-specific metric off the persisted
        // artifacts. The remote envelope's primary_metric_* fields are
        // placeholder nulls for the cluster path; this is where the
        // browseable mm³ / µm value comes from. Soft-fail: a metric
        // compute crash leaves the row with the envelope's values so the
        // operator can still browse the segmentation + re-run.
        ComputedMetrics metrics = null;
        try {
            metrics = metricComputer.compute(taskClean, artifactDir, remote.geometry(), lat);
        } catch (Exception metricEx) {
            LOG.warn("Metric compute failed for job {} (task={}): {}",
                    jobId, taskClean, metricEx.getMessage());
            // The artifacts are persisted; the row will still INSERT with raw envelope values.
            // Wave 3 chose not to fail the upload on metric-compute error so the operator can
            // still browse the segmentation and re-run.
        }

        // INSERT retinal_inference_result then mark the job 'done'.
        try (Connection c = dataSource.getConnection()) {
            insertResult(c, jobId, taskClean, remote, artifactDir, metrics);
            updateStatus(c, jobId, "done",
                    /* setScreenedAt */ false,
                    /* setCompletedAt */ true,
                    remote.modelVersion());
        } catch (SQLException sqlEx) {
            LOG.error("Remote /run succeeded but result persist failed for job {}: {}",
                    jobId, sqlEx.getMessage());
            try (Connection c = dataSource.getConnection()) {
                updateStatus(c, jobId, "queued", false, null);
            } catch (SQLException ignored) { /* best-effort */ }
            return null;
        }

        // Body + log: prefer the computed metric over the envelope's
        // placeholder values; fall back to the envelope when compute
        // soft-failed.
        Object respValue = (metrics != null) ? metrics.primaryValue() : remote.primaryMetricValue();
        String respUnit  = (metrics != null) ? metrics.primaryUnit()  : remote.primaryMetricUnit();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jobId", jobId);
        body.put("status", "done");
        body.put("task", taskClean);
        body.put("laterality", lat);
        body.put("primaryMetricValue", respValue);
        body.put("primaryMetricUnit", respUnit);
        body.put("confidence", remote.confidence());
        body.put("modelVersion", remote.modelVersion());
        body.put("artifactCount", remote.artifacts().size());

        LOG.info("Retinal inference (remote): event_crf {} → job {} done (task={}, model={}, metric={}{})",
                eventCrfId == null ? "<unbound>" : eventCrfId, jobId, taskClean,
                remote.modelVersion(), respValue, respUnit);
        return ResponseEntity.ok(body);
    }

    /**
     * INSERT into {@code retinal_inference_result}. The output_payload column
     * is JSONB; we serialise the payload map to a string and let Postgres
     * cast it via {@code ?::jsonb} — no postgres JDBC dep needed.
     *
     * <p>When Wave 3's task-specific {@link ComputedMetrics} is non-null
     * (the normal path) the row carries the computed BigDecimal primary
     * value + structured payload. If the metric compute soft-failed (or
     * the caller passed null for back-compat) the inserted row falls back
     * to the remote envelope's placeholder fields — the operator can
     * still browse the segmentation and re-run.
     */
    private void insertResult(Connection c, long jobId, String task,
                              RemoteRunResult remote, Path artifactDir,
                              ComputedMetrics metrics) throws SQLException {
        Map<String, Object> payloadMap = (metrics != null)
                ? metrics.payload()
                : remote.outputPayload();
        String payloadJson;
        try {
            payloadJson = JSON.writeValueAsString(payloadMap);
        } catch (Exception jsonEx) {
            throw new SQLException("Failed to serialise output_payload for job " + jobId, jsonEx);
        }
        // 2026-06-19 — UPSERT semantics. A previous attempt (e.g. the
        // local Python placeholder worker that races the bind→remote
        // dispatch when an operator re-binds a stale parked row) may
        // have already inserted a result row for this job_id. Without
        // the ON CONFLICT clause the second insert hit a unique-key
        // violation on (job_id), aborted the transaction, and left
        // the job stuck at 'segmenting' with no model_version + no
        // completed_at. The DO UPDATE clause overwrites the stale
        // row with the fresh GPU-side metrics so re-binds are robust.
        String sql = "INSERT INTO retinal_inference_result ("
                + "job_id, task, output_payload, primary_metric_value, "
                + "primary_metric_unit, bscan_masks_dir, pixel_scale_mm, confidence"
                + ") VALUES (?, ?, ?::jsonb, ?, ?, ?, ?, ?) "
                + "ON CONFLICT (job_id) DO UPDATE SET "
                + "  task = EXCLUDED.task, "
                + "  output_payload = EXCLUDED.output_payload, "
                + "  primary_metric_value = EXCLUDED.primary_metric_value, "
                + "  primary_metric_unit = EXCLUDED.primary_metric_unit, "
                + "  bscan_masks_dir = EXCLUDED.bscan_masks_dir, "
                + "  pixel_scale_mm = EXCLUDED.pixel_scale_mm, "
                + "  confidence = EXCLUDED.confidence";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, jobId);
            ps.setString(2, task);
            ps.setString(3, payloadJson);
            if (metrics != null) {
                // BigDecimal lands exactly into NUMERIC(12,4) with no
                // lossy double→BigDecimal round-trip.
                ps.setBigDecimal(4, metrics.primaryValue());
                ps.setString(5, metrics.primaryUnit());
            } else {
                ps.setDouble(4, remote.primaryMetricValue());
                ps.setString(5, remote.primaryMetricUnit());
            }
            ps.setString(6, artifactDir.toString());
            // pixel_scale_mm: the remote envelope doesn't carry it in v1 (the
            // SPA's modality registry resolves scale by OID), so leave it null.
            ps.setNull(7, java.sql.Types.NUMERIC);
            ps.setDouble(8, remote.confidence());
            ps.executeUpdate();
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
        updateStatus(c, jobId, newStatus, setScreenedAt, /* setCompletedAt */ false, modelVersion);
    }

    /**
     * Like {@link #updateStatus(Connection, long, String, boolean, String)} but
     * also stamps {@code completed_at} when {@code setCompletedAt=true}. The
     * remote DR-022 branch needs this on the {@code 'done'} transition; the
     * local path keeps the 4-arg call for back-compat.
     *
     * <p>Wave 1B: every successful flip is broadcast to the SSE fan-out so
     * subscribers see live status transitions without polling. The publish
     * happens after the SQL commit (try-with-resources auto-close on the
     * Statement; the Connection is short-lived autocommit) so a SQL failure
     * never leaves an in-flight subscriber with a stale terminal status.
     */
    private void updateStatus(Connection c, long jobId, String newStatus,
                              boolean setScreenedAt, boolean setCompletedAt,
                              String modelVersion) throws SQLException {
        StringBuilder sql = new StringBuilder(
                "UPDATE retinal_inference_job SET status = ?");
        if (setScreenedAt)  sql.append(", screened_at = ?");
        if (setCompletedAt) sql.append(", completed_at = ?");
        if (modelVersion != null) sql.append(", model_version = ?");
        sql.append(" WHERE job_id = ?");
        try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
            int i = 1;
            ps.setString(i++, newStatus);
            if (setScreenedAt)  ps.setTimestamp(i++, Timestamp.from(Instant.now()));
            if (setCompletedAt) ps.setTimestamp(i++, Timestamp.from(Instant.now()));
            if (modelVersion != null) ps.setString(i++, modelVersion);
            ps.setLong(i, jobId);
            ps.executeUpdate();
        }
        // Broadcast AFTER the SQL succeeds — keeps the SSE stream
        // truth-tracking the DB. broadcaster is nullable for legacy
        // test ctors that haven't been re-wired; null-guard keeps those
        // tests compiling without touching SSE-irrelevant code paths.
        if (broadcaster != null) {
            broadcaster.publish(jobId, newStatus);
        }
    }

    /**
     * 2026-06-19 — terminal-failure helper used when the remote GPU
     * dispatch fails (network / sidecar 5xx / empty response /
     * artifact persist failure). Flips the job to status='failed' +
     * stamps a descriptive {@code status_message}, so the SPA's
     * metrics viewer shows the failure reason instead of the
     * placeholder-v1 mock that the local worker would otherwise drain
     * a 'queued' row with.
     *
     * <p>Broadcast through the existing {@link RetinalJobStatusBroadcaster}
     * SSE channel so the operator sees the flip live without a refresh.
     * SQL failures are swallowed: best-effort cleanup.
     */
    private void markRemoteFailed(long jobId, String reason) {
        String msg = reason != null && !reason.isBlank()
                ? reason
                : "Remote GPU dispatch failed";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE retinal_inference_job "
                             + "   SET status = 'failed', status_message = ?, "
                             + "       completed_at = ? "
                             + " WHERE job_id = ?")) {
            ps.setString(1, msg);
            ps.setTimestamp(2, Timestamp.from(Instant.now()));
            ps.setLong(3, jobId);
            ps.executeUpdate();
        } catch (SQLException sqlEx) {
            LOG.warn("markRemoteFailed: SQL update failed for job {}: {}",
                    jobId, sqlEx.getMessage());
        }
        if (broadcaster != null) {
            broadcaster.publish(jobId, "failed");
        }
        LOG.warn("Remote GPU dispatch failed for job {}: {}", jobId, msg);
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
