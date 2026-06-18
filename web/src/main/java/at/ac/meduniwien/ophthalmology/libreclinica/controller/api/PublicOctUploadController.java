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
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.StudySubjectFinder;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.StudySubjectMatch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
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

    @Autowired
    public PublicOctUploadController(@Qualifier("dataSource") DataSource dataSource,
                                     StudySubjectFinder studySubjectFinder) {
        this.dataSource = dataSource;
        this.studySubjectFinder = studySubjectFinder;
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
        if (!park && eventCrfId == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "either eventCrfId or park=true must be supplied"));
        }
        if (park && eventCrfId != null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "park=true and eventCrfId are mutually exclusive"));
        }

        // Resolve study_subject for the audit row. When park-no-patient
        // the lookup may legitimately return zero rows; that's fine —
        // the audit row's entity_id still points at the new job row,
        // study_subject_id is just metadata.
        Integer auditStudySubjectId = null;
        if (!park) {
            // bound to an event_crf → derive study_subject from event_crf
            auditStudySubjectId = resolveStudySubjectFromEventCrf(eventCrfId);
            if (auditStudySubjectId == null) {
                return ResponseEntity.status(404).body(Map.of(
                        "message", "No event_crf with id " + eventCrfId));
            }
        } else {
            // park flow — best-effort: if the label resolves to exactly
            // one study_subject we attach it for the audit trail
            List<StudySubjectMatch> matches = studySubjectFinder.findByLabelAcrossStudies(pid);
            if (matches.size() == 1) {
                auditStudySubjectId = matches.get(0).studySubjectId();
            }
        }

        // ---- persist the upload to disk -----------------------------------
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
            LOG.error("Failed to persist E2E upload for portal patientId={}: {}", pid, ioEx.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "message", "Failed to persist E2E: " + ioEx.getMessage()));
        }
        String absolutePath = savedPath.toString();

        String status = park
                ? RetinalInferenceJobStatus.PARKED.dbValue()
                : RetinalInferenceJobStatus.QUEUED.dbValue();
        long jobId;
        try (Connection c = dataSource.getConnection()) {
            jobId = insertJob(c, eventCrfId, DEFAULT_TASK, absolutePath, lat, status, scanIndex);
        } catch (SQLException sqlEx) {
            LOG.error("Failed to enqueue retinal_inference_job from portal (patientId={}): {}",
                    pid, sqlEx.getMessage());
            // Best-effort cleanup: don't leave an orphan file when the INSERT failed.
            try { Files.deleteIfExists(savedPath); } catch (IOException ignored) { /* swallow */ }
            return ResponseEntity.internalServerError().body(Map.of(
                    "message", "Failed to enqueue job: " + sqlEx.getMessage()));
        }

        // ---- audit row on enqueue -----------------------------------------
        writePublicOctUploadAuditRow(jobId, auditStudySubjectId, pid, lat, status);

        // ---- disambiguation marker (Wave 1B) ------------------------------
        // Emit a SECOND audit row when the SPA flagged the upload as a
        // disambiguated pick (resolve returned state='ambiguous' AND staff
        // selected one of N candidates). The marker rides on the same
        // unauthenticated transaction; failure to write it is best-effort
        // and does NOT roll back the main audit row.
        if (disambiguated && auditStudySubjectId != null) {
            writeAmbiguousDisambiguationAuditRow(auditStudySubjectId, candidateCount);
        }

        LOG.info("Public OCT upload — job {} {} (patientId={}, lat={}, scanIndex={}, eventCrfId={}, disambiguated={})",
                jobId, status, pid, lat, scanIndex, eventCrfId, disambiguated);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jobId", jobId);
        body.put("status", status);
        return ResponseEntity.status(201).body(body);
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

    private long insertJob(Connection c, Integer eventCrfId, String task, String e2ePath,
                           String lat, String status, int scanIndex) throws SQLException {
        String sql = "INSERT INTO retinal_inference_job ("
                + "event_crf_id, task, e2e_path, eye_laterality, status, scan_index, enqueued_at"
                + ") VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            if (eventCrfId == null) {
                ps.setNull(1, java.sql.Types.INTEGER);
            } else {
                ps.setInt(1, eventCrfId);
            }
            ps.setString(2, task);
            ps.setString(3, e2ePath);
            ps.setString(4, lat);
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
