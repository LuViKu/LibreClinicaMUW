/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.UserType;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;

/**
 * The heritage account-creation API. Its admin check read
 * {@code !active && (...)}, which every active account passed, so any
 * logged-in user could create a sysadmin and receive its password.
 */
class UserAccountControllerTest {

    private static final String BODY = "{\"username\":\"escalated\",\"fName\":\"E\",\"lName\":\"S\","
            + "\"institution\":\"X\",\"email\":\"e@example.org\",\"study_name\":\"Any\","
            + "\"role_name\":\"Data Manager\",\"user_type\":\"system administrator\",\"authorize_soap\":\"false\"}";

    private static UserAccountBean account(UserType type, boolean active) {
        UserAccountBean ub = new UserAccountBean();
        ub.setId(7);
        ub.setName("caller");
        ub.addUserType(type);
        ub.setActive(active);
        return ub;
    }

    @Test
    void onlyAnActiveSystemOrTechnicalAdministratorMayCreateAccounts() {
        assertFalse(UserAccountController.mayCreateAccounts(null));
        assertFalse(UserAccountController.mayCreateAccounts(account(UserType.USER, true)),
                "an ordinary active user passed the heritage check");
        assertFalse(UserAccountController.mayCreateAccounts(account(UserType.SYSADMIN, false)));
        assertTrue(UserAccountController.mayCreateAccounts(account(UserType.SYSADMIN, true)));
        assertTrue(UserAccountController.mayCreateAccounts(account(UserType.TECHADMIN, true)));
    }

    @Test
    void anOrdinaryLoggedInUserIsRefusedBeforeAnythingIsCreated() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new UserAccountController()).build();
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("userBean", account(UserType.USER, true));

        mvc.perform(post("/auth/api/v1/createuseraccount").session(session)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    void aRequestWithoutASessionUserIsUnauthorized() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new UserAccountController()).build();

        mvc.perform(post("/auth/api/v1/createuseraccount")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
    }
}
