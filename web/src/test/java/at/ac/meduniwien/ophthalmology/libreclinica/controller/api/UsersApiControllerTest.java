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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import at.ac.meduniwien.ophthalmology.libreclinica.config.SsoProperties;
import at.ac.meduniwien.ophthalmology.libreclinica.core.SecurityManager;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.AuthoritiesDao;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Phase E.5 A2 — MockMvc IT pinning the {@link UsersApiController}
 * session-guard contract surface.
 *
 * <p>What this pins:
 * <ul>
 *   <li>{@code GET /api/v1/users} → {@code 401} anonymous /
 *       {@code 400} no active study.</li>
 * </ul>
 *
 * <p>Response-shape verification (one row per user × role binding;
 * filter params {@code role=…}, {@code siteOid=…}, {@code active=…})
 * needs Testcontainers Postgres — deferred.
 */
class UsersApiControllerTest extends AbstractApiControllerTest {

    private MockMvc mockMvcWith() {
        // Phase E.6: SsoProperties default ctor wires up the
        // shibboleth-meduniwien provider name — sufficient for the
        // shape tests below, which only need a non-null provider when
        // the SSO branch is exercised.
        return mockMvcFor(new UsersApiController(mockDataSource(),
                Mockito.mock(SiteVisibilityFilter.class),
                Mockito.mock(SecurityManager.class),
                Mockito.mock(AuthoritiesDao.class),
                new SsoProperties()));
    }

    @Test
    void listReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(get("/api/v1/users")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listReturns400WhenNoActiveStudy() throws Exception {
        mockMvcWith().perform(get("/api/v1/users")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSessionWithoutStudy(1, "root")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("No active study")));
    }

    /* ---------------------------------------------------------------------- */
    /* POST /api/v1/users (Phase E A7.1 — create new user)                    */
    /* ---------------------------------------------------------------------- */

    @Test
    void createReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(post("/api/v1/users")
                .contentType("application/json")
                .content("{\"username\":\"newuser\"}")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createReturns400WhenNoActiveStudy() throws Exception {
        mockMvcWith().perform(post("/api/v1/users")
                .contentType("application/json")
                .content("{\"username\":\"newuser\"}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSessionWithoutStudy(1, "root")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("No active study")));
    }

    @Test
    void createReturns403WhenNonSysadminAttempts() throws Exception {
        // authenticatedSessionWithRole builds a session whose userBean
        // is a regular USER (not sysadmin); UserAdminAuthorization
        // refuses → 403.
        mockMvcWith().perform(post("/api/v1/users")
                .contentType("application/json")
                .content("{\"username\":\"newuser\"}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSessionWithRole(2, "physician", 1, "S_DEFAULTS1",
                                "Default Study",
                                at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role.INVESTIGATOR, 1)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message")
                        .value(containsString("sysadmin only")));
    }

    @Test
    void createReturns400OnMissingBody() throws Exception {
        mockMvcWith().perform(post("/api/v1/users")
                .contentType("application/json")
                .content("")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSysadminSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createReturns400OnMissingFields() throws Exception {
        mockMvcWith().perform(post("/api/v1/users")
                .contentType("application/json")
                .content("{}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSysadminSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'username')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'firstName')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'lastName')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'email')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'institutionalAffiliation')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'studyId')]").exists());
    }

    @Test
    void createReturns400OnBadEmail() throws Exception {
        mockMvcWith().perform(post("/api/v1/users")
                .contentType("application/json")
                .content("{\"username\":\"newuser\",\"firstName\":\"A\",\"lastName\":\"B\","
                        + "\"email\":\"not-an-email\",\"institutionalAffiliation\":\"Inst\","
                        + "\"studyId\":1,\"role\":\"Investigator\"}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSysadminSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'email')]").exists());
    }

    @Test
    void createReturns400OnBadUsernameRegex() throws Exception {
        mockMvcWith().perform(post("/api/v1/users")
                .contentType("application/json")
                .content("{\"username\":\"new user!\",\"firstName\":\"A\",\"lastName\":\"B\","
                        + "\"email\":\"a@b.co\",\"institutionalAffiliation\":\"Inst\","
                        + "\"studyId\":1,\"role\":\"Investigator\"}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSysadminSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'username')]").exists());
    }

    @Test
    void createReturns400OnUnknownRole() throws Exception {
        mockMvcWith().perform(post("/api/v1/users")
                .contentType("application/json")
                .content("{\"username\":\"newuser\",\"firstName\":\"A\",\"lastName\":\"B\","
                        + "\"email\":\"a@b.co\",\"institutionalAffiliation\":\"Inst\","
                        + "\"studyId\":1,\"role\":\"Wizard\"}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSysadminSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'role')]").exists());
    }

    @Test
    void createReturns400OnLdapUserSource() throws Exception {
        mockMvcWith().perform(post("/api/v1/users")
                .contentType("application/json")
                .content("{\"username\":\"newuser\",\"firstName\":\"A\",\"lastName\":\"B\","
                        + "\"email\":\"a@b.co\",\"institutionalAffiliation\":\"Inst\","
                        + "\"studyId\":1,\"role\":\"Investigator\",\"userSource\":\"ldap\"}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSysadminSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'userSource')]").exists());
    }

    /* ---------------------------------------------------------------------- */
    /* SSO-bound invite (Phase E.6 — DR-014 follow-up)                        */
    /* ---------------------------------------------------------------------- */

    @Test
    void createReturns400OnExternalIdMissingAtSign() throws Exception {
        // eppn shape rule: must contain '@'. A bare username here would
        // never match a SAML assertion, so we refuse server-side before
        // a row is written.
        mockMvcWith().perform(post("/api/v1/users")
                .contentType("application/json")
                .content("{\"username\":\"newuser\",\"firstName\":\"A\",\"lastName\":\"B\","
                        + "\"email\":\"a@b.co\",\"institutionalAffiliation\":\"Inst\","
                        + "\"studyId\":1,\"role\":\"Investigator\","
                        + "\"externalId\":\"justausername\"}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSysadminSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'externalId')]").exists());
    }

    @Test
    void createReturns400OnExternalIdTooLong() throws Exception {
        // 256 chars — one over the schema column limit (varchar(255)).
        String tooLong = "a".repeat(250) + "@x.io"; // 255 chars total of valid eppn... add one more
        tooLong = "x" + tooLong; // 256 chars
        mockMvcWith().perform(post("/api/v1/users")
                .contentType("application/json")
                .content("{\"username\":\"newuser\",\"firstName\":\"A\",\"lastName\":\"B\","
                        + "\"email\":\"a@b.co\",\"institutionalAffiliation\":\"Inst\","
                        + "\"studyId\":1,\"role\":\"Investigator\","
                        + "\"externalId\":\"" + tooLong + "\"}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSysadminSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'externalId')]").exists());
    }

    @Test
    void createBlankExternalIdIsAcceptedAsLocal() throws Exception {
        // Whitespace-only externalId should NOT trip the externalId
        // shape rule — it's treated as "operator left the field
        // empty" → local account path. We don't have the DAO mock to
        // assert the happy path completes, but we DO want to assert
        // there's no externalId error in the validation bag — instead
        // the request fails downstream at DAO time (which here surfaces
        // as some other error, not externalId). The cheapest assertion
        // is: validation pass for the externalId field specifically.
        mockMvcWith().perform(post("/api/v1/users")
                .contentType("application/json")
                .content("{\"username\":\"newuser\",\"firstName\":\"A\",\"lastName\":\"B\","
                        + "\"email\":\"a@b.co\",\"institutionalAffiliation\":\"Inst\","
                        + "\"studyId\":1,\"role\":\"Investigator\","
                        + "\"externalId\":\"   \"}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSysadminSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                // Whatever status comes back, no externalId-shape error.
                .andExpect(jsonPath("$.errors[?(@.field == 'externalId')]").doesNotExist());
    }

    /* ---------------------------------------------------------------------- */
    /* UserAdminAuthorization — unit coverage                                 */
    /* ---------------------------------------------------------------------- */

    @Test
    void userAdminAuth_NullSubjectIsForbidden() {
        org.junit.jupiter.api.Assertions.assertFalse(
                UserAdminAuthorization.roleMayAdministerUsers(null));
    }

    @Test
    void userAdminAuth_SysadminIsPermitted() {
        at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean ub =
                new at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean();
        ub.setId(1);
        ub.addUserType(at.ac.meduniwien.ophthalmology.libreclinica.bean.core.UserType.SYSADMIN);
        org.junit.jupiter.api.Assertions.assertTrue(
                UserAdminAuthorization.roleMayAdministerUsers(ub));
    }

    @Test
    void userAdminAuth_RegularUserIsForbidden() {
        at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean ub =
                new at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean();
        ub.setId(1);
        ub.addUserType(at.ac.meduniwien.ophthalmology.libreclinica.bean.core.UserType.USER);
        org.junit.jupiter.api.Assertions.assertFalse(
                UserAdminAuthorization.roleMayAdministerUsers(ub));
    }

    @Test
    void userAdminAuth_SiteLevelRoleLegality() {
        at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean parent =
                new at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean();
        parent.setParentStudyId(0);
        at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean site =
                new at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean();
        site.setParentStudyId(1);

        // Parent study — all roles legal.
        org.junit.jupiter.api.Assertions.assertTrue(UserAdminAuthorization.roleAssignmentIsLegal(
                at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role.COORDINATOR, parent));
        org.junit.jupiter.api.Assertions.assertTrue(UserAdminAuthorization.roleAssignmentIsLegal(
                at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role.STUDYDIRECTOR, parent));
        org.junit.jupiter.api.Assertions.assertTrue(UserAdminAuthorization.roleAssignmentIsLegal(
                at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role.INVESTIGATOR, parent));

        // Site — Coordinator + StudyDirector illegal.
        org.junit.jupiter.api.Assertions.assertFalse(UserAdminAuthorization.roleAssignmentIsLegal(
                at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role.COORDINATOR, site));
        org.junit.jupiter.api.Assertions.assertFalse(UserAdminAuthorization.roleAssignmentIsLegal(
                at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role.STUDYDIRECTOR, site));
        org.junit.jupiter.api.Assertions.assertTrue(UserAdminAuthorization.roleAssignmentIsLegal(
                at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role.INVESTIGATOR, site));
        org.junit.jupiter.api.Assertions.assertTrue(UserAdminAuthorization.roleAssignmentIsLegal(
                at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role.MONITOR, site));
    }

    /* ---------------------------------------------------------------------- */
    /* PUT /api/v1/users/{username}  (Phase E A7.2 — edit profile)            */
    /* ---------------------------------------------------------------------- */

    @Test
    void updateReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(put("/api/v1/users/somebody")
                .contentType("application/json")
                .content("{}")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void updateReturns400WhenNoActiveStudy() throws Exception {
        mockMvcWith().perform(put("/api/v1/users/somebody")
                .contentType("application/json")
                .content("{}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSessionWithoutStudy(1, "root")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("No active study")));
    }

    @Test
    void updateReturns403WhenNonSysadminAttempts() throws Exception {
        mockMvcWith().perform(put("/api/v1/users/somebody")
                .contentType("application/json")
                .content("{}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSessionWithRole(2, "physician", 1, "S_DEFAULTS1",
                                "Default Study",
                                at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role.INVESTIGATOR, 1)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message")
                        .value(containsString("sysadmin only")));
    }

    @Test
    void updateReturns400OnMissingBody() throws Exception {
        mockMvcWith().perform(put("/api/v1/users/somebody")
                .contentType("application/json")
                .content("")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSysadminSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateReturns400OnBadEmail() throws Exception {
        mockMvcWith().perform(put("/api/v1/users/somebody")
                .contentType("application/json")
                .content("{\"email\":\"not-an-email\"}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSysadminSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'email')]").exists());
    }

    @Test
    void updateReturns400OnLongFirstName() throws Exception {
        // 51 chars — one over the legacy 50-char limit.
        String tooLong = "a".repeat(51);
        mockMvcWith().perform(put("/api/v1/users/somebody")
                .contentType("application/json")
                .content("{\"firstName\":\"" + tooLong + "\"}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSysadminSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'firstName')]").exists());
    }

    @Test
    void updateReturns400OnBlankFirstName() throws Exception {
        // Empty string — different from "field omitted" (null).
        mockMvcWith().perform(put("/api/v1/users/somebody")
                .contentType("application/json")
                .content("{\"firstName\":\"   \"}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSysadminSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'firstName')]").exists());
    }

    @Test
    void updateReturns400OnUnknownUserType() throws Exception {
        mockMvcWith().perform(put("/api/v1/users/somebody")
                .contentType("application/json")
                .content("{\"userType\":\"SUPERUSER\"}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSysadminSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'userType')]").exists());
    }

    /* ---------------------------------------------------------------------- */
    /* POST /api/v1/users/{username}/{disable,restore}                        */
    /*   (Phase E A7.3 — lifecycle)                                           */
    /* ---------------------------------------------------------------------- */

    @Test
    void disableReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(post("/api/v1/users/somebody/disable")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void disableReturns400WhenNoActiveStudy() throws Exception {
        mockMvcWith().perform(post("/api/v1/users/somebody/disable")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSessionWithoutStudy(1, "root")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("No active study")));
    }

    @Test
    void disableReturns403WhenNonSysadminAttempts() throws Exception {
        mockMvcWith().perform(post("/api/v1/users/somebody/disable")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSessionWithRole(2, "physician", 1, "S_DEFAULTS1",
                                "Default Study",
                                at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role.INVESTIGATOR, 1)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message")
                        .value(containsString("sysadmin only")));
    }

    @Test
    void restoreReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(post("/api/v1/users/somebody/restore")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void restoreReturns400WhenNoActiveStudy() throws Exception {
        mockMvcWith().perform(post("/api/v1/users/somebody/restore")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSessionWithoutStudy(1, "root")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("No active study")));
    }

    @Test
    void restoreReturns403WhenNonSysadminAttempts() throws Exception {
        mockMvcWith().perform(post("/api/v1/users/somebody/restore")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSessionWithRole(2, "physician", 1, "S_DEFAULTS1",
                                "Default Study",
                                at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role.INVESTIGATOR, 1)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message")
                        .value(containsString("sysadmin only")));
    }

    /* ---------------------------------------------------------------------- */
    /* POST /api/v1/users/{username}/resetPassword                            */
    /*   (Phase E A7.4 — admin password reset)                                */
    /* ---------------------------------------------------------------------- */

    @Test
    void resetPasswordReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(post("/api/v1/users/somebody/resetPassword")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void resetPasswordReturns400WhenNoActiveStudy() throws Exception {
        mockMvcWith().perform(post("/api/v1/users/somebody/resetPassword")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSessionWithoutStudy(1, "root")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("No active study")));
    }

    @Test
    void resetPasswordReturns403WhenNonSysadminAttempts() throws Exception {
        mockMvcWith().perform(post("/api/v1/users/somebody/resetPassword")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSessionWithRole(2, "physician", 1, "S_DEFAULTS1",
                                "Default Study",
                                at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role.INVESTIGATOR, 1)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message")
                        .value(containsString("sysadmin only")));
    }

    /* ---------------------------------------------------------------------- */
    /* POST /api/v1/users/{username}/unlock                                   */
    /*   (Phase E.6 unlock-user — clear lock + issue fresh OTP)               */
    /* ---------------------------------------------------------------------- */

    @Test
    void unlockReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(post("/api/v1/users/somebody/unlock")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unlockReturns400WhenNoActiveStudy() throws Exception {
        mockMvcWith().perform(post("/api/v1/users/somebody/unlock")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSessionWithoutStudy(1, "root")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("No active study")));
    }

    @Test
    void unlockReturns403WhenNonSysadminAttempts() throws Exception {
        mockMvcWith().perform(post("/api/v1/users/somebody/unlock")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSessionWithRole(2, "physician", 1, "S_DEFAULTS1",
                                "Default Study",
                                at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role.INVESTIGATOR, 1)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message")
                        .value(containsString("sysadmin only")));
    }

    /* ---------------------------------------------------------------------- */
    /* GET/POST/PUT/DELETE /api/v1/users/{username}/roles[/{studyId}]         */
    /*   (Phase E A7.5 — role assignments)                                   */
    /* ---------------------------------------------------------------------- */

    @Test
    void listRolesReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(get("/api/v1/users/somebody/roles")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listRolesReturns403WhenNonSysadminAttempts() throws Exception {
        mockMvcWith().perform(get("/api/v1/users/somebody/roles")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSessionWithRole(2, "physician", 1, "S_DEFAULTS1",
                                "Default Study",
                                at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role.INVESTIGATOR, 1)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message")
                        .value(containsString("sysadmin only")));
    }

    @Test
    void grantRoleReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(post("/api/v1/users/somebody/roles")
                .contentType("application/json")
                .content("{\"studyOid\":\"S_DEFAULTS1\",\"role\":\"Investigator\"}")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void grantRoleReturns403WhenNonSysadminAttempts() throws Exception {
        mockMvcWith().perform(post("/api/v1/users/somebody/roles")
                .contentType("application/json")
                .content("{\"studyOid\":\"S_DEFAULTS1\",\"role\":\"Investigator\"}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSessionWithRole(2, "physician", 1, "S_DEFAULTS1",
                                "Default Study",
                                at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role.INVESTIGATOR, 1)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message")
                        .value(containsString("sysadmin only")));
    }

    @Test
    void grantRoleReturns400OnMissingBody() throws Exception {
        mockMvcWith().perform(post("/api/v1/users/somebody/roles")
                .contentType("application/json")
                .content("")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSysadminSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void grantRoleReturns400OnMissingFields() throws Exception {
        mockMvcWith().perform(post("/api/v1/users/somebody/roles")
                .contentType("application/json")
                .content("{}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSysadminSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'studyOid')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'role')]").exists());
    }

    @Test
    void grantRoleReturns400OnUnknownRole() throws Exception {
        mockMvcWith().perform(post("/api/v1/users/somebody/roles")
                .contentType("application/json")
                .content("{\"studyOid\":\"S_DEFAULTS1\",\"role\":\"Wizard\"}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSysadminSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'role')]").exists());
    }

    @Test
    void updateRoleReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(put("/api/v1/users/somebody/roles/S_DEFAULTS1")
                .contentType("application/json")
                .content("{\"role\":\"Investigator\"}")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void updateRoleReturns403WhenNonSysadminAttempts() throws Exception {
        mockMvcWith().perform(put("/api/v1/users/somebody/roles/S_DEFAULTS1")
                .contentType("application/json")
                .content("{\"role\":\"Investigator\"}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSessionWithRole(2, "physician", 1, "S_DEFAULTS1",
                                "Default Study",
                                at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role.INVESTIGATOR, 1)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message")
                        .value(containsString("sysadmin only")));
    }

    @Test
    void updateRoleReturns400OnMissingRole() throws Exception {
        mockMvcWith().perform(put("/api/v1/users/somebody/roles/S_DEFAULTS1")
                .contentType("application/json")
                .content("{}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSysadminSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'role')]").exists());
    }

    @Test
    void revokeRoleReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(delete("/api/v1/users/somebody/roles/S_DEFAULTS1")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void revokeRoleReturns403WhenNonSysadminAttempts() throws Exception {
        mockMvcWith().perform(delete("/api/v1/users/somebody/roles/S_DEFAULTS1")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSessionWithRole(2, "physician", 1, "S_DEFAULTS1",
                                "Default Study",
                                at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role.INVESTIGATOR, 1)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message")
                        .value(containsString("sysadmin only")));
    }
}
