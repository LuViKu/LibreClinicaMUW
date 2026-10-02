/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.core.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link Pair#equals} must compare null components as values: a pair whose
 * component is null is simply not equal to one whose component is set.
 */
public class PairTest {

    @Test
    public void aNullFirstComponentIsNotEqualToASetOne() {
        assertFalse(new Pair<String, String>(null, "b").equals(new Pair<>("a", "b")));
    }

    @Test
    public void aNullSecondComponentIsNotEqualToASetOne() {
        assertFalse(new Pair<String, String>("a", null).equals(new Pair<>("a", "b")));
    }

    @Test
    public void pairsWithTheSameComponentsAreEqual() {
        assertTrue(new Pair<>("a", "b").equals(new Pair<>("a", "b")));
        assertTrue(new Pair<String, String>(null, null).equals(new Pair<String, String>(null, null)));
        assertEquals(new Pair<>("a", 1).hashCode(), new Pair<>("a", 1).hashCode());
    }
}
