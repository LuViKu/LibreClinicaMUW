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
 * with their subject or their event definition.
 *
 * <p>A removal records each row's status in {@code old_status_id} before it
 * marks the row auto-removed, and a restore puts the recorded status back,
 * so a locked CRF comes back locked and a signed one signed, as the legacy
 * site removal and restore do. A row that was auto-removed already records
 * that, and the restore leaves it removed: what took it brings it back. A
 * row removed on its own is not touched.
 *
 * <p>Status-only SQL on the caller's connection, so the caller's
 * transaction covers the cascade. {@code ItemDataDAO.update} would clear a
 * value's provenance.
 */
final class EventDataStatusCascade {

    private EventDataStatusCascade() {}

    /** Neither removed (5) nor auto-removed (7). */
    static final String LIVE = "status_id NOT IN (5, 7)";

    /** The status a restored row returns to: the recorded one, else available. */
    private static final String RECORDED_STATUS = "COALESCE(NULLIF(old_status_id, 0), 1)";

    /** Taken by a removal: auto-removed, and the recorded status is not itself a removal. */
    private static final String TAKEN = "status_id = 7 AND (old_status_id IS NULL OR old_status_id NOT IN (5, 7))";

    /** The event CRFs and values a cascade changed. */
    record Counts(int eventCrfs, int values) {}

    /**
     * Auto-removes the event CRFs and values under {@code visits}, each
     * recording the status it had. The rows already auto-removed record that
     * first: afterwards they could not be told apart from the ones this
     * removal takes.
     */
    static Counts autoRemove(Connection c, List<Integer> visits, int userId) throws SQLException {
        Array visitIds = array(c, visits);
        String crfsOfVisits = "event_crf_id IN (SELECT event_crf_id FROM event_crf "
                + "WHERE study_event_id = ANY(?) AND status_id <> 5)";
        update(c, "UPDATE event_crf SET old_status_id = 7 WHERE study_event_id = ANY(?) AND status_id = 7",
                null, visitIds);
        update(c, "UPDATE item_data SET old_status_id = 7 WHERE " + crfsOfVisits + " AND status_id = 7",
                null, visitIds);
        int eventCrfs = update(c, "UPDATE event_crf SET old_status_id = status_id, status_id = 7, "
                + "date_updated = now(), update_id = ? WHERE study_event_id = ANY(?) AND " + LIVE,
                userId, visitIds);
        int values = update(c, "UPDATE item_data SET old_status_id = status_id, status_id = 7, "
                + "date_updated = now(), update_id = ? WHERE " + crfsOfVisits + " AND " + LIVE,
                userId, visitIds);
        return new Counts(eventCrfs, values);
    }

    /** Brings back the event CRFs and values under {@code visits} that a removal took. */
    static Counts restore(Connection c, List<Integer> visits, int userId) throws SQLException {
        List<Integer> eventCrfs = ids(c, "UPDATE event_crf SET status_id = " + RECORDED_STATUS
                + ", date_updated = now(), update_id = ? WHERE study_event_id = ANY(?) AND " + TAKEN
                + " RETURNING event_crf_id", userId, array(c, visits));
        int values = update(c, "UPDATE item_data SET status_id = " + RECORDED_STATUS
                + ", date_updated = now(), update_id = ? WHERE event_crf_id = ANY(?) AND " + TAKEN,
                userId, array(c, eventCrfs));
        return new Counts(eventCrfs.size(), values);
    }

    /** Runs an {@code UPDATE ... RETURNING} bound to the updater and one parameter; returns the ids. */
    static List<Integer> ids(Connection c, String sql, int userId, Object param) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, userId);
            ps.setObject(2, param);
            List<Integer> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getInt(1));
            }
            return out;
        }
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
