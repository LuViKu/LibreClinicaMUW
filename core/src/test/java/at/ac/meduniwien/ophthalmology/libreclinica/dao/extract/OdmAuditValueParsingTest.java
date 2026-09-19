/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.dao.extract;

import static org.junit.Assert.assertEquals;

import java.util.Locale;

import org.junit.BeforeClass;
import org.junit.Test;

import at.ac.meduniwien.ophthalmology.libreclinica.i18n.util.ResourceBundleProvider;

/**
 * Audit values in the ODM export.
 *
 * <p>Six audit event types happen to store a status id in old_value/new_value,
 * and the exporter renders those as names. It called {@code Integer.parseInt}
 * on the value with no guard — so when newer code started writing rows of type
 * 8 ("Event CRF marked complete") carrying an ISO timestamp, a single completed
 * CRF made the whole study impossible to export as ODM.
 *
 * <p>The column is free text. An exporter copying an audit row must not die on
 * its content, which is the same lesson as
 * {@link OdmWidthDecimalParsingTest}.
 */
public class OdmAuditValueParsingTest {

    /** The heritage status terms read their display names from a bundle. */
    @BeforeClass
    public static void initBundles() {
        ResourceBundleProvider.updateLocale(Locale.ENGLISH);
    }

    @Test
    public void aStatusIdBecomesItsName() {
        // 1 = available in the heritage status map; the point is that a
        // numeric value is still translated, not that it is this word.
        String name = OdmExtractDAO.statusNameOrRaw("1");
        assertEquals("available", name);
    }

    @Test
    public void zeroIsTheInvalidStatus() {
        assertEquals("invalid", OdmExtractDAO.statusNameOrRaw("0"));
    }

    /** The row that broke every ODM export of a study with a completed CRF. */
    @Test
    public void anIsoTimestampTravelsThroughUnchanged() {
        String ts = "2026-06-21T15:48:36.647138129Z";
        assertEquals(ts, OdmExtractDAO.statusNameOrRaw(ts));
    }

    @Test
    public void ordinaryTextTravelsThroughUnchanged() {
        assertEquals("date_completed", OdmExtractDAO.statusNameOrRaw("date_completed"));
        assertEquals("", OdmExtractDAO.statusNameOrRaw(""));
    }

    @Test
    public void aNullStaysNull() {
        assertEquals(null, OdmExtractDAO.statusNameOrRaw(null));
    }

    /** Whitespace around a stored id is still an id. */
    @Test
    public void aPaddedStatusIdIsStillTranslated() {
        assertEquals(OdmExtractDAO.statusNameOrRaw("1"), OdmExtractDAO.statusNameOrRaw(" 1 "));
    }
}
