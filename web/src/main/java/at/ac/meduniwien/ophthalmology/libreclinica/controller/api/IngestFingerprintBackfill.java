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
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.sql.DataSource;

import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.DicomDescribeClient;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.ImageFingerprint;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.IngestArtifactStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * DR-036 — fills in {@code ingest_item.pixel_sha256} for the rows written
 * before the column existed, once an hour, so a picture uploaded last month
 * is recognised when it arrives again under another label.
 *
 * <p>Rows are taken in id order, up to {@value #MAX_ROWS_PER_RUN} per run,
 * and the digest is written only where the column is still empty. A file
 * whose picture cannot be read — gone from disk, outside the stores, not
 * decodable — is remembered for the life of the JVM and not tried again
 * every hour. DICOM rows need the sidecar; without one they are left for a
 * run that has it.
 *
 * <p>Lives in {@code controller.api} because that is what the MVC child
 * context scans, and {@code @Scheduled} fires there since #327 put
 * {@code @EnableScheduling} on {@code WebMvcConfig}.
 */
@Component
public class IngestFingerprintBackfill {

    private static final Logger LOG = LoggerFactory.getLogger(IngestFingerprintBackfill.class);

    static final long INTERVAL_MS = 3_600_000L;
    static final long INITIAL_DELAY_MS = 120_000L;
    static final int BATCH = 200;
    static final int MAX_ROWS_PER_RUN = 2_000;

    /** One row still without a digest. */
    record Row(long id, String kind, String storedPath, Integer scanIndex) {}

    private final DataSource dataSource;
    private final IngestArtifactStore store;
    private final DicomDescribeClient describe;
    private final Set<Long> unreadable = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Autowired
    public IngestFingerprintBackfill(@Qualifier("dataSource") DataSource dataSource) {
        this(dataSource, new IngestArtifactStore(), new DicomDescribeClient());
    }

    IngestFingerprintBackfill(DataSource dataSource, IngestArtifactStore store, DicomDescribeClient describe) {
        this.dataSource = dataSource;
        this.store = store;
        this.describe = describe;
    }

    @Scheduled(fixedDelay = INTERVAL_MS, initialDelay = INITIAL_DELAY_MS)
    public void run() {
        try {
            int done = runOnce();
            if (done > 0) LOG.info("ingest fingerprint backfill: {} rows digested", done);
        } catch (RuntimeException e) {
            LOG.warn("ingest fingerprint backfill failed: {}", e.getMessage());
        }
    }

    /** @return how many rows received a digest */
    int runOnce() {
        if (!running.compareAndSet(false, true)) return 0;
        try {
            boolean dicom = describe.isConfigured();
            long cursor = 0;
            int scanned = 0;
            int done = 0;
            while (scanned < MAX_ROWS_PER_RUN) {
                List<Row> batch = nextBatch(cursor, dicom);
                if (batch.isEmpty()) break;
                for (Row row : batch) {
                    cursor = row.id();
                    scanned++;
                    if (unreadable.contains(row.id())) continue;
                    String fp = fingerprint(row);
                    if (fp == null) {
                        unreadable.add(row.id());
                        continue;
                    }
                    if (write(row.id(), fp)) done++;
                }
                if (batch.size() < BATCH) break;
            }
            return done;
        } finally {
            running.set(false);
        }
    }

    private List<Row> nextBatch(long after, boolean includeDicom) {
        String kinds = includeDicom ? "('image', 'e2e', 'dicom')" : "('image', 'e2e')";
        List<Row> rows = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT ingest_item_id, kind, stored_path, scan_index FROM ingest_item "
                             + " WHERE pixel_sha256 IS NULL AND ingest_item_id > ? AND kind IN " + kinds
                             + " ORDER BY ingest_item_id LIMIT " + BATCH)) {
            ps.setLong(1, after);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int scan = rs.getInt("scan_index");
                    rows.add(new Row(rs.getLong("ingest_item_id"), rs.getString("kind"),
                            rs.getString("stored_path"), rs.wasNull() ? null : scan));
                }
            }
        } catch (SQLException e) {
            LOG.warn("ingest fingerprint backfill: could not list rows: {}", e.getMessage());
        }
        return rows;
    }

    /** The digest of the row's picture, or null when it cannot be had. */
    private String fingerprint(Row row) {
        Optional<Path> path = store.resolveConfined(row.storedPath());
        if (path.isEmpty()) return null;
        try {
            return switch (row.kind()) {
                case "image" -> ImageFingerprint.ofImage(path.get());
                case "e2e" -> ImageFingerprint.ofE2eVolume(path.get(), row.scanIndex() == null ? 0 : row.scanIndex());
                case "dicom" -> describe.fingerprint(path.get());
                default -> null;
            };
        } catch (IOException | DicomDescribeClient.DescribeException | RuntimeException e) {
            LOG.debug("ingest fingerprint backfill: ingest_item {} not digested: {}", row.id(),
                    e.getClass().getSimpleName());
            return null;
        }
    }

    private boolean write(long id, String fp) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE ingest_item SET pixel_sha256 = ? WHERE ingest_item_id = ? AND pixel_sha256 IS NULL")) {
            ps.setString(1, fp);
            ps.setLong(2, id);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            LOG.warn("ingest fingerprint backfill: could not write ingest_item {}: {}", id, e.getMessage());
            return false;
        }
    }
}
