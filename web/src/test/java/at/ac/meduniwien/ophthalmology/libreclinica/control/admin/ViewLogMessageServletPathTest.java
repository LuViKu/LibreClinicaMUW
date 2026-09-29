/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.control.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The import job links to its per-run log as
 * {@code ViewLogMessage?n=<yyyy>/<MM>/<dd>/<HHmmssSSS>/<file name>}. That
 * name has to keep resolving, while names that leave the import directory
 * resolve to nothing.
 */
class ViewLogMessageServletPathTest {

    @TempDir
    Path root;

    private File importDir() throws Exception {
        return Files.createDirectories(root.resolve("import")).toFile();
    }

    private static String sep(String path) {
        return path.replace('/', File.separatorChar);
    }

    @Test
    void resolvesTheLinkTheImportJobWrites() throws Exception {
        File importDir = importDir();
        Path log = importDir.toPath().resolve(sep("2026/09/29/134501123/odm_import.xml.log.txt/log.txt"));
        Files.createDirectories(log.getParent());
        Files.writeString(log, "3 subjects imported");

        File resolved = ViewLogMessageServlet.resolveLogFile(importDir, sep("2026/09/29/134501123/odm_import.xml"));

        assertEquals(log.toFile().getCanonicalFile(), resolved.getCanonicalFile());
    }

    @Test
    void whitespaceInTheFileNameStillMapsToUnderscores() throws Exception {
        File importDir = importDir();
        File resolved = ViewLogMessageServlet.resolveLogFile(importDir, sep("2026/09/29/134501123/odm import.xml"));
        assertEquals(new File(importDir, sep("2026/09/29/134501123/odm_import.xml.log.txt/log.txt")).getCanonicalFile(),
                resolved.getCanonicalFile());
    }

    @Test
    void namesThatClimbOutOfTheImportDirectoryResolveToNothing() throws Exception {
        File importDir = importDir();
        Path outside = root.resolve(sep("elsewhere.log.txt/log.txt"));
        Files.createDirectories(outside.getParent());
        Files.writeString(outside, "not an import log");

        assertNull(ViewLogMessageServlet.resolveLogFile(importDir, "../elsewhere"));
        assertNull(ViewLogMessageServlet.resolveLogFile(importDir, "2026/../../elsewhere"));
        assertNull(ViewLogMessageServlet.resolveLogFile(importDir, "2026\\..\\..\\elsewhere"));
        assertNull(ViewLogMessageServlet.resolveLogFile(importDir, ""));
    }
}
