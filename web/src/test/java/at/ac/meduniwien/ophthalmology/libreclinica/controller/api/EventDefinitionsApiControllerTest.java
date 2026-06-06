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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Phase E A8.2 — MockMvc IT pinning the
 * {@link EventDefinitionsApiController} session + role + body
 * validation guards.
 *
 * <p>Happy-path coverage (DAO-bound paths — round-trip create, list
 * shape, audit rows, reorder mechanics) needs Testcontainers Postgres
 * — deferred to the IT infra slice.
 */
class EventDefinitionsApiControllerTest extends AbstractApiControllerTest {

    private MockMvc mockMvcWith() {
        return mockMvcFor(new EventDefinitionsApiController(mockDataSource()));
    }

    /* ---------------------------------------------------------------------- */
    /* GET /api/v1/studies/{studyOid}/event-definitions                       */
    /* ---------------------------------------------------------------------- */

    @Test
    void listReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(get("/api/v1/studies/S_DEFAULTS1/event-definitions")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    /* ---------------------------------------------------------------------- */
    /* POST /api/v1/studies/{studyOid}/event-definitions                      */
    /* ---------------------------------------------------------------------- */

    @Test
    void createReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(post("/api/v1/studies/S_DEFAULTS1/event-definitions")
                .contentType("application/json")
                .content("{\"name\":\"V1\",\"type\":\"scheduled\"}")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    // Note: tests for 400-on-missing-body, 400-on-bad-fields, 404-unknown-study,
    // and 409-site-not-parent all require StudyDAO to return a real bean from
    // findByOid (the preflight runs before body validation). Those paths are
    // exercised by the static validateCreateShape / validateUpdateShape tests
    // below + by the Testcontainers IT layer once it ships.

    /* ---------------------------------------------------------------------- */
    /* PUT /api/v1/studies/{studyOid}/event-definitions/{sedOid}              */
    /* ---------------------------------------------------------------------- */

    @Test
    void updateReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(put("/api/v1/studies/S_DEFAULTS1/event-definitions/SE_V1")
                .contentType("application/json")
                .content("{}")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    /* ---------------------------------------------------------------------- */
    /* POST /reorder + /disable                                               */
    /* ---------------------------------------------------------------------- */

    @Test
    void reorderReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(post("/api/v1/studies/S_DEFAULTS1/event-definitions/reorder")
                .contentType("application/json")
                .content("{\"orderedOids\":[\"SE_V1\"]}")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void disableReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(post("/api/v1/studies/S_DEFAULTS1/event-definitions/SE_V1/disable")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    /* ---------------------------------------------------------------------- */
    /* POST /restore + /lock + /unlock                                        */
    /*   Phase E.6 — session guards. Happy-path cascade behavior + 409        */
    /*   precondition guards (DELETED-required for restore, LOCKED-required   */
    /*   for unlock, sysadmin-only for lock/unlock) require a real DataSource */
    /*   and ride on the Testcontainers IT slice.                             */
    /* ---------------------------------------------------------------------- */

    @Test
    void restoreReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(post("/api/v1/studies/S_DEFAULTS1/event-definitions/SE_V1/restore")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void lockReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(post("/api/v1/studies/S_DEFAULTS1/event-definitions/SE_V1/lock")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unlockReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(post("/api/v1/studies/S_DEFAULTS1/event-definitions/SE_V1/unlock")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    /* ---------------------------------------------------------------------- */
    /* Shape validation (CreateEventDefinitionRequest)                        */
    /* ---------------------------------------------------------------------- */

    @Test
    void createValidate_RequiresNameAndType() {
        // Hit the static validation method directly — DAO-free.
        CreateEventDefinitionRequest body =
                new CreateEventDefinitionRequest(null, null, null, null, null);
        java.util.List<SubjectsApiController.ValidationErrorBody.FieldError> errors =
                callValidateCreateShape(body);
        // Both required fields should fire.
        long nameErrors = errors.stream().filter(e -> "name".equals(e.field())).count();
        long typeErrors = errors.stream().filter(e -> "type".equals(e.field())).count();
        org.junit.jupiter.api.Assertions.assertEquals(1, nameErrors);
        org.junit.jupiter.api.Assertions.assertEquals(1, typeErrors);
    }

    @Test
    void createValidate_RejectsUnknownType() {
        CreateEventDefinitionRequest body =
                new CreateEventDefinitionRequest("Visit 1", null, null, "wizard-only", false);
        java.util.List<SubjectsApiController.ValidationErrorBody.FieldError> errors =
                callValidateCreateShape(body);
        long typeErrors = errors.stream().filter(e -> "type".equals(e.field())).count();
        org.junit.jupiter.api.Assertions.assertEquals(1, typeErrors);
    }

    @Test
    void createValidate_AcceptsLegalTypes() {
        for (String type : new String[]{"scheduled", "unscheduled", "common"}) {
            CreateEventDefinitionRequest body =
                    new CreateEventDefinitionRequest("Visit 1", null, null, type, false);
            java.util.List<SubjectsApiController.ValidationErrorBody.FieldError> errors =
                    callValidateCreateShape(body);
            org.junit.jupiter.api.Assertions.assertTrue(errors.isEmpty(),
                    "Expected no errors for type=" + type + " but got " + errors);
        }
    }

    @Test
    void createValidate_RejectsLongName() {
        String tooLong = "a".repeat(2001);
        CreateEventDefinitionRequest body =
                new CreateEventDefinitionRequest(tooLong, null, null, "scheduled", false);
        java.util.List<SubjectsApiController.ValidationErrorBody.FieldError> errors =
                callValidateCreateShape(body);
        long nameErrors = errors.stream().filter(e -> "name".equals(e.field())).count();
        org.junit.jupiter.api.Assertions.assertEquals(1, nameErrors);
    }

    @Test
    void updateValidate_BlankNameRejected() {
        UpdateEventDefinitionRequest body =
                new UpdateEventDefinitionRequest("   ", null, null, null, null);
        java.util.List<SubjectsApiController.ValidationErrorBody.FieldError> errors =
                callValidateUpdateShape(body);
        long nameErrors = errors.stream().filter(e -> "name".equals(e.field())).count();
        org.junit.jupiter.api.Assertions.assertEquals(1, nameErrors);
    }

    @Test
    void updateValidate_OmittedFieldsAreNoOps() {
        UpdateEventDefinitionRequest body =
                new UpdateEventDefinitionRequest(null, null, null, null, null);
        java.util.List<SubjectsApiController.ValidationErrorBody.FieldError> errors =
                callValidateUpdateShape(body);
        org.junit.jupiter.api.Assertions.assertTrue(errors.isEmpty());
    }

    /* Reflection helpers — the validate* methods are package-private
       static; surface them in tests without exposing them publicly. */
    @SuppressWarnings("unchecked")
    private static java.util.List<SubjectsApiController.ValidationErrorBody.FieldError>
            callValidateCreateShape(CreateEventDefinitionRequest body) {
        try {
            java.lang.reflect.Method m = EventDefinitionsApiController.class
                    .getDeclaredMethod("validateCreateShape", CreateEventDefinitionRequest.class);
            m.setAccessible(true);
            return (java.util.List<SubjectsApiController.ValidationErrorBody.FieldError>) m.invoke(null, body);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static java.util.List<SubjectsApiController.ValidationErrorBody.FieldError>
            callValidateUpdateShape(UpdateEventDefinitionRequest body) {
        try {
            java.lang.reflect.Method m = EventDefinitionsApiController.class
                    .getDeclaredMethod("validateUpdateShape", UpdateEventDefinitionRequest.class);
            m.setAccessible(true);
            return (java.util.List<SubjectsApiController.ValidationErrorBody.FieldError>) m.invoke(null, body);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    /* ---------------------------------------------------------------------- */
    /* GET/POST/PUT/DELETE /event-definitions/{sedOid}/crfs                   */
    /*   (Phase E A8.3 — assignment surface)                                 */
    /* ---------------------------------------------------------------------- */

    @Test
    void listAssignmentsReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(get("/api/v1/studies/S_DEFAULTS1/event-definitions/SE_V1/crfs")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void attachCrfReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(post("/api/v1/studies/S_DEFAULTS1/event-definitions/SE_V1/crfs")
                .contentType("application/json")
                .content("{\"crfOid\":\"F_DEMOS\",\"defaultVersionOid\":\"F_DEMOS_V1\"}")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void updateAssignmentReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(put("/api/v1/studies/S_DEFAULTS1/event-definitions/SE_V1/crfs/F_DEMOS")
                .contentType("application/json")
                .content("{\"required\":true}")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void removeAssignmentReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(delete("/api/v1/studies/S_DEFAULTS1/event-definitions/SE_V1/crfs/F_DEMOS")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }
}
