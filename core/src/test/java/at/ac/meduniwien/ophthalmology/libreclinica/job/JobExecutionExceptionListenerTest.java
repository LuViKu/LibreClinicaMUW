/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.job;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.JobKey;

import at.ac.meduniwien.ophthalmology.libreclinica.dao.admin.AuditEventDAO;

/**
 * Phase A6 (2026-06-10) — unit test for {@link JobExecutionExceptionListener}.
 *
 * <p>The listener writes a {@code JOB_FAILED} (event-type id 62) row
 * via {@link AuditEventDAO#insertOperationFailure} when a Quartz job
 * fires with an exception. This test runs without a Quartz scheduler
 * or a live DataSource — the DAO is mocked, and the listener's
 * package-private {@code resolveAuditDao} seam is overridden via a
 * subclass to return the mock directly. That isolates the assertion
 * to the audit-write contract (call shape + argument values), which
 * is what we actually want to pin.
 */
public class JobExecutionExceptionListenerTest {

    /**
     * The load-bearing case: a failing job lands exactly one
     * {@code insertOperationFailure} call carrying the job-key
     * details in the {@code operation} string and the throwable's
     * class + message in the {@code errorClass} / {@code errorMessage}
     * fields.
     */
    @Test
    public void jobFailureWritesAuditRow() throws Exception {
        AuditEventDAO dao = mock(AuditEventDAO.class);

        JobExecutionContext context = mock(JobExecutionContext.class);
        JobDetail jobDetail = mock(JobDetail.class);
        JobKey key = new JobKey("dailyRetentionSweep", "archive");
        when(context.getJobDetail()).thenReturn(jobDetail);
        when(jobDetail.getKey()).thenReturn(key);

        JobExecutionException jobException = new JobExecutionException("simulated failure");

        // Subclass-with-seam: the resolveAuditDao(...) hook returns the
        // mock so jobWasExecuted can write to it without booting a
        // Spring scheduler context.
        JobExecutionExceptionListener listener = new JobExecutionExceptionListener() {
            @Override
            AuditEventDAO resolveAuditDao(JobExecutionContext ctx) {
                return dao;
            }
        };

        listener.jobWasExecuted(context, jobException);

        // Exactly one audit-write call.
        ArgumentCaptor<String> operationCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> errorClassCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> errorMessageCaptor = ArgumentCaptor.forClass(String.class);
        verify(dao, times(1)).insertOperationFailure(
                eq(0),
                eq("quartz_job"),
                isNull(),
                operationCaptor.capture(),
                errorClassCaptor.capture(),
                errorMessageCaptor.capture(),
                /* reqId — null in this test, MDC is not set */ isNull());

        // The operation string must encode the job key so a
        // sysadmin-side audit query can distinguish failures across
        // jobs. We assert prefix + key suffix rather than exact
        // equality because the prefix string ("Quartz.jobExecutionVetoed.")
        // is fixed by the listener but the key format may evolve.
        String operation = operationCaptor.getValue();
        org.junit.Assert.assertTrue(
                "operation should be prefixed with Quartz.jobExecutionVetoed.: " + operation,
                operation.startsWith("Quartz.jobExecutionVetoed."));
        org.junit.Assert.assertTrue(
                "operation should encode the failing job key (archive.dailyRetentionSweep): " + operation,
                operation.contains("archive") && operation.contains("dailyRetentionSweep"));

        // Throwable details propagate verbatim.
        org.junit.Assert.assertEquals(
                "org.quartz.JobExecutionException", errorClassCaptor.getValue());
        org.junit.Assert.assertEquals(
                "simulated failure", errorMessageCaptor.getValue());
    }

    /**
     * Sanity guard: a job that finishes without an exception MUST NOT
     * write an audit row. Without this it would be easy to slip a
     * regression where every successful fire spams a JOB_FAILED row.
     */
    @Test
    public void successfulJobWritesNothing() throws Exception {
        AuditEventDAO dao = mock(AuditEventDAO.class);
        JobExecutionContext context = mock(JobExecutionContext.class);

        JobExecutionExceptionListener listener = new JobExecutionExceptionListener() {
            @Override
            AuditEventDAO resolveAuditDao(JobExecutionContext ctx) {
                return dao;
            }
        };

        listener.jobWasExecuted(context, null);

        verify(dao, never()).insertOperationFailure(
                anyInt(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
    }
}
