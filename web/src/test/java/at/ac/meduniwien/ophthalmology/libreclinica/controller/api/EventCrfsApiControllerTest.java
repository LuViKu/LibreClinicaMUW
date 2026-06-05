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
import at.ac.meduniwien.ophthalmology.libreclinica.service.crf.EventCrfPresenceRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Phase E.5 A2 — MockMvc IT pinning the {@link EventCrfsApiController}
 * session-guard + body-validation contract surface.
 *
 * <p>What this pins:
 * <ul>
 *   <li>{@code GET /api/v1/eventCrfs/{id}} returns {@code 401} when
 *       anonymous, {@code 400} when no active study is bound.</li>
 *   <li>{@code POST /api/v1/eventCrfs/{id}/items} returns {@code 400}
 *       on missing/null {@code values} body.</li>
 *   <li>{@code POST /api/v1/eventCrfs/{id}/markComplete} returns
 *       {@code 401} when anonymous, {@code 400} when no active study.</li>
 * </ul>
 *
 * <p>DAO-touching paths (409 already-complete, 404 unknown id, etc.)
 * require Testcontainers Postgres — out of scope for this cut.
 */
class EventCrfsApiControllerTest extends AbstractApiControllerTest {

    private MockMvc mockMvcWith() {
        return mockMvcFor(new EventCrfsApiController(mockDataSource(),
                Mockito.mock(SiteVisibilityFilter.class),
                new EventCrfPresenceRegistry()));
    }

    /* ---------------------------------------------------------------------- */
    /* GET /api/v1/eventCrfs/{id}                                             */
    /* ---------------------------------------------------------------------- */

    @Test
    void getEventCrfReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(get("/api/v1/eventCrfs/1")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getEventCrfReturns400WhenNoActiveStudy() throws Exception {
        mockMvcWith().perform(get("/api/v1/eventCrfs/1")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSessionWithoutStudy(1, "root")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("No active study")));
    }

    /* ---------------------------------------------------------------------- */
    /* POST /api/v1/eventCrfs/{id}/items                                      */
    /* ---------------------------------------------------------------------- */

    @Test
    void saveItemsReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(post("/api/v1/eventCrfs/1/items")
                .contentType("application/json")
                .content("{\"values\":{}}")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void saveItemsReturns400OnMissingValues() throws Exception {
        // Body present but `values` is null — the controller's guard:
        //   body == null || body.values() == null
        mockMvcWith().perform(post("/api/v1/eventCrfs/1/items")
                .contentType("application/json")
                .content("{}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("Missing 'values'")));
    }

    @Test
    void saveItemsReturns400WhenNoActiveStudy() throws Exception {
        mockMvcWith().perform(post("/api/v1/eventCrfs/1/items")
                .contentType("application/json")
                .content("{\"values\":{}}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSessionWithoutStudy(1, "root")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("No active study")));
    }

    /* ---------------------------------------------------------------------- */
    /* POST /api/v1/eventCrfs/{id}/markComplete                               */
    /* ---------------------------------------------------------------------- */

    @Test
    void markCompleteReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(post("/api/v1/eventCrfs/1/markComplete")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void markCompleteReturns400WhenNoActiveStudy() throws Exception {
        mockMvcWith().perform(post("/api/v1/eventCrfs/1/markComplete")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSessionWithoutStudy(1, "root")))
                .andExpect(status().isBadRequest());
    }

    /* ---------------------------------------------------------------------- */
    /* POST /api/v1/eventCrfs/{id}/markIncomplete (Phase E A5)                */
    /* ---------------------------------------------------------------------- */

    @Test
    void markIncompleteReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(post("/api/v1/eventCrfs/1/markIncomplete")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void markIncompleteReturns400WhenNoActiveStudy() throws Exception {
        mockMvcWith().perform(post("/api/v1/eventCrfs/1/markIncomplete")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSessionWithoutStudy(1, "root")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("No active study")));
    }

    @Test
    void markIncompleteReturns500WhenLookupFailsAgainstMockDataSource() throws Exception {
        // The mock DataSource throws NPE on getConnection(); the
        // ApiExceptionHandler wraps it to 500. Pins that the request
        // reached the post-auth/post-study guard layer (i.e. not
        // blocked earlier). Real-DB coverage of the 200/404/409/403
        // branches lives in EventCrfsApiControllerDatabaseIT (queued
        // behind the broader Testcontainers IT slice).
        mockMvcWith().perform(post("/api/v1/eventCrfs/9999/markIncomplete")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").exists());
    }

    /* ---------------------------------------------------------------------- */
    /* CrfReopenAuthorization — pure unit-level role coverage                 */
    /* ---------------------------------------------------------------------- */

    @Test
    void crfReopenAuth_PermittedRoles() {
        // STUDYDIRECTOR (3) = Data Manager
        org.junit.jupiter.api.Assertions.assertTrue(
                CrfReopenAuthorization.roleMayReopen(3));
        // ADMIN (1)
        org.junit.jupiter.api.Assertions.assertTrue(
                CrfReopenAuthorization.roleMayReopen(1));
        // INVESTIGATOR (4)
        org.junit.jupiter.api.Assertions.assertTrue(
                CrfReopenAuthorization.roleMayReopen(4));
        // COORDINATOR (2) = CRC
        org.junit.jupiter.api.Assertions.assertTrue(
                CrfReopenAuthorization.roleMayReopen(2));
    }

    @Test
    void crfReopenAuth_ForbiddenRoles() {
        // MONITOR (6) — monitors verify, don't edit
        org.junit.jupiter.api.Assertions.assertFalse(
                CrfReopenAuthorization.roleMayReopen(6));
        // RA (5) — data entry, not data correction
        org.junit.jupiter.api.Assertions.assertFalse(
                CrfReopenAuthorization.roleMayReopen(5));
        // RA2 (7)
        org.junit.jupiter.api.Assertions.assertFalse(
                CrfReopenAuthorization.roleMayReopen(7));
        // INVALID / no role (0)
        org.junit.jupiter.api.Assertions.assertFalse(
                CrfReopenAuthorization.roleMayReopen(0));
    }
}
