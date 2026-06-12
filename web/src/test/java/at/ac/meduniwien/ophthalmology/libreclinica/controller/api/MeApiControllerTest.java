/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.UserType;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Phase E.5 A2 — MockMvc IT pinning the {@link MeApiController}
 * session-guard + validation-guard contract surface.
 *
 * <p>Happy-path tests (which need real DB rows for the
 * {@code /me/activeStudy} branch) are out of scope for the first-cut
 * MockMvc infra; they ride on the curl-probe runbook in the M1 commit
 * (Phase E.4) until Testcontainers Postgres lands.
 *
 * <p>What this test pins (contract surface the SPA consumes):
 * <ul>
 *   <li>{@code GET /api/v1/me} returns {@code 401} when no userBean
 *       is in session.</li>
 *   <li>{@code POST /api/v1/me/activeStudy} returns {@code 400} on
 *       missing / blank {@code oid}.</li>
 *   <li>{@code POST /api/v1/me/activeStudy} returns {@code 401} when
 *       no userBean is in session.</li>
 * </ul>
 */
class MeApiControllerTest extends AbstractApiControllerTest {

    private MockMvc mockMvcWith() {
        return mockMvcFor(new MeApiController(mockDataSource()));
    }

    /* ---------------------------------------------------------------------- */
    /* GET /api/v1/me                                                         */
    /* ---------------------------------------------------------------------- */

    @Test
    void getMeReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(get("/api/v1/me")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message")
                        .value(containsString("Not authenticated")));
    }

    /* ---------------------------------------------------------------------- */
    /* POST /api/v1/me/activeStudy                                            */
    /* ---------------------------------------------------------------------- */

    @Test
    void setActiveStudyReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(post("/api/v1/me/activeStudy")
                .contentType("application/json")
                .content("{\"oid\":\"S_DEFAULTS1\"}")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void setActiveStudyReturns400OnMissingOid() throws Exception {
        mockMvcWith().perform(post("/api/v1/me/activeStudy")
                .contentType("application/json")
                .content("{}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSessionWithoutStudy(1, "root")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("Missing 'oid'")));
    }

    @Test
    void setActiveStudyReturns400OnBlankOid() throws Exception {
        mockMvcWith().perform(post("/api/v1/me/activeStudy")
                .contentType("application/json")
                .content("{\"oid\":\"\"}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSessionWithoutStudy(1, "root")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("Missing 'oid'")));
    }

    /* ---------------------------------------------------------------------- */
    /* Multi-role projection (M1 backend)                                     */
    /* ---------------------------------------------------------------------- */

    /**
     * Build a session with sysadmin userBean + study + an explicit
     * userRole that does NOT trigger DAO lookups in {@code getMe}.
     * Used to assert the static-projection paths in isolation from the
     * DAO so we don't NPE on the Mockito mock DataSource.
     */
    private MockHttpSession sysadminSession() {
        MockHttpSession session = new MockHttpSession();
        UserAccountBean ub = new UserAccountBean();
        ub.setId(1);
        ub.setName("root");
        ub.addUserType(UserType.SYSADMIN);
        session.setAttribute("userBean", ub);
        StudyBean study = new StudyBean();
        study.setId(1);
        study.setOid("S_DEFAULTS1");
        study.setName("Default Study");
        session.setAttribute("study", study);
        return session;
    }

    /**
     * Multi-role parity for sysadmin: {@code role = "Administrator"}
     * and {@code roles = ["Administrator"]} — the controller fast-
     * paths sysadmin/techadmin past the DAO walk per the existing
     * Phase E.5 fix.
     */
    @Test
    void getMeProjectsSysadminAsSingleAdministratorRole() throws Exception {
        mockMvcWith().perform(get("/api/v1/me")
                .session(sysadminSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("Administrator"))
                .andExpect(jsonPath("$.roles").isArray())
                .andExpect(jsonPath("$.roles", hasSize(1)))
                .andExpect(jsonPath("$.roles", contains("Administrator")));
    }

    /**
     * Multi-role fallback for a non-sysadmin user with a single
     * {@code userRole} session attribute and a Mockito DataSource
     * (DAO walk returns empty): {@code role / roles[0]} are populated
     * from the legacy single-role session bean.
     */
    @Test
    void getMeFallsBackToSessionUserRoleWhenDaoIsEmpty() throws Exception {
        mockMvcWith().perform(get("/api/v1/me")
                .session((MockHttpSession) authenticatedSessionWithRole(
                        2, "physician", 1, "S_DEFAULTS1", "Default Study",
                        Role.MONITOR, 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("Monitor"))
                .andExpect(jsonPath("$.roles").isArray())
                .andExpect(jsonPath("$.roles", hasSize(1)))
                .andExpect(jsonPath("$.roles", contains("Monitor")));
    }

    /**
     * Both {@code role} and {@code roles} must be present on every
     * /me response — the SPA's auth store consumes them together.
     */
    @Test
    void getMeAlwaysEmitsBothRoleAndRolesFields() throws Exception {
        mockMvcWith().perform(get("/api/v1/me")
                .session((MockHttpSession) authenticatedSessionWithRole(
                        2, "physician", 1, "S_DEFAULTS1", "Default Study",
                        Role.INVESTIGATOR, 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").exists())
                .andExpect(jsonPath("$.roles").exists());
    }

}
