/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 * For details see: https://libreclinica.org/license
 * LibreClinica, copyright (C) 2020-2026
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.VisitImagingPlan.Coverage;
import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.VisitImagingPlan.Entry;
import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.VisitImagingPlan.PresentFile;

/**
 * DR-034 — the pure part of the visit imaging plan: which files satisfy which
 * entry. The database side is exercised by the controllers' integration tests.
 */
class VisitImagingPlanTest {

    private static final Entry OCT_OU = VisitImagingPlan.entry(1, "OCT", "spectralis", "required", "OU", "fluid");
    private static final Entry OCT_ANY = VisitImagingPlan.entry(1, "OCT", "spectralis", "required", null, "fluid");
    private static final Entry FUNDUS_OD = VisitImagingPlan.entry(2, "CLARUS", "clarus", "optional", "OD");

    @Test
    void anEntryWithoutLateralityIsSatisfiedByAnyFileOfThatModality() {
        List<Coverage> cov = VisitImagingPlan.coverage(List.of(OCT_ANY),
                List.of(new PresentFile(1, "spectralis", null)));
        assertEquals(1, cov.size());
        assertTrue(cov.get(0).satisfied());
        assertEquals(1, cov.get(0).presentTotal());
        assertEquals(0, cov.get(0).presentOD());
    }

    @Test
    void bothEyesNeedsOneFilePerEyeOrOneMarkedOU() {
        assertFalse(VisitImagingPlan.coverage(List.of(OCT_OU),
                List.of(new PresentFile(1, "spectralis", "OD"))).get(0).satisfied());
        assertTrue(VisitImagingPlan.coverage(List.of(OCT_OU),
                List.of(new PresentFile(1, "spectralis", "OD"),
                        new PresentFile(1, "spectralis", "OS"))).get(0).satisfied());
        assertTrue(VisitImagingPlan.coverage(List.of(OCT_OU),
                List.of(new PresentFile(1, "spectralis", "OU"))).get(0).satisfied());
    }

    @Test
    void aFileOfTheWrongEyeDoesNotCount() {
        Coverage c = VisitImagingPlan.coverage(List.of(FUNDUS_OD),
                List.of(new PresentFile(2, "clarus", "OS"))).get(0);
        assertFalse(c.satisfied());
        assertEquals(1, c.presentTotal());
        assertEquals(1, c.presentOS());
    }

    @Test
    void anUnclassifiedFileMatchesByDeviceKeyOnly() {
        // No catalogue id on the row: the device key decides, case-insensitively.
        assertTrue(VisitImagingPlan.coverage(List.of(OCT_ANY),
                List.of(new PresentFile(null, "Spectralis", "OD"))).get(0).satisfied());
        // A classified row never falls back to the device key.
        assertFalse(VisitImagingPlan.coverage(List.of(OCT_ANY),
                List.of(new PresentFile(2, "spectralis", "OD"))).get(0).satisfied());
        // And an unclassified row with no device matches nothing.
        assertFalse(VisitImagingPlan.coverage(List.of(OCT_ANY),
                List.of(new PresentFile(null, null, "OD"))).get(0).satisfied());
    }

    @Test
    void entriesAreReportedInPlanOrderWhetherOrNotAnythingIsPresent() {
        List<Coverage> cov = VisitImagingPlan.coverage(List.of(OCT_OU, FUNDUS_OD), List.of());
        assertEquals(2, cov.size());
        assertEquals("OCT", cov.get(0).entry().code());
        assertFalse(cov.get(0).satisfied());
        assertEquals("CLARUS", cov.get(1).entry().code());
        assertFalse(cov.get(1).satisfied());
    }

    @Test
    void tasksRoundTripThroughTheCsvColumn() {
        assertEquals(List.of("fluid", "layers"), VisitImagingPlan.splitTasks(" Fluid, layers ,fluid,"));
        assertEquals(List.of(), VisitImagingPlan.splitTasks(""));
        assertEquals(List.of(), VisitImagingPlan.splitTasks(null));
        assertEquals("fluid,layers", VisitImagingPlan.joinTasks(List.of("fluid", "layers")));
    }

    @Test
    void kindsAcceptedIsAcommaListMatchedWholeWord() {
        assertTrue(VisitImagingPlan.acceptsKind("e2e,dicom", "e2e"));
        assertTrue(VisitImagingPlan.acceptsKind(" E2E ", "e2e"));
        assertFalse(VisitImagingPlan.acceptsKind("dicom,image", "e2e"));
        assertFalse(VisitImagingPlan.acceptsKind("", "e2e"));
        assertFalse(VisitImagingPlan.acceptsKind(null, "e2e"));
    }

    @Test
    void describeIsOneLinePerPlanForTheAuditRow() {
        assertEquals("OCT:required:OU:fluid;CLARUS:optional:OD:",
                VisitImagingPlan.describe(List.of(OCT_OU, FUNDUS_OD)));
    }
}
