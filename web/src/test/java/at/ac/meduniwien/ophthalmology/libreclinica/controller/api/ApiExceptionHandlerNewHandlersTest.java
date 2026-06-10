/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * Phase E-hardening A3 — MockMvc test pinning the wire contract for
 * the five new {@link ApiExceptionHandler} branches added in this
 * sub-phase.
 *
 * <p>Each test mounts a throwaway {@link ThrowController} method that
 * unconditionally throws the target exception type, hits the endpoint
 * with the production {@link ApiExceptionHandler} registered as
 * controller-advice (matching the pattern in {@link
 * AbstractApiControllerTest#mockMvcFor}), and asserts:
 *
 * <ul>
 *   <li>status code matches the A3 spec,</li>
 *   <li>response body is JSON,</li>
 *   <li>the {@code message} field exists and is non-empty.</li>
 * </ul>
 *
 * <p>The exact German/English copy is not pinned — the A3 contract is
 * "there is a message in the body shape the SPA expects". A copy edit
 * shouldn't bring this test down.
 *
 * <p>This class does NOT extend {@link AbstractApiControllerTest} —
 * the {@code ThrowController} is a static nested class that doesn't
 * need any session helpers, and the base class' DataSource mock is
 * unused here.
 */
class ApiExceptionHandlerNewHandlersTest {

    private MockMvc mockMvc() {
        return MockMvcBuilders.standaloneSetup(new ThrowController())
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void accessDeniedReturns403WithJsonMessage() throws Exception {
        mockMvc().perform(get("/__test/throw/access-denied"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").value(not(emptyOrNullString())));
    }

    @Test
    void authenticationFailureReturns401WithJsonMessage() throws Exception {
        mockMvc().perform(get("/__test/throw/authentication"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").value(not(emptyOrNullString())));
    }

    @Test
    void missingRequestHeaderReturns400WithJsonMessage() throws Exception {
        mockMvc().perform(get("/__test/throw/missing-header"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").value(not(emptyOrNullString())));
    }

    @Test
    void dataIntegrityViolationReturns409WithJsonMessage() throws Exception {
        mockMvc().perform(get("/__test/throw/data-integrity"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").value(not(emptyOrNullString())));
    }

    @Test
    void maxUploadSizeExceededReturns413WithJsonMessage() throws Exception {
        mockMvc().perform(get("/__test/throw/max-upload-size"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").value(not(emptyOrNullString())));
    }

    /**
     * Throwaway controller for the five branches. Each method throws
     * unconditionally; the routing serves only to dispatch the test
     * request to the right throw site.
     *
     * <p>{@link MissingRequestHeaderException} needs a real
     * {@link MethodParameter}; we synthesize one off this controller's
     * own {@link #throwMissingHeader()} signature — Spring's matcher
     * does not introspect the parameter beyond its existence.
     */
    @RestController
    static class ThrowController {

        @GetMapping("/__test/throw/access-denied")
        public void throwAccessDenied() {
            throw new AccessDeniedException("test denied");
        }

        @GetMapping("/__test/throw/authentication")
        public void throwAuthentication() {
            throw new TestAuthenticationException("test unauthenticated");
        }

        @GetMapping("/__test/throw/missing-header")
        public void throwMissingHeader() throws MissingRequestHeaderException {
            MethodParameter param;
            try {
                param = new MethodParameter(
                        ThrowController.class.getDeclaredMethod("throwMissingHeader"),
                        -1);
            } catch (NoSuchMethodException nsme) {
                throw new IllegalStateException(nsme);
            }
            throw new MissingRequestHeaderException("X-Test-Header", param);
        }

        @GetMapping("/__test/throw/data-integrity")
        public void throwDataIntegrity() {
            throw new DataIntegrityViolationException("duplicate key");
        }

        @GetMapping("/__test/throw/max-upload-size")
        public void throwMaxUploadSize() {
            throw new MaxUploadSizeExceededException(1024L);
        }
    }

    /**
     * Concrete {@link AuthenticationException} for the test — the
     * Spring class is abstract. The handler is keyed on the abstract
     * superclass; any concrete subclass triggers the 401 branch.
     */
    private static final class TestAuthenticationException extends AuthenticationException {
        private static final long serialVersionUID = 1L;

        TestAuthenticationException(String msg) {
            super(msg);
        }
    }
}
