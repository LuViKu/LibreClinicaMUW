/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.bean.login;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

/**
 * Equality of a user account bean (2026-09-29).
 *
 * <p>{@code UserAccountBean} declared {@code equals(UserAccountBean)} (same
 * id), which overloads rather than overrides {@code equals(Object)}: the
 * double-data-entry owner check compared ids, while collections fell back to
 * the inherited class+active+id+name comparison. The override keeps the id
 * comparison and gives it to every caller.
 */
public class UserAccountBeanEqualityTest {

    private static UserAccountBean account(int id, String name) {
        UserAccountBean u = new UserAccountBean();
        u.setId(id);
        u.setName(name);
        return u;
    }

    @Test
    public void theSameAccountIsEqualHoweverItWasLoaded() {
        UserAccountBean fromSession = account(1000, "nurse1");
        UserAccountBean asOwner = account(1000, "");
        assertTrue(fromSession.equals(asOwner));
        assertTrue(fromSession.equals((Object) asOwner));
        assertTrue(asOwner.equals((Object) fromSession));
        assertEquals(fromSession.hashCode(), asOwner.hashCode());
        Set<UserAccountBean> set = new HashSet<>();
        set.add(fromSession);
        assertTrue(set.contains(asOwner));
    }

    @Test
    public void differentAccountsAreNotEqual() {
        assertFalse(account(1000, "nurse1").equals(account(1001, "nurse1")));
        assertFalse(account(1000, "nurse1").equals((Object) account(1001, "nurse1")));
    }

    @Test
    public void nothingEqualsNullOrAnotherKindOfObject() {
        UserAccountBean none = null;
        assertFalse(account(1000, "nurse1").equals(none));
        assertFalse(account(1000, "nurse1").equals((Object) Integer.valueOf(1000)));
    }
}
