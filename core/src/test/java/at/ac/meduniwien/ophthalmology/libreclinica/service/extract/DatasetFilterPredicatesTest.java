/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.extract;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.math.BigDecimal;
import java.sql.Date;
import java.util.List;

import org.junit.Test;

/**
 * The SQL one dataset filter row renders to — shared by the wizard's preview
 * and the export, so the two cannot disagree.
 */
public class DatasetFilterPredicatesTest {

    @Test
    public void notEqualIsAcceptedInBothSpellings() {
        // The wizard sends !=; the export used to reject it and, with no other
        // filter, export everyone.
        assertEquals("id.value <> ?", DatasetFilterPredicates.render("!=", "x", List.of()).sql());
        assertEquals("id.value <> ?", DatasetFilterPredicates.render("<>", "x", List.of()).sql());
    }

    @Test
    public void aNumericItemComparesAsANumberAndNeverFailsOnText() {
        DatasetFilterPredicates.Fragment f = DatasetFilterPredicates.render(">", "100", List.of(), true);
        assertTrue(f.sql(), f.sql().contains("::numeric"));
        assertTrue("a value that is not a number reads as NULL: " + f.sql(), f.sql().contains("CASE WHEN"));
        assertEquals(List.of(new BigDecimal("100")), f.params());
        assertEquals("a decimal comma counts", List.of(new BigDecimal("1.5")),
                DatasetFilterPredicates.render("=", "1,5", List.of(), true).params());
        assertThrows(IllegalArgumentException.class,
                () -> DatasetFilterPredicates.render(">", "abc", List.of(), true));
    }

    @Test
    public void aTextItemStillComparesAsText() {
        DatasetFilterPredicates.Fragment f = DatasetFilterPredicates.render("=", "yes", List.of());
        assertEquals("id.value = ?", f.sql());
        assertEquals(List.of("yes"), f.params());
    }

    @Test
    public void startsWithIsForTheParticipantIdOnly() {
        assertThrows(IllegalArgumentException.class,
                () -> DatasetFilterPredicates.render("starts-with", "x", List.of()));
    }

    @Test
    public void theParticipantIdIgnoresCaseAndBlanks() {
        DatasetFilterPredicates.Fragment eq = DatasetFilterPredicates.renderParticipant(
                "participantId", "=", " hae-050 ", List.of());
        assertEquals("upper(ss.label) = upper(trim(?))", eq.sql());
        assertEquals(List.of(" hae-050 "), eq.params());

        DatasetFilterPredicates.Fragment in = DatasetFilterPredicates.renderParticipant(
                "participantId", "in", null, List.of("HAE-050", "hae-051"));
        assertEquals("upper(ss.label) IN (upper(trim(?)),upper(trim(?)))", in.sql());
    }

    @Test
    public void startsWithMatchesItsWildcardsLiterally() {
        DatasetFilterPredicates.Fragment f = DatasetFilterPredicates.renderParticipant(
                "participantId", "starts-with", "hae_05%", List.of());
        assertEquals("upper(ss.label) LIKE ? ESCAPE '\\'", f.sql());
        assertEquals(List.of("HAE\\_05\\%%"), f.params());
    }

    @Test
    public void theEnrolmentDateTakesIsoDates() {
        DatasetFilterPredicates.Fragment f = DatasetFilterPredicates.renderParticipant(
                "enrollmentDate", "between", null, List.of("2026-11-01", "2026-11-30"));
        assertEquals("ss.enrollment_date BETWEEN ? AND ?", f.sql());
        assertEquals(List.of(Date.valueOf("2026-11-01"), Date.valueOf("2026-11-30")), f.params());
        assertThrows(IllegalArgumentException.class, () -> DatasetFilterPredicates.renderParticipant(
                "enrollmentDate", "=", "24.11.2026", List.of()));
    }

    @Test
    public void eachParticipantFieldOffersOnlyItsOwnOperators() {
        assertThrows(IllegalArgumentException.class, () -> DatasetFilterPredicates.renderParticipant(
                "participantId", ">", "HAE", List.of()));
        assertThrows(IllegalArgumentException.class, () -> DatasetFilterPredicates.renderParticipant(
                "enrollmentDate", "starts-with", "2026", List.of()));
        assertThrows(IllegalArgumentException.class, () -> DatasetFilterPredicates.renderParticipant(
                "dateOfBirth", "=", "2000-01-01", List.of()));
    }
}
