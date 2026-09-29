/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.DicomDescribeClient;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.ImageFingerprint;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.IngestArtifactStore;

/**
 * DR-036 — rows from before the digest existed get one, once, from the file
 * on disk; a row whose file cannot be read is not tried every hour.
 */
@SuppressWarnings("null")
class IngestFingerprintBackfillDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static final String MARKER = "backfill-it-";

    /** 1x1 transparent PNG. */
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk"
                    + "+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==");

    @TempDir
    Path root;

    @AfterEach
    void cleanRows() throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM ingest_item WHERE original_filename LIKE '" + MARKER + "%'")) {
            ps.executeUpdate();
        }
    }

    private long seed(String kind, String storedPath) throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO ingest_item (kind, source_kind, stored_path, original_filename, "
                             + "received_at, status) VALUES (?, 'upload', ?, ?, now(), 'UNBOUND') "
                             + "RETURNING ingest_item_id")) {
            ps.setString(1, kind);
            ps.setString(2, storedPath);
            ps.setString(3, MARKER + System.nanoTime());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private String pixelSha256Of(long id) throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT pixel_sha256 FROM ingest_item WHERE ingest_item_id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    @Test
    void olderRowsGetTheirDigestFromTheFileOnDisk() throws Exception {
        Path png = root.resolve("old.png");
        Files.write(png, PNG);
        long readable = seed("image", png.toString());
        long gone = seed("image", root.resolve("missing.png").toString());
        long outside = seed("image", "/etc/hostname");
        long dicom = seed("dicom", png.toString());

        IngestFingerprintBackfill backfill = new IngestFingerprintBackfill(DATA_SOURCE,
                new IngestArtifactStore(List.of(root)), new DicomDescribeClient("", ""));
        assertEquals(1, backfill.runOnce());
        assertEquals(ImageFingerprint.ofImage(png), pixelSha256Of(readable));
        assertNull(pixelSha256Of(gone), "a file that is not there yields nothing");
        assertNull(pixelSha256Of(outside), "a path outside the stores is never read");
        assertNull(pixelSha256Of(dicom), "DICOM waits for a sidecar");

        // The unreadable rows are remembered; the readable one is done. Nothing left to do.
        assertEquals(0, backfill.runOnce());
    }
}
