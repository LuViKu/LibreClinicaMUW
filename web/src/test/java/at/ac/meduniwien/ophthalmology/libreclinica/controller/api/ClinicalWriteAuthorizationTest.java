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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.TreeSet;
import java.util.function.IntPredicate;

import javax.sql.DataSource;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpSession;

/**
 * The two rules {@link ClinicalWriteAuthorization} owns, over every legacy
 * role id, and how {@link ClinicalWriteAuthorization#anyRoleOnTheStudyMay}
 * reads them. The ITs send each rule a few sessions; this pins every cell,
 * the admin binding among them, in the default build.
 */
class ClinicalWriteAuthorizationTest {

    @Test
    void everyRoleButTheMonitorEntersData() {
        // admin, coordinator, director, Investigator, ra, ra2
        assertEquals(Set.of(1, 2, 3, 4, 5, 7), admitted(ClinicalWriteAuthorization::roleMayEnterData));
    }

    @Test
    void theDirectorCoordinatorMonitorAndAdminVerify() {
        assertEquals(Set.of(1, 2, 3, 6), admitted(ClinicalWriteAuthorization::roleMayVerifySdv));
    }

    @Test
    void theSessionsRoleIsEnoughWhenItIsAdmitted() {
        // No binding is read: the data source is never asked.
        DataSource untouched = Mockito.mock(DataSource.class);
        assertTrue(ClinicalWriteAuthorization.anyRoleOnTheStudyMay(
                sessionBoundAs(Role.MONITOR), untouched, ClinicalWriteAuthorization::roleMayVerifySdv));
        Mockito.verifyNoInteractions(untouched);
    }

    @Test
    void bindingsThatCannotBeReadRefuse() {
        assertFalse(ClinicalWriteAuthorization.anyRoleOnTheStudyMay(
                sessionBoundAs(Role.INVESTIGATOR), Mockito.mock(DataSource.class),
                ClinicalWriteAuthorization::roleMayVerifySdv));
    }

    @Test
    void noUserNoStudyRefuses() {
        MockHttpSession session = new MockHttpSession();
        assertFalse(ClinicalWriteAuthorization.anyRoleOnTheStudyMay(
                session, Mockito.mock(DataSource.class), ClinicalWriteAuthorization::roleMayVerifySdv));
    }

    /** The role ids 0 to 7 the rule admits. */
    private static Set<Integer> admitted(IntPredicate rule) {
        Set<Integer> admitted = new TreeSet<>();
        for (int roleId = 0; roleId <= 7; roleId++) {
            if (rule.test(roleId)) {
                admitted.add(roleId);
            }
        }
        return admitted;
    }

    private static MockHttpSession sessionBoundAs(Role role) {
        UserAccountBean user = new UserAccountBean();
        user.setId(7);
        user.setName("someone");
        StudyBean study = new StudyBean();
        study.setId(1);
        StudyUserRoleBean binding = new StudyUserRoleBean();
        binding.setRole(role);
        binding.setStudyId(1);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("userBean", user);
        session.setAttribute("study", study);
        session.setAttribute("userRole", binding);
        return session;
    }
}
