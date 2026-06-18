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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RemoteRetinalInferenceClient;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RetinalArtifactStorageService;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RetinalInferenceClient;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RetinalJobStatusBroadcaster;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.metrics.RetinalMetricComputer;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Phase E.7 Wave 3 — MockMvc IT pinning the session-guard + request-shape
 * contract surface of {@link RetinalInferenceApiController}.
 *
 * <p>DAO-touching happy paths require a live Postgres + filesystem
 * fixtures (the controller writes the .e2e and INSERTs the job row);
 * those tests live in {@code RetinalInferenceApiControllerDatabaseIT}
 * (integration-tests profile). This default-profile slice covers:
 *
 * <ul>
 *   <li>{@code 401} when anonymous.</li>
 *   <li>{@code 400} when no active study bound.</li>
 *   <li>{@code 400} on unsupported task / laterality.</li>
 * </ul>
 */
class RetinalInferenceApiControllerTest extends AbstractApiControllerTest {

    private MockMvc mockMvcWith() {
        return mockMvcFor(new RetinalInferenceApiController(
                mockDataSource(),
                Mockito.mock(SiteVisibilityFilter.class),
                Mockito.mock(RetinalInferenceClient.class),
                Mockito.mock(RemoteRetinalInferenceClient.class),
                Mockito.mock(RetinalArtifactStorageService.class),
                Mockito.mock(RetinalMetricComputer.class),
                Mockito.mock(RetinalJobStatusBroadcaster.class)));
    }

    private static MockMultipartFile sampleE2e() {
        return new MockMultipartFile("file", "scan.e2e",
                "application/octet-stream", new byte[]{0x01, 0x02, 0x03});
    }

    @Test
    void octUploadReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(multipart("/api/v1/event-crfs/1/oct-upload")
                .file(sampleE2e())
                .param("task", "fluid")
                .param("laterality", "OD")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void octUploadReturns400WhenNoActiveStudy() throws Exception {
        mockMvcWith().perform(multipart("/api/v1/event-crfs/1/oct-upload")
                .file(sampleE2e())
                .param("task", "fluid")
                .param("laterality", "OD")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSessionWithoutStudy(1, "root")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("No active study")));
    }

    @Test
    void octUploadReturns400OnUnsupportedTask() throws Exception {
        mockMvcWith().perform(multipart("/api/v1/event-crfs/1/oct-upload")
                .file(sampleE2e())
                .param("task", "not-a-task")
                .param("laterality", "OD")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("Unsupported task")));
    }

    @Test
    void octUploadReturns400OnUnsupportedLaterality() throws Exception {
        mockMvcWith().perform(multipart("/api/v1/event-crfs/1/oct-upload")
                .file(sampleE2e())
                .param("task", "fluid")
                .param("laterality", "OU")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("laterality")));
    }

    @Test
    void octUploadReturns400OnNegativeScanIndex() throws Exception {
        mockMvcWith().perform(multipart("/api/v1/event-crfs/1/oct-upload")
                .file(sampleE2e())
                .param("task", "fluid")
                .param("laterality", "OD")
                .param("scanIndex", "-1")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("scanIndex")));
    }
}
