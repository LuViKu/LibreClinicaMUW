/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.job;

import jakarta.annotation.PostConstruct;

import org.quartz.CronScheduleBuilder;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.quartz.TriggerKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DR-041 - wires {@link E2eSourceRetentionJob} into the shared Quartz scheduler at boot.
 *
 * <p>Fires daily at 03:45 server time, a quarter of an hour after the dismissed-image
 * sweep. Durable job, idempotent trigger, like the other retention schedulers.
 */
public class E2eSourceRetentionScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(E2eSourceRetentionScheduler.class);

    public static final String JOB_NAME = "e2eSourceRetention";
    public static final String GROUP_NAME = "lc-muw-retention";
    /** Quartz cron (sec min hour ...) - 03:45 daily. */
    public static final String CRON_EXPRESSION = "0 45 3 * * ?";

    private final Scheduler scheduler;

    public E2eSourceRetentionScheduler(Scheduler scheduler) {
        this.scheduler = scheduler;
    }

    @PostConstruct
    public void scheduleAtStartup() {
        if (scheduler == null) {
            LOG.warn("E2eSourceRetentionScheduler: Quartz scheduler not present, skipping schedule");
            return;
        }
        try {
            JobKey jobKey = JobKey.jobKey(JOB_NAME, GROUP_NAME);
            TriggerKey triggerKey = TriggerKey.triggerKey(JOB_NAME, GROUP_NAME);

            JobDetail jobDetail = JobBuilder.newJob(E2eSourceRetentionJob.class)
                    .withIdentity(jobKey)
                    .withDescription("Daily sweep of E2E source copies past their retention (DR-041)")
                    .storeDurably(true)
                    .requestRecovery(false)
                    .build();

            Trigger trigger = TriggerBuilder.newTrigger()
                    .withIdentity(triggerKey)
                    .forJob(jobKey)
                    .withSchedule(CronScheduleBuilder.cronSchedule(CRON_EXPRESSION)
                            .withMisfireHandlingInstructionDoNothing())
                    .withDescription("Cron 03:45 daily - delete E2E source copies past their window")
                    .build();

            scheduler.addJob(jobDetail, true, true);
            if (scheduler.checkExists(triggerKey)) {
                scheduler.rescheduleJob(triggerKey, trigger);
            } else {
                scheduler.scheduleJob(trigger);
            }
            LOG.info("E2eSourceRetentionScheduler: scheduled {} (cron='{}', group={})",
                    JOB_NAME, CRON_EXPRESSION, GROUP_NAME);
        } catch (SchedulerException e) {
            // Never fail boot over a retention schedule.
            LOG.warn("E2eSourceRetentionScheduler: failed to schedule the sweep: {}", e.getMessage(), e);
        }
    }
}
