/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.extract;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * P3.7 — what a FILE item says once it leaves the platform.
 *
 * <p>The rule has two halves and the second is the one worth pinning: a value
 * is rewritten <em>only</em> because the item is declared FILE, never because
 * it happens to look like a path. Free text with a slash in it is clinical
 * data, and truncating it would silently discard what someone wrote.
 */
public class FileItemValueTest {

    private static final int FILE = 11;
    private static final int TEXT = 5;

    @Test
    public void aFileItemExportsAsItsFilename() {
        assertEquals("scan.e2e",
                FileItemValue.forExport("/var/lib/libreclinica/attached/12/scan.e2e", FILE));
    }

    @Test
    public void windowsPathsAreHandledToo() {
        assertEquals("report.pdf",
                FileItemValue.forExport("C:\\libreclinica\\files\\report.pdf", FILE));
    }

    @Test
    public void aBareFilenameIsLeftAlone() {
        assertEquals("scan.e2e", FileItemValue.forExport("scan.e2e", FILE));
    }

    /**
     * A path ending in a separator has no filename to reduce to. Returning the
     * value unchanged is better than returning an empty string, which would
     * read as "no file was attached".
     */
    @Test
    public void aTrailingSeparatorLeavesTheValueIntact() {
        assertEquals("/var/lib/libreclinica/attached/",
                FileItemValue.forExport("/var/lib/libreclinica/attached/", FILE));
    }

    @Test
    public void freeTextThatLooksLikeAPathIsNotTouched() {
        assertEquals("a text answer is clinical data, whatever characters are in it",
                "N/A - patient declined",
                FileItemValue.forExport("N/A - patient declined", TEXT));
        assertEquals("20/40", FileItemValue.forExport("20/40", TEXT));
    }

    @Test
    public void emptyAndNullSurviveUnchanged() {
        assertEquals("", FileItemValue.forExport("", FILE));
        assertNull(FileItemValue.forExport(null, FILE));
    }
}
