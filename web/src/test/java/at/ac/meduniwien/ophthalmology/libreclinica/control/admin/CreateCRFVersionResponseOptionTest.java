/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.control.admin;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.ResponseOptionBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.ResponseSetBean;
import org.junit.jupiter.api.Test;

/** A spreadsheet option with a missing text or value is compared as empty, not dereferenced. */
class CreateCRFVersionResponseOptionTest {

    private static ResponseSetBean set(String text, String value) {
        ResponseOptionBean option = new ResponseOptionBean();
        option.setText(text);
        option.setValue(value);
        ResponseSetBean set = new ResponseSetBean();
        set.addOption(option);
        return set;
    }

    private final CreateCRFVersionServlet servlet = new CreateCRFVersionServlet();

    @Test
    void nullTextWithValueDoesNotThrow() {
        assertNull(servlet.hasDifferentOption(set("yes", "1"), set(null, "2")));
    }

    @Test
    void nullValueWithTextDoesNotThrow() {
        assertNull(servlet.hasDifferentOption(set("yes", "1"), set("no", null)));
    }

    @Test
    void sameTextWithDifferentValueIsStillReported() {
        assertNotNull(servlet.hasDifferentOption(set("yes", "1"), set("yes", "2")));
    }

    @Test
    void emptyRepeatedOptionIsSkipped() {
        assertNull(servlet.hasDifferentOption(set("yes", "1"), set(null, null)));
    }
}
