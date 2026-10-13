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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * DR-041 (amendment b) - the E2E source copy is kept for the retention window and then deleted;
 * the move into the source directory starts the clock; nothing but {@code <uuid>.e2e} regular
 * files is touched. (The audit row is covered by E2eSplitUploadDatabaseIT.)
 */
public class E2eSourceRetentionServiceTest {

    private static final String OLD = "00000000-0000-4000-8000-000000000001.e2e";
    private static final String NEW = "00000000-0000-4000-8000-000000000002.e2e";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Path put(Path dir, String name, Instant modified) throws Exception {
        Path p = dir.resolve(name);
        Files.writeString(p, "x");
        Files.setLastModifiedTime(p, FileTime.from(modified));
        return p;
    }

    @Test
    public void deletesOnlyCopiesOlderThanTheWindow() throws Exception {
        Path root = tmp.newFolder("src").toPath();
        Instant now = Instant.now();
        Path old = put(root, OLD, now.minus(Duration.ofDays(91)));
        Path fresh = put(root, NEW, now.minus(Duration.ofDays(89)));

        int deleted = new E2eSourceRetentionService(null, root, 90).garbageCollect(now);

        assertEquals(1, deleted);
        assertFalse(Files.exists(old));
        assertTrue(Files.exists(fresh));
    }

    @Test
    public void theWindowIsConfigurable() throws Exception {
        Path root = tmp.newFolder("src").toPath();
        Instant now = Instant.now();
        Path ten = put(root, OLD, now.minus(Duration.ofDays(10)));

        assertEquals(0, new E2eSourceRetentionService(null, root, 30).garbageCollect(now));
        assertTrue(Files.exists(ten));
        assertEquals(1, new E2eSourceRetentionService(null, root, 7).garbageCollect(now));
        assertFalse(Files.exists(ten));
    }

    @Test
    public void leavesEverythingElseAlone() throws Exception {
        Path root = tmp.newFolder("src").toPath();
        Instant ancient = Instant.now().minus(Duration.ofDays(1000));
        Path other = put(root, "notes.txt", ancient);
        Path badName = put(root, "x.e2e", ancient);
        Path dicom = put(root, "00000000-0000-4000-8000-000000000003.dcm", ancient);
        Path dir = Files.createDirectory(root.resolve("00000000-0000-4000-8000-000000000004.e2e"));
        Files.setLastModifiedTime(dir, FileTime.from(ancient));

        assertEquals(0, new E2eSourceRetentionService(null, root, 1).garbageCollect());

        assertTrue(Files.exists(other) && Files.exists(badName) && Files.exists(dicom) && Files.isDirectory(dir));
    }

    @Test
    public void doesNotFollowASymbolicLinkOutOfTheDirectory() throws Exception {
        Path root = tmp.newFolder("src").toPath();
        Path outside = put(tmp.newFolder("elsewhere").toPath(), "keep.bin", Instant.now().minus(Duration.ofDays(500)));
        try {
            Files.createSymbolicLink(root.resolve(OLD), outside);
        } catch (UnsupportedOperationException | java.io.IOException noLinks) {
            return; // e.g. Windows without the privilege: nothing to test here
        }

        assertEquals(0, new E2eSourceRetentionService(null, root, 1).garbageCollect());

        assertTrue(Files.exists(outside));
    }

    @Test
    public void aMissingDirectoryIsNothingToDo() throws Exception {
        Path root = tmp.getRoot().toPath().resolve("not-there");
        assertEquals(0, new E2eSourceRetentionService(null, root, 1).garbageCollect());
    }

    @Test
    public void movingInStartsTheClockAndRemovesTheStagedFile() throws Exception {
        Path staging = tmp.newFolder("staging").toPath();
        Path root = tmp.getRoot().toPath().resolve("source-not-yet-there");
        Path staged = put(staging, OLD, Instant.now().minus(Duration.ofDays(400)));

        Path moved = E2eSourceRetentionService.moveIn(staged, root);

        assertNotNull(moved);
        assertFalse(Files.exists(staged));
        assertTrue(Files.exists(moved));
        assertEquals(root.resolve(OLD), moved);
        // an old staged file is not instantly expired: the window starts at the move
        assertEquals(0, new E2eSourceRetentionService(null, root, 90).garbageCollect());
        assertTrue(Files.exists(moved));
    }

    @Test
    public void aFailedMoveLeavesTheStagedFileAndReturnsNull() throws Exception {
        Path staging = tmp.newFolder("staging").toPath();
        Path staged = put(staging, OLD, Instant.now());
        Path blocker = put(tmp.newFolder("blocked").toPath(), "file", Instant.now());
        // a root below a regular file cannot be created
        assertNull(E2eSourceRetentionService.moveIn(staged, blocker.resolve("sub")));
        assertTrue(Files.exists(staged));
    }

    @Test
    public void configuredDefaults() {
        assertEquals(90, E2eSourceRetentionService.DEFAULT_RETENTION_DAYS);
        assertEquals(Path.of("/var/lib/libreclinica/e2e-source"), Path.of(E2eSourceRetentionService.DEFAULT_SOURCE_PATH));
        assertEquals(90, new E2eSourceRetentionService(null, Path.of("/x"), 0).getRetentionDays());
    }
}
