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

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Status;

/**
 * The rows a CRF or a CRF version takes with it when it is removed, and brings
 * back when it is restored. Legacy parity: {@code RemoveCRFServlet},
 * {@code RestoreCRFServlet}, {@code RemoveCRFVersionServlet} and
 * {@code RestoreCRFVersionServlet}.
 *
 * <p>Removal marks the dependent rows {@link Status#AUTO_DELETED}; restore
 * brings back only rows in that state, so a row removed on its own
 * ({@link Status#DELETED}) stays removed, as in the legacy servlets. Three
 * things differ from the legacy loops, each so that removal and restore are
 * inverses:
 * <ul>
 *   <li><b>Prior status is kept.</b> An event CRF or item value that is
 *       auto-removed records its status in {@code old_status_id} (the
 *       convention {@code UpdateEventDefinitionServlet} and
 *       {@code DeleteEventCRFServlet} already use), and restore puts it back.
 *       The legacy restore set every row to available, so a signed or locked
 *       event CRF came back unsigned and unlocked. A version's prior status is
 *       in its lifecycle audit row, so a locked version comes back locked.</li>
 *   <li><b>Rows removed by something else stay removed.</b> Restore leaves an
 *       auto-removed event CRF alone while its subject, its event or its
 *       version is removed, and an event-definition CRF while its event
 *       definition or its study is. {@code AUTO_DELETED} does not say which
 *       removal set it; such a row was taken by the other removal, and that
 *       row's own restore brings it back. The legacy restore resurrected them
 *       under a removed parent.</li>
 *   <li><b>Rows that were already removed are not touched</b> by a removal,
 *       so the prior status they recorded survives.</li>
 * </ul>
 *
 * <p>Each method runs on the caller's connection, inside the caller's
 * transaction, and changes rows with set-based statements. They leave the
 * provenance columns of {@code item_data} alone, which the per-row
 * {@code ItemDataDAO.update} of the legacy servlets clears: a status change
 * is not a correction of the value.
 */
final class CrfLifecycleCascade {

    /** How many dependent rows of each kind changed. */
    record Counts(int versions, int sections, int eventDefinitionCrfs, int eventCrfs, int itemData) {}

    /**
     * A version whose status changed: on removal with the status it had, on
     * restore with the status it came back at.
     */
    record VersionChange(int versionId, String oid, int status) {}

    /** What a CRF removal or restore changed. */
    record Result(List<VersionChange> versions, Counts counts) {}

    /** One {@code event_definition_crf} whose default version was moved. */
    record DefaultMove(int eventDefinitionCrfId, int fromVersionId, int toVersionId) {}

    private static final int AVAILABLE = Status.AVAILABLE.getId();
    private static final int DELETED = Status.DELETED.getId();
    private static final int AUTO_DELETED = Status.AUTO_DELETED.getId();
    private static final int LOCKED = Status.LOCKED.getId();

    /** Restore target for a row: its recorded prior status, or available when that is missing or a removal. */
    private static String priorStatus(String column) {
        return "CASE WHEN " + column + " IS NULL OR " + column + " IN (0, " + DELETED + ", " + AUTO_DELETED + ")"
                + " THEN " + AVAILABLE + " ELSE " + column + " END";
    }

    private CrfLifecycleCascade() {
    }

    /* ------------------------------------------------------------------ */
    /* CRF                                                                */
    /* ------------------------------------------------------------------ */

    /**
     * Removes what {@code RemoveCRFServlet} removes with a CRF: every version
     * that is not removed (and its sections), every event-definition CRF, and
     * every event CRF on any of its versions (and its item data). The CRF row
     * itself is the caller's.
     *
     * @return the versions that changed, each with its prior status, and the counts
     */
    static Result removeCrf(Connection c, int crfId, int userId) throws SQLException {
        List<VersionChange> versions = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT crf_version_id, oc_oid, status_id FROM crf_version"
                        + " WHERE crf_id = ? AND status_id NOT IN (?, ?) ORDER BY crf_version_id")) {
            ps.setInt(1, crfId);
            ps.setInt(2, DELETED);
            ps.setInt(3, AUTO_DELETED);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) versions.add(new VersionChange(rs.getInt(1), rs.getString(2), rs.getInt(3)));
            }
        }
        List<Integer> versionIds = new ArrayList<>(versions.size());
        for (VersionChange v : versions) versionIds.add(v.versionId());
        if (!versionIds.isEmpty()) {
            update(c, "UPDATE crf_version SET status_id = ?, update_id = ?, date_updated = now()"
                    + " WHERE crf_version_id = ANY (?)", AUTO_DELETED, userId, intArray(c, versionIds));
        }
        int sections = removeSections(c, versionIds, userId);
        int edcs = update(c,
                "UPDATE event_definition_crf SET status_id = ?, update_id = ?, date_updated = now()"
                        + " WHERE crf_id = ? AND status_id NOT IN (?, ?)",
                AUTO_DELETED, userId, crfId, DELETED, AUTO_DELETED);
        List<Integer> eventCrfs = removeEventCrfs(c,
                "ec.crf_version_id IN (SELECT crf_version_id FROM crf_version WHERE crf_id = ?)"
                        + " AND ec.status_id NOT IN (" + DELETED + ", " + AUTO_DELETED + ")",
                crfId, userId);
        int itemData = removeItemData(c, eventCrfs, userId);
        return new Result(versions, new Counts(versions.size(), sections, edcs, eventCrfs.size(), itemData));
    }

    /**
     * Brings back what {@link #removeCrf} took: auto-removed versions (at the
     * status their lifecycle audit row recorded) and their sections,
     * auto-removed event-definition CRFs whose event definition and study are
     * not removed, and auto-removed event CRFs whose subject, event and
     * version are not removed, with their item data.
     *
     * @return the versions that changed, each with the status it came back at, and the counts
     */
    static Result restoreCrf(Connection c, int crfId, int userId) throws SQLException {
        List<Integer> autoRemoved = new ArrayList<>();
        List<String> oids = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT crf_version_id, oc_oid FROM crf_version"
                        + " WHERE crf_id = ? AND status_id = ? ORDER BY crf_version_id")) {
            ps.setInt(1, crfId);
            ps.setInt(2, AUTO_DELETED);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    autoRemoved.add(rs.getInt(1));
                    oids.add(rs.getString(2));
                }
            }
        }
        List<VersionChange> versions = new ArrayList<>(autoRemoved.size());
        for (int i = 0; i < autoRemoved.size(); i++) {
            int versionId = autoRemoved.get(i);
            int restoredTo = statusBeforeAutoRemoval(c, versionId);
            update(c, "UPDATE crf_version SET status_id = ?, update_id = ?, date_updated = now()"
                    + " WHERE crf_version_id = ?", restoredTo, userId, versionId);
            versions.add(new VersionChange(versionId, oids.get(i), restoredTo));
        }
        int sections = restoreSections(c, autoRemoved, userId);
        int edcs = update(c,
                "UPDATE event_definition_crf edc SET status_id = ?, update_id = ?, date_updated = now()"
                        + " WHERE edc.crf_id = ? AND edc.status_id = ?"
                        + "   AND EXISTS (SELECT 1 FROM study_event_definition sed"
                        + "                WHERE sed.study_event_definition_id = edc.study_event_definition_id"
                        + "                  AND sed.status_id NOT IN (?, ?))"
                        + "   AND EXISTS (SELECT 1 FROM study s"
                        + "                WHERE s.study_id = edc.study_id AND s.status_id NOT IN (?, ?))",
                AVAILABLE, userId, crfId, AUTO_DELETED, DELETED, AUTO_DELETED, DELETED, AUTO_DELETED);
        List<Integer> eventCrfs = restoreEventCrfs(c, "cv.crf_id = ?", crfId, userId);
        int itemData = restoreItemData(c, eventCrfs, userId);
        return new Result(versions, new Counts(versions.size(), sections, edcs, eventCrfs.size(), itemData));
    }

    /* ------------------------------------------------------------------ */
    /* CRF version                                                        */
    /* ------------------------------------------------------------------ */

    /**
     * Removes what {@code RemoveCRFVersionServlet} removes with a version: its
     * sections, and its event CRFs that are available, completed, pending or
     * locked (the legacy {@code findUndeletedWithStudySubjectsByCRFVersion}
     * set, statuses 1, 2, 4 and 6: a signed event CRF is not removed), with
     * their item data. The version row itself is the caller's.
     */
    static Counts removeVersion(Connection c, int versionId, int userId) throws SQLException {
        int sections = removeSections(c, List.of(versionId), userId);
        List<Integer> eventCrfs = removeEventCrfs(c,
                "ec.crf_version_id = ? AND ec.status_id IN (1, 2, 4, 6)", versionId, userId);
        int itemData = removeItemData(c, eventCrfs, userId);
        return new Counts(0, sections, 0, eventCrfs.size(), itemData);
    }

    /**
     * Brings back what {@link #removeVersion} took: the version's auto-removed
     * sections, and its auto-removed event CRFs whose subject and event are not
     * removed, with their item data.
     */
    static Counts restoreVersion(Connection c, int versionId, int userId) throws SQLException {
        int sections = restoreSections(c, List.of(versionId), userId);
        List<Integer> eventCrfs = restoreEventCrfs(c, "ec.crf_version_id = ?", versionId, userId);
        int itemData = restoreItemData(c, eventCrfs, userId);
        return new Counts(0, sections, 0, eventCrfs.size(), itemData);
    }

    /**
     * Points every event-definition CRF that defaults to {@code versionId} at
     * the newest available version of the same CRF, among the versions the
     * row's {@code selected_version_ids} allows when it names any. A row with
     * no such version keeps its default. Called when a version stops taking
     * new data (removed or locked), so new event CRFs are not created on it.
     *
     * <p>The legacy {@code RemoveCRFVersionServlet.updateEventDef} re-pointed
     * every event-definition CRF of the CRF at the newest version whatever its
     * status: rows that defaulted to another version as well, and, when it was
     * the newest, at the very version being removed. This moves only the rows
     * that default to the version, and only to an available one.
     */
    static List<DefaultMove> repointDefaults(Connection c, int crfId, int versionId, int userId)
            throws SQLException {
        List<Integer> candidates = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT crf_version_id FROM crf_version"
                        + " WHERE crf_id = ? AND status_id = ? AND crf_version_id <> ?"
                        + " ORDER BY date_created DESC, crf_version_id DESC")) {
            ps.setInt(1, crfId);
            ps.setInt(2, AVAILABLE);
            ps.setInt(3, versionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) candidates.add(rs.getInt(1));
            }
        }
        List<DefaultMove> moves = new ArrayList<>();
        if (candidates.isEmpty()) return moves;
        List<Integer> edcIds = new ArrayList<>();
        List<String> selections = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT event_definition_crf_id, selected_version_ids FROM event_definition_crf"
                        + " WHERE crf_id = ? AND default_version_id = ? ORDER BY event_definition_crf_id")) {
            ps.setInt(1, crfId);
            ps.setInt(2, versionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    edcIds.add(rs.getInt(1));
                    selections.add(rs.getString(2));
                }
            }
        }
        for (int i = 0; i < edcIds.size(); i++) {
            Integer target = firstAllowed(candidates, selections.get(i));
            if (target == null) continue;
            update(c, "UPDATE event_definition_crf SET default_version_id = ?, update_id = ?, date_updated = now()"
                    + " WHERE event_definition_crf_id = ?", target, userId, edcIds.get(i));
            moves.add(new DefaultMove(edcIds.get(i), versionId, target));
        }
        return moves;
    }

    /** The first candidate a comma-separated version selection allows; the first of all when it names none. */
    static Integer firstAllowed(List<Integer> candidates, String selectedVersionIds) {
        if (selectedVersionIds == null || selectedVersionIds.isBlank()) {
            return candidates.isEmpty() ? null : candidates.get(0);
        }
        List<String> allowed = new ArrayList<>();
        for (String s : selectedVersionIds.split(",")) allowed.add(s.trim());
        for (Integer candidate : candidates) {
            if (allowed.contains(String.valueOf(candidate))) return candidate;
        }
        return null;
    }

    /* ------------------------------------------------------------------ */
    /* Steps                                                              */
    /* ------------------------------------------------------------------ */

    private static int removeSections(Connection c, List<Integer> versionIds, int userId) throws SQLException {
        if (versionIds.isEmpty()) return 0;
        return update(c,
                "UPDATE section SET status_id = ?, update_id = ?, date_updated = now()"
                        + " WHERE crf_version_id = ANY (?) AND status_id NOT IN (?, ?)",
                AUTO_DELETED, userId, intArray(c, versionIds), DELETED, AUTO_DELETED);
    }

    private static int restoreSections(Connection c, List<Integer> versionIds, int userId) throws SQLException {
        if (versionIds.isEmpty()) return 0;
        return update(c,
                "UPDATE section SET status_id = ?, update_id = ?, date_updated = now()"
                        + " WHERE crf_version_id = ANY (?) AND status_id = ?",
                AVAILABLE, userId, intArray(c, versionIds), AUTO_DELETED);
    }

    /** Auto-removes the event CRFs {@code where} selects (over {@code ec}), recording each one's prior status. */
    private static List<Integer> removeEventCrfs(Connection c, String where, int param, int userId)
            throws SQLException {
        List<Integer> ids = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE event_crf ec SET old_status_id = ec.status_id, status_id = ?,"
                        + "       update_id = ?, date_updated = now()"
                        + " WHERE " + where
                        + " RETURNING ec.event_crf_id")) {
            ps.setInt(1, AUTO_DELETED);
            ps.setInt(2, userId);
            ps.setInt(3, param);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) ids.add(rs.getInt(1));
            }
        }
        return ids;
    }

    /**
     * Restores, at their recorded prior status, the auto-removed event CRFs on
     * the versions {@code versionWhere} selects (over {@code cv}) whose
     * subject, event and version are not removed.
     */
    private static List<Integer> restoreEventCrfs(Connection c, String versionWhere, int param, int userId)
            throws SQLException {
        List<Integer> ids = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE event_crf ec SET status_id = " + priorStatus("ec.old_status_id") + ","
                        + "       update_id = ?, date_updated = now()"
                        + "  FROM crf_version cv, study_subject ss, study_event se"
                        + " WHERE cv.crf_version_id = ec.crf_version_id"
                        + "   AND ss.study_subject_id = ec.study_subject_id"
                        + "   AND se.study_event_id = ec.study_event_id"
                        + "   AND " + versionWhere
                        + "   AND ec.status_id = ?"
                        + "   AND cv.status_id NOT IN (?, ?)"
                        + "   AND ss.status_id NOT IN (?, ?)"
                        + "   AND se.status_id NOT IN (?, ?)"
                        + " RETURNING ec.event_crf_id")) {
            ps.setInt(1, userId);
            ps.setInt(2, param);
            ps.setInt(3, AUTO_DELETED);
            ps.setInt(4, DELETED);
            ps.setInt(5, AUTO_DELETED);
            ps.setInt(6, DELETED);
            ps.setInt(7, AUTO_DELETED);
            ps.setInt(8, DELETED);
            ps.setInt(9, AUTO_DELETED);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) ids.add(rs.getInt(1));
            }
        }
        return ids;
    }

    private static int removeItemData(Connection c, List<Integer> eventCrfIds, int userId) throws SQLException {
        if (eventCrfIds.isEmpty()) return 0;
        return update(c,
                "UPDATE item_data SET old_status_id = status_id, status_id = ?, update_id = ?, date_updated = now()"
                        + " WHERE event_crf_id = ANY (?) AND status_id NOT IN (?, ?)",
                AUTO_DELETED, userId, intArray(c, eventCrfIds), DELETED, AUTO_DELETED);
    }

    private static int restoreItemData(Connection c, List<Integer> eventCrfIds, int userId) throws SQLException {
        if (eventCrfIds.isEmpty()) return 0;
        return update(c,
                "UPDATE item_data SET status_id = " + priorStatus("old_status_id") + ","
                        + "       update_id = ?, date_updated = now()"
                        + " WHERE event_crf_id = ANY (?) AND status_id = ?",
                userId, intArray(c, eventCrfIds), AUTO_DELETED);
    }

    /**
     * The status a version had before it was auto-removed, from the most
     * recent lifecycle audit row that recorded the auto-removal: locked when
     * that row says so, available otherwise (also when there is no row, as
     * for a version the legacy servlet removed).
     */
    private static int statusBeforeAutoRemoval(Connection c, int versionId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT old_value FROM audit_log_event"
                        + " WHERE audit_log_event_type_id = ? AND audit_table = 'crf_version'"
                        + "   AND entity_id = ? AND new_value = ?"
                        + " ORDER BY audit_id DESC LIMIT 1")) {
            ps.setInt(1, AuditTypeIds.CRF_VERSION_LIFECYCLE_CHANGED);
            ps.setInt(2, versionId);
            ps.setString(3, Status.AUTO_DELETED.getName());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && Status.LOCKED.getName().equals(rs.getString(1))) return LOCKED;
            }
        }
        return AVAILABLE;
    }

    private static Array intArray(Connection c, List<Integer> ids) throws SQLException {
        return c.createArrayOf("integer", ids.toArray());
    }

    private static int update(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                Object p = params[i];
                if (p instanceof Integer n) ps.setInt(i + 1, n);
                else if (p instanceof Array a) ps.setArray(i + 1, a);
                else ps.setObject(i + 1, p);
            }
            return ps.executeUpdate();
        }
    }
}
