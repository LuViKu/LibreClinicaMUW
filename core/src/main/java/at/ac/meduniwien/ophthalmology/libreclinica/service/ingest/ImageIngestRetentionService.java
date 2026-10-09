/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.ingest;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.ac.meduniwien.ophthalmology.libreclinica.dao.core.CoreResources;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RetinalArtifactKey;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RetinalArtifactStorageService;

/**
 * DR-025 — retention sweep for dismissed fundus images.
 *
 * <p>A camera on a clinic bench sends whatever it holds. Test exposures,
 * images of the wrong patient, and anything a device flushes on first contact
 * all land in the reconciliation inbox, and an operator dismisses them. The
 * dismissal is the clinical decision that the image is not study data — but
 * until now the image itself stayed on disk forever, which for a retinal
 * photograph of an identifiable person is exactly the kind of indefinite
 * retention a data-protection review asks about.
 *
 * <p>This sweep removes a dismissed image and its row once the review window
 * has passed ({@code core.ingest.retention.dismissedDays}, default 30). Bound
 * images are study data and are never touched; unbound ones are still awaiting
 * a decision and are never touched either. The window is counted from the
 * dismissal, falling back to arrival for rows that predate the timestamp.
 *
 * <p>An analysed OCT scan takes its whole chain with it: inference jobs,
 * results, layer corrections, the per-job artifact directories and the
 * preprocess companions in the B-scan store. CRF values that name a removed
 * job or row as their source keep their value; only the link is cleared. A
 * scan with an analysis still running is kept for the next night.
 *
 * <p><strong>Deletion is confined to each path's own store.</strong> The paths
 * come from the database, and a retention job holding a delete primitive over
 * arbitrary paths is a liability — so a stored file outside the ingest roots,
 * an artifact directory outside {@code core.retinalInference.artifactStorePath}
 * or a companion directory outside {@code core.retinalInference.bscanStorePath}
 * is skipped and logged rather than deleted, and the item is kept whole so the
 * discrepancy stays visible. A file or directory another row still uses — the
 * other volume of a two-volume .e2e — is kept.
 *
 * <p>Each pass writes one audit row summarising what it removed, so the
 * deletion is itself part of the trail. Filenames are not logged: an upload's
 * stored name derives from operator-supplied text.
 */
public class ImageIngestRetentionService {

    private static final Logger LOG = LoggerFactory.getLogger(ImageIngestRetentionService.class);

    /** How long a dismissed image is kept before the sweep removes it. */
    public static final String CONFIG_KEY_RETENTION_DAYS = "core.ingest.retention.dismissedDays";

    /** Fallback when the key is absent or unparseable. */
    public static final int DEFAULT_RETENTION_DAYS = 30;

    /** Where both ingress routes write — see PublicImageUploadController. */
    public static final String CONFIG_KEY_STORE_PATH = "core.dicom.ingest.storePath";
    public static final String DEFAULT_STORE_PATH = "/var/lib/libreclinica/dicom-ingest";

    /**
     * {@code AuditTypeIds.IMAGE_DISMISS}. Duplicated as a literal because that
     * class lives in the web module and core cannot see it; the id is fixed by
     * a Liquibase seed, so it cannot drift.
     */
    public static final int AUDIT_TYPE_IMAGE_DISMISS = 128;

    private final DataSource dataSource;
    private final int retentionDays;
    /** {@code core.retinalInference.artifactStorePath}: one directory per finished job. */
    private final Path artifactRoot;
    /** {@code core.retinalInference.bscanStorePath}: one companion directory per scan file. */
    private final Path bscanRoot;

    /** Production constructor — window and store root from datainfo.properties. */
    public ImageIngestRetentionService(DataSource dataSource) {
        this(dataSource, readRetentionDays(), readStoreRoot());
    }

    /**
     * Test constructor — explicit window and ingest store root; the retinal
     * roots still come from configuration.
     */
    public ImageIngestRetentionService(DataSource dataSource, int retentionDays, Path storeRoot) {
        this(dataSource, retentionDays, storeRoot,
                Path.of(RetinalArtifactStorageService.configuredStorePath()),
                Path.of(RetinalArtifactStorageService.configuredBscanStorePath()));
    }

    /** Every root explicit. */
    public ImageIngestRetentionService(DataSource dataSource, int retentionDays, Path storeRoot,
                                       Path artifactRoot, Path bscanRoot) {
        this.dataSource = dataSource;
        this.retentionDays = retentionDays > 0 ? retentionDays : DEFAULT_RETENTION_DAYS;
        this.artifactRoot = artifactRoot;
        this.bscanRoot = bscanRoot;
        // The explicit root is accepted alongside the configured ones, so a
        // caller handed a root (a test, an override) still resolves against it.
        this.artifactStore = new IngestArtifactStore(
                storeRoot == null ? List.of() : List.of(storeRoot));
    }

    private final IngestArtifactStore artifactStore;

    static int readRetentionDays() {
        try {
            String raw = CoreResources.getField(CONFIG_KEY_RETENTION_DAYS);
            if (raw == null || raw.isBlank()) return DEFAULT_RETENTION_DAYS;
            int v = Integer.parseInt(raw.trim());
            return v > 0 ? v : DEFAULT_RETENTION_DAYS;
        } catch (Exception e) {
            LOG.warn("Failed to read {}, using default {} days",
                    CONFIG_KEY_RETENTION_DAYS, DEFAULT_RETENTION_DAYS);
            return DEFAULT_RETENTION_DAYS;
        }
    }

    static Path readStoreRoot() {
        try {
            String raw = CoreResources.getField(CONFIG_KEY_STORE_PATH);
            if (raw != null && !raw.isBlank()) return Path.of(raw.trim());
        } catch (Exception ignored) {
            // CoreResources unavailable — use the default.
        }
        return Path.of(DEFAULT_STORE_PATH);
    }

    public int getRetentionDays() {
        return retentionDays;
    }

    /**
     * Remove dismissed files whose review window has passed, each with
     * everything derived from it.
     *
     * <p>A dismissed OCT scan is not one row. Its inference jobs point at it
     * through a RESTRICT foreign key; their results, corrections and CRF
     * provenance point at the jobs; each finished job has an artifact
     * directory, and the preprocess step left companions in the B-scan store.
     * This sweep used to delete the stored file first and then the row, which
     * that foreign key refused — so an analysed scan lost its file and kept
     * the rest, and the sweep failed on it again every night.
     *
     * <p>Per item, now: database first, in one transaction (provenance links
     * cleared, corrections, results, jobs, the row); files only after the
     * commit, each confined to its own root and kept when another row still
     * uses it. A failed transaction therefore leaves every file in place. A
     * row whose file the old order already deleted takes the same path, so
     * the stranded rows clear on the first run of this version.
     *
     * @return the number of {@code ingest_item} rows removed
     */
    public int garbageCollect() {
        if (dataSource == null) {
            LOG.warn("ImageIngestRetentionService: null DataSource — skipping");
            return 0;
        }

        List<Long> expired = findExpired();
        if (expired.isEmpty()) {
            LOG.debug("ImageIngestRetentionService: nothing dismissed longer than {} days ago", retentionDays);
            return 0;
        }

        Totals t = new Totals();
        for (long id : expired) {
            Outcome o;
            try {
                o = sweepOne(id);
            } catch (SQLException e) {
                LOG.warn("ImageIngestRetentionService: ingest_item {} kept — its rows could not be removed: {}",
                        id, e.getMessage());
                t.failed++;
                continue;
            }
            switch (o.kind) {
                case REMOVED -> {
                    t.removed++;
                    t.jobs += o.jobs;
                    t.results += o.results;
                    t.corrections += o.corrections;
                    t.provenance += o.provenance;
                    int deleted = deleteFiles(id, o.toDelete);
                    t.files += deleted;
                    LOG.info("ImageIngestRetentionService: ingest_item {} removed with {} analysis job(s), "
                                    + "{} result(s), {} correction(s); {} file(s)/director(ies) deleted, {} kept "
                                    + "because another row still uses them",
                            id, o.jobs, o.results, o.corrections, deleted, o.sharedKept);
                    if (o.provenance > 0) {
                        LOG.warn("ImageIngestRetentionService: ingest_item {} — {} CRF value(s) named it or its "
                                + "jobs as their source; the values are kept, the link is cleared", id, o.provenance);
                    }
                }
                case KEPT_RUNNING -> t.keptRunning++;
                case KEPT_OUTSIDE -> t.keptOutside++;
                case GONE -> { /* restored or removed in between */ }
            }
        }

        if (t.keptOutside > 0) {
            LOG.warn("ImageIngestRetentionService: {} dismissed rows kept — a stored file, artifact directory "
                    + "or companion directory resolves outside its store and was not deleted", t.keptOutside);
        }
        writeGcAudit(t);
        LOG.info("ImageIngestRetentionService: removed {} dismissed ingest_item rows ({} analysis jobs, {} results, "
                        + "{} files/directories) dismissed more than {} days ago; kept {} running, {} outside store, "
                        + "{} failed",
                t.removed, t.jobs, t.results, t.files, retentionDays, t.keptRunning, t.keptOutside, t.failed);
        return t.removed;
    }

    private List<Long> findExpired() {
        List<Long> out = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT ingest_item_id FROM ingest_item WHERE " + EXPIRED + " ORDER BY ingest_item_id")) {
            ps.setInt(1, retentionDays);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getLong(1));
            }
        } catch (SQLException e) {
            LOG.warn("ImageIngestRetentionService.findExpired failed: {}", e.getMessage());
        }
        return out;
    }

    /**
     * bound_at carries the dismissal timestamp (the dismiss path sets it);
     * received_at covers rows written before that was true. One placeholder:
     * the window in days.
     */
    private static final String EXPIRED = "status = 'DISMISSED' "
            + "AND COALESCE(bound_at, received_at) < now() - (? * INTERVAL '1 day')";

    /** Job states in which a worker or the remote dispatch is writing: wait for the next night. */
    private static final Set<String> IN_PROGRESS = Set.of("screening", "screened", "segmenting");

    /** Job states that are finished, or never started and safe to drop under the row lock. */
    private static final Set<String> REMOVABLE =
            Set.of("done", "failed", "cancelled", "queued", "parked", "remote_pending");

    private enum OutcomeKind { REMOVED, KEPT_RUNNING, KEPT_OUTSIDE, GONE }

    private static final class Outcome {
        OutcomeKind kind;
        int jobs;
        int results;
        int corrections;
        int provenance;
        int sharedKept;
        final List<Doomed> toDelete = new ArrayList<>();

        static Outcome of(OutcomeKind k) {
            Outcome o = new Outcome();
            o.kind = k;
            return o;
        }
    }

    /** A file or directory to delete after the commit, with the root it must lie under. */
    private record Doomed(Path path, List<Path> roots, boolean directory) {}

    private static final class Totals {
        int removed, jobs, results, corrections, provenance, files, keptRunning, keptOutside, failed;
    }

    /** Database half of one item, in one transaction. Files are only listed here. */
    private Outcome sweepOne(long id) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            boolean autoCommit = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                Outcome o = sweepOneInTransaction(c, id);
                if (o.kind == OutcomeKind.REMOVED || o.kind == OutcomeKind.KEPT_RUNNING) c.commit();
                else c.rollback();
                return o;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(autoCommit);
            }
        }
    }

    private Outcome sweepOneInTransaction(Connection c, long id) throws SQLException {
        // Re-read under a lock: a restore between the listing and now wins.
        String storedPath;
        String previewPath;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT stored_path, preview_png_path FROM ingest_item "
                        + " WHERE ingest_item_id = ? AND " + EXPIRED + " FOR UPDATE")) {
            ps.setLong(1, id);
            ps.setInt(2, retentionDays);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Outcome.of(OutcomeKind.GONE);
                storedPath = rs.getString(1);
                previewPath = rs.getString(2);
            }
        }

        // The jobs, locked: a worker claiming one now waits for this
        // transaction and then finds nothing to claim.
        List<Long> jobIds = new ArrayList<>();
        Set<String> scanPaths = new LinkedHashSet<>();
        if (storedPath != null && !storedPath.isBlank()) scanPaths.add(storedPath);
        boolean busy = false;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT job_id, status, e2e_path FROM retinal_inference_job "
                        + " WHERE ingest_item_id = ? ORDER BY job_id FOR UPDATE")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long jobId = rs.getLong(1);
                    String status = rs.getString(2) == null ? "" : rs.getString(2).toLowerCase(Locale.ROOT);
                    jobIds.add(jobId);
                    if (rs.getString(3) != null && !rs.getString(3).isBlank()) scanPaths.add(rs.getString(3));
                    if (IN_PROGRESS.contains(status) || !REMOVABLE.contains(status)) {
                        LOG.info("ImageIngestRetentionService: ingest_item {} kept until its analysis job {} "
                                + "({}) finishes", id, jobId, status);
                        busy = true;
                    }
                }
            }
        }
        if (busy) {
            // Nothing new may start for a dismissed scan. The dismiss path does
            // this since the fix; rows dismissed before it still need it.
            cancelWaiting(c, id);
            return Outcome.of(OutcomeKind.KEPT_RUNNING);
        }

        List<String> artifactDirs = new ArrayList<>();
        if (!jobIds.isEmpty()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT bscan_masks_dir FROM retinal_inference_result "
                            + " WHERE job_id = ANY (?) AND bscan_masks_dir IS NOT NULL")) {
                ps.setArray(1, c.createArrayOf("bigint", jobIds.toArray()));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        if (!rs.getString(1).isBlank()) artifactDirs.add(rs.getString(1));
                    }
                }
            }
        }

        // Classify every path before anything is deleted. One outside its
        // root keeps the whole item: the discrepancy stays visible.
        List<Path> fileRoots = artifactStore.readRoots();
        List<Path> artifactRoots = List.of(artifactRoot);
        List<Path> bscanRoots = List.of(bscanRoot);
        Outcome o = Outcome.of(OutcomeKind.REMOVED);
        List<Candidate> candidates = new ArrayList<>();
        for (String p : new String[] { storedPath, previewPath }) {
            if (p != null && !p.isBlank()) candidates.add(new Candidate(p, fileRoots, false, Use.FILE));
        }
        for (String d : artifactDirs) candidates.add(new Candidate(d, artifactRoots, true, Use.ARTIFACT_DIR));
        Set<String> keys = new LinkedHashSet<>();
        for (String scan : scanPaths) {
            String key = RetinalArtifactKey.of(scan);
            if (key != null && keys.add(key)) {
                if (!SAFE_KEY.matcher(key).matches() || key.equals(".") || key.equals("..")) {
                    LOG.warn("ImageIngestRetentionService: ingest_item {} kept — its companion key is not a "
                            + "plain directory name", id);
                    return Outcome.of(OutcomeKind.KEPT_OUTSIDE);
                }
                Candidate companion = new Candidate(bscanRoot.resolve(key).toString(), bscanRoots, true,
                        Use.COMPANION);
                companion.source = scan;
                candidates.add(companion);
            }
        }
        for (Candidate cand : candidates) {
            cand.state = classify(cand.raw, cand.roots, cand.use == Use.ARTIFACT_DIR);
            if (cand.state == PathState.OUTSIDE) {
                LOG.warn("ImageIngestRetentionService: ingest_item {} kept — a recorded {} lies outside its store "
                        + "and is not deleted", id, cand.use.label);
                return Outcome.of(OutcomeKind.KEPT_OUTSIDE);
            }
        }

        // The database half: provenance links first (the values are clinical
        // data and stay), then the job's dependants, the jobs, the row.
        Array jobs = c.createArrayOf("bigint", jobIds.toArray());
        o.provenance += update(c, "UPDATE item_data SET source_retinal_job_id = NULL "
                + " WHERE source_retinal_job_id = ANY (?)", jobs);
        o.provenance += update(c, "UPDATE item_data SET source_ingest_item_id = NULL "
                + " WHERE source_ingest_item_id = ?", id);
        o.corrections = update(c, "DELETE FROM retinal_inference_correction WHERE job_id = ANY (?)", jobs);
        o.results = update(c, "DELETE FROM retinal_inference_result WHERE job_id = ANY (?)", jobs);
        o.jobs = update(c, "DELETE FROM retinal_inference_job WHERE job_id = ANY (?)", jobs);
        if (update(c, "DELETE FROM ingest_item WHERE ingest_item_id = ? AND status = 'DISMISSED'", id) == 0) {
            return Outcome.of(OutcomeKind.GONE);
        }

        // With this item's rows gone, whatever still names a path is another
        // scan's: a two-volume .e2e is one file and two rows, and both share
        // one companion directory.
        for (Candidate cand : candidates) {
            if (cand.state != PathState.PRESENT) continue;
            boolean shared = switch (cand.use) {
                case FILE, ARTIFACT_DIR -> pathStillReferenced(c, cand.raw);
                case COMPANION -> keyStillUsed(c, Path.of(cand.raw).getFileName().toString(), cand.source);
            };
            if (shared) {
                o.sharedKept++;
                continue;
            }
            o.toDelete.add(new Doomed(Path.of(cand.raw).toAbsolutePath().normalize(), cand.roots, cand.directory));
        }
        return o;
    }

    private enum Use {
        FILE("stored file"), ARTIFACT_DIR("artifact directory"), COMPANION("companion directory");

        final String label;

        Use(String label) {
            this.label = label;
        }
    }

    private static final class Candidate {
        final String raw;
        final List<Path> roots;
        final boolean directory;
        final Use use;
        PathState state;
        /** For a companion directory: the scan path its key derives from. */
        String source;

        Candidate(String raw, List<Path> roots, boolean directory, Use use) {
            this.raw = raw;
            this.roots = roots;
            this.directory = directory;
            this.use = use;
        }
    }

    /** A companion key is a file stem or a UUID; anything else is not a name to join onto a root. */
    private static final java.util.regex.Pattern SAFE_KEY = java.util.regex.Pattern.compile("[A-Za-z0-9_.-]+");

    private enum PathState { PRESENT, GONE, OUTSIDE }

    /**
     * Where a recorded path stands. GONE is nothing to do; OUTSIDE is a path
     * that exists but not strictly below any of its roots (or, for an artifact
     * directory, one that would contain the B-scan store).
     */
    private PathState classify(String raw, List<Path> roots, boolean artifactDir) {
        Path candidate;
        try {
            candidate = Path.of(raw).toAbsolutePath().normalize();
        } catch (Exception notAPath) {
            return PathState.OUTSIDE;
        }
        if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) return PathState.GONE;
        // The B-scan store defaults to a subdirectory of the artifact store.
        // A job's directory is never that store, above it, or inside it.
        Path bscans = bscanRoot.toAbsolutePath().normalize();
        if (artifactDir && (bscans.startsWith(candidate) || candidate.startsWith(bscans))) {
            return PathState.OUTSIDE;
        }
        for (Path root : roots) {
            if (IngestArtifactStore.isStrictlyUnder(candidate, root)) return PathState.PRESENT;
        }
        return PathState.OUTSIDE;
    }

    private static void cancelWaiting(Connection c, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE retinal_inference_job SET status = 'cancelled', "
                        + "status_message = 'scan dismissed before this job ran' "
                        + " WHERE ingest_item_id = ? AND status IN ('queued','remote_pending','parked')")) {
            ps.setLong(1, id);
            int n = ps.executeUpdate();
            if (n > 0) LOG.info("ImageIngestRetentionService: ingest_item {} — {} waiting job(s) cancelled", id, n);
        }
    }

    private static int update(Connection c, String sql, Object param) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            if (param instanceof Array a) ps.setArray(1, a);
            else ps.setLong(1, (Long) param);
            return ps.executeUpdate();
        }
    }

    /**
     * Rows that may name the same file: matched loosely on the file name in
     * SQL, then exactly on the normalised path here.
     */
    private static final String PATHS_NAMING = ""
            + "SELECT stored_path FROM ingest_item WHERE position(lower(?) in lower(stored_path)) > 0 "
            + "UNION ALL SELECT preview_png_path FROM ingest_item "
            + "  WHERE preview_png_path IS NOT NULL AND position(lower(?) in lower(preview_png_path)) > 0 "
            + "UNION ALL SELECT e2e_path FROM retinal_inference_job WHERE position(lower(?) in lower(e2e_path)) > 0 "
            + "UNION ALL SELECT bscan_masks_dir FROM retinal_inference_result "
            + "  WHERE bscan_masks_dir IS NOT NULL AND position(lower(?) in lower(bscan_masks_dir)) > 0";

    private static List<String> pathsNaming(Connection c, String fragment) throws SQLException {
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(PATHS_NAMING)) {
            for (int i = 1; i <= 4; i++) ps.setString(i, fragment);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getString(1));
            }
        }
        return out;
    }

    private static boolean pathStillReferenced(Connection c, String raw) throws SQLException {
        Path target = Path.of(raw).toAbsolutePath().normalize();
        Path name = target.getFileName();
        if (name == null) return true;
        for (String other : pathsNaming(c, name.toString())) {
            try {
                if (Path.of(other).toAbsolutePath().normalize().equals(target)) return true;
            } catch (Exception unparseable) {
                // Not a path this one could be.
            }
        }
        return false;
    }

    /**
     * Whether a remaining scan maps onto the same companion directory. An .e2e
     * key is the file's stem, so another file of that name shares it; a key of
     * any other file is a hash of its whole path, so only that path does.
     * Either way the other path ends in the same file name, which is what the
     * lookup matches on before the key is compared exactly.
     */
    private static boolean keyStillUsed(Connection c, String key, String sourcePath) throws SQLException {
        Path name;
        try {
            name = Path.of(sourcePath).getFileName();
        } catch (Exception unparseable) {
            return true;
        }
        if (name == null) return true;
        for (String other : pathsNaming(c, name.toString())) {
            if (key.equals(RetinalArtifactKey.of(other))) return true;
        }
        return false;
    }

    /** The file half, after the commit. Every path is re-checked against its root here. */
    private int deleteFiles(long id, List<Doomed> doomed) {
        int deleted = 0;
        for (Doomed d : doomed) {
            boolean inside = false;
            for (Path root : d.roots()) {
                if (IngestArtifactStore.isStrictlyUnder(d.path(), root)) {
                    inside = true;
                    break;
                }
            }
            if (!inside) {
                if (Files.exists(d.path(), LinkOption.NOFOLLOW_LINKS)) {
                    LOG.warn("ImageIngestRetentionService: ingest_item {} — a path left its store between "
                            + "the check and the delete; not deleted", id);
                }
                continue;
            }
            try {
                if (d.directory() && Files.isDirectory(d.path(), LinkOption.NOFOLLOW_LINKS)) {
                    deleteTree(d.path());
                } else {
                    Files.deleteIfExists(d.path());
                }
                deleted++;
            } catch (IOException e) {
                LOG.warn("ImageIngestRetentionService: ingest_item {} — could not delete a stored {}: {}",
                        id, d.directory() ? "directory" : "file", e.getClass().getSimpleName());
            }
        }
        return deleted;
    }

    /** Depth-first delete that never follows a symbolic link out of the tree. */
    private static void deleteTree(Path dir) throws IOException {
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                if (exc != null) throw exc;
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void writeGcAudit(Totals t) {
        if (t.removed == 0) return;
        StringBuilder v = new StringBuilder(String.format(
                "purged %d dismissed files (%d analysis jobs, %d results, %d corrections; "
                        + "%d stored files and directories deleted) dismissed more than %d days ago",
                t.removed, t.jobs, t.results, t.corrections, t.files, retentionDays));
        if (t.provenance > 0) v.append("; ").append(t.provenance).append(" CRF source links cleared, values kept");
        if (t.keptRunning > 0) v.append("; kept ").append(t.keptRunning).append(" with an analysis still running");
        if (t.keptOutside > 0) v.append("; kept ").append(t.keptOutside).append(" with a path outside its store");
        if (t.failed > 0) v.append("; ").append(t.failed).append(" failed and kept");
        String newValue = v.toString();
        // user_id NULL: nobody performed this, the schedule did. Same idiom the
        // public upload path uses for its system-authored audit rows.
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO audit_log_event (audit_log_event_type_id, audit_date, "
                             + "user_id, audit_table, entity_id, entity_name, old_value, new_value) "
                             + "VALUES (?, now(), NULL, 'ingest_item', 0, ?, NULL, ?)")) {
            ps.setInt(1, AUDIT_TYPE_IMAGE_DISMISS);
            ps.setString(2, "Retention sweep");
            ps.setString(3, newValue);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warn("ImageIngestRetentionService: failed to write the sweep audit row: {}", e.getMessage());
        }
    }

}
