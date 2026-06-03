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
 * Phase E.6 Milestone B — MockMvc IT pinning the response-set catalog
 * endpoint guards. Happy-path catalog reads (which exercise the
 * distinct-tuples SQL) need a real Postgres and are covered by the
 * integration-test profile.
 */
class ResponseSetsApiControllerTest extends AbstractApiControllerTest {

    private MockMvc mockMvc() {
        return mockMvcFor(new ResponseSetsApiController(mockDataSource()));
    }

    @Test
    void listReturns401WhenAnonymous() throws Exception {
        mockMvc().perform(get("/api/v1/response-sets")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createReturns401WhenAnonymous() throws Exception {
        mockMvc().perform(post("/api/v1/response-sets")
                .contentType("application/json")
                .content("{\"label\":\"yes_no\",\"responseType\":\"single-select\"}")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createReturns403WhenInvestigatorAttempts() throws Exception {
        mockMvc().perform(post("/api/v1/response-sets")
                .contentType("application/json")
                .content("{\"label\":\"yes_no\",\"responseType\":\"single-select\"}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSessionWithRole(2, "physician", 1, "S_DEFAULTS1",
                                "Default Study",
                                at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role.INVESTIGATOR, 1)))
                .andExpect(status().isForbidden());
    }

    @Test
    void createReturns400OnMissingLabel() throws Exception {
        mockMvc().perform(post("/api/v1/response-sets")
                .contentType("application/json")
                .content("{\"responseType\":\"single-select\",\"options\":[{\"text\":\"Yes\",\"value\":\"1\"}]}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSysadminSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("label"));
    }

    @Test
    void createReturns400OnInvalidResponseType() throws Exception {
        mockMvc().perform(post("/api/v1/response-sets")
                .contentType("application/json")
                .content("{\"label\":\"yes_no\",\"responseType\":\"calculation\"}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSysadminSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field")
                        .value(org.hamcrest.Matchers.hasItem("responseType")));
    }

    @Test
    void createReturns400OnChoiceTypeWithoutOptions() throws Exception {
        mockMvc().perform(post("/api/v1/response-sets")
                .contentType("application/json")
                .content("{\"label\":\"yes_no\",\"responseType\":\"single-select\"}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSysadminSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field")
                        .value(org.hamcrest.Matchers.hasItem("options")));
    }

    @Test
    void createReturns201OnValidSingleSelect() throws Exception {
        // Virtual create — accepts and echoes the payload back without
        // touching the database. The MockMvc test pins that the 201
        // surface is reachable; persistence happens at CRF-version
        // submit time.
        mockMvc().perform(post("/api/v1/response-sets")
                .contentType("application/json")
                .content("{\"label\":\"yes_no\",\"responseType\":\"single-select\","
                        + "\"options\":[{\"text\":\"Yes\",\"value\":\"1\"},{\"text\":\"No\",\"value\":\"0\"}]}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSysadminSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.label").value("yes_no"))
                .andExpect(jsonPath("$.responseType").value("single-select"))
                .andExpect(jsonPath("$.options[0].text").value("Yes"));
    }

    @Test
    void createBodyMissingReturns400() throws Exception {
        mockMvc().perform(post("/api/v1/response-sets")
                .contentType("application/json")
                .content("")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSysadminSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("body")));
    }
}
