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

    private static final int STUDY_SUBJECT_ID = 1;
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
    }

    @AfterEach
    void cleanRows() throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM ingest_item WHERE original_filename LIKE ?")) {
            ps.setString(1, MARKER + "%");
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
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        BundleExportWriter.Result r = writeBundle(true, sink);

        JsonNode m = JSON.readTree(unzip(sink.toByteArray()).get("manifest.json"));
        assertEquals("ai-withheld", m.get("masking").asText());
        assertTrue(r.omitted().stream().anyMatch(o -> o.ref().startsWith("ai/")));
        // The raw scan is not AI output: the physician still gets the image.
        assertTrue(unzip(sink.toByteArray()).keySet().stream()
                        .anyMatch(k -> k.startsWith("acquisitions/")),
                "blinding hides the AI's reading, not the patient's eye");
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

    @Test
    void aSubjectWithNoFilesStillGetsACasebook() throws Exception {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        writeBundle(false, sink);
        Map<String, byte[]> entries = unzip(sink.toByteArray());
        assertTrue(entries.containsKey("casebook.xml"));
        assertTrue(entries.containsKey("manifest.json"));
    }
}
