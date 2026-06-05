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
                new CrfJsonValidator(),
                Mockito.mock(at.ac.meduniwien.ophthalmology.libreclinica.service.CrfVersionMigrationService.class)));
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
        // Milestone B accepted every non-formula ResponseType; Milestone
        // C extends the vocabulary with the three calculation variants.
        // A response set with an unrecognised type must still be
        // rejected at the type layer.
        String body = "{"
                + "\"versionName\":\"v1.0\","
                + "\"sections\":[{"
                + "  \"label\":\"S1\",\"title\":\"Section 1\",\"ordinal\":1,"
                + "  \"items\":[{"
                + "    \"name\":\"AGE\",\"descriptionLabel\":\"Age\","
                + "    \"dataType\":\"INTEGER\",\"required\":false,"
                + "    \"responseSet\":{\"type\":\"nonsense\",\"label\":\"calc1\"}"
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

    /* ----------------------------------------------------------------- */
    /* Phase E.6 Milestone C — :validate-expression endpoint              */
    /* ----------------------------------------------------------------- */

    @Test
    void validateExpressionReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(post("/api/v1/crfs/F_DEMOS/versions:validate-expression")
                .contentType("application/json")
                .content("{\"expression\":\"1 + 1\",\"draftItemOids\":[],\"draftItemDataTypes\":{}}")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validateExpressionReturns403WhenInvestigatorAttempts() throws Exception {
        mockMvcWith().perform(post("/api/v1/crfs/F_DEMOS/versions:validate-expression")
                .contentType("application/json")
                .content("{\"expression\":\"1 + 1\",\"draftItemOids\":[],\"draftItemDataTypes\":{}}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSessionWithRole(2, "physician", 1, "S_DEFAULTS1",
                                "Default Study",
                                at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role.INVESTIGATOR, 1)))
                .andExpect(status().isForbidden());
    }

    @Test
    void validateExpressionReturns400OnMissingBody() throws Exception {
        mockMvcWith().perform(post("/api/v1/crfs/F_DEMOS/versions:validate-expression")
                .contentType("application/json")
                .content("")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSysadminSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void validateExpressionReturns400OnMissingExpressionField() throws Exception {
        // body shape is valid JSON but expression is null — shape failure
        // surfaces as 400 with a field-keyed error.
        mockMvcWith().perform(post("/api/v1/crfs/F_DEMOS/versions:validate-expression")
                .contentType("application/json")
                .content("{\"draftItemOids\":[],\"draftItemDataTypes\":{}}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSysadminSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("expression"));
    }

    @Test
    void validateExpressionReturns200OnValidFormula() throws Exception {
        // M-C — a syntactically valid formula referencing only draft
        // OIDs returns 200 with valid:true.
        String body = "{"
                + "\"expression\":\"AGE + 1\","
                + "\"draftItemOids\":[\"AGE\"],"
                + "\"draftItemDataTypes\":{\"AGE\":\"INT\"}"
                + "}";
        mockMvcWith().perform(post("/api/v1/crfs/F_DEMOS/versions:validate-expression")
                .contentType("application/json")
                .content(body)
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSysadminSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.referencedOids[0]").value("AGE"));
    }

    @Test
    void validateExpressionReturns200ValidFalseOnSyntaxError() throws Exception {
        // M-C — broken syntax returns 200 with valid:false + an error
        // message. The endpoint never returns 4xx for in-body validation
        // failures — those land in the response body.
        String body = "{"
                + "\"expression\":\"(1 + 2\","
                + "\"draftItemOids\":[],"
                + "\"draftItemDataTypes\":{}"
                + "}";
        mockMvcWith().perform(post("/api/v1/crfs/F_DEMOS/versions:validate-expression")
                .contentType("application/json")
                .content(body)
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSysadminSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.errorMessage")
                        .value(containsString("Expression failed to parse")));
    }

    @Test
    void validateExpressionResolvesAgainstDraftOidsNotInDb() throws Exception {
        // M-C — the endpoint never touches the DB; OIDs referenced in
        // the expression are resolved against the in-flight draft scope
        // the SPA supplies. Mid-authoring OIDs that don't yet exist as
        // item rows still resolve.
        String body = "{"
                + "\"expression\":\"WEIGHT_KG / HEIGHT_M\","
                + "\"draftItemOids\":[\"WEIGHT_KG\",\"HEIGHT_M\"],"
                + "\"draftItemDataTypes\":{\"WEIGHT_KG\":\"REAL\",\"HEIGHT_M\":\"REAL\"}"
                + "}";
        mockMvcWith().perform(post("/api/v1/crfs/F_NEWBORN/versions:validate-expression")
                .contentType("application/json")
                .content(body)
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSysadminSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));
    }

    @Test
    void validateExpressionReturns200ValidFalseOnUnknownOid() throws Exception {
        // M-C — formula parses cleanly but references an OID outside
        // the draft scope → valid:false (body, not status).
        String body = "{"
                + "\"expression\":\"AGE + WEIGHT\","
                + "\"draftItemOids\":[\"AGE\"],"
                + "\"draftItemDataTypes\":{\"AGE\":\"INT\"}"
                + "}";
        mockMvcWith().perform(post("/api/v1/crfs/F_DEMOS/versions:validate-expression")
                .contentType("application/json")
                .content(body)
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSysadminSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.errorMessage").value(containsString("WEIGHT")));
    }

    /* ----------------------------------------------------------------- */
    /* Phase E.6 crf-library — lock/unlock/restore guards                 */
    /* ----------------------------------------------------------------- */

    @org.junit.jupiter.api.Test
    void lockVersionReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(post("/api/v1/crfs/F_DEMOS/versions/F_DEMOS_V1/lock")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @org.junit.jupiter.api.Test
    void lockVersionReturns403WhenInvestigatorAttempts() throws Exception {
        mockMvcWith().perform(post("/api/v1/crfs/F_DEMOS/versions/F_DEMOS_V1/lock")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSessionWithRole(2, "physician", 1, "S_DEFAULTS1",
                                "Default Study",
                                at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role.INVESTIGATOR, 1)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message")
                        .value(containsString("does not permit managing CRFs")));
    }

    @org.junit.jupiter.api.Test
    void unlockVersionReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(post("/api/v1/crfs/F_DEMOS/versions/F_DEMOS_V1/unlock")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @org.junit.jupiter.api.Test
    void restoreVersionReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(post("/api/v1/crfs/F_DEMOS/versions/F_DEMOS_V1/restore")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @org.junit.jupiter.api.Test
    void restoreVersionReturns403WhenInvestigatorAttempts() throws Exception {
        mockMvcWith().perform(post("/api/v1/crfs/F_DEMOS/versions/F_DEMOS_V1/restore")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSessionWithRole(2, "physician", 1, "S_DEFAULTS1",
                                "Default Study",
                                at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role.INVESTIGATOR, 1)))
                .andExpect(status().isForbidden());
    }

    /* ----------------------------------------------------------------- */
    /* Phase E.6 crf-library — hard-remove sysadmin gate                   */
    /* ----------------------------------------------------------------- */

    @org.junit.jupiter.api.Test
    void hardRemoveReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/crfs/F_DEMOS/versions/F_DEMOS_V1")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @org.junit.jupiter.api.Test
    void hardRemoveReturns403WhenDirectorAttempts() throws Exception {
        // Lock/unlock/restore/migrate accept director — hard-remove
        // does NOT. The director session should be 403'd by the
        // sysadmin re-check INSIDE the endpoint (not the shared
        // preflight which would let them through).
        mockMvcWith().perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/crfs/F_DEMOS/versions/F_DEMOS_V1")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSessionWithRole(2, "director", 1, "S_DEFAULTS1",
                                "Default Study",
                                at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role.STUDYDIRECTOR, 1)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message")
                        .value(containsString("sysadmin-only")));
    }

    /* ----------------------------------------------------------------- */
    /* Phase E.6 crf-library — xls download auth                            */
    /* ----------------------------------------------------------------- */

    @org.junit.jupiter.api.Test
    void downloadXlsReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(get("/api/v1/crfs/F_DEMOS/versions/F_DEMOS_V1/xls")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    /* ----------------------------------------------------------------- */
    /* Phase E.6 crf-library — migrate endpoint                             */
    /* ----------------------------------------------------------------- */

    @org.junit.jupiter.api.Test
    void migrateReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(post("/api/v1/crfs/F_DEMOS/versions/V1/migrate-to/V2")
                .contentType("application/json")
                .content("{\"dryRun\":true}")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @org.junit.jupiter.api.Test
    void migrateReturns403WhenInvestigatorAttempts() throws Exception {
        mockMvcWith().perform(post("/api/v1/crfs/F_DEMOS/versions/V1/migrate-to/V2")
                .contentType("application/json")
                .content("{\"dryRun\":true}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSessionWithRole(2, "physician", 1, "S_DEFAULTS1",
                                "Default Study",
                                at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role.INVESTIGATOR, 1)))
                .andExpect(status().isForbidden());
    }

    @org.junit.jupiter.api.Test
    void migrateReturns400WhenFromAndToMatch() throws Exception {
        // Caught BEFORE DAO touches — short-circuits with no auth-state
        // dependency. The endpoint never tries to load V1 (which would
        // NPE on the mock DataSource); it rejects the path shape.
        mockMvcWith().perform(post("/api/v1/crfs/F_DEMOS/versions/V1/migrate-to/V1")
                .contentType("application/json")
                .content("{\"dryRun\":true}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSysadminSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("must differ")));
    }
}
