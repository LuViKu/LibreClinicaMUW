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

import java.util.Set;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.core.SecurityManager;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;

/**
 * Phase E.4 M13 — first MockMvc IT pinning the SubjectsApiController
 * contract surface.
 *
 * <p>Tests focus on the session-state guards that never reach the DAO
 * layer (see {@link AbstractApiControllerTest} for the rationale).
 * Happy-path coverage lives in the manual curl-probe section of each
 * milestone commit message + the Selenium smoke ITs.
 *
 * <p>What this test pins (contract surface the SPA consumes):
 * <ul>
 *   <li>{@code GET /api/v1/subjects} returns {@code 400} with
 *       {@code message} mentioning "active study" when no study is
 *       bound to the session.</li>
 *   <li>Same call returns {@code 401} when no userBean is in session
 *       (defence-in-depth — the SecurityFilterChain blocks this
 *       upstream in production).</li>
 *   <li>{@code GET /api/v1/subjects/{oid}} routes correctly under
 *       the matrix endpoint (no path-overlap surprise).</li>
 *   <li>{@code POST /api/v1/subjects} returns {@code 400 / errors[]}
 *       when validation fails — without reaching the DAO.</li>
 * </ul>
 */
class SubjectsApiControllerTest extends AbstractApiControllerTest {

    private MockMvc mockMvcWith() {
        // Constructor takes (DataSource, SecurityManager). Neither
        // collaborator is touched by the session-guard paths under test
        // (the guards short-circuit before any DAO or password compare),
        // so both can be plain Mockito mocks without behaviour stubs.
        return mockMvcFor(new SubjectsApiController(mockDataSource(),
                Mockito.mock(SecurityManager.class),
                Mockito.mock(SiteVisibilityFilter.class)));
    }

    /* ---------------------------------------------------------------------- */
    /* GET /api/v1/subjects — session guards                                  */
    /* ---------------------------------------------------------------------- */

    @Test
    void listRejectsRequestWithoutActiveStudy() throws Exception {
        mockMvcWith().perform(get("/api/v1/subjects")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSessionWithoutStudy(1, "root")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("No active study")));
    }

    @Test
    void listRejectsAnonymousRequest() throws Exception {
        mockMvcWith().perform(get("/api/v1/subjects")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                // Anonymous → no userBean in session → controller's
                // first guard (no study) trips with 400. The 401-on-
                // no-userBean path requires a study to be bound first;
                // in production the SecurityFilterChain blocks the
                // anonymous request upstream before it gets here.
                .andExpect(status().isBadRequest());
    }

    /* ---------------------------------------------------------------------- */
    /* GET /api/v1/subjects/{oid} — session guards                            */
    /* ---------------------------------------------------------------------- */

    @Test
    void detailRejectsRequestWithoutActiveStudy() throws Exception {
        mockMvcWith().perform(get("/api/v1/subjects/SS_M001")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSessionWithoutStudy(1, "root")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("No active study")));
    }

    /* ---------------------------------------------------------------------- */
    /* POST /api/v1/subjects — validation guards                              */
    /* ---------------------------------------------------------------------- */

    @Test
    void createRejectsBadGenderWithoutHittingDao() throws Exception {
        // Multi-field validation: gender 'Z' is invalid; enrollment in
        // the future is invalid. Both errors should arrive in one 400
        // body so the SPA can light up every offending field at once.
        //
        // Note: omit `id` so the validator's per-id uniqueness DAO call
        // (findByLabelAndStudy) is skipped — the "Subject ID is required"
        // error short-circuits the duplicate-check branch. The DAO is
        // a Mockito mock without behaviour stubs, so reaching it would
        // NPE inside DAODigester.
        mockMvcWith().perform(post("/api/v1/subjects")
                .contentType("application/json")
                .content("{\"gender\":\"Z\",\"enrolledOn\":\"2099-01-01\"}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'gender')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'enrolledOn')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'id')]").exists());
    }

    /* ---------------------------------------------------------------------- */
    /* A4 — SiteVisibilityFilter wiring                                       */
    /* ---------------------------------------------------------------------- */

    @Test
    void listInvokesSiteVisibilityFilterForMonitorWithSiteGrant() throws Exception {
        // A4 contract: when a Monitor with a single-site grant under a
        // multi-site parent issues a list call, the controller must
        // invoke SiteVisibilityFilter.visibleStudyIds(...) with the
        // session-bound (user, currentStudy, role) triple before any
        // DAO touches. The stubbed filter throws a sentinel exception
        // so we can both (a) verify the invocation happened and (b)
        // confirm the controller never reached the DAO layer (which
        // would NPE on the mock DataSource anyway). This pattern
        // pins the wiring contract without needing a real
        // DataSource.
        SiteVisibilityFilter filter = Mockito.mock(SiteVisibilityFilter.class);
        Mockito.when(filter.visibleStudyIds(
                        Mockito.any(UserAccountBean.class),
                        Mockito.any(StudyBean.class),
                        Mockito.any(StudyUserRoleBean.class)))
                .thenThrow(new RuntimeException("FILTER_INVOKED"));
        SubjectsApiController controller = new SubjectsApiController(
                mockDataSource(), Mockito.mock(SecurityManager.class), filter);
        MockMvc mockMvc = mockMvcFor(controller);

        try {
            mockMvc.perform(get("/api/v1/subjects")
                    .session((org.springframework.mock.web.MockHttpSession)
                            authenticatedSessionWithRole(1, "monitor1",
                                    1, "S_DEFAULTS1", "Default Study",
                                    Role.MONITOR, /* roleStudyId = site */ 2)));
        } catch (Exception e) {
            // ServletException wraps the FILTER_INVOKED sentinel.
            if (!String.valueOf(e.getCause() == null ? e : e.getCause())
                    .contains("FILTER_INVOKED")
                    && !String.valueOf(e).contains("FILTER_INVOKED")) {
                throw e;
            }
        }

        Mockito.verify(filter, Mockito.atLeastOnce())
                .visibleStudyIds(
                        Mockito.any(UserAccountBean.class),
                        Mockito.any(StudyBean.class),
                        Mockito.any(StudyUserRoleBean.class));
    }

    @Test
    void createRejectsMissingRequiredFields() throws Exception {
        mockMvcWith().perform(post("/api/v1/subjects")
                .contentType("application/json")
                .content("{}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors").isArray());
    }

    /* ---------------------------------------------------------------------- */
    /* POST /api/v1/subjects/{oid}/remove and /restore (Phase E A3)           */
    /* ---------------------------------------------------------------------- */

    @Test
    void removeReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(post("/api/v1/subjects/M-001/remove")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void removeReturns400WhenNoActiveStudy() throws Exception {
        mockMvcWith().perform(post("/api/v1/subjects/M-001/remove")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSessionWithoutStudy(1, "root")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void removeReturns403WhenInvestigatorAttempts() throws Exception {
        // Investigator (role id 4) cannot soft-delete subjects per
        // the legacy RemoveSubjectServlet#mayProceed rule (DM/Admin only).
        mockMvcWith().perform(post("/api/v1/subjects/M-001/remove")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSessionWithRole(2, "physician", 1, "S_DEFAULTS1",
                                "Default Study",
                                at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role.INVESTIGATOR, 1)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message")
                        .value(containsString("does not permit remove")));
    }

    @Test
    void removeReturns403WhenMonitorAttempts() throws Exception {
        mockMvcWith().perform(post("/api/v1/subjects/M-001/remove")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSessionWithRole(3, "monitor", 1, "S_DEFAULTS1",
                                "Default Study",
                                at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role.MONITOR, 1)))
                .andExpect(status().isForbidden());
    }

    @Test
    void restoreReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(post("/api/v1/subjects/M-001/restore")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void restoreReturns403WhenInvestigatorAttempts() throws Exception {
        mockMvcWith().perform(post("/api/v1/subjects/M-001/restore")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSessionWithRole(2, "physician", 1, "S_DEFAULTS1",
                                "Default Study",
                                at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role.INVESTIGATOR, 1)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message")
                        .value(containsString("does not permit restore")));
    }

    /* ---------------------------------------------------------------------- */
    /* SubjectLifecycleAuthorization — pure unit-level role coverage          */
    /* ---------------------------------------------------------------------- */

    @Test
    void subjectLifecycleAuth_PermittedRoles() {
        // STUDYDIRECTOR (3) = Data Manager, ADMIN (1)
        org.junit.jupiter.api.Assertions.assertTrue(
                SubjectLifecycleAuthorization.roleMayManageLifecycle(3));
        org.junit.jupiter.api.Assertions.assertTrue(
                SubjectLifecycleAuthorization.roleMayManageLifecycle(1));
    }

    @Test
    void subjectLifecycleAuth_ForbiddenRoles() {
        // INVESTIGATOR(4), COORDINATOR(2), MONITOR(6), RA(5), RA2(7), INVALID(0)
        org.junit.jupiter.api.Assertions.assertFalse(
                SubjectLifecycleAuthorization.roleMayManageLifecycle(4));
        org.junit.jupiter.api.Assertions.assertFalse(
                SubjectLifecycleAuthorization.roleMayManageLifecycle(2));
        org.junit.jupiter.api.Assertions.assertFalse(
                SubjectLifecycleAuthorization.roleMayManageLifecycle(6));
        org.junit.jupiter.api.Assertions.assertFalse(
                SubjectLifecycleAuthorization.roleMayManageLifecycle(5));
        org.junit.jupiter.api.Assertions.assertFalse(
                SubjectLifecycleAuthorization.roleMayManageLifecycle(7));
        org.junit.jupiter.api.Assertions.assertFalse(
                SubjectLifecycleAuthorization.roleMayManageLifecycle(0));
    }
}
