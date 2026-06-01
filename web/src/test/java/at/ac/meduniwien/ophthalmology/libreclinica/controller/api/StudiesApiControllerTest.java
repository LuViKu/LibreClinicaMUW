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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Phase E.5 A2 — MockMvc IT pinning the {@link StudiesApiController}
 * session-guard contract surface.
 *
 * <p>{@code GET /api/v1/studies} must:
 * <ul>
 *   <li>return {@code 401} when there is no {@code userBean} in session.</li>
 * </ul>
 *
 * <p>Happy-path coverage (array shape with the studies the user has
 * a role on) requires a real DataSource — out of scope for the
 * session-guard cut and deferred to the Testcontainers Postgres infra.
 */
class StudiesApiControllerTest extends AbstractApiControllerTest {

    private MockMvc mockMvcWith() {
        return mockMvcFor(new StudiesApiController(mockDataSource()));
    }

    @Test
    void listReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(get("/api/v1/studies")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message")
                        .value(containsString("Not authenticated")));
    }
}
