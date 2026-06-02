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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
        return mockMvcFor(new UsersApiController(mockDataSource(),
                Mockito.mock(SiteVisibilityFilter.class),
                Mockito.mock(SecurityManager.class),
                Mockito.mock(AuthoritiesDao.class)));
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
}
