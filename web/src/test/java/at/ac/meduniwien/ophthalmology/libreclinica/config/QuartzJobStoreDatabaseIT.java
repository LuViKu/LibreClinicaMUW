/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.config;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.quartz.CronScheduleBuilder;
import org.quartz.Job;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.Scheduler;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.quartz.impl.matchers.GroupMatcher;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;

import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.AbstractApiControllerDatabaseIT;
import at.ac.meduniwien.ophthalmology.libreclinica.job.OpenClinicaSchedulerFactoryBean;

/**
 * The scheduler the application runs, against the schema Liquibase builds.
 *
 * <p>Nothing else exercises Quartz's JDBC job store: the other database ITs
 * run without a scheduler and the unit tests mock it. The export jobs and the
 * nightly retention sweeps depend on three things this pins, with the scheduler
 * built by {@link QuartzConfig} from the deployment's settings (PostgreSQL
 * delegate, {@code oc_qrtz_} tables, job data stored as a serialised map): a
 * job is written to the tables, it fires with its data read back from them,
 * and a trigger scheduled before a restart is still there after it.
 */
@SuppressWarnings("null")
class QuartzJobStoreDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static final String GROUP = "quartz-it";

    /** Survives the serialised round trip only if the job store reads it back intact. */
    private static final String PAYLOAD = "Ödem ≥ 312 µm";

    private final List<OpenClinicaSchedulerFactoryBean> running = new ArrayList<>();

    /** Records what the scheduler handed the job when it fired. */
    public static class RecordingJob implements Job {
        static volatile CountDownLatch fired = new CountDownLatch(1);
        static volatile String payload;

        @Override
        public void execute(JobExecutionContext context) {
            payload = context.getMergedJobDataMap().getString("payload");
            fired.countDown();
        }
    }

    /** The scheduler as QuartzConfig builds it, with docker/config/datainfo.properties' values. */
    private Scheduler startScheduler() throws Exception {
        QuartzConfig config = new QuartzConfig();
        ReflectionTestUtils.setField(config, "misfireThreshold", "18000000");
        ReflectionTestUtils.setField(config, "driverDelegateClass", "org.quartz.impl.jdbcjobstore.PostgreSQLDelegate");
        ReflectionTestUtils.setField(config, "useProperties", "false");
        ReflectionTestUtils.setField(config, "tablePrefix", "oc_qrtz_");
        ReflectionTestUtils.setField(config, "threadCount", "1");
        ReflectionTestUtils.setField(config, "threadPriority", "5");

        OpenClinicaSchedulerFactoryBean factory =
                config.schedulerFactoryBean(DATA_SOURCE, new DataSourceTransactionManager(DATA_SOURCE));
        // QuartzConfig publishes the application context to the jobs.
        GenericApplicationContext context = new GenericApplicationContext();
        context.refresh();
        factory.setApplicationContext(context);
        factory.afterPropertiesSet();
        factory.start();
        running.add(factory);
        return factory.getObject();
    }

    private void stopScheduler(Scheduler scheduler) throws Exception {
        for (OpenClinicaSchedulerFactoryBean factory : running) {
            if (factory.getObject() == scheduler) {
                factory.destroy();
                running.remove(factory);
                return;
            }
        }
    }

    @AfterEach
    void removeWhatThisTestStored() throws Exception {
        if (running.isEmpty()) {
            startScheduler();
        }
        Scheduler scheduler = running.get(running.size() - 1).getObject();
        scheduler.deleteJobs(new ArrayList<>(scheduler.getJobKeys(GroupMatcher.jobGroupEquals(GROUP))));
        for (OpenClinicaSchedulerFactoryBean factory : running) {
            factory.destroy();
        }
        running.clear();
    }

    private int storedJobs(String name) throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM oc_qrtz_job_details WHERE job_name = ? AND job_group = ?")) {
            ps.setString(1, name);
            ps.setString(2, GROUP);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    @Test
    void aJobIsStoredAndFiresWithItsData() throws Exception {
        Scheduler scheduler = startScheduler();
        RecordingJob.fired = new CountDownLatch(1);
        RecordingJob.payload = null;

        JobDetail job = JobBuilder.newJob(RecordingJob.class)
                .withIdentity("fires", GROUP)
                .usingJobData("payload", PAYLOAD)
                .storeDurably()
                .build();
        scheduler.scheduleJob(job, TriggerBuilder.newTrigger()
                .withIdentity("fires-now", GROUP)
                .startNow()
                .build());

        assertEquals(1, storedJobs("fires"), "the job is written to oc_qrtz_job_details");
        assertTrue(RecordingJob.fired.await(60, SECONDS), "the job did not fire");
        assertEquals(PAYLOAD, RecordingJob.payload,
                "the job data must come back from the job store as it went in");
    }

    /** The retention sweeps are daily cron triggers; a restart must not lose them. */
    @Test
    void aCronTriggerOutlivesARestart() throws Exception {
        Scheduler first = startScheduler();
        JobDetail job = JobBuilder.newJob(RecordingJob.class)
                .withIdentity("nightly", GROUP)
                .usingJobData("payload", PAYLOAD)
                .build();
        Trigger trigger = TriggerBuilder.newTrigger()
                .withIdentity("nightly-0300", GROUP)
                .withSchedule(CronScheduleBuilder.dailyAtHourAndMinute(3, 0))
                .build();
        first.scheduleJob(job, trigger);
        Date nextFire = first.getTrigger(trigger.getKey()).getNextFireTime();
        stopScheduler(first);

        Scheduler second = startScheduler();
        Trigger reloaded = second.getTrigger(trigger.getKey());
        assertNotNull(reloaded, "the trigger must be read back from oc_qrtz_triggers");
        assertEquals(nextFire, reloaded.getNextFireTime());
        assertEquals(PAYLOAD, second.getJobDetail(job.getKey()).getJobDataMap().getString("payload"));
    }
}
