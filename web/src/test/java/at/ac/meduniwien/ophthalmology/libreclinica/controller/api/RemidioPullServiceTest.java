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
    void thePassStartsBehindTheLastSuccessOrAtTheConfiguredBeginning() {
        LocalDate today = LocalDate.of(2026, 9, 23);
        LocalDate since = LocalDate.of(2026, 6, 17);

        assertEquals(since, RemidioPullService.windowStart(null, 14, since, today),
                "first pass: from the configured beginning");
        assertEquals(today.minusDays(365), RemidioPullService.windowStart(null, 14, null, today),
                "first pass, nothing configured: a year back");
        assertEquals(LocalDate.of(2026, 9, 8),
                RemidioPullService.windowStart(LocalDate.of(2026, 9, 22), 14, since, today),
                "later pass: overlap behind the last success");
        assertEquals(LocalDate.of(2026, 7, 20),
                RemidioPullService.windowStart(LocalDate.of(2026, 8, 3), 14, since, today),
                "after downtime the window reaches back to the last success, so it catches up");
        assertEquals(today, RemidioPullService.windowStart(today.plusDays(3), 0, since, today),
                "a watermark from the future (clock skew) never starts after today");
    }

    @Test
    void aLongWindowIsListedInChunksThatMeetWithoutGaps() {
        List<LocalDate[]> one = RemidioPullService.chunks(
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 23), 30);
        assertEquals(1, one.size());
        assertEquals(LocalDate.of(2026, 9, 1), one.get(0)[0]);
        assertEquals(LocalDate.of(2026, 9, 23), one.get(0)[1]);

        List<LocalDate[]> many = RemidioPullService.chunks(
                LocalDate.of(2026, 6, 17), LocalDate.of(2026, 9, 23), 30);
        assertEquals(4, many.size());
        assertEquals(LocalDate.of(2026, 6, 17), many.get(0)[0]);
        assertEquals(LocalDate.of(2026, 7, 16), many.get(0)[1]);
        assertEquals(LocalDate.of(2026, 7, 17), many.get(1)[0], "the next chunk starts the day after");
        assertEquals(LocalDate.of(2026, 9, 23), many.get(3)[1], "the last chunk ends today");

        assertEquals(1, RemidioPullService.chunks(LocalDate.of(2026, 9, 23), LocalDate.of(2026, 9, 23), 30).size(),
                "a single day is one chunk");
    }

    @Test
    void summariesAddUpAcrossChunks() {
        RemidioPullService.Summary a = new RemidioPullService.Summary(
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 30), 5, 2, 4, 3, 1, 0, 0, 0);
        RemidioPullService.Summary b = new RemidioPullService.Summary(
                LocalDate.of(2026, 8, 31), LocalDate.of(2026, 9, 23), 7, 1, 2, 0, 2, 1, 0, 1);

        RemidioPullService.Summary sum = RemidioPullService.Summary.empty(null, null).plus(a).plus(b);

        assertEquals(LocalDate.of(2026, 8, 1), sum.from());
        assertEquals(LocalDate.of(2026, 9, 23), sum.to());
        assertEquals(12, sum.exams());
        assertEquals(3, sum.newExams());
        assertEquals(6, sum.images());
        assertEquals(3, sum.bound());
        assertEquals(3, sum.unbound());
        assertEquals(1, sum.duplicates());
        assertEquals(1, sum.failed());
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
