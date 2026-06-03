/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Phase E.6 — MockMvc IT pinning the {@code POST /pages/api/v1/me/password}
 * session-guard + validation surface restored to parity with the legacy
 * {@code SecureController.passwdTimeOut()} / {@code MainMenuServlet}
 * forced-rotation gate.
 *
 * <p>Pure-guard tests only: the happy-path UPDATE requires a real DB plus
 * the Spring-wired {@code SecurityManager} bean, and rides on the same
 * Testcontainers-backed IT envelope the other adapter tests defer to.
 * What this test pins:
 *
 * <ul>
 *   <li>401 when no userBean is in session;</li>
 *   <li>400 + per-field errors on empty body and partial bodies
 *       (current/new missing, repeat mismatch, no-op when new==current);</li>
 *   <li>403 when the session user is LDAP-bound or SSO-bound (their
 *       credential lifecycle is owned upstream per DR-014).</li>
 * </ul>
 */
class MePasswordChangeTest extends AbstractApiControllerTest {

    private MockMvc mockMvcWith() {
        return mockMvcFor(new MeApiController(mockDataSource()));
    }

    @Test
    void changePasswordReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(post("/api/v1/me/password")
                .contentType("application/json")
                .content("{\"currentPassword\":\"x\",\"newPassword\":\"y\",\"newPasswordRepeat\":\"y\"}")
                .session((MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(containsString("Not authenticated")));
    }

    @Test
    void changePasswordReturns400OnEmptyBody() throws Exception {
        mockMvcWith().perform(post("/api/v1/me/password")
                .contentType("application/json")
                .content("")
                .session((MockHttpSession) authenticatedSessionWithoutStudy(1, "root")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void changePasswordReturns400WhenCurrentMissing() throws Exception {
        mockMvcWith().perform(post("/api/v1/me/password")
                .contentType("application/json")
                .content("{\"currentPassword\":\"\",\"newPassword\":\"NewPass123!\",\"newPasswordRepeat\":\"NewPass123!\"}")
                .session((MockHttpSession) authenticatedSessionWithoutStudy(1, "root")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field=='currentPassword')]").exists());
    }

    @Test
    void changePasswordReturns400WhenNewMissing() throws Exception {
        mockMvcWith().perform(post("/api/v1/me/password")
                .contentType("application/json")
                .content("{\"currentPassword\":\"x\",\"newPassword\":\"\",\"newPasswordRepeat\":\"\"}")
                .session((MockHttpSession) authenticatedSessionWithoutStudy(1, "root")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field=='newPassword')]").exists());
    }

    @Test
    void changePasswordReturns400WhenRepeatDoesNotMatch() throws Exception {
        mockMvcWith().perform(post("/api/v1/me/password")
                .contentType("application/json")
                .content("{\"currentPassword\":\"x\",\"newPassword\":\"NewPass123!\",\"newPasswordRepeat\":\"OopsTypo!\"}")
                .session((MockHttpSession) authenticatedSessionWithoutStudy(1, "root")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field=='newPasswordRepeat')]").exists());
    }

    @Test
    void changePasswordReturns400WhenNewEqualsCurrent() throws Exception {
        mockMvcWith().perform(post("/api/v1/me/password")
                .contentType("application/json")
                .content("{\"currentPassword\":\"SamePass1!\",\"newPassword\":\"SamePass1!\",\"newPasswordRepeat\":\"SamePass1!\"}")
                .session((MockHttpSession) authenticatedSessionWithoutStudy(1, "root")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field=='newPassword')]").exists());
    }

    @Test
    void changePasswordReturns403ForLdapUser() throws Exception {
        MockHttpSession session = new MockHttpSession();
        UserAccountBean ub = new UserAccountBean();
        ub.setId(7);
        ub.setName("ldap-user");
        // UserAccountBean.isLdapUser() returns true when passwd == "*"
        // (long-standing legacy sentinel — see UserAccountBean line 545).
        ub.setPasswd("*");
        session.setAttribute("userBean", ub);

        mockMvcWith().perform(post("/api/v1/me/password")
                .contentType("application/json")
                .content("{\"currentPassword\":\"x\",\"newPassword\":\"NewPass123!\",\"newPasswordRepeat\":\"NewPass123!\"}")
                .session(session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(containsString("identity provider")));
    }

    @Test
    void changePasswordReturns403ForSsoUser() throws Exception {
        MockHttpSession session = new MockHttpSession();
        UserAccountBean ub = new UserAccountBean();
        ub.setId(8);
        ub.setName("sso-user");
        ub.setExternalId("eppn-sso-user@meduniwien.ac.at");
        ub.setExternalIdProvider("shibboleth-meduniwien");
        session.setAttribute("userBean", ub);

        mockMvcWith().perform(post("/api/v1/me/password")
                .contentType("application/json")
                .content("{\"currentPassword\":\"x\",\"newPassword\":\"NewPass123!\",\"newPasswordRepeat\":\"NewPass123!\"}")
                .session(session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(containsString("identity provider")));
    }
}
