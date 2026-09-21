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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.ac.meduniwien.ophthalmology.libreclinica.dao.core.CoreResources;
import at.ac.meduniwien.ophthalmology.libreclinica.service.export.BundleExportWriter;

/**
 * P3.7 — a subject's whole record, files included.
 *
 * <p>Before this, "export the subject" produced text only: the OCT volumes,
 * the fundus photographs and the files attached to CRF items never left the
 * server, and a FILE item exported as a <em>server path</em> — useless to the
 * recipient and a disclosure of the filesystem layout. For a study whose
 * endpoint is imaging, that is not an export.
 *
 * <p>What is pinned here is mostly what the bundle refuses to do. A path in a
 * row that points outside the store is not read, and the manifest says a file
 * is missing rather than the bundle quietly containing one fewer — a recipient
 * must be able to tell "this subject had no scan" from "the scan is gone".
 */
@SuppressWarnings("null")
class SubjectExportBundleDatabaseIT extends AbstractApiControllerDatabaseIT {

    @TempDir
    static Path STORE_ROOT;

    /** The retinal pipeline writes elsewhere; the bundle confines each separately. */
    @TempDir
    static Path ARTIFACT_ROOT;

    private static final int STUDY_SUBJECT_ID = 1;
    /** event_crf 1 belongs to M-001 — how a job is tied back to the subject. */
    private static final int EVENT_CRF_ID = 1;
    private static final long JOB_ID = 990001L;
    private static final String MARKER = "bundle-it-";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static java.util.Properties SAVED;

    @BeforeAll
    static void pointTheStoreAtATempDir() throws Exception {
        java.lang.reflect.Field f = CoreResources.class.getDeclaredField("DATAINFO");
        f.setAccessible(true);
        java.util.Properties live = (java.util.Properties) f.get(null);
        assertNotNull(live, "DATAINFO must be set by AbstractApiControllerDatabaseIT");
        SAVED = new java.util.Properties();
        SAVED.putAll(live);
        live.setProperty("core.ingest.storePath", STORE_ROOT.toString());
        live.setProperty("core.retinalInference.artifactStorePath", ARTIFACT_ROOT.toString());
    }

    @AfterEach
    void cleanRows() throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM ingest_item WHERE original_filename LIKE ?")) {
            ps.setString(1, MARKER + "%");
            ps.executeUpdate();
        }
        exec("DELETE FROM retinal_inference_result WHERE job_id = " + JOB_ID);
        exec("DELETE FROM retinal_inference_job WHERE job_id = " + JOB_ID);
    }

    private void exec(String sql) throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }

    /** A file on disk inside the store, filed against the subject. */
    private long seedBoundFile(String kind, byte[] body) throws Exception {
        Path dir = STORE_ROOT.resolve(kind);
        Files.createDirectories(dir);
        String name = MARKER + System.nanoTime() + (kind.equals("e2e") ? ".e2e" : ".jpg");
        Path file = dir.resolve(name);
        Files.write(file, body);
        return insertRow(kind, file.toString(), name);
    }

    /** A row whose path points outside every known root. */
    private long seedStrayPath(String kind) throws Exception {
        String name = MARKER + System.nanoTime() + ".jpg";
        return insertRow(kind, "/etc/passwd", name);
    }

    private long insertRow(String kind, String storedPath, String name) throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO ingest_item (kind, source_kind, device, stored_path, "
                             + "original_filename, laterality, received_at, status, "
                             + "bound_study_subject_id) "
                             + "VALUES (?, 'upload', 'it-camera', ?, ?, 'OD', now(), 'BOUND', ?) "
                             + "RETURNING ingest_item_id")) {
            ps.setString(1, kind);
            ps.setString(2, storedPath);
            ps.setString(3, name);
            ps.setInt(4, STUDY_SUBJECT_ID);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /**
     * A finished inference job with an artifact directory holding one companion
     * and one mask. Listed rather than assumed by the writer, so the fixture
     * puts real files on disk.
     *
     * @return the directory the masks live in
     */
    private Path seedFinishedJob() throws Exception {
        Path dir = ARTIFACT_ROOT.resolve("job-" + JOB_ID);
        Files.createDirectories(dir);
        // A rendering of the eye — kept even under blinding.
        Files.write(dir.resolve("fundus.png"), "fundus".getBytes(StandardCharsets.UTF_8));
        // The model's reading of it — withheld under blinding.
        Files.write(dir.resolve("irf_mask.png"), "mask".getBytes(StandardCharsets.UTF_8));

        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO retinal_inference_job (job_id, event_crf_id, task, e2e_path, "
                             + "eye_laterality, status, enqueued_at, completed_at, model_version) "
                             + "VALUES (?, ?, 'fluid', '/dev/null', 'OD', 'done', now(), now(), 'v1')")) {
            ps.setLong(1, JOB_ID);
            ps.setInt(2, EVENT_CRF_ID);
            ps.executeUpdate();
        }
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO retinal_inference_result (job_id, task, output_payload, "
                             + "bscan_masks_dir) VALUES (?, 'fluid', '{}'::jsonb, ?)")) {
            ps.setLong(1, JOB_ID);
            ps.setString(2, dir.toString());
            ps.executeUpdate();
        }
        return dir;
    }

    private BundleExportWriter.Result writeBundle(boolean maskAi, ByteArrayOutputStream sink)
            throws Exception {
        return BundleExportWriter.write(sink, DATA_SOURCE, STUDY_SUBJECT_ID, "M-001", "S_DEFAULTS1",
                "<ODM/>".getBytes(StandardCharsets.UTF_8),
                "label,value\n".getBytes(StandardCharsets.UTF_8),
                new BundleExportWriter.Policy(maskAi), "root", false);
    }

    private Map<String, byte[]> unzip(byte[] zip) throws Exception {
        Map<String, byte[]> out = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new java.io.ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                out.put(e.getName(), zis.readAllBytes());
            }
        }
        return out;
    }

    /* ---------------- what the bundle contains ---------------- */

    @Test
    void theCasebookAndEveryFileAreInTheZip() throws Exception {
        long id = seedBoundFile("image", "fundus-bytes".getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        writeBundle(false, sink);

        Map<String, byte[]> entries = unzip(sink.toByteArray());
        assertTrue(entries.containsKey("casebook.xml"));
        assertTrue(entries.containsKey("casebook.csv"));
        assertTrue(entries.containsKey("manifest.json"));
        assertTrue(entries.containsKey("acquisitions/" + id + ".jpg"),
                "the subject's image has to be in the bundle — that is the point");
        assertEquals("fundus-bytes",
                new String(entries.get("acquisitions/" + id + ".jpg"), StandardCharsets.UTF_8));
    }

    /**
     * The manifest is written last so a truncated download is detectable: a
     * bundle without one is an incomplete bundle.
     */
    @Test
    void theManifestIsTheLastEntry() throws Exception {
        seedBoundFile("image", "x".getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        writeBundle(false, sink);

        String last = null;
        try (ZipInputStream zis = new ZipInputStream(
                new java.io.ByteArrayInputStream(sink.toByteArray()))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) last = e.getName();
        }
        assertEquals("manifest.json", last);
    }

    @Test
    void theManifestDescribesWhatIsInside() throws Exception {
        long id = seedBoundFile("e2e", new byte[2048]);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        writeBundle(false, sink);

        JsonNode m = JSON.readTree(unzip(sink.toByteArray()).get("manifest.json"));
        assertEquals(1, m.get("schemaVersion").asInt());
        assertEquals("M-001", m.get("subject").asText());
        assertEquals("none", m.get("masking").asText());
        JsonNode acq = m.get("acquisitions");
        assertTrue(acq.isArray() && acq.size() >= 1);
        boolean found = false;
        for (JsonNode a : acq) {
            if (a.get("ingestItemId").asLong() == id) {
                found = true;
                assertEquals("e2e", a.get("kind").asText());
                assertEquals(2048, a.get("byteSize").asLong());
                assertEquals("acquisitions/" + id + ".e2e", a.get("path").asText());
            }
        }
        assertTrue(found, "every file written has to appear in the manifest");
    }

    /**
     * The scan without what the platform made of it is half an export. For a
     * study whose endpoint is a segmented volume, the masks <em>are</em> the
     * result — a recipient who gets only the raw .e2e cannot check the number
     * in the CRF against anything.
     */
    @Test
    void theInferenceOutputTravelsWithTheScan() throws Exception {
        seedFinishedJob();
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        writeBundle(false, sink);

        Map<String, byte[]> entries = unzip(sink.toByteArray());
        assertTrue(entries.containsKey("inference/" + JOB_ID + "/irf_mask.png"),
                "the mask the model produced has to be in the bundle");
        assertTrue(entries.containsKey("inference/" + JOB_ID + "/fundus.png"),
                "so does the companion rendered from the volume");
        assertEquals("mask",
                new String(entries.get("inference/" + JOB_ID + "/irf_mask.png"), StandardCharsets.UTF_8));

        JsonNode inference = JSON.readTree(entries.get("manifest.json")).get("inference");
        assertTrue(inference.isArray() && inference.size() >= 2,
                "and each artifact has to be named in the manifest");
    }

    /**
     * Blinding is about the algorithm, not the patient. A masked export drops
     * the model's reading and keeps the rendering of the eye — the same split
     * the on-screen artifact stream already makes.
     */
    @Test
    void aBlindedExportWithholdsTheMasksAndKeepsTheCompanions() throws Exception {
        seedFinishedJob();
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        BundleExportWriter.Result r = writeBundle(true, sink);

        Map<String, byte[]> entries = unzip(sink.toByteArray());
        assertFalse(entries.containsKey("inference/" + JOB_ID + "/irf_mask.png"),
                "the model's answer must not reach a blinded recipient");
        assertTrue(entries.containsKey("inference/" + JOB_ID + "/fundus.png"),
                "the eye itself is not AI output");
        assertTrue(r.omitted().stream().anyMatch(
                        o -> o.ref().equals("retinal_job/" + JOB_ID + "/irf_mask.png")),
                "and what was withheld has to be named, or it cannot be asked for");
    }

    /* ---------------- what it refuses ---------------- */

    /**
     * The stored path comes from a row an unauthenticated ingress can write.
     * Reading it unchecked would turn an export into an arbitrary file read.
     */
    @Test
    void aPathOutsideTheStoreIsNotReadAndIsReported() throws Exception {
        long stray = seedStrayPath("image");
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        BundleExportWriter.Result r = writeBundle(false, sink);

        Map<String, byte[]> entries = unzip(sink.toByteArray());
        assertFalse(entries.keySet().stream().anyMatch(k -> k.contains("passwd")),
                "nothing outside the store may reach the zip");
        assertTrue(entries.keySet().stream().noneMatch(k -> k.startsWith("acquisitions/" + stray)),
                "and the row must not produce an entry either");
        // Reported, not silently dropped: a recipient has to be able to tell
        // "no scan" from "the scan is gone".
        assertTrue(r.omitted().stream().anyMatch(o -> o.ref().equals("ingest_item/" + stray)),
                "the omission has to be named");
        JsonNode m = JSON.readTree(entries.get("manifest.json"));
        assertTrue(m.get("omitted").toString().contains(String.valueOf(stray)));
    }

    /**
     * Blinding follows the data out of the platform. A treating clinician who
     * cannot see AI output on screen must not receive it in a zip, and must be
     * told something was withheld — a blinded export that looks complete is
     * worse than one that says so.
     */
    @Test
    void aBlindedExportSaysWhatItWithheld() throws Exception {
        seedBoundFile("image", "x".getBytes(StandardCharsets.UTF_8));
        seedFinishedJob();
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        BundleExportWriter.Result r = writeBundle(true, sink);

        JsonNode m = JSON.readTree(unzip(sink.toByteArray()).get("manifest.json"));
        assertEquals("ai-withheld", m.get("masking").asText());
        assertFalse(r.omitted().isEmpty(), "a blinded export that looks complete is worse than one that says so");
        assertTrue(m.get("omitted").toString().contains("retinal_job/" + JOB_ID),
                "the manifest, not only the return value, has to carry the omissions");
        // The raw scan is not AI output: the physician still gets the image.
        assertTrue(unzip(sink.toByteArray()).keySet().stream()
                        .anyMatch(k -> k.startsWith("acquisitions/")),
                "blinding hides the AI's reading, not the patient's eye");
    }

    /**
     * The artifact path is a database column, and the pipeline that writes it
     * runs on another host. A row pointing outside the artifact store is not
     * followed, for the same reason the acquisition store is confined.
     */
    @Test
    void anArtifactDirectoryOutsideTheStoreIsNotRead() throws Exception {
        Path outside = Files.createTempDirectory("not-the-artifact-store");
        Files.write(outside.resolve("secret_mask.png"), "nope".getBytes(StandardCharsets.UTF_8));
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO retinal_inference_job (job_id, event_crf_id, task, e2e_path, "
                             + "eye_laterality, status, enqueued_at, model_version) "
                             + "VALUES (?, ?, 'fluid', '/dev/null', 'OD', 'done', now(), 'v1')")) {
            ps.setLong(1, JOB_ID);
            ps.setInt(2, EVENT_CRF_ID);
            ps.executeUpdate();
        }
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO retinal_inference_result (job_id, task, output_payload, "
                             + "bscan_masks_dir) VALUES (?, 'fluid', '{}'::jsonb, ?)")) {
            ps.setLong(1, JOB_ID);
            ps.setString(2, outside.toString());
            ps.executeUpdate();
        }

        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        writeBundle(false, sink);
        assertTrue(unzip(sink.toByteArray()).keySet().stream().noneMatch(k -> k.contains("secret_mask")),
                "nothing outside the artifact store may reach the zip");
    }

    /* ---------------- dry run ---------------- */

    /**
     * A subject's OCT volumes can run to gigabytes. Asking what a bundle would
     * contain, and how large, should not cost that.
     */
    @Test
    void aDryRunDescribesTheBundleWithoutWritingIt() throws Exception {
        seedBoundFile("e2e", new byte[4096]);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        BundleExportWriter.Result r = BundleExportWriter.write(
                sink, DATA_SOURCE, STUDY_SUBJECT_ID, "M-001", "S_DEFAULTS1",
                "<ODM/>".getBytes(StandardCharsets.UTF_8), "x".getBytes(StandardCharsets.UTF_8),
                new BundleExportWriter.Policy(false), "root", true);

        assertEquals(0, sink.size(), "a dry run writes nothing");
        assertEquals(0, r.filesWritten());
        JsonNode acq = JSON.valueToTree(r.manifest().get("acquisitions"));
        assertTrue(acq.size() >= 1, "but still says what would be in it");
    }

    /* ---------------- a whole dataset ---------------- */

    /**
     * P3.8 — many subjects, one archive, one manifest.
     *
     * <p>A zip of per-subject zips would have been less work to write and more
     * work for every recipient: unpack twice before finding anything, and no
     * single place to read what the export contains or what it withheld.
     */
    @Test
    void aDatasetBundleGivesEachSubjectAFolderUnderOneManifest() throws Exception {
        long id = seedBoundFile("image", "fundus-bytes".getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream sink = new ByteArrayOutputStream();

        BundleExportWriter.Result r = BundleExportWriter.writeDataset(
                sink, DATA_SOURCE,
                List.of(new BundleExportWriter.DatasetSubject(
                                STUDY_SUBJECT_ID, "M-001",
                                "<ODM/>".getBytes(StandardCharsets.UTF_8),
                                "label,value\n".getBytes(StandardCharsets.UTF_8),
                                new BundleExportWriter.Policy(false)),
                        // A second subject with nothing filed against it: it
                        // still gets its casebook, because "this subject had
                        // no scan" is an answer the recipient needs.
                        new BundleExportWriter.DatasetSubject(
                                2, "M-002",
                                "<ODM/>".getBytes(StandardCharsets.UTF_8),
                                "label,value\n".getBytes(StandardCharsets.UTF_8),
                                new BundleExportWriter.Policy(false))),
                "S_DEFAULTS1", "root", false);

        Map<String, byte[]> entries = unzip(sink.toByteArray());
        assertTrue(entries.containsKey("subjects/M-001/casebook.xml"));
        assertTrue(entries.containsKey("subjects/M-002/casebook.xml"));
        assertTrue(entries.containsKey("subjects/M-001/acquisitions/" + id + ".jpg"),
                "a subject's files sit under that subject's folder");
        assertTrue(entries.containsKey("manifest.json"));
        assertFalse(entries.containsKey("casebook.xml"),
                "there is no dataset-level casebook — each subject has its own");

        JsonNode m = JSON.readTree(entries.get("manifest.json"));
        assertEquals("dataset", m.get("scope").asText());
        JsonNode subjects = m.get("subjects");
        assertEquals(2, subjects.size());
        assertEquals("M-001", subjects.get(0).get("label").asText());
        assertEquals("subjects/M-001/", subjects.get(0).get("path").asText());
        assertEquals("M-002", subjects.get(1).get("label").asText());
        // The manifest's own path for the acquisition has to be the path in
        // the zip, prefix included — a recipient reads one and opens the other.
        assertEquals("subjects/M-001/acquisitions/" + id + ".jpg",
                subjects.get(0).get("acquisitions").get(0).get("path").asText());
        assertEquals(r.filesWritten(), m.get("fileCount").asInt());
    }

    /**
     * One subject's missing file must not cost the rest of the dataset. An
     * export that aborts partway because of one bad row is worse than one that
     * names the row.
     */
    @Test
    void aDatasetBundleReportsOneSubjectsLossAndKeepsGoing() throws Exception {
        long stray = seedStrayPath("image");
        ByteArrayOutputStream sink = new ByteArrayOutputStream();

        BundleExportWriter.Result r = BundleExportWriter.writeDataset(
                sink, DATA_SOURCE,
                List.of(new BundleExportWriter.DatasetSubject(
                                STUDY_SUBJECT_ID, "M-001",
                                "<ODM/>".getBytes(StandardCharsets.UTF_8),
                                "x".getBytes(StandardCharsets.UTF_8),
                                new BundleExportWriter.Policy(false)),
                        new BundleExportWriter.DatasetSubject(
                                2, "M-002",
                                "<ODM/>".getBytes(StandardCharsets.UTF_8),
                                "x".getBytes(StandardCharsets.UTF_8),
                                new BundleExportWriter.Policy(false))),
                "S_DEFAULTS1", "root", false);

        Map<String, byte[]> entries = unzip(sink.toByteArray());
        assertTrue(entries.containsKey("subjects/M-002/casebook.xml"),
                "the subject after the bad row still got its casebook");
        assertTrue(r.omitted().stream().anyMatch(o -> o.ref().equals("ingest_item/" + stray)),
                "and the loss is named");
    }

    /**
     * A label is operator-typed and becomes a folder name. A separator in one
     * must not become a separator in the archive.
     */
    @Test
    void aLabelCannotClimbOutOfTheArchive() throws Exception {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        BundleExportWriter.writeDataset(
                sink, DATA_SOURCE,
                List.of(new BundleExportWriter.DatasetSubject(
                        STUDY_SUBJECT_ID, "../../etc/passwd",
                        "<ODM/>".getBytes(StandardCharsets.UTF_8),
                        "x".getBytes(StandardCharsets.UTF_8),
                        new BundleExportWriter.Policy(false))),
                "S_DEFAULTS1", "root", false);

        Map<String, byte[]> entries = unzip(sink.toByteArray());
        // What matters is that the label contributes no path structure: one
        // folder under subjects/, no segment that means "go up", nothing
        // hidden. The characters themselves are harmless once they cannot
        // separate.
        for (String k : entries.keySet()) {
            if (!k.startsWith("subjects/")) continue;
            String folder = k.substring("subjects/".length(), k.indexOf('/', "subjects/".length()));
            assertFalse(folder.isEmpty(), k);
            assertFalse(folder.equals("..") || folder.equals("."), k);
            assertFalse(folder.startsWith("."), "no hidden folder: " + k);
        }
        assertEquals(3, entries.size(),
                "casebook.xml, casebook.csv and the manifest — and nothing at the root");
        assertTrue(entries.keySet().stream().anyMatch(k -> k.startsWith("subjects/")),
                "the subject is still exported, under a name that is safe");
    }

    @Test
    void aSubjectWithNoFilesStillGetsACasebook() throws Exception {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        writeBundle(false, sink);
        Map<String, byte[]> entries = unzip(sink.toByteArray());
        assertTrue(entries.containsKey("casebook.xml"));
        assertTrue(entries.containsKey("manifest.json"));
    }
}
