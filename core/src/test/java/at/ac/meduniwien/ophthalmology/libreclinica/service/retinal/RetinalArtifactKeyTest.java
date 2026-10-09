/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.retinal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import java.util.UUID;

import org.junit.Test;

/** DR-039 — one key per stored scan; the {@code .e2e} key is what it always was. */
public class RetinalArtifactKeyTest {

    private static final String STRICT_UUID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    /** The rule every caller used before (RetinalJobAccess.e2eUuidFromPath et al.). */
    private static String legacyE2eKey(String path) {
        String base = Path.of(path).getFileName().toString();
        if (base.toLowerCase().endsWith(".e2e")) base = base.substring(0, base.length() - 4);
        return base;
    }

    @Test
    public void e2eKeysAreUnchanged() {
        String[] paths = {
                "/var/lib/libreclinica/e2e-uploads/3f2b8c1e-0d4a-4b7e-9c11-2a6f0e5d9b40.e2e",
                "/var/lib/libreclinica/ingest/e2e/9e7c2a10-5b3d-4c8e-8f21-7d6a4b3c2e10.E2E",
                "/data/uploads/legacy-name.e2e",
                "relative/abc.e2e",
        };
        for (String p : paths) {
            assertEquals(p, legacyE2eKey(p), RetinalArtifactKey.of(p));
        }
    }

    @Test
    public void aDicomGetsADeterministicLowerCaseUuid() {
        String p = "/var/lib/libreclinica/ingest/dicom/5a1c7e2b-1111-4a2b-9c3d-0e4f5a6b7c8d.dcm";
        String key = RetinalArtifactKey.of(p);
        assertTrue(key, key.matches(STRICT_UUID));
        assertEquals("same path, same key", key, RetinalArtifactKey.of(p));
        assertEquals("a normalised path is the same file",
                key, RetinalArtifactKey.of("/var/lib/libreclinica/ingest/./dicom/5a1c7e2b-1111-4a2b-9c3d-0e4f5a6b7c8d.dcm"));
        assertNotEquals("the key is not the file's own name",
                "5a1c7e2b-1111-4a2b-9c3d-0e4f5a6b7c8d", key);
        assertNotEquals(key, RetinalArtifactKey.of("/var/lib/libreclinica/ingest/dicom/other.dcm"));
        // A real UUID, parseable and round-tripping to the same string.
        assertEquals(key, UUID.fromString(key).toString());
    }

    /**
     * A fixed vector, so the rule cannot drift unnoticed, and so the
     * comparison script (deploy/compare-oct-jobs.sh, which recomputes it in
     * Python) has something to agree with.
     */
    @Test
    public void theKeyOfAKnownPathIsFixed() {
        assertEquals("6ff862cf-d3bf-3b45-a879-b5f1daa3e2f9", RetinalArtifactKey.of(
                "/var/lib/libreclinica/ingest/dicom/5a1c7e2b-1111-4a2b-9c3d-0e4f5a6b7c8d.dcm"));
    }

    @Test
    public void aCstoreHandoffPathWithoutExtensionGetsAKey() {
        String key = RetinalArtifactKey.of("/var/lib/dicom-ingest/1.2.840.113619.2.55/IMG0001");
        assertTrue(key, key.matches(STRICT_UUID));
    }

    @Test
    public void noPathNoKey() {
        assertNull(RetinalArtifactKey.of(null));
        assertNull(RetinalArtifactKey.of(""));
        assertNull(RetinalArtifactKey.of("   "));
    }

    @Test
    public void scanLayoutIsTheSidecars() {
        assertEquals("", RetinalArtifactKey.scanSubdir(0));
        assertEquals("", RetinalArtifactKey.scanSubdir(-1));
        assertEquals("scan-1", RetinalArtifactKey.scanSubdir(1));
        assertEquals("scan-3", RetinalArtifactKey.scanSubdir(3));
        Path base = Path.of("/store");
        assertEquals(Path.of("/store/k"), RetinalArtifactKey.scanDir(base, "k", 0));
        assertEquals(Path.of("/store/k/scan-2"), RetinalArtifactKey.scanDir(base, "k", 2));
    }
}
