/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;

import org.junit.jupiter.api.Test;

/**
 * DR-036 — what an ingress does with a picture it has seen before.
 *
 * <p>Pinned: the same label is a duplicate and another label is a hold, a
 * dismissed twin is always a hold, labels compare the way devices type them,
 * and the audit value names the twin and both labels.
 */
class IngestTwinsTest {

    private static IngestTwins.Twin twin(String status, String label) {
        return new IngestTwins.Twin(42L, status, label, null, null, Instant.parse("2026-09-01T08:00:00Z"));
    }

    @Test
    void noTwinMeansNothingToDecide() {
        assertEquals(IngestTwins.Verdict.NONE, IngestTwins.verdict(null, "HAE-001"));
    }

    @Test
    void theSamePictureUnderTheSameLabelIsADuplicate() {
        assertEquals(IngestTwins.Verdict.DUPLICATE, IngestTwins.verdict(twin("BOUND", "HAE-001"), "HAE-001"));
        assertEquals(IngestTwins.Verdict.DUPLICATE, IngestTwins.verdict(twin("UNBOUND", "HAE-001"), " hae-001 "));
    }

    @Test
    void theSamePictureUnderAnotherLabelIsHeld() {
        assertEquals(IngestTwins.Verdict.HELD, IngestTwins.verdict(twin("BOUND", "HAE-001"), "HAE-002"));
        assertEquals(IngestTwins.Verdict.HELD, IngestTwins.verdict(twin("UNBOUND", null), "HAE-002"));
        assertEquals(IngestTwins.Verdict.HELD, IngestTwins.verdict(twin("BOUND", "HAE-001"), null));
    }

    @Test
    void aDismissedTwinIsAlwaysHeldSoSomebodyLooks() {
        assertEquals(IngestTwins.Verdict.HELD, IngestTwins.verdict(twin("DISMISSED", "HAE-001"), "HAE-001"));
    }

    @Test
    void twoFilesWithoutAnyLabelAgree() {
        assertEquals(IngestTwins.Verdict.DUPLICATE, IngestTwins.verdict(twin("UNBOUND", null), null));
        assertEquals(IngestTwins.Verdict.DUPLICATE, IngestTwins.verdict(twin("UNBOUND", " "), ""));
    }

    @Test
    void labelsCompareTheWayDevicesTypeThem() {
        assertTrue(IngestTwins.sameLabel("HAE-001", "hae-001"));
        assertTrue(IngestTwins.sameLabel(" HAE-001", "HAE-001 "));
        assertTrue(IngestTwins.sameLabel(null, ""));
        assertFalse(IngestTwins.sameLabel("HAE-001", "HAE-0011"));
        assertFalse(IngestTwins.sameLabel(null, "HAE-001"));
    }

    @Test
    void theAuditValueNamesTheTwinAndBothLabels() {
        assertEquals("held;same_image_as=42;twin_status=BOUND;twin_label=HAE-001;claimed_label=HAE-002;route=dicom",
                IngestTwins.heldValue(twin("BOUND", "HAE-001"), "HAE-002", "dicom"));
        assertEquals("held;same_image_as=42;twin_status=DISMISSED;route=upload",
                IngestTwins.heldValue(twin("DISMISSED", null), " ", "upload"));
    }
}
