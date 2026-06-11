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
 * Phase E.6 ophth-field-catalog — pins the storage-format parser the
 * catalog controller runs over the {@code response_options} column.
 * Storage format: {@code value|label,value|label,…}. Pinned here so a
 * future schema evolution (e.g. multi-locale labels) can't silently
 * change the wire shape under the SPA's feet.
 */
class OphthFieldCatalogApiControllerTest {

    @Test
    void parseResponseOptions_returnsEmptyListForNullOrBlankInput() {
        assertTrue(OphthFieldCatalogApiController.parseResponseOptions(null).isEmpty());
        assertTrue(OphthFieldCatalogApiController.parseResponseOptions("").isEmpty());
        assertTrue(OphthFieldCatalogApiController.parseResponseOptions("   ").isEmpty());
    }

    @Test
    void parseResponseOptions_splitsCanonicalValueLabelPairs() {
        // Seeded format for yesno entries: "ja|Ja,nein|Nein".
        List<OphthFieldCatalogDto.ResponseOption> out =
                OphthFieldCatalogApiController.parseResponseOptions("ja|Ja,nein|Nein");
        assertEquals(2, out.size());
        assertEquals("ja", out.get(0).value());
        assertEquals("Ja", out.get(0).label());
        assertEquals("nein", out.get(1).value());
        assertEquals("Nein", out.get(1).label());
    }

    @Test
    void parseResponseOptions_handlesLensStatusFourOptionForm() {
        // Seeded LENS_STATUS row carries four lens-anatomy values.
        List<OphthFieldCatalogDto.ResponseOption> out =
                OphthFieldCatalogApiController.parseResponseOptions(
                        "phakic|Phak,cataract|Katarakt,iol|IOL (pseudophak),aphakic|Aphak");
        assertEquals(4, out.size());
        assertEquals("phakic", out.get(0).value());
        assertEquals("Katarakt", out.get(1).label());
        assertEquals("IOL (pseudophak)", out.get(2).label());
        assertEquals("aphakic", out.get(3).value());
    }

    @Test
    void parseResponseOptions_promotesBareValueWhenNoPipeSeparator() {
        // Legacy / hand-edited rows without the "value|label" form
        // surface both fields as the same string so the wire shape
        // stays stable.
        List<OphthFieldCatalogDto.ResponseOption> out =
                OphthFieldCatalogApiController.parseResponseOptions("only,both");
        assertEquals(2, out.size());
        assertEquals("only", out.get(0).value());
        assertEquals("only", out.get(0).label());
    }

    @Test
    void parseResponseOptions_skipsEmptyTokensFromTrailingOrDoubledCommas() {
        // Tolerant of stray commas — the wizard's authoring form may
        // serialise an empty trailing slot.
        List<OphthFieldCatalogDto.ResponseOption> out =
                OphthFieldCatalogApiController.parseResponseOptions("ja|Ja,,nein|Nein,");
        assertEquals(2, out.size());
        assertEquals("ja", out.get(0).value());
        assertEquals("nein", out.get(1).value());
    }

    @Test
    void parseResponseOptions_fillsEmptyLabelFromValue() {
        // Storage row like "phakic|" (label missing) — surface the
        // value as both fields rather than ship an empty label.
        List<OphthFieldCatalogDto.ResponseOption> out =
                OphthFieldCatalogApiController.parseResponseOptions("phakic|");
        assertEquals(1, out.size());
        assertEquals("phakic", out.get(0).value());
        assertEquals("phakic", out.get(0).label());
    }
}
