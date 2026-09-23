/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The Optomed Client imports exactly one shape of file, and the shape was
 * established by dropping a hand-written file into the real Client on
 * 2026-09-23 and reading its database afterwards. These pin that shape byte
 * for byte, because "close" is not a format a vendor's parser accepts.
 */
class OptomedWorklistFormatTest {

    private static ScheduledVisitQuery.ScheduledVisit visit(String label, String eventLabel, String gender,
                                                             String date, LocalTime time) {
        return new ScheduledVisitQuery.ScheduledVisit(
                7, 5, label, gender, "1954-03-02", date, time,
                eventLabel, "HealthAEye", null, 1, 1);
    }

    @Test
    void oneRecordIsSixCrlfLinesPlusTwoBlankLines() {
        byte[] out = OptomedWorklistFormat.render(List.of(
                visit("HAE-002", "Baseline", "f", "2026-09-23", LocalTime.of(9, 0))));

        String expected = "20260923090000\r\n"
                + "HAE-002\r\n"
                + "Baseline\r\n"
                + "HAE-002\r\n"
                + "19700101\r\n"
                + "F\r\n"
                + "\r\n\r\n";
        assertArrayEquals(expected.getBytes(StandardCharsets.US_ASCII), out);
    }

    @Test
    void recordsAreSeparatedByExactlyOneBlankLine() {
        byte[] out = OptomedWorklistFormat.render(List.of(
                visit("HAE-002", "Baseline", "f", "2026-09-23", LocalTime.of(9, 0)),
                visit("HAE-003", "Baseline", "m", "2026-09-23", LocalTime.of(9, 30))));
        String s = new String(out, StandardCharsets.US_ASCII);

        // The vendor's six-record template has 43 CRLFs: 36 field lines, 5
        // separators, 2 trailing. Two records → 12 + 1 + 2 = 15.
        assertEquals(15, s.split("\r\n", -1).length - 1);
        assertTrue(s.contains("F\r\n\r\n20260923093000\r\n"), "one blank line between records");
        assertTrue(s.endsWith("M\r\n\r\n\r\n"), "two blank lines after the last record");
    }

    @Test
    void noBomNoLoneLfNothingOutsideAscii() {
        byte[] out = OptomedWorklistFormat.render(List.of(
                visit("HAE-002", "Baseline", "f", "2026-09-23", LocalTime.of(9, 0))));

        assertFalse(out[0] == (byte) 0xEF, "no UTF-8 BOM");
        for (int i = 0; i < out.length; i++) {
            assertTrue(out[i] >= 0x0A && out[i] < 0x7F, "byte " + i + " is 7-bit");
            if (out[i] == '\n') assertEquals('\r', out[i - 1], "every LF is preceded by CR");
        }
    }

    @Test
    void dateOfBirthIsThePlaceholderNeverTheRealOne() {
        byte[] out = OptomedWorklistFormat.render(List.of(
                visit("HAE-002", "Baseline", "f", "2026-09-23", LocalTime.of(9, 0))));
        String s = new String(out, StandardCharsets.US_ASCII);

        assertTrue(s.contains("\r\n19700101\r\n"));
        assertFalse(s.contains("1954"), "the subject's real date of birth must not leave the platform this way");
    }

    @Test
    void visitWithNoMeaningfulTimeIsMidnight() {
        assertEquals("20260923000000", OptomedWorklistFormat.scheduledStart(
                visit("HAE-002", "Baseline", "f", "2026-09-23", null)));
    }

    @Test
    void sexMirrorsTheSidecarMapping() {
        assertEquals("M", OptomedWorklistFormat.sex("m"));
        assertEquals("M", OptomedWorklistFormat.sex("Male"));
        assertEquals("F", OptomedWorklistFormat.sex("f"));
        assertEquals("F", OptomedWorklistFormat.sex(" female "));
        assertEquals("O", OptomedWorklistFormat.sex("o"));
        assertEquals("O", OptomedWorklistFormat.sex(null));
        assertEquals("O", OptomedWorklistFormat.sex(""));
        assertEquals("O", OptomedWorklistFormat.sex("x"));
    }

    @Test
    void nonAsciiAndLineBreaksCannotSplitOrCorruptARecord() {
        // An event label a study designer typed, with an umlaut, a tab, and a
        // newline that would otherwise turn six lines into seven.
        assertEquals("Baseline Untersuchung", OptomedWorklistFormat.ascii("Baseline\tUntersuchung\r\n", "Visit"));
        assertEquals("Rosenbchler", OptomedWorklistFormat.ascii("Rosenbüchler", "Visit"));
        assertEquals("Visit", OptomedWorklistFormat.ascii("   ", "Visit"));
        assertEquals("Visit", OptomedWorklistFormat.ascii(null, "Visit"));
        assertEquals("Visit", OptomedWorklistFormat.ascii("äöü", "Visit"));
    }

    @Test
    void fieldsAreCappedAtSixtyFourCharacters() {
        String longLabel = "L".repeat(200);
        assertEquals(64, OptomedWorklistFormat.ascii(longLabel, "x").length());
    }

    @Test
    void emptyListRendersAnEmptyFile() {
        // Replace semantics on the Client: an empty file clears the camera,
        // which is the right answer for a day with no visits.
        assertEquals(0, OptomedWorklistFormat.render(List.of()).length);
    }
}
