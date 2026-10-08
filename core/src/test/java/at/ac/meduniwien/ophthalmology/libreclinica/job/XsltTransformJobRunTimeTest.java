/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.job;

import static org.junit.Assert.assertEquals;

import java.util.Locale;

import org.junit.Test;

/**
 * The XSLT export job rounds its run time to one decimal by formatting it and
 * parsing the text back. The format took the JVM's default locale, so under a
 * decimal-comma locale such as de-AT it produced "12,3", which
 * Double.parseDouble rejects: the job failed after the export file had been
 * written, and the file never got its archive record.
 */
public class XsltTransformJobRunTimeTest {

    @Test
    public void theRunTimeIsRoundedUnderADecimalCommaLocale() {
        Locale before = Locale.getDefault(Locale.Category.FORMAT);
        Locale.setDefault(Locale.Category.FORMAT, Locale.forLanguageTag("de-AT"));
        try {
            assertEquals(12.3, new XsltTransformJob().setFormat(12.34), 0.0);
        } finally {
            Locale.setDefault(Locale.Category.FORMAT, before);
        }
    }
}
