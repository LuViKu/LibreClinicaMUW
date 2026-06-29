/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.metrics;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.PixelGeometry;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RetinalArtifactStorageService;

/**
 * 2026-06-24 — orchestrates a CRT (Central Retinal Thickness, central 1
 * mm) computation against the persisted artifacts on disk.
 *
 * <p>The service expects two {@code done} retinal-inference jobs to
 * exist for the same {@code study_event} and {@code eye_laterality}:
 *
 * <ul>
 *   <li>{@code task = 'ga'} — the GA runner's IOWA OCTLayerSeg
 *       output dir contains every retinal layer surface including
 *       <strong>{@code 001-ILM (ILM).csv}</strong>; the GA-area metric
 *       it nominally produces is unused here.</li>
 *   <li>{@code task = 'bm'} — the BM runner's output dir contains a
 *       single Bruch's-membrane surface CSV
 *       (<strong>{@code 001-Bruch's membrane (BM).csv}</strong>).</li>
 * </ul>
 *
 * <p>Both jobs share an {@code e2e_path} (the same OCT upload was
 * dispatched to both runners), so {@code geometry.json} — the
 * pixel-axis-scale carrier produced by the preprocess sidecar — is
 * read from one (whichever job's path resolves first).
 *
 * <p>Per-eye contract: a {@link Result} comes back only when BOTH
 * runner jobs for that eye reached {@code done} (or the legacy
 * {@code succeeded} status spelling). When either is missing the
 * caller receives {@link Optional#empty()} for that eye; the
 * timeline endpoint surfaces that as a null in the response so the
 * SPA can render "—" instead of a stale value.
 *
 * <p>The math itself ({@link CrtComputer}) is unit-tested in
 * isolation; this service is pure plumbing — DB lookup, file
 * resolution, geometry parsing.
 */
@Service
@SuppressWarnings("null")
public class CrtComputeService {

    private static final Logger LOG = LoggerFactory.getLogger(CrtComputeService.class);

    /** Statuses where the per-eye computation is allowed. Matches the
     *  same set the SPA's nAMD module + the listSubjectJobs endpoint use. */
    private static final Set<String> DONE_STATUSES = Set.of("done", "succeeded");

    /** Filename glob for the IOWA ILM CSV inside the GA artifact dir.
     *  IOWA OCTLayerSeg emits "001-ILM (ILM).csv"; this match is
     *  case-insensitive ("ilm") to tolerate future filename churn
     *  without code edits. */
    private static final String ILM_FILENAME_NEEDLE = "ilm";

    /** Filename glob for the BM CSV inside the BM artifact dir. The
     *  sese_bm runner emits "001-Bruch's membrane (BM).csv"; "bm"
     *  matches both the parenthesised tag and the alternate
     *  "Bruch" naming the BM apptainer fallback path uses. */
    private static final String BM_FILENAME_NEEDLE = "bm";

    private final DataSource dataSource;
    private final RetinalArtifactStorageService artifactStorage;
    private final CrtComputer crtComputer;
    private final ObjectMapper jsonMapper = new ObjectMapper();

    @Autowired
    public CrtComputeService(@Qualifier("dataSource") DataSource dataSource,
                             RetinalArtifactStorageService artifactStorage,
                             CrtComputer crtComputer) {
        this.dataSource = dataSource;
        this.artifactStorage = artifactStorage;
        this.crtComputer = crtComputer;
    }

    /**
     * Per-eye computation result. The pixel count is surfaced so the
     * SPA tooltip / report can say "average over N pixels in the
     * central 1 mm". {@code layersJobId} points at the single
     * source job (task = {@code layers}) carrying both ILM + BM
     * artifacts in one bscan_masks_dir.
     *
     * <p>2026-06-24: was previously a pair (gaJobId, bmJobId) — the
     * upstream runner changed so GA only emits RPEL now and the new
     * {@code layers} task delivers ILM + BM together. Single source
     * job, single id.
     */
    public record Result(double crtMicrons, int pixelsInDisk, long layersJobId) {}

    /** Eye laterality the BCVA + retinal job rows use. */
    public enum Eye { OD, OS }

    /**
     * Compute CRT for both eyes on the named study_event. Each map
     * entry is present only when BOTH the GA + BM jobs for that eye
     * are {@code done} and the math succeeded. Map preserves insertion
     * order (OD before OS) for stable JSON output.
     */
    public Map<Eye, Result> computeForStudyEvent(int studyEventId) {
        Map<Eye, Result> out = new EnumMap<>(Eye.class);
        for (Eye eye : Eye.values()) {
            computeForEvent(studyEventId, eye).ifPresent(r -> out.put(eye, r));
        }
        return out;
    }

    /** Single-eye entry point — exposed so callers that already filter
     *  by eye (e.g. the nAMD selected-eye path) don't have to walk a
     *  two-eye map. */
    public Optional<Result> computeForEvent(int studyEventId, Eye eye) {
        try {
            return doCompute(studyEventId, eye);
        } catch (MetricComputationException mce) {
            LOG.info("CRT compute skipped for (event={}, eye={}): {}",
                    studyEventId, eye, mce.getMessage());
            return Optional.empty();
        } catch (Exception ex) {
            LOG.warn("CRT compute failed for (event={}, eye={}): {}",
                    studyEventId, eye, ex.getMessage(), ex);
            return Optional.empty();
        }
    }

    private Optional<Result> doCompute(int studyEventId, Eye eye) {
        // 2026-06-24 — switched from GA+BM pair to a single `layers`
        // job. The upstream runner change (sidecar PR #255) has
        // `layers` return the full IOWA reference stack including
        // ILM + BM in one bscan_masks_dir, while GA returns only RPEL.
        JobRef layers;
        try (Connection c = dataSource.getConnection()) {
            layers = findJob(c, studyEventId, eye, "layers");
        } catch (SQLException sqlEx) {
            throw new MetricComputationException(
                    "Database lookup failed for (event=" + studyEventId + ", eye=" + eye + "): "
                            + sqlEx.getMessage(), sqlEx);
        }
        if (layers == null) {
            // Not an error — the typical case is "the layers task
            // hasn't completed for this event yet". Caller surfaces
            // this as a missing entry on the timeline.
            return Optional.empty();
        }

        Path ilmCsv = locateSurfaceCsv(layers.bscanMasksDir, ILM_FILENAME_NEEDLE)
                .orElseThrow(() -> new MetricComputationException(
                        "layers job " + layers.jobId + " has no ILM CSV in " + layers.bscanMasksDir));
        Path bmCsv = locateSurfaceCsv(layers.bscanMasksDir, BM_FILENAME_NEEDLE)
                .orElseThrow(() -> new MetricComputationException(
                        "layers job " + layers.jobId + " has no BM CSV in " + layers.bscanMasksDir));
        PixelGeometry geom = loadGeometry(layers);
        CrtComputer.Result r = crtComputer.computeCrtMicrons(ilmCsv, bmCsv, geom);
        return Optional.of(new Result(r.crtMicrons(), r.pixelsInDisk(), layers.jobId));
    }

    /** Internal carrier holding everything we need about one task's
     *  source job. Populated by {@link #findJob}. */
    private record JobRef(long jobId, String task, String bscanMasksDir,
                          String e2ePath, int scanIndex) {}

    /**
     * Find the most-recent {@code done} job for the (studyEventId,
     * eye, task) tuple. Joins {@code retinal_inference_result} to
     * surface {@code bscan_masks_dir} in one round-trip.
     */
    private JobRef findJob(Connection c, int studyEventId, Eye eye, String task) throws SQLException {
        String sql = "SELECT j.job_id, j.task, r.bscan_masks_dir, j.e2e_path, "
                + "       COALESCE(j.scan_index, 0) AS scan_index "
                + "  FROM retinal_inference_job j "
                + "  LEFT JOIN event_crf ec ON ec.event_crf_id = j.event_crf_id "
                + "  LEFT JOIN retinal_inference_result r ON r.job_id = j.job_id "
                + " WHERE COALESCE(ec.study_event_id, j.study_event_id) = ? "
                + "   AND j.eye_laterality = ? "
                + "   AND j.task = ? "
                + "   AND j.status IN ('done','succeeded') "
                + "   AND r.bscan_masks_dir IS NOT NULL "
                + " ORDER BY j.completed_at DESC NULLS LAST, j.job_id DESC "
                + " LIMIT 1";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, studyEventId);
            ps.setString(2, eye.name());
            ps.setString(3, task);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new JobRef(
                        rs.getLong("job_id"),
                        rs.getString("task"),
                        rs.getString("bscan_masks_dir"),
                        rs.getString("e2e_path"),
                        rs.getInt("scan_index"));
            }
        }
    }

    /**
     * Walk the artifact dir for a file whose name (case-insensitive)
     * contains the needle and ends with {@code .csv}. Returns
     * empty when the dir is missing, unreadable, or has no match.
     */
    /** Package-private for direct unit testing of the corrections-preference rule. */
    static Optional<Path> locateSurfaceCsv(String dir, String needle) {
        if (dir == null || dir.isBlank()) return Optional.empty();
        Path p = Paths.get(dir);
        if (!Files.isDirectory(p)) return Optional.empty();
        String lowerNeedle = needle.toLowerCase();
        // 2026-06-27 — Operator-corrected surfaces live alongside the
        // original AI output under {@code <dir>/corrections/<filename>}
        // (same probe pattern SegmentationEnvelopeLoader uses for the
        // streaming envelope). Probing corrections/ FIRST means CST
        // automatically follows operator corrections: the next
        // /crt-timeline request after a Save POST returns the
        // recomputed value without any explicit cache invalidation.
        Path corrections = p.resolve("corrections");
        if (Files.isDirectory(corrections)) {
            Optional<Path> corrected = scanCsvForNeedle(corrections, lowerNeedle);
            if (corrected.isPresent()) return corrected;
        }
        return scanCsvForNeedle(p, lowerNeedle);
    }

    private static Optional<Path> scanCsvForNeedle(Path dir, String lowerNeedle) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.csv")) {
            for (Path entry : stream) {
                String name = entry.getFileName().toString().toLowerCase();
                if (name.contains(lowerNeedle)) {
                    return Optional.of(entry);
                }
            }
        } catch (IOException ioEx) {
            LOG.warn("Failed to enumerate {} for needle '{}': {}", dir, lowerNeedle, ioEx.getMessage());
            return Optional.empty();
        }
        return Optional.empty();
    }

    /**
     * Resolve geometry.json (preprocess-sidecar output) for the given
     * job's e2e + scan_index, then parse it into a PixelGeometry. The
     * JSON layout is the canonical one we already exercise on the SPA
     * (see docker/retinal-artifacts/bscans/&lt;uuid&gt;/geometry.json):
     *
     * <pre>{"bscan": {"dim_x_ascans": ..., "dim_y_rows": ...,
     *                 "dim_z_bscans": ..., "pixel_axial_mm": ...,
     *                 "pixel_lateral_mm": ..., "pixel_slice_mm": ...}}</pre>
     */
    private PixelGeometry loadGeometry(JobRef job) {
        String e2eUuid = e2eUuidFromPath(job.e2ePath);
        if (e2eUuid == null) {
            throw new MetricComputationException(
                    "Job " + job.jobId + " has no e2e_path; cannot resolve geometry.json");
        }
        Path geom;
        try {
            geom = artifactStorage.resolveGeometry(e2eUuid, job.scanIndex);
        } catch (IOException ioEx) {
            throw new MetricComputationException(
                    "Failed to resolve geometry.json for job " + job.jobId + ": " + ioEx.getMessage(), ioEx);
        }
        if (geom == null || !Files.isRegularFile(geom)) {
            throw new MetricComputationException(
                    "geometry.json not found for job " + job.jobId + " (e2eUuid=" + e2eUuid
                            + ", scanIndex=" + job.scanIndex + ")");
        }
        try {
            JsonNode root = jsonMapper.readTree(geom.toFile());
            JsonNode b = root.path("bscan");
            return new PixelGeometry(
                    b.path("pixel_axial_mm").asDouble(),
                    b.path("pixel_lateral_mm").asDouble(),
                    b.path("pixel_slice_mm").asDouble(),
                    b.path("dim_z_bscans").asInt(),
                    b.path("dim_y_rows").asInt(),
                    b.path("dim_x_ascans").asInt());
        } catch (IOException ioEx) {
            throw new MetricComputationException(
                    "Failed to parse geometry.json for job " + job.jobId + ": " + ioEx.getMessage(), ioEx);
        }
    }

    /** Mirror of {@code RetinalResultsApiController.e2eUuidFromPath}.
     *  Kept local so this service can run without pulling the
     *  web-module controller as a dependency. */
    private static String e2eUuidFromPath(String e2ePath) {
        if (e2ePath == null) return null;
        String base = Paths.get(e2ePath).getFileName().toString();
        if (base.toLowerCase().endsWith(".e2e")) {
            base = base.substring(0, base.length() - 4);
        }
        return base;
    }

    /**
     * Compute CRT for a list of study_event_ids in a single pass.
     * Returns insertion-ordered {@code Map<studyEventId, Map<Eye, Result>>}
     * with every input id present (missing eyes have an empty inner
     * map). Each event is independent — a failure on one doesn't
     * abort the rest. Backs the timeline endpoint.
     */
    public Map<Integer, Map<Eye, Result>> computeForStudyEvents(List<Integer> studyEventIds) {
        Map<Integer, Map<Eye, Result>> out = new LinkedHashMap<>();
        for (Integer eventId : studyEventIds) {
            if (eventId == null) continue;
            out.put(eventId, computeForStudyEvent(eventId));
        }
        return out;
    }
}
