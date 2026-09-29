/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.control.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * {@link CreateFiltersTwoServlet#extractIdsFromForm()} scans the request for
 * parameters whose <em>name</em> starts with "ID" and turns the rest of the
 * name into an item id. Parameter names are chosen by whoever sends the
 * request, so a name such as {@code IDENTIFIER} used to reach
 * {@code Integer.valueOf("ENTIFIER")} and leave the servlet with an uncaught
 * NumberFormatException (a 500 page). Names that do not reduce to a number are
 * now skipped, exactly as if they had not been sent.
 */
class CreateFiltersTwoServletIdParsingTest {

    /** Wires the servlet's request field by hand; no container needed. */
    private static final class Probe extends CreateFiltersTwoServlet {
        private static final long serialVersionUID = 1L;

        List<Integer> idsFrom(MockHttpServletRequest req) {
            this.request = req;
            return extractIdsFromForm();
        }
    }

    private static MockHttpServletRequest requestWith(String... parameterNames) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        for (String name : parameterNames) {
            req.setParameter(name, "on");
        }
        return req;
    }

    @Test
    void numericIdParametersAreCollected() {
        List<Integer> ids = new Probe().idsFrom(requestWith("ID12", "ID7"));

        List<Integer> sorted = new ArrayList<>(ids);
        sorted.sort(null);
        assertEquals(List.of(7, 12), sorted);
    }

    @Test
    void nonNumericIdParameterNamesAreSkippedInsteadOfThrowing() {
        // "IDENTIFIER" reduces to "ENTIFIER" once every "ID" is stripped.
        List<Integer> ids = new Probe().idsFrom(requestWith("IDENTIFIER", "ID", "ID_4", "ID9"));

        assertEquals(List.of(9), ids);
    }

    @Test
    void parametersNotStartingWithIdAreIgnored() {
        List<Integer> ids = new Probe().idsFrom(requestWith("action", "submitted", "myID3"));

        assertTrue(ids.isEmpty(), "only parameter names starting with ID are item ids");
    }
}
