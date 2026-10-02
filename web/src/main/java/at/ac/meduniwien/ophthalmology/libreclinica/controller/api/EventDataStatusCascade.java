/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * The event CRFs and values under visits that are auto-removed or restored
 * with their subject or their event definition, and the record that lets a
 * restore give back what its removal took.
 *
 * <p>A removal records, for each event CRF it takes, what took it and the
 * status it had ({@code event_crf_cascade_removal}); each value records its
 * status in {@code old_status_id}. A restore puts the recorded status back,
 * so a locked CRF comes back locked and a signed one signed, as the legacy
 * site removal and restore do. A CRF that was auto-removed already is left
 * to whatever took it: the removal records that too, and the restore leaves
 * it removed.
 *
 * <p>A record holds only while the CRF is as the removal left it: its
 * {@code removed_at} is the CRF's {@code date_updated} at the removal, and
 * every later write through {@code EventCRFDAO.update}, which the legacy
 * removals and restores use, sets {@code date_updated} again. A CRF those
 * paths auto-removed, or changed since, has no record that holds, and the
 * restore makes it available, as legacy {@code RestoreStudySubjectServlet}
 * and {@code RestoreEventDefinitionServlet} do. {@code old_status_id} alone
 * is not trusted: those paths write it back unchanged, so it can describe a
 * removal long undone.
 *
 * <p>Status-only SQL on the caller's connection, so the caller's
 * transaction covers the cascade. {@code ItemDataDAO.update} would clear a
 * value's provenance.
 */
final class EventDataStatusCascade {

    private EventDataStatusCascade() {}

    /** What removed an event CRF, as {@code event_crf_cascade_removal.removed_with} names it. */
    enum Remover {
        STUDY_SUBJECT("study_subject"),
        EVENT_DEFINITION("study_event_definition"),
        EVENT_CRF("event_crf"),
        /** A removal before the one that recorded it, which left no record of its own. */
        EARLIER("earlier");

        final String key;

        Remover(String key) {
            this.key = key;
        }
    }

    /** Neither removed (5) nor auto-removed (7). */
    static final String LIVE = "status_id NOT IN (5, 7)";

    /** The status a restored value returns to: the recorded one, else available. */
    private static final String RECORDED_STATUS = "COALESCE(NULLIF(old_status_id, 0), 1)";

    /** A value taken by a removal: auto-removed, and the recorded status is not itself a removal. */
    private static final String TAKEN = "status_id = 7 AND (old_status_id IS NULL OR old_status_id NOT IN (5, 7))";

    /** {@code r} is a record on event CRF {@code ec} that still holds. */
    private static final String RECORD_HOLDS = "r.event_crf_id = ec.event_crf_id AND r.removed_at = ec.date_updated";

    /** Records the CRFs in {@code taken} (event_crf_id, old_status_id, date_updated) as taken by a remover. */
    private static final String RECORD_TAKEN = "INSERT INTO event_crf_cascade_removal "
            + "(event_crf_id, removed_with, removed_with_id, prior_status_id, removed_at) "
            + "SELECT event_crf_id, ?, ?, old_status_id, date_updated FROM taken "
            + "ON CONFLICT (event_crf_id) DO UPDATE SET removed_with = EXCLUDED.removed_with, "
            + "removed_with_id = EXCLUDED.removed_with_id, prior_status_id = EXCLUDED.prior_status_id, "
            + "removed_at = EXCLUDED.removed_at RETURNING event_crf_id";

    /** The event CRFs and values a cascade changed. */
    record Counts(int eventCrfs, int values) {}

    /**
     * Auto-removes the event CRFs under {@code visits}, and their values,
     * each recording the status it had. The CRFs and values auto-removed
     * already record that first: afterwards they could not be told apart
     * from the ones this removal takes.
     */
    static Counts autoRemove(Connection c, List<Integer> visits, Remover by, int byId, int userId)
            throws SQLException {
        Array visitIds = array(c, visits);
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO event_crf_cascade_removal "
                + "(event_crf_id, removed_with, removed_with_id, prior_status_id, removed_at) "
                + "SELECT ec.event_crf_id, ?, 0, 7, ec.date_updated FROM event_crf ec "
                + "WHERE ec.study_event_id = ANY(?) AND ec.status_id = 7 AND ec.date_updated IS NOT NULL "
                + "AND NOT EXISTS (SELECT 1 FROM event_crf_cascade_removal r WHERE " + RECORD_HOLDS + ") "
                + "ON CONFLICT (event_crf_id) DO UPDATE SET removed_with = EXCLUDED.removed_with, "
                + "removed_with_id = 0, prior_status_id = 7, removed_at = EXCLUDED.removed_at")) {
            ps.setString(1, Remover.EARLIER.key);
            ps.setArray(2, visitIds);
            ps.executeUpdate();
        }
        List<Integer> eventCrfs = take(c, "UPDATE event_crf SET old_status_id = status_id, status_id = 7, "
                + "date_updated = now(), update_id = ? WHERE study_event_id = ANY(?) AND " + LIVE,
                userId, visitIds, by, byId);
        Array crfIds = array(c, eventCrfs);
        update(c, "UPDATE item_data SET old_status_id = 7 WHERE event_crf_id = ANY(?) AND status_id = 7",
                null, crfIds);
        int values = update(c, "UPDATE item_data SET old_status_id = status_id, status_id = 7, "
                + "date_updated = now(), update_id = ? WHERE event_crf_id = ANY(?) AND " + LIVE,
                userId, crfIds);
        return new Counts(eventCrfs.size(), values);
    }

    /**
     * Brings back the event CRFs under {@code visits} that the removal by
     * {@code by} {@code byId} took, and their values, as they were; and the
     * auto-removed ones for which no removal's record holds, as available.
     */
    static Counts restore(Connection c, List<Integer> visits, Remover by, int byId, int userId)
            throws SQLException {
        Array visitIds = array(c, visits);
        List<Integer> recorded;
        try (PreparedStatement ps = c.prepareStatement("UPDATE event_crf ec SET status_id = r.prior_status_id, "
                + "date_updated = now(), update_id = ? FROM event_crf_cascade_removal r WHERE " + RECORD_HOLDS
                + " AND r.removed_with = ? AND r.removed_with_id = ? AND ec.status_id = 7 "
                + "AND ec.study_event_id = ANY(?) RETURNING ec.event_crf_id")) {
            ps.setInt(1, userId);
            ps.setString(2, by.key);
            ps.setInt(3, byId);
            ps.setArray(4, visitIds);
            recorded = collect(ps);
        }
        List<Integer> unrecorded = ids(c, "UPDATE event_crf ec SET status_id = 1, date_updated = now(), "
                + "update_id = ? WHERE ec.study_event_id = ANY(?) AND ec.status_id = 7 AND NOT EXISTS "
                + "(SELECT 1 FROM event_crf_cascade_removal r WHERE " + RECORD_HOLDS + ") "
                + "RETURNING ec.event_crf_id", userId, visitIds);
        int values = update(c, "UPDATE item_data SET status_id = " + RECORDED_STATUS
                + ", date_updated = now(), update_id = ? WHERE event_crf_id = ANY(?) AND " + TAKEN,
                userId, array(c, recorded));
        values += update(c, "UPDATE item_data SET status_id = 1, date_updated = now(), update_id = ? "
                + "WHERE event_crf_id = ANY(?) AND status_id = 7", userId, array(c, unrecorded));
        List<Integer> restored = new ArrayList<>(recorded);
        restored.addAll(unrecorded);
        forget(c, restored);
        return new Counts(restored.size(), values);
    }

    /**
     * Removes one event CRF on its own, unless it is removed, locked or
     * signed by now, recording the status it had.
     *
     * @return whether the CRF was removed
     */
    static boolean removeEventCrf(Connection c, int eventCrfId, int userId) throws SQLException {
        return take(c, "UPDATE event_crf SET old_status_id = status_id, status_id = 5, update_id = ?, "
                + "date_updated = now() WHERE event_crf_id = ? AND status_id NOT IN (5, 6, 7, 8)",
                userId, eventCrfId, Remover.EVENT_CRF, eventCrfId).size() == 1;
    }

    /**
     * The status the removal that took this removed CRF recorded, while its
     * record holds; otherwise {@code null}, and the CRF comes back available.
     */
    static Integer recordedStatus(Connection c, int eventCrfId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT r.prior_status_id FROM event_crf ec "
                + "JOIN event_crf_cascade_removal r ON " + RECORD_HOLDS
                + " WHERE ec.event_crf_id = ? AND ec.status_id IN (5, 7) AND r.removed_with <> ?")) {
            ps.setInt(1, eventCrfId);
            ps.setString(2, Remover.EARLIER.key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : null;
            }
        }
    }

    /** Drops the records of event CRFs that are restored. */
    static void forget(Connection c, List<Integer> eventCrfs) throws SQLException {
        update(c, "DELETE FROM event_crf_cascade_removal WHERE event_crf_id = ANY(?)", null, array(c, eventCrfs));
    }

    /** Runs an event-CRF {@code UPDATE} bound to the updater and one parameter, recording what it takes. */
    private static List<Integer> take(Connection c, String update, int userId, Object param,
                                      Remover by, int byId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("WITH taken AS (" + update
                + " RETURNING event_crf_id, old_status_id, date_updated) " + RECORD_TAKEN)) {
            ps.setInt(1, userId);
            ps.setObject(2, param);
            ps.setString(3, by.key);
            ps.setInt(4, byId);
            return collect(ps);
        }
    }

    /** Runs an {@code UPDATE ... RETURNING} bound to the updater and one parameter; returns the ids. */
    static List<Integer> ids(Connection c, String sql, int userId, Object param) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, userId);
            ps.setObject(2, param);
            return collect(ps);
        }
    }

    private static List<Integer> collect(PreparedStatement ps) throws SQLException {
        List<Integer> out = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(rs.getInt(1));
        }
        return out;
    }

    /** Runs an {@code UPDATE} bound to the updater, when there is one, and to an id array. */
    private static int update(Connection c, String sql, Integer userId, Array ids) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            int i = 1;
            if (userId != null) ps.setInt(i++, userId);
            ps.setArray(i, ids);
            return ps.executeUpdate();
        }
    }

    private static Array array(Connection c, List<Integer> ids) throws SQLException {
        return c.createArrayOf("integer", ids.toArray());
    }
}
