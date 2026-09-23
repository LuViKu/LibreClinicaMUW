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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The gate, not the list. {@code CoreResources} has no configuration in a
 * unit test, so every property reads as absent — which is exactly the
 * shipped default, and the one state this endpoint must get right without
 * anyone having thought about it: a fresh install answers 404, not a list of
 * the day's patients. The list itself is
 * {@link OptomedWorklistFormatTest}'s business.
 */
class OptomedWorklistApiControllerTest extends AbstractApiControllerTest {

    private MockMvc mvc() {
        DataSource ds = Mockito.mock(DataSource.class);
        return mockMvcFor(new OptomedWorklistApiController(ds));
    }

    @Test
    void offByDefaultAnswersNotFoundEvenWithAToken() throws Exception {
        mvc().perform(get("/api/v1/device/optomed/worklist.txt")
                        .header(OptomedWorklistApiController.TOKEN_HEADER, "anything"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("not found"));
    }

    @Test
    void offByDefaultDoesNotDistinguishMissingTokenFromWrongOne() throws Exception {
        // While disabled the path must not confirm itself: no 401, no 503,
        // the same 404 whatever the caller sends.
        mvc().perform(get("/api/v1/device/optomed/worklist.txt"))
                .andExpect(status().isNotFound());
        mvc().perform(get("/api/v1/device/optomed/worklist.txt").param("date", "2026-09-23"))
                .andExpect(status().isNotFound());
    }
}
