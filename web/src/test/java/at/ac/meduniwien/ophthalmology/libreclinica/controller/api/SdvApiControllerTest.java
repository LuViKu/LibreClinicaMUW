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
 * Phase E.5 A2 — MockMvc IT pinning the {@link SdvApiController}
 * session + body validation contract.
 *
 * <p>What this pins:
 * <ul>
 *   <li>{@code GET /api/v1/sdv} → {@code 401} anonymous / {@code 400}
 *       no active study.</li>
 *   <li>{@code POST /api/v1/sdv/verify} → {@code 400} on empty /
 *       missing {@code eventCrfOids}.</li>
 * </ul>
 *
 * <p>Cross-study guard (403 on event-crf belonging to a different
 * study) needs a populated DataSource — covered by the Testcontainers
 * cut.
 */
class SdvApiControllerTest extends AbstractApiControllerTest {

    private MockMvc mockMvcWith() {
        return mockMvcFor(new SdvApiController(mockDataSource(),
                Mockito.mock(SiteVisibilityFilter.class)));
    }

    /* ---------------------------------------------------------------------- */
    /* GET /api/v1/sdv                                                        */
    /* ---------------------------------------------------------------------- */

    @Test
    void listReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(get("/api/v1/sdv")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listReturns400WhenNoActiveStudy() throws Exception {
        mockMvcWith().perform(get("/api/v1/sdv")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSessionWithoutStudy(1, "root")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("No active study")));
    }

    /* ---------------------------------------------------------------------- */
    /* POST /api/v1/sdv/verify                                                */
    /* ---------------------------------------------------------------------- */

    @Test
    void verifyReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(post("/api/v1/sdv/verify")
                .contentType("application/json")
                .content("{\"eventCrfOids\":[\"1\"]}")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void verifyReturns400OnMissingArray() throws Exception {
        mockMvcWith().perform(post("/api/v1/sdv/verify")
                .contentType("application/json")
                .content("{}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("'eventCrfOids' is required")));
    }

    @Test
    void verifyReturns400OnEmptyArray() throws Exception {
        mockMvcWith().perform(post("/api/v1/sdv/verify")
                .contentType("application/json")
                .content("{\"eventCrfOids\":[]}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("'eventCrfOids' is required")));
    }

    /* ---------------------------------------------------------------------- */
    /* POST /api/v1/sdv/unverify (Phase E A6)                                 */
    /* ---------------------------------------------------------------------- */

    @Test
    void unverifyReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(post("/api/v1/sdv/unverify")
                .contentType("application/json")
                .content("{\"eventCrfOids\":[\"1\"],\"reason\":\"data changed\"}")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unverifyReturns400WhenNoActiveStudy() throws Exception {
        mockMvcWith().perform(post("/api/v1/sdv/unverify")
                .contentType("application/json")
                .content("{\"eventCrfOids\":[\"1\"],\"reason\":\"data changed\"}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSessionWithoutStudy(1, "root")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("No active study")));
    }

    @Test
    void unverifyReturns400OnMissingArray() throws Exception {
        mockMvcWith().perform(post("/api/v1/sdv/unverify")
                .contentType("application/json")
                .content("{\"reason\":\"r\"}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("'eventCrfOids' is required")));
    }

    @Test
    void unverifyReturns400OnMissingReason() throws Exception {
        mockMvcWith().perform(post("/api/v1/sdv/unverify")
                .contentType("application/json")
                .content("{\"eventCrfOids\":[\"1\"]}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("'reason' is required")));
    }

    @Test
    void unverifyReturns400OnBlankReason() throws Exception {
        mockMvcWith().perform(post("/api/v1/sdv/unverify")
                .contentType("application/json")
                .content("{\"eventCrfOids\":[\"1\"],\"reason\":\"   \"}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("'reason' is required")));
    }

    @Test
    void unverifyReturns403WhenInvestigatorAttempts() throws Exception {
        // Investigator (role id 4) cannot un-verify a Monitor's
        // SDV stamp per the legacy handleSDVRemove separation-of-
        // duties rule.
        mockMvcWith().perform(post("/api/v1/sdv/unverify")
                .contentType("application/json")
                .content("{\"eventCrfOids\":[\"1\"],\"reason\":\"source corrected\"}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSessionWithRole(2, "physician", 1, "S_DEFAULTS1",
                                "Default Study",
                                at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role.INVESTIGATOR, 1)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message")
                        .value(containsString("does not permit un-verifying")));
    }

    /* ---------------------------------------------------------------------- */
    /* SdvUnverifyAuthorization — pure unit-level role coverage               */
    /* ---------------------------------------------------------------------- */

    @Test
    void sdvUnverifyAuth_PermittedRoles() {
        // MONITOR(6), STUDYDIRECTOR(3) = Data Manager, ADMIN(1)
        org.junit.jupiter.api.Assertions.assertTrue(
                SdvUnverifyAuthorization.roleMayUnverify(6));
        org.junit.jupiter.api.Assertions.assertTrue(
                SdvUnverifyAuthorization.roleMayUnverify(3));
        org.junit.jupiter.api.Assertions.assertTrue(
                SdvUnverifyAuthorization.roleMayUnverify(1));
    }

    @Test
    void sdvUnverifyAuth_ForbiddenRoles() {
        // INVESTIGATOR(4), COORDINATOR(2) = CRC, RA(5), RA2(7), INVALID(0)
        org.junit.jupiter.api.Assertions.assertFalse(
                SdvUnverifyAuthorization.roleMayUnverify(4));
        org.junit.jupiter.api.Assertions.assertFalse(
                SdvUnverifyAuthorization.roleMayUnverify(2));
        org.junit.jupiter.api.Assertions.assertFalse(
                SdvUnverifyAuthorization.roleMayUnverify(5));
        org.junit.jupiter.api.Assertions.assertFalse(
                SdvUnverifyAuthorization.roleMayUnverify(7));
        org.junit.jupiter.api.Assertions.assertFalse(
                SdvUnverifyAuthorization.roleMayUnverify(0));
    }
}
