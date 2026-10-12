/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.stream.Stream;

import javax.sql.DataSource;

import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.dto.ValidationErrorBody.FieldError;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * DR-040 — the parts of {@link AdminLocationsApiController} that need no
 * database: the guard in front of every endpoint (it answers before the
 * database is touched), the shape rules of a location's fields, and the route.
 * The behaviour against a real database is
 * {@code AdminLocationsApiControllerDatabaseIT}.
 */
class AdminLocationsApiControllerTest extends AbstractApiControllerTest {

    static Stream<Arguments> endpoints() {
        return Stream.of(
                Arguments.of("GET", "/api/v1/admin/locations", null),
                Arguments.of("POST", "/api/v1/admin/locations", "{\"code\":\"IT-X\",\"name\":\"X\"}"),
                Arguments.of("GET", "/api/v1/admin/locations/1", null),
                Arguments.of("PUT", "/api/v1/admin/locations/1", "{\"name\":\"X\"}"),
                Arguments.of("POST", "/api/v1/admin/locations/1/disable", null),
                Arguments.of("POST", "/api/v1/admin/locations/1/restore", null),
                Arguments.of("PUT", "/api/v1/admin/locations/1/users/someone", null),
                Arguments.of("DELETE", "/api/v1/admin/locations/1/users/someone", null));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("endpoints")
    void everyEndpointAnswers401ToAnonymousAnd403ToAnyoneButASystemAdministratorBeforeTouchingTheDatabase(
            String verb, String path, String body) throws Exception {
        DataSource ds = mockDataSource();
        var mvc = mockMvcFor(new AdminLocationsApiController(ds));

        mvc.perform(build(verb, path, body).session((MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
        mvc.perform(build(verb, path, body).session((MockHttpSession) authenticatedSession(
                        7, "physician", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(ds);
    }

    @Test
    void theRouteIsUnderTheApiPrefixWithoutThePagesDispatcherPrefix() {
        RequestMapping mapping = AdminLocationsApiController.class.getAnnotation(RequestMapping.class);
        assertEquals(List.of("/api/v1/admin/locations"), List.of(mapping.value()),
                "the pages dispatcher strips /pages/ before matching, so a mapping must not include it");
    }

    /* ---- the shape of a location's fields ----------------------------- */

    @Test
    void aValidLocationHasNoShapeErrors() {
        assertTrue(AdminLocationsApiController.shapeErrors("MUW-AUGEN", "Klinik", "Street 1", "Wien", "1090", "AT", true).isEmpty());
        assertTrue(AdminLocationsApiController.shapeErrors("ab", "n".repeat(100), "s".repeat(255), "c".repeat(255),
                "z".repeat(64), "k".repeat(64), true).isEmpty(), "every limit is inclusive");
        assertTrue(AdminLocationsApiController.shapeErrors("a".repeat(30), "N", null, null, null, null, true).isEmpty());
    }

    @Test
    void theCodeIsRequiredAndMatchesTheDocumentedPatternOnCreate() {
        assertEquals(List.of("code"), fieldsOf(AdminLocationsApiController.shapeErrors(null, "N", null, null, null, null, true)));
        for (String bad : new String[] {"a", "a".repeat(31), "a b", "a.b", "a/b", "kühn", "a!"}) {
            assertEquals(List.of("code"), fieldsOf(AdminLocationsApiController.shapeErrors(bad, "N", null, null, null, null, true)), bad);
        }
        for (String good : new String[] {"ab", "AB", "a-b", "a_b", "A1-b_2", "0123456789"}) {
            assertTrue(AdminLocationsApiController.shapeErrors(good, "N", null, null, null, null, true).isEmpty(), good);
        }
    }

    @Test
    void theCodeOfAnEditIsNotLookedAt() {
        assertTrue(AdminLocationsApiController.shapeErrors(null, "N", null, null, null, null, false).isEmpty());
        assertTrue(AdminLocationsApiController.shapeErrors("not a valid code!", "N", null, null, null, null, false).isEmpty());
    }

    @Test
    void theNameIsRequiredAndEveryFieldHasItsLimit() {
        assertEquals(List.of("name"), fieldsOf(AdminLocationsApiController.shapeErrors("ab", null, null, null, null, null, true)));
        assertEquals(List.of("name"), fieldsOf(AdminLocationsApiController.shapeErrors("ab", "n".repeat(101), null, null, null, null, true)));
        assertEquals(List.of("name", "street", "city", "zip", "country"), fieldsOf(AdminLocationsApiController.shapeErrors(
                "ab", "n".repeat(101), "s".repeat(256), "c".repeat(256), "z".repeat(65), "k".repeat(65), false)));
    }

    @Test
    void aStudyStatusReadsAsAStableLowerCaseKey() {
        assertEquals("available", AdminLocationsApiController.studyStatusKey(1));
        assertEquals("pending", AdminLocationsApiController.studyStatusKey(4));
        assertEquals("removed", AdminLocationsApiController.studyStatusKey(5));
        assertEquals("locked", AdminLocationsApiController.studyStatusKey(6));
        assertEquals("auto-removed", AdminLocationsApiController.studyStatusKey(7));
        assertEquals("frozen", AdminLocationsApiController.studyStatusKey(9));
        assertEquals("unknown", AdminLocationsApiController.studyStatusKey(42));
    }

    private static List<String> fieldsOf(List<FieldError> errors) {
        return errors.stream().map(FieldError::field).toList();
    }

    private static AbstractMockHttpServletRequestBuilder<?> build(String verb, String path, String body) {
        AbstractMockHttpServletRequestBuilder<?> req = request(HttpMethod.valueOf(verb), path);
        return body == null ? req : req.contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
