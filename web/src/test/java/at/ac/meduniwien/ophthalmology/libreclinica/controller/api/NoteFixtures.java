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
import java.sql.Timestamp;
import java.time.Instant;

import javax.sql.DataSource;

/**
 * Discrepancy notes written straight into the database for the note ITs,
 * with the dates a test needs, which the API cannot backdate.
 *
 * <p>Notes go into Default Study (study 1), owned by {@code root} (user 1).
 * Ids from the demo seed: resolution status 1 New, 2 Updated, 3 Resolution
 * Proposed, 4 Closed, 5 Not Applicable; type 1 failed validation check,
 * 2 annotation, 3 query, 4 reason for change.
 */
final class NoteFixtures {

    private NoteFixtures() {}

    /** A parent note on an {@code item_data} row, mapped the way legacy maps it. */
    static int insertItemNote(DataSource dataSource, int typeId, int statusId, Instant created,
                              int itemDataId, String description) throws SQLException {
        int noteId = insertNote(dataSource, 0, typeId, statusId, created, description);
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO dn_item_data_map (item_data_id, discrepancy_note_id, column_name, "
                             + "study_subject_id, activated) "
                             + "SELECT ?, ?, 'value', ec.study_subject_id, true FROM item_data d "
                             + "JOIN event_crf ec ON ec.event_crf_id = d.event_crf_id "
                             + "WHERE d.item_data_id = ?")) {
            ps.setInt(1, itemDataId);
            ps.setInt(2, noteId);
            ps.setInt(3, itemDataId);
            ps.executeUpdate();
        }
        return noteId;
    }

    /** A child note in the thread of {@code parentId}, as a reply is stored. */
    static int insertChild(DataSource dataSource, int parentId, int statusId, Instant created,
                           String description) throws SQLException {
        return insertNote(dataSource, parentId, typeOf(dataSource, parentId), statusId, created,
                description);
    }

    static void delete(DataSource dataSource, int noteId) throws SQLException {
        ClinicalWriteFixtures.execute(dataSource,
                "DELETE FROM dn_item_data_map WHERE discrepancy_note_id = " + noteId);
        ClinicalWriteFixtures.execute(dataSource,
                "DELETE FROM discrepancy_note WHERE parent_dn_id = " + noteId);
        ClinicalWriteFixtures.execute(dataSource,
                "DELETE FROM discrepancy_note WHERE discrepancy_note_id = " + noteId);
    }

    static Instant createdAt(DataSource dataSource, int noteId) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT date_created FROM discrepancy_note WHERE discrepancy_note_id = ?")) {
            ps.setInt(1, noteId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getTimestamp(1).toInstant();
            }
        }
    }

    static int statusOf(DataSource dataSource, int noteId) throws SQLException {
        return intColumn(dataSource,
                "SELECT resolution_status_id FROM discrepancy_note WHERE discrepancy_note_id = ?", noteId);
    }

    static int assigneeOf(DataSource dataSource, int noteId) throws SQLException {
        return intColumn(dataSource,
                "SELECT COALESCE(assigned_user_id, 0) FROM discrepancy_note WHERE discrepancy_note_id = ?",
                noteId);
    }

    static int typeOf(DataSource dataSource, int noteId) throws SQLException {
        return intColumn(dataSource,
                "SELECT discrepancy_note_type_id FROM discrepancy_note WHERE discrepancy_note_id = ?",
                noteId);
    }

    /** The label of a study subject of the demo seed. */
    static String subjectLabel(DataSource dataSource, int studySubjectId) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT label FROM study_subject WHERE study_subject_id = ?")) {
            ps.setInt(1, studySubjectId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private static int insertNote(DataSource dataSource, int parentId, int typeId, int statusId,
                                  Instant created, String description) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO discrepancy_note (description, discrepancy_note_type_id, "
                             + "resolution_status_id, date_created, owner_id, parent_dn_id, "
                             + "entity_type, study_id) "
                             + "VALUES (?, ?, ?, ?, 1, ?, 'itemData', 1) RETURNING discrepancy_note_id")) {
            ps.setString(1, description);
            ps.setInt(2, typeId);
            ps.setInt(3, statusId);
            ps.setTimestamp(4, Timestamp.from(created));
            if (parentId > 0) {
                ps.setInt(5, parentId);
            } else {
                ps.setNull(5, java.sql.Types.INTEGER);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static int intColumn(DataSource dataSource, String sql, int id) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }
}
