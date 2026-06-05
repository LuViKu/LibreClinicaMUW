/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.extract;

import java.time.Instant;
import java.util.Date;
import java.util.List;

import javax.sql.DataSource;

import org.quartz.CronExpression;
import org.quartz.CronScheduleBuilder;
import org.quartz.JobBuilder;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.quartz.TriggerKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.stereotype.Component;

import at.ac.meduniwien.ophthalmology.libreclinica.dao.extract.ExportJobDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.extract.ExportScheduleDAO;

/**
 * Phase E.6 — Data Export Phase 4.
 *
 * <p>Quartz bootstrap for the async export pipeline. On
 * {@link ContextRefreshedEvent} (root context refresh) this:
 *
 * <ol>
 *   <li>Registers a global polling trigger that fires
 *       {@link ExportJobRunner} every 30 seconds — drains queued
 *       export_job rows one at a time. Multi-instance safe via the
 *       DAO's {@code SELECT … FOR UPDATE SKIP LOCKED}.</li>
 *   <li>Loads every active {@code export_schedule} row, validates its
 *       cron, and registers a per-schedule cron trigger backed by
 *       {@link ScheduleFireJob} — that job inserts a fresh queued
 *       row + stamps the schedule's {@code last_run_at}.</li>
 * </ol>
 *
 * <p>Public API for the controller:
 *
 * <ul>
 *   <li>{@link #registerSchedule(long, int, String, String)} — call
 *       after a {@code POST /datasets/{id}/schedules} insert so the
 *       new trigger goes live without an app restart.</li>
 *   <li>{@link #unregisterSchedule(long)} — call after a
 *       {@code DELETE /schedules/{id}} so the soft-deleted row stops
 *       firing.</li>
 *   <li>{@link #computeNextFireTime(String)} — helper that returns
 *       the next fire instant for a given cron, used by the
 *       controller to populate {@code next_run_at} on insert.</li>
 * </ul>
 */
@Component
public class ExportScheduleRegistrar implements ApplicationListener<ContextRefreshedEvent> {

    private static final Logger LOG = LoggerFactory.getLogger(ExportScheduleRegistrar.class);

    private static final String GROUP_POLLER = "exportJobPoller";
    private static final String GROUP_SCHEDULE = "exportSchedule";
    private static final String POLLER_JOB_NAME = "exportJobRunner";
    private static final String POLLER_TRIGGER_NAME = "exportJobRunnerTrigger";

    /** 30 seconds, as required by the brief. */
    private static final int POLLER_INTERVAL_SECONDS = 30;

    private final Scheduler scheduler;
    private final DataSource dataSource;

    private volatile boolean booted = false;

    @Autowired
    public ExportScheduleRegistrar(
            @Qualifier("schedulerFactoryBean") Scheduler scheduler,
            @Qualifier("dataSource") DataSource dataSource) {
        this.scheduler = scheduler;
        this.dataSource = dataSource;
    }

    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        // ContextRefreshedEvent fires for every refresh — both root and
        // child contexts. We only want to boot once, on the root.
        if (booted) return;
        if (event.getApplicationContext().getParent() != null) return;
        booted = true;
        try {
            registerPoller();
            reloadSchedules();
        } catch (Exception e) {
            // Don't kill app startup — surface in logs, schedules can be
            // rewired manually via re-POST.
            LOG.error("ExportScheduleRegistrar boot failed (continuing): {}", e.getMessage(), e);
        }
    }

    /* ------------------------------------------------------------------ */
    /* Boot                                                               */
    /* ------------------------------------------------------------------ */

    private void registerPoller() throws SchedulerException {
        JobKey jobKey = JobKey.jobKey(POLLER_JOB_NAME, GROUP_POLLER);
        if (scheduler.checkExists(jobKey)) {
            LOG.debug("ExportJobRunner poller already registered — skipping");
            return;
        }
        JobDetail job = JobBuilder.newJob(ExportJobRunner.class)
                .withIdentity(jobKey)
                .withDescription("Phase E.6 P4 — drains export_job queue every "
                        + POLLER_INTERVAL_SECONDS + "s")
                .storeDurably(true)
                .build();
        Trigger trigger = TriggerBuilder.newTrigger()
                .withIdentity(POLLER_TRIGGER_NAME, GROUP_POLLER)
                .forJob(job)
                .startAt(new Date(System.currentTimeMillis() + 5_000L))
                .withSchedule(SimpleScheduleBuilder.simpleSchedule()
                        .withIntervalInSeconds(POLLER_INTERVAL_SECONDS)
                        .repeatForever()
                        .withMisfireHandlingInstructionNextWithExistingCount())
                .build();
        scheduler.scheduleJob(job, trigger);
        LOG.info("ExportJobRunner poller registered (every {} s)", POLLER_INTERVAL_SECONDS);
    }

    private void reloadSchedules() {
        ExportScheduleDAO dao = new ExportScheduleDAO(dataSource);
        List<ExportScheduleDAO.Row> active = dao.findAllActive();
        int registered = 0;
        for (ExportScheduleDAO.Row row : active) {
            try {
                registerScheduleInternal(row.id, row.datasetId, row.format, row.cronExpression);
                registered++;
            } catch (Exception e) {
                LOG.warn("Schedule id={} dataset_id={} cron='{}' rejected at boot: {}",
                        row.id, row.datasetId, row.cronExpression, e.getMessage());
            }
        }
        LOG.info("ExportScheduleRegistrar: registered {}/{} active schedules at boot",
                registered, active.size());
    }

    /* ------------------------------------------------------------------ */
    /* Public API — called from the controller                            */
    /* ------------------------------------------------------------------ */

    public void registerSchedule(long scheduleId, int datasetId, String format, String cronExpression)
            throws SchedulerException {
        // Defer until boot has wired the scheduler — the controller
        // calls this synchronously from the request thread, so by the
        // time it gets called the scheduler is up.
        registerScheduleInternal(scheduleId, datasetId, format, cronExpression);
    }

    public void unregisterSchedule(long scheduleId) {
        JobKey jobKey = JobKey.jobKey(scheduleJobName(scheduleId), GROUP_SCHEDULE);
        TriggerKey triggerKey = TriggerKey.triggerKey(scheduleTriggerName(scheduleId), GROUP_SCHEDULE);
        try {
            scheduler.unscheduleJob(triggerKey);
            scheduler.deleteJob(jobKey);
        } catch (SchedulerException e) {
            LOG.warn("unregisterSchedule(id={}) failed: {}", scheduleId, e.getMessage());
        }
    }

    /** Returns the next fire instant for the given cron, or null if it never fires again. */
    public Instant computeNextFireTime(String cronExpression) {
        try {
            CronExpression cron = new CronExpression(cronExpression);
            Date next = cron.getNextValidTimeAfter(new Date());
            return next == null ? null : next.toInstant();
        } catch (Exception e) {
            return null;
        }
    }

    /** Cheap validation hook the controller calls before persisting. */
    public boolean isValidCron(String cronExpression) {
        return cronExpression != null
                && !cronExpression.isBlank()
                && CronExpression.isValidExpression(cronExpression);
    }

    /* ------------------------------------------------------------------ */
    /* Internals                                                          */
    /* ------------------------------------------------------------------ */

    private void registerScheduleInternal(long scheduleId, int datasetId, String format,
                                          String cronExpression) throws SchedulerException {
        JobKey jobKey = JobKey.jobKey(scheduleJobName(scheduleId), GROUP_SCHEDULE);
        if (scheduler.checkExists(jobKey)) {
            scheduler.deleteJob(jobKey);
        }
        JobDataMap data = new JobDataMap();
        data.put(ScheduleFireJob.KEY_SCHEDULE_ID, scheduleId);
        data.put(ScheduleFireJob.KEY_DATASET_ID, datasetId);
        data.put(ScheduleFireJob.KEY_FORMAT, format);
        data.put(ScheduleFireJob.KEY_CRON, cronExpression);

        JobDetail job = JobBuilder.newJob(ScheduleFireJob.class)
                .withIdentity(jobKey)
                .withDescription("Phase E.6 P4 — fires export_schedule id=" + scheduleId)
                .usingJobData(data)
                .storeDurably(true)
                .build();
        Trigger trigger = TriggerBuilder.newTrigger()
                .withIdentity(scheduleTriggerName(scheduleId), GROUP_SCHEDULE)
                .forJob(job)
                .withSchedule(CronScheduleBuilder.cronSchedule(cronExpression)
                        .withMisfireHandlingInstructionFireAndProceed())
                .build();
        scheduler.scheduleJob(job, trigger);
        LOG.info("Registered schedule id={} dataset_id={} format={} cron='{}'",
                scheduleId, datasetId, format, cronExpression);
    }

    private static String scheduleJobName(long scheduleId) {
        return "exportSchedule-" + scheduleId;
    }

    private static String scheduleTriggerName(long scheduleId) {
        return "exportScheduleTrigger-" + scheduleId;
    }

    /* ------------------------------------------------------------------ */
    /* Quartz Job that the per-schedule trigger fires                     */
    /* ------------------------------------------------------------------ */

    public static class ScheduleFireJob implements org.quartz.Job {
        public static final String KEY_SCHEDULE_ID = "scheduleId";
        public static final String KEY_DATASET_ID = "datasetId";
        public static final String KEY_FORMAT = "format";
        public static final String KEY_CRON = "cron";

        @Override
        public void execute(JobExecutionContext context) throws JobExecutionException {
            JobDataMap data = context.getMergedJobDataMap();
            long scheduleId = data.getLong(KEY_SCHEDULE_ID);
            int datasetId = data.getInt(KEY_DATASET_ID);
            String format = data.getString(KEY_FORMAT);
            String cron = data.getString(KEY_CRON);

            try {
                org.springframework.context.ApplicationContext appCtx =
                        (org.springframework.context.ApplicationContext)
                                context.getScheduler().getContext().get("applicationContext");
                if (appCtx == null) {
                    LOG.error("ScheduleFireJob: missing applicationContext for schedule_id={}", scheduleId);
                    return;
                }
                DataSource dataSource = appCtx.getBean("dataSource", DataSource.class);
                ExportScheduleDAO scheduleDao = new ExportScheduleDAO(dataSource);
                ExportScheduleDAO.Row row = scheduleDao.findById(scheduleId);
                if (row == null) {
                    LOG.warn("ScheduleFireJob: schedule_id={} not found; was it deleted?", scheduleId);
                    return;
                }
                if (!row.active) {
                    LOG.info("ScheduleFireJob: schedule_id={} is inactive; skipping", scheduleId);
                    return;
                }
                // Enqueue as if the creator had hit POST /export.
                ExportJobDAO jobDao = new ExportJobDAO(dataSource);
                long jobId = jobDao.insertQueued(datasetId, format, row.createdBy);

                Instant now = Instant.now();
                Instant next = null;
                try {
                    CronExpression ce = new CronExpression(cron);
                    Date nextDate = ce.getNextValidTimeAfter(new Date());
                    next = nextDate == null ? null : nextDate.toInstant();
                } catch (Exception e) {
                    // cron was validated on POST, so this shouldn't happen
                    LOG.warn("Cron '{}' rejected at fire time: {}", cron, e.getMessage());
                }
                scheduleDao.stampRun(scheduleId, jobId, now, next);
                LOG.info("ScheduleFireJob: schedule_id={} fired -> queued export_job id={}",
                        scheduleId, jobId);
            } catch (Throwable t) { // NOSONAR
                LOG.error("ScheduleFireJob: schedule_id=" + scheduleId + " failed", t);
            }
        }
    }
}
