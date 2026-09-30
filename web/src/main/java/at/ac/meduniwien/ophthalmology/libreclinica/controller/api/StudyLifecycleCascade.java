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
 * Removal and restoration of a top-level study together with everything
 * under it: the SPA counterpart of {@code RemoveStudyServlet} and
 * {@code RestoreStudyServlet}.
 *
 * <p><b>What it changes.</b> The same tables, to the same statuses, as the
 * servlets. The study becomes removed (5). Its sites, role bindings,
 * subjects, subject-group classes and their subject maps, event
 * definitions and their event-definition CRFs, events, event CRFs, item
 * data and datasets become auto-removed (7). Restore reverses it. The
 * study, its sites, event CRFs and item data keep the status they had in
 * {@code old_status_id} and get it back, so a locked study comes back
 * locked. The other tables have no such column and come back available,
 * as they do in the servlets.
 *
 * <p><b>Where it deliberately differs from the servlets.</b>
 * <ul>
 *   <li><em>Status-only SQL instead of the DAOs' full-row updates.</em>
 *       {@code UserAccountDAO.updateStudyUserRole} keys on (study, user)
 *       and rewrites {@code role_name} on every row of the pair, so a user
 *       holding two roles on the study would end up with one of them
 *       twice. {@code ItemDataDAO.update} clears the provenance columns
 *       ({@code source_kind} and the source ids). Neither may happen to
 *       data that is only being hidden.</li>
 *   <li><em>One transaction</em>, supplied by the caller. The servlets
 *       commit row by row, so a failure leaves a study half removed.</li>
 *   <li><em>Only the live part of the tree is walked, and restore brings a
 *       row back only once its parents are live again.</em> A site,
 *       subject, definition, event, event CRF or item removed on its own
 *       before the study keeps that state through removal and restore.
 *       The servlets bring back the subjects and role bindings of a
 *       removed site, the events of a removed subject, the study subjects
 *       of a removed person and the study roles of a removed user
 *       account.</li>
 * </ul>
 *
 * <p>Every changed row gets {@code date_updated = now()} and
 * {@code update_id} = the acting user, so the status-change audit
 * triggers attribute the change to that user.
 */
final class StudyLifecycleCascade {

    private StudyLifecycleCascade() {}

    /** Neither removed (5) nor auto-removed (7). */
    private static final String LIVE = "status_id NOT IN (5, 7)";

    /**
     * The person behind a study subject is not removed. Removing a person
     * (the subject record) auto-removes their study subjects and what is
     * under them; those stay removed with the person.
     */
    private static final String PERSON_LIVE =
            "subject_id IN (SELECT subject_id FROM subject WHERE " + LIVE + ")";

    /**
     * The status a restored study or site returns to: the one recorded at
     * removal, or available when nothing usable was recorded.
     */
    private static final String RECORDED_STUDY_STATUS =
            "CASE WHEN old_status_id IS NULL OR old_status_id IN (0, 5, 7) THEN 1 ELSE old_status_id END";

    /** The status a restored event CRF or item returns to. */
    private static final String RECORDED_STATUS = "COALESCE(NULLIF(old_status_id, 0), 1)";

    /** What a removal takes with it, counted per kind of row. */
    record Impact(List<String> siteNames,
                  int roleBindings,
                  int subjects,
                  int groupClasses,
                  int eventDefinitions,
                  int events,
                  int eventCrfs,
                  int itemData,
                  int datasets) {}

    /**
     * Counts what {@link #remove} would change, without changing it. Each
     * count uses the same selection as the removal step it previews.
     */
    static Impact previewRemoval(Connection c, int studyId) throws SQLException {
        List<Integer> sites = select(c, "study", "study_id", "parent_study_id = ? AND " + LIVE, studyId);
        Array tree = tree(c, studyId, sites);
        int roles = select(c, "study_user_role", "study_id", "study_id = ANY(?) AND " + LIVE, tree).size();
        int subjects = select(c, "study_subject", "study_subject_id", "study_id = ANY(?) AND " + LIVE, tree).size();
        int groups = select(c, "study_group_class", "study_group_class_id",
                "study_id = ANY(?) AND " + LIVE, tree).size();
        List<Integer> defs = select(c, "study_event_definition", "study_event_definition_id",
                "study_id = ANY(?) AND " + LIVE, tree);
        List<Integer> events = select(c, "study_event", "study_event_id",
                "study_event_definition_id = ANY(?) AND " + LIVE, array(c, defs));
        List<Integer> eventCrfs = select(c, "event_crf", "event_crf_id",
                "study_event_id = ANY(?) AND status_id <> 5", array(c, events));
        int liveEventCrfs = select(c, "event_crf", "event_crf_id",
                "study_event_id = ANY(?) AND " + LIVE, array(c, events)).size();
        int items = select(c, "item_data", "item_data_id",
                "event_crf_id = ANY(?) AND " + LIVE, array(c, eventCrfs)).size();
        int datasets = select(c, "dataset", "dataset_id", "study_id = ANY(?) AND " + LIVE, tree).size();
        return new Impact(names(c, sites), roles, subjects, groups, defs.size(), events.size(),
                liveEventCrfs, items, datasets);
    }

    /**
     * Removes {@code studyId} and auto-removes what lives under it. The
     * caller owns the transaction on {@code c}.
     */
    static Impact remove(Connection c, int studyId, int userId) throws SQLException {
        update(c, userId, "study", "study_id", "old_status_id = status_id, status_id = 5",
                "study_id = ?", studyId);
        List<Integer> sites = update(c, userId, "study", "study_id",
                "old_status_id = status_id, status_id = 7", "parent_study_id = ? AND " + LIVE, studyId);
        Array tree = tree(c, studyId, sites);

        int roles = update(c, userId, "study_user_role", "study_id", "status_id = 7",
                "study_id = ANY(?) AND " + LIVE, tree).size();
        int subjects = update(c, userId, "study_subject", "study_subject_id", "status_id = 7",
                "study_id = ANY(?) AND " + LIVE, tree).size();
        List<Integer> groups = update(c, userId, "study_group_class", "study_group_class_id", "status_id = 7",
                "study_id = ANY(?) AND " + LIVE, tree);
        update(c, userId, "subject_group_map", "subject_group_map_id", "status_id = 7",
                "study_group_class_id = ANY(?) AND " + LIVE, array(c, groups));

        List<Integer> defs = update(c, userId, "study_event_definition", "study_event_definition_id",
                "status_id = 7", "study_id = ANY(?) AND " + LIVE, tree);
        Array defIds = array(c, defs);
        // Parent-level rows only, as the servlet's findAllByDefinition.
        update(c, userId, "event_definition_crf", "event_definition_crf_id", "status_id = 7",
                "study_event_definition_id = ANY(?) AND parent_id IS NULL AND " + LIVE, defIds);
        List<Integer> events = update(c, userId, "study_event", "study_event_id", "status_id = 7",
                "study_event_definition_id = ANY(?) AND " + LIVE, defIds);
        Array eventIds = array(c, events);

        // Event CRFs and items record the status they had. Rows already
        // auto-removed record that too, so that restore leaves them removed;
        // the servlet does the same by copying every non-removed status.
        // The already auto-removed rows go first: afterwards they could no
        // longer be told apart from the ones this removal takes.
        List<Integer> hiddenEventCrfs = recordAutoRemoved(c, "event_crf", "event_crf_id",
                "study_event_id = ANY(?)", eventIds);
        List<Integer> liveEventCrfs = update(c, userId, "event_crf", "event_crf_id",
                "old_status_id = status_id, status_id = 7", "study_event_id = ANY(?) AND " + LIVE, eventIds);
        List<Integer> eventCrfs = new ArrayList<>(liveEventCrfs);
        eventCrfs.addAll(hiddenEventCrfs);
        Array eventCrfIds = array(c, eventCrfs);
        recordAutoRemoved(c, "item_data", "item_data_id", "event_crf_id = ANY(?)", eventCrfIds);
        int items = update(c, userId, "item_data", "item_data_id",
                "old_status_id = status_id, status_id = 7", "event_crf_id = ANY(?) AND " + LIVE,
                eventCrfIds).size();

        int datasets = update(c, userId, "dataset", "dataset_id", "status_id = 7",
                "study_id = ANY(?) AND " + LIVE, tree).size();
        return new Impact(names(c, sites), roles, subjects, groups.size(), defs.size(), events.size(),
                liveEventCrfs.size(), items, datasets);
    }

    /**
     * Restores {@code studyId} and what its removal auto-removed. The
     * caller owns the transaction on {@code c}.
     */
    static Impact restore(Connection c, int studyId, int userId) throws SQLException {
        update(c, userId, "study", "study_id", "status_id = " + RECORDED_STUDY_STATUS,
                "study_id = ?", studyId);
        List<Integer> sites = update(c, userId, "study", "study_id", "status_id = " + RECORDED_STUDY_STATUS,
                "parent_study_id = ? AND status_id = 7", studyId);
        Array tree = tree(c, studyId, sites);

        // A removed account keeps its auto-removed roles until the account is restored.
        int roles = update(c, userId, "study_user_role", "study_id", "status_id = 1",
                "study_id = ANY(?) AND status_id = 7 "
                        + "AND user_name IN (SELECT user_name FROM user_account WHERE " + LIVE + ")",
                tree).size();
        int subjects = update(c, userId, "study_subject", "study_subject_id", "status_id = 1",
                "study_id = ANY(?) AND status_id = 7 AND " + PERSON_LIVE, tree).size();
        List<Integer> groups = update(c, userId, "study_group_class", "study_group_class_id", "status_id = 1",
                "study_id = ANY(?) AND status_id = 7", tree);
        update(c, userId, "subject_group_map", "subject_group_map_id", "status_id = 1",
                "study_group_class_id = ANY(?) AND status_id = 7 "
                        + "AND study_subject_id IN (SELECT study_subject_id FROM study_subject WHERE " + LIVE + ")",
                array(c, groups));

        List<Integer> defs = update(c, userId, "study_event_definition", "study_event_definition_id",
                "status_id = 1", "study_id = ANY(?) AND status_id = 7", tree);
        Array defIds = array(c, defs);
        // A CRF removed from the library keeps its event-definition CRFs removed.
        update(c, userId, "event_definition_crf", "event_definition_crf_id", "status_id = 1",
                "study_event_definition_id = ANY(?) AND parent_id IS NULL AND status_id = 7 "
                        + "AND crf_id IN (SELECT crf_id FROM crf WHERE " + LIVE + ")",
                defIds);
        List<Integer> events = update(c, userId, "study_event", "study_event_id", "status_id = 1",
                "study_event_definition_id = ANY(?) AND status_id = 7 "
                        + "AND study_subject_id IN (SELECT study_subject_id FROM study_subject WHERE " + LIVE + ")",
                defIds);

        // Rows whose recorded status is itself removed stay as they are.
        List<Integer> eventCrfs = update(c, userId, "event_crf", "event_crf_id",
                "status_id = " + RECORDED_STATUS,
                "study_event_id = ANY(?) AND status_id = 7 "
                        + "AND (old_status_id IS NULL OR old_status_id NOT IN (5, 7))",
                array(c, events));
        int items = update(c, userId, "item_data", "item_data_id", "status_id = " + RECORDED_STATUS,
                "event_crf_id = ANY(?) AND status_id = 7 "
                        + "AND (old_status_id IS NULL OR old_status_id NOT IN (5, 7))",
                array(c, eventCrfs)).size();

        int datasets = update(c, userId, "dataset", "dataset_id", "status_id = 1",
                "study_id = ANY(?) AND status_id = 7", tree).size();
        return new Impact(names(c, sites), roles, subjects, groups.size(), defs.size(), events.size(),
                eventCrfs.size(), items, datasets);
    }

    /* ------------------------------------------------------------------ */

    /** Changes the matching rows and returns their ids. */
    private static List<Integer> update(Connection c, int userId, String table, String idColumn,
                                        String set, String where, Object param) throws SQLException {
        return ids(c, "UPDATE " + table + " SET " + set + ", date_updated = now(), update_id = ? WHERE "
                + where + " RETURNING " + idColumn, userId, param);
    }

    /**
     * Records {@code old_status_id = 7} on rows that are already
     * auto-removed. Bookkeeping only: the status does not change, so the
     * row's update stamp is left alone.
     */
    private static List<Integer> recordAutoRemoved(Connection c, String table, String idColumn,
                                                   String where, Object param) throws SQLException {
        return ids(c, "UPDATE " + table + " SET old_status_id = 7 WHERE " + where
                + " AND status_id = 7 RETURNING " + idColumn, null, param);
    }

    /** Reads the ids of the matching rows. */
    private static List<Integer> select(Connection c, String table, String idColumn,
                                        String where, Object param) throws SQLException {
        return ids(c, "SELECT " + idColumn + " FROM " + table + " WHERE " + where, null, param);
    }

    private static List<Integer> ids(Connection c, String sql, Integer userId, Object param)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            int i = 1;
            if (userId != null) ps.setInt(i++, userId);
            ps.setObject(i, param);
            List<Integer> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getInt(1));
            }
            return out;
        }
    }

    private static Array tree(Connection c, int studyId, List<Integer> sites) throws SQLException {
        List<Integer> all = new ArrayList<>(sites);
        all.add(0, studyId);
        return array(c, all);
    }

    private static Array array(Connection c, List<Integer> ids) throws SQLException {
        return c.createArrayOf("integer", ids.toArray());
    }

    private static List<String> names(Connection c, List<Integer> studyIds) throws SQLException {
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT name FROM study WHERE study_id = ANY(?) ORDER BY name")) {
            ps.setArray(1, array(c, studyIds));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getString(1));
            }
        }
        return out;
    }
}
