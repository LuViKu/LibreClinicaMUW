/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.ingest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import at.ac.meduniwien.ophthalmology.libreclinica.dao.core.CoreResources;

/**
 * P3.0 — where an ingested file goes, and what may be read back.
 *
 * <p>The confinement half is the point. The stored path comes out of the
 * database and used to be handed straight to the filesystem, so every reader
 * was one bad row away from serving an arbitrary file — and the row can be
 * written by an ingest path that does not authenticate.
 */
public class IngestArtifactStoreTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Properties datainfo;
    private Properties saved;
    private Path root;
    private Path legacyDicom;
    private final IngestArtifactStore store = new IngestArtifactStore();

    @Before
    public void overrideRoots() throws Exception {
        root = tmp.newFolder("ingest").toPath();
        legacyDicom = tmp.newFolder("dicom-ingest").toPath();

        java.lang.reflect.Field f = CoreResources.class.getDeclaredField("DATAINFO");
        f.setAccessible(true);
        datainfo = (Properties) f.get(null);
        if (datainfo == null) {
            datainfo = new Properties();
            f.set(null, datainfo);
        }
        saved = new Properties();
        saved.putAll(datainfo);
        datainfo.setProperty(IngestArtifactStore.CONFIG_KEY_STORE_PATH, root.toString());
        datainfo.setProperty(IngestArtifactStore.LEGACY_KEY_DICOM, legacyDicom.toString());
        datainfo.setProperty(IngestArtifactStore.LEGACY_KEY_E2E, tmp.newFolder("e2e").toString());
    }

    @After
    public void restore() {
        datainfo.clear();
        datainfo.putAll(saved);
    }

    private static ByteArrayInputStream bytes(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    /* ---------------- storing ---------------- */

    @Test
    public void storesUnderTheKindsOwnDirectory() throws Exception {
        var stored = store.store(IngestArtifactStore.Kind.DICOM, bytes("hello"), "scan.dcm");
        assertTrue(Files.isRegularFile(stored.path()));
        assertEquals(root.resolve("dicom"), stored.path().getParent());
    }

    @Test
    public void reportsTheDigestAndSizeItWroteRatherThanRereadingTheFile() throws Exception {
        var stored = store.store(IngestArtifactStore.Kind.IMAGE, bytes("hello"), "a.jpg");
        // SHA-256 of "hello".
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
                stored.sha256());
        assertEquals(5L, stored.byteSize());
        assertEquals(5L, Files.size(stored.path()));
    }

    /**
     * The operator names the file on a phone. That name is untrusted text and
     * must never become part of a path — only its extension survives.
     */
    @Test
    public void theStoredNameIsNotTheSuppliedName() throws Exception {
        var stored = store.store(IngestArtifactStore.Kind.IMAGE, bytes("x"),
                "../../etc/passwd.jpg");
        assertEquals(root.resolve("image"), stored.path().getParent());
        assertFalse(stored.path().getFileName().toString().contains("passwd"));
        assertTrue(stored.path().getFileName().toString().endsWith(".jpg"));
    }

    @Test
    public void anAbsurdExtensionIsIgnored() throws Exception {
        var stored = store.store(IngestArtifactStore.Kind.DICOM, bytes("x"),
                "scan.thisisnotanextension");
        assertTrue(stored.path().getFileName().toString().endsWith(".dcm"));
    }

    @Test
    public void aMissingNameFallsBackToTheKindsExtension() throws Exception {
        var stored = store.store(IngestArtifactStore.Kind.E2E, bytes("x"), null);
        assertTrue(stored.path().getFileName().toString().endsWith(".e2e"));
    }

    /* ---------------- reading back ---------------- */

    @Test
    public void aFileInTheStoreResolves() throws Exception {
        var stored = store.store(IngestArtifactStore.Kind.IMAGE, bytes("x"), "a.png");
        assertTrue(store.resolveConfined(stored.path().toString()).isPresent());
    }

    /** Paths recorded before the unified root keep working; nothing moved on disk. */
    @Test
    public void aFileInTheLegacyRootStillResolves() throws Exception {
        Path legacy = legacyDicom.resolve("old.dcm");
        Files.writeString(legacy, "x");
        assertTrue(store.resolveConfined(legacy.toString()).isPresent());
    }

    @Test
    public void aFileOutsideEveryRootIsRefused() throws Exception {
        Path stray = tmp.newFile("elsewhere.bin").toPath();
        Files.writeString(stray, "x");
        assertTrue(store.resolveConfined(stray.toString()).isEmpty());
    }

    /** The classic shape: a path that starts inside the root and climbs out. */
    @Test
    public void aTraversalOutOfTheStoreIsRefused() throws Exception {
        Path stray = tmp.newFile("escaped.bin").toPath();
        Files.writeString(stray, "x");
        String traversal = root.resolve("..").resolve(stray.getFileName()).toString();
        assertTrue(store.resolveConfined(traversal).isEmpty());
    }

    @Test
    public void aDirectoryIsNotAFile() {
        assertTrue(store.resolveConfined(root.toString()).isEmpty());
    }

    @Test
    public void nothingResolvesToNothing() {
        assertTrue(store.resolveConfined(null).isEmpty());
        assertTrue(store.resolveConfined("   ").isEmpty());
        assertTrue(store.resolveConfined(root.resolve("absent.dcm").toString()).isEmpty());
    }
}
