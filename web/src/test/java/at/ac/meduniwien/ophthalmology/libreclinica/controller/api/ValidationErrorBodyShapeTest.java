/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase E-hardening B5 — pins the canonical
 * {@link at.ac.meduniwien.ophthalmology.libreclinica.controller.api.dto.ValidationErrorBody}
 * wire shape on the {@link ApiExceptionHandler} 4xx/5xx catch-all
 * branches.
 *
 * <p>Triggers the A3 {@link AccessDeniedException} handler (3 →
 * forbidden), then asserts the response carries:
 *
 * <ul>
 *   <li>{@code application/json} content type,</li>
 *   <li>{@code message} field that is a non-empty string,</li>
 *   <li>{@code errors} field that is an array (empty, since framework
 *       exceptions don't carry per-field validation details).</li>
 * </ul>
 *
 * <p>This is the canonicalize-only smoke test: every other 4xx/5xx
 * branch in {@link ApiExceptionHandler} returns the same shape with
 * the same {@code List.of()} for {@code errors}, so a single sample
 * is enough to lock the contract.
 */
class ValidationErrorBodyShapeTest {

    private MockMvc mockMvc() {
        return MockMvcBuilders.standaloneSetup(new ThrowController())
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void accessDeniedReturnsCanonicalValidationErrorBody() throws Exception {
        mockMvc().perform(get("/__test/throw/access-denied"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").value(not(emptyOrNullString())))
                .andExpect(jsonPath("$.errors").isArray())
                .andExpect(jsonPath("$.errors").value(hasSize(0)));
    }

    /**
     * Throwaway controller whose only purpose is to dispatch the test
     * request into the {@code AccessDeniedException} branch of the
     * production {@link ApiExceptionHandler}.
     */
    @RestController
    static class ThrowController {

        @GetMapping("/__test/throw/access-denied")
        public void throwAccessDenied() {
            throw new AccessDeniedException("test denied");
        }
    }
}
