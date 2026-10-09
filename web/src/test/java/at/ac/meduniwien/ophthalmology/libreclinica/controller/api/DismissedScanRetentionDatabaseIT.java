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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.ac.meduniwien.ophthalmology.libreclinica.dao.core.CoreResources;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.ImageIngestRetentionService;

/**
 * The retention sweep for a dismissed OCT scan, which is not one row but a
 * chain: the ingest row, its inference jobs, their results and corrections,
 * the per-job artifact directories, and the preprocess companions in the
 * B-scan store.
 *
 * <p>Until this was fixed the sweep deleted the stored file first and then
 * tried the row, which the job's RESTRICT foreign key refused — so every
 * dismissed scan that had been analysed lost its file and kept everything
 * else, and the sweep failed on it again every night.
 */
class DismissedScanRetentionDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static final String TAG = "retention-chain-it-";

    @TempDir
    Path store;

    @TempDir
    Path artifacts;

    @TempDir
    Path bscans;

    @TempDir
    Path elsewhere;

    private Properties savedDatainfo;

    @BeforeEach
    void pointTheRetinalStoresAtTempDirs() throws Exception {
        Properties live = datainfo();
        savedDatainfo = new Properties();
        savedDatainfo.putAll(live);
        live.setProperty("core.retinalInference.artifactStorePath", artifacts.toString());
        live.setProperty("core.retinalInference.bscanStorePath", bscans.toString());
    }

    @AfterEach
    void cleanUp() throws Exception {
        Properties live = datainfo();
        live.clear();
        live.putAll(savedDatainfo);
        try (Connection c = DATA_SOURCE.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("UPDATE item_data SET source_retinal_job_id = NULL, source_ingest_item_id = NULL "
                    + " WHERE source_ingest_item_id IN (SELECT ingest_item_id FROM ingest_item "
                    + "                                  WHERE original_filename LIKE '" + TAG + "%')");
            s.executeUpdate("DELETE FROM retinal_inference_job WHERE ingest_item_id IN "
                    + "(SELECT ingest_item_id FROM ingest_item WHERE original_filename LIKE '" + TAG + "%')");
            s.executeUpdate("DELETE FROM ingest_item WHERE original_filename LIKE '" + TAG + "%'");
            s.executeUpdate("DELETE FROM audit_log_event WHERE audit_table = 'ingest_item' "
                    + "AND entity_name = 'Retention sweep'");
        }
    }

    private static Properties datainfo() throws Exception {
        Field f = CoreResources.class.getDeclaredField("DATAINFO");
        f.setAccessible(true);
        Properties live = (Properties) f.get(null);
        assertNotNull(live, "DATAINFO must be set by AbstractApiControllerDatabaseIT");
        return live;
    }

    /* ------------------------------------------------------------------ */
    /* fixtures                                                            */
    /* ------------------------------------------------------------------ */

    private ImageIngestRetentionService service() {
        return new ImageIngestRetentionService(DATA_SOURCE, 30, store);
    }

    /** A scan file on disk under the ingest store, named like a real upload. */
    private Path e2eFile() throws Exception {
        Path p = store.resolve(UUID.randomUUID() + ".e2e");
        Files.writeString(p, "e2e bytes");
        return p;
    }

    private long item(String status, int decidedDaysAgo, Path stored, Path preview, int scanIndex)
            throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO ingest_item (kind, source_kind, content_type, stored_path, preview_png_path, "
                             + "original_filename, laterality, scan_index, received_at, status, bound_at) "
                             + "VALUES ('e2e', 'upload', 'application/octet-stream', ?, ?, ?, 'OD', ?, "
                             + "        now() - (? * INTERVAL '1 day'), ?, now() - (? * INTERVAL '1 day')) "
                             + "RETURNING ingest_item_id")) {
            ps.setString(1, stored.toString());
            ps.setString(2, preview == null ? null : preview.toString());
            ps.setString(3, TAG + System.nanoTime());
            ps.setInt(4, scanIndex);
            ps.setInt(5, decidedDaysAgo);
            ps.setString(6, status);
            ps.setInt(7, decidedDaysAgo);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getLong(1);
            }
        }
    }

    private long job(long item, Path e2e, String task, String status) throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO retinal_inference_job (task, e2e_path, eye_laterality, status, enqueued_at, "
                             + "scan_index, ingest_item_id) VALUES (?, ?, 'OD', ?, now() - INTERVAL '50 days', 0, ?) "
                             + "RETURNING job_id")) {
            ps.setString(1, task);
            ps.setString(2, e2e.toString());
            ps.setString(3, status);
            ps.setLong(4, item);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getLong(1);
            }
        }
    }

    /** A result whose artifact directory exists and holds files, as the remote path writes it. */
    private Path result(long jobId, Path artifactDir) throws Exception {
        Files.createDirectories(artifactDir.resolve("corrections"));
        Files.writeString(artifactDir.resolve("fluidseg.npz"), "npz");
        Files.writeString(artifactDir.resolve("corrections").resolve("001-ILM.csv"), "csv");
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO retinal_inference_result (job_id, task, output_payload, bscan_masks_dir, "
                             + "en_face_mask_path) VALUES (?, 'fluid', '{}'::jsonb, ?, ?)")) {
            ps.setLong(1, jobId);
            ps.setString(2, artifactDir.toString());
            ps.setString(3, artifactDir.resolve("enface.png").toString());
            ps.executeUpdate();
        }
        return artifactDir;
    }

    private void correction(long jobId) throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO retinal_inference_correction (job_id, layer_index, layer_label, csv_relpath, "
                             + "edited_by_user_id) VALUES (?, 0, 'ILM', 'corrections/001-ILM.csv', 1)")) {
            ps.setLong(1, jobId);
            ps.executeUpdate();
        }
    }

    /** The preprocess sidecar's companions for a stored .e2e: scan 0 at the root, scan 1 in scan-1/. */
    private Path companions(Path e2e) throws Exception {
        String name = e2e.getFileName().toString();
        Path dir = bscans.resolve(name.substring(0, name.length() - 4));
        Files.createDirectories(dir.resolve("scan-1"));
        for (String f : new String[] { "bscan.dcm", "fundus.png", "geometry.json" }) {
            Files.writeString(dir.resolve(f), f);
            Files.writeString(dir.resolve("scan-1").resolve(f), f);
        }
        return dir;
    }

    /** An item_data row that names the job and the item as its provenance. */
    private int provenance(long jobId, long itemId) throws Exception {
        try (Connection c = DATA_SOURCE.getConnection()) {
            String label = "RCH-" + (System.nanoTime() % 1_000_000_000L);
            int ss = LifecycleFixtures.insertStudySubject(c, label, 1, 1);
            int def = LifecycleFixtures.insertDefinition(c, 1, "SE_" + label.replace("-", ""), 1);
            int event = LifecycleFixtures.insertEvent(c, def, ss, 1);
            int ecrf = LifecycleFixtures.insertEventCrf(c, event, ss, 1, 1);
            int itemData = LifecycleFixtures.insertItemData(c, ecrf, 1, 1, "retinal_inference");
            try (Statement s = c.createStatement()) {
                s.executeUpdate("UPDATE item_data SET source_retinal_job_id = " + jobId
                        + ", source_ingest_item_id = " + itemId + " WHERE item_data_id = " + itemData);
            }
            return itemData;
        }
    }

    private static int count(String sql, long id) throws Exception {
        try (Connection c = DATA_SOURCE.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static boolean itemExists(long id) throws Exception {
        return count("SELECT count(*) FROM ingest_item WHERE ingest_item_id = ?", id) > 0;
    }

    private static int jobsOf(long item) throws Exception {
        return count("SELECT count(*) FROM retinal_inference_job WHERE ingest_item_id = ?", item);
    }

    private static int resultsOf(long job) throws Exception {
        return count("SELECT count(*) FROM retinal_inference_result WHERE job_id = ?", job);
    }

    private static String jobStatus(long job) throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT status FROM retinal_inference_job WHERE job_id = ?")) {
            ps.setLong(1, job);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static String sweepAudit() throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT new_value FROM audit_log_event WHERE audit_table = 'ingest_item' "
                     + "AND entity_name = 'Retention sweep' AND user_id IS NULL ORDER BY audit_id")) {
            String last = null;
            int n = 0;
            while (rs.next()) {
                last = rs.getString(1);
                n++;
            }
            assertTrue(n <= 1, "one audit row per pass, found " + n);
            return last;
        }
    }

    /* ------------------------------------------------------------------ */
    /* the whole chain goes                                                */
    /* ------------------------------------------------------------------ */

    @Test
    void anAnalysedDismissedScanPastTheWindow_isRemovedWithItsWholeChain() throws Exception {
        Path e2e = e2eFile();
        Path preview = store.resolve("preview-" + UUID.randomUUID() + ".png");
        Files.writeString(preview, "png");
        long item = item("DISMISSED", 45, e2e, preview, 0);
        long done = job(item, e2e, "fluid", "done");
        long failed = job(item, e2e, "layers", "failed");
        long cancelled = job(item, e2e, "ga", "cancelled");
        Path artifactDir = result(done, artifacts.resolve(UUID.randomUUID().toString()));
        correction(done);
        Path companionDir = companions(e2e);
        int itemData = provenance(done, item);

        assertEquals(1, service().garbageCollect());

        assertFalse(itemExists(item), "the ingest row must go");
        assertEquals(0, jobsOf(item), "its jobs must go");
        assertEquals(0, resultsOf(done), "the result must go");
        assertEquals(0, count("SELECT count(*) FROM retinal_inference_correction WHERE job_id = ?", done));
        assertNull(jobStatus(failed));
        assertNull(jobStatus(cancelled));
        assertFalse(Files.exists(e2e), "the stored scan must be deleted");
        assertFalse(Files.exists(preview), "the preview must be deleted");
        assertFalse(Files.exists(artifactDir), "the per-job artifact directory must be deleted");
        assertFalse(Files.exists(companionDir), "the B-scan companions must be deleted");
        assertTrue(Files.isDirectory(artifacts), "the artifact root itself stays");
        assertTrue(Files.isDirectory(bscans), "the B-scan root itself stays");

        // Clinical data is never deleted by the sweep: the value stays, only
        // the pointer at the vanished job and file is cleared.
        assertEquals(1, count("SELECT count(*) FROM item_data WHERE item_data_id = ? AND value = 'v' "
                + "AND source_retinal_job_id IS NULL AND source_ingest_item_id IS NULL", itemData));

        String audit = sweepAudit();
        assertNotNull(audit, "the pass must leave an audit row");
        assertTrue(audit.contains("1 dismissed file"), audit);
        assertTrue(audit.contains("3 analysis jobs"), audit);
        assertTrue(audit.contains("1 results"), audit);
    }

    /** Rows stranded by the old order: file deleted, row refused by the job's foreign key. */
    @Test
    void aRowWhoseFileTheOldSweepAlreadyDeleted_isRemovedWithItsJobs() throws Exception {
        Path e2e = e2eFile();
        long item = item("DISMISSED", 90, e2e, null, 0);
        long done = job(item, e2e, "fluid", "done");
        Path artifactDir = result(done, artifacts.resolve(UUID.randomUUID().toString()));
        Path companionDir = companions(e2e);
        Files.delete(e2e);

        assertEquals(1, service().garbageCollect());

        assertFalse(itemExists(item));
        assertEquals(0, jobsOf(item));
        assertEquals(0, resultsOf(done));
        assertFalse(Files.exists(artifactDir));
        assertFalse(Files.exists(companionDir));
    }

    /* ------------------------------------------------------------------ */
    /* what must survive                                                   */
    /* ------------------------------------------------------------------ */

    @Test
    void aScanWithAnAnalysisStillRunning_isKeptAndItsWaitingJobsCancelled() throws Exception {
        Path e2e = e2eFile();
        long item = item("DISMISSED", 45, e2e, null, 0);
        long running = job(item, e2e, "fluid", "segmenting");
        long waiting = job(item, e2e, "layers", "queued");
        Path companionDir = companions(e2e);

        assertEquals(0, service().garbageCollect());

        assertTrue(itemExists(item), "a running analysis is still writing; the next night takes it");
        assertEquals("segmenting", jobStatus(running));
        assertEquals("cancelled", jobStatus(waiting), "nothing new may start for a dismissed scan");
        assertTrue(Files.exists(e2e));
        assertTrue(Files.exists(companionDir));
        assertNull(sweepAudit(), "nothing removed, nothing to audit");
    }

    @Test
    void anAnalysedScanDismissedInsideTheWindow_isKept() throws Exception {
        Path e2e = e2eFile();
        long item = item("DISMISSED", 3, e2e, null, 0);
        long done = job(item, e2e, "fluid", "done");
        Path artifactDir = result(done, artifacts.resolve(UUID.randomUUID().toString()));

        assertEquals(0, service().garbageCollect());

        assertTrue(itemExists(item));
        assertEquals(1, resultsOf(done));
        assertTrue(Files.exists(e2e));
        assertTrue(Files.exists(artifactDir));
    }

    /**
     * A two-volume .e2e is one file and two ingest rows (scan 0 and scan 1).
     * Dismissing one volume must not take the other's file or companions.
     */
    @Test
    void aFileAndCompanionsSharedWithAnotherScan_areKept() throws Exception {
        Path e2e = e2eFile();
        long dismissed = item("DISMISSED", 45, e2e, null, 0);
        long bound = item("BOUND", 45, e2e, null, 1);
        long doneDismissed = job(dismissed, e2e, "fluid", "done");
        Path dismissedArtifacts = result(doneDismissed, artifacts.resolve(UUID.randomUUID().toString()));
        long doneBound = job(bound, e2e, "fluid", "done");
        Path boundArtifacts = result(doneBound, artifacts.resolve(UUID.randomUUID().toString()));
        Path companionDir = companions(e2e);

        assertEquals(1, service().garbageCollect());

        assertFalse(itemExists(dismissed));
        assertFalse(Files.exists(dismissedArtifacts), "the dismissed volume's own results go");
        assertTrue(itemExists(bound));
        assertEquals(1, resultsOf(doneBound));
        assertTrue(Files.exists(e2e), "the other volume still needs the file");
        assertTrue(Files.exists(companionDir.resolve("scan-1").resolve("bscan.dcm")),
                "the other volume still needs the companions");
        assertTrue(Files.exists(boundArtifacts));
    }

    /** A result pointing outside the artifact store is a discrepancy to keep visible, not to delete. */
    @Test
    void anArtifactDirectoryOutsideItsRoot_isNeitherDeletedNorForgotten() throws Exception {
        Path e2e = e2eFile();
        long item = item("DISMISSED", 45, e2e, null, 0);
        long done = job(item, e2e, "fluid", "done");
        Path stray = result(done, elsewhere.resolve("not-ours"));

        assertEquals(0, service().garbageCollect());

        assertTrue(Files.exists(stray.resolve("fluidseg.npz")), "nothing outside the root is deleted");
        assertTrue(itemExists(item), "the row stays so the misconfiguration is visible");
        assertEquals(1, resultsOf(done));
        assertTrue(Files.exists(e2e), "and the scan itself is kept with its row");
    }

    /** An artifact path that is the root itself must never be taken as a job's directory. */
    @Test
    void anArtifactDirectoryThatIsTheRootItself_isRefused() throws Exception {
        Path e2e = e2eFile();
        long item = item("DISMISSED", 45, e2e, null, 0);
        long done = job(item, e2e, "fluid", "done");
        Path other = artifacts.resolve("someone-elses-job");
        Files.createDirectories(other);
        Files.writeString(other.resolve("keep.npz"), "npz");
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO retinal_inference_result (job_id, task, output_payload, bscan_masks_dir) "
                             + "VALUES (?, 'fluid', '{}'::jsonb, ?)")) {
            ps.setLong(1, done);
            ps.setString(2, artifacts.toString());
            ps.executeUpdate();
        }

        assertEquals(0, service().garbageCollect());

        assertTrue(Files.exists(other.resolve("keep.npz")));
        assertTrue(itemExists(item));
    }

    /* ------------------------------------------------------------------ */
    /* dismissing stops new work; restoring still works                    */
    /* ------------------------------------------------------------------ */

    @Test
    void dismissCancelsTheJobsNotYetRunning_andARestoreKeepsThem() throws Exception {
        Path e2e = e2eFile();
        long item = item("UNBOUND", 0, e2e, null, 0);
        try (Connection c = DATA_SOURCE.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("UPDATE ingest_item SET bound_at = NULL WHERE ingest_item_id = " + item);
        }
        long queued = job(item, e2e, "fluid", "queued");
        long pending = job(item, e2e, "layers", "remote_pending");
        long done = job(item, e2e, "ga", "done");
        IngestBindService binds = new IngestBindService(DATA_SOURCE);

        assertEquals(IngestBindService.Result.OK,
                binds.dismiss(item, "test exposure", IngestBindService.Actor.system()));

        assertEquals("cancelled", jobStatus(queued), "a dismissed scan must not be analysed");
        assertEquals("cancelled", jobStatus(pending));
        assertEquals("done", jobStatus(done), "a finished result is left as it is");

        assertEquals(IngestBindService.Result.OK, binds.restore(item, IngestBindService.Actor.system()));
        assertEquals(1, count("SELECT count(*) FROM ingest_item WHERE ingest_item_id = ? AND status = 'UNBOUND'",
                item));
        assertEquals(3, jobsOf(item), "a restore keeps the jobs for a later bind to revive or attach");
        assertEquals("done", jobStatus(done));
    }

    /** A dismiss the state check refuses must not cancel the jobs of a filed scan. */
    @Test
    void aRefusedDismiss_leavesAFiledScansJobsAlone() throws Exception {
        Path e2e = e2eFile();
        long item = item("BOUND", 1, e2e, null, 0);
        long queued = job(item, e2e, "fluid", "queued");

        assertEquals(IngestBindService.Result.WRONG_STATE, new IngestBindService(DATA_SOURCE)
                .dismiss(item, "wrong patient", IngestBindService.Actor.system()));

        assertEquals("queued", jobStatus(queued));
    }

    /* ------------------------------------------------------------------ */
    /* the schema the sweep was written against                            */
    /* ------------------------------------------------------------------ */

    /**
     * The sweep deletes a job's dependants by name. A new table that points
     * at a job or an ingest row would make its delete fail again — this
     * fails first, so whoever adds one also teaches the sweep about it.
     */
    @Test
    void everyForeignKeyIntoTheChainIsOneTheSweepHandles() throws Exception {
        Set<String> found = new TreeSet<>();
        try (Connection c = DATA_SOURCE.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT src.relname || '.' || a.attname || ' -> ' || dst.relname "
                             + "  FROM pg_constraint k "
                             + "  JOIN pg_class src ON src.oid = k.conrelid "
                             + "  JOIN pg_class dst ON dst.oid = k.confrelid "
                             + "  JOIN pg_attribute a ON a.attrelid = k.conrelid AND a.attnum = ANY (k.conkey) "
                             + " WHERE k.contype = 'f' "
                             + "   AND dst.relname IN ('retinal_inference_job', 'ingest_item')")) {
            while (rs.next()) found.add(rs.getString(1));
        }
        assertEquals(new TreeSet<>(Set.of(
                        "item_data.source_ingest_item_id -> ingest_item",
                        "item_data.source_retinal_job_id -> retinal_inference_job",
                        "retinal_inference_correction.job_id -> retinal_inference_job",
                        "retinal_inference_job.ingest_item_id -> ingest_item",
                        "retinal_inference_result.job_id -> retinal_inference_job")),
                found);
    }
}
