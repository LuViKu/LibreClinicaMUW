/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.job;

import javax.sql.DataSource;

import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.quartz.QuartzJobBean;

import at.ac.meduniwien.ophthalmology.libreclinica.service.extract.ArchivedFileRetentionService;

/**
 * Phase E.6 — Quartz job wrapper for
 * {@link ArchivedFileRetentionService}.
 *
 * <p>Scheduled by {@link ArchivedFileRetentionScheduler} at boot to
 * fire daily at 03:00 (server time). One pass per fire — the service
 * itself is the unit of work; this class just wires the application
 * context up to it.
 */
public class ArchivedFileRetentionJob extends QuartzJobBean {

    private static final Logger LOG = LoggerFactory.getLogger(ArchivedFileRetentionJob.class);

    @Override
    protected void executeInternal(JobExecutionContext context) throws JobExecutionException {
        try {
            ApplicationContext appContext =
                    (ApplicationContext) context.getScheduler().getContext().get("applicationContext");
            if (appContext == null) {
                LOG.warn("ArchivedFileRetentionJob: applicationContext not present in scheduler context — skipping");
                return;
            }
            DataSource ds = (DataSource) appContext.getBean("dataSource");
            ArchivedFileRetentionService svc = new ArchivedFileRetentionService(ds);
            int removed = svc.garbageCollect();
            LOG.info("ArchivedFileRetentionJob: GC pass complete, removed={} retentionDays={}",
                    removed, svc.getRetentionDays());
        } catch (Exception e) {
            // Don't propagate — Quartz logs JobExecutionException as
            // an alert, but we'd rather have a warning + the next
            // scheduled run pick up the slack than a noisy stack
            // trace at 03:00 for an issue the next fire will retry.
            LOG.warn("ArchivedFileRetentionJob failed: {}", e.getMessage(), e);
        }
    }
}
