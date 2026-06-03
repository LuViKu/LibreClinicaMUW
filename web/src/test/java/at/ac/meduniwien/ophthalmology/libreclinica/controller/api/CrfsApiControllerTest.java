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
import org.mockito.Mockito;
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
        return mockMvcFor(new CrfsApiController(mockDataSource(),
                Mockito.mock(CrfSpreadsheetParserService.class),
                new CrfJsonToWorkbookAdapter(),
                new CrfJsonValidator()));
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

    /* ----------------------------------------------------------------- */
    /* Phase E.6 Milestone A — JSON authoring endpoint guards             */
    /* ----------------------------------------------------------------- */

    /**
     * Minimal authoring payload that passes shape validation. Used to
     * exercise auth + content-type guards without tripping the
     * validator.
     */
    private static final String MINIMAL_AUTHORING_JSON = "{"
            + "\"versionName\":\"v1.0\","
            + "\"versionDescription\":\"Demo\","
            + "\"revisionNotes\":\"Initial\","
            + "\"sections\":[{"
            + "  \"label\":\"S1\",\"title\":\"Section 1\",\"instructions\":\"\",\"ordinal\":1,"
            + "  \"items\":[{"
            + "    \"name\":\"AGE\",\"oid\":\"\",\"descriptionLabel\":\"Age\","
            + "    \"leftItemText\":\"Age in years\",\"dataType\":\"INTEGER\",\"required\":true"
            + "  }]"
            + "}]"
            + "}";

    @Test
    void authorVersionReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(post("/api/v1/crfs/F_DEMOS/versions")
                .contentType("application/json")
                .content(MINIMAL_AUTHORING_JSON)
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void authorVersionReturns403WhenInvestigatorAttempts() throws Exception {
        mockMvcWith().perform(post("/api/v1/crfs/F_DEMOS/versions")
                .contentType("application/json")
                .content(MINIMAL_AUTHORING_JSON)
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSessionWithRole(2, "physician", 1, "S_DEFAULTS1",
                                "Default Study",
                                at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role.INVESTIGATOR, 1)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message")
                        .value(containsString("does not permit managing CRFs")));
    }

    @Test
    void authorVersionReturns415OnXmlContentType() throws Exception {
        // Spring routes the JSON variant on Content-Type:application/json;
        // anything else falls through to the multipart variant which then
        // rejects with 415 due to the missing multipart payload.
        mockMvcWith().perform(post("/api/v1/crfs/F_DEMOS/versions")
                .contentType("application/xml")
                .content("<crf/>")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSysadminSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void authorVersionReturns400OnMissingVersionName() throws Exception {
        String body = "{"
                + "\"versionName\":\"\","
                + "\"sections\":[{"
                + "  \"label\":\"S1\",\"title\":\"Section 1\",\"ordinal\":1,"
                + "  \"items\":[{\"name\":\"AGE\",\"descriptionLabel\":\"Age\",\"dataType\":\"INTEGER\",\"required\":true}]"
                + "}]"
                + "}";
        mockMvcWith().perform(post("/api/v1/crfs/F_DEMOS/versions")
                .contentType("application/json")
                .content(body)
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSysadminSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("Validation failed")))
                .andExpect(jsonPath("$.errors[0].field").value("versionName"));
    }

    @Test
    void authorVersionReturns400OnEmptySections() throws Exception {
        String body = "{"
                + "\"versionName\":\"v1.0\","
                + "\"sections\":[]"
                + "}";
        mockMvcWith().perform(post("/api/v1/crfs/F_DEMOS/versions")
                .contentType("application/json")
                .content(body)
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSysadminSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field")
                        .value(org.hamcrest.Matchers.hasItem("sections")));
    }

    @Test
    void authorVersionReturns400OnInvalidDataType() throws Exception {
        String body = "{"
                + "\"versionName\":\"v1.0\","
                + "\"sections\":[{"
                + "  \"label\":\"S1\",\"title\":\"Section 1\",\"ordinal\":1,"
                + "  \"items\":[{\"name\":\"AGE\",\"descriptionLabel\":\"Age\",\"dataType\":\"XYZ\",\"required\":true}]"
                + "}]"
                + "}";
        mockMvcWith().perform(post("/api/v1/crfs/F_DEMOS/versions")
                .contentType("application/json")
                .content(body)
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSysadminSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field")
                        .value(org.hamcrest.Matchers.hasItem("sections[0].items[0].dataType")));
    }

    /* ----------------------------------------------------------------- */
    /* Phase E.6 Milestone B — full taxonomy + response-set + validation  */
    /* ----------------------------------------------------------------- */

    @Test
    void authorVersionReturns400OnInvalidResponseType() throws Exception {
        // Milestone B accepts every non-formula ResponseType; calculation
        // variants belong to Milestone C and should be rejected at the
        // shape layer.
        String body = "{"
                + "\"versionName\":\"v1.0\","
                + "\"sections\":[{"
                + "  \"label\":\"S1\",\"title\":\"Section 1\",\"ordinal\":1,"
                + "  \"items\":[{"
                + "    \"name\":\"AGE\",\"descriptionLabel\":\"Age\","
                + "    \"dataType\":\"INTEGER\",\"required\":false,"
                + "    \"responseSet\":{\"type\":\"calculation\",\"label\":\"calc1\"}"
                + "  }]"
                + "}]"
                + "}";
        mockMvcWith().perform(post("/api/v1/crfs/F_DEMOS/versions")
                .contentType("application/json")
                .content(body)
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSysadminSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field")
                        .value(org.hamcrest.Matchers.hasItem("sections[0].items[0].responseSet.type")));
    }

    @Test
    void authorVersionReturns400OnChoiceTypeWithoutOptions() throws Exception {
        String body = "{"
                + "\"versionName\":\"v1.0\","
                + "\"sections\":[{"
                + "  \"label\":\"S1\",\"title\":\"Section 1\",\"ordinal\":1,"
                + "  \"items\":[{"
                + "    \"name\":\"SEX\",\"descriptionLabel\":\"Sex\","
                + "    \"dataType\":\"ST\",\"required\":true,"
                + "    \"responseSet\":{\"type\":\"single-select\",\"label\":\"sex\"}"
                + "  }]"
                + "}]"
                + "}";
        mockMvcWith().perform(post("/api/v1/crfs/F_DEMOS/versions")
                .contentType("application/json")
                .content(body)
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSysadminSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field")
                        .value(org.hamcrest.Matchers.hasItem("sections[0].items[0].responseSet.options")));
    }

    @Test
    void authorVersionReturns400OnNumericOptionValueOnIntegerItem() throws Exception {
        String body = "{"
                + "\"versionName\":\"v1.0\","
                + "\"sections\":[{"
                + "  \"label\":\"S1\",\"title\":\"Section 1\",\"ordinal\":1,"
                + "  \"items\":[{"
                + "    \"name\":\"AGE_BAND\",\"descriptionLabel\":\"Age band\","
                + "    \"dataType\":\"INTEGER\",\"required\":false,"
                + "    \"responseSet\":{\"type\":\"radio\",\"label\":\"age_band\","
                + "      \"options\":[{\"text\":\"Young\",\"value\":\"abc\"}]}"
                + "  }]"
                + "}]"
                + "}";
        mockMvcWith().perform(post("/api/v1/crfs/F_DEMOS/versions")
                .contentType("application/json")
                .content(body)
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSysadminSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field")
                        .value(org.hamcrest.Matchers.hasItem(
                                "sections[0].items[0].responseSet.options[0].value")));
    }

    @Test
    void authorVersionReturns400OnRegexpWithoutErrorMessage() throws Exception {
        String body = "{"
                + "\"versionName\":\"v1.0\","
                + "\"sections\":[{"
                + "  \"label\":\"S1\",\"title\":\"Section 1\",\"ordinal\":1,"
                + "  \"items\":[{"
                + "    \"name\":\"PHONE\",\"descriptionLabel\":\"Phone\","
                + "    \"dataType\":\"ST\",\"required\":false,"
                + "    \"validation\":{\"regexp\":\"^[0-9]+$\"}"
                + "  }]"
                + "}]"
                + "}";
        mockMvcWith().perform(post("/api/v1/crfs/F_DEMOS/versions")
                .contentType("application/json")
                .content(body)
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSysadminSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field")
                        .value(org.hamcrest.Matchers.hasItem(
                                "sections[0].items[0].validation.errorMessage")));
    }

    @Test
    void authorVersionReturns400OnInvalidRegexp() throws Exception {
        String body = "{"
                + "\"versionName\":\"v1.0\","
                + "\"sections\":[{"
                + "  \"label\":\"S1\",\"title\":\"Section 1\",\"ordinal\":1,"
                + "  \"items\":[{"
                + "    \"name\":\"PHONE\",\"descriptionLabel\":\"Phone\","
                + "    \"dataType\":\"ST\",\"required\":false,"
                + "    \"validation\":{\"regexp\":\"([0-9\",\"errorMessage\":\"Bad\"}"
                + "  }]"
                + "}]"
                + "}";
        mockMvcWith().perform(post("/api/v1/crfs/F_DEMOS/versions")
                .contentType("application/json")
                .content(body)
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSysadminSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field")
                        .value(org.hamcrest.Matchers.hasItem(
                                "sections[0].items[0].validation.regexp")));
    }

    @Test
    void authorVersionReturns400OnDefaultValueNotInOptions() throws Exception {
        String body = "{"
                + "\"versionName\":\"v1.0\","
                + "\"sections\":[{"
                + "  \"label\":\"S1\",\"title\":\"Section 1\",\"ordinal\":1,"
                + "  \"items\":[{"
                + "    \"name\":\"SEX\",\"descriptionLabel\":\"Sex\","
                + "    \"dataType\":\"ST\",\"required\":false,"
                + "    \"defaultValue\":\"X\","
                + "    \"responseSet\":{\"type\":\"single-select\",\"label\":\"sex\","
                + "      \"options\":[{\"text\":\"Male\",\"value\":\"M\"},{\"text\":\"Female\",\"value\":\"F\"}]}"
                + "  }]"
                + "}]"
                + "}";
        mockMvcWith().perform(post("/api/v1/crfs/F_DEMOS/versions")
                .contentType("application/json")
                .content(body)
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSysadminSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field")
                        .value(org.hamcrest.Matchers.hasItem(
                                "sections[0].items[0].defaultValue")));
    }

    /* ----------------------------------------------------------------- */
    /* Phase E.6 Milestone B — :preview endpoint                          */
    /* ----------------------------------------------------------------- */

    @Test
    void previewVersionReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(post("/api/v1/crfs/F_DEMOS/versions:preview")
                .contentType("application/json")
                .content(MINIMAL_AUTHORING_JSON)
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void previewVersionReturns403WhenInvestigatorAttempts() throws Exception {
        mockMvcWith().perform(post("/api/v1/crfs/F_DEMOS/versions:preview")
                .contentType("application/json")
                .content(MINIMAL_AUTHORING_JSON)
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSessionWithRole(2, "physician", 1, "S_DEFAULTS1",
                                "Default Study",
                                at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role.INVESTIGATOR, 1)))
                .andExpect(status().isForbidden());
    }

    @Test
    void previewVersionReturns400OnMissingVersionName() throws Exception {
        String body = "{"
                + "\"versionName\":\"\","
                + "\"sections\":[{"
                + "  \"label\":\"S1\",\"title\":\"Section 1\",\"ordinal\":1,"
                + "  \"items\":[{\"name\":\"AGE\",\"descriptionLabel\":\"Age\",\"dataType\":\"INTEGER\",\"required\":true}]"
                + "}]"
                + "}";
        mockMvcWith().perform(post("/api/v1/crfs/F_DEMOS/versions:preview")
                .contentType("application/json")
                .content(body)
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSysadminSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("versionName"));
    }

    @Test
    void previewVersionReturns400OnMissingBody() throws Exception {
        mockMvcWith().perform(post("/api/v1/crfs/F_DEMOS/versions:preview")
                .contentType("application/json")
                .content("")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSysadminSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isBadRequest());
    }
}
