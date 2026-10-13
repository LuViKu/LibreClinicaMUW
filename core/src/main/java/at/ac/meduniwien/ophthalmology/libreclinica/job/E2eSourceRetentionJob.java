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
import org.springframework.lang.NonNull;
import org.springframework.scheduling.quartz.QuartzJobBean;

import at.ac.meduniwien.ophthalmology.libreclinica.dao.admin.AuditEventDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.E2eSourceRetentionService;

/**
 * DR-041 - Quartz wrapper for {@link E2eSourceRetentionService}: deletes the
 * original {@code .E2E} of a split upload once
 * {@code core.retinalInference.e2eSourceRetentionDays} have passed. Scheduled by
 * {@link E2eSourceRetentionScheduler}; one pass per fire.
 */
public class E2eSourceRetentionJob extends QuartzJobBean {

    private static final Logger LOG = LoggerFactory.getLogger(E2eSourceRetentionJob.class);

    @Override
    protected void executeInternal(@NonNull JobExecutionContext context) throws JobExecutionException {
        DataSource ds = null;
        try {
            ApplicationContext appContext =
                    (ApplicationContext) context.getScheduler().getContext().get("applicationContext");
            if (appContext == null) {
                LOG.warn("E2eSourceRetentionJob: applicationContext not in scheduler context - skipping");
                return;
            }
            ds = (DataSource) appContext.getBean("dataSource");
            E2eSourceRetentionService svc = new E2eSourceRetentionService(ds);
            int deleted = svc.garbageCollect();
            LOG.info("E2eSourceRetentionJob: pass complete, deleted={} retentionDays={}",
                    deleted, svc.getRetentionDays());
        } catch (Exception e) {
            // As the other retention jobs: a loud log and a JOB_FAILED audit row, then return
            // normally so Quartz waits for the next fire.
            LOG.error("E2eSourceRetentionJob failed: {}", e.getMessage(), e);
            if (ds != null) {
                try {
                    new AuditEventDAO(ds).insertOperationFailure(
                            0,
                            "quartz_job",
                            null,
                            "E2eSourceRetentionJob.execute",
                            e.getClass().getName(),
                            e.getMessage() == null ? "" : e.getMessage(),
                            MDC.get("reqId"));
                } catch (Throwable auditFailure) {
                    LOG.error("E2eSourceRetentionJob: JOB_FAILED audit-write failed: {}",
                            auditFailure.getMessage(), auditFailure);
                }
            }
        }
    }
}
