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
 * Phase E.6 — Wires {@link ArchivedFileRetentionJob} into the
 * shared Quartz {@link Scheduler} at boot.
 *
 * <p>Fires daily at 03:00 server-time. The job is durable (kept in
 * the Quartz schema across restarts) and the trigger is idempotent
 * (re-scheduled with the same {@code TriggerKey} so a restart
 * doesn't accumulate duplicates).
 *
 * <p>Lives in {@code core/} alongside the rest of the Quartz wiring
 * ({@link at.ac.meduniwien.ophthalmology.libreclinica.config.QuartzConfig}).
 * Registered as a Spring bean from {@code QuartzConfig.archivedFileRetentionScheduler}
 * so the {@code @PostConstruct} fires once the {@code OpenClinicaSchedulerFactoryBean}
 * has already started the scheduler.
 */
public class ArchivedFileRetentionScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(ArchivedFileRetentionScheduler.class);

    /** Identifier for the durable JobDetail (Quartz schema key). */
    public static final String JOB_NAME = "archivedFileRetention";

    /** Trigger + job group — separate from the user-facing extract groups. */
    public static final String GROUP_NAME = "lc-muw-retention";

    /** Cron expression: every day at 03:00:00 (Quartz quartz-cron syntax: sec min hour …). */
    public static final String CRON_EXPRESSION = "0 0 3 * * ?";

    private final Scheduler scheduler;

    public ArchivedFileRetentionScheduler(Scheduler scheduler) {
        this.scheduler = scheduler;
    }

    @PostConstruct
    public void scheduleAtStartup() {
        if (scheduler == null) {
            LOG.warn("ArchivedFileRetentionScheduler: Quartz scheduler not present, skipping schedule");
            return;
        }
        try {
            JobKey jobKey = JobKey.jobKey(JOB_NAME, GROUP_NAME);
            TriggerKey triggerKey = TriggerKey.triggerKey(JOB_NAME, GROUP_NAME);

            JobDetail jobDetail = JobBuilder.newJob(ArchivedFileRetentionJob.class)
                    .withIdentity(jobKey)
                    .withDescription("Daily archived_dataset_file retention sweep (Phase E.6)")
                    .storeDurably(true)
                    .requestRecovery(false)
                    .build();

            Trigger trigger = TriggerBuilder.newTrigger()
                    .withIdentity(triggerKey)
                    .forJob(jobKey)
                    .withSchedule(CronScheduleBuilder.cronSchedule(CRON_EXPRESSION)
                            .withMisfireHandlingInstructionDoNothing())
                    .withDescription("Cron 03:00 daily — sweep expired exports")
                    .build();

            // Idempotent re-registration: on every boot replace the
            // job+trigger so the cron and class wire-up reflect the
            // current code. addJob(replace=true) overwrites the JobDetail
            // even when a trigger references it; rescheduleJob handles
            // the trigger half (or scheduleJob when absent).
            scheduler.addJob(jobDetail, true, true);
            if (scheduler.checkExists(triggerKey)) {
                scheduler.rescheduleJob(triggerKey, trigger);
            } else {
                scheduler.scheduleJob(trigger);
            }
            LOG.info("ArchivedFileRetentionScheduler: scheduled {} (cron='{}', group={})",
                    JOB_NAME, CRON_EXPRESSION, GROUP_NAME);
        } catch (SchedulerException e) {
            // Don't fail the app boot — the scheduled retention sweep
            // is a defence-in-depth feature; operators can manually run
            // the GC via DB if scheduling silently breaks.
            LOG.warn("ArchivedFileRetentionScheduler: failed to schedule retention job: {}",
                    e.getMessage(), e);
        }
    }
}
