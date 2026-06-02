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

/**
 * Phase E RX.2 — session-guard + file-type contract surface for the
 * rules-XML import adapter.
 *
 * <p>Session guards (401 / 403) and shape guards (415 file type, 400
 * empty file, 400 missing token, 410 unknown token) are covered here
 * via {@code MockMvc standaloneSetup}. The
 * {@link RulesImportApiController#RulesImportApiController()}
 * test-only no-arg constructor lets us instantiate the controller
 * without wiring the four production collaborators — the contract
 * paths short-circuit before any of them get touched.
 *
 * <p>DAO-bound paths (XSD validation success, validator success,
 * commit persistence) need Testcontainers + the full Spring root
 * context — deferred to the MockMvc IT cohort.
 */
class RulesImportApiControllerTest extends AbstractApiControllerTest {

    private MockMvc mockMvcWith() {
        return mockMvcFor(new RulesImportApiController());
    }

    /* ----------------------------------------------------------------- */
    /* GET /api/v1/rules/template                                          */
    /* ----------------------------------------------------------------- */

    @Test
    void downloadTemplateReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(get("/api/v1/rules/template")
                .session((MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void downloadTemplateReturns400WhenNoActiveStudy() throws Exception {
        mockMvcWith().perform(get("/api/v1/rules/template")
                .session((MockHttpSession) authenticatedSessionWithoutStudy(1, "root")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("No active study")));
    }

    /* ----------------------------------------------------------------- */
    /* POST /api/v1/rules/import                                           */
    /* ----------------------------------------------------------------- */

    @Test
    void uploadImportReturns401WhenAnonymous() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "rules.xml", "application/xml", "<Rules/>".getBytes());
        mockMvcWith().perform(multipart("/api/v1/rules/import")
                .file(file)
                .session((MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void uploadImportReturns403WhenInvestigator() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "rules.xml", "application/xml", "<Rules/>".getBytes());
        mockMvcWith().perform(multipart("/api/v1/rules/import")
                .file(file)
                .session((MockHttpSession) authenticatedSessionWithRole(
                        2, "physician", 1, "S_DEMO", "Demo",
                        at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role.INVESTIGATOR, 1)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message")
                        .value(containsString("does not permit")));
    }

    @Test
    void uploadImportReturns415OnWrongFileType() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "rules.txt", "text/plain", "not xml".getBytes());
        mockMvcWith().perform(multipart("/api/v1/rules/import")
                .file(file)
                .session((MockHttpSession) authenticatedSysadminSession(
                        1, "root", 1, "S_DEMO", "Demo")))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.message")
                        .value(containsString(".xml")));
    }

    @Test
    void uploadImportReturns400OnEmptyFile() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "rules.xml", "application/xml", new byte[0]);
        mockMvcWith().perform(multipart("/api/v1/rules/import")
                .file(file)
                .session((MockHttpSession) authenticatedSysadminSession(
                        1, "root", 1, "S_DEMO", "Demo")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("file part is required")));
    }

    /* ----------------------------------------------------------------- */
    /* POST /api/v1/rules/import/commit                                    */
    /* ----------------------------------------------------------------- */

    @Test
    void commitImportReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(post("/api/v1/rules/import/commit")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"previewToken\":\"any\"}")
                .session((MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void commitImportReturns403WhenInvestigator() throws Exception {
        mockMvcWith().perform(post("/api/v1/rules/import/commit")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"previewToken\":\"any\"}")
                .session((MockHttpSession) authenticatedSessionWithRole(
                        2, "physician", 1, "S_DEMO", "Demo",
                        at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role.INVESTIGATOR, 1)))
                .andExpect(status().isForbidden());
    }

    @Test
    void commitImportReturns400OnMissingPreviewToken() throws Exception {
        mockMvcWith().perform(post("/api/v1/rules/import/commit")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .session((MockHttpSession) authenticatedSysadminSession(
                        1, "root", 1, "S_DEMO", "Demo")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.errors[0].field").value("previewToken"));
    }

    @Test
    void commitImportReturns400OnBlankPreviewToken() throws Exception {
        mockMvcWith().perform(post("/api/v1/rules/import/commit")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"previewToken\":\"   \"}")
                .session((MockHttpSession) authenticatedSysadminSession(
                        1, "root", 1, "S_DEMO", "Demo")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("previewToken"));
    }

    @Test
    void commitImportReturns410OnUnknownPreviewToken() throws Exception {
        mockMvcWith().perform(post("/api/v1/rules/import/commit")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"previewToken\":\"does-not-exist\"}")
                .session((MockHttpSession) authenticatedSysadminSession(
                        1, "root", 1, "S_DEMO", "Demo")))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.message")
                        .value(containsString("unknown")));
    }
}
