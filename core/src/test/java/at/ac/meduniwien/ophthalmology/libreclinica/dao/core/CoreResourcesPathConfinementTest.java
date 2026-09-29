/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.dao.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.io.File;
import java.nio.file.Files;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * getFile/getAttachedFile resolve a request-supplied name below the data
 * directory. A sibling directory whose name merely starts with the data
 * directory's name is outside it.
 */
public class CoreResourcesPathConfinementTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final CoreResources resources = new CoreResources();

    @Test
    public void fileBelowTheBaseDirectoryResolves() throws Exception {
        File base = tmp.newFolder("libreclinica.data");
        File crf = new File(base, "crf" + File.separator + "original" + File.separator + "12_VA.xls");
        crf.getParentFile().mkdirs();
        Files.write(crf.toPath(), new byte[] { 1 });

        File resolved = resources.getFileFromPath(base.getPath() + File.separator, "12_VA.xls",
                "crf" + File.separator + "original" + File.separator);

        assertNotNull(resolved);
        assertEquals(crf.getCanonicalFile(), resolved.getCanonicalFile());
    }

    @Test
    public void siblingDirectorySharingThePrefixIsRejected() throws Exception {
        File base = tmp.newFolder("libreclinica.data");
        File sibling = tmp.newFolder("libreclinica.dataX");
        Files.write(new File(sibling, "secret.txt").toPath(), new byte[] { 1 });

        assertNull(resources.getFileFromPath(base.getPath() + File.separator, "secret.txt",
                ".." + File.separator + "libreclinica.dataX" + File.separator));
    }

    @Test
    public void parentDirectoryIsRejected() throws Exception {
        File base = tmp.newFolder("libreclinica.data");
        Files.write(tmp.newFile("outside.txt").toPath(), new byte[] { 1 });

        assertNull(resources.getFileFromPath(base.getPath() + File.separator, "outside.txt",
                ".." + File.separator));
    }
}
