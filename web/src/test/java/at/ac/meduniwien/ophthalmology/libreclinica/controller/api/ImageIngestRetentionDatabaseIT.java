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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.ImageIngestRetentionService;

/**
 * DR-025 — the nightly sweep that removes dismissed fundus images.
 *
 * <p>The sweep deletes files and rows, so what it must NOT touch matters more
 * than what it removes: bound images are study data, unbound ones are still
 * awaiting an operator's decision, and anything whose stored path escapes the
 * ingest store is a misconfiguration rather than a licence to delete.
 */
class ImageIngestRetentionDatabaseIT extends AbstractApiControllerDatabaseIT {

    @TempDir
    Path store;

    @TempDir
    Path elsewhere;

    @AfterEach
    void cleanUp() throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM ingest_item WHERE original_filename LIKE 'retention-it-%'")) {
            ps.executeUpdate();
        }
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM audit_log_event WHERE audit_table = 'ingest_item' "
                             + "AND entity_name = 'Retention sweep'")) {
            ps.executeUpdate();
        }
    }

    /* ---------------- fixtures ---------------- */

    /**
     * @param status      UNBOUND / BOUND / DISMISSED
     * @param decidedDaysAgo how long ago the decision was taken (bound_at)
     */
    private long insert(String status, int decidedDaysAgo, Path stored, Path preview) throws Exception {
        Files.writeString(stored, "image bytes");
        if (preview != null) Files.writeString(preview, "preview bytes");
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO ingest_item (kind, source_kind, content_type, stored_path, "
                             + "preview_png_path, original_filename, received_at, status, bound_at) "
                             + "VALUES ('image', 'upload', 'image/png', ?, ?, ?, "
                             + "        now() - (? * INTERVAL '1 day'), ?, "
                             + "        now() - (? * INTERVAL '1 day')) "
                             + "RETURNING ingest_item_id")) {
            ps.setString(1, stored.toString());
            ps.setString(2, preview == null ? null : preview.toString());
            ps.setString(3, "retention-it-" + System.nanoTime());
            ps.setInt(4, decidedDaysAgo);
            ps.setString(5, status);
            ps.setInt(6, decidedDaysAgo);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getLong(1);
            }
        }
    }

    private boolean rowExists(long id) throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM ingest_item WHERE ingest_item_id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }

    private int sweepAuditRows() throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM audit_log_event WHERE audit_table = 'ingest_item' "
                             + "AND entity_name = 'Retention sweep' AND user_id IS NULL")) {
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private ImageIngestRetentionService service() {
        return new ImageIngestRetentionService(DATA_SOURCE, 30, store);
    }

    /* ---------------- what gets removed ---------------- */

    @Test
    void dismissedPastTheWindow_isRemovedWithItsFiles() throws Exception {
        Path img = store.resolve("old.jpg");
        Path prev = store.resolve("old-preview.png");
        long id = insert("DISMISSED", 45, img, prev);

        assertEquals(1, service().garbageCollect());

        assertFalse(rowExists(id), "the row should be gone");
        assertFalse(Files.exists(img), "the stored image should be deleted");
        assertFalse(Files.exists(prev), "the preview should be deleted too");
    }

    @Test
    void aPassThatRemovedSomething_leavesOneAuditRow() throws Exception {
        insert("DISMISSED", 45, store.resolve("a.jpg"), null);
        insert("DISMISSED", 60, store.resolve("b.jpg"), null);

        assertEquals(2, service().garbageCollect());
        assertEquals(1, sweepAuditRows(), "one summary row per pass, not one per image");
    }

    @Test
    void aPassThatRemovedNothing_writesNoAudit() throws Exception {
        insert("DISMISSED", 3, store.resolve("fresh.jpg"), null);

        assertEquals(0, service().garbageCollect());
        assertEquals(0, sweepAuditRows());
    }

    /* ---------------- what must survive ---------------- */

    @Test
    void dismissedInsideTheWindow_isKept() throws Exception {
        Path img = store.resolve("recent.jpg");
        long id = insert("DISMISSED", 3, img, null);

        service().garbageCollect();

        assertTrue(rowExists(id), "a recent dismissal is still within its review window");
        assertTrue(Files.exists(img));
    }

    @Test
    void boundImages_areStudyDataAndAreNeverSwept() throws Exception {
        Path img = store.resolve("bound.jpg");
        long id = insert("BOUND", 400, img, null);

        service().garbageCollect();

        assertTrue(rowExists(id), "a bound image is study data regardless of age");
        assertTrue(Files.exists(img));
    }

    @Test
    void unboundImages_awaitADecisionAndAreNeverSwept() throws Exception {
        Path img = store.resolve("unbound.jpg");
        long id = insert("UNBOUND", 400, img, null);

        service().garbageCollect();

        assertTrue(rowExists(id), "an unbound image has had no decision taken on it yet");
        assertTrue(Files.exists(img));
    }

    /**
     * The paths come from the database. A sweep holding a delete primitive over
     * arbitrary paths is a liability, so one that escapes the store is skipped
     * and its row kept, leaving the discrepancy visible.
     */
    @Test
    void aPathOutsideTheStore_isNeitherDeletedNorForgotten() throws Exception {
        Path stray = elsewhere.resolve("not-ours.jpg");
        long id = insert("DISMISSED", 45, stray, null);

        assertEquals(0, service().garbageCollect());

        assertTrue(Files.exists(stray), "the sweep must not delete outside its own store");
        assertTrue(rowExists(id), "the row should stay so the misconfiguration is visible");
    }

    /**
     * A traversal that lands back outside the store must be rejected even
     * though the string starts with the store path.
     */
    @Test
    void aTraversalOutOfTheStore_isRejected() throws Exception {
        Path stray = elsewhere.resolve("escaped.jpg");
        Files.writeString(stray, "image bytes");
        Path traversal = store.resolve("..").resolve(elsewhere.getFileName()).resolve("escaped.jpg");

        long id;
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO ingest_item (kind, source_kind, content_type, stored_path, "
                             + "original_filename, received_at, status, bound_at) "
                             + "VALUES ('image', 'upload', 'image/png', ?, ?, now() - INTERVAL '45 days', "
                             + "        'DISMISSED', now() - INTERVAL '45 days') "
                             + "RETURNING ingest_item_id")) {
            ps.setString(1, traversal.toString());
            ps.setString(2, "retention-it-traversal");
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                id = rs.getLong(1);
            }
        }

        assertEquals(0, service().garbageCollect());
        assertTrue(Files.exists(stray), "a ../ path out of the store must not be deleted");
        assertTrue(rowExists(id));
    }

    /** A file already gone still lets the row go — nothing is left orphaned. */
    @Test
    void aMissingFile_doesNotStrandTheRow() throws Exception {
        Path img = store.resolve("vanished.jpg");
        long id = insert("DISMISSED", 45, img, null);
        Files.delete(img);

        assertEquals(1, service().garbageCollect());
        assertFalse(rowExists(id));
    }
}
