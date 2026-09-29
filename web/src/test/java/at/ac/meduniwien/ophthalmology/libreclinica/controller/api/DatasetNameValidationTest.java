/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * The dataset name becomes part of every export file name, so the wizard
 * refuses path separators, as the legacy Create Dataset form does.
 */
class DatasetNameValidationTest {

    private static CreateDatasetRequest named(String name) {
        return new CreateDatasetRequest(name, "", List.of("SE_V1"), List.of(1), List.of(1),
                Map.of(), List.of(), List.of());
    }

    private static boolean nameRejected(String name) {
        return DatasetsApiController.validateWizardShape(named(name), null, null, 0).stream()
                .anyMatch(e -> "name".equals(e.field()));
    }

    @Test
    void ordinaryNamesAreAccepted() {
        assertFalse(nameRejected("Baseline VA export"));
        assertFalse(nameRejected("nAMD_2026-09 (OD+OS)"));
    }

    @Test
    void namesWithPathSeparatorsAreRejected() {
        assertTrue(nameRejected("../../outside"));
        assertTrue(nameRejected("a/b"));
        assertTrue(nameRejected("a\\b"));
    }
}
