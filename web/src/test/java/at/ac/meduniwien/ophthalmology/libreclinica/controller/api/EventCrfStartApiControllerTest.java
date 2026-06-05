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

import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Phase E.6 — MockMvc IT for the new
 * {@code POST /api/v1/events/{id}/crfs/{edcId}:start} endpoint
 * (in-SPA CRF entry replacement for the legacy
 * {@code /pages/EnterDataForStudyEvent} JSP bridge).
 *
 * <p>What this pins:
 * <ul>
 *   <li>{@code 401} when the session has no userBean.</li>
 *   <li>{@code 400} when no active study is bound.</li>
 *   <li>URL routing — the action-style {@code :start} suffix resolves
 *       through Spring MVC under the {@code /pages} dispatcher.</li>
 * </ul>
 *
 * <p>Happy-path + 404 (unknown event / edc) + 403 (visibility) +
 * 409 (already-started) coverage ride on the Testcontainers Postgres
 * follow-up alongside the parent controller's 200 pin (existing
 * {@link EventsApiControllerTest} note).
 */
class EventCrfStartApiControllerTest extends AbstractApiControllerTest {

    private MockMvc mockMvcWith() {
        return mockMvcFor(new EventsApiController(mockDataSource(),
                Mockito.mock(SiteVisibilityFilter.class)));
    }

    @Test
    void startReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(post("/api/v1/events/42/crfs/100:start")
                .contentType("application/json")
                .content("{}")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message")
                        .value(containsString("Not authenticated")));
    }

    @Test
    void startReturns400WhenNoActiveStudy() throws Exception {
        mockMvcWith().perform(post("/api/v1/events/42/crfs/100:start")
                .contentType("application/json")
                .content("{}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSessionWithoutStudy(1, "root")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("No active study")));
    }

    @Test
    void startReturns401EvenWithEmptyBodyAnonymous() throws Exception {
        // Body is optional (crfVersionId defaults to edc.default_version_id);
        // the auth check must short-circuit before the body is read.
        mockMvcWith().perform(post("/api/v1/events/42/crfs/100:start")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }
}
