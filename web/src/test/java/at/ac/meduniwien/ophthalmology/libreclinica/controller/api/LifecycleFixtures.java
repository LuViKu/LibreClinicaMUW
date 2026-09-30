/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Rows for the lifecycle ITs (study and site removal, the status
 * cascades), inserted with plain SQL so a fixture can hold states the
 * API would not produce on its own: a row removed or auto-removed before
 * the operation under test. Reads go through the IT's database.
 */
final class LifecycleFixtures {

    private LifecycleFixtures() {}

    /* ---------------------------- reads ------------------------------- */

    static int statusOf(String table, String idColumn, int id) throws SQLException {
        return intQuery("SELECT status_id FROM " + table + " WHERE " + idColumn + " = " + id);
    }

    static int oldStatusOf(String table, String idColumn, int id) throws SQLException {
        return intQuery("SELECT old_status_id FROM " + table + " WHERE " + idColumn + " = " + id);
    }

    static int roleStatus(String user, int studyId, String role) throws SQLException {
        return intQuery("SELECT status_id FROM study_user_role WHERE user_name = '" + user
                + "' AND study_id = " + studyId + " AND role_name = '" + role + "'");
    }

    static int roleRowCount(String user, int studyId) throws SQLException {
        return intQuery("SELECT count(*) FROM study_user_role WHERE user_name = '" + user
                + "' AND study_id = " + studyId);
    }

    static String sourceKindOf(int itemDataId) throws SQLException {
        try (Connection c = AbstractApiControllerDatabaseIT.DATA_SOURCE.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT source_kind FROM item_data WHERE item_data_id = " + itemDataId)) {
            assertTrue(rs.next());
            return rs.getString(1);
        }
    }

    static int intQuery(String sql) throws SQLException {
        try (Connection c = AbstractApiControllerDatabaseIT.DATA_SOURCE.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            assertTrue(rs.next(), "no row for: " + sql);
            return rs.getInt(1);
        }
    }

    /* ---------------------------- inserts ----------------------------- */

    static int insertOne(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    static int insertStudy(Connection c, Integer parent, String uid, String name, String oid, int statusId)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO study (parent_study_id, unique_identifier, name, summary, date_created, owner_id, "
                        + "type_id, status_id, old_status_id, principal_investigator, protocol_type, sponsor, oc_oid) "
                        + "VALUES (?, ?, ?, '', now(), 1, 1, ?, 1, 'PI', 'observational', 'MUW', ?) "
                        + "RETURNING study_id")) {
            if (parent == null) ps.setNull(1, java.sql.Types.INTEGER); else ps.setInt(1, parent);
            ps.setString(2, uid);
            ps.setString(3, name);
            ps.setInt(4, statusId);
            ps.setString(5, oid);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    static void insertUser(Connection c, String name, int statusId) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.executeUpdate("INSERT INTO user_account (user_name, passwd, first_name, last_name, email, "
                    + "active_study, institutional_affiliation, status_id, owner_id, date_created, user_type_id, "
                    + "enabled, account_non_locked, lock_counter, run_webservices, authtype, enable_api_key) "
                    + "VALUES ('" + name + "', 'x', 'F', 'L', '" + name + "@example.invalid', 1, 'MUW', "
                    + statusId + ", 1, now(), 2, true, true, 0, false, 'STANDARD', false)");
        }
    }

    static void insertRole(Connection c, String user, int studyId, String role, int statusId)
            throws SQLException {
        try (Statement s = c.createStatement()) {
            s.executeUpdate("INSERT INTO study_user_role (role_name, study_id, status_id, owner_id, date_created, "
                    + "user_name) VALUES ('" + role + "', " + studyId + ", " + statusId + ", 1, now(), '" + user + "')");
        }
    }

    static int insertStudySubject(Connection c, String label, int studyId, int statusId)
            throws SQLException {
        int subject = insertOne(c, "INSERT INTO subject (status_id, gender, date_created, owner_id, dob_collected) "
                + "VALUES (1, 'f', now(), 1, false) RETURNING subject_id");
        return insertOne(c, "INSERT INTO study_subject (label, subject_id, study_id, status_id, date_created, "
                + "owner_id, oc_oid, enrollment_date) VALUES ('" + label + "', " + subject + ", " + studyId + ", "
                + statusId + ", now(), 1, 'SS_" + label.replace("-", "") + "', '2026-01-01') "
                + "RETURNING study_subject_id");
    }

    /** Marks the person behind a study subject removed, as RemoveSubjectServlet does. */
    static void removePerson(Connection c, int studySubjectId) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.executeUpdate("UPDATE subject SET status_id = 5 WHERE subject_id = (SELECT subject_id "
                    + "FROM study_subject WHERE study_subject_id = " + studySubjectId + ")");
        }
    }

    static int insertGroupClass(Connection c, int studyId, String name) throws SQLException {
        return insertOne(c, "INSERT INTO study_group_class (name, study_id, owner_id, date_created, "
                + "group_class_type_id, status_id, subject_assignment) "
                + "VALUES ('" + name + "', " + studyId + ", 1, now(), 1, 1, 'optional') RETURNING study_group_class_id");
    }

    static int insertGroup(Connection c, int groupClassId, String name) throws SQLException {
        return insertOne(c, "INSERT INTO study_group (name, description, study_group_class_id) "
                + "VALUES ('" + name + "', '', " + groupClassId + ") RETURNING study_group_id");
    }

    static int insertMap(Connection c, int groupClassId, int studySubjectId, int groupId, int statusId)
            throws SQLException {
        return insertOne(c, "INSERT INTO subject_group_map (study_group_class_id, study_subject_id, study_group_id, "
                + "status_id, owner_id, date_created) VALUES (" + groupClassId + ", " + studySubjectId + ", "
                + groupId + ", " + statusId + ", 1, now()) RETURNING subject_group_map_id");
    }

    static int insertDefinition(Connection c, int studyId, String oid, int statusId) throws SQLException {
        return insertOne(c, "INSERT INTO study_event_definition (study_id, name, repeating, type, category, "
                + "owner_id, status_id, date_created, ordinal, oc_oid) VALUES (" + studyId + ", '" + oid
                + "', false, 'scheduled', '', 1, " + statusId + ", now(), 1, '" + oid + "') "
                + "RETURNING study_event_definition_id");
    }

    static int insertEvent(Connection c, int definitionId, int studySubjectId, int statusId)
            throws SQLException {
        return insertOne(c, "INSERT INTO study_event (study_event_definition_id, study_subject_id, sample_ordinal, "
                + "date_start, owner_id, status_id, date_created, subject_event_status_id, start_time_flag, "
                + "end_time_flag) VALUES (" + definitionId + ", " + studySubjectId + ", 1, now(), 1, " + statusId
                + ", now(), 1, false, false) RETURNING study_event_id");
    }

    static int insertEventCrf(Connection c, int eventId, int studySubjectId, int crfVersionId,
                              int statusId) throws SQLException {
        return insertOne(c, "INSERT INTO event_crf (study_event_id, crf_version_id, completion_status_id, "
                + "status_id, owner_id, date_created, study_subject_id, electronic_signature_status, sdv_status) "
                + "VALUES (" + eventId + ", " + crfVersionId + ", 1, " + statusId + ", 1, now(), " + studySubjectId
                + ", false, false) RETURNING event_crf_id");
    }

    static int insertItemData(Connection c, int eventCrfId, int itemId, int statusId, String sourceKind)
            throws SQLException {
        return insertOne(c, "INSERT INTO item_data (item_id, event_crf_id, status_id, value, date_created, "
                + "owner_id, ordinal, source_kind) VALUES (" + itemId + ", " + eventCrfId + ", " + statusId
                + ", 'v', now(), 1, 1, " + (sourceKind == null ? "NULL" : "'" + sourceKind + "'") + ") "
                + "RETURNING item_data_id");
    }

    static int insertDataset(Connection c, int studyId, String name, int statusId) throws SQLException {
        return insertOne(c, "INSERT INTO dataset (study_id, status_id, name, description, sql_statement, "
                + "num_runs, date_created, owner_id) VALUES (" + studyId + ", " + statusId + ", '" + name
                + "', '', '', 0, now(), 1) RETURNING dataset_id");
    }

    /** Sets {@code old_status_id} directly, for rows whose history a fixture has to spell out. */
    static void setOldStatus(Connection c, String table, String idColumn, int id, int oldStatusId)
            throws SQLException {
        try (Statement s = c.createStatement()) {
            s.executeUpdate("UPDATE " + table + " SET old_status_id = " + oldStatusId + " WHERE " + idColumn
                    + " = " + id);
        }
    }
}
