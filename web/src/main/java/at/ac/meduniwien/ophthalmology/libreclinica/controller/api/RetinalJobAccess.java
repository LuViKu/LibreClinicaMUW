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
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import javax.sql.DataSource;
import jakarta.servlet.http.HttpSession;

import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RetinalArtifactStorageService;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * P3.6 — what every retinal endpoint needs to know about a job and its files.
 *
 * <p>The retinal controller grew to 3,677 lines around one shared core: read
 * the job row, decide whether the caller may see it, and name the files it
 * produced. Splitting the endpoints across four controllers without first
 * giving that core a home would have meant copying it, and a second copy of a
 * visibility check is how a surface ends up unguarded — the same reasoning
 * that produced {@link StudyResourceAccess}.
 *
 * <p>Nothing here decides policy beyond visibility. Blinding stays in
 * {@link AiArmPolicy}, where the endpoints that mask can be read together.
 */
final class RetinalJobAccess {

    private static final Logger LOG = LoggerFactory.getLogger(RetinalJobAccess.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Companion files the preprocess sidecar writes per e2eUuid. Order is
     * stable so the SPA can iterate without sorting client-side.
     */
    static final List<String> COMPANION_NAMES =
            List.of("bscan.dcm", "fundus.png", "geometry.json");

    /**
     * Path-traversal guard for {@code GET …/artifacts/{name}}. Allows only
     * alphanumerics plus the punctuation segmentation runners actually emit.
     */
    static final Pattern SAFE_ARTIFACT_NAME =
            Pattern.compile("[A-Za-z0-9_.()# -]+");

    private final DataSource dataSource;
    private final RetinalArtifactStorageService artifactStore;
    private final StudyResourceAccess access;

    RetinalJobAccess(DataSource dataSource, RetinalArtifactStorageService artifactStore,
                     StudyResourceAccess access) {
        this.dataSource = dataSource;
        this.artifactStore = artifactStore;
        this.access = access;
    }

    /* ------------------------------------------------------------------ */
    /* The job row                                                         */
    /* ------------------------------------------------------------------ */

    /** One job with its result, and the study it belongs to. */
    static final class JobRow {
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
        /**
         * Which scan of a multi-volume .e2e this job read. The artifact
         * resolver needs it to pick the right {@code scan-N/} subdirectory.
         */
        int scanIndex;
        /**
         * Set directly by the public OCT portal and the Wave-2 pipeline, which
         * bind to a planned visit and leave {@code event_crf_id} null.
         * {@link AiArmPolicy#armForEvent} needs both to find the arm.
         */
        Integer studyEventId;
    }

    /**
     * The job, its result and its study, or null when there is no such job.
     *
     * <p>The study is resolved through <em>either</em> binding path — a job
     * attached to a planned visit has no event_crf yet, and reading only that
     * path would leave studyId null, which the visibility guard refuses. A job
     * that is merely early in its life would then read as one belonging to
     * somebody else's study.
     */
    JobRow fetchJobDetail(Connection c, long jobId) throws SQLException {
        String sql = "SELECT j.job_id, j.event_crf_id, j.task, j.e2e_path, "
                + "       j.eye_laterality, j.status, j.enqueued_at, j.completed_at, j.model_version, "
                + "       j.scan_index, j.study_event_id, "
                + "       r.output_payload, r.primary_metric_value, r.primary_metric_unit, "
                + "       r.bscan_masks_dir, r.confidence, ss.study_id "
                + "  FROM retinal_inference_job j "
                + "  LEFT JOIN retinal_inference_result r ON r.job_id = j.job_id "
                + "  LEFT JOIN event_crf ec ON ec.event_crf_id = j.event_crf_id "
                + "  LEFT JOIN study_event se ON se.study_event_id = COALESCE(ec.study_event_id, j.study_event_id) "
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
                row.scanIndex = rs.getInt("scan_index");
                int sev = rs.getInt("study_event_id");
                row.studyEventId = rs.wasNull() ? null : sev;
                return row;
            }
        }
    }

    /**
     * 403 when the job's study is outside what this session may see.
     *
     * <p>The relaxed form, because a link to a job arrives with whatever study
     * the session was last pointed at — see
     * {@link StudyResourceAccess#guardStudyVisibilityAllowingDeepLink}.
     */
    ResponseEntity<?> guardJobVisibility(JobRow row, HttpSession session) {
        return access.guardStudyVisibilityAllowingDeepLink(row.studyId, session,
                "retinal_inference_job " + row.jobId + " belongs to a different study");
    }

    /**
     * The trial arm this job's subject is in, or null where the study does not
     * randomise on AI visibility.
     *
     * <p>Resolved through whichever binding the job has: a job attached to a
     * planned visit carries the study_event directly and has no event_crf yet.
     *
     * <p><strong>Fails open, and that is inherited rather than chosen.</strong>
     * A failed lookup returns null, which {@link AiArmPolicy#maskAiFor} reads
     * as "not the hidden arm", so a database error shows AI output to a
     * clinician who may be blinded to it. The export path decided the opposite
     * (DR-028): an unanswerable blinding question withholds, because a file
     * that has left the platform cannot be taken back. On screen the stakes
     * are lower and the behaviour is long-standing, so P3.6 moved it
     * unchanged rather than altering blinding inside a refactor — but the two
     * halves of the platform disagreeing about what an unknown arm means is
     * worth settling deliberately.
     */
    String armForJobRow(JobRow row) {
        try (Connection c = dataSource.getConnection()) {
            int sev = row.studyEventId == null ? 0 : row.studyEventId.intValue();
            return AiArmPolicy.armForEvent(c, row.eventCrfId, sev);
        } catch (SQLException e) {
            LOG.warn("arm lookup failed for job {}: {}", row.jobId, e.getMessage());
            return null;
        }
    }

    /* ------------------------------------------------------------------ */
    /* The files a job produced                                            */
    /* ------------------------------------------------------------------ */

    /** Trim a single trailing ".e2e" — match what the upload controller saves. */
    static String e2eUuidFromPath(String e2ePath) {
        if (e2ePath == null) return null;
        String base = Paths.get(e2ePath).getFileName().toString();
        if (base.toLowerCase().endsWith(".e2e")) {
            base = base.substring(0, base.length() - 4);
        }
        return base;
    }

    List<String> listArtifactNames(String dir) {
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

    List<String> listCompanionNames(String e2eUuid) {
        return listCompanionNames(e2eUuid, -1);
    }

    /**
     * Which companions actually exist for this scan. Named rather than
     * assumed: a companion the sidecar did not write must not appear in a
     * listing that the viewer will then fail to fetch.
     */
    List<String> listCompanionNames(String e2eUuid, int scanIndex) {
        if (e2eUuid == null || e2eUuid.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String name : COMPANION_NAMES) {
            try {
                switch (name) {
                    case "bscan.dcm"     -> artifactStore.resolveBscanDcm(e2eUuid, scanIndex);
                    case "fundus.png"    -> artifactStore.resolveFundus(e2eUuid, scanIndex);
                    case "geometry.json" -> artifactStore.resolveGeometry(e2eUuid, scanIndex);
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

    static MediaType mediaTypeForName(String name) {
        String lower = name.toLowerCase();
        if (lower.endsWith(".csv"))  return MediaType.parseMediaType("text/csv");
        if (lower.endsWith(".npy"))  return MediaType.APPLICATION_OCTET_STREAM;
        if (lower.endsWith(".npz"))  return MediaType.APPLICATION_OCTET_STREAM;
        if (lower.endsWith(".dcm"))  return MediaType.parseMediaType("application/dicom");
        if (lower.endsWith(".png"))  return MediaType.IMAGE_PNG;
        if (lower.endsWith(".json")) return MediaType.APPLICATION_JSON;
        return MediaType.APPLICATION_OCTET_STREAM;
    }

    /* ------------------------------------------------------------------ */
    /* Shapes both halves render                                           */
    /* ------------------------------------------------------------------ */

    @SuppressWarnings("unchecked")
    static Map<String, Object> parsePayload(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return JSON.readValue(json, Map.class);
        } catch (Exception jsonEx) {
            LOG.warn("Failed to parse output_payload JSON: {}", jsonEx.getMessage());
            return Map.of();
        }
    }

    static String toIso(Timestamp ts) {
        return ts == null ? null : ts.toInstant().toString();
    }
}
