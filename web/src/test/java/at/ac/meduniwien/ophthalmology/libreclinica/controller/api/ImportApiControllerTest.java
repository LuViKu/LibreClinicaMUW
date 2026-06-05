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
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;

/**
 * Phase E.6 {@code bulk-import} — auth + shape contract surface for
 * {@link ImportApiController}.
 *
 * <p>Covered via {@code MockMvc standaloneSetup} + the test-only no-arg
 * constructor: 401 anon, 400 no-study, 403 wrong role (MONITOR — the
 * only role NOT in the upload allowlist), 415 non-XML, 400 empty file,
 * 400 missing token, 410 unknown token, 200 happy-path empty body
 * (for the rows endpoint).
 *
 * <p>DAO-bound paths (ODM unmarshal success, metadata validation
 * success, audit write, future persistence) defer to the MockMvc IT
 * cohort — they need Testcontainers + the full Spring root context.
 */
class ImportApiControllerTest extends AbstractApiControllerTest {

    private MockMvc mockMvcWith() {
        return mockMvcFor(new ImportApiController());
    }

    /* ----------------------------------------------------------------- */
    /* POST /api/v1/import (multipart)                                    */
    /* ----------------------------------------------------------------- */

    @Test
    void uploadReturns401WhenAnonymous() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "import.xml", MediaType.APPLICATION_XML_VALUE, "<ODM/>".getBytes());
        mockMvcWith().perform(multipart("/api/v1/import")
                .file(file)
                .session((MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void uploadReturns400WhenNoActiveStudy() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "import.xml", MediaType.APPLICATION_XML_VALUE, "<ODM/>".getBytes());
        mockMvcWith().perform(multipart("/api/v1/import")
                .file(file)
                .session((MockHttpSession) authenticatedSessionWithoutStudy(1, "root")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("No active study")));
    }

    @Test
    void uploadReturns403WhenMonitor() throws Exception {
        // MONITOR is the only role NOT in the bulk-import allowlist.
        // Sysadmin + Director/Coordinator/Investigator/RA/RA2 all pass.
        MockMultipartFile file = new MockMultipartFile(
                "file", "import.xml", MediaType.APPLICATION_XML_VALUE, "<ODM/>".getBytes());
        mockMvcWith().perform(multipart("/api/v1/import")
                .file(file)
                .session((MockHttpSession) authenticatedSessionWithRole(
                        2, "watcher", 1, "S_DEMO", "Demo", Role.MONITOR, 1)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message")
                        .value(containsString("does not permit")));
    }

    @Test
    void uploadReturns415OnWrongFileType() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "import.csv", "text/csv", "name,age\n".getBytes());
        mockMvcWith().perform(multipart("/api/v1/import")
                .file(file)
                .session((MockHttpSession) authenticatedSysadminSession(
                        1, "root", 1, "S_DEMO", "Demo")))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.message")
                        .value(containsString(".xml")));
    }

    @Test
    void uploadReturns400OnEmptyFile() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "import.xml", MediaType.APPLICATION_XML_VALUE, new byte[0]);
        mockMvcWith().perform(multipart("/api/v1/import")
                .file(file)
                .session((MockHttpSession) authenticatedSysadminSession(
                        1, "root", 1, "S_DEMO", "Demo")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("file part is required")));
    }

    /* ----------------------------------------------------------------- */
    /* GET /api/v1/import/{token}/rows                                    */
    /* ----------------------------------------------------------------- */

    @Test
    void listRowsReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(get("/api/v1/import/any-token/rows")
                .session((MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listRowsReturns403WhenMonitor() throws Exception {
        mockMvcWith().perform(get("/api/v1/import/any-token/rows")
                .session((MockHttpSession) authenticatedSessionWithRole(
                        2, "watcher", 1, "S_DEMO", "Demo", Role.MONITOR, 1)))
                .andExpect(status().isForbidden());
    }

    @Test
    void listRowsReturns410OnUnknownToken() throws Exception {
        mockMvcWith().perform(get("/api/v1/import/does-not-exist/rows")
                .session((MockHttpSession) authenticatedSysadminSession(
                        1, "root", 1, "S_DEMO", "Demo")))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.message")
                        .value(containsString("unknown")));
    }

    /* ----------------------------------------------------------------- */
    /* POST /api/v1/import/commit                                         */
    /* ----------------------------------------------------------------- */

    @Test
    void commitReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(post("/api/v1/import/commit")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"previewToken\":\"any\"}")
                .session((MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void commitReturns403WhenMonitor() throws Exception {
        mockMvcWith().perform(post("/api/v1/import/commit")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"previewToken\":\"any\"}")
                .session((MockHttpSession) authenticatedSessionWithRole(
                        2, "watcher", 1, "S_DEMO", "Demo", Role.MONITOR, 1)))
                .andExpect(status().isForbidden());
    }

    @Test
    void commitReturns400OnMissingPreviewToken() throws Exception {
        mockMvcWith().perform(post("/api/v1/import/commit")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .session((MockHttpSession) authenticatedSysadminSession(
                        1, "root", 1, "S_DEMO", "Demo")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.errors[0].field").value("previewToken"));
    }

    @Test
    void commitReturns400OnBlankPreviewToken() throws Exception {
        mockMvcWith().perform(post("/api/v1/import/commit")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"previewToken\":\"   \"}")
                .session((MockHttpSession) authenticatedSysadminSession(
                        1, "root", 1, "S_DEMO", "Demo")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("previewToken"));
    }

    @Test
    void commitReturns410OnUnknownToken() throws Exception {
        mockMvcWith().perform(post("/api/v1/import/commit")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"previewToken\":\"does-not-exist\"}")
                .session((MockHttpSession) authenticatedSysadminSession(
                        1, "root", 1, "S_DEMO", "Demo")))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.message")
                        .value(containsString("unknown")));
    }
}
