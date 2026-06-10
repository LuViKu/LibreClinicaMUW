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
        DataSource ds = null;
        try {
            ApplicationContext appContext =
                    (ApplicationContext) context.getScheduler().getContext().get("applicationContext");
            if (appContext == null) {
                LOG.warn("ArchivedFileRetentionJob: applicationContext not present in scheduler context — skipping");
                return;
            }
            ds = (DataSource) appContext.getBean("dataSource");
            ArchivedFileRetentionService svc = new ArchivedFileRetentionService(ds);
            int removed = svc.garbageCollect();
            LOG.info("ArchivedFileRetentionJob: GC pass complete, removed={} retentionDays={}",
                    removed, svc.getRetentionDays());
        } catch (Exception e) {
            // Phase A6 (2026-06-10): bump to ERROR (was WARN) +
            // write a JOB_FAILED audit row. Clinical-trial production
            // deserves a louder alarm than a single log line, and
            // the audit trail surfaces silent overnight failures in
            // the sysadmin view. We still return normally so Quartz
            // doesn't immediately reschedule — the misfire policy +
            // next scheduled fire handle retry.
            LOG.error("ArchivedFileRetentionJob failed: {}", e.getMessage(), e);
            if (ds != null) {
                try {
                    new AuditEventDAO(ds).insertOperationFailure(
                            0,                          // system user
                            "quartz_job",
                            null,
                            "ArchivedFileRetentionJob.execute",
                            e.getClass().getName(),
                            e.getMessage() == null ? "" : e.getMessage(),
                            MDC.get("reqId"));
                } catch (Throwable auditFailure) {
                    LOG.error("ArchivedFileRetentionJob: JOB_FAILED audit-write failed: {}",
                            auditFailure.getMessage(), auditFailure);
                }
            }
        }
    }
}
