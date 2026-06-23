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
                Mockito.mock(SiteVisibilityFilter.class),
                Mockito.mock(at.ac.meduniwien.ophthalmology.libreclinica.service.scheduling.VisitIntervalCalculator.class)));
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
    /* GET /api/v1/events/{id} — Phase E.6 event detail                       */
    /* ---------------------------------------------------------------------- */

    @Test
    void getEventDetailReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(get("/api/v1/events/42")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getEventDetailReturns400WhenNoActiveStudy() throws Exception {
        mockMvcWith().perform(get("/api/v1/events/42")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSessionWithoutStudy(1, "root")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("No active study")));
    }

    // 404 (unknown event id) + 403 (visibility) + 200 happy-path pin
    // need a real backing DataSource so the StudyEventDAO can resolve
    // — they ride the Testcontainers Postgres follow-up alongside the
    // 409 dup-non-repeating coverage already noted on this class.

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
        // 2026-06-22 — 'completed' was promoted to a writeable status in
        // the 2026-06-21 manual-visit-complete change (the operator now
        // drives the COMPLETED transition explicitly from EventDetailView).
        // Use a token that's truly not in the allow-list so the test
        // still exercises the validation branch.
        mockMvcWith().perform(put("/api/v1/events/1")
                .contentType("application/json")
                .content("{\"status\":\"garbage\"}")
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
    void cancelReturns403WhenMonitorAttempts2() throws Exception {
        // 2026-06-22 — the previous "cancelReturns403WhenInvestigatorAttempts"
        // test enshrined a DM-only escalation gate that the 2026-06-21
        // user-feedback batch widened: Investigator + CRC may now cancel
        // (audit captures the reason). The 403 contract is now pinned by
        // the Monitor case alone (and by the role-allow-list unit test
        // below). Kept here as a second 403 fixture for the Monitor
        // role so the negative-path coverage isn't dropped by attrition.
        mockMvcWith().perform(delete("/api/v1/events/1")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSessionWithRole(3, "monitor2", 1, "S_DEFAULTS1",
                                "Default Study",
                                at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role.MONITOR, 1)))
                .andExpect(status().isForbidden());
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
        // 2026-06-22 — the 2026-06-21 user-feedback batch aligned
        // cancel with edit (Investigator + CRC may now cancel; structured
        // reason code captures accountability). Same writer set as the
        // edit predicate.
        org.junit.jupiter.api.Assertions.assertTrue(
                EventEditAuthorization.roleMayCancel(1)); // ADMIN
        org.junit.jupiter.api.Assertions.assertTrue(
                EventEditAuthorization.roleMayCancel(2)); // COORDINATOR
        org.junit.jupiter.api.Assertions.assertTrue(
                EventEditAuthorization.roleMayCancel(3)); // STUDYDIRECTOR
        org.junit.jupiter.api.Assertions.assertTrue(
                EventEditAuthorization.roleMayCancel(4)); // INVESTIGATOR
    }

    @Test
    void eventCancelAuth_ForbiddenRoles() {
        // 2026-06-22 — Monitor / RA / RA2 / invalid still cannot cancel
        // (Monitor verifies, RA enters; neither corrects).
        org.junit.jupiter.api.Assertions.assertFalse(
                EventEditAuthorization.roleMayCancel(6)); // MONITOR
        org.junit.jupiter.api.Assertions.assertFalse(
                EventEditAuthorization.roleMayCancel(5)); // RA
        org.junit.jupiter.api.Assertions.assertFalse(
                EventEditAuthorization.roleMayCancel(7)); // RA2
        org.junit.jupiter.api.Assertions.assertFalse(
                EventEditAuthorization.roleMayCancel(0)); // INVALID
    }
}
