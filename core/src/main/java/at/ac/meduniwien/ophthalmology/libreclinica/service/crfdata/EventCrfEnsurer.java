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

/**
 * P3.0 — get the CRF instance for a visit, creating it if data is about to
 * arrive for it.
 *
 * <p>Two places wrote the same fourteen-column {@code INSERT INTO event_crf}
 * with the same literal defaults: the BCVA portal and the retinal flags
 * endpoint. Identical statements, divergent surroundings — they chose the
 * owner differently, and they disagreed about removed instances.
 *
 * <p><strong>There is only ever one instance.</strong> {@code event_crf}
 * carries a unique constraint on {@code (study_event_id, crf_version_id,
 * study_subject_id)} — heritage {@code 2017-06-05-OC-7394} — with no status
 * predicate. That single fact decides the disagreement, and shows both copies
 * were wrong:
 *
 * <ul>
 *   <li>The retinal copy took whatever row it found. When the form had been
 *       removed it wrote into it anyway, so automatic values resurrected a
 *       form somebody had deleted.</li>
 *   <li>The portal copy skipped removed rows and fell through to the insert —
 *       which the constraint rejects. A removed BCVA form therefore turned the
 *       portal into a 500 rather than a message anyone could act on.</li>
 * </ul>
 *
 * <p>So this reports rather than decides: the caller is handed the one
 * instance and its status, and refuses the write itself. Reviving a removal
 * silently is the one outcome nothing here will produce.
 *
 * <p>The owner stays a parameter. A staff action is owned by the person who
 * took it; an unauthenticated portal has nobody to name, and passes the
 * account the institution set aside for that.
 *
 * <p>The literal defaults are the ones the application's own data-entry path
 * writes: status 1 (available), completion 1 (initial data entry), no
 * interviewer, no signature, not source-data verified.
 */
public final class EventCrfEnsurer {

    /** An operator removed this form. */
    public static final int STATUS_DELETED = 5;

    /** Removing the visit above it cascaded to this form. Same meaning. */
    public static final int STATUS_AUTO_DELETED = 7;

    private EventCrfEnsurer() {}

    /**
     * The one CRF instance for a visit and version.
     *
     * @param created true when this call inserted it, false when it was found
     */
    public record Instance(int eventCrfId, int statusId, boolean created) {

        /**
         * True when the form has been removed. Callers must refuse to write
         * rather than treat this as an ordinary instance — the constraint
         * leaves no room for a replacement, so a write here would undo the
         * removal instead of living beside it.
         */
        public boolean removed() {
            return statusId == STATUS_DELETED || statusId == STATUS_AUTO_DELETED;
        }
    }

    /**
     * @param ownerId the account to record as having created the row; the FK
     *                into {@code user_account} means a sentinel like 0 fails
     * @return the existing or newly created instance — check {@link
     *         Instance#removed()} before writing into it
     */
    public static Instance ensure(Connection c, int studyEventId, int crfVersionId, int ownerId)
            throws SQLException {
        Instance existing = find(c, studyEventId, crfVersionId);
        if (existing != null) return existing;

        int studySubjectId = studySubjectOf(c, studyEventId);
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO event_crf ("
                        + "  study_event_id, crf_version_id, "
                        + "  status_id, completion_status_id, "
                        + "  owner_id, date_created, "
                        + "  study_subject_id, "
                        + "  interviewer_name, date_interviewed, "
                        + "  electronic_signature_status, sdv_status, "
                        + "  old_status_id, sdv_update_id) "
                        + "VALUES (?, ?, 1, 1, ?, now(), ?, '', NULL, false, false, 1, 0) "
                        + "RETURNING event_crf_id")) {
            ps.setInt(1, studyEventId);
            ps.setInt(2, crfVersionId);
            ps.setInt(3, ownerId);
            ps.setInt(4, studySubjectId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new SQLException("INSERT event_crf returned no id");
                return new Instance(rs.getInt(1), 1, true);
            }
        }
    }

    /**
     * The CRF instance for this visit and version, or null when there is none.
     *
     * <p>Removed instances are returned like any other, carrying their status.
     * They are not skipped: the unique constraint means a skip cannot lead to a
     * replacement, only to a failed insert.
     */
    public static Instance find(Connection c, int studyEventId, int crfVersionId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT event_crf_id, COALESCE(status_id, 0) FROM event_crf "
                        + " WHERE study_event_id = ? AND crf_version_id = ? "
                        + " ORDER BY event_crf_id LIMIT 1")) {
            ps.setInt(1, studyEventId);
            ps.setInt(2, crfVersionId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new Instance(rs.getInt(1), rs.getInt(2), false) : null;
            }
        }
    }

    private static int studySubjectOf(Connection c, int studyEventId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT study_subject_id FROM study_event WHERE study_event_id = ?")) {
            ps.setInt(1, studyEventId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new SQLException("study_event " + studyEventId + " not found");
                return rs.getInt(1);
            }
        }
    }

    /**
     * The account an unauthenticated writer records as owner: the lowest-id
     * active user, which is the institutional root in seeded data.
     *
     * <p>A portal has no logged-in user, and the foreign key rules out a
     * sentinel. The audit row written alongside carries whatever the operator
     * typed as their name, so the human attribution survives; this link is
     * structural. Where a locked service account exists it is the better
     * answer, and callers that have one should pass it instead.
     */
    public static int fallbackOwnerId(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT user_id FROM user_account WHERE status_id = 1 ORDER BY user_id LIMIT 1");
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) throw new SQLException("no active user_account to own the event_crf row");
            return rs.getInt(1);
        }
    }
}
