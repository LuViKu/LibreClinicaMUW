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
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Status;

/**
 * Which of several roles on one study {@link UserAccountBean#getRoleByStudy}
 * answers with. The login and every legacy page bind the session role from
 * it, so an account holding Study Director and Monitor on a study used to be
 * signed in as whichever row the database returned last, in practice the
 * newest grant, and as a Monitor saw no participant at a site.
 */
public class UserAccountBeanRoleByStudyTest {

    private static final int STUDY = 7;
    private static final int OTHER_STUDY = 9;

    @Test
    public void theStrongestRoleStandsWhateverTheRowOrder() {
        UserAccountBean newestLast = account(
                binding(STUDY, Role.STUDYDIRECTOR, Status.AVAILABLE),
                binding(STUDY, Role.COORDINATOR, Status.AVAILABLE),
                binding(STUDY, Role.MONITOR, Status.AVAILABLE));
        UserAccountBean strongestLast = account(
                binding(STUDY, Role.MONITOR, Status.AVAILABLE),
                binding(STUDY, Role.COORDINATOR, Status.AVAILABLE),
                binding(STUDY, Role.STUDYDIRECTOR, Status.AVAILABLE));

        assertEquals(Role.STUDYDIRECTOR, newestLast.getRoleByStudy(STUDY).getRole());
        assertEquals(Role.STUDYDIRECTOR, strongestLast.getRoleByStudy(STUDY).getRole());
    }

    @Test
    public void studyDirectorStandsBeforeCoordinatorAsInTheStudyPicker() {
        // Role.max would pick the coordinator (lower legacy id); the study
        // picker ranks the director first, and the login must agree with it.
        UserAccountBean account = account(
                binding(STUDY, Role.STUDYDIRECTOR, Status.AVAILABLE),
                binding(STUDY, Role.COORDINATOR, Status.AVAILABLE));

        assertEquals(Role.STUDYDIRECTOR, account.getRoleByStudy(STUDY).getRole());
    }

    @Test
    public void aRemovedRoleNeverHidesALiveOne() {
        UserAccountBean account = account(
                binding(STUDY, Role.MONITOR, Status.AVAILABLE),
                binding(STUDY, Role.STUDYDIRECTOR, Status.DELETED));

        assertEquals(Role.MONITOR, account.getRoleByStudy(STUDY).getRole());
    }

    @Test
    public void onlyRemovedRolesLeaveNoRole() {
        UserAccountBean account = account(
                binding(STUDY, Role.STUDYDIRECTOR, Status.DELETED),
                binding(STUDY, Role.MONITOR, Status.AUTO_DELETED));

        assertTrue(account.getRoleByStudy(STUDY).isInvalid());
    }

    @Test
    public void eachStudyKeepsItsOwnRole() {
        UserAccountBean account = account(
                binding(STUDY, Role.MONITOR, Status.AVAILABLE),
                binding(OTHER_STUDY, Role.INVESTIGATOR, Status.AVAILABLE),
                binding(STUDY, Role.STUDYDIRECTOR, Status.AVAILABLE));

        assertEquals(Role.STUDYDIRECTOR, account.getRoleByStudy(STUDY).getRole());
        assertEquals(Role.INVESTIGATOR, account.getRoleByStudy(OTHER_STUDY).getRole());
    }

    @Test
    public void everyRowIsStillListed() {
        UserAccountBean account = account(
                binding(STUDY, Role.STUDYDIRECTOR, Status.AVAILABLE),
                binding(STUDY, Role.MONITOR, Status.AVAILABLE));

        assertEquals(2, account.getRoles().size());
    }

    private static UserAccountBean account(StudyUserRoleBean... bindings) {
        UserAccountBean account = new UserAccountBean();
        account.setRoles(new ArrayList<>(List.of(bindings)));
        return account;
    }

    private static StudyUserRoleBean binding(int studyId, Role role, Status status) {
        StudyUserRoleBean binding = new StudyUserRoleBean();
        binding.setStudyId(studyId);
        binding.setRole(role);
        binding.setStatus(status);
        return binding;
    }
}
