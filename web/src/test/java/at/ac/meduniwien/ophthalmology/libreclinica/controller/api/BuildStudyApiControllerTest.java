/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Phase E.5 A2 — MockMvc IT pinning the {@link BuildStudyApiController}
 * session-guard contract surface.
 *
 * <p>What this pins:
 * <ul>
 *   <li>{@code GET /api/v1/studies/{oid}/build-status} →
 *       {@code 401} when anonymous.</li>
 * </ul>
 *
 * <p>The 404 unknown-oid and 200 response-shape paths both hit
 * {@code StudyDAO.findByOid}, which trips on the unstubbed mock
 * DataSource (DAODigester NPE). Those land in the Testcontainers
 * Postgres cut.
 */
class BuildStudyApiControllerTest extends AbstractApiControllerTest {

    private MockMvc mockMvcWith() {
        return mockMvcFor(new BuildStudyApiController(mockDataSource()));
    }

    @Test
    void buildStatusReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(get("/api/v1/studies/S_BOGUS/build-status")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }
}
