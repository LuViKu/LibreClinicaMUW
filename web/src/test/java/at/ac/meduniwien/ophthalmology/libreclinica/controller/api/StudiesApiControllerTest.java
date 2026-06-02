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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Locale;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import at.ac.meduniwien.ophthalmology.libreclinica.i18n.util.ResourceBundleProvider;

/**
 * Phase E.5 A2 — MockMvc IT pinning the {@link StudiesApiController}
 * session-guard contract surface.
 *
 * <p>{@code GET /api/v1/studies} must:
 * <ul>
 *   <li>return {@code 401} when there is no {@code userBean} in session.</li>
 * </ul>
 *
 * <p>Happy-path coverage (array shape with the studies the user has
 * a role on) requires a real DataSource — out of scope for the
 * session-guard cut and deferred to the Testcontainers Postgres infra.
 */
class StudiesApiControllerTest extends AbstractApiControllerTest {

    @BeforeAll
    static void bindLocale() {
        // StudyUserRoleBean.setRole + Role.getName trigger a
        // ResourceBundle lookup against the current thread's locale.
        // Most tests reach this path via authenticatedSessionWithRole
        // (which binds it internally), but the unit-level
        // StudyAdminAuthorization tests construct beans directly —
        // bind once for the class.
        ResourceBundleProvider.updateLocale(Locale.ENGLISH);
    }

    private MockMvc mockMvcWith() {
        return mockMvcFor(new StudiesApiController(mockDataSource()));
    }

    @Test
    void listReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(get("/api/v1/studies")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message")
                        .value(containsString("Not authenticated")));
    }

    /* ---------------------------------------------------------------------- */
    /* POST /api/v1/studies  (Phase E A8.1 — create top-level study)          */
    /* ---------------------------------------------------------------------- */

    @Test
    void createReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(post("/api/v1/studies")
                .contentType("application/json")
                .content("{\"name\":\"Demo Study\"}")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createReturns403WhenNonSysadminAttempts() throws Exception {
        mockMvcWith().perform(post("/api/v1/studies")
                .contentType("application/json")
                .content("{\"name\":\"Demo Study\"}")
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
        mockMvcWith().perform(post("/api/v1/studies")
                .contentType("application/json")
                .content("")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSysadminSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createReturns400OnMissingFields() throws Exception {
        mockMvcWith().perform(post("/api/v1/studies")
                .contentType("application/json")
                .content("{}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSysadminSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'name')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'uniqueProtocolId')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'briefSummary')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'principalInvestigator')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'sponsor')]").exists());
    }

    @Test
    void createReturns400OnLongName() throws Exception {
        String tooLong = "a".repeat(101);
        mockMvcWith().perform(post("/api/v1/studies")
                .contentType("application/json")
                .content("{\"name\":\"" + tooLong + "\",\"uniqueProtocolId\":\"PROTO001\","
                        + "\"briefSummary\":\"sum\",\"principalInvestigator\":\"PI\",\"sponsor\":\"S\"}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSysadminSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'name')]").exists());
    }

    @Test
    void createReturns400OnBadUniqueProtocolIdFormat() throws Exception {
        mockMvcWith().perform(post("/api/v1/studies")
                .contentType("application/json")
                .content("{\"name\":\"Demo\",\"uniqueProtocolId\":\"has spaces!\","
                        + "\"briefSummary\":\"sum\",\"principalInvestigator\":\"PI\",\"sponsor\":\"S\"}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSysadminSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'uniqueProtocolId')]").exists());
    }

    /* ---------------------------------------------------------------------- */
    /* PUT /api/v1/studies/{studyOid}  (Phase E A8.1 — edit identity)         */
    /* ---------------------------------------------------------------------- */

    @Test
    void updateReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(put("/api/v1/studies/S_DEFAULTS1")
                .contentType("application/json")
                .content("{}")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void updateReturns400OnMissingBody() throws Exception {
        mockMvcWith().perform(put("/api/v1/studies/S_DEFAULTS1")
                .contentType("application/json")
                .content("")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSysadminSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateReturns400OnLongName() throws Exception {
        String tooLong = "a".repeat(101);
        mockMvcWith().perform(put("/api/v1/studies/S_DEFAULTS1")
                .contentType("application/json")
                .content("{\"name\":\"" + tooLong + "\"}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSysadminSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'name')]").exists());
    }

    @Test
    void updateReturns400OnBlankName() throws Exception {
        mockMvcWith().perform(put("/api/v1/studies/S_DEFAULTS1")
                .contentType("application/json")
                .content("{\"name\":\"   \"}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSysadminSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'name')]").exists());
    }

    /* ---------------------------------------------------------------------- */
    /* POST /api/v1/studies/{studyOid}/{disable,restore}                      */
    /*   (Phase E A8.1 — study lifecycle, sysadmin only)                      */
    /* ---------------------------------------------------------------------- */

    @Test
    void disableReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(post("/api/v1/studies/S_DEFAULTS1/disable")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void disableReturns403WhenNonSysadminAttempts() throws Exception {
        mockMvcWith().perform(post("/api/v1/studies/S_DEFAULTS1/disable")
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
        mockMvcWith().perform(post("/api/v1/studies/S_DEFAULTS1/restore")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void restoreReturns403WhenNonSysadminAttempts() throws Exception {
        mockMvcWith().perform(post("/api/v1/studies/S_DEFAULTS1/restore")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSessionWithRole(2, "physician", 1, "S_DEFAULTS1",
                                "Default Study",
                                at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role.INVESTIGATOR, 1)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message")
                        .value(containsString("sysadmin only")));
    }

    /* ---------------------------------------------------------------------- */
    /* StudyAdminAuthorization — pure unit-level role coverage                */
    /* ---------------------------------------------------------------------- */

    @Test
    void studyAdminAuth_NullSubjectIsForbidden() {
        org.junit.jupiter.api.Assertions.assertFalse(
                StudyAdminAuthorization.roleMayCreateStudy(null));
        org.junit.jupiter.api.Assertions.assertFalse(
                StudyAdminAuthorization.roleMayLifecycleStudy(null));
    }

    @Test
    void studyAdminAuth_SysadminMayCreateAndLifecycle() {
        at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean ub =
                new at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean();
        ub.setId(1);
        ub.addUserType(at.ac.meduniwien.ophthalmology.libreclinica.bean.core.UserType.SYSADMIN);
        org.junit.jupiter.api.Assertions.assertTrue(
                StudyAdminAuthorization.roleMayCreateStudy(ub));
        org.junit.jupiter.api.Assertions.assertTrue(
                StudyAdminAuthorization.roleMayLifecycleStudy(ub));
    }

    @Test
    void studyAdminAuth_RegularUserIsForbidden() {
        at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean ub =
                new at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean();
        ub.setId(1);
        ub.addUserType(at.ac.meduniwien.ophthalmology.libreclinica.bean.core.UserType.USER);
        org.junit.jupiter.api.Assertions.assertFalse(
                StudyAdminAuthorization.roleMayCreateStudy(ub));
        org.junit.jupiter.api.Assertions.assertFalse(
                StudyAdminAuthorization.roleMayLifecycleStudy(ub));
    }

    @Test
    void studyAdminAuth_EditAllowsDirectorBoundToTarget() {
        at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean ub =
                new at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean();
        ub.setId(2);
        ub.addUserType(at.ac.meduniwien.ophthalmology.libreclinica.bean.core.UserType.USER);

        at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean target =
                new at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean();
        target.setId(42);
        target.setParentStudyId(0);

        at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean role =
                new at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean();
        role.setStudyId(42);
        role.setRole(at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role.STUDYDIRECTOR);

        org.junit.jupiter.api.Assertions.assertTrue(
                StudyAdminAuthorization.roleMayEditStudy(ub, role, target));
    }

    @Test
    void studyAdminAuth_EditRefusesDirectorBoundToOtherStudy() {
        at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean ub =
                new at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean();
        ub.setId(2);
        ub.addUserType(at.ac.meduniwien.ophthalmology.libreclinica.bean.core.UserType.USER);

        at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean target =
                new at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean();
        target.setId(42);
        target.setParentStudyId(0);

        at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean role =
                new at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean();
        role.setStudyId(99);  // Different study.
        role.setRole(at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role.STUDYDIRECTOR);

        org.junit.jupiter.api.Assertions.assertFalse(
                StudyAdminAuthorization.roleMayEditStudy(ub, role, target));
    }

    @Test
    void studyAdminAuth_EditAllowsDirectorBoundToParentOfSite() {
        // Site (parent_study_id = 1); director's role binds to parent.
        at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean ub =
                new at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean();
        ub.setId(2);
        ub.addUserType(at.ac.meduniwien.ophthalmology.libreclinica.bean.core.UserType.USER);

        at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean site =
                new at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean();
        site.setId(50);
        site.setParentStudyId(1);

        at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean role =
                new at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean();
        role.setStudyId(1);  // Bound to parent.
        role.setRole(at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role.STUDYDIRECTOR);

        org.junit.jupiter.api.Assertions.assertTrue(
                StudyAdminAuthorization.roleMayEditStudy(ub, role, site));
    }

    @Test
    void studyAdminAuth_AcceptsWritesFalseForLockedFrozenDeleted() {
        at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean s =
                new at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean();
        s.setStatus(at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Status.AVAILABLE);
        org.junit.jupiter.api.Assertions.assertTrue(
                StudyAdminAuthorization.studyAcceptsWrites(s));
        s.setStatus(at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Status.LOCKED);
        org.junit.jupiter.api.Assertions.assertFalse(
                StudyAdminAuthorization.studyAcceptsWrites(s));
        s.setStatus(at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Status.FROZEN);
        org.junit.jupiter.api.Assertions.assertFalse(
                StudyAdminAuthorization.studyAcceptsWrites(s));
        s.setStatus(at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Status.DELETED);
        org.junit.jupiter.api.Assertions.assertFalse(
                StudyAdminAuthorization.studyAcceptsWrites(s));
    }

    /* ---------------------------------------------------------------------- */
    /* POST /api/v1/studies/{studyOid}/status  (Phase E A8.5 — lifecycle)     */
    /* ---------------------------------------------------------------------- */

    @Test
    void setStatusReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(post("/api/v1/studies/S_DEFAULTS1/status")
                .contentType("application/json")
                .content("{\"targetStatus\":\"LOCKED\",\"reason\":\"GCP review\"}")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void setStatusReturns403WhenNonSysadminAttempts() throws Exception {
        mockMvcWith().perform(post("/api/v1/studies/S_DEFAULTS1/status")
                .contentType("application/json")
                .content("{\"targetStatus\":\"LOCKED\",\"reason\":\"GCP review\"}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSessionWithRole(2, "physician", 1, "S_DEFAULTS1",
                                "Default Study",
                                at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role.INVESTIGATOR, 1)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message")
                        .value(containsString("sysadmin only")));
    }

    @Test
    void setStatusReturns400OnMissingTargetStatus() throws Exception {
        mockMvcWith().perform(post("/api/v1/studies/S_DEFAULTS1/status")
                .contentType("application/json")
                .content("{}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSysadminSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'targetStatus')]").exists());
    }

    @Test
    void setStatusReturns400OnUnsupportedTargetStatus() throws Exception {
        mockMvcWith().perform(post("/api/v1/studies/S_DEFAULTS1/status")
                .contentType("application/json")
                .content("{\"targetStatus\":\"SIGNED\"}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSysadminSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'targetStatus')]").exists());
    }
}
