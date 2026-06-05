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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;

import at.ac.meduniwien.ophthalmology.libreclinica.service.extract.ExportScheduleRegistrar;

/**
 * Phase E.6 P4 — MockMvc IT for {@link ExportJobsApiController}.
 *
 * <p>Pins the session-state + body validation contract for the async
 * export endpoints. Happy-path tests that need DB rows (queued →
 * running transition, schedule registration round-trip) live in
 * {@code ExportJobRunnerTest} (core) which exercises the DAO + runner
 * against an in-memory data source.
 *
 * <p>Note (per {@link AbstractApiControllerTest}): Surefire 2.x +
 * mismatched junit-platform-launcher means subclasses compile but
 * are not discovered until the parent-pom alignment lands. This file
 * documents the contract until then.
 */
class ExportJobsApiControllerTest extends AbstractApiControllerTest {

    private MockMvc mockMvcWith() {
        return mockMvcFor(new ExportJobsApiController(
                mockDataSource(),
                Mockito.mock(ExportScheduleRegistrar.class)));
    }

    /* ---------------------------------------------------------------- */
    /* POST /api/v1/datasets/{id}/exports                               */
    /* ---------------------------------------------------------------- */

    @Test
    void enqueueReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(post("/api/v1/datasets/1/exports")
                .contentType("application/json")
                .content("{\"format\":\"odm\"}")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void enqueueReturns400OnMissingFormat() throws Exception {
        mockMvcWith().perform(post("/api/v1/datasets/1/exports")
                .contentType("application/json")
                .content("{}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSysadminSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("'format' is required")));
    }

    @Test
    void enqueueReturns400OnUnsupportedFormat() throws Exception {
        mockMvcWith().perform(post("/api/v1/datasets/1/exports")
                .contentType("application/json")
                .content("{\"format\":\"xyz\"}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSysadminSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("Unsupported format 'xyz'")));
    }

    /* ---------------------------------------------------------------- */
    /* GET /api/v1/exports/{jobId}                                      */
    /* ---------------------------------------------------------------- */

    @Test
    void getJobReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(get("/api/v1/exports/42")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    /* ---------------------------------------------------------------- */
    /* GET /api/v1/studies/{oid}/export-jobs                            */
    /* ---------------------------------------------------------------- */

    @Test
    void listJobsByStudyReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(get("/api/v1/studies/S_DEMO/export-jobs")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    /* ---------------------------------------------------------------- */
    /* POST /api/v1/datasets/{id}/schedules                             */
    /* ---------------------------------------------------------------- */

    @Test
    void createScheduleReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(post("/api/v1/datasets/1/schedules")
                .contentType("application/json")
                .content("{\"format\":\"odm\",\"cronExpression\":\"0 0 3 ? * MON\"}")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createScheduleReturns400OnMissingBody() throws Exception {
        mockMvcWith().perform(post("/api/v1/datasets/1/schedules")
                .contentType("application/json")
                .content("{}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSysadminSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createScheduleReturns400OnInvalidCron() throws Exception {
        // Registrar is a Mockito mock — its isValidCron() returns
        // false by default for any input, which is what we want for
        // this 400 test. (A real registrar would also reject this
        // garbage cron via CronExpression.isValidExpression.)
        mockMvcWith().perform(post("/api/v1/datasets/1/schedules")
                .contentType("application/json")
                .content("{\"format\":\"odm\",\"cronExpression\":\"not a cron\"}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSysadminSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("Invalid cron expression")));
    }

    /* ---------------------------------------------------------------- */
    /* GET /api/v1/datasets/{id}/schedules                              */
    /* ---------------------------------------------------------------- */

    @Test
    void listSchedulesReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(get("/api/v1/datasets/1/schedules")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    /* ---------------------------------------------------------------- */
    /* DELETE /api/v1/schedules/{id}                                    */
    /* ---------------------------------------------------------------- */

    @Test
    void deleteScheduleReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(delete("/api/v1/schedules/7")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }
}
