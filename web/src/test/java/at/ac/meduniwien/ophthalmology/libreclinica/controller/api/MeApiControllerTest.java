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

import org.junit.jupiter.api.Test;
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
}
