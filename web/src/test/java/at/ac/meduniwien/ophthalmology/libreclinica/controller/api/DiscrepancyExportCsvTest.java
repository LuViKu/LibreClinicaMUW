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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Phase E.6 {@code discrepancy-full} — unit coverage for
 * {@link DiscrepancyExportCsv}. Pins RFC 4180 quoting + the
 * header row + filename builder.
 */
class DiscrepancyExportCsvTest {

    @Test
    void render_HeaderRow_AlwaysPresent_EvenForEmptyInput() {
        String csv = DiscrepancyExportCsv.render(List.of());
        assertEquals(
                "id,type,status,subjectId,itemOid,description,assignedTo,daysOpen,lastActivityAt\r\n",
                csv);
    }

    @Test
    void render_OneRow_EmitsCrlfTerminatedRow() {
        DiscrepancyNoteDto row = new DiscrepancyNoteDto(
                "42", "query", "new", "M-001", "I_AGE",
                "Age looks low", "monitor_demo", 3, "2026-06-01T08:00:00Z");
        String csv = DiscrepancyExportCsv.render(List.of(row));
        assertTrue(csv.endsWith("\r\n"));
        assertTrue(csv.contains("\r\n42,query,new,M-001,I_AGE,Age looks low,monitor_demo,3,2026-06-01T08:00:00Z\r\n"));
    }

    @Test
    void quoteIfNeeded_LeavesPlainStringsAlone() {
        assertEquals("plain", DiscrepancyExportCsv.quoteIfNeeded("plain"));
        assertEquals("", DiscrepancyExportCsv.quoteIfNeeded(null));
        assertEquals("M-001", DiscrepancyExportCsv.quoteIfNeeded("M-001"));
    }

    @Test
    void quoteIfNeeded_QuotesEmbeddedComma() {
        assertEquals("\"a,b\"", DiscrepancyExportCsv.quoteIfNeeded("a,b"));
    }

    @Test
    void quoteIfNeeded_QuotesEmbeddedCrLf() {
        assertEquals("\"line1\r\nline2\"", DiscrepancyExportCsv.quoteIfNeeded("line1\r\nline2"));
        assertEquals("\"only-lf\nhere\"", DiscrepancyExportCsv.quoteIfNeeded("only-lf\nhere"));
    }

    @Test
    void quoteIfNeeded_DoublesEmbeddedDoubleQuotes() {
        // RFC 4180 §2.7 — " inside a quoted field doubles to "".
        assertEquals("\"He said \"\"hi\"\"\"", DiscrepancyExportCsv.quoteIfNeeded("He said \"hi\""));
    }

    @Test
    void filenameFor_BakesInOidAndDate() {
        assertEquals("discrepancies-S_TEST-2026-06-06.csv",
                DiscrepancyExportCsv.filenameFor("S_TEST", "2026-06-06"));
    }

    @Test
    void filenameFor_SanitizesUnsafeCharsInOid() {
        // Slashes, spaces etc. get coerced to underscores so the file
        // lands cleanly on every OS.
        assertEquals("discrepancies-S_T_E_S_T-2026-06-06.csv",
                DiscrepancyExportCsv.filenameFor("S/T E:S*T", "2026-06-06"));
    }

    @Test
    void filenameFor_DefaultsWhenOidIsBlank() {
        assertEquals("discrepancies-study-2026-06-06.csv",
                DiscrepancyExportCsv.filenameFor("", "2026-06-06"));
        assertEquals("discrepancies-study-2026-06-06.csv",
                DiscrepancyExportCsv.filenameFor(null, "2026-06-06"));
    }
}
