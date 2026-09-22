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
 * DR-025 — wires {@link ImageIngestRetentionJob} into the shared Quartz
 * scheduler at boot.
 *
 * <p>Fires daily at 03:30 server time, half an hour after the export sweep.
 * Durable job, idempotent trigger — a restart re-registers rather than
 * accumulating duplicates, the same pattern
 * {@link ArchivedFileRetentionScheduler} uses.
 */
@SuppressWarnings("all")
public class ImageIngestRetentionScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(ImageIngestRetentionScheduler.class);

    public static final String JOB_NAME = "imageIngestRetention";
    public static final String GROUP_NAME = "lc-muw-retention";
    /** Quartz cron (sec min hour …) — 03:30 daily. */
    public static final String CRON_EXPRESSION = "0 30 3 * * ?";

    private final Scheduler scheduler;

    public ImageIngestRetentionScheduler(Scheduler scheduler) {
        this.scheduler = scheduler;
    }

    @PostConstruct
    public void scheduleAtStartup() {
        if (scheduler == null) {
            LOG.warn("ImageIngestRetentionScheduler: Quartz scheduler not present, skipping schedule");
            return;
        }
        try {
            JobKey jobKey = JobKey.jobKey(JOB_NAME, GROUP_NAME);
            TriggerKey triggerKey = TriggerKey.triggerKey(JOB_NAME, GROUP_NAME);

            JobDetail jobDetail = JobBuilder.newJob(ImageIngestRetentionJob.class)
                    .withIdentity(jobKey)
                    .withDescription("Daily sweep of dismissed fundus images (DR-025)")
                    .storeDurably(true)
                    .requestRecovery(false)
                    .build();

            Trigger trigger = TriggerBuilder.newTrigger()
                    .withIdentity(triggerKey)
                    .forJob(jobKey)
                    .withSchedule(CronScheduleBuilder.cronSchedule(CRON_EXPRESSION)
                            .withMisfireHandlingInstructionDoNothing())
                    .withDescription("Cron 03:30 daily — purge dismissed images past their window")
                    .build();

            scheduler.addJob(jobDetail, true, true);
            if (scheduler.checkExists(triggerKey)) {
                scheduler.rescheduleJob(triggerKey, trigger);
            } else {
                scheduler.scheduleJob(trigger);
            }
            LOG.info("ImageIngestRetentionScheduler: scheduled {} (cron='{}', group={})",
                    JOB_NAME, CRON_EXPRESSION, GROUP_NAME);
        } catch (SchedulerException e) {
            // Never fail boot over a retention schedule.
            LOG.warn("ImageIngestRetentionScheduler: failed to schedule the sweep: {}", e.getMessage(), e);
        }
    }
}
