/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Status;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.i18n.util.ResourceBundleProvider;

/**
 * Phase E.6 multi-role auth (2026-06-12) — pin the contract that
 * {@link StudyAdminAuthorization#userMayEditStudy(UserAccountBean, List, StudyBean)}
 * admits a user as long as ANY of their active bindings is authorized,
 * NOT only the binding that happens to be in the session attribute.
 *
 * <p>Before this fix a user with both Investigator and STUDYDIRECTOR
 * bindings on the same study would be 403'd whenever the session-
 * attribute role landed on Investigator (non-deterministic per
 * {@code MeApiController.setActiveStudy}). With the multi-binding gate
 * the same user passes.
 */
class StudyAdminAuthorizationTest {

    private static final int STUDY_ID = 42;

    /**
     * Pin a locale before any bean construction — {@code StudyUserRoleBean}'s
     * {@code setRole} cascades into {@code Term.getName()} which reads the
     * terms resource bundle, and that bundle resolves through the
     * thread-local map in {@link ResourceBundleProvider}. Without this
     * the per-thread {@code localeMap} entry is null and {@code getResBundle}
     * NPEs the moment we construct a {@code StudyUserRoleBean}.
     */
    @BeforeAll
    static void pinLocale() {
        ResourceBundleProvider.updateLocale(Locale.ENGLISH);
    }

    private static StudyBean topLevelStudy() {
        StudyBean s = new StudyBean();
        s.setId(STUDY_ID);
        s.setParentStudyId(0);
        return s;
    }

    private static UserAccountBean nonAdmin() {
        UserAccountBean u = new UserAccountBean();
        u.setId(7);
        u.setName("alice");
        return u;
    }

    private static StudyUserRoleBean bindingFor(Role role) {
        StudyUserRoleBean b = new StudyUserRoleBean();
        b.setRole(role);
        b.setStudyId(STUDY_ID);
        b.setStatus(Status.AVAILABLE);
        return b;
    }

    @Test
    void admitsWhenAnyBindingPassesEditGate() {
        boolean ok = StudyAdminAuthorization.userMayEditStudy(
                nonAdmin(),
                List.of(bindingFor(Role.INVESTIGATOR), bindingFor(Role.STUDYDIRECTOR)),
                topLevelStudy());
        assertThat(ok)
                .as("Investigator + STUDYDIRECTOR multi-binding — Director should win")
                .isTrue();
    }

    @Test
    void admitsWhenStudyDirectorIsFirstAndInvestigatorSecond() {
        boolean ok = StudyAdminAuthorization.userMayEditStudy(
                nonAdmin(),
                List.of(bindingFor(Role.STUDYDIRECTOR), bindingFor(Role.INVESTIGATOR)),
                topLevelStudy());
        assertThat(ok).isTrue();
    }

    @Test
    void admitsCoordinatorOnly() {
        boolean ok = StudyAdminAuthorization.userMayEditStudy(
                nonAdmin(),
                List.of(bindingFor(Role.COORDINATOR)),
                topLevelStudy());
        assertThat(ok).isTrue();
    }

    @Test
    void rejectsInvestigatorOnly() {
        boolean ok = StudyAdminAuthorization.userMayEditStudy(
                nonAdmin(),
                List.of(bindingFor(Role.INVESTIGATOR)),
                topLevelStudy());
        assertThat(ok).isFalse();
    }

    @Test
    void rejectsMonitorOnly() {
        boolean ok = StudyAdminAuthorization.userMayEditStudy(
                nonAdmin(),
                List.of(bindingFor(Role.MONITOR)),
                topLevelStudy());
        assertThat(ok).isFalse();
    }

    @Test
    void rejectsInvestigatorPlusMonitor() {
        boolean ok = StudyAdminAuthorization.userMayEditStudy(
                nonAdmin(),
                List.of(bindingFor(Role.INVESTIGATOR), bindingFor(Role.MONITOR)),
                topLevelStudy());
        assertThat(ok).isFalse();
    }

    @Test
    void ignoresNonAvailableBindings() {
        StudyUserRoleBean disabled = bindingFor(Role.STUDYDIRECTOR);
        disabled.setStatus(Status.DELETED);
        boolean ok = StudyAdminAuthorization.userMayEditStudy(
                nonAdmin(),
                List.of(bindingFor(Role.INVESTIGATOR), disabled),
                topLevelStudy());
        assertThat(ok)
                .as("Soft-deleted STUDYDIRECTOR binding must not grant access")
                .isFalse();
    }

    @Test
    void sysadminPassesEvenWithoutBindings() {
        UserAccountBean sysadmin = new UserAccountBean();
        sysadmin.setId(1);
        sysadmin.addUserType(at.ac.meduniwien.ophthalmology.libreclinica.bean.core.UserType.SYSADMIN);
        boolean ok = StudyAdminAuthorization.userMayEditStudy(
                sysadmin,
                List.of(),
                topLevelStudy());
        assertThat(ok).isTrue();
    }

    @Test
    void rejectsNullBindings() {
        boolean ok = StudyAdminAuthorization.userMayEditStudy(
                nonAdmin(), (List<StudyUserRoleBean>) null, topLevelStudy());
        assertThat(ok).isFalse();
    }

    @Test
    void rejectsWhenBindingStudyIdDoesNotMatch() {
        StudyUserRoleBean wrongStudy = bindingFor(Role.STUDYDIRECTOR);
        wrongStudy.setStudyId(99);
        boolean ok = StudyAdminAuthorization.userMayEditStudy(
                nonAdmin(), List.of(wrongStudy), topLevelStudy());
        assertThat(ok)
                .as("STUDYDIRECTOR binding on a different study must not leak")
                .isFalse();
    }
}
