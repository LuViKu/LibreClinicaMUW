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
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.UserType;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.i18n.util.ResourceBundleProvider;

/**
 * Who may remove an event CRF: legacy {@code RemoveEventCRFServlet#mayProceed}
 * admits a system administrator, the study director (Data Manager) and the
 * study coordinator, and no one else.
 */
class EventCrfRemoveAuthorizationTest {

    @BeforeAll
    static void pinLocale() {
        ResourceBundleProvider.updateLocale(Locale.ENGLISH);
    }

    private static UserAccountBean user(boolean sysAdmin) {
        UserAccountBean u = new UserAccountBean();
        u.setId(7);
        u.setName("alice");
        if (sysAdmin) u.addUserType(UserType.SYSADMIN);
        return u;
    }

    @Test
    void aSystemAdministratorMayRemoveWithoutAStudyRole() {
        assertThat(EventCrfRemoveAuthorization.roleMayRemove(user(true), 0)).isTrue();
    }

    @Test
    void theDataManagerAndTheCoordinatorMayRemove() {
        assertThat(EventCrfRemoveAuthorization.roleMayRemove(user(false), Role.STUDYDIRECTOR.getId())).isTrue();
        assertThat(EventCrfRemoveAuthorization.roleMayRemove(user(false), Role.COORDINATOR.getId())).isTrue();
    }

    /** The study-level admin role is not the system administrator: legacy does not admit it either. */
    @Test
    void noOtherStudyRoleMayRemove() {
        for (Role role : List.of(Role.ADMIN, Role.INVESTIGATOR, Role.RESEARCHASSISTANT, Role.RESEARCHASSISTANT2,
                Role.MONITOR, Role.INVALID)) {
            assertThat(EventCrfRemoveAuthorization.roleMayRemove(user(false), role.getId()))
                    .as(role.getName()).isFalse();
        }
        assertThat(EventCrfRemoveAuthorization.roleMayRemove(null, Role.MONITOR.getId())).isFalse();
    }
}
