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
import org.slf4j.MDC;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.quartz.QuartzJobBean;

import at.ac.meduniwien.ophthalmology.libreclinica.dao.admin.AuditEventDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.ImageIngestRetentionService;

/**
 * DR-025 — Quartz wrapper for {@link ImageIngestRetentionService}.
 *
 * <p>Scheduled by {@link ImageIngestRetentionScheduler} to fire daily, half an
 * hour after the export retention sweep so the two never contend for the same
 * connections. One pass per fire.
 */
public class ImageIngestRetentionJob extends QuartzJobBean {

    private static final Logger LOG = LoggerFactory.getLogger(ImageIngestRetentionJob.class);

    @Override
    protected void executeInternal(JobExecutionContext context) throws JobExecutionException {
        DataSource ds = null;
        try {
            ApplicationContext appContext =
                    (ApplicationContext) context.getScheduler().getContext().get("applicationContext");
            if (appContext == null) {
                LOG.warn("ImageIngestRetentionJob: applicationContext not in scheduler context — skipping");
                return;
            }
            ds = (DataSource) appContext.getBean("dataSource");
            ImageIngestRetentionService svc = new ImageIngestRetentionService(ds);
            int removed = svc.garbageCollect();
            LOG.info("ImageIngestRetentionJob: pass complete, removed={} retentionDays={}",
                    removed, svc.getRetentionDays());
        } catch (Exception e) {
            // Same treatment as the export sweep: loud log plus a JOB_FAILED
            // audit row, so an overnight failure is visible in the sysadmin
            // view rather than only in a log nobody reads. Return normally so
            // Quartz waits for the next scheduled fire instead of retrying.
            LOG.error("ImageIngestRetentionJob failed: {}", e.getMessage(), e);
            if (ds != null) {
                try {
                    new AuditEventDAO(ds).insertOperationFailure(
                            0,
                            "quartz_job",
                            null,
                            "ImageIngestRetentionJob.execute",
                            e.getClass().getName(),
                            e.getMessage() == null ? "" : e.getMessage(),
                            MDC.get("reqId"));
                } catch (Throwable auditFailure) {
                    LOG.error("ImageIngestRetentionJob: JOB_FAILED audit-write failed: {}",
                            auditFailure.getMessage(), auditFailure);
                }
            }
        }
    }
}
