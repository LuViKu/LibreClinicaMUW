/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.web.table.sdv;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

/** The {@code sdvCheck_<id>} form keys: a malformed one is ignored, not a 500. */
class SDVUtilParamTest {

    private final SDVUtil util = new SDVUtil();

    @Test
    void aMalformedKeyIsIgnored() {
        assertEquals(List.of(7, 9),
                util.getListOfSdvEventCRFIds(List.of("sdvCheck_7", "sdvCheck_x", "sdvCheck_", "sdvCheck_9")));
    }
}
