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

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Phase E.6 Data Export — Phase 3 (filters) MockMvc IT.
 *
 * <p>Pins the entry-point validation contract for the
 * {@code POST /api/v1/datasets/{id}:test-filter} endpoint:
 *
 * <ul>
 *   <li>{@code 401} when the session has no {@code userBean}.</li>
 *   <li>{@code 400} when the session has no active study.</li>
 *   <li>{@code 400} when the body omits {@code filters}.</li>
 *   <li>{@code 400} when a row omits {@code itemOid}.</li>
 *   <li>{@code 400} when a row carries an unsupported operator.</li>
 * </ul>
 *
 * <p>Per the existing M13 MockMvc IT pattern (see
 * {@link AbstractApiControllerTest}), happy-path counts ride on a
 * Testcontainers cut; this slice covers the session + body
 * short-circuits that never touch a DAO. Type-mismatched operator
 * validation (e.g. {@code <} on a {@code ST} item) also rides on the
 * Testcontainers cut because the row resolves via {@code ItemDAO}.
 */
class DatasetsApiControllerTest extends AbstractApiControllerTest {

    private MockMvc mockMvcWith() {
        return mockMvcFor(new DatasetsApiController(mockDataSource()));
    }

    /* ---------------------------------------------------------------------- */
    /* Session guards                                                          */
    /* ---------------------------------------------------------------------- */

    @Test
    void testFilterReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(post("/api/v1/datasets/0:test-filter")
                .contentType("application/json")
                .content("{\"filters\":[]}")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void testFilterReturns400WhenNoActiveStudy() throws Exception {
        mockMvcWith().perform(post("/api/v1/datasets/0:test-filter")
                .contentType("application/json")
                .content("{\"filters\":[]}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSessionWithoutStudy(1, "root")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("No active study")));
    }

    /* ---------------------------------------------------------------------- */
    /* Body validation                                                         */
    /* ---------------------------------------------------------------------- */

    @Test
    void testFilterReturns400WhenFiltersFieldMissing() throws Exception {
        mockMvcWith().perform(post("/api/v1/datasets/0:test-filter")
                .contentType("application/json")
                .content("{}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("'filters' is required")));
    }

    @Test
    void testFilterReturns400WhenBodyMissing() throws Exception {
        mockMvcWith().perform(post("/api/v1/datasets/0:test-filter")
                .contentType("application/json")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("'filters' is required")));
    }

    @Test
    void testFilterReturns400OnRowMissingItemOid() throws Exception {
        mockMvcWith().perform(post("/api/v1/datasets/0:test-filter")
                .contentType("application/json")
                .content("{\"filters\":[{\"operator\":\"=\",\"value\":\"42\"}]}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("itemOid is required")));
    }

    @Test
    void testFilterReturns400OnRowMissingOperator() throws Exception {
        mockMvcWith().perform(post("/api/v1/datasets/0:test-filter")
                .contentType("application/json")
                .content("{\"filters\":[{\"itemOid\":\"I_AGE\",\"value\":\"42\"}]}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("operator is required")));
    }

    @Test
    void testFilterReturns400OnUnsupportedOperator() throws Exception {
        mockMvcWith().perform(post("/api/v1/datasets/0:test-filter")
                .contentType("application/json")
                .content("{\"filters\":[{\"itemOid\":\"I_AGE\",\"operator\":\"like\",\"value\":\"42\"}]}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("is not supported")));
    }
}
