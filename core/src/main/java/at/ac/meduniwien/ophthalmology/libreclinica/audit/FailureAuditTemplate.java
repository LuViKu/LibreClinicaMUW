/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.ac.meduniwien.ophthalmology.libreclinica.dao.admin.AuditEventDAO;

/**
 * Phase A1 (2026-06-10) — wraps a clinical write path so any {@code Throwable}
 * it raises lands in {@code audit_log_event} as an
 * {@code OPERATION_FAILED} row (type 61) before the exception
 * propagates.
 *
 * <p>The §11.10(e) audit-trail requirement says the system must record
 * <em>actions</em> on clinical records — the legacy code captured
 * successful operations only. Anything that threw between commit + the
 * audit-write disappeared. This template closes that gap: it catches,
 * audits, and rethrows. Callers preserve their existing failure response
 * (HTTP 500, JSP redirect, …); the SPA + sysadmin both regain a
 * trail-of-evidence row that survives the outer transaction rollback
 * (the DAO uses a separate connection — see
 * {@link AuditEventDAO#insertOperationFailure}).
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * String reqId = MDC.get("reqId"); // null OK until A4 lands
 * try {
 *     return FailureAuditTemplate.runOrAudit(
 *         new AuditEventDAO(dataSource),
 *         currentUser.getId(),
 *         "study_subject",
 *         null, // entity does not yet exist at point of failure
 *         "SubjectsApiController.create",
 *         reqId,
 *         () -> doTheActualWrite()
 *     );
 * } catch (Exception e) {
 *     // existing failure-response logic is unchanged
 * }
 * }</pre>
 *
 * <h2>If the audit-write itself fails</h2>
 *
 * <p>The audit-write is wrapped in its own try/catch so a transient DB
 * error during the audit insert does not <em>swallow</em> the original
 * exception. Losing the original throwable would mean the operator sees
 * a generic "internal error" with no signal to compliance that something
 * went wrong — strictly worse than missing an audit row. The audit
 * failure logs at ERROR; reconciliation can scan request logs for the
 * exception class and cross-reference missing rows by reqId.
 */
public final class FailureAuditTemplate {

    private static final Logger LOG = LoggerFactory.getLogger(FailureAuditTemplate.class);

    private FailureAuditTemplate() {
        // Utility class — no instances.
    }

    /**
     * Functional interface for the wrapped operation. Allows checked
     * exceptions so callers don't have to wrap every SQLException in a
     * RuntimeException to satisfy {@link java.util.concurrent.Callable}.
     */
    @FunctionalInterface
    public interface CheckedRunnable<T> {
        T run() throws Exception;
    }

    /**
     * Run {@code body}. If it throws any {@link Throwable}, write an
     * OPERATION_FAILED audit row tagged with the supplied context, then
     * rethrow the original exception unchanged.
     *
     * @param auditDao     DAO used to write the failure row. Required.
     * @param userId       acting user id, 0 if not authenticated.
     * @param entityType   table or symbolic entity name. Required.
     * @param entityId     domain entity id, null when not applicable.
     * @param operation    short label for the operation
     *                     (controller method, servlet method).
     * @param reqId        request-correlation id from MDC. Null until
     *                     A4 populates the filter.
     * @param body         the work to perform.
     * @param <T>          return type.
     * @return whatever {@code body} returns on success.
     * @throws Exception   the original exception unchanged.
     */
    public static <T> T runOrAudit(AuditEventDAO auditDao,
                                   int userId,
                                   String entityType,
                                   Integer entityId,
                                   String operation,
                                   String reqId,
                                   CheckedRunnable<T> body) throws Exception {
        try {
            return body.run();
        } catch (Throwable t) {
            try {
                auditDao.insertOperationFailure(
                        userId,
                        entityType,
                        entityId,
                        operation,
                        t.getClass().getName(),
                        t.getMessage() == null ? "" : t.getMessage(),
                        reqId);
            } catch (Throwable auditFailure) {
                // Audit-write itself failed — log via SLF4J at ERROR
                // but do NOT swallow the original. Compliance can
                // detect missing rows via reconciliation; losing the
                // original exception is worse.
                LOG.error("Audit-failure insert itself failed for {}.{}: {}",
                        entityType, operation, auditFailure.getMessage(), auditFailure);
            }
            // Preserve the original Throwable's identity. Checked
            // exceptions ride the throws clause; unchecked + Error
            // propagate via the matching branches.
            if (t instanceof Exception e) {
                throw e;
            }
            if (t instanceof Error err) {
                throw err;
            }
            // Defensive — Throwable subclasses outside Exception / Error
            // shouldn't exist in practice but propagate as a generic
            // RuntimeException carrying the original cause.
            throw new RuntimeException(t);
        }
    }
}
