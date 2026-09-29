/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.bean.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Test;

/**
 * Equality of a {@link Term} — Status, Role, ResolutionStatus and the rest
 * (2026-09-29).
 *
 * <p>{@code Term} declared {@code equals(Term)} (id equality), which
 * overloads rather than overrides {@code equals(Object)}: calls with a
 * Term-typed argument compared ids, while collections, {@code Objects.equals}
 * and EL fell back to the inherited class+active+id+name comparison. The
 * override keeps the id comparison every typed call already made and gives
 * it to the untyped callers too.
 */
public class TermEqualityTest {

    @Test
    public void typedComparisonsKeepTheirResults() {
        assertTrue(Status.AVAILABLE.equals(Status.AVAILABLE));
        assertFalse(Status.AVAILABLE.equals(Status.UNAVAILABLE));
        assertTrue(Role.MONITOR.equals(Role.get(Role.MONITOR.getId())));
        // The id probe the subclasses' get(id) methods use.
        assertTrue(new Term(Status.LOCKED.getId(), "").equals(Status.LOCKED));
        assertSame(ResolutionStatus.CLOSED, ResolutionStatus.get(ResolutionStatus.CLOSED.getId()));
        assertTrue(Status.contains(Status.SIGNED.getId()));
    }

    @Test
    public void anUntypedComparisonAgreesWithTheTypedOne() {
        Object probe = new Term(Status.LOCKED.getId(), "");
        assertTrue(Status.LOCKED.equals(probe));
        assertTrue(probe.equals(Status.LOCKED));
        assertFalse(Status.AVAILABLE.equals((Object) new Term(Status.LOCKED.getId(), "")));
        assertTrue(List.of(Status.AVAILABLE, Status.LOCKED).contains(probe));
    }

    @Test
    public void equalTermsHashAlike() {
        Term probe = new Term(Status.LOCKED.getId(), "");
        assertEquals(Status.LOCKED.hashCode(), probe.hashCode());
        Set<Term> set = new HashSet<>();
        set.add(Status.LOCKED);
        assertTrue(set.contains(probe));
    }

    @Test
    public void nothingEqualsNullOrANonTerm() {
        Term nullTerm = null;
        assertFalse(Status.AVAILABLE.equals(nullTerm));
        assertFalse(Status.AVAILABLE.equals((Object) Integer.valueOf(Status.AVAILABLE.getId())));
    }
}
