/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.ingest;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class NeutralFilenameTest {

    @Test
    public void theDocumentedShapesMatch() {
        assertTrue(NeutralFilename.matches("HAE-001_20260918_OD.e2e", "HAE-001", ".e2e"));
        assertTrue(NeutralFilename.matches("HAE-001_20260918_OS_2.e2e", "HAE-001", ".e2e"));
        assertTrue(NeutralFilename.matches("HAE-001_20260918_OD.dcm", "HAE-001", ".dcm"));
        assertTrue(NeutralFilename.matches("HAE-001_20260918_OS_12.dcm", "HAE-001", ".dcm"));
    }

    @Test
    public void theLabelMustBeTheUploadsLabelExactly() {
        assertFalse(NeutralFilename.matches("HAE-002_20260918_OD.e2e", "HAE-001", ".e2e"));
        assertFalse(NeutralFilename.matches("hae-001_20260918_OD.e2e", "HAE-001", ".e2e"));
        assertFalse(NeutralFilename.matches("XHAE-001_20260918_OD.e2e", "HAE-001", ".e2e"));
        assertFalse(NeutralFilename.matches("Muster_Max_20260918_OD.e2e", "HAE-001", ".e2e"));
    }

    @Test
    public void aLabelWithRegexCharactersIsMatchedLiterally() {
        assertTrue(NeutralFilename.matches("A.B+C(1)_20260918_OD.e2e", "A.B+C(1)", ".e2e"));
        assertFalse(NeutralFilename.matches("AxB+C(1)_20260918_OD.e2e", "A.B+C(1)", ".e2e"));
    }

    @Test
    public void theDayMustBeARealDay() {
        assertFalse(NeutralFilename.matches("HAE-001_20261340_OD.e2e", "HAE-001", ".e2e"));
        assertFalse(NeutralFilename.matches("HAE-001_20260230_OD.e2e", "HAE-001", ".e2e"));
        assertFalse(NeutralFilename.matches("HAE-001_2026091_OD.e2e", "HAE-001", ".e2e"));
    }

    @Test
    public void theEyeAndTheExtensionAreFixed() {
        assertFalse(NeutralFilename.matches("HAE-001_20260918_OU.e2e", "HAE-001", ".e2e"));
        assertFalse(NeutralFilename.matches("HAE-001_20260918_OD.jpg", "HAE-001", ".e2e"));
        assertFalse(NeutralFilename.matches("HAE-001_20260918_OD.e2e", "HAE-001", ".dcm"));
        assertFalse(NeutralFilename.matches("HAE-001_20260918_OD.e2e.exe", "HAE-001", ".e2e"));
    }

    @Test
    public void nothingElseMayRideAlong() {
        assertFalse(NeutralFilename.matches("HAE-001_20260918_OD_Muster.e2e", "HAE-001", ".e2e"));
        assertFalse(NeutralFilename.matches("HAE-001_20260918_OD_1_2.e2e", "HAE-001", ".e2e"));
        assertFalse(NeutralFilename.matches("../HAE-001_20260918_OD.e2e", "HAE-001", ".e2e"));
        assertFalse(NeutralFilename.matches("HAE-001_20260918_OD.e2e\n", "HAE-001", ".e2e"));
    }

    @Test
    public void nullsAndBlanksNeverMatch() {
        assertFalse(NeutralFilename.matches(null, "HAE-001", ".e2e"));
        assertFalse(NeutralFilename.matches("HAE-001_20260918_OD.e2e", null, ".e2e"));
        assertFalse(NeutralFilename.matches("HAE-001_20260918_OD.e2e", " ", ".e2e"));
        assertFalse(NeutralFilename.matches("_20260918_OD.e2e", "", ".e2e"));
    }

    @Test
    public void matchesLabelAcceptsEitherKind() {
        assertTrue(NeutralFilename.matchesLabel("HAE-001_20260918_OD.e2e", "HAE-001"));
        assertTrue(NeutralFilename.matchesLabel("HAE-001_20260918_OD.dcm", "HAE-001"));
        assertFalse(NeutralFilename.matchesLabel("photo.jpg", "HAE-001"));
    }
}
