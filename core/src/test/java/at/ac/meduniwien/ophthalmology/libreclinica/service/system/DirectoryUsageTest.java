/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.system;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * DR-033 — the storage scan's two measurements. What matters: the sum is
 * the sum (nested folders included), a missing store says so instead of
 * reading as empty, a budget stops the walk and marks the result as a
 * floor, and a store inside another store is not counted twice.
 */
public class DirectoryUsageTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static void write(Path file, int bytes) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[bytes]);
    }

    @Test
    public void sumsRegularFilesInNestedFolders() throws Exception {
        Path root = tmp.newFolder("ingest").toPath();
        write(root.resolve("dicom/a.dcm"), 1000);
        write(root.resolve("dicom/b.dcm"), 500);
        write(root.resolve("image/2026/09/c.jpg"), 250);

        DirectoryUsage.Usage u = DirectoryUsage.measure(root, 1_000_000, Duration.ofMinutes(1));

        assertTrue(u.present());
        assertTrue(u.complete());
        assertEquals(1750L, u.bytes());
        assertEquals(3L, u.files());
    }

    @Test
    public void aMissingStoreIsReportedAbsentNotEmpty() {
        Path nowhere = tmp.getRoot().toPath().resolve("never-created");

        DirectoryUsage.Usage u = DirectoryUsage.measure(nowhere, 1_000_000, Duration.ofMinutes(1));

        assertFalse(u.present());
        assertEquals(0L, u.bytes());
    }

    @Test
    public void theFileBudgetStopsTheWalkAndMarksTheTotalAsAFloor() throws Exception {
        Path root = tmp.newFolder("big").toPath();
        for (int i = 0; i < 10; i++) write(root.resolve("f" + i), 10);

        DirectoryUsage.Usage u = DirectoryUsage.measure(root, 4, Duration.ofMinutes(1));

        assertTrue(u.present());
        assertFalse(u.complete());
        assertEquals(4L, u.files());
        assertEquals(40L, u.bytes());
    }

    @Test
    public void theTimeBudgetStopsTheWalkToo() throws Exception {
        Path root = tmp.newFolder("slow").toPath();
        write(root.resolve("a"), 1);

        DirectoryUsage.Usage u = DirectoryUsage.measure(root, 1_000_000, Duration.ofSeconds(-1));   // deadline already passed

        assertFalse(u.complete());
    }

    @Test
    public void aStoreInsideAnotherIsNotCountedTwice() {
        List<Path> paths = List.of(
                Path.of("/var/lib/libreclinica/retinal-artifacts"),
                Path.of("/usr/local/tomcat/libreclinica.data/"),
                Path.of("/var/lib/libreclinica/retinal-artifacts/bscans"),
                Path.of("/usr/local/tomcat/libreclinica.data/attached_files"),
                Path.of("/var/lib/libreclinica/ingest"),
                Path.of("/var/lib/libreclinica/ingest"));

        assertEquals(List.of(0, 1, 4), DirectoryUsage.outermost(paths));
    }

    @Test
    public void siblingsWithACommonPrefixAreBothKept() {
        // "/data/ingest-old" does not lie inside "/data/ingest".
        List<Path> paths = List.of(Path.of("/data/ingest"), Path.of("/data/ingest-old"));

        assertEquals(List.of(0, 1), DirectoryUsage.outermost(paths));
    }

    @Test
    public void theFilesystemUnderAStoreIsReadable() throws Exception {
        DirectoryUsage.FileSystemInfo fs = DirectoryUsage.fileSystemOf(tmp.getRoot().toPath());

        assertNotNull(fs);
        assertTrue(fs.totalBytes() > 0);
        assertTrue(fs.usableBytes() >= 0 && fs.usableBytes() <= fs.totalBytes());
    }

    @Test
    public void onlyContainerLayerTypesAreFlagged() {
        assertTrue(DirectoryUsage.isContainerLayer("overlay"));
        assertTrue(DirectoryUsage.isContainerLayer("TMPFS"));
        assertFalse(DirectoryUsage.isContainerLayer("ext4"));
        assertFalse(DirectoryUsage.isContainerLayer("xfs"));
        assertFalse(DirectoryUsage.isContainerLayer(null));
    }
}
