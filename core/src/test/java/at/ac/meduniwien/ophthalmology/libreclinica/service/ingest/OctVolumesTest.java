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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** DR-039 — an OCT volume is recognised by what it is, not by its format. */
public class OctVolumesTest {

    private static final String OPT = OctVolumes.OPT_SOP_CLASS_UID;
    private static final String FUNDUS = "1.2.840.10008.5.1.4.1.1.77.1.5.1";

    @Test
    public void anOptObjectWithSeveralFramesIsAVolume() {
        assertEquals(Boolean.TRUE, OctVolumes.classify(OPT, "OPT", 49));
        assertEquals("the modality alone says OPT", Boolean.TRUE, OctVolumes.classify(null, "opt", 97));
        assertEquals("the SOP class alone says OPT", Boolean.TRUE, OctVolumes.classify(OPT, null, 2));
    }

    @Test
    public void aLineScanOrAFundusIsNot() {
        assertEquals(Boolean.FALSE, OctVolumes.classify(OPT, "OPT", 1));
        assertEquals(Boolean.FALSE, OctVolumes.classify(FUNDUS, "OP", 1));
        assertEquals("frames do not make a fundus an OCT", Boolean.FALSE, OctVolumes.classify(FUNDUS, "OP", 3));
        assertEquals(Boolean.FALSE, OctVolumes.classify(null, null, null));
    }

    @Test
    public void anOptObjectOfUnknownFrameCountIsUnclassified() {
        assertNull(OctVolumes.classify(OPT, "OPT", null));
    }

    @Test
    public void onlyE2eAndClassifiedDicomVolumesAreAnalysable() {
        assertTrue(OctVolumes.isAnalysable("e2e", null));
        assertTrue(OctVolumes.isAnalysable("E2E", Boolean.FALSE));
        assertTrue(OctVolumes.isAnalysable("dicom", Boolean.TRUE));
        assertFalse(OctVolumes.isAnalysable("dicom", Boolean.FALSE));
        assertFalse("unclassified is not analysable", OctVolumes.isAnalysable("dicom", null));
        assertFalse(OctVolumes.isAnalysable("image", Boolean.TRUE));
        assertFalse(OctVolumes.isAnalysable(null, Boolean.TRUE));
    }
}
