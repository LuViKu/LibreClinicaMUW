/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.dto.ValidationErrorBody;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Phase E A8.4 — MockMvc IT pinning {@link SitesApiController}
 * session + role + body validation guards.
 *
 * <p>DAO-bound paths (404 unknown parent, 409 site-not-top-level,
 * round-trip create / disable / restore, uniqueness conflicts) need
 * Testcontainers — deferred to the IT infra slice.
 */
class SitesApiControllerTest extends AbstractApiControllerTest {

    private MockMvc mockMvcWith() {
        return mockMvcFor(new SitesApiController(mockDataSource()));
    }

    @Test
    void listReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(get("/api/v1/studies/S_DEFAULTS1/sites")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(post("/api/v1/studies/S_DEFAULTS1/sites")
                .contentType("application/json")
                .content("{\"name\":\"Vienna\"}")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void updateReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(put("/api/v1/studies/S_DEFAULTS1/sites/S_VIE1")
                .contentType("application/json")
                .content("{}")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void disableReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(post("/api/v1/studies/S_DEFAULTS1/sites/S_VIE1/disable")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void disableReturns403WhenNonSysadminAttempts() throws Exception {
        mockMvcWith().perform(post("/api/v1/studies/S_DEFAULTS1/sites/S_VIE1/disable")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSessionWithRole(2, "physician", 1, "S_DEFAULTS1",
                                "Default Study",
                                at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role.INVESTIGATOR, 1)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("sysadmin only")));
    }

    @Test
    void restoreReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(post("/api/v1/studies/S_DEFAULTS1/sites/S_VIE1/restore")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    /* ---------------------------------------------------------------------- */
    /* Shape validation (CreateSiteRequest / UpdateSiteRequest)               */
    /* ---------------------------------------------------------------------- */

    @Test
    void createValidate_RequiresNameAndUniqueProtocolIdAndPi() {
        CreateSiteRequest body =
                new CreateSiteRequest(null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        java.util.List<ValidationErrorBody.FieldError> errors =
                callValidateCreateShape(body);
        long nameErrors = errors.stream().filter(e -> "name".equals(e.field())).count();
        long uidErrors = errors.stream().filter(e -> "uniqueProtocolId".equals(e.field())).count();
        long piErrors = errors.stream().filter(e -> "principalInvestigator".equals(e.field())).count();
        org.junit.jupiter.api.Assertions.assertEquals(1, nameErrors);
        org.junit.jupiter.api.Assertions.assertEquals(1, uidErrors);
        org.junit.jupiter.api.Assertions.assertEquals(1, piErrors);
    }

    @Test
    void createValidate_RejectsBadUniqueProtocolIdFormat() {
        CreateSiteRequest body =
                new CreateSiteRequest("Vienna", "has spaces!", null, "Dr. Smith",
                        null, null, null, null, null, null, null, null, null, null);
        java.util.List<ValidationErrorBody.FieldError> errors =
                callValidateCreateShape(body);
        long uidErrors = errors.stream().filter(e -> "uniqueProtocolId".equals(e.field())).count();
        org.junit.jupiter.api.Assertions.assertEquals(1, uidErrors);
    }

    @Test
    void updateValidate_BlankNameRejected() {
        UpdateSiteRequest body =
                new UpdateSiteRequest("   ", null, null, null, null, null, null, null, null, null, null, null);
        java.util.List<ValidationErrorBody.FieldError> errors =
                callValidateUpdateShape(body);
        long nameErrors = errors.stream().filter(e -> "name".equals(e.field())).count();
        org.junit.jupiter.api.Assertions.assertEquals(1, nameErrors);
    }

    @Test
    void updateValidate_OmittedFieldsAreNoOps() {
        UpdateSiteRequest body =
                new UpdateSiteRequest(null, null, null, null, null, null, null, null, null, null, null, null);
        java.util.List<ValidationErrorBody.FieldError> errors =
                callValidateUpdateShape(body);
        org.junit.jupiter.api.Assertions.assertTrue(errors.isEmpty());
    }

    @SuppressWarnings("unchecked")
    private static java.util.List<ValidationErrorBody.FieldError>
            callValidateCreateShape(CreateSiteRequest body) {
        try {
            java.lang.reflect.Method m = SitesApiController.class
                    .getDeclaredMethod("validateCreateShape", CreateSiteRequest.class);
            m.setAccessible(true);
            return (java.util.List<ValidationErrorBody.FieldError>) m.invoke(null, body);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static java.util.List<ValidationErrorBody.FieldError>
            callValidateUpdateShape(UpdateSiteRequest body) {
        try {
            java.lang.reflect.Method m = SitesApiController.class
                    .getDeclaredMethod("validateUpdateShape", UpdateSiteRequest.class);
            m.setAccessible(true);
            return (java.util.List<ValidationErrorBody.FieldError>) m.invoke(null, body);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
