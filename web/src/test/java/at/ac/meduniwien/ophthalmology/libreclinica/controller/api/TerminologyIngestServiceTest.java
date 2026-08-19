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

import org.junit.jupiter.api.Test;

/**
 * #26 — pure-unit coverage for {@link TerminologyIngestService} helpers used
 * by the medication ingest: the ELGA OID-prefix strip (so an ATC value like
 * {@code 2.16.840.1.113883.6.73:C05BB02} stores as {@code C05BB02}) and the
 * search-key normaliser (German-safe, whitespace-collapsed).
 */
class TerminologyIngestServiceTest {

    @Test
    void stripOidPrefix_KeepsTailAfterLastColon() {
        assertEquals("C05BB02", TerminologyIngestService.stripOidPrefix("2.16.840.1.113883.6.73:C05BB02"));
        assertEquals("N03AF01", TerminologyIngestService.stripOidPrefix("2.16.840.1.113883.6.73:N03AF01"));
    }

    @Test
    void stripOidPrefix_NoColon_ReturnsInput() {
        assertEquals("C05BB02", TerminologyIngestService.stripOidPrefix("C05BB02"));
    }

    @Test
    void stripOidPrefix_TrailingColon_ReturnsInput() {
        assertEquals("abc:", TerminologyIngestService.stripOidPrefix("abc:"));
    }

    @Test
    void normalise_LowercasesAndCollapsesWhitespace_KeepsUmlauts() {
        assertEquals("neurotop 400 mg - tabletten",
                TerminologyIngestService.normalise("  NEUROTOP   400 mg -  Tabletten "));
        assertEquals("injektionslösung", TerminologyIngestService.normalise("Injektionslösung"));
    }
}
