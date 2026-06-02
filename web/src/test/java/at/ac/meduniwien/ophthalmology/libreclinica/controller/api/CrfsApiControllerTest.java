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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Phase E A8.3 — MockMvc IT pinning {@link CrfsApiController} guards.
 *
 * <p>Happy-path paths (round-trip create → version upload → disable +
 * audit-row emission) need Testcontainers Postgres — deferred to
 * the IT infra slice.
 */
class CrfsApiControllerTest extends AbstractApiControllerTest {

    private MockMvc mockMvcWith() {
        return mockMvcFor(new CrfsApiController(mockDataSource()));
    }

    @Test
    void listReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(get("/api/v1/crfs")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(post("/api/v1/crfs")
                .contentType("application/json")
                .content("{\"name\":\"Demographics\"}")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createReturns403WhenInvestigatorAttempts() throws Exception {
        // Investigator is NOT in the sysadmin / director / coordinator
        // triad — refused 403 by the preflight.
        mockMvcWith().perform(post("/api/v1/crfs")
                .contentType("application/json")
                .content("{\"name\":\"Demographics\"}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSessionWithRole(2, "physician", 1, "S_DEFAULTS1",
                                "Default Study",
                                at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role.INVESTIGATOR, 1)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message")
                        .value(containsString("does not permit managing CRFs")));
    }

    @Test
    void disableReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(post("/api/v1/crfs/F_DEMOS/disable")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listVersionsReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(get("/api/v1/crfs/F_DEMOS/versions")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void uploadVersionReturns401WhenAnonymous() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "demo.xls", "application/vnd.ms-excel", new byte[]{0x1, 0x2});
        MockMultipartFile versionName = new MockMultipartFile(
                "versionName", "", "text/plain", "v1.0".getBytes());
        mockMvcWith().perform(multipart("/api/v1/crfs/F_DEMOS/versions")
                .file(file)
                .file(versionName)
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void uploadVersionReturns415OnWrongFileType() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "demo.pdf", "application/pdf", new byte[]{0x25, 0x50});
        MockMultipartFile versionName = new MockMultipartFile(
                "versionName", "", "text/plain", "v1.0".getBytes());
        mockMvcWith().perform(multipart("/api/v1/crfs/F_DEMOS/versions")
                .file(file)
                .file(versionName)
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSysadminSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.message")
                        .value(containsString(".xls / .xlsx")));
    }

    @Test
    void uploadVersionReturns400OnEmptyFile() throws Exception {
        MockMultipartFile emptyFile = new MockMultipartFile(
                "file", "demo.xls", "application/vnd.ms-excel", new byte[0]);
        MockMultipartFile versionName = new MockMultipartFile(
                "versionName", "", "text/plain", "v1.0".getBytes());
        mockMvcWith().perform(multipart("/api/v1/crfs/F_DEMOS/versions")
                .file(emptyFile)
                .file(versionName)
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSysadminSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("file part is required")));
    }

    @Test
    void disableVersionReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(post("/api/v1/crfs/F_DEMOS/versions/F_DEMOS_V1/disable")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }
}
