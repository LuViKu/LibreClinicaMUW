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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Phase E.5 A2 — MockMvc IT pinning the {@link EventsApiController}
 * session + body validation contract.
 *
 * <p>What this pins:
 * <ul>
 *   <li>{@code GET /api/v1/events} → {@code 401} anonymous /
 *       {@code 400} no active study.</li>
 *   <li>{@code POST /api/v1/events} → {@code 400} on each missing
 *       required field (subjectId / eventDefinitionOid / dateStarted)
 *       and on a malformed date.</li>
 * </ul>
 *
 * <p>409 dup-non-repeating coverage requires Testcontainers Postgres —
 * the controller's max-ordinal check needs a real DAO walk.
 */
class EventsApiControllerTest extends AbstractApiControllerTest {

    private MockMvc mockMvcWith() {
        return mockMvcFor(new EventsApiController(mockDataSource(),
                Mockito.mock(SiteVisibilityFilter.class)));
    }

    /* ---------------------------------------------------------------------- */
    /* GET /api/v1/events                                                     */
    /* ---------------------------------------------------------------------- */

    @Test
    void listReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(get("/api/v1/events")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listReturns400WhenNoActiveStudy() throws Exception {
        mockMvcWith().perform(get("/api/v1/events")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSessionWithoutStudy(1, "root")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("No active study")));
    }

    /* ---------------------------------------------------------------------- */
    /* POST /api/v1/events                                                    */
    /* ---------------------------------------------------------------------- */

    @Test
    void scheduleReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(post("/api/v1/events")
                .contentType("application/json")
                .content("{\"subjectId\":\"M-001\",\"eventDefinitionOid\":\"SE_V1\",\"dateStarted\":\"2026-06-01\"}")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void scheduleReturns400OnEmptyBody() throws Exception {
        mockMvcWith().perform(post("/api/v1/events")
                .contentType("application/json")
                .content("{}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("'subjectId' is required")));
    }

    @Test
    void scheduleReturns400OnMissingEventDefinitionOid() throws Exception {
        mockMvcWith().perform(post("/api/v1/events")
                .contentType("application/json")
                .content("{\"subjectId\":\"M-001\"}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("'eventDefinitionOid' is required")));
    }

    @Test
    void scheduleReturns400OnMissingDateStarted() throws Exception {
        mockMvcWith().perform(post("/api/v1/events")
                .contentType("application/json")
                .content("{\"subjectId\":\"M-001\",\"eventDefinitionOid\":\"SE_V1\"}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("'dateStarted' is required")));
    }

    @Test
    void scheduleReturns400OnMalformedDate() throws Exception {
        mockMvcWith().perform(post("/api/v1/events")
                .contentType("application/json")
                .content("{\"subjectId\":\"M-001\",\"eventDefinitionOid\":\"SE_V1\",\"dateStarted\":\"not-a-date\"}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("must be YYYY-MM-DD")));
    }

    /* ---------------------------------------------------------------------- */
    /* PUT /api/v1/events/{id} (Phase E A4 — edit)                            */
    /* ---------------------------------------------------------------------- */

    @Test
    void updateReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(put("/api/v1/events/1")
                .contentType("application/json")
                .content("{\"location\":\"OR-3\"}")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void updateReturns400WhenNoActiveStudy() throws Exception {
        mockMvcWith().perform(put("/api/v1/events/1")
                .contentType("application/json")
                .content("{\"location\":\"OR-3\"}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSessionWithoutStudy(1, "root")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateReturns403WhenMonitorAttempts() throws Exception {
        mockMvcWith().perform(put("/api/v1/events/1")
                .contentType("application/json")
                .content("{\"location\":\"OR-3\"}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSessionWithRole(3, "monitor", 1, "S_DEFAULTS1",
                                "Default Study",
                                at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role.MONITOR, 1)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message")
                        .value(containsString("does not permit editing")));
    }

    @Test
    void updateReturns400OnEmptyBody() throws Exception {
        mockMvcWith().perform(put("/api/v1/events/1")
                .contentType("application/json")
                .content("")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSessionWithRole(2, "physician", 1, "S_DEFAULTS1",
                                "Default Study",
                                at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role.INVESTIGATOR, 1)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateReturns400OnInvalidStatus() throws Exception {
        // 'completed' is derived from CRF state; not user-editable.
        mockMvcWith().perform(put("/api/v1/events/1")
                .contentType("application/json")
                .content("{\"status\":\"completed\"}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSessionWithRole(2, "physician", 1, "S_DEFAULTS1",
                                "Default Study",
                                at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role.INVESTIGATOR, 1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("'status' must be one of")));
    }

    @Test
    void updateReturns400OnMalformedDate() throws Exception {
        mockMvcWith().perform(put("/api/v1/events/1")
                .contentType("application/json")
                .content("{\"dateStarted\":\"not-a-date\"}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSessionWithRole(2, "physician", 1, "S_DEFAULTS1",
                                "Default Study",
                                at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role.INVESTIGATOR, 1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("must be YYYY-MM-DD")));
    }

    /* ---------------------------------------------------------------------- */
    /* DELETE /api/v1/events/{id} (Phase E A4 — cancel)                       */
    /* ---------------------------------------------------------------------- */

    @Test
    void cancelReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(delete("/api/v1/events/1")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void cancelReturns403WhenInvestigatorAttempts() throws Exception {
        // Investigator can edit but not cancel — escalation to DM.
        mockMvcWith().perform(delete("/api/v1/events/1")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSessionWithRole(2, "physician", 1, "S_DEFAULTS1",
                                "Default Study",
                                at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role.INVESTIGATOR, 1)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message")
                        .value(containsString("does not permit cancelling")));
    }

    @Test
    void cancelReturns403WhenMonitorAttempts() throws Exception {
        mockMvcWith().perform(delete("/api/v1/events/1")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSessionWithRole(3, "monitor", 1, "S_DEFAULTS1",
                                "Default Study",
                                at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role.MONITOR, 1)))
                .andExpect(status().isForbidden());
    }

    /* ---------------------------------------------------------------------- */
    /* EventEditAuthorization — pure unit-level role coverage                 */
    /* ---------------------------------------------------------------------- */

    @Test
    void eventEditAuth_PermittedRoles() {
        // INVESTIGATOR(4), COORDINATOR(2), STUDYDIRECTOR(3), ADMIN(1)
        org.junit.jupiter.api.Assertions.assertTrue(
                EventEditAuthorization.roleMayEdit(4));
        org.junit.jupiter.api.Assertions.assertTrue(
                EventEditAuthorization.roleMayEdit(2));
        org.junit.jupiter.api.Assertions.assertTrue(
                EventEditAuthorization.roleMayEdit(3));
        org.junit.jupiter.api.Assertions.assertTrue(
                EventEditAuthorization.roleMayEdit(1));
    }

    @Test
    void eventEditAuth_ForbiddenRoles() {
        // MONITOR(6), RA(5), RA2(7), INVALID(0)
        org.junit.jupiter.api.Assertions.assertFalse(
                EventEditAuthorization.roleMayEdit(6));
        org.junit.jupiter.api.Assertions.assertFalse(
                EventEditAuthorization.roleMayEdit(5));
        org.junit.jupiter.api.Assertions.assertFalse(
                EventEditAuthorization.roleMayEdit(7));
        org.junit.jupiter.api.Assertions.assertFalse(
                EventEditAuthorization.roleMayEdit(0));
    }

    @Test
    void eventCancelAuth_PermittedRoles() {
        // STUDYDIRECTOR(3), ADMIN(1)
        org.junit.jupiter.api.Assertions.assertTrue(
                EventEditAuthorization.roleMayCancel(3));
        org.junit.jupiter.api.Assertions.assertTrue(
                EventEditAuthorization.roleMayCancel(1));
    }

    @Test
    void eventCancelAuth_ForbiddenRoles() {
        // Investigator + CRC can edit but NOT cancel; Monitor/RA neither.
        org.junit.jupiter.api.Assertions.assertFalse(
                EventEditAuthorization.roleMayCancel(4));
        org.junit.jupiter.api.Assertions.assertFalse(
                EventEditAuthorization.roleMayCancel(2));
        org.junit.jupiter.api.Assertions.assertFalse(
                EventEditAuthorization.roleMayCancel(6));
        org.junit.jupiter.api.Assertions.assertFalse(
                EventEditAuthorization.roleMayCancel(5));
        org.junit.jupiter.api.Assertions.assertFalse(
                EventEditAuthorization.roleMayCancel(7));
        org.junit.jupiter.api.Assertions.assertFalse(
                EventEditAuthorization.roleMayCancel(0));
    }
}
