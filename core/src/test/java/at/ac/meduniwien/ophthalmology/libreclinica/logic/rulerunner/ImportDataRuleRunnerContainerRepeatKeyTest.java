/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.logic.rulerunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * The repeat keys of an import file, as the import's rule run reads them: a
 * key left out is 1, and a key that is not a whole number of 1 or more names
 * nothing, rather than failing the import with NumberFormatException or being
 * read as the first row or visit.
 */
public class ImportDataRuleRunnerContainerRepeatKeyTest {

    @Test
    public void aKeyLeftOutIsOne() {
        assertEquals(Integer.valueOf(1), ImportDataRuleRunnerContainer.repeatKey(null));
        assertEquals(Integer.valueOf(1), ImportDataRuleRunnerContainer.repeatKey(""));
    }

    @Test
    public void aNumericKeyIsItsNumber() {
        assertEquals(Integer.valueOf(3), ImportDataRuleRunnerContainer.repeatKey("3"));
    }

    @Test
    public void aKeyThatIsNotAPositiveWholeNumberNamesNothing() {
        assertNull(ImportDataRuleRunnerContainer.repeatKey("abc"));
        assertNull(ImportDataRuleRunnerContainer.repeatKey("0"));
        assertNull(ImportDataRuleRunnerContainer.repeatKey("99999999999"));
    }
}
