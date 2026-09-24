/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.sql.DataSource;

import at.ac.meduniwien.ophthalmology.libreclinica.dao.core.CoreResources;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.IngestArtifactStore;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RetinalArtifactStorageService;
import at.ac.meduniwien.ophthalmology.libreclinica.service.system.DirectoryUsage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * DR-033 — measures what the platform stores, once an hour, and keeps the
 * result for 90 days.
 *
 * <p>The file stores are walked (see {@link DirectoryUsage} for why that is
 * too slow to do on a page load), the filesystem under each is asked for its
 * size and free space, and the database is asked for its size. One row per
 * store per scan goes into {@code storage_usage_sample}, all with the same
 * {@code sampled_at}; the System Status page reads the newest scan and
 * compares it with the one a week earlier.
 *
 * <p>A sysadmin can ask for a scan now ({@link #requestRescan()}); it runs on
 * its own thread, and a scan already under way is not started twice.
 *
 * <p>Lives in {@code controller.api} because that is what the MVC child
 * context scans, and {@code @Scheduled} fires there since #327 put
 * {@code @EnableScheduling} on {@code WebMvcConfig}.
 */
@Component
public class StorageUsageSampler {

    private static final Logger LOG = LoggerFactory.getLogger(StorageUsageSampler.class);

    static final long INTERVAL_MS = 3_600_000L;
    static final int RETENTION_DAYS = 90;
    /** Per store. A store beyond either is reported as a floor, not walked for an hour. */
    static final long MAX_FILES_PER_STORE = 5_000_000L;
    static final Duration MAX_TIME_PER_STORE = Duration.ofMinutes(15);

    static final String DATABASE_KEY = "database";

    /** One store: a key the page translates, and where it is. */
    record StoreSpec(String key, Path path) {
    }

    private final DataSource dataSource;
    private final AtomicBoolean scanning = new AtomicBoolean(false);
    private volatile Instant lastFinishedAt;
    private volatile long lastDurationMs = -1;

    @Autowired
    public StorageUsageSampler(@Qualifier("dataSource") DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Scheduled(fixedDelay = INTERVAL_MS, initialDelay = 180_000L)
    public void scheduled() {
        sampleNow();
    }

    /** Start a scan on its own thread; false when one is already running. */
    public boolean requestRescan() {
        if (scanning.get()) return false;
        Thread t = new Thread(this::sampleNow, "storage-usage-scan");
        t.setDaemon(true);
        t.start();
        return true;
    }

    public boolean isScanning() {
        return scanning.get();
    }

    public Instant lastFinishedAt() {
        return lastFinishedAt;
    }

    public long lastDurationMs() {
        return lastDurationMs;
    }

    /** One scan, synchronously. Returns false when another scan held the flag. */
    boolean sampleNow() {
        if (!scanning.compareAndSet(false, true)) return false;
        long started = System.nanoTime();
        try {
            List<StoreSpec> stores = resolveStores();
            List<Object[]> rows = new ArrayList<>();
            for (StoreSpec s : stores) {
                DirectoryUsage.Usage u = DirectoryUsage.measure(s.path(), MAX_FILES_PER_STORE, MAX_TIME_PER_STORE);
                DirectoryUsage.FileSystemInfo fs = DirectoryUsage.fileSystemOf(s.path());
                rows.add(new Object[] {s.key(), s.path().toString(), u.present(), u.bytes(), u.files(), u.complete(),
                        fs == null ? null : fs.key(), fs == null ? null : fs.type(),
                        fs == null ? null : fs.totalBytes(), fs == null ? null : fs.usableBytes()});
                if (!u.complete()) {
                    LOG.warn("storage scan: store '{}' was not walked completely ({} files seen)", s.key(), u.files());
                }
            }
            write(rows);
            lastFinishedAt = Instant.now();
            lastDurationMs = (System.nanoTime() - started) / 1_000_000L;
            LOG.info("storage scan: {} store(s) measured in {} ms", stores.size(), lastDurationMs);
            return true;
        } catch (SQLException e) {
            LOG.error("storage scan: could not record the result ({})", e.getSQLState());
            return true;
        } catch (RuntimeException e) {
            LOG.error("storage scan failed: {}", e.getClass().getSimpleName());
            return true;
        } finally {
            scanning.set(false);
        }
    }

    /**
     * The stores to measure, outermost first-come: a store configured inside
     * another (the B-scans inside the retinal artifacts, the CRF attachments
     * inside the app's data directory) is part of that one's total.
     */
    List<StoreSpec> resolveStores() {
        List<StoreSpec> candidates = new ArrayList<>();
        add(candidates, "ingest", configField(IngestArtifactStore.CONFIG_KEY_STORE_PATH,
                IngestArtifactStore.DEFAULT_STORE_PATH));
        add(candidates, "dicom-ingest", configField(IngestArtifactStore.LEGACY_KEY_DICOM,
                IngestArtifactStore.LEGACY_DEFAULT_DICOM));
        add(candidates, "e2e-uploads", configField(IngestArtifactStore.LEGACY_KEY_E2E,
                IngestArtifactStore.LEGACY_DEFAULT_E2E));
        add(candidates, "retinal-artifacts", configField("core.retinalInference.artifactStorePath",
                RetinalArtifactStorageService.DEFAULT_STORE_PATH));
        add(candidates, "retinal-bscans", configField("core.retinalInference.bscanStorePath", ""));
        // CRF attachments and dataset exports (filePath); attachments may be
        // configured elsewhere (attached_file_location).
        add(candidates, "app-data", configField("filePath", ""));
        add(candidates, "crf-attachments", configField("attached_file_location", ""));
        String catalinaHome = System.getProperty("catalina.home");
        if (catalinaHome != null && !catalinaHome.isBlank()) {
            add(candidates, "logs", Path.of(catalinaHome, "logs").toString());
        }

        List<Path> paths = new ArrayList<>();
        for (StoreSpec s : candidates) paths.add(s.path());
        List<StoreSpec> out = new ArrayList<>();
        for (int i : DirectoryUsage.outermost(paths)) out.add(candidates.get(i));
        return out;
    }

    private static void add(List<StoreSpec> list, String key, String raw) {
        if (raw == null || raw.isBlank()) return;
        try {
            list.add(new StoreSpec(key, Path.of(raw.trim()).toAbsolutePath().normalize()));
        } catch (RuntimeException invalidPath) {
            LOG.warn("storage scan: the configured path for store '{}' is not a path", key);
        }
    }

    private void write(List<Object[]> rows) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                Timestamp at = Timestamp.from(Instant.now());
                String sql = "INSERT INTO storage_usage_sample (sampled_at, store_key, store_path, present, "
                        + " used_bytes, file_count, complete, fs_key, fs_type, fs_total_bytes, fs_usable_bytes) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    for (Object[] r : rows) {
                        ps.setTimestamp(1, at);
                        ps.setString(2, (String) r[0]);
                        ps.setString(3, (String) r[1]);
                        ps.setBoolean(4, (Boolean) r[2]);
                        ps.setLong(5, (Long) r[3]);
                        ps.setLong(6, (Long) r[4]);
                        ps.setBoolean(7, (Boolean) r[5]);
                        ps.setString(8, (String) r[6]);
                        ps.setString(9, (String) r[7]);
                        setLong(ps, 10, (Long) r[8]);
                        setLong(ps, 11, (Long) r[9]);
                        ps.addBatch();
                    }
                    // The database as one more "store": its size, no filesystem.
                    ps.setTimestamp(1, at);
                    ps.setString(2, DATABASE_KEY);
                    ps.setNull(3, Types.VARCHAR);
                    ps.setBoolean(4, true);
                    ps.setLong(5, databaseSize(c));
                    ps.setNull(6, Types.BIGINT);
                    ps.setBoolean(7, true);
                    ps.setNull(8, Types.VARCHAR);
                    ps.setNull(9, Types.VARCHAR);
                    ps.setNull(10, Types.BIGINT);
                    ps.setNull(11, Types.BIGINT);
                    ps.addBatch();
                    ps.executeBatch();
                }
                try (PreparedStatement del = c.prepareStatement(
                        "DELETE FROM storage_usage_sample WHERE sampled_at < now() - (? * interval '1 day')")) {
                    del.setInt(1, RETENTION_DAYS);
                    del.executeUpdate();
                }
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(auto);
            }
        }
    }

    static long databaseSize(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT pg_database_size(current_database())");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static void setLong(PreparedStatement ps, int i, Long v) throws SQLException {
        if (v == null) ps.setNull(i, Types.BIGINT);
        else ps.setLong(i, v);
    }

    /** {@code datainfo.properties} read, overridable for tests. */
    protected String configField(String key, String fallback) {
        try {
            String raw = CoreResources.getField(key);
            if (raw != null && !raw.isBlank()) return raw.trim();
        } catch (Exception ignored) {
            // CoreResources unavailable outside a booted container.
        }
        return fallback;
    }
}
