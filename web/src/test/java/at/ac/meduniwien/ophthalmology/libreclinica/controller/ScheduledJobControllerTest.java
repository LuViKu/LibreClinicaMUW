/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Date;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quartz.CronScheduleBuilder;
import org.quartz.JobDataMap;
import org.quartz.Scheduler;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.Trigger;
import org.quartz.Trigger.TriggerState;
import org.quartz.TriggerBuilder;
import org.quartz.TriggerKey;
import org.quartz.impl.matchers.GroupMatcher;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.util.ReflectionTestUtils;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.UserType;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.extract.ExtractPropertyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.web.table.sdv.SDVUtil;

/**
 * The export-job pages share the Quartz scheduler with jobs that are not
 * export jobs and run on a cron schedule, such as the retention job. The
 * list and the cancel action handle only the simple triggers of the export
 * jobs and leave every other trigger alone.
 */
class ScheduledJobControllerTest {

    private static final TriggerKey CRON = new TriggerKey("retention", "DEFAULT");
    private static final TriggerKey EXPORT = new TriggerKey("export-1", "XsltTriggersExportJobs");

    private ScheduledJobController controller;
    private Scheduler scheduler;

    @BeforeEach
    void setUp() throws Exception {
        controller = new ScheduledJobController();
        scheduler = mock(Scheduler.class);
        ReflectionTestUtils.setField(controller, "scheduler", scheduler);
        ReflectionTestUtils.setField(controller, "sdvUtil", new SDVUtil());

        Trigger cron = TriggerBuilder.newTrigger()
                .withIdentity(CRON)
                .forJob("retention", "DEFAULT")
                .withSchedule(CronScheduleBuilder.dailyAtHourAndMinute(3, 0))
                .build();
        ExtractPropertyBean epBean = new ExtractPropertyBean();
        epBean.setDatasetName("Dataset A");
        epBean.setExportFileName(new String[] {"dataset-a.tsv"});
        JobDataMap data = new JobDataMap();
        data.put(ScheduledJobController.EP_BEAN, epBean);
        Trigger export = TriggerBuilder.newTrigger()
                .withIdentity(EXPORT)
                .forJob("export-1", "XsltTriggersExportJobs")
                .startAt(new Date(0))
                .withSchedule(SimpleScheduleBuilder.simpleSchedule())
                .usingJobData(data)
                .build();

        when(scheduler.getCurrentlyExecutingJobs()).thenReturn(List.of());
        when(scheduler.getTriggerGroupNames()).thenReturn(List.of("DEFAULT", "XsltTriggersExportJobs"));
        when(scheduler.getTriggerKeys(any())).thenAnswer(call -> {
            GroupMatcher<?> group = call.getArgument(0);
            return "DEFAULT".equals(group.getCompareToValue()) ? Set.of(CRON) : Set.of(EXPORT);
        });
        when(scheduler.getTriggerState(any())).thenReturn(TriggerState.NORMAL);
        when(scheduler.getTrigger(CRON)).thenReturn(cron);
        when(scheduler.getTrigger(EXPORT)).thenReturn(export);
    }

    private static MockHttpServletRequest adminRequest(String method, String uri) {
        UserAccountBean admin = new UserAccountBean();
        admin.setId(1);
        admin.setName("root");
        admin.addUserType(UserType.SYSADMIN);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("userBean", admin);
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setSession(session);
        return request;
    }

    @Test
    void theJobListShowsTheExportJobAndSkipsTheCronTrigger() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.listScheduledJobsData(adminRequest("GET", "/pages/listCurrentScheduledJobsData"), response);

        assertEquals(200, response.getStatus());
        JsonNode payload = new ObjectMapper().readTree(response.getContentAsString());
        assertEquals(1, payload.get("recordsTotal").asInt());
        assertEquals("Dataset A", payload.get("data").get(0).get("datasetId").asText());
        assertEquals("export-1", payload.get("data").get(0).get("triggerName").asText());
    }

    @Test
    void cancellingACronTriggerChangesNothing() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.cancelScheduledJob(adminRequest("POST", "/pages/cancelScheduledJob"), response,
                "retention", "DEFAULT", "retention", "DEFAULT", null);

        verify(scheduler, never()).pauseJob(any());
        verify(scheduler, never()).unscheduleJob(any());
        assertEquals("/pages/listCurrentScheduledJobs", response.getForwardedUrl());
    }
}
