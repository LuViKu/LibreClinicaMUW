/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.quartz.Job;
import org.quartz.JobBuilder;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.quartz.JobKey;
import org.quartz.JobPersistenceException;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.Trigger;
import org.quartz.Trigger.TriggerState;
import org.quartz.TriggerBuilder;
import org.quartz.TriggerKey;
import org.quartz.impl.StdSchedulerFactory;
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

    /* ====================================================================== */
    /* Legacy scheduled exports (/CreateJobExport)                            */
    /* ====================================================================== */

    private static final String LEGACY = "XsltTriggersExportJobs";

    @Test
    void listJobsShowsWhatALegacyExportWasSetToDo() throws Exception {
        Scheduler s = Mockito.mock(Scheduler.class);
        when(s.getSchedulerName()).thenReturn("public");
        when(s.getTriggerGroupNames()).thenReturn(List.of(LEGACY));
        TriggerKey tk = new TriggerKey("weekly-odm", LEGACY);
        when(s.getTriggerKeys(any())).thenReturn(new HashSet<>(Set.of(tk)));
        JobDataMap data = new JobDataMap();
        data.put("jobName", "weekly-odm");
        data.put("dsId", 3);
        data.put("periodToRun", "weekly");
        data.put("exportFormat", "CDISC ODM XML 1.3 Full with OpenClinica extensions");
        data.put("contactEmail", "dm-team@example.org");
        Trigger trigger = Mockito.mock(Trigger.class);
        when(trigger.getJobDataMap()).thenReturn(data);
        when(trigger.getJobKey()).thenReturn(new JobKey("weekly-odm", LEGACY));
        when(s.getTrigger(tk)).thenReturn(trigger);
        when(s.getTriggerState(tk)).thenReturn(TriggerState.NORMAL);

        mockMvcWith(s)
                .perform(get("/api/v1/admin/jobs")
                        .session((MockHttpSession)
                                authenticatedSysadminSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobs[0].legacyExport.datasetId").value(3))
                .andExpect(jsonPath("$.jobs[0].legacyExport.period").value("weekly"))
                .andExpect(jsonPath("$.jobs[0].legacyExport.exportFormat")
                        .value(Matchers.startsWith("CDISC ODM XML 1.3")))
                .andExpect(jsonPath("$.jobs[0].legacyExport.contactEmail").value("dm-team@example.org"));
    }

    /**
     * A legacy job stored before the package rename names classes that no
     * longer exist, and the store cannot read its data. It must not take the
     * whole list down: it is the row an administrator needs to see and delete.
     */
    @Test
    void listJobsStillListsATriggerWhoseStoredDataCannotBeRead() throws Exception {
        Scheduler s = Mockito.mock(Scheduler.class);
        when(s.getSchedulerName()).thenReturn("public");
        when(s.getTriggerGroupNames()).thenReturn(List.of(LEGACY));
        TriggerKey tk = new TriggerKey("from-before-the-rename", LEGACY);
        when(s.getTriggerKeys(any())).thenReturn(new HashSet<>(Set.of(tk)));
        when(s.getTrigger(tk)).thenThrow(new JobPersistenceException(
                "Couldn't retrieve trigger: org.akaza.openclinica.bean.extract.ExtractPropertyBean"));
        when(s.getTriggerState(tk)).thenReturn(TriggerState.ERROR);

        mockMvcWith(s)
                .perform(get("/api/v1/admin/jobs")
                        .session((MockHttpSession)
                                authenticatedSysadminSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobs.length()").value(1))
                .andExpect(jsonPath("$.jobs[0].name").value("from-before-the-rename"))
                .andExpect(jsonPath("$.jobs[0].unreadable").value(true))
                .andExpect(jsonPath("$.jobs[0].state").value("ERROR"))
                .andExpect(jsonPath("$.jobs[0].legacyExport").exists());
    }

    /** Stands in for XsltStatefulJob: the store only needs a class. */
    public static class IdleJob implements Job {
        @Override
        public void execute(JobExecutionContext context) {
        }
    }

    private static Scheduler ramScheduler(String name) throws SchedulerException {
        Properties p = new Properties();
        p.setProperty("org.quartz.scheduler.instanceName", name);
        p.setProperty("org.quartz.threadPool.threadCount", "1");
        p.setProperty("org.quartz.jobStore.class", "org.quartz.simpl.RAMJobStore");
        return new StdSchedulerFactory(p).getScheduler(); // never started
    }

    private static void scheduleDurable(Scheduler s, String name, String group) throws SchedulerException {
        s.scheduleJob(
                JobBuilder.newJob(IdleJob.class).withIdentity(name, group).storeDurably().build(),
                TriggerBuilder.newTrigger().withIdentity(name, group)
                        .withSchedule(SimpleScheduleBuilder.repeatHourlyForever()).build());
    }

    @Test
    void aLegacyExportJobIsDeletedWithItsTriggerAndNothingElse() throws Exception {
        Scheduler s = ramScheduler("jobs-admin-delete");
        try {
            scheduleDurable(s, "nightly", LEGACY);
            // the same name in one of the application's own groups
            scheduleDurable(s, "nightly", "exportSchedule");

            mockMvcWith(s)
                    .perform(delete("/api/v1/admin/jobs/legacy-exports/nightly")
                            .session((MockHttpSession)
                                    authenticatedSysadminSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                    .andExpect(status().isNoContent());

            assertFalse(s.checkExists(new TriggerKey("nightly", LEGACY)));
            assertFalse(s.checkExists(new JobKey("nightly", LEGACY)), "the durable job goes too");
            assertTrue(s.checkExists(new TriggerKey("nightly", "exportSchedule")),
                    "only the legacy export group is reachable from here");
            assertTrue(s.checkExists(new JobKey("nightly", "exportSchedule")));
        } finally {
            s.shutdown(false);
        }
    }

    @Test
    void deletingAnUnknownLegacyExportJobAnswers404() throws Exception {
        Scheduler s = ramScheduler("jobs-admin-delete-unknown");
        try {
            scheduleDurable(s, "exportJobRunnerTrigger", "exportJobPoller");

            mockMvcWith(s)
                    .perform(delete("/api/v1/admin/jobs/legacy-exports/exportJobRunnerTrigger")
                            .session((MockHttpSession)
                                    authenticatedSysadminSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value(Matchers.containsString("No legacy export job")));
            assertTrue(s.checkExists(new TriggerKey("exportJobRunnerTrigger", "exportJobPoller")));
        } finally {
            s.shutdown(false);
        }
    }

    @Test
    void deletingALegacyExportJobIsForSysadminsOnly() throws Exception {
        Scheduler s = Mockito.mock(Scheduler.class);
        mockMvcWith(s)
                .perform(delete("/api/v1/admin/jobs/legacy-exports/nightly")
                        .session((MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
        mockMvcWith(s)
                .perform(delete("/api/v1/admin/jobs/legacy-exports/nightly")
                        .session((MockHttpSession)
                                authenticatedSession(7, "physician", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isForbidden());
        Mockito.verify(s, Mockito.never()).deleteJob(any());
        Mockito.verify(s, Mockito.never()).unscheduleJob(any());
    }
}
