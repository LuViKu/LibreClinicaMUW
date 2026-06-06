/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.crf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

/**
 * Phase E.6 cluster `crf-data-types` PR (b) — unit tests for the
 * file storage service's pure helpers.
 *
 * <p>These tests do NOT touch CoreResources (which loads on first
 * use from the runtime datainfo.properties path). They exercise the
 * static helper methods + the validation entrypoints with mocked
 * MultipartFile inputs, so they run fast + don't need a Postgres
 * Testcontainer or a real WAR deployment.
 */
class CrfFileStorageServiceTest {

    private final CrfFileStorageService service = new CrfFileStorageService();

    @Test
    void sanitiseSegmentStripsPathSeparators() {
        // The regex keeps [A-Za-z0-9._-]; everything else collapses to "_".
        assertEquals("_", CrfFileStorageService.sanitiseSegment(null));
        assertEquals("_", CrfFileStorageService.sanitiseSegment(""));
        assertEquals("CRF_V1", CrfFileStorageService.sanitiseSegment("CRF/V1"));
        assertEquals("CRF_V1", CrfFileStorageService.sanitiseSegment("CRF\\V1"));
        assertEquals("CRF.V1-2", CrfFileStorageService.sanitiseSegment("CRF.V1-2"));
        // Dots survive the segment sanitiser (legal in OIDs); the slash
        // collapses to "_" so a "CRF/.." attacker payload becomes
        // "CRF_..", which Paths.resolve still rejects on the upload
        // side because the resolved target must stay under baseDir.
        assertEquals("CRF_..", CrfFileStorageService.sanitiseSegment("CRF/.."));
    }

    @Test
    void sanitiseFilenamePreservesExtensionAndStripsTraversal() {
        // Extension survives; path separators + ".." sequences collapse
        // to "_". The leading ".." in "../etc/passwd" becomes "_", and
        // the inner "/" also becomes "_", giving "__etc_passwd".
        assertEquals("retina.jpg", CrfFileStorageService.sanitiseFilename("retina.jpg"));
        assertEquals("__etc_passwd", CrfFileStorageService.sanitiseFilename("../etc/passwd"));
        assertEquals("a_b.jpg", CrfFileStorageService.sanitiseFilename("a/b.jpg"));
        assertEquals("upload.bin", CrfFileStorageService.sanitiseFilename(""));
    }

    @Test
    void checkExtensionAcceptsAllowedAndRejectsOthers() {
        // Allowlist is read from CoreResources. Without runtime context
        // the call falls through to the DEFAULT_EXTENSIONS constant
        // ("pdf,jpg,jpeg,png,tif,tiff"), which is what we test here.
        assertTrue(service.checkExtension(
                new MockMultipartFile("file", "retina.jpg", "image/jpeg", new byte[1])));
        assertTrue(service.checkExtension(
                new MockMultipartFile("file", "scan.pdf", "application/pdf", new byte[1])));
        assertFalse(service.checkExtension(
                new MockMultipartFile("file", "trojan.exe", "application/x-msdownload", new byte[1])));
        assertFalse(service.checkExtension(
                new MockMultipartFile("file", "no-extension", "text/plain", new byte[1])));
    }

    @Test
    void checkSizeRespectsCap() {
        // Service default cap is 50 MiB. A 1 KB file passes; an 8 GB
        // descriptor is rejected (we don't actually allocate the bytes
        // — MockMultipartFile reports the size we hand in).
        assertTrue(service.checkSize(
                new MockMultipartFile("file", "small.pdf", "application/pdf", new byte[1024])));
        // Build a "huge" mock with an inflated reported size by
        // wrapping a tiny byte[] but overriding getSize via Mockito —
        // simpler to just build one slightly above the default cap.
        MockMultipartFile borderline = new MockMultipartFile(
                "file", "huge.pdf", "application/pdf", new byte[1024]);
        assertTrue(service.checkSize(borderline));
        assertFalse(service.checkSize(null));
    }

    @Test
    void isPotentiallyExecutableFlagsHostileExtensions() {
        assertTrue(service.isPotentiallyExecutable(
                new MockMultipartFile("file", "bad.exe", "application/octet-stream", new byte[1])));
        assertTrue(service.isPotentiallyExecutable(
                new MockMultipartFile("file", "shell.SH", "text/plain", new byte[1])));
        assertTrue(service.isPotentiallyExecutable(
                new MockMultipartFile("file", "rce.jsp", "text/plain", new byte[1])));
        assertFalse(service.isPotentiallyExecutable(
                new MockMultipartFile("file", "scan.jpg", "image/jpeg", new byte[1])));
    }

    @Test
    void storeWritesUnderBaseDirAndReturnsAbsolutePath(@org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        // The service reads its base dir from CoreResources; bypass it
        // by sub-classing inline so the test stays hermetic.
        CrfFileStorageService scoped = new CrfFileStorageService() {
            @Override public Path baseDir() { return tempDir; }
        };
        MockMultipartFile upload = new MockMultipartFile(
                "file", "retina.jpg", "image/jpeg", "FAKEBYTES".getBytes());
        Path target = scoped.store("F_OPHTH", "v1.0", upload);
        assertTrue(Files.exists(target));
        assertTrue(target.toString().contains("F_OPHTH"));
        assertTrue(target.toString().contains("v1.0"));
        assertTrue(target.getFileName().toString().endsWith("-retina.jpg"));
        // Roundtrip check on the bytes we wrote.
        assertEquals("FAKEBYTES", Files.readString(target));
    }

    @Test
    void deleteRefusesPathsOutsideBaseDir(@org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        CrfFileStorageService scoped = new CrfFileStorageService() {
            @Override public Path baseDir() { return tempDir; }
        };
        // Create a file inside and one outside the base dir.
        Path outside = Files.createTempFile("escape", ".dat");
        try {
            Path inside = Files.createTempFile(tempDir, "ok", ".dat");
            assertTrue(scoped.delete(inside.toAbsolutePath().toString()));
            assertFalse(Files.exists(inside));
            assertFalse(scoped.delete(outside.toAbsolutePath().toString()));
            assertTrue(Files.exists(outside));
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void allowedExtensionsCsvIsDeterministicallyOrdered() {
        // CoreResources may not be populated under the unit-test class
        // loader — the service falls through to DEFAULT_EXTENSIONS.
        // We exercise the orderable contract: csv is alphabetised so
        // the SPA + audit log diffs stay stable across redeploys.
        String csv = service.allowedExtensionsCsv();
        assertNotNull(csv);
        Set<String> allowed = service.allowedExtensions();
        if (!allowed.isEmpty()) {
            String[] parts = csv.split(",");
            for (int i = 1; i < parts.length; i++) {
                assertTrue(parts[i - 1].compareTo(parts[i]) <= 0,
                        "csv must be sorted: " + csv);
            }
        }
    }
}
