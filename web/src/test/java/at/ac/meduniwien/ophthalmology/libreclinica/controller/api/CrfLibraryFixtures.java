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

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.UserType;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;

import org.springframework.mock.web.MockHttpSession;

/**
 * Rows for the CRF-library database ITs: a CRF with versions, sections,
 * items and groups, event-definition CRFs, subjects with events, event CRFs
 * and item data, sites, users and role bindings. Every insert returns the
 * generated key, so each test builds its own rows and tests stay
 * independent within one container.
 *
 * <p>Column sets follow the demo seed
 * ({@code lc-muw-2026-06-01-seed-demo-data.xml}), which the same schema
 * accepts. Study 1 ({@code S_DEFAULTS1}) and its event definition 1
 * ({@code SE_V1_INCLUSION}) come from the seed.
 */
final class CrfLibraryFixtures {

    static final int STUDY_ID = 1;
    static final String STUDY_OID = "S_DEFAULTS1";
    static final int SED_ID = 1;
    static final String SED_OID = "SE_V1_INCLUSION";
    static final int SED2_ID = 2;

    private final DataSource ds;

    CrfLibraryFixtures(DataSource ds) {
        this.ds = ds;
    }

    int crf(String name, String oid, int ownerId) throws SQLException {
        return insert("crf_id", "INSERT INTO crf (status_id, name, description, owner_id, date_created, oc_oid)"
                + " VALUES (1, ?, 'fixture', ?, now(), ?)", name, ownerId, oid);
    }

    int version(int crfId, String name, String oid, int statusId) throws SQLException {
        return insert("crf_version_id", "INSERT INTO crf_version (crf_id, name, description, status_id, date_created, owner_id, oc_oid)"
                + " VALUES (?, ?, 'fixture', ?, now(), 1, ?)", crfId, name, statusId, oid);
    }

    int section(int versionId, String label, int statusId) throws SQLException {
        return insert("section_id", "INSERT INTO section (crf_version_id, status_id, label, title, instructions, ordinal,"
                + " date_created, owner_id) VALUES (?, ?, ?, ?, '', 1, now(), 1)", versionId, statusId, label, label);
    }

    int item(String name, int dataTypeId) throws SQLException {
        return insert("item_id", "INSERT INTO item (name, description, phi_status, item_data_type_id, status_id, owner_id,"
                + " date_created, oc_oid) VALUES (?, ?, false, ?, 1, 1, now(), ?)",
                name, "item " + name, dataTypeId, "I_" + name);
    }

    void place(int itemId, int versionId, int sectionId, int ordinal) throws SQLException {
        execute("INSERT INTO item_form_metadata (item_id, crf_version_id, section_id, response_set_id,"
                + " left_item_text, ordinal, required, show_item) VALUES (?, ?, ?, 2, 'label', ?, false, true)",
                itemId, versionId, sectionId, ordinal);
    }

    int group(int crfId, String name) throws SQLException {
        return insert("item_group_id", "INSERT INTO item_group (name, crf_id, status_id, date_created, owner_id, oc_oid)"
                + " VALUES (?, ?, 1, now(), 1, ?)", name, crfId, "IG_" + name);
    }

    void groupItem(int groupId, int versionId, int itemId, int ordinal) throws SQLException {
        execute("INSERT INTO item_group_metadata (item_group_id, header, subheader, layout, repeat_number,"
                + " repeat_max, repeat_array, row_start_number, crf_version_id, item_id, ordinal, borders,"
                + " show_group) VALUES (?, '', '', '', 1, 1, '', 1, ?, ?, ?, 0, true)",
                groupId, versionId, itemId, ordinal);
    }

    int eventDefinitionCrf(int sedId, int studyId, int crfId, int defaultVersionId, String selectedVersionIds)
            throws SQLException {
        return insert("event_definition_crf_id", "INSERT INTO event_definition_crf (study_event_definition_id, study_id, crf_id, required_crf,"
                + " double_entry, default_version_id, status_id, owner_id, date_created, ordinal,"
                + " source_data_verification_code, selected_version_ids)"
                + " VALUES (?, ?, ?, true, false, ?, 1, 1, now(), 1, 1, ?)",
                sedId, studyId, crfId, defaultVersionId, selectedVersionIds);
    }

    int site(String oid, String name) throws SQLException {
        return study(1, oid, name, 1);
    }

    /** A study of its own (no parent), in {@code statusId}. */
    int topStudy(String oid, String name, int statusId) throws SQLException {
        return study(null, oid, name, statusId);
    }

    /** An event definition of {@code studyId}, in {@code statusId}. */
    int eventDefinition(int studyId, String name, String oid, int statusId) throws SQLException {
        return insert("study_event_definition_id", "INSERT INTO study_event_definition (study_id, name, description,"
                + " repeating, type, category, owner_id, status_id, date_created, ordinal, oc_oid)"
                + " VALUES (?, ?, '', false, 'scheduled', '', 1, ?, now(), 9, ?)", studyId, name, statusId, oid);
    }

    private int study(Integer parentStudyId, String oid, String name, int statusId) throws SQLException {
        return insert("study_id", "INSERT INTO study (parent_study_id, unique_identifier, secondary_identifier, "
                + "name, summary, date_planned_start, date_planned_end, date_created, "
                + "owner_id, type_id, status_id, principal_investigator, facility_name, "
                + "facility_city, facility_state, facility_zip, facility_country, "
                + "facility_recruitment_status, facility_contact_name, facility_contact_degree, "
                + "facility_contact_phone, facility_contact_email, protocol_type, "
                + "protocol_description, protocol_date_verification, phase, "
                + "expected_total_enrollment, sponsor, collaborators, medline_identifier, "
                + "url, url_description, conditions, keywords, eligibility, gender, "
                + "age_max, age_min, healthy_volunteer_accepted, purpose, allocation, "
                + "masking, control, assignment, endpoint, interventions, duration, "
                + "selection, timing, official_title, results_reference, oc_oid) "
                + "VALUES (CAST(? AS INTEGER), ?, ?, ?, '', NOW(), NOW(), NOW(), 1, 1, ?, 'default', "
                + "'', '', '', '', '', '', '', '', '', '', 'observational', '', NOW(), "
                + "'default', 0, 'default', '', '', '', '', '', '', '', 'both', '', '', "
                + "false, 'Natural History', '', '', '', '', '', '', 'longitudinal', "
                + "'Convenience Sample', 'Retrospective', '', false, ?)",
                parentStudyId, oid, oid, name, statusId, oid);
    }

    int subject(String label, int studyId, int statusId) throws SQLException {
        int subjectId = insert("subject_id", "INSERT INTO subject (status_id, date_of_birth, gender, date_created, owner_id,"
                + " dob_collected) VALUES (1, '1970-01-01', 'f', now(), 1, true)");
        return insert("study_subject_id", "INSERT INTO study_subject (label, subject_id, study_id, status_id, enrollment_date,"
                + " date_created, owner_id, oc_oid) VALUES (?, ?, ?, ?, now(), now(), 1, ?)",
                label, subjectId, studyId, statusId, "SS_" + label.replaceAll("[^A-Za-z0-9]", ""));
    }

    int event(int studySubjectId, int sedId, int subjectEventStatusId, int statusId) throws SQLException {
        return insert("study_event_id", "INSERT INTO study_event (study_event_definition_id, study_subject_id, sample_ordinal,"
                + " date_start, owner_id, status_id, date_created, subject_event_status_id, start_time_flag,"
                + " end_time_flag) VALUES (?, ?, 1, now(), 1, ?, now(), ?, false, false)",
                sedId, studySubjectId, statusId, subjectEventStatusId);
    }

    int eventCrf(int studyEventId, int studySubjectId, int versionId, int statusId, boolean sdv,
                 boolean completed, boolean eSignature) throws SQLException {
        return insert("event_crf_id", "INSERT INTO event_crf (study_event_id, crf_version_id, date_interviewed,"
                + " completion_status_id, status_id, date_completed, owner_id, date_created, study_subject_id,"
                + " electronic_signature_status, sdv_status)"
                + " VALUES (?, ?, now(), 1, ?, " + (completed ? "now()" : "NULL") + ", 1, now(), ?, ?, ?)",
                studyEventId, versionId, statusId, studySubjectId, eSignature, sdv);
    }

    int itemData(int eventCrfId, int itemId, String value, int statusId) throws SQLException {
        return insert("item_data_id", "INSERT INTO item_data (item_id, event_crf_id, status_id, value, date_created, owner_id,"
                + " ordinal) VALUES (?, ?, ?, ?, now(), 1, 1)", itemId, eventCrfId, statusId, value);
    }

    /** A signing recorded in the audit trail: the row the triggers write for a status change to 8. */
    void signedAudit(String table, int entityId, String before) throws SQLException {
        execute("INSERT INTO audit_log_event (audit_log_event_type_id, audit_date, user_id, audit_table,"
                + " entity_id, entity_name, old_value, new_value)"
                + " VALUES (3, now() - interval '1 day', 1, ?, ?, 'Status', ?, '8')", table, entityId, before);
    }

    int user(String userName, boolean sysAdmin) throws SQLException {
        return insert("user_id", "INSERT INTO user_account (user_name, passwd, first_name, last_name, email, active_study,"
                + " institutional_affiliation, status_id, owner_id, date_created, user_type_id, enabled,"
                + " account_non_locked, lock_counter, run_webservices, authtype, enable_api_key)"
                + " VALUES (?, 'x', 'Test', ?, ?, 1, 'MUW (test)', 1, 1, now(), ?, true, true, 0, false,"
                + " 'STANDARD', false)", userName, userName, userName + "@example.invalid", sysAdmin ? 1 : 2);
    }

    void role(String userName, int studyId, String roleName) throws SQLException {
        role(userName, studyId, roleName, 1);
    }

    void role(String userName, int studyId, String roleName, int statusId) throws SQLException {
        execute("INSERT INTO study_user_role (role_name, study_id, status_id, owner_id, date_created, user_name)"
                + " VALUES (?, ?, ?, 1, now(), ?)", roleName, studyId, statusId, userName);
    }

    int intValue(String sql, Object... params) throws SQLException {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new SQLException("no row: " + sql);
                return rs.getInt(1);
            }
        }
    }

    String stringValue(String sql, Object... params) throws SQLException {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new SQLException("no row: " + sql);
                return rs.getString(1);
            }
        }
    }

    boolean boolValue(String sql, Object... params) throws SQLException {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new SQLException("no row: " + sql);
                return rs.getBoolean(1);
            }
        }
    }

    int status(String table, int id) throws SQLException {
        return intValue("SELECT status_id FROM " + table + " WHERE " + table + "_id = ?", id);
    }

    void execute(String sql, Object... params) throws SQLException {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            ps.executeUpdate();
        }
    }

    /** A session for a user row; {@code sysAdmin} marks the bean as the SPA's system administrator. */
    static MockHttpSession session(int userId, String userName, boolean sysAdmin) {
        MockHttpSession session = new MockHttpSession();
        UserAccountBean ub = new UserAccountBean();
        ub.setId(userId);
        ub.setName(userName);
        if (sysAdmin) ub.addUserType(UserType.SYSADMIN);
        session.setAttribute("userBean", ub);
        StudyBean study = new StudyBean();
        study.setId(STUDY_ID);
        study.setOid(STUDY_OID);
        study.setName("Default Study");
        session.setAttribute("study", study);
        return session;
    }

    /** {@link #session} with {@code role} on study 1 as the session's current role, as the study screens read it. */
    static MockHttpSession session(int userId, String userName, boolean sysAdmin, Role role) {
        MockHttpSession session = session(userId, userName, sysAdmin);
        StudyUserRoleBean current = new StudyUserRoleBean();
        current.setRole(role);
        current.setStudyId(STUDY_ID);
        session.setAttribute("userRole", current);
        return session;
    }

    private int insert(String keyColumn, String sql, Object... params) throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql + " RETURNING " + keyColumn)) {
            bind(ps, params);
            try (ResultSet keys = ps.executeQuery()) {
                return keys.next() ? keys.getInt(1) : 0;
            }
        }
    }

    private static void bind(PreparedStatement ps, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            Object p = params[i];
            if (p == null) ps.setNull(i + 1, java.sql.Types.VARCHAR);
            else if (p instanceof Integer n) ps.setInt(i + 1, n);
            else if (p instanceof Boolean b) ps.setBoolean(i + 1, b);
            else ps.setString(i + 1, p.toString());
        }
    }
}
