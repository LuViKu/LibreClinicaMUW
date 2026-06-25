/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.Trigger.TriggerState;
import org.quartz.TriggerKey;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Phase E.8 Slice L5 (2026-06-20) — MockMvc IT for the Quartz job
 * listing endpoint. The Quartz {@link Scheduler} is mocked so the
 * controller's enumeration logic is exercised end-to-end without
 * standing up a real scheduler thread.
 *
 * Coverage:
 *   - 401 when anonymous, 403 when not sysadmin.
 *   - Empty scheduler → {@code jobs: []}.
 *   - One trigger in one group → flat row with the expected fields.
 *   - Scheduler throws → 503 with diagnostic body.
 */
class JobsAdminApiControllerTest extends AbstractApiControllerTest {

    private MockMvc mockMvcWith(Scheduler scheduler) {
        return mockMvcFor(new JobsAdminApiController(scheduler));
    }

    /* ====================================================================== */
    /* Auth gate                                                              */
    /* ====================================================================== */

    @Test
    void listJobsReturns401WhenAnonymous() throws Exception {
        Scheduler s = Mockito.mock(Scheduler.class);
        mockMvcWith(s)
                .perform(get("/api/v1/admin/jobs")
                        .session((MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listJobsReturns403WhenNotSysadmin() throws Exception {
        Scheduler s = Mockito.mock(Scheduler.class);
        mockMvcWith(s)
                .perform(get("/api/v1/admin/jobs")
                        .session((MockHttpSession)
                                authenticatedSession(7, "physician", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isForbidden());
    }

    /* ====================================================================== */
    /* Happy path                                                             */
    /* ====================================================================== */

    @Test
    void listJobsReturnsEmptyJobsWhenSchedulerIsEmpty() throws Exception {
        Scheduler s = Mockito.mock(Scheduler.class);
        when(s.getSchedulerName()).thenReturn("public");
        when(s.isStarted()).thenReturn(true);
        when(s.isInStandbyMode()).thenReturn(false);
        when(s.getTriggerGroupNames()).thenReturn(Collections.emptyList());

        mockMvcWith(s)
                .perform(get("/api/v1/admin/jobs")
                        .session((MockHttpSession)
                                authenticatedSysadminSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schedulerName").value("public"))
                .andExpect(jsonPath("$.isStarted").value(true))
                .andExpect(jsonPath("$.jobs").isArray())
                .andExpect(jsonPath("$.jobs.length()").value(0));
    }

    @Test
    void listJobsReturnsRowForEachTrigger() throws Exception {
        Scheduler s = Mockito.mock(Scheduler.class);
        when(s.getSchedulerName()).thenReturn("public");
        when(s.isStarted()).thenReturn(true);
        when(s.isInStandbyMode()).thenReturn(false);
        when(s.getTriggerGroupNames()).thenReturn(List.of("DEFAULT"));

        TriggerKey tk = new TriggerKey("nightly-export", "DEFAULT");
        Set<TriggerKey> tks = new HashSet<>();
        tks.add(tk);
        when(s.getTriggerKeys(any())).thenReturn(tks);

        Trigger trigger = Mockito.mock(Trigger.class);
        when(trigger.getDescription()).thenReturn("Nightly XML export");
        when(trigger.getPriority()).thenReturn(5);
        Date prev = new Date(1718800000000L);
        Date next = new Date(1718886400000L);
        when(trigger.getPreviousFireTime()).thenReturn(prev);
        when(trigger.getNextFireTime()).thenReturn(next);
        when(trigger.getFinalFireTime()).thenReturn(null);
        when(trigger.getJobKey()).thenReturn(new JobKey("export-runner", "DEFAULT"));
        when(s.getTrigger(tk)).thenReturn(trigger);
        when(s.getTriggerState(tk)).thenReturn(TriggerState.NORMAL);

        mockMvcWith(s)
                .perform(get("/api/v1/admin/jobs")
                        .session((MockHttpSession)
                                authenticatedSysadminSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobs.length()").value(1))
                .andExpect(jsonPath("$.jobs[0].name").value("nightly-export"))
                .andExpect(jsonPath("$.jobs[0].group").value("DEFAULT"))
                .andExpect(jsonPath("$.jobs[0].state").value("NORMAL"))
                .andExpect(jsonPath("$.jobs[0].priority").value(5))
                .andExpect(jsonPath("$.jobs[0].jobName").value("export-runner"))
                .andExpect(jsonPath("$.jobs[0].description").value("Nightly XML export"));
    }

    /* ====================================================================== */
    /* Scheduler failure                                                      */
    /* ====================================================================== */

    @Test
    void listJobsReturns503WhenSchedulerThrows() throws Exception {
        Scheduler s = Mockito.mock(Scheduler.class);
        when(s.getSchedulerName()).thenReturn("public");
        when(s.isStarted()).thenReturn(true);
        when(s.isInStandbyMode()).thenReturn(false);
        when(s.getTriggerGroupNames()).thenThrow(new SchedulerException("backing store wedged"));

        mockMvcWith(s)
                .perform(get("/api/v1/admin/jobs")
                        .session((MockHttpSession)
                                authenticatedSysadminSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value(Matchers.containsString("Scheduler")));
    }
}
