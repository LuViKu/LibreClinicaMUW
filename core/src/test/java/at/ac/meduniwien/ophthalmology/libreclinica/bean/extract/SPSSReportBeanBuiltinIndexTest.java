/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.bean.extract;

import static org.junit.Assert.assertEquals;

import java.util.Locale;

import org.junit.Before;
import org.junit.Test;

import at.ac.meduniwien.ophthalmology.libreclinica.i18n.util.ResourceBundleProvider;

/**
 * builtinIndex guarded a null item name with the non-short-circuit {@code &},
 * so a null name still reached {@code startsWith}.
 */
public class SPSSReportBeanBuiltinIndexTest {

    private final String[] attributes = {"SubjID", "ProtocolID", "Sex"};

    @Before
    public void locale() {
        // SPSSReportBean reads the date format from the format bundle when it is constructed.
        ResourceBundleProvider.updateLocale(Locale.ENGLISH);
    }

    @Test
    public void aNullItemNameMatchesNothing() {
        assertEquals(-1, new SPSSReportBean().builtinIndex(null, attributes));
    }

    @Test
    public void anItemNameMatchesTheAttributeItStartsWith() {
        assertEquals(1, new SPSSReportBean().builtinIndex("ProtocolID_E1", attributes));
        assertEquals(-1, new SPSSReportBean().builtinIndex("I_WEIGHT", attributes));
    }
}
