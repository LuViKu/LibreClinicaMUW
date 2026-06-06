/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api.export;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Phase E.6 — covers the RFC 4180 quoting rules + the UTF-8 BOM
 * preamble. Keep these green: parsers downstream of the
 * discrepancy export (R / pandas / Excel) all depend on exactly
 * this byte-level contract.
 */
class CsvWriterTest {

    @Test
    void emitsUtf8BomThenCrlfHeader() throws Exception {
        byte[] out = CsvWriter.toByteArray(List.of("a", "b"), List.of());
        // First three bytes are the BOM.
        assertArrayEquals(CsvWriter.UTF8_BOM,
                new byte[] { out[0], out[1], out[2] });
        // Rest is "a,b\r\n".
        String body = new String(out, 3, out.length - 3, StandardCharsets.UTF_8);
        assertEquals("a,b\r\n", body);
    }

    @Test
    void quotesCellsContainingCommaOrQuoteOrNewline() {
        // Plain ASCII passes through.
        assertEquals("plain", CsvWriter.encode("plain"));
        // Comma triggers quoting.
        assertEquals("\"O'Brien, Mary\"", CsvWriter.encode("O'Brien, Mary"));
        // Embedded quote is doubled inside the quoted form.
        assertEquals("\"she said \"\"hi\"\"\"",
                CsvWriter.encode("she said \"hi\""));
        // CR / LF in a description triggers quoting (the value itself
        // is preserved verbatim — CSV consumers are responsible for
        // multi-line handling).
        assertEquals("\"line1\nline2\"", CsvWriter.encode("line1\nline2"));
        assertEquals("\"line1\r\nline2\"", CsvWriter.encode("line1\r\nline2"));
    }

    @Test
    void encodesNullAsEmptyString() {
        assertEquals("", CsvWriter.encode(null));
        assertEquals("", CsvWriter.encode(""));
    }

    @Test
    void roundTripsUmlautsThroughBomPreservedUtf8() throws Exception {
        byte[] out = CsvWriter.toByteArray(
                List.of("id", "label"),
                List.of(List.of("1", "Östrogen-Spiegel"),
                        List.of("2", "Würmer")));
        // Skip the BOM.
        String body = new String(out, 3, out.length - 3, StandardCharsets.UTF_8);
        // RFC 4180 + CRLF + umlauts come through verbatim — no
        // quoting needed because none of the cells contain a
        // special character.
        assertTrue(body.contains("Östrogen-Spiegel"), body);
        assertTrue(body.contains("Würmer"), body);
        // Header + 2 rows + trailing CRLF on each.
        assertEquals(3, body.split("\r\n").length);
    }
}
