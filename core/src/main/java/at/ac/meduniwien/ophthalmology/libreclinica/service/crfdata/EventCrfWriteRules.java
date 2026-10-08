/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.crfdata;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

/**
 * What every writer of an event CRF's values keeps to, in one place: when
 * the values may not change at all, and what a change sets off.
 *
 * <ul>
 *   <li><b>No change to removed, locked or signed data.</b> A value of an
 *       event CRF may not change while the event CRF, its visit, its subject
 *       or its study is removed, locked or signed (a study also when
 *       frozen). Legacy hides removed data and refuses entry into locked
 *       data; the SPA refuses saves to a signed CRF, since it has no way to
 *       withdraw a signature the way legacy administrative editing does.
 *       {@link #refusal} says which applies.</li>
 *   <li><b>A change ends source data verification.</b> Verification attests
 *       that the data match the source, so a changed value withdraws it, as
 *       legacy administrative editing does: {@link #withdrawVerification}
 *       clears {@code sdv_status} and records the editor, and the
 *       {@code event_crf} trigger writes the audit row (type 32, TRUE to
 *       FALSE).</li>
 * </ul>
 *
 * <p>The change itself is audited by the {@code item_data} triggers, whoever
 * writes it: {@code item_data_initial} for a new value, {@code item_data_update}
 * for a changed one, attributed to the row's owner and updater.
 */
public final class EventCrfWriteRules {

    private EventCrfWriteRules() {}

    /** Why an event CRF's values may not change. */
    public enum Refusal {
        /** The event CRF, visit, subject or study is removed or auto-removed. */
        REMOVED("is removed; restore it first"),
        /** The event CRF, visit or subject is locked, or the study is locked or frozen. */
        LOCKED("is locked"),
        /** The event CRF, visit or subject is signed. */
        SIGNED("is signed");

        private final String reason;

        Refusal(String reason) {
            this.reason = reason;
        }

        /** Completes "event CRF n ..." in a message. */
        public String reason() {
            return reason;
        }
    }

    // Status ids: 5 removed, 6 locked, 7 auto-removed, 8 signed, 9 frozen.
    // Visit (subject_event_status): 7 locked, 8 signed.
    private static final String STATE_SQL =
            "SELECT ec.status_id, se.status_id, se.subject_event_status_id, ss.status_id, "
                    + "       st.status_id, parent.status_id "
                    + "  FROM event_crf ec "
                    + "  JOIN study_event se ON se.study_event_id = ec.study_event_id "
                    + "  JOIN study_subject ss ON ss.study_subject_id = ec.study_subject_id "
                    + "  JOIN study st ON st.study_id = ss.study_id "
                    + "  LEFT JOIN study parent ON parent.study_id = st.parent_study_id "
                    + " WHERE ec.event_crf_id = ?";

    /**
     * Why the values of the event CRF may not change now, or {@code null}
     * when they may. Removal outranks a lock, and a lock a signature.
     *
     * @return {@code null} too when there is no such event CRF; the caller
     *         has found it already
     */
    public static Refusal refusal(Connection c, int eventCrfId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(STATE_SQL)) {
            ps.setInt(1, eventCrfId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                int eventCrf = rs.getInt(1);
                int visit = rs.getInt(2);
                int visitStatus = rs.getInt(3);
                int subject = rs.getInt(4);
                int study = rs.getInt(5);
                int parentStudy = rs.getInt(6);
                if (removed(eventCrf) || removed(visit) || removed(subject)
                        || removed(study) || removed(parentStudy)) {
                    return Refusal.REMOVED;
                }
                if (eventCrf == 6 || visitStatus == 7 || subject == 6
                        || lockedStudy(study) || lockedStudy(parentStudy)) {
                    return Refusal.LOCKED;
                }
                if (eventCrf == 8 || visitStatus == 8 || subject == 8) {
                    return Refusal.SIGNED;
                }
                return null;
            }
        }
    }

    private static boolean removed(int statusId) {
        return statusId == 5 || statusId == 7;
    }

    private static boolean lockedStudy(int statusId) {
        return statusId == 6 || statusId == 9;
    }

    /**
     * Withdraw source data verification from an event CRF whose values just
     * changed, if it was verified.
     *
     * @param actorUserId who changed the values; recorded as
     *                    {@code sdv_update_id}, the trigger's audit author.
     *                    {@code null} for a writer that is no user account,
     *                    such as the public BCVA portal: the audit row then
     *                    names nobody rather than a stand-in account
     * @return {@code true} when the CRF was verified and no longer is
     */
    public static boolean withdrawVerification(Connection c, int eventCrfId, Integer actorUserId)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE event_crf SET sdv_status = false, sdv_update_id = ? "
                        + " WHERE event_crf_id = ? AND sdv_status = true")) {
            if (actorUserId == null) {
                ps.setNull(1, Types.INTEGER);
            } else {
                ps.setInt(1, actorUserId);
            }
            ps.setInt(2, eventCrfId);
            return ps.executeUpdate() > 0;
        }
    }
}
