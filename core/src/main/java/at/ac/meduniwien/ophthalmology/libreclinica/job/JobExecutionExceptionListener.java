/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 */
package at.ac.meduniwien.ophthalmology.libreclinica.job;

import javax.sql.DataSource;

import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.JobKey;
import org.quartz.listeners.JobListenerSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.ApplicationContext;

import at.ac.meduniwien.ophthalmology.libreclinica.dao.admin.AuditEventDAO;

/**
 * @author Doug Rodrigues (douglas.rodrigues@openclinica.com)
 *
 * <p>Phase A6 (2026-06-10) — formerly logged at WARN and went silent.
 * Now logs at ERROR and writes a {@code JOB_FAILED} audit row
 * (event-type id 62, seeded by A1's
 * {@code lc-muw-2026-06-10-audit-event-type-operation-failed.xml})
 * so a clinical-trial production deployment surfaces Quartz failures
 * in the sysadmin audit view rather than burying them in a single
 * log line.
 *
 * <p>The DataSource is resolved lazily from the scheduler's
 * application-context key ({@code "applicationContext"}, set in
 * {@link at.ac.meduniwien.ophthalmology.libreclinica.config.QuartzConfig
 * #schedulerFactoryBean}). This keeps the listener constructor zero-arg
 * so {@code QuartzConfig.setGlobalJobListeners(new JobListener[]{
 * new JobExecutionExceptionListener()})} compiles unchanged. Tests
 * override {@link #resolveAuditDao(JobExecutionContext)} to inject a
 * mocked DAO.
 */
public class JobExecutionExceptionListener extends JobListenerSupport {

    private static final Logger LOG = LoggerFactory.getLogger(JobExecutionExceptionListener.class);

    /* (non-Javadoc)
     * @see org.quartz.JobListener#getName()
     */
    @Override
    public String getName() {
        return "JobExecutionExceptionListener";
    }

    /* (non-Javadoc)
     * @see org.quartz.listeners.JobListenerSupport#jobWasExecuted(org.quartz.JobExecutionContext, org.quartz.JobExecutionException)
     */
    @Override
    public void jobWasExecuted(JobExecutionContext context, JobExecutionException jobException) {
        super.jobWasExecuted(context, jobException);
        if (jobException == null) {
            return;
        }

        // Phase A6: ERROR, not WARN — clinical-trial production
        // deserves a louder alarm. A daily scheduled job that goes
        // silently red between fires breaks the §11.10(e) trail.
        LOG.error("Error executing Quartz job", jobException);

        try {
            AuditEventDAO dao = resolveAuditDao(context);
            if (dao == null) {
                // Lazy resolution failed (scheduler context missing the
                // applicationContext key, or the DataSource bean not
                // resolvable). Already logged inside resolveAuditDao.
                return;
            }
            dao.insertOperationFailure(
                    0,                                   // system user
                    "quartz_job",
                    null,
                    "Quartz.jobExecutionVetoed." + describeJobKey(context),
                    jobException.getClass().getName(),
                    jobException.getMessage() == null ? "" : jobException.getMessage(),
                    MDC.get("reqId"));
        } catch (Throwable auditFailure) {
            // Defence-in-depth: a failed audit-write must not mask
            // the original Quartz failure (which has already been
            // logged at ERROR above). Log + swallow.
            LOG.error("JOB_FAILED audit-write failed: {}",
                    auditFailure.getMessage(), auditFailure);
        }
    }

    /**
     * Resolve the {@link AuditEventDAO} by walking the Quartz
     * scheduler context for the {@code applicationContext} key set in
     * {@link at.ac.meduniwien.ophthalmology.libreclinica.config
     * .QuartzConfig#schedulerFactoryBean}.
     *
     * <p>Package-private to expose a seam for the unit test, which
     * overrides this method to return a Mockito mock without booting
     * a Spring context.
     */
    AuditEventDAO resolveAuditDao(JobExecutionContext context) {
        try {
            ApplicationContext appContext =
                    (ApplicationContext) context.getScheduler().getContext().get("applicationContext");
            if (appContext == null) {
                LOG.warn("JobExecutionExceptionListener: applicationContext missing from "
                        + "scheduler context — JOB_FAILED audit row will not be written");
                return null;
            }
            DataSource ds = (DataSource) appContext.getBean("dataSource");
            return new AuditEventDAO(ds);
        } catch (Throwable t) {
            LOG.warn("JobExecutionExceptionListener: failed to resolve AuditEventDAO: {}",
                    t.getMessage(), t);
            return null;
        }
    }

    /**
     * Build a stable identifier for the failing job key so the audit
     * row's {@code entity_name} column distinguishes between e.g.
     * the daily archived-file retention sweep and an ad-hoc data
     * extract job. Falls back to {@code "unknown"} if the job
     * detail is somehow absent.
     */
    private static String describeJobKey(JobExecutionContext context) {
        if (context == null || context.getJobDetail() == null) {
            return "unknown";
        }
        JobKey key = context.getJobDetail().getKey();
        if (key == null) {
            return "unknown";
        }
        String group = key.getGroup() == null ? "" : key.getGroup();
        String name = key.getName() == null ? "" : key.getName();
        return group.isEmpty() ? name : group + "." + name;
    }

}
