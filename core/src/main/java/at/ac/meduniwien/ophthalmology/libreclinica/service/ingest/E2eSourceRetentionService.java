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
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.ac.meduniwien.ophthalmology.libreclinica.dao.core.CoreResources;

/**
 * DR-041 (amendment 2026-10-13 b) - the original {@code .E2E} of a split upload is
 * kept as a time-limited source copy and then deleted.
 *
 * <p>An upload is converted into one DICOM per series; the {@code .E2E} itself moves
 * to {@code core.retinalInference.e2eSourcePath} (default
 * {@code /var/lib/libreclinica/e2e-source}). Nothing serves or analyses it there.
 * This service moves a file in ({@link #moveIn}) and, on a schedule, deletes the
 * files older than {@code core.retinalInference.e2eSourceRetentionDays} (default 90)
 * ({@link #garbageCollect}). The age is the file's modification time, which
 * {@link #moveIn} sets to the moment of the move, so the window starts at the upload.
 *
 * <p>Only {@code <uuid>.e2e} regular files directly in the source directory are
 * touched: a symbolic link, a subdirectory or any other name is left alone. The items
 * made from the file are not affected - each is a DICOM with its own stored file and
 * keeps {@code ingest_item.e2e_source_sha256}, so the same export is still refused as a
 * duplicate after its source is gone.
 *
 * <p>One audit row (type {@value #AUDIT_TYPE_E2E_SOURCE_DELETED}) records each pass that
 * deleted files. File names are not logged.
 */
public class E2eSourceRetentionService {

    private static final Logger LOG = LoggerFactory.getLogger(E2eSourceRetentionService.class);

    public static final String CONFIG_KEY_SOURCE_PATH = "core.retinalInference.e2eSourcePath";
    public static final String DEFAULT_SOURCE_PATH = "/var/lib/libreclinica/e2e-source";
    public static final String CONFIG_KEY_RETENTION_DAYS = "core.retinalInference.e2eSourceRetentionDays";
    public static final int DEFAULT_RETENTION_DAYS = 90;

    /** {@code audit_log_event_type} 209 {@code e2e_source_deleted}, seeded by lc-muw-2026-10-14-e2e-source.xml. */
    public static final int AUDIT_TYPE_E2E_SOURCE_DELETED = 209;

    /** The names the app gives a stored upload: {@code <uuid>.e2e}. */
    private static final Pattern STORED_NAME = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.e2e");

    private final DataSource dataSource;
    private final Path root;
    private final int retentionDays;

    public E2eSourceRetentionService(DataSource dataSource) {
        this(dataSource, sourceRoot(), retentionDays());
    }

    public E2eSourceRetentionService(DataSource dataSource, Path root, int retentionDays) {
        this.dataSource = dataSource;
        this.root = root;
        this.retentionDays = retentionDays > 0 ? retentionDays : DEFAULT_RETENTION_DAYS;
    }

    public int getRetentionDays() {
        return retentionDays;
    }

    /** {@code core.retinalInference.e2eSourcePath}, else the default. */
    public static Path sourceRoot() {
        return Path.of(read(CONFIG_KEY_SOURCE_PATH, DEFAULT_SOURCE_PATH));
    }

    /** {@code core.retinalInference.e2eSourceRetentionDays} when it is a positive integer, else 90. */
    public static int retentionDays() {
        try {
            int v = Integer.parseInt(read(CONFIG_KEY_RETENTION_DAYS, "").trim());
            return v > 0 ? v : DEFAULT_RETENTION_DAYS;
        } catch (NumberFormatException e) {
            return DEFAULT_RETENTION_DAYS;
        }
    }

    /**
     * Moves a staged upload into the source directory and starts its retention clock.
     *
     * @return the new path, or null when the move failed; the staged file is then left
     *         where it was, and the caller decides what to do with it
     */
    public static Path moveIn(Path staged, Path sourceRoot) {
        try {
            Files.createDirectories(sourceRoot);
            Path target = sourceRoot.resolve(staged.getFileName().toString());
            Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING);
            // The window runs from the upload, whatever a move across file systems kept.
            Files.setLastModifiedTime(target, FileTime.from(Instant.now()));
            return target;
        } catch (IOException | RuntimeException e) {
            LOG.warn("could not move an uploaded .E2E into the source directory: {}", e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * Deletes the source copies older than the retention window.
     *
     * @return the number of files deleted
     */
    public int garbageCollect() {
        return garbageCollect(Instant.now());
    }

    public int garbageCollect(Instant now) {
        if (!Files.isDirectory(root)) {
            LOG.debug("E2eSourceRetentionService: no source directory yet - nothing to do");
            return 0;
        }
        Instant cutoff = now.minus(Duration.ofDays(retentionDays));
        int deleted = 0;
        int failed = 0;
        long bytes = 0;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(root)) {
            for (Path p : entries) {
                if (!STORED_NAME.matcher(p.getFileName().toString()).matches()) continue;
                BasicFileAttributes attrs;
                try {
                    attrs = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                } catch (IOException e) {
                    continue;
                }
                if (!attrs.isRegularFile() || !attrs.lastModifiedTime().toInstant().isBefore(cutoff)) continue;
                try {
                    Files.delete(p);
                    deleted++;
                    bytes += attrs.size();
                } catch (IOException e) {
                    failed++;
                    LOG.warn("E2eSourceRetentionService: could not delete an expired source copy: {}",
                            e.getClass().getSimpleName());
                }
            }
        } catch (IOException e) {
            LOG.warn("E2eSourceRetentionService: could not list the source directory: {}", e.getClass().getSimpleName());
            return deleted;
        }
        LOG.info("E2eSourceRetentionService: deleted {} source copies older than {} days ({} MB), {} failed",
                deleted, retentionDays, bytes / (1024 * 1024), failed);
        if (deleted > 0) writeAudit(deleted, bytes, failed);
        return deleted;
    }

    private void writeAudit(int deleted, long bytes, int failed) {
        if (dataSource == null) return;
        String value = String.format("deleted %d E2E source copies older than %d days (%d bytes)%s",
                deleted, retentionDays, bytes, failed > 0 ? "; " + failed + " could not be deleted" : "");
        // user_id NULL: nobody performed this, the schedule did (as the dismissed-image sweep does).
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO audit_log_event (audit_log_event_type_id, audit_date, "
                             + "user_id, audit_table, entity_id, entity_name, old_value, new_value) "
                             + "VALUES (?, now(), NULL, 'e2e_source', 0, ?, NULL, ?)")) {
            ps.setInt(1, AUDIT_TYPE_E2E_SOURCE_DELETED);
            ps.setString(2, "Retention sweep");
            ps.setString(3, value);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warn("E2eSourceRetentionService: failed to write the audit row: {}", e.getMessage());
        }
    }

    private static String read(String key, String fallback) {
        try {
            String raw = CoreResources.getField(key);
            if (raw != null && !raw.isBlank()) return raw.trim();
        } catch (Exception noContext) {
            // CoreResources is not initialised in some test paths.
        }
        return fallback;
    }
}
