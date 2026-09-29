/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.control.extract;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.lang.reflect.Method;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;

/**
 * Every ODM export through the legacy Export Dataset page used to unzip the
 * freshly written archive into the server's working directory, naming the
 * extracted files after the zip entries (derived from the dataset name), and
 * then schedule an XSLT job for a stylesheet that is not shipped. The branch
 * was meant to be opt-in but ran on every request. It is gone; this guards
 * against it coming back.
 */
class ExportDatasetServletZipTest {

    @Test
    void theServletNoLongerExtractsExportArchives() {
        for (Method m : ExportDatasetServlet.class.getDeclaredMethods()) {
            assertFalse(m.getName().equals("openZipFile"), "openZipFile is back");
            for (Class<?> p : m.getParameterTypes()) {
                assertFalse(ZipFile.class.equals(p), m.toString());
            }
        }
    }
}
