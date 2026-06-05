/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.extract;

import java.io.File;
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
 * Phase E.6 — Retention sweep for {@code archived_dataset_file}.
 *
 * <p>Dataset exports stay on disk + in the {@code archived_dataset_file}
 * table indefinitely in the legacy code. For an active study with
 * frequent CSV/ODM dumps that means unbounded disk growth and an
 * indefinite egress window.
 *
 * <p>This service runs once a day (via Quartz; see
 * {@link ArchivedFileRetentionJob}) and removes the on-disk file +
 * its row for any export older than the configured retention window
 * ({@code libreclinica.export.retention.days}, default 90 days).
 *
 * <p>Each pass writes exactly one {@code audit_log_event} row of type
 * {@link ExportAuditService#AUDIT_TYPE_DATASET_EXPORTED} summarising
 * how many files were removed so operators can review GC activity in
 * the SPA's Audit Log timeline alongside the per-download events.
 */
public class ArchivedFileRetentionService {

    private static final Logger LOG = LoggerFactory.getLogger(ArchivedFileRetentionService.class);

    /** Datainfo.properties key carrying the retention window in days. */
    public static final String CONFIG_KEY_RETENTION_DAYS = "libreclinica.export.retention.days";

    /** Fallback when the key is absent or unparseable. */
    public static final int DEFAULT_RETENTION_DAYS = 90;

    /** Synthetic user_id used as the audit actor for unattended GC passes. */
    public static final int SYSTEM_USER_ID = 1;

    private final DataSource dataSource;
    private final int retentionDays;

    /**
     * Production constructor — pulls the retention window from the
     * application's {@code datainfo.properties} via
     * {@link CoreResources#getField(String)} (the same mechanism the
     * rest of the app uses for runtime config).
     */
    public ArchivedFileRetentionService(DataSource dataSource) {
        this(dataSource, readRetentionDays());
    }

    /**
     * Test constructor — accepts an explicit retention window. Use this
     * from unit tests with an in-memory DataSource to avoid pulling on
     * {@link CoreResources} static state.
     */
    public ArchivedFileRetentionService(DataSource dataSource, int retentionDays) {
        this.dataSource = dataSource;
        this.retentionDays = retentionDays > 0 ? retentionDays : DEFAULT_RETENTION_DAYS;
    }

    static int readRetentionDays() {
        try {
            String raw = CoreResources.getField(CONFIG_KEY_RETENTION_DAYS);
            if (raw == null || raw.isBlank()) return DEFAULT_RETENTION_DAYS;
            int v = Integer.parseInt(raw.trim());
            return v > 0 ? v : DEFAULT_RETENTION_DAYS;
        } catch (Exception e) {
            // CoreResources may not be initialised in some test paths;
            // fall back to the default rather than failing the GC.
            LOG.warn("Failed to read {} from CoreResources, using default {} days",
                    CONFIG_KEY_RETENTION_DAYS, DEFAULT_RETENTION_DAYS);
            return DEFAULT_RETENTION_DAYS;
        }
    }

    public int getRetentionDays() {
        return retentionDays;
    }

    /**
     * Sweep expired archived exports.
     *
     * <p>Pipeline:
     * <ol>
     *   <li>Find {@code archived_dataset_file} rows with
     *       {@code date_created < now() - retentionDays * INTERVAL '1 day'}.</li>
     *   <li>For each row, delete the on-disk file at
     *       {@code file_reference}. Missing files are tolerated (the
     *       row gets deleted regardless — we don't want a vanished
     *       file to keep the row around indefinitely).</li>
     *   <li>Delete the row.</li>
     *   <li>Emit one summary audit_log_event row capturing the
     *       count + window.</li>
     * </ol>
     *
     * @return the number of {@code archived_dataset_file} rows removed.
     */
    public int garbageCollect() {
        if (dataSource == null) {
            LOG.warn("ArchivedFileRetentionService.garbageCollect: null DataSource — skipping");
            return 0;
        }

        List<Expired> expired = findExpired();
        if (expired.isEmpty()) {
            LOG.debug("ArchivedFileRetentionService: no expired archived_dataset_file rows (window={} days)",
                    retentionDays);
            return 0;
        }

        int removed = 0;
        long bytesFreed = 0L;
        for (Expired row : expired) {
            bytesFreed += Math.max(0L, row.fileSize);
            if (row.fileReference != null && !row.fileReference.isBlank()) {
                File f = new File(row.fileReference);
                if (f.exists()) {
                    if (!f.delete()) {
                        LOG.warn("Failed to delete on-disk export {} — proceeding to drop DB row anyway",
                                row.fileReference);
                    }
                }
            }
            if (deleteRow(row.id)) {
                removed++;
            }
        }

        writeGcAudit(removed, bytesFreed);
        LOG.info("ArchivedFileRetentionService: removed {} archived_dataset_file rows older than {} days ({} bytes freed)",
                removed, retentionDays, bytesFreed);
        return removed;
    }

    private List<Expired> findExpired() {
        List<Expired> out = new ArrayList<>();
        // Cast retentionDays to text + concat is awkward in SQL; we
        // multiply a 1-day interval by the int parameter instead.
        String sql = "SELECT archived_dataset_file_id, file_reference, file_size "
                + "FROM archived_dataset_file "
                + "WHERE date_created < now() - (? * INTERVAL '1 day')";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, retentionDays);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Expired e = new Expired();
                    e.id = rs.getInt(1);
                    e.fileReference = rs.getString(2);
                    e.fileSize = rs.getLong(3);
                    out.add(e);
                }
            }
        } catch (SQLException e) {
            LOG.warn("ArchivedFileRetentionService.findExpired failed: {}", e.getMessage());
        }
        return out;
    }

    private boolean deleteRow(int archivedFileId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM archived_dataset_file WHERE archived_dataset_file_id = ?")) {
            ps.setInt(1, archivedFileId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            LOG.warn("ArchivedFileRetentionService.deleteRow failed for id={}: {}",
                    archivedFileId, e.getMessage());
            return false;
        }
    }

    private void writeGcAudit(int removed, long bytesFreed) {
        // One summary row per pass — uses entity_id = 0 because no
        // single dataset is being deleted (the rows span all studies).
        // entity_name carries a human label the SPA renders as the
        // "details" chip.
        String entityName = "Retention sweep";
        String newValue = String.format(
                "removed %d files older than %d days (%d bytes freed)",
                removed, retentionDays, bytesFreed);

        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO audit_log_event (audit_log_event_type_id, audit_date, "
                             + "user_id, audit_table, entity_id, entity_name, old_value, new_value) "
                             + "VALUES (?, now(), ?, 'dataset', ?, ?, NULL, ?)")) {
            ps.setInt(1, ExportAuditService.AUDIT_TYPE_DATASET_EXPORTED);
            ps.setInt(2, SYSTEM_USER_ID);
            ps.setInt(3, 0);
            ps.setString(4, entityName);
            ps.setString(5, newValue);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warn("ArchivedFileRetentionService: failed to write GC audit row: {}",
                    e.getMessage());
        }
    }

    /** Internal carrier for the SELECT result rows. */
    private static final class Expired {
        int id;
        String fileReference;
        long fileSize;
    }
}
