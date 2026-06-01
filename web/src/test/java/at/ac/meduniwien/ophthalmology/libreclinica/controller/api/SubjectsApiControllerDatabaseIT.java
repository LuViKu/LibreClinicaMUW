/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Phase E.5 #1 — first happy-path IT against a Testcontainers Postgres.
 *
 * <p>Pins that {@code GET /api/v1/subjects} returns the 7 seeded
 * subjects (M-001 .. M-007) when an authenticated user has study #1
 * bound, with M-003 and M-006 marked {@code signed: true} per the seed
 * file's status_id=8 SIGNED rows.
 *
 * <p>This IT proves the {@link AbstractApiControllerDatabaseIT} wiring
 * works end-to-end against a real DB: container start → Liquibase
 * apply → DAO query → JSON response. Subsequent adapter PRs can opt
 * in (per-IT, additive) as their happy paths become worth pinning.
 */
class SubjectsApiControllerDatabaseIT extends AbstractApiControllerDatabaseIT {

    @Test
    void listReturnsSevenSeededSubjects() throws Exception {
        SubjectsApiController controller = buildSubjectsController();
        // No ApiExceptionHandler so any underlying NPE / SQLException bubbles
        // out as a ServletException with the real cause, giving us a useful
        // stack trace if the IT regresses. Production wraps via the advice;
        // the wrapping is already pinned by SubjectsApiControllerTest.
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        mockMvc.perform(get("/api/v1/subjects").session(authenticatedSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(7))
                .andExpect(jsonPath("$[?(@.id == 'M-001')]").exists())
                .andExpect(jsonPath("$[?(@.id == 'M-003')]").exists())
                .andExpect(jsonPath("$[?(@.id == 'M-006')]").exists())
                .andExpect(jsonPath("$[?(@.signed == true)].id")
                        .value(containsInAnyOrder("M-003", "M-006")));
    }
}
