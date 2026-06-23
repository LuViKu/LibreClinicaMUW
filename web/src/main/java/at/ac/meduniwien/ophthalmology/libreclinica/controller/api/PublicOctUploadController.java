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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import javax.sql.DataSource;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.retinal.RetinalInferenceJobStatus;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.core.CoreResources;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.EventCandidate;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RemoteRetinalInferenceClient;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.StudySubjectFinder;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.StudySubjectMatch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Public OCT-upload portal — see plan
 * {@code /Users/lukas/.claude/plans/robust-jumping-eich.md}.
 *
 * <p>Two endpoints (plus an undo) backing the staff-facing portal at
 * {@code /app/oct-upload}. No authentication is required: the
 * institutional reverse proxy is the only access gate (DR-022 sibling).
 * The {@link at.ac.meduniwien.ophthalmology.libreclinica.config.SecurityConfig}
 * whitelist places {@code /pages/api/v1/public/oct-upload/**} under
 * {@code permitAll()}.
 *
 * <ul>
 *   <li>{@code POST /resolve} — JSON in, JSON out. Takes (patientId,
 *       scanDate, laterality) triples extracted client-side from the
 *       .e2e header and returns per-scan resolution states
 *       (suggested / novisit / nopatient / ambiguous) so the SPA can
 *       render the review queue.</li>
 *   <li>{@code POST /commit} — multipart. Persists the .e2e file +
 *       INSERTs a {@code retinal_inference_job} row + emits an
 *       {@code OCT_UPLOAD_PUBLIC} audit row (user_id NULL).</li>
 *   <li>{@code DELETE /{jobId}} — undo within 60 s; deletes the job
 *       row + the .e2e file.</li>
 * </ul>
 *
 * <h2>v1 task selection</h2>
 *
 * <p>The portal does not surface a task picker; every committed job
 * lands with {@code task='fluid'} as the v1 default. Multi-task
 * selection (ga, layers, ...) is a follow-up — the authenticated
 * {@code RetinalInferenceApiController} keeps its task picker for
 * staff who do choose. Documented in the plan's "Out of scope" list.
 */
@RestController
@RequestMapping("/api/v1/public/oct-upload")
@Tag(name = "Public OCT upload portal",
     description = "Unauthenticated drag-and-drop OCT (.e2e) upload that resolves "
                 + "across all studies via PatientId + scan date.")
public class PublicOctUploadController {

    private static final Logger LOG = LoggerFactory.getLogger(PublicOctUploadController.class);

    /** Default task when the portal commits — see class-Javadoc on v1 task selection. */
    private static final String DEFAULT_TASK = "fluid";

    /** Laterality field gate. OU is rejected — the .e2e parser only ever emits OD/OS. */
    private static final Set<String> SUPPORTED_LATERALITIES = Set.of("OD", "OS");

    /** Filesystem fallback when {@code core.retinalInference.e2eUploadsPath} is unset. */
    public static final String DEFAULT_UPLOADS_PATH = "/var/lib/libreclinica/e2e-uploads";

    /** Undo window. Mirrors the SPA's "Rückgängig" link. */
    static final Duration UNDO_WINDOW = Duration.ofSeconds(60);

    private final DataSource dataSource;
    private final StudySubjectFinder studySubjectFinder;
    /**
     * 2026-06-18 — best-effort preprocess sidecar invocation at upload
     * commit time. Without this, the {@code fundus.png}/{@code bscan.dcm}/
     * {@code geometry.json} companion files only exist when (and IF) the
     * inference pipeline runs to completion; queued + parked uploads
     * leave the operator unable to browse the just-uploaded scan. The
     * dispatch is fire-and-continue: a sidecar failure logs a warning
     * and the upload still completes. Nullable so the IT-friendly
     * back-compat ctor stays valid.
     */
    private final RemoteRetinalInferenceClient remoteClient;
    /**
     * 2026-06-19 — used by the async post-commit pipeline to fire the
     * full remote-GPU inference run after the {@code commit} response
     * has already gone back to the operator. Without this hand-off
     * public-portal commits drop into the local Python worker which
     * runs {@code placeholder-v1} and never produces real segmentation
     * artifacts. Cross-controller wiring mirrors the bind endpoint
     * (also calls {@link RetinalInferenceApiController#handleRemote}).
     * Nullable so the IT-friendly back-compat ctor stays valid.
     */
    private final RetinalInferenceApiController inferenceController;

    @Autowired
    public PublicOctUploadController(@Qualifier("dataSource") DataSource dataSource,
                                     StudySubjectFinder studySubjectFinder,
                                     RemoteRetinalInferenceClient remoteClient,
                                     RetinalInferenceApiController inferenceController) {
        this.dataSource = dataSource;
        this.studySubjectFinder = studySubjectFinder;
        this.remoteClient = remoteClient;
        this.inferenceController = inferenceController;
    }

    /**
     * Wave 1B (2026-06-18): IT-friendly back-compat ctor. The new
     * collaborators (remoteClient, inferenceController) are null-safe:
     * when null, the commit path skips the async preprocess+remote
     * dispatch and behaves as the pre-Wave-2B portal did.
     */
    public PublicOctUploadController(DataSource dataSource,
                                     StudySubjectFinder studySubjectFinder) {
        this(dataSource, studySubjectFinder, null, null);
    }

    /* ====================================================================== */
    /* POST /resolve                                                          */
    /* ====================================================================== */

    @PostMapping(path = "/resolve",
                 consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> resolve(@RequestBody ResolveRequest request) {
        if (request == null || request.scans() == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "scans[] is required"));
        }
        List<ResolveResponseScan> out = new ArrayList<>(request.scans().size());
        for (ResolveRequestScan scan : request.scans()) {
            out.add(resolveOne(scan));
        }
        return ResponseEntity.ok(Map.of("scans", out));
    }

    private ResolveResponseScan resolveOne(ResolveRequestScan scan) {
        if (scan == null) {
            return new ResolveResponseScan(null, List.of(), "nopatient");
        }
        String patientId = scan.patientId() == null ? "" : scan.patientId().trim();
        LocalDate scanDate = parseLocalDate(scan.scanDate());

        if (patientId.isBlank()) {
            return new ResolveResponseScan(scan.patientId(), List.of(), "nopatient");
        }
        List<StudySubjectMatch> matches = studySubjectFinder.findByLabelAcrossStudies(patientId);
        if (matches.isEmpty()) {
            return new ResolveResponseScan(patientId, List.of(), "nopatient");
        }

        List<ResolveCandidate> candidates = new ArrayList<>(matches.size());
        int candidatesWithEvent = 0;
        for (StudySubjectMatch m : matches) {
            Optional<EventCandidate> ev = (scanDate == null)
                    ? Optional.empty()
                    : studySubjectFinder.findEventOnDate(m.studySubjectId(), scanDate);
            if (ev.isPresent()) candidatesWithEvent++;
            candidates.add(new ResolveCandidate(
                    m.studyId(), m.studyName(), m.studyOid(),
                    m.studySubjectId(), m.subjectLabel(),
                    m.siteName(), ev.orElse(null)));
        }

        String state;
        if (matches.size() > 1) {
            state = "ambiguous";
        } else if (candidatesWithEvent == 1) {
            state = "suggested";
        } else {
            state = "novisit";
        }
        return new ResolveResponseScan(patientId, candidates, state);
    }

    /* ====================================================================== */
    /* POST /commit                                                           */
    /* ====================================================================== */

    @PostMapping(path = "/commit",
                 consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> commit(@RequestPart("file") MultipartFile file,
                                    @RequestParam("patientId") String patientId,
                                    @RequestParam("scanDate") String scanDateRaw,
                                    @RequestParam("laterality") String laterality,
                                    @RequestParam(value = "scanIndex", defaultValue = "0") int scanIndex,
                                    @RequestParam(value = "eventCrfId", required = false) Integer eventCrfId,
                                    // 2026-06-23 — studyEventId binds the job to a planned visit
                                    // when no event_crf exists yet. Mutually exclusive with the
                                    // other two binding modes; commit accepts exactly one of
                                    // {eventCrfId, studyEventId, park}.
                                    @RequestParam(value = "studyEventId", required = false) Integer studyEventId,
                                    @RequestParam(value = "park", defaultValue = "false") boolean park,
                                    // Wave 1B (2026-06-18): when the resolve response carried
                                    // state='ambiguous' AND the staff picked, the SPA sets
                                    // disambiguated=true and supplies candidateCount so the
                                    // audit timeline records WHICH candidate was chosen out of
                                    // how many. Both fields are optional + default to no-op.
                                    @RequestParam(value = "disambiguated", defaultValue = "false") boolean disambiguated,
                                    @RequestParam(value = "candidateCount", defaultValue = "0") int candidateCount) {

        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "file part is required"));
        }
        String pid = patientId == null ? "" : patientId.trim();
        if (pid.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "patientId is required"));
        }
        LocalDate scanDate = parseLocalDate(scanDateRaw);
        if (scanDate == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "scanDate must be ISO yyyy-MM-dd (got '" + scanDateRaw + "')"));
        }
        String lat = laterality == null ? "" : laterality.trim().toUpperCase(Locale.ROOT);
        if (!SUPPORTED_LATERALITIES.contains(lat)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "laterality must be one of " + SUPPORTED_LATERALITIES + " (got '" + laterality + "')"));
        }
        // Exactly one binding mode: park | eventCrfId | studyEventId.
        int modeCount = (park ? 1 : 0) + (eventCrfId != null ? 1 : 0) + (studyEventId != null ? 1 : 0);
        if (modeCount == 0) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "exactly one of eventCrfId, studyEventId or park=true must be supplied"));
        }
        if (modeCount > 1) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "park, eventCrfId and studyEventId are mutually exclusive"));
        }

        // Resolve study_subject for the audit row. When park-no-patient
        // the lookup may legitimately return zero rows; that's fine —
        // the audit row's entity_id still points at the new job row,
        // study_subject_id is just metadata.
        Integer auditStudySubjectId = null;
        if (eventCrfId != null) {
            // bound to an event_crf → derive study_subject from event_crf
            auditStudySubjectId = resolveStudySubjectFromEventCrf(eventCrfId);
            if (auditStudySubjectId == null) {
                return ResponseEntity.status(404).body(Map.of(
                        "message", "No event_crf with id " + eventCrfId));
            }
        } else if (studyEventId != null) {
            // bound to a study_event → derive study_subject from study_event
            auditStudySubjectId = resolveStudySubjectFromStudyEvent(studyEventId);
            if (auditStudySubjectId == null) {
                return ResponseEntity.status(404).body(Map.of(
                        "message", "No study_event with id " + studyEventId));
            }
        } else {
            // park flow — best-effort: if the label resolves to exactly
            // one study_subject we attach it for the audit trail
            List<StudySubjectMatch> matches = studySubjectFinder.findByLabelAcrossStudies(pid);
            if (matches.size() == 1) {
                auditStudySubjectId = matches.get(0).studySubjectId();
            }
        }

        // ---- stream the upload to disk + compute SHA-256 in one pass -----
        // 2026-06-19 — was: file.getBytes() + Files.write (200 MB held in
        // JVM heap per upload + the whole request thread blocked through
        // the preprocess sidecar). Now: stream the multipart payload
        // straight to disk through a DigestInputStream so memory stays
        // flat regardless of file size and we get the dedup hash for
        // free during the copy. The preprocess + remote-inference
        // dispatch moves to an async block below, so the commit
        // response returns in low single-digit seconds.
        Path savedPath;
        String e2eSha256;
        final String originalFilename = file.getOriginalFilename();
        try {
            Path dir = Paths.get(uploadsDir());
            Files.createDirectories(dir);
            String filename = UUID.randomUUID() + ".e2e";
            savedPath = dir.resolve(filename);
            java.security.MessageDigest md;
            try {
                md = java.security.MessageDigest.getInstance("SHA-256");
            } catch (java.security.NoSuchAlgorithmException nsa) {
                LOG.warn("SHA-256 unavailable on this JRE — dedup hash disabled: {}", nsa.getMessage());
                md = null;
            }
            try (var in = file.getInputStream()) {
                if (md != null) {
                    try (var dis = new java.security.DigestInputStream(in, md)) {
                        Files.copy(dis, savedPath);
                    }
                } else {
                    Files.copy(in, savedPath);
                }
            }
            if (md != null) {
                byte[] digest = md.digest();
                StringBuilder hex = new StringBuilder(digest.length * 2);
                for (byte b : digest) hex.append(String.format("%02x", b));
                e2eSha256 = hex.toString();
            } else {
                e2eSha256 = null;
            }
        } catch (IOException ioEx) {
            LOG.error("Failed to persist E2E upload for portal patientId={}: {}", pid, ioEx.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "message", "Failed to persist E2E: " + ioEx.getMessage()));
        }
        String absolutePath = savedPath.toString();

        // Dedup pre-check after the stream finishes (we needed the bytes
        // to hash). The unique index on retinal_inference_job.e2e_sha256
        // is the race-safe gate; this pre-check just lets us short-
        // circuit before the INSERT + skip the orphan-file cleanup
        // path. Soft 409 with a pointer to the existing job.
        Long existingDuplicateJobId = findJobBySha256(e2eSha256, scanIndex);
        if (existingDuplicateJobId != null) {
            try { Files.deleteIfExists(savedPath); } catch (IOException ignored) { /* swallow */ }
            LOG.info("Public OCT upload — duplicate (sha256={}, scanIndex={}) matches existing job {} (patientId={})",
                    e2eSha256, scanIndex, existingDuplicateJobId, pid);
            return ResponseEntity.status(409).body(Map.of(
                    "message", "Diese .e2e-Datei wurde bereits hochgeladen.",
                    "existingJobId", existingDuplicateJobId,
                    "duplicate", true));
        }

        // 2026-06-19 — initial-status discriminator mirrors the legacy
        // RetinalInferenceApiController upload flow: when the remote
        // GPU sidecar is configured, land at {@code remote_pending} so
        // the local DB-poll worker's {@code status IN ('queued','screened')}
        // filter skips the row by construction. The async block below
        // then drives the row through {@code segmenting → done} via
        // {@link RetinalInferenceApiController#handleRemote}. Without
        // this discriminator, the local worker grabs the row first and
        // produces {@code placeholder-v1} synthetic metrics (no real
        // segmentation, no fundus overlay) — the 2026-06-18 smoke bug.
        boolean remoteClientNonNull = remoteClient != null;
        boolean remoteConfigured = remoteClientNonNull && remoteClient.isConfigured();
        boolean inferenceCtrlNonNull = inferenceController != null;
        boolean dispatchToRemote = !park && remoteConfigured && inferenceCtrlNonNull;
        // 2026-06-19 — diagnostic log. Without it, when dispatchToRemote
        // evaluates to false we have no easy way to tell WHICH of the
        // three components is missing (Spring DI silently degrades on
        // some misconfigurations + the LogFilter chain hides INFO logs
        // from this controller package in the docker compose dev stack).
        LOG.warn("Public OCT commit — dispatchDecision: park={} remoteClient={} remoteConfigured={} inferenceController={} → dispatchToRemote={}",
                park, remoteClientNonNull, remoteConfigured, inferenceCtrlNonNull, dispatchToRemote);
        String status;
        if (park) {
            status = RetinalInferenceJobStatus.PARKED.dbValue();
        } else if (dispatchToRemote) {
            status = "remote_pending";
        } else {
            status = RetinalInferenceJobStatus.QUEUED.dbValue();
        }
        // 2026-06-22 — per-event-definition default task list. When the
        // upload is bound to an event_crf or study_event we look up the
        // configured panel for its event_definition and enqueue ONE
        // retinal_inference_job per task in that set. Parked uploads
        // and event_definitions with no config fall back to DEFAULT_TASK
        // so pre-multi-task behaviour is preserved.
        List<String> tasks;
        if (eventCrfId != null) {
            tasks = resolveDefaultRetinalTasksByEventCrf(eventCrfId);
            if (tasks.isEmpty()) tasks = List.of(DEFAULT_TASK);
        } else if (studyEventId != null) {
            tasks = resolveDefaultRetinalTasksByStudyEvent(studyEventId);
            if (tasks.isEmpty()) tasks = List.of(DEFAULT_TASK);
        } else {
            tasks = List.of(DEFAULT_TASK);
        }

        List<Long> jobIds = new ArrayList<>();
        List<Map<String, Object>> jobInfos = new ArrayList<>();
        try (Connection c = dataSource.getConnection()) {
            for (String task : tasks) {
                long jId = insertJob(c, eventCrfId, studyEventId, task, absolutePath, lat, status, scanIndex, e2eSha256);
                jobIds.add(jId);
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("jobId", jId);
                info.put("task", task);
                info.put("status", status);
                jobInfos.add(info);
            }
        } catch (SQLException sqlEx) {
            // 2026-06-19 — race-safe dedup catch. If a concurrent upload
            // beat us to the unique index between the pre-check above
            // and this INSERT, the constraint fires here. Postgres
            // unique-violation SQLState = 23505. Roll back any partial
            // multi-task inserts before surfacing the 409.
            if ("23505".equals(sqlEx.getSQLState())) {
                deleteJobsByIds(jobIds);
                Long racedJobId = findJobBySha256(e2eSha256, scanIndex);
                try { Files.deleteIfExists(savedPath); } catch (IOException ignored) { /* swallow */ }
                LOG.info("Public OCT upload — (sha256, scanIndex, task) race detected for patientId={}, existing job={}",
                        pid, racedJobId);
                return ResponseEntity.status(409).body(Map.of(
                        "message", "Diese .e2e-Datei wurde bereits hochgeladen.",
                        "existingJobId", racedJobId == null ? -1L : racedJobId,
                        "duplicate", true));
            }
            LOG.error("Failed to enqueue retinal_inference_job from portal (patientId={}): {}",
                    pid, sqlEx.getMessage());
            // Best-effort cleanup: roll back partial inserts + drop the orphan file.
            deleteJobsByIds(jobIds);
            try { Files.deleteIfExists(savedPath); } catch (IOException ignored) { /* swallow */ }
            return ResponseEntity.internalServerError().body(Map.of(
                    "message", "Failed to enqueue job: " + sqlEx.getMessage()));
        }

        // Primary jobId for back-compat with API consumers that expect
        // the legacy single-job shape; `jobs` carries the full set.
        long jobId = jobIds.get(0);

        // ---- audit row per enqueued job ------------------------------
        for (long jid : jobIds) {
            writePublicOctUploadAuditRow(jid, auditStudySubjectId, pid, lat, status);
        }

        // ---- disambiguation marker (Wave 1B) ------------------------------
        // Emit a SECOND audit row when the SPA flagged the upload as a
        // disambiguated pick (resolve returned state='ambiguous' AND staff
        // selected one of N candidates). The marker rides on the same
        // unauthenticated transaction; failure to write it is best-effort
        // and does NOT roll back the main audit row.
        if (disambiguated && auditStudySubjectId != null) {
            writeAmbiguousDisambiguationAuditRow(auditStudySubjectId, candidateCount);
        }

        LOG.info("Public OCT upload — job {} {} (patientId={}, lat={}, scanIndex={}, eventCrfId={}, studyEventId={}, disambiguated={})",
                jobId, status, pid, lat, scanIndex, eventCrfId, studyEventId, disambiguated);

        // 2026-06-19 — fire the preprocess + remote-inference dispatch
        // asynchronously so the commit response returns immediately and
        // the operator's portal page stays responsive (was: 60+ s
        // synchronous wall-clock during the 2026-06-18 smoke). The
        // pipeline writes back to the same job row via DB, so the
        // SPA's job-status SSE stream picks up the {@code segmenting}
        // → {@code done} transitions without further coordination.
        // Soft-fail: any exception in the async block logs + leaves
        // the row in {@code remote_pending} or {@code queued}, which
        // the local worker still drains as the fallback path.
        final List<Map<String, Object>> finalJobInfos = jobInfos;
        final Path finalSavedPath = savedPath;
        final String finalLat = lat;
        final int finalScanIndex = scanIndex;
        final Integer finalEventCrfId = eventCrfId;
        final boolean finalDispatchToRemote = dispatchToRemote;
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                runPostCommitPipelineMulti(finalJobInfos, finalSavedPath, originalFilename,
                        finalLat, finalScanIndex, finalEventCrfId, finalDispatchToRemote);
            } catch (Exception ex) {
                LOG.warn("Post-commit async pipeline threw: {}", ex.getMessage());
            }
        });

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jobId", jobId);
        body.put("status", status);
        body.put("jobs", jobInfos);
        return ResponseEntity.status(201).body(body);
    }

    /**
     * 2026-06-22 — best-effort rollback after a multi-task INSERT
     * partially succeeded (e.g. the 2nd task hit the unique-key race).
     * Swallows errors — we're already on a failure path and the
     * dangling rows are harmless until the next deploy's GC.
     */
    private void deleteJobsByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM retinal_inference_job WHERE job_id = ?")) {
            for (Long id : ids) {
                ps.setLong(1, id);
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException sqlEx) {
            LOG.warn("Failed to clean up partial multi-task inserts {}: {}", ids, sqlEx.getMessage());
        }
    }

    /**
     * 2026-06-22 — resolve the configured retinal-task panel for the
     * event_definition that owns this event_crf. Returns the lowercase
     * task tokens ordered as stored. Empty list when the event_def has
     * no config (the caller falls back to DEFAULT_TASK in that case).
     *
     * <p>Joins event_crf → study_event → study_event_definition →
     * event_definition_retinal_task so a single round-trip resolves
     * the panel without surfacing the event-definition id to the
     * caller.
     */
    private List<String> resolveDefaultRetinalTasksByEventCrf(int eventCrfId) {
        List<String> out = new ArrayList<>();
        String sql = "SELECT t.task "
                + "  FROM event_crf ec "
                + "  JOIN study_event se ON se.study_event_id = ec.study_event_id "
                + "  JOIN event_definition_retinal_task t "
                + "    ON t.study_event_definition_id = se.study_event_definition_id "
                + " WHERE ec.event_crf_id = ? "
                + " ORDER BY t.id";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, eventCrfId);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getString(1));
            }
        } catch (SQLException sqlEx) {
            LOG.warn("Failed to resolve default retinal tasks for event_crf {}: {}",
                    eventCrfId, sqlEx.getMessage());
        }
        return out;
    }

    /**
     * 2026-06-23 — sibling of {@link #resolveDefaultRetinalTasksByEventCrf}
     * for the planned-visit binding path. When the operator picks a
     * scheduled visit (no CRF yet) the panel still comes from the
     * event_definition; we just take the shorter join path through
     * study_event directly.
     */
    private List<String> resolveDefaultRetinalTasksByStudyEvent(int studyEventId) {
        List<String> out = new ArrayList<>();
        String sql = "SELECT t.task "
                + "  FROM study_event se "
                + "  JOIN event_definition_retinal_task t "
                + "    ON t.study_event_definition_id = se.study_event_definition_id "
                + " WHERE se.study_event_id = ? "
                + " ORDER BY t.id";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, studyEventId);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getString(1));
            }
        } catch (SQLException sqlEx) {
            LOG.warn("Failed to resolve default retinal tasks for study_event {}: {}",
                    studyEventId, sqlEx.getMessage());
        }
        return out;
    }

    /**
     * 2026-06-19 — runs the slow part of the upload commit (preprocess
     * sidecar + remote GPU inference dispatch) on a background thread
     * so the request thread can return immediately. Both steps are
     * fire-and-continue: a sidecar failure or a remote outage logs +
     * leaves the row in the appropriate fallback state ({@code queued}
     * for local-worker drain, or {@code remote_pending} for a later
     * retry), so the operator's upload is never lost.
     *
     * <p>Re-reads the .e2e bytes from disk rather than holding them in
     * memory across the async boundary — the request thread no longer
     * needs to keep 200 MB resident per concurrent upload.
     */
    private void runPostCommitPipeline(long jobId, Path savedPath,
                                       String originalFilename, String laterality,
                                       int scanIndex, Integer eventCrfId,
                                       boolean dispatchToRemote) {
        // Single-task convenience wrapper for callers that haven't been
        // migrated to the multi-task shape (kept to minimise churn in
        // any future callers; currently the legacy code path is gone).
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("jobId", jobId);
        info.put("task", DEFAULT_TASK);
        runPostCommitPipelineMulti(List.of(info), savedPath, originalFilename,
                laterality, scanIndex, eventCrfId, dispatchToRemote);
    }

    /**
     * 2026-06-22 — multi-task post-commit pipeline. Preprocessing
     * (e2e → bscan.dcm + fundus.png + geometry.json) is task-independent
     * so we run it ONCE for the .e2e + then dispatch each enqueued
     * job's task to the remote inference adapter in parallel.
     */
    private void runPostCommitPipelineMulti(List<Map<String, Object>> jobInfos,
                                            Path savedPath, String originalFilename,
                                            String laterality, int scanIndex,
                                            Integer eventCrfId,
                                            boolean dispatchToRemote) {
        String fileNameForLog = savedPath.getFileName().toString();
        String e2eUuid = fileNameForLog.toLowerCase(Locale.ROOT).endsWith(".e2e")
                ? fileNameForLog.substring(0, fileNameForLog.length() - 4)
                : fileNameForLog;
        long primaryJobId = jobInfos.isEmpty()
                ? -1L
                : ((Number) jobInfos.get(0).get("jobId")).longValue();

        // ---- preprocess sidecar (best-effort, ONCE per .e2e) --------
        if (remoteClient != null) {
            byte[] e2eBytes = null;
            try {
                e2eBytes = Files.readAllBytes(savedPath);
            } catch (IOException ioEx) {
                LOG.warn("Async pipeline: failed to re-read .e2e for preprocess (primary job {}): {}",
                        primaryJobId, ioEx.getMessage());
            }
            if (e2eBytes != null) {
                try {
                    RemoteRetinalInferenceClient.PreprocessResult prep =
                            remoteClient.preprocessUpload(primaryJobId,
                                    originalFilename != null ? originalFilename : fileNameForLog,
                                    e2eBytes, laterality, e2eUuid, scanIndex);
                    if (prep == null) {
                        LOG.warn("Preprocess sidecar returned null for upload e2eUuid={} (primary job {})",
                                e2eUuid, primaryJobId);
                    } else {
                        LOG.info("Preprocess sidecar ok for upload e2eUuid={} (primary job {}, dcmBytes={})",
                                e2eUuid, primaryJobId, prep.dcmBytes() != null ? prep.dcmBytes().length : 0);
                    }
                } catch (Exception prepEx) {
                    LOG.warn("Preprocess sidecar threw for upload (primary job {}): {}",
                            primaryJobId, prepEx.getMessage());
                }
            }
        }

        // ---- remote inference dispatch — one call per task ----------
        if (dispatchToRemote && inferenceController != null && eventCrfId != null) {
            for (Map<String, Object> info : jobInfos) {
                long jId = ((Number) info.get("jobId")).longValue();
                String task = String.valueOf(info.get("task"));
                try {
                    inferenceController.handleRemote(jId, task,
                            savedPath.toString(), laterality, scanIndex, eventCrfId);
                } catch (Exception remoteEx) {
                    LOG.warn("Remote dispatch threw for portal commit job {} (task {}): {}",
                            jId, task, remoteEx.getMessage());
                }
            }
        }
    }

    /* ====================================================================== */
    /* GET /preflight?sha256=…&scanIndex=… — pre-upload dedup check           */
    /* ====================================================================== */

    /**
     * 2026-06-19 — pre-upload dedup gate. The SPA hashes the .e2e
     * bytes client-side (crypto.subtle.digest) BEFORE uploading and
     * calls this endpoint to ask "has this exact (sha256, scanIndex)
     * been uploaded before?" If so the SPA surfaces a "Bereits
     * hochgeladen" state without sending the 200 MB upload over the
     * wire — saving bandwidth, time, and the operator's confusion
     * about why their Bestätigen returned 409.
     *
     * <p>Anonymous + idempotent: GET, no side effects. Returns
     * {@code { exists, jobId }}. Distinct from the legacy commit-time
     * 409 catch — the unique-index race-guard there still fires when
     * two operators upload the same file in parallel between the
     * preflight check and the commit, so the dedup gate stays
     * race-safe regardless of whether the SPA called preflight first.
     *
     * @param sha256 hex SHA-256 (64 chars) of the .e2e bytes
     * @param scanIndex which volume in a multi-acquisition .e2e
     *                  the operator is about to commit; matches the
     *                  composite unique-index column shape
     */
    @GetMapping(path = "/preflight",
                produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> preflight(
            @RequestParam("sha256") String sha256,
            @RequestParam(value = "scanIndex", defaultValue = "0") int scanIndex) {
        if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "sha256 must be 64 hex characters"));
        }
        Long existingJobId = findJobBySha256(sha256, scanIndex);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("exists", existingJobId != null);
        body.put("jobId", existingJobId);
        return ResponseEntity.ok(body);
    }

    /* ====================================================================== */
    /* GET /patients/{studySubjectId}/events                                  */
    /* ====================================================================== */

    /**
     * 2026-06-19 — public (anonymous) event listing for the OCT-upload
     * portal's "Visite wählen" / "Studie wählen" flows.
     *
     * <p>The authenticated {@code /api/v1/events?subjectId=…} endpoint
     * requires a session, but the portal at {@code /app/oct-upload} is
     * unauthenticated by design (DR-022 — institutional reverse proxy
     * is the perimeter). Without this endpoint, every {@code novisit}
     * row strands the operator because VisitPickerModal can't list
     * candidate events. Identified during the 2026-06-18 smoke when an
     * EIAMD139 upload resolved as {@code novisit} and "Visite wählen"
     * returned 401.
     *
     * <p>Scope tightening for the public path:
     * <ul>
     *   <li>Single-subject lookup by numeric {@code studySubjectId} —
     *       the resolve response always returns this; no label-to-id
     *       round-trip needed.</li>
     *   <li>Active-study filter ({@code study.status_id = 1} =
     *       {@code Status.AVAILABLE}). An archived study's events
     *       never surface from the portal.</li>
     *   <li>First non-removed {@code event_crf_id} is pre-resolved in
     *       the same query — saves the SPA's second-hop call to
     *       {@code /events/{id}/detail}. Returned as
     *       {@code firstEventCrfId} on each row; null when no started
     *       CRF exists yet.</li>
     * </ul>
     */
    @GetMapping(path = "/patients/{studySubjectId:[0-9]+}/events",
                produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> listPatientEventsPublic(
            @PathVariable("studySubjectId") int studySubjectId) {
        List<Map<String, Object>> out = new ArrayList<>();
        String sql = "SELECT se.study_event_id, "
                + "       sed.oc_oid AS event_def_oid, "
                + "       sed.name AS event_label, "
                + "       sed.ordinal AS def_ordinal, "
                + "       se.date_start, "
                + "       se.date_end, "
                + "       se.location, "
                + "       se.subject_event_status_id, "
                + "       sed.repeating, "
                + "       ( SELECT ec.event_crf_id FROM event_crf ec "
                + "           WHERE ec.study_event_id = se.study_event_id "
                + "             AND ec.status_id NOT IN (5, 7) "
                + "           ORDER BY ec.event_crf_id ASC "
                + "           LIMIT 1 ) AS first_event_crf_id "
                + "  FROM study_event se "
                + "  JOIN study_event_definition sed "
                + "    ON sed.study_event_definition_id = se.study_event_definition_id "
                + "  JOIN study_subject ss "
                + "    ON ss.study_subject_id = se.study_subject_id "
                + "  JOIN study s "
                + "    ON s.study_id = ss.study_id "
                + " WHERE se.study_subject_id = ? "
                + "   AND s.status_id = 1 "
                + " ORDER BY se.date_start ASC, se.study_event_id ASC";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, studySubjectId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", String.valueOf(rs.getInt("study_event_id")));
                    row.put("eventDefinitionOid", rs.getString("event_def_oid"));
                    row.put("eventLabel", rs.getString("event_label"));
                    row.put("ordinal", rs.getInt("def_ordinal"));
                    Timestamp ds = rs.getTimestamp("date_start");
                    row.put("dateStarted", ds == null ? null : ds.toInstant().toString().substring(0, 10));
                    Timestamp de = rs.getTimestamp("date_end");
                    row.put("dateEnded", de == null ? null : de.toInstant().toString().substring(0, 10));
                    row.put("location", rs.getString("location"));
                    row.put("status", statusForSubjectEventStatusId(rs.getInt("subject_event_status_id")));
                    row.put("repeating", rs.getBoolean("repeating"));
                    int firstEcid = rs.getInt("first_event_crf_id");
                    row.put("firstEventCrfId", rs.wasNull() ? null : firstEcid);
                    out.add(row);
                }
            }
        } catch (SQLException sqlEx) {
            LOG.error("Public listPatientEventsPublic failed for studySubjectId={}: {}",
                    studySubjectId, sqlEx.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "message", "Failed to list events: " + sqlEx.getMessage()));
        }
        return ResponseEntity.ok(out);
    }

    /**
     * Map {@code study_event.subject_event_status_id} to the same
     * lowercase-hyphenated status string the authenticated
     * {@code EventsApiController.list} emits, so the SPA's
     * VisitPickerModal renders identically on the public + auth'd
     * branches. The full code map lives in
     * {@code SubjectEventStatus}; the cases below cover the values
     * the portal can encounter (scheduled / data-entry-started /
     * completed / signed / locked / stopped / skipped / removed).
     */
    private static String statusForSubjectEventStatusId(int id) {
        return switch (id) {
            case 1 -> "scheduled";
            case 2 -> "data-entry-started";
            case 4 -> "completed";
            case 5 -> "stopped";
            case 6 -> "skipped";
            case 7 -> "locked";
            case 8 -> "signed";
            case 9 -> "scheduled"; // not_scheduled — treat as scheduled for portal
            case 10 -> "removed";
            default -> "scheduled";
        };
    }

    /* ====================================================================== */
    /* DELETE /{jobId} — undo within 60 s                                     */
    /* ====================================================================== */

    @DeleteMapping(path = "/{jobId:[0-9]+}",
                   produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> undo(@PathVariable("jobId") long jobId) {
        // Pull enqueued_at + e2e_path in one round trip; reject when the
        // window has elapsed before we touch the row.
        String e2ePath;
        Instant enqueuedAt;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT enqueued_at, e2e_path FROM retinal_inference_job WHERE job_id = ?")) {
            ps.setLong(1, jobId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return ResponseEntity.status(404).body(Map.of(
                            "message", "No retinal_inference_job with id " + jobId));
                }
                Timestamp ts = rs.getTimestamp("enqueued_at");
                enqueuedAt = ts == null ? Instant.EPOCH : ts.toInstant();
                e2ePath = rs.getString("e2e_path");
            }
        } catch (SQLException sqlEx) {
            LOG.error("undo lookup failed for jobId {}: {}", jobId, sqlEx.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "message", "Failed to load job: " + sqlEx.getMessage()));
        }

        if (Duration.between(enqueuedAt, Instant.now()).compareTo(UNDO_WINDOW) > 0) {
            return ResponseEntity.status(410).body(Map.of(
                    "message", "Undo window of " + UNDO_WINDOW.toSeconds() + "s elapsed"));
        }

        // Delete the row first; only then unlink the file. If the row
        // delete fails we keep the file (operator can still see + bind
        // the job from the existing UI). If the file delete fails after
        // the row deleted we just log — the orphan can be cleaned by
        // ops; the user-visible undo succeeded.
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM retinal_inference_job WHERE job_id = ?")) {
            ps.setLong(1, jobId);
            int n = ps.executeUpdate();
            if (n == 0) {
                return ResponseEntity.status(404).body(Map.of(
                        "message", "No retinal_inference_job with id " + jobId));
            }
        } catch (SQLException sqlEx) {
            LOG.error("undo DELETE failed for jobId {}: {}", jobId, sqlEx.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "message", "Failed to delete job: " + sqlEx.getMessage()));
        }
        if (e2ePath != null && !e2ePath.isBlank()) {
            try {
                Files.deleteIfExists(Paths.get(e2ePath));
            } catch (IOException ioEx) {
                LOG.warn("Job {} row deleted but unlinking {} failed: {}",
                        jobId, e2ePath, ioEx.getMessage());
            }
        }
        return ResponseEntity.noContent().build();
    }

    /* ====================================================================== */
    /* helpers                                                                */
    /* ====================================================================== */

    /** {@code null} → null on parse failure. */
    private static LocalDate parseLocalDate(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    private Integer resolveStudySubjectFromEventCrf(int eventCrfId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT study_subject_id FROM event_crf WHERE event_crf_id = ?")) {
            ps.setInt(1, eventCrfId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                int v = rs.getInt(1);
                return rs.wasNull() ? null : v;
            }
        } catch (SQLException e) {
            LOG.warn("resolveStudySubjectFromEventCrf({}) failed: {}", eventCrfId, e.getMessage());
            return null;
        }
    }

    /**
     * 2026-06-23 — sibling of {@link #resolveStudySubjectFromEventCrf}
     * for the planned-visit binding path. Returns the owning
     * study_subject_id for the supplied study_event, or null if no
     * such row exists.
     */
    private Integer resolveStudySubjectFromStudyEvent(int studyEventId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT study_subject_id FROM study_event WHERE study_event_id = ?")) {
            ps.setInt(1, studyEventId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                int v = rs.getInt(1);
                return rs.wasNull() ? null : v;
            }
        } catch (SQLException e) {
            LOG.warn("resolveStudySubjectFromStudyEvent({}) failed: {}", studyEventId, e.getMessage());
            return null;
        }
    }

    private long insertJob(Connection c, Integer eventCrfId, Integer studyEventId, String task,
                           String e2ePath, String lat, String status, int scanIndex,
                           String e2eSha256) throws SQLException {
        String sql = "INSERT INTO retinal_inference_job ("
                + "event_crf_id, study_event_id, task, e2e_path, eye_laterality, status, scan_index, enqueued_at, e2e_sha256"
                + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            if (eventCrfId == null) {
                ps.setNull(1, java.sql.Types.INTEGER);
            } else {
                ps.setInt(1, eventCrfId);
            }
            if (studyEventId == null) {
                ps.setNull(2, java.sql.Types.INTEGER);
            } else {
                ps.setInt(2, studyEventId);
            }
            ps.setString(3, task);
            ps.setString(4, e2ePath);
            ps.setString(5, lat);
            ps.setString(6, status);
            ps.setInt(7, scanIndex);
            ps.setTimestamp(8, Timestamp.from(Instant.now()));
            if (e2eSha256 == null || e2eSha256.isBlank()) {
                ps.setNull(9, java.sql.Types.VARCHAR);
            } else {
                ps.setString(9, e2eSha256);
            }
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) return keys.getLong(1);
                throw new SQLException("retinal_inference_job INSERT returned no PK");
            }
        }
    }

    /**
     * 2026-06-19 — hex SHA-256 of the uploaded .e2e bytes. Backs the
     * upload-dedup gate: a unique index on {@code e2e_sha256} rejects
     * re-uploads of byte-identical files at INSERT time, and the
     * {@link #commit} handler catches the constraint violation to
     * surface a soft 409 with a pointer to the existing job.
     */
    private static String sha256Hex(byte[] bytes) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(bytes);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            // SHA-256 is required by every JRE — this is unreachable in
            // practice. Log + return null so the upload still proceeds
            // (without dedup) rather than failing the whole flow.
            LOG.warn("SHA-256 unavailable on this JRE — dedup hash will be null: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Locate the {@code job_id} of an existing row that carries the
     * supplied {@code (e2eSha256, scanIndex)} pair. Used by the
     * soft-409 dedup path to point the operator at the prior upload
     * instead of just rejecting with a bare error.
     *
     * <p>2026-06-19 widened from sha256-only to (sha256, scanIndex)
     * so a multi-volume .e2e file (e.g. OD + OS in one acquisition)
     * doesn't have its second volume blocked by the unique constraint.
     * The composite unique index on the DB matches this lookup.
     */
    private Long findJobBySha256(String e2eSha256, int scanIndex) {
        if (e2eSha256 == null || e2eSha256.isBlank()) return null;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT job_id FROM retinal_inference_job "
                             + "WHERE e2e_sha256 = ? AND scan_index = ? "
                             + "ORDER BY enqueued_at DESC LIMIT 1")) {
            ps.setString(1, e2eSha256);
            ps.setInt(2, scanIndex);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        } catch (SQLException ignored) { /* best-effort */ }
        return null;
    }

    /**
     * Write the {@code OCT_UPLOAD_PUBLIC} (id 115) audit row. The shared
     * EventCrfsApiController.writeAuditEvent helper assumes a non-null
     * user — this endpoint is unauthenticated, so user_id stays NULL,
     * which requires a separate INSERT.
     */
    private void writePublicOctUploadAuditRow(long jobId, Integer studySubjectId,
                                              String patientId, String laterality,
                                              String status) {
        // entity_id is the new retinal_inference_job PK; entity_name
        // carries the column we're recording (status), matching
        // RetinalInferenceApiController's convention.
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO audit_log_event (audit_log_event_type_id, audit_date, "
                             + "user_id, audit_table, entity_id, entity_name, old_value, new_value) "
                             + "VALUES (?, now(), NULL, ?, ?, ?, ?, ?)")) {
            ps.setInt(1, AuditTypeIds.OCT_UPLOAD_PUBLIC);
            ps.setString(2, "retinal_inference_job");
            ps.setInt(3, (int) jobId);
            ps.setString(4, "status");
            // Pack patientId + laterality + (optional) studySubjectId
            // hint into old_value so the audit view can render a
            // meaningful row even though user_id is NULL.
            String oldValue = "patientId=" + patientId + ";laterality=" + laterality
                    + (studySubjectId == null ? "" : ";studySubjectId=" + studySubjectId);
            ps.setString(5, oldValue);
            ps.setString(6, status);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warn("OCT_UPLOAD_PUBLIC audit-write failed for job {}: {}", jobId, e.getMessage());
        }
    }

    /**
     * Wave 1B (2026-06-18) — second audit row written when the SPA flagged
     * the upload as a disambiguated pick. {@code audit_table='study_subject'}
     * (the row records the human decision against the chosen subject, not
     * against the job row); {@code new_value} packs the pick + candidate
     * count so the audit timeline can render a "chose X of N" line without
     * a second look-up.
     */
    private void writeAmbiguousDisambiguationAuditRow(int studySubjectId, int candidateCount) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO audit_log_event (audit_log_event_type_id, audit_date, "
                             + "user_id, audit_table, entity_id, entity_name, old_value, new_value) "
                             + "VALUES (?, now(), NULL, ?, ?, ?, ?, ?)")) {
            ps.setInt(1, AuditTypeIds.OCT_UPLOAD_PUBLIC_AMBIGUOUS);
            ps.setString(2, "study_subject");
            ps.setInt(3, studySubjectId);
            ps.setString(4, "study_subject_id");
            ps.setString(5, "");
            ps.setString(6, "chose:" + studySubjectId + ":from:" + candidateCount + " candidates");
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warn("OCT_UPLOAD_PUBLIC_AMBIGUOUS audit-write failed for study_subject {}: {}",
                    studySubjectId, e.getMessage());
        }
    }

    private static String uploadsDir() {
        try {
            String raw = CoreResources.getField("core.retinalInference.e2eUploadsPath");
            if (raw != null && !raw.isBlank()) return raw.trim();
        } catch (Exception ignored) {
            // CoreResources unavailable -- fall back.
        }
        return DEFAULT_UPLOADS_PATH;
    }

    /* ====================================================================== */
    /* DTOs                                                                   */
    /* ====================================================================== */

    public record ResolveRequest(List<ResolveRequestScan> scans) { }

    public record ResolveRequestScan(String patientId, String scanDate, String laterality) { }

    public record ResolveResponseScan(String patientId,
                                      List<ResolveCandidate> candidates,
                                      String state) { }

    public record ResolveCandidate(int studyId, String studyName, String studyOid,
                                   int studySubjectId, String subjectLabel,
                                   String siteName,
                                   EventCandidate matchingEvent) { }
}
