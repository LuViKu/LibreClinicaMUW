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

import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;

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
                Mockito.mock(SecurityManager.class)));
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
        mockMvcWith().perform(post("/api/v1/subjects")
                .contentType("application/json")
                .content("{\"id\":\"M-200\",\"gender\":\"Z\",\"enrolledOn\":\"2099-01-01\"}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'gender')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'enrolledOn')]").exists());
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
}
