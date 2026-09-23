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

import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The gate, not the list. The list is {@link OptomedWorklistFormatTest}'s
 * business; this checks that the endpoint refuses in the right order and
 * with the right status, and that a fresh install — no keys at all — answers
 * 404, not a list of the day's patients.
 *
 * <p>Configuration is injected as a map, so all three refusals are exercised
 * without a configured {@code CoreResources}. These tests went red twice in
 * CI with a 500 before the cause was read correctly: not configuration, but
 * {@code produces = text/plain} on the mapping, under which no converter
 * could write the JSON {@code Map} error bodies. The mapping no longer
 * declares {@code produces}; the file's content type is set on the success
 * response itself.
 */
class OptomedWorklistApiControllerTest extends AbstractApiControllerTest {

    private static final String PATH = "/api/v1/device/optomed/worklist.txt";
    private static final String TOKEN = OptomedWorklistApiController.TOKEN_HEADER;

    private MockMvc mvc(Map<String, String> config) {
        DataSource ds = Mockito.mock(DataSource.class);
        return mockMvcFor(new OptomedWorklistApiController(ds, config::get));
    }

    @Test
    void offByDefaultAnswersNotFoundEvenWithAToken() throws Exception {
        mvc(Map.of()).perform(get(PATH).header(TOKEN, "anything"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("not found"));
    }

    @Test
    void offByDefaultDoesNotDistinguishMissingTokenFromWrongOne() throws Exception {
        // While disabled the path must not confirm itself: no 401, no 503,
        // the same 404 whatever the caller sends.
        mvc(Map.of()).perform(get(PATH)).andExpect(status().isNotFound());
        mvc(Map.of()).perform(get(PATH).param("date", "2026-09-23")).andExpect(status().isNotFound());
    }

    @Test
    void explicitlyDisabledIsTheSameAsAbsent() throws Exception {
        mvc(Map.of(OptomedWorklistApiController.ENABLED_KEY, "false"))
                .perform(get(PATH).header(TOKEN, "anything"))
                .andExpect(status().isNotFound());
    }

    @Test
    void enabledWithoutATokenConfiguredIsAServerMisconfiguration() throws Exception {
        // 503, not 401: the caller did nothing wrong, the operator half-configured it.
        mvc(Map.of(OptomedWorklistApiController.ENABLED_KEY, "true"))
                .perform(get(PATH).header(TOKEN, "anything"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void enabledRefusesAMissingOrWrongToken() throws Exception {
        Map<String, String> on = Map.of(
                OptomedWorklistApiController.ENABLED_KEY, "true",
                OptomedWorklistApiController.TOKEN_KEY, "correct-horse-battery-staple");

        mvc(on).perform(get(PATH)).andExpect(status().isUnauthorized());
        mvc(on).perform(get(PATH).header(TOKEN, "wrong")).andExpect(status().isUnauthorized());
        // A prefix or a case variant is not the token either.
        mvc(on).perform(get(PATH).header(TOKEN, "correct-horse-battery")).andExpect(status().isUnauthorized());
        mvc(on).perform(get(PATH).header(TOKEN, "CORRECT-HORSE-BATTERY-STAPLE")).andExpect(status().isUnauthorized());
    }

    // The 200 path needs a real ScheduledVisitQuery behind a real DataSource;
    // a Mockito DataSource returns null connections. Rendering is covered
    // byte for byte by OptomedWorklistFormatTest; the query is shared with the
    // DICOM worklist and the upload page and is exercised by their ITs.
}
