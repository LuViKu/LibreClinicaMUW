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

import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Phase E.5 A2 — MockMvc IT pinning the
 * {@link DiscrepancyApiController} session + body validation contract.
 *
 * <p>What this pins:
 * <ul>
 *   <li>{@code GET /api/v1/discrepancies} → {@code 401} anonymous /
 *       {@code 400} no active study.</li>
 *   <li>{@code POST /api/v1/discrepancies} → {@code 400} on missing
 *       {@code description} or missing {@code subjectId}/{@code itemOid}
 *       refs.</li>
 * </ul>
 */
class DiscrepancyApiControllerTest extends AbstractApiControllerTest {

    private MockMvc mockMvcWith() {
        return mockMvcFor(new DiscrepancyApiController(mockDataSource(),
                Mockito.mock(SiteVisibilityFilter.class)));
    }

    /* ---------------------------------------------------------------------- */
    /* GET /api/v1/discrepancies                                              */
    /* ---------------------------------------------------------------------- */

    @Test
    void listReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(get("/api/v1/discrepancies")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listReturns400WhenNoActiveStudy() throws Exception {
        mockMvcWith().perform(get("/api/v1/discrepancies")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSessionWithoutStudy(1, "root")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("No active study")));
    }

    /* ---------------------------------------------------------------------- */
    /* POST /api/v1/discrepancies                                             */
    /* ---------------------------------------------------------------------- */

    @Test
    void addReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(post("/api/v1/discrepancies")
                .contentType("application/json")
                .content("{\"subjectId\":\"M-001\",\"itemOid\":\"I_AGE\",\"description\":\"d\"}")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void addReturns400OnMissingDescription() throws Exception {
        mockMvcWith().perform(post("/api/v1/discrepancies")
                .contentType("application/json")
                .content("{\"subjectId\":\"M-001\",\"itemOid\":\"I_AGE\"}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("'description' is required")));
    }

    @Test
    void addReturns400OnMissingEntityRefs() throws Exception {
        mockMvcWith().perform(post("/api/v1/discrepancies")
                .contentType("application/json")
                .content("{\"description\":\"a query\"}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("'subjectId' and 'itemOid' are required")));
    }
}
