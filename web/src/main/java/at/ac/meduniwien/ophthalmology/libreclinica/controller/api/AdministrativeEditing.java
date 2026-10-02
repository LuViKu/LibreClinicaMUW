/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import javax.sql.DataSource;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Status;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.EventCRFBean;

/**
 * Administrative editing: when a change to an event CRF's data needs a reason
 * for change.
 *
 * <p>In legacy an event CRF, once marked complete, is edited only through
 * {@code AdministrativeEditingServlet}, and stays there. When the study sets
 * {@code adminForcedReasonForChange} (the default), {@code DataEntryServlet}
 * refuses every changed item that has no Reason for Change note.
 *
 * <p>The SPA completes a CRF by date and can reopen it, which clears the date.
 * Keying the requirement on the date let a reopened CRF be edited without any
 * reason. So it is keyed on whether the CRF has <em>ever</em> been completed,
 * which the audit trail records durably: audit rows are never deleted, and
 * every way a CRF is completed or reopened writes one against the
 * {@code event_crf} row.
 */
final class AdministrativeEditing {

    private AdministrativeEditing() {}

    /**
     * Audit rows on {@code event_crf} that show the CRF was complete.
     *
     * <ul>
     *   <li>8 and 14: completed. The {@code event_crf} trigger writes them when
     *       legacy moves the status from available to completed (14 when
     *       signed on the way); the SPA's mark-complete writes 8.</li>
     *   <li>10 and 15: initial data entry completed, the second pass of double
     *       data entry pending (status 1 to 4).</li>
     *   <li>11 and 16: the second pass completed (status 4 to 2). Before
     *       2026-09-27 the SPA also wrote 11 when it reopened a CRF, with
     *       {@code date_completed} as the column, and when it restored one,
     *       with {@code status_id}; a restore says nothing about completion.</li>
     *   <li>138: reopened, which only a completed CRF can be.</li>
     * </ul>
     */
    private static final String COMPLETION_EVIDENCE_SQL =
            "SELECT 1 FROM audit_log_event "
                    + " WHERE entity_id = ? AND audit_table = 'event_crf' "
                    + "   AND (audit_log_event_type_id IN (8, 10, 14, 15, 16, "
                    + AuditTypeIds.EVENT_CRF_REOPENED + ") "
                    + "        OR (audit_log_event_type_id = 11 "
                    + "            AND COALESCE(entity_name, '') <> 'status_id')) "
                    + " LIMIT 1";

    /**
     * Whether the event CRF is complete now or has been at any time.
     *
     * @throws IllegalStateException when the audit trail cannot be read: the
     *         caller must not treat an unknown as "never completed"
     */
    static boolean everCompleted(DataSource dataSource, EventCRFBean ecb) {
        if (ecb.getDateCompleted() != null || ecb.getDateValidateCompleted() != null
                || Status.UNAVAILABLE.equals(ecb.getStatus())) {
            return true;
        }
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(COMPLETION_EVIDENCE_SQL)) {
            ps.setInt(1, ecb.getId());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Could not read the completion history of event_crf " + ecb.getId(), e);
        }
    }

    /**
     * Whether every change to the event CRF's data needs a reason: it has
     * been completed and the session's study forces the reason, as
     * {@code AdministrativeEditingServlet.isAdminForcedReasonForChange} reads
     * it.
     */
    static boolean reasonRequired(DataSource dataSource, StudyBean study, EventCRFBean ecb) {
        return everCompleted(dataSource, ecb)
                && StudyParameters.adminForcedReasonForChange(dataSource, study);
    }
}
