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
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Status;

/**
 * The rows a CRF or a CRF version takes with it when it is removed, and brings
 * back when it is restored. Legacy parity: {@code RemoveCRFServlet},
 * {@code RestoreCRFServlet}, {@code RemoveCRFVersionServlet} and
 * {@code RestoreCRFVersionServlet}.
 *
 * <p>Removal marks the dependent rows {@link Status#AUTO_DELETED}; restore
 * brings back only rows in that state, so a row removed on its own
 * ({@link Status#DELETED}) stays removed, as in the legacy servlets. Where
 * this differs from the legacy loops, it is so that removal and restore are
 * inverses:
 * <ul>
 *   <li><b>A removal records each event CRF it takes.</b> The status it had
 *       goes to {@code old_status_id} (the convention
 *       {@code UpdateEventDefinitionServlet} uses), and one audit row on the
 *       event CRF names the removal
 *       ({@link AuditTypeIds#EVENT_CRF_REMOVED_WITH_CRF} or
 *       {@link AuditTypeIds#EVENT_CRF_REMOVED_WITH_VERSION}) and that status.
 *       The row puts the change in the study's and the subject's audit log,
 *       and it is how a restore knows which rows its removal took.</li>
 *   <li><b>A restore brings back what its removal took, at the status it
 *       had</b>: an auto-removed event CRF whose latest such row is that
 *       removal's and whose {@code old_status_id} still says what the row
 *       says, whatever its version's status. Each is recorded
 *       ({@link AuditTypeIds#EVENT_CRF_RESTORED}). The legacy restore set
 *       every row available, so a signed or locked event CRF came back
 *       unsigned and unlocked. A version comes back at the status its latest
 *       lifecycle audit row recorded, so a locked version comes back locked,
 *       and an assignment under a locked event definition comes back
 *       locked.</li>
 *   <li><b>A row this code did not take comes back as the legacy restore
 *       brought it back</b>, available: an event CRF that an older screen
 *       auto-removed, or one removed before this code. Its
 *       {@code old_status_id} may be left over from anything, so it is not
 *       used. A CRF restore leaves such a row alone while its version is
 *       removed, since the version's removal may have taken it.</li>
 *   <li><b>Rows removed by something else stay removed.</b> Restore leaves an
 *       auto-removed event CRF alone while its subject, its event or its
 *       assignment to the event definition is removed, and when the other
 *       removal (of its CRF, or of its version) took it; and an
 *       event-definition CRF while its event definition or its study is. That
 *       removal's own restore brings it back. The legacy restore resurrected
 *       them under a removed parent. {@link #heldByRemoval} lets the other
 *       restores do the same the other way round.</li>
 *   <li><b>Rows that were already removed are not touched</b> by a removal,
 *       so what they recorded survives.</li>
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

    /**
     * A study whose event CRFs changed, with how many; a subject at a site
     * counts for the site's study.
     */
    record StudyTouch(int studyId, String oid, String name, int statusId, int eventCrfs) {}

    /** What a removal or restore changed; {@code versions} only for a CRF's. */
    record Result(List<VersionChange> versions, Counts counts, List<StudyTouch> studies) {}

    /** One {@code event_definition_crf} whose default version was moved. */
    record DefaultMove(int eventDefinitionCrfId, int fromVersionId, int toVersionId) {}

    private static final int AVAILABLE = Status.AVAILABLE.getId();
    private static final int DELETED = Status.DELETED.getId();
    private static final int AUTO_DELETED = Status.AUTO_DELETED.getId();
    private static final int LOCKED = Status.LOCKED.getId();

    /**
     * The statuses a restored event CRF or item value comes back at: those of
     * a live row (available, completed, pending, locked, signed). A recorded
     * status outside them, such as reset, comes back available.
     */
    private static final Set<Integer> LIVE = Set.of(AVAILABLE, Status.UNAVAILABLE.getId(),
            Status.PENDING.getId(), LOCKED, Status.SIGNED.getId());

    private static final String LIVE_LIST =
            LIVE.stream().sorted().map(String::valueOf).collect(Collectors.joining(", "));

    /**
     * The latest audit row on event CRF {@code ec} that says which removal
     * took it, or that it was restored since.
     */
    private static final String LATEST_MARK =
            "SELECT a.audit_log_event_type_id AS type_id, a.old_value FROM audit_log_event a"
                    + " WHERE a.audit_table = 'event_crf' AND a.entity_id = ec.event_crf_id"
                    + "   AND a.audit_log_event_type_id IN (" + AuditTypeIds.EVENT_CRF_REMOVED_WITH_CRF + ", "
                    + AuditTypeIds.EVENT_CRF_REMOVED_WITH_VERSION + ", " + AuditTypeIds.EVENT_CRF_RESTORED + ")"
                    + " ORDER BY a.audit_id DESC LIMIT 1";

    /** Which removal a restore undoes: the event CRFs it looks at, and the audit row that removal wrote. */
    private enum Scope {
        CRF("cv.crf_id = ?", AuditTypeIds.EVENT_CRF_REMOVED_WITH_CRF),
        VERSION("ec.crf_version_id = ?", AuditTypeIds.EVENT_CRF_REMOVED_WITH_VERSION);

        final String where;
        final int mark;

        Scope(String where, int mark) {
            this.where = where;
            this.mark = mark;
        }
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
     * @return the versions that changed, each with its prior status, the
     *         counts, and the studies whose event CRFs were taken
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
                crfId, userId, AuditTypeIds.EVENT_CRF_REMOVED_WITH_CRF);
        int itemData = removeItemData(c, eventCrfs, userId);
        return new Result(versions, new Counts(versions.size(), sections, edcs, eventCrfs.size(), itemData),
                studiesOf(c, eventCrfs));
    }

    /**
     * Brings back what {@link #removeCrf} took: auto-removed versions (at the
     * status their lifecycle audit row recorded) and their sections,
     * auto-removed event-definition CRFs whose event definition and study are
     * not removed (locked while the event definition is), and the event CRFs
     * with their item data as the class comment says.
     *
     * @return the versions that changed, each with the status it came back at,
     *         the counts, and the studies whose event CRFs came back
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
            int restoredTo = statusBeforeRemoval(c, versionId, Status.AUTO_DELETED);
            update(c, "UPDATE crf_version SET status_id = ?, update_id = ?, date_updated = now()"
                    + " WHERE crf_version_id = ?", restoredTo, userId, versionId);
            versions.add(new VersionChange(versionId, oids.get(i), restoredTo));
        }
        int sections = restoreSections(c, autoRemoved, userId);
        // An assignment's prior status is not kept. Under a locked event
        // definition every assignment is locked (LockEventDefinitionServlet,
        // the SPA's lock), whichever came first, the lock or the removal.
        int edcs = update(c,
                "UPDATE event_definition_crf edc SET status_id = CASE WHEN sed.status_id = ? THEN ? ELSE ? END,"
                        + "       update_id = ?, date_updated = now()"
                        + "  FROM study_event_definition sed"
                        + " WHERE sed.study_event_definition_id = edc.study_event_definition_id"
                        + "   AND edc.crf_id = ? AND edc.status_id = ? AND sed.status_id NOT IN (?, ?)"
                        + "   AND EXISTS (SELECT 1 FROM study s"
                        + "                WHERE s.study_id = edc.study_id AND s.status_id NOT IN (?, ?))",
                LOCKED, LOCKED, AVAILABLE, userId, crfId, AUTO_DELETED, DELETED, AUTO_DELETED,
                DELETED, AUTO_DELETED);
        Restored eventCrfs = restoreEventCrfs(c, Scope.CRF, crfId, userId);
        int itemData = restoreItemData(c, eventCrfs, userId);
        return new Result(versions, new Counts(versions.size(), sections, edcs, eventCrfs.all().size(), itemData),
                studiesOf(c, eventCrfs.all()));
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
    static Result removeVersion(Connection c, int versionId, int userId) throws SQLException {
        int sections = removeSections(c, List.of(versionId), userId);
        List<Integer> eventCrfs = removeEventCrfs(c,
                "ec.crf_version_id = ? AND ec.status_id IN (1, 2, 4, 6)", versionId, userId,
                AuditTypeIds.EVENT_CRF_REMOVED_WITH_VERSION);
        int itemData = removeItemData(c, eventCrfs, userId);
        return new Result(List.of(), new Counts(0, sections, 0, eventCrfs.size(), itemData),
                studiesOf(c, eventCrfs));
    }

    /**
     * Brings back what {@link #removeVersion} took: the version's auto-removed
     * sections, and its event CRFs with their item data as the class comment
     * says. The version row itself is the caller's; see
     * {@link #statusBeforeRemoval}.
     */
    static Result restoreVersion(Connection c, int versionId, int userId) throws SQLException {
        int sections = restoreSections(c, List.of(versionId), userId);
        Restored eventCrfs = restoreEventCrfs(c, Scope.VERSION, versionId, userId);
        int itemData = restoreItemData(c, eventCrfs, userId);
        return new Result(List.of(), new Counts(0, sections, 0, eventCrfs.all().size(), itemData),
                studiesOf(c, eventCrfs.all()));
    }

    /**
     * The status a version comes back at: locked when its latest lifecycle
     * audit row records that it went from locked to {@code removedAs}
     * (auto-removed with its CRF, or removed on its own), else available. Only
     * the latest row counts: after a later unlock, or after a removal by the
     * legacy screens (which write no such row), an older one no longer says
     * how the version was when it was removed.
     */
    static int statusBeforeRemoval(Connection c, int versionId, Status removedAs) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT old_value, new_value FROM audit_log_event"
                        + " WHERE audit_log_event_type_id = ? AND audit_table = 'crf_version' AND entity_id = ?"
                        + " ORDER BY audit_id DESC LIMIT 1")) {
            ps.setInt(1, AuditTypeIds.CRF_VERSION_LIFECYCLE_CHANGED);
            ps.setInt(2, versionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && removedAs.getName().equals(rs.getString(2))
                        && Status.LOCKED.getName().equals(rs.getString(1))) {
                    return LOCKED;
                }
            }
        }
        return AVAILABLE;
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
    /* For the other restores                                             */
    /* ------------------------------------------------------------------ */

    /**
     * The event CRFs among {@code eventCrfIds} that a CRF or version removal
     * holds: auto-removed while their CRF is removed, or while their version
     * is removed and the version's removal took them. The restore of a
     * subject, an event or an event definition leaves these to the CRF's or
     * the version's restore, which brings them back at the status they had;
     * otherwise a removed CRF would have live event CRFs again.
     */
    static Set<Integer> heldByRemoval(DataSource dataSource, Collection<Integer> eventCrfIds) {
        Set<Integer> held = new HashSet<>();
        if (eventCrfIds.isEmpty()) return held;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT ec.event_crf_id FROM event_crf ec"
                             + "  JOIN crf_version cv ON cv.crf_version_id = ec.crf_version_id"
                             + "  JOIN crf f ON f.crf_id = cv.crf_id"
                             + "  LEFT JOIN LATERAL (" + LATEST_MARK + ") m ON true"
                             + " WHERE ec.event_crf_id = ANY (?) AND ec.status_id = ?"
                             + "   AND (f.status_id IN (?, ?)"
                             + "        OR (cv.status_id IN (?, ?) AND m.type_id = ?))")) {
            ps.setArray(1, intArray(c, new ArrayList<>(eventCrfIds)));
            ps.setInt(2, AUTO_DELETED);
            ps.setInt(3, DELETED);
            ps.setInt(4, AUTO_DELETED);
            ps.setInt(5, DELETED);
            ps.setInt(6, AUTO_DELETED);
            ps.setInt(7, AuditTypeIds.EVENT_CRF_REMOVED_WITH_VERSION);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) held.add(rs.getInt(1));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Reading which event CRFs a CRF or version removal holds failed", e);
        }
        return held;
    }

    /**
     * The CRFs among {@code crfIds} that are removed. The restore of an event
     * definition leaves their assignments removed; the CRF's restore brings
     * them back.
     */
    static Set<Integer> removedCrfs(DataSource dataSource, Collection<Integer> crfIds) {
        Set<Integer> removed = new HashSet<>();
        if (crfIds.isEmpty()) return removed;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT crf_id FROM crf WHERE crf_id = ANY (?) AND status_id IN (?, ?)")) {
            ps.setArray(1, intArray(c, new ArrayList<>(crfIds)));
            ps.setInt(2, DELETED);
            ps.setInt(3, AUTO_DELETED);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) removed.add(rs.getInt(1));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Reading which CRFs are removed failed", e);
        }
        return removed;
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

    /**
     * Auto-removes the event CRFs {@code where} selects (over {@code ec}),
     * recording each one's prior status in {@code old_status_id} and in an
     * audit row of type {@code markType}.
     */
    private static List<Integer> removeEventCrfs(Connection c, String where, int param, int userId, int markType)
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
        ids.sort(null);
        audit(c, ids, markType, "CAST(ec.old_status_id AS VARCHAR)", userId);
        return ids;
    }

    /** The event CRFs a restore brought back: those its removal recorded, and those it did not. */
    private record Restored(List<Integer> recorded, List<Integer> unrecorded) {
        List<Integer> all() {
            List<Integer> all = new ArrayList<>(recorded);
            all.addAll(unrecorded);
            all.sort(null);
            return all;
        }
    }

    private static final String RESTORE_CANDIDATES =
            "SELECT ec.event_crf_id, ec.old_status_id, cv.status_id, m.type_id, m.old_value"
                    + "  FROM event_crf ec"
                    + "  JOIN crf_version cv ON cv.crf_version_id = ec.crf_version_id"
                    + "  JOIN study_subject ss ON ss.study_subject_id = ec.study_subject_id"
                    + "  JOIN study s ON s.study_id = ss.study_id"
                    + "  JOIN study_event se ON se.study_event_id = ec.study_event_id"
                    + "  LEFT JOIN LATERAL (" + LATEST_MARK + ") m ON true"
                    + " WHERE %s AND ec.status_id = ?"
                    + "   AND ss.status_id NOT IN (?, ?) AND se.status_id NOT IN (?, ?)"
                    + "   AND NOT EXISTS (SELECT 1 FROM event_definition_crf edc"
                    + "                    WHERE edc.crf_id = cv.crf_id"
                    + "                      AND edc.study_event_definition_id = se.study_event_definition_id"
                    + "                      AND edc.study_id IN (ss.study_id, COALESCE(s.parent_study_id, ss.study_id))"
                    + "                      AND edc.status_id IN (?, ?))"
                    + " ORDER BY ec.event_crf_id";

    /**
     * Restores the auto-removed event CRFs of the CRF or version whose
     * subject, event and assignment are not removed: those its removal took,
     * at the status recorded; those no removal of this code took, available
     * (a CRF restore only while their version is not removed); none that the
     * other removal took.
     */
    private static Restored restoreEventCrfs(Connection c, Scope scope, int param, int userId)
            throws SQLException {
        Map<Integer, List<Integer>> byStatus = new TreeMap<>();
        List<Integer> recorded = new ArrayList<>();
        List<Integer> unrecorded = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(String.format(RESTORE_CANDIDATES, scope.where))) {
            int i = 1;
            ps.setInt(i++, param);
            ps.setInt(i++, AUTO_DELETED);
            ps.setInt(i++, DELETED);
            ps.setInt(i++, AUTO_DELETED);
            ps.setInt(i++, DELETED);
            ps.setInt(i++, AUTO_DELETED);
            ps.setInt(i++, DELETED);
            ps.setInt(i, AUTO_DELETED);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int id = rs.getInt(1);
                    Integer prior = nullableInt(rs, 2);
                    boolean versionRemoved = isRemoved(rs.getInt(3));
                    Integer mark = nullableInt(rs, 4);
                    Integer marked = parseInt(rs.getString(5));
                    int status;
                    if (mark != null && mark == scope.mark && Objects.equals(marked, prior)) {
                        status = prior != null && LIVE.contains(prior) ? prior : AVAILABLE;
                        recorded.add(id);
                    } else if (mark == null || mark == scope.mark || mark == AuditTypeIds.EVENT_CRF_RESTORED) {
                        if (scope == Scope.CRF && versionRemoved) continue;
                        status = AVAILABLE;
                        unrecorded.add(id);
                    } else {
                        continue; // the other removal took it
                    }
                    byStatus.computeIfAbsent(status, _ -> new ArrayList<>()).add(id);
                }
            }
        }
        for (Map.Entry<Integer, List<Integer>> e : byStatus.entrySet()) {
            update(c, "UPDATE event_crf SET status_id = ?, update_id = ?, date_updated = now()"
                    + " WHERE event_crf_id = ANY (?)", e.getKey(), userId, intArray(c, e.getValue()));
        }
        Restored restored = new Restored(recorded, unrecorded);
        audit(c, restored.all(), AuditTypeIds.EVENT_CRF_RESTORED, "'" + AUTO_DELETED + "'", userId);
        return restored;
    }

    private static int removeItemData(Connection c, List<Integer> eventCrfIds, int userId) throws SQLException {
        if (eventCrfIds.isEmpty()) return 0;
        return update(c,
                "UPDATE item_data SET old_status_id = status_id, status_id = ?, update_id = ?, date_updated = now()"
                        + " WHERE event_crf_id = ANY (?) AND status_id NOT IN (?, ?)",
                AUTO_DELETED, userId, intArray(c, eventCrfIds), DELETED, AUTO_DELETED);
    }

    /** The item values of restored event CRFs: at their recorded status where the removal recorded it, else available. */
    private static int restoreItemData(Connection c, Restored restored, int userId) throws SQLException {
        int n = 0;
        if (!restored.recorded().isEmpty()) {
            n += update(c,
                    "UPDATE item_data SET status_id = CASE WHEN old_status_id IN (" + LIVE_LIST + ")"
                            + " THEN old_status_id ELSE ? END, update_id = ?, date_updated = now()"
                            + " WHERE event_crf_id = ANY (?) AND status_id = ?",
                    AVAILABLE, userId, intArray(c, restored.recorded()), AUTO_DELETED);
        }
        if (!restored.unrecorded().isEmpty()) {
            n += update(c,
                    "UPDATE item_data SET status_id = ?, update_id = ?, date_updated = now()"
                            + " WHERE event_crf_id = ANY (?) AND status_id = ?",
                    AVAILABLE, userId, intArray(c, restored.unrecorded()), AUTO_DELETED);
        }
        return n;
    }

    /**
     * One audit row per event CRF, on the event CRF, in the shape of the
     * {@code event_crf} trigger's rows (column {@code Status}, status ids as
     * values): from {@code oldValueSql} to its status now.
     */
    private static void audit(Connection c, List<Integer> eventCrfIds, int typeId, String oldValueSql, int userId)
            throws SQLException {
        if (eventCrfIds.isEmpty()) return;
        update(c,
                "INSERT INTO audit_log_event (audit_log_event_type_id, audit_date, user_id, audit_table, entity_id,"
                        + " entity_name, old_value, new_value, event_crf_id, study_event_id, event_crf_version_id)"
                        + " SELECT ?, now(), ?, 'event_crf', ec.event_crf_id, 'Status', " + oldValueSql + ","
                        + "        CAST(ec.status_id AS VARCHAR), ec.event_crf_id, ec.study_event_id, ec.crf_version_id"
                        + "   FROM event_crf ec WHERE ec.event_crf_id = ANY (?) ORDER BY ec.event_crf_id",
                typeId, userId, intArray(c, eventCrfIds));
    }

    private static List<StudyTouch> studiesOf(Connection c, List<Integer> eventCrfIds) throws SQLException {
        List<StudyTouch> out = new ArrayList<>();
        if (eventCrfIds.isEmpty()) return out;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT p.study_id, p.oc_oid, p.name, p.status_id, count(*) FROM event_crf ec"
                        + "  JOIN study_subject ss ON ss.study_subject_id = ec.study_subject_id"
                        + "  JOIN study s ON s.study_id = ss.study_id"
                        + "  JOIN study p ON p.study_id = COALESCE(NULLIF(s.parent_study_id, 0), s.study_id)"
                        + " WHERE ec.event_crf_id = ANY (?)"
                        + " GROUP BY p.study_id, p.oc_oid, p.name, p.status_id ORDER BY p.study_id")) {
            ps.setArray(1, intArray(c, eventCrfIds));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new StudyTouch(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getInt(4),
                            rs.getInt(5)));
                }
            }
        }
        return out;
    }

    private static boolean isRemoved(int statusId) {
        return statusId == DELETED || statusId == AUTO_DELETED;
    }

    private static Integer nullableInt(ResultSet rs, int column) throws SQLException {
        int v = rs.getInt(column);
        return rs.wasNull() ? null : v;
    }

    private static Integer parseInt(String s) {
        if (s == null) return null;
        try {
            return Integer.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
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
