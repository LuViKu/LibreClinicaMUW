/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.ingest;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.ac.meduniwien.ophthalmology.libreclinica.dao.core.CoreResources;

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
 * <p><strong>Deletion is confined to the ingest store.</strong> The paths come
 * from the database, and a retention job holding a delete primitive over
 * arbitrary paths is a liability — so anything resolving outside
 * {@code core.dicom.ingest.storePath} is skipped and logged rather than
 * deleted, and the row is kept so the discrepancy stays visible.
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
    private final Path storeRoot;

    /** Production constructor — window and store root from datainfo.properties. */
    public ImageIngestRetentionService(DataSource dataSource) {
        this(dataSource, readRetentionDays(), readStoreRoot());
    }

    /** Test constructor — explicit window and store root, no CoreResources. */
    public ImageIngestRetentionService(DataSource dataSource, int retentionDays, Path storeRoot) {
        this.dataSource = dataSource;
        this.retentionDays = retentionDays > 0 ? retentionDays : DEFAULT_RETENTION_DAYS;
        this.storeRoot = storeRoot;
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
     * Remove dismissed images whose review window has passed.
     *
     * @return the number of {@code image_ingest} rows removed
     */
    public int garbageCollect() {
        if (dataSource == null) {
            LOG.warn("ImageIngestRetentionService: null DataSource — skipping");
            return 0;
        }

        List<Expired> expired = findExpired();
        if (expired.isEmpty()) {
            LOG.debug("ImageIngestRetentionService: nothing dismissed longer than {} days ago", retentionDays);
            return 0;
        }

        int removed = 0;
        int filesDeleted = 0;
        int skippedOutsideStore = 0;
        for (Expired row : expired) {
            int deletedForRow = 0;
            boolean outside = false;
            for (String p : new String[] { row.storedPath, row.previewPath }) {
                if (p == null || p.isBlank()) continue;
                switch (deleteConfined(p)) {
                    case DELETED -> deletedForRow++;
                    case ALREADY_GONE -> { /* nothing to free */ }
                    case OUTSIDE_STORE -> outside = true;
                }
            }
            if (outside) {
                // Keep the row: a path outside the store is a misconfiguration
                // or a hand-edited row, and silently dropping the record would
                // hide it.
                skippedOutsideStore++;
                continue;
            }
            filesDeleted += deletedForRow;
            if (deleteRow(row.id)) removed++;
        }

        if (skippedOutsideStore > 0) {
            LOG.warn("ImageIngestRetentionService: {} dismissed rows kept — their stored path resolves "
                    + "outside {} and was not deleted", skippedOutsideStore, storeRoot);
        }
        writeGcAudit(removed, filesDeleted);
        LOG.info("ImageIngestRetentionService: removed {} dismissed image_ingest rows "
                + "({} files) dismissed more than {} days ago", removed, filesDeleted, retentionDays);
        return removed;
    }

    private List<Expired> findExpired() {
        List<Expired> out = new ArrayList<>();
        // bound_at carries the dismissal timestamp (the dismiss path sets it);
        // received_at covers rows written before that was true.
        String sql = "SELECT image_ingest_id, stored_path, preview_png_path "
                + "  FROM image_ingest "
                + " WHERE status = 'DISMISSED' "
                + "   AND COALESCE(bound_at, received_at) < now() - (? * INTERVAL '1 day')";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, retentionDays);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Expired e = new Expired();
                    e.id = rs.getLong(1);
                    e.storedPath = rs.getString(2);
                    e.previewPath = rs.getString(3);
                    out.add(e);
                }
            }
        } catch (SQLException e) {
            LOG.warn("ImageIngestRetentionService.findExpired failed: {}", e.getMessage());
        }
        return out;
    }

    private enum DeleteOutcome { DELETED, ALREADY_GONE, OUTSIDE_STORE }

    /**
     * Deletes a stored file only when the shared artifact store agrees it lies
     * under an ingest root.
     *
     * <p>P3.0 — this used to carry its own copy of the confinement logic. One
     * implementation matters more here than anywhere else: this is the only
     * code in the platform that deletes a file named by a database row.
     */
    private DeleteOutcome deleteConfined(String rawPath) {
        java.nio.file.Path resolved = artifactStore.resolveConfined(rawPath).orElse(null);
        if (resolved == null) {
            // Either it escapes every root, or it is already gone. Tell those
            // apart, because one is a misconfiguration worth keeping the row for
            // and the other is nothing to do.
            try {
                Path candidate = Path.of(rawPath).toAbsolutePath().normalize();
                if (!candidate.toFile().exists()) return DeleteOutcome.ALREADY_GONE;
            } catch (Exception notAPath) {
                return DeleteOutcome.OUTSIDE_STORE;
            }
            return DeleteOutcome.OUTSIDE_STORE;
        }
        File f = resolved.toFile();
        if (!f.exists()) return DeleteOutcome.ALREADY_GONE;
        if (f.delete()) return DeleteOutcome.DELETED;
        LOG.warn("ImageIngestRetentionService: could not delete a stored image");
        return DeleteOutcome.ALREADY_GONE;
    }

    private boolean deleteRow(long imageIngestId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM image_ingest WHERE image_ingest_id = ? AND status = 'DISMISSED'")) {
            ps.setLong(1, imageIngestId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            LOG.warn("ImageIngestRetentionService.deleteRow failed for id={}: {}",
                    imageIngestId, e.getMessage());
            return false;
        }
    }

    private void writeGcAudit(int removed, int filesDeleted) {
        if (removed == 0) return;
        String newValue = String.format(
                "purged %d dismissed images (%d files) dismissed more than %d days ago",
                removed, filesDeleted, retentionDays);
        // user_id NULL: nobody performed this, the schedule did. Same idiom the
        // public upload path uses for its system-authored audit rows.
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO audit_log_event (audit_log_event_type_id, audit_date, "
                             + "user_id, audit_table, entity_id, entity_name, old_value, new_value) "
                             + "VALUES (?, now(), NULL, 'image_ingest', 0, ?, NULL, ?)")) {
            ps.setInt(1, AUDIT_TYPE_IMAGE_DISMISS);
            ps.setString(2, "Retention sweep");
            ps.setString(3, newValue);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warn("ImageIngestRetentionService: failed to write the sweep audit row: {}", e.getMessage());
        }
    }

    /** Internal carrier for the SELECT result rows. */
    private static final class Expired {
        long id;
        String storedPath;
        String previewPath;
    }
}
