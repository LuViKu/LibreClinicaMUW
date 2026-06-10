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

class GroupClassesApiControllerTest extends AbstractApiControllerTest {

    private MockMvc mockMvcWith() {
        return mockMvcFor(new GroupClassesApiController(mockDataSource()));
    }

    @Test
    void listReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(get("/api/v1/studies/S_DEFAULTS1/group-classes")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(post("/api/v1/studies/S_DEFAULTS1/group-classes")
                .contentType("application/json")
                .content("{\"name\":\"Treatment Arms\",\"groupClassType\":\"Arm\",\"subjectAssignment\":\"REQUIRED\"}")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void updateReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(put("/api/v1/studies/S_DEFAULTS1/group-classes/1")
                .contentType("application/json")
                .content("{}")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void disableReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(post("/api/v1/studies/S_DEFAULTS1/group-classes/1/disable")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void restoreReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(post("/api/v1/studies/S_DEFAULTS1/group-classes/1/restore")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    /* ---------------------------------------------------------------------- */
    /* Shape validation                                                       */
    /* ---------------------------------------------------------------------- */

    @Test
    void createValidate_RequiresNameTypeAssignment() {
        CreateGroupClassRequest body =
                new CreateGroupClassRequest(null, null, null, null);
        java.util.List<ValidationErrorBody.FieldError> errors =
                callValidateCreateShape(body);
        long nameErrors = errors.stream().filter(e -> "name".equals(e.field())).count();
        long typeErrors = errors.stream().filter(e -> "groupClassType".equals(e.field())).count();
        long assignErrors = errors.stream().filter(e -> "subjectAssignment".equals(e.field())).count();
        org.junit.jupiter.api.Assertions.assertEquals(1, nameErrors);
        org.junit.jupiter.api.Assertions.assertEquals(1, typeErrors);
        org.junit.jupiter.api.Assertions.assertEquals(1, assignErrors);
    }

    @Test
    void createValidate_RejectsUnknownType() {
        CreateGroupClassRequest body =
                new CreateGroupClassRequest("Arms", "Cohort", "REQUIRED", null);
        java.util.List<ValidationErrorBody.FieldError> errors =
                callValidateCreateShape(body);
        long typeErrors = errors.stream().filter(e -> "groupClassType".equals(e.field())).count();
        org.junit.jupiter.api.Assertions.assertEquals(1, typeErrors);
    }

    @Test
    void createValidate_RejectsUnknownAssignment() {
        CreateGroupClassRequest body =
                new CreateGroupClassRequest("Arms", "Arm", "MAYBE", null);
        java.util.List<ValidationErrorBody.FieldError> errors =
                callValidateCreateShape(body);
        long assignErrors = errors.stream().filter(e -> "subjectAssignment".equals(e.field())).count();
        org.junit.jupiter.api.Assertions.assertEquals(1, assignErrors);
    }

    @Test
    void createValidate_RejectsLongName() {
        String tooLong = "a".repeat(31);
        CreateGroupClassRequest body =
                new CreateGroupClassRequest(tooLong, "Arm", "REQUIRED", null);
        java.util.List<ValidationErrorBody.FieldError> errors =
                callValidateCreateShape(body);
        long nameErrors = errors.stream().filter(e -> "name".equals(e.field())).count();
        org.junit.jupiter.api.Assertions.assertEquals(1, nameErrors);
    }

    @Test
    void updateValidate_OmittedFieldsAreNoOps() {
        UpdateGroupClassRequest body =
                new UpdateGroupClassRequest(null, null, null, null);
        java.util.List<ValidationErrorBody.FieldError> errors =
                callValidateUpdateShape(body);
        org.junit.jupiter.api.Assertions.assertTrue(errors.isEmpty());
    }

    @Test
    void updateValidate_BlankNameRejected() {
        UpdateGroupClassRequest body =
                new UpdateGroupClassRequest("   ", null, null, null);
        java.util.List<ValidationErrorBody.FieldError> errors =
                callValidateUpdateShape(body);
        long nameErrors = errors.stream().filter(e -> "name".equals(e.field())).count();
        org.junit.jupiter.api.Assertions.assertEquals(1, nameErrors);
    }

    @SuppressWarnings("unchecked")
    private static java.util.List<ValidationErrorBody.FieldError>
            callValidateCreateShape(CreateGroupClassRequest body) {
        try {
            java.lang.reflect.Method m = GroupClassesApiController.class
                    .getDeclaredMethod("validateCreateShape", CreateGroupClassRequest.class);
            m.setAccessible(true);
            return (java.util.List<ValidationErrorBody.FieldError>) m.invoke(null, body);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static java.util.List<ValidationErrorBody.FieldError>
            callValidateUpdateShape(UpdateGroupClassRequest body) {
        try {
            java.lang.reflect.Method m = GroupClassesApiController.class
                    .getDeclaredMethod("validateUpdateShape", UpdateGroupClassRequest.class);
            m.setAccessible(true);
            return (java.util.List<ValidationErrorBody.FieldError>) m.invoke(null, body);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void ignoredMatcher() {
        // Suppress unused-imports warning for jsonPath; kept available for
        // future tests that need it.
        org.junit.jupiter.api.Assertions.assertNotNull(jsonPath("$"));
    }
}
