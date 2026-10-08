/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.bean.rule;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.io.File;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import org.junit.Test;

/**
 * An upload that names no target directory must not land somewhere other local
 * accounts can read (2026-09-29).
 *
 * <p>The fallback used to be {@code java.io.tmpdir} itself, which is shared. The
 * CRF data import reaches that path — it calls {@code returnFiles} without a
 * directory — so what was exposed was clinical data.
 */
@SuppressWarnings("resource") // the default FileSystem is never closed
public class FileUploadHelperTempDirectoryTest {

    @Test
    public void theFallbackDirectoryIsReadableOnlyByItsOwner() throws Exception {
        assumeTrue("POSIX permissions are not modelled on this platform",
                FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));

        File dir = new FileUploadHelper().privateUploadDirectory();
        try {
            assertTrue(dir.isDirectory());
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(dir.toPath());
            for (PosixFilePermission p : Set.of(
                    PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE,
                    PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_READ,
                    PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE)) {
                assertFalse(p + " must not be granted on the upload fallback directory",
                        perms.contains(p));
            }
        } finally {
            Files.deleteIfExists(dir.toPath());
        }
    }

    @Test
    public void eachCallGetsItsOwnDirectory() throws Exception {
        FileUploadHelper helper = new FileUploadHelper();
        File a = helper.privateUploadDirectory();
        File b = helper.privateUploadDirectory();
        try {
            assertFalse("two uploads must not share a directory", a.equals(b));
        } finally {
            Files.deleteIfExists(a.toPath());
            Files.deleteIfExists(b.toPath());
        }
    }
}
