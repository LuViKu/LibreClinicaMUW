/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

/**
 * Equality of a study intervention (2026-09-29).
 *
 * <p>{@code equals(InterventionBean)} (type and name, ignoring case)
 * overloaded rather than overrode {@code equals(Object)}, and
 * {@code hashCode} returned a per-instance counter, so the two could never
 * agree. Nothing compares interventions today; the pair now states the
 * intended value equality consistently.
 */
public class InterventionBeanTest {

    @Test
    public void theSameTypeAndNameInAnyCaseAreEqualAndHashAlike() {
        InterventionBean a = new InterventionBean("Drug", "Ranibizumab");
        InterventionBean b = new InterventionBean("drug", "RANIBIZUMAB");
        assertTrue(a.equals(b));
        assertTrue(a.equals((Object) b));
        assertEquals(a.hashCode(), b.hashCode());
        Set<InterventionBean> set = new HashSet<>();
        set.add(a);
        assertTrue(set.contains(b));
    }

    @Test
    public void aDifferentTypeOrNameIsNotEqual() {
        InterventionBean a = new InterventionBean("Drug", "Ranibizumab");
        assertFalse(a.equals(new InterventionBean("Device", "Ranibizumab")));
        assertFalse(a.equals(new InterventionBean("Drug", "Aflibercept")));
    }

    @Test
    public void nullsNeitherThrowNorMatch() {
        InterventionBean a = new InterventionBean("Drug", "Ranibizumab");
        InterventionBean none = null;
        assertFalse(a.equals(none));
        assertFalse(a.equals((Object) "Drug/Ranibizumab"));
        InterventionBean unnamed = new InterventionBean("Drug", null);
        assertFalse(unnamed.equals(a));
        assertFalse(a.equals(unnamed));
        assertTrue(unnamed.equals(new InterventionBean("drug", null)));
        assertEquals(unnamed.hashCode(), new InterventionBean("drug", null).hashCode());
    }
}
