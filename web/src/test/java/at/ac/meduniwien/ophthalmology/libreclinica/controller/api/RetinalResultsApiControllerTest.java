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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RetinalArtifactStorageService;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Phase E.7 Wave 3 — MockMvc IT pinning the session-guard contract
 * surface of {@link RetinalResultsApiController}.
 *
 * <p>This is the default-profile slice: session guards (401/400),
 * path-traversal validation (400). Happy-path tests that need a real
 * DataSource live in {@code RetinalResultsApiControllerDatabaseIT}
 * (integration-tests profile).
 */
class RetinalResultsApiControllerTest extends AbstractApiControllerTest {

    private MockMvc mockMvcWith() {
        return mockMvcFor(new RetinalResultsApiController(
                mockDataSource(),
                Mockito.mock(SiteVisibilityFilter.class),
                Mockito.mock(RetinalArtifactStorageService.class)));
    }

    /* ---------------------------------------------------------------------- */
    /* GET /api/v1/retinal-jobs/{jobId}                                       */
    /* ---------------------------------------------------------------------- */

    @Test
    void getJobReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(get("/api/v1/retinal-jobs/1")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getJobReturns400WhenNoActiveStudy() throws Exception {
        mockMvcWith().perform(get("/api/v1/retinal-jobs/1")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSessionWithoutStudy(1, "root")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("No active study")));
    }

    /* ---------------------------------------------------------------------- */
    /* GET /api/v1/event-crfs/{eventCrfId}/retinal-jobs                       */
    /* ---------------------------------------------------------------------- */

    @Test
    void listByEventCrfReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(get("/api/v1/event-crfs/1/retinal-jobs")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listByEventCrfReturns400WhenNoActiveStudy() throws Exception {
        mockMvcWith().perform(get("/api/v1/event-crfs/1/retinal-jobs")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSessionWithoutStudy(1, "root")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("No active study")));
    }

    /* ---------------------------------------------------------------------- */
    /* GET /api/v1/study-subjects/{ssId}/retinal-jobs                         */
    /* ---------------------------------------------------------------------- */

    @Test
    void listByStudySubjectReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(get("/api/v1/study-subjects/1/retinal-jobs")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listByStudySubjectReturns400WhenNoActiveStudy() throws Exception {
        mockMvcWith().perform(get("/api/v1/study-subjects/1/retinal-jobs")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSessionWithoutStudy(1, "root")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("No active study")));
    }

    /* ---------------------------------------------------------------------- */
    /* GET /api/v1/study-subjects/{ssId}/retinal-trends — Wave 2A             */
    /* ---------------------------------------------------------------------- */

    @Test
    void trendsReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(get("/api/v1/study-subjects/1/retinal-trends")
                .param("task", "fluid")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void trendsReturns400WhenNoActiveStudy() throws Exception {
        mockMvcWith().perform(get("/api/v1/study-subjects/1/retinal-trends")
                .param("task", "fluid")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSessionWithoutStudy(1, "root")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("No active study")));
    }

    @Test
    void trendsReturns400WhenTaskInvalid() throws Exception {
        mockMvcWith().perform(get("/api/v1/study-subjects/1/retinal-trends")
                .param("task", "not-a-task")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("task must be one of")));
    }

    /* ---------------------------------------------------------------------- */
    /* GET /api/v1/retinal-jobs/{jobId}/artifacts/{name}                      */
    /* ---------------------------------------------------------------------- */

    @Test
    void streamArtifactReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(get("/api/v1/retinal-jobs/1/artifacts/fluidseg.npz")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void streamArtifactReturns400OnPathTraversal() throws Exception {
        // The traversal name "..%2Fetc%2Fpasswd" decodes to "../etc/passwd".
        // Spring's path-variable URL decoding hits the SAFE_ARTIFACT_NAME guard.
        mockMvcWith().perform(get("/api/v1/retinal-jobs/1/artifacts/..%2Fetc%2Fpasswd")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("disallowed characters")));
    }

    @Test
    void streamArtifactReturns400OnSlashInName() throws Exception {
        // A literal forward-slash inside the filename portion can't reach
        // the controller (Spring resolves it as a separator). But a name
        // with an encoded slash or a star character lands and trips the
        // allow-list. Use a bare "*" — single segment, fails the regex.
        mockMvcWith().perform(get("/api/v1/retinal-jobs/1/artifacts/foo*bar")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("disallowed characters")));
    }
}
