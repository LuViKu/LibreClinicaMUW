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
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.remidio.RemidioGatewayClient.Exam;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.remidio.RemidioGatewayClient.Image;

/** The pull's mapping of what the cloud says onto what the inbox stores. */
class RemidioPullServiceTest {

    @Test
    void lateralityIsTheInboxesOdOs() {
        assertEquals("OD", RemidioPullService.lateralityOf("RIGHT"));
        assertEquals("OS", RemidioPullService.lateralityOf("left"));
        assertEquals("OU", RemidioPullService.lateralityOf("BOTH"));
        assertEquals("OD", RemidioPullService.lateralityOf("OD"));
        assertNull(RemidioPullService.lateralityOf("UNKNOWN"));
        assertNull(RemidioPullService.lateralityOf(null));
    }

    @Test
    void theClinicDayComesFromTheImageThenTheExam() {
        // 23:30 UTC on the 22nd is already the 23rd in Vienna (CEST).
        Instant lateUtc = Instant.parse("2026-09-22T23:30:00Z");
        assertEquals(LocalDate.of(2026, 9, 23), RemidioPullService.dateOf(lateUtc, null));
        assertEquals(LocalDate.of(2026, 9, 23), RemidioPullService.dateOf(null, lateUtc));
        assertEquals(LocalDate.of(2026, 9, 23),
                RemidioPullService.dateOf(lateUtc, Instant.parse("2026-09-01T10:00:00Z")),
                "the image's own stamp wins over the exam's");
        assertNull(RemidioPullService.dateOf(null, null));
    }

    @Test
    void theFilenameNamesExamImageAndEyeAndNothingUnsafe() {
        Exam exam = new Exam("ex/1", null, null, null, List.of("FOP"), "ACTIVE", "HAE-002", List.of());
        Image image = new Image("im 2", "ex/1", null, "RIGHT", null, null, null,
                "https://x/y", null, "FOP", "fopImages", "STANDARD");

        assertEquals("ex_1_im_2_OD.jpg", RemidioPullService.filenameFor(exam, image, "OD", ".jpg"));
        assertEquals("ex_1_im_2.png", RemidioPullService.filenameFor(exam, image, null, ".png"));
    }
}
