/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.control.isolation;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import javax.sql.DataSource;

/**
 * Two sites of Default Study (study 1), each with a subject, a visit, a CRF
 * with item data, discrepancy notes and a dataset, and four users scoped to
 * site A only (one study_user_role row each, on the site, none on the parent).
 *
 * <p>Every datum that a leak could show carries a marker unique to its site
 * ({@code SITEB-...}), so that the tests can search the output of a legacy
 * servlet for another site's data without parsing a JSP.
 */
final class IsolationFixture {

    /** The roles an external site user holds; the value is the study_user_role.role_name. */
    static final String INVESTIGATOR = "Investigator";
    static final String COORDINATOR = "coordinator";
    static final String MONITOR = "monitor";
    static final String RESEARCH_ASSISTANT = "ra";

    static final List<String> SITE_ROLES = List.of(INVESTIGATOR, COORDINATOR, MONITOR, RESEARCH_ASSISTANT);

    /** One site's rows. */
    static final class Site {
        final String tag;
        int studyId;
        String oid;
        int personId;
        int studySubjectId;
        int eventId;
        int eventCrfId;
        int itemDataId;
        int itemNoteId;
        int subjectNoteId;
        int datasetId;
        int archivedFileId;

        Site(String tag) {
            this.tag = tag;
        }

        String label() {
            return "SITE" + tag + "-SUBJ-001";
        }

        String secondaryLabel() {
            return "SITE" + tag + "-SECONDARY";
        }

        String personUniqueId() {
            return "PERSON-SITE" + tag;
        }

        String itemValue() {
            return "ITEMVAL-SITE" + tag;
        }

        String noteText() {
            return "DNTEXT-SITE" + tag;
        }

        String noteDetail() {
            return "DNDETAIL-SITE" + tag;
        }

        String datasetName() {
            return "DSNAME-SITE" + tag;
        }

        String fileName() {
            return "FILENAME-SITE" + tag + ".csv";
        }

        String fileReference() {
            return "/tmp/FILEREF-SITE" + tag + ".csv";
        }

        String siteName() {
            return "SITE" + tag + "-CLINIC";
        }

        String principalInvestigator() {
            return "PI-SITE" + tag;
        }

        /** Date of birth, as ISO text; A is 1972-02-02, B 1961-11-30. */
        String dob() {
            return tag.endsWith("A") ? "1972-02-02" : "1961-11-30";
        }

        /** Strings that, found in another site's output, are that site's data. */
        List<String> markers() {
            return List.of(label(), secondaryLabel(), personUniqueId(), itemValue(), noteText(), noteDetail(),
                    datasetName(), principalInvestigator(), dob(), fileName(), fileReference(),
                    userName(INVESTIGATOR));
        }

        String userName(String role) {
            return "iso_" + role.toLowerCase() + "_" + tag.toLowerCase();
        }
    }

    final Site a;
    final Site b;

    private IsolationFixture(String prefix) {
        a = new Site(prefix + "A");
        b = new Site(prefix + "B");
    }
    /** The parent (Default Study) and a third site of the same study, with no users of the fixture. */
    final int parentStudyId = 1;

    /** Seeds the two sites and the users of site A (and, symmetric, one investigator of site B). */
    static IsolationFixture seed(DataSource ds, String prefix) throws SQLException {
        IsolationFixture f = new IsolationFixture(prefix);
        try (Connection c = ds.getConnection()) {
            for (Site s : new Site[] { f.a, f.b }) {
                seedSite(c, s);
            }
            for (String role : SITE_ROLES) {
                user(c, f.a.userName(role), f.a.studyId, role);
            }
            user(c, f.b.userName(INVESTIGATOR), f.b.studyId, INVESTIGATOR);
        }
        return f;
    }

    private static void seedSite(Connection c, Site s) throws SQLException {
        String tag = s.tag;
        s.studyId = one(c, "INSERT INTO study (parent_study_id, unique_identifier, secondary_identifier, name, summary,"
                + " date_planned_start, date_planned_end, date_created, owner_id, type_id, status_id,"
                + " principal_investigator, facility_name, facility_city, facility_state, facility_zip,"
                + " facility_country, facility_recruitment_status, facility_contact_name, facility_contact_degree,"
                + " facility_contact_phone, facility_contact_email, protocol_type, protocol_description,"
                + " protocol_date_verification, phase, expected_total_enrollment, sponsor, collaborators,"
                + " medline_identifier, url, url_description, conditions, keywords, eligibility, gender, age_max,"
                + " age_min, healthy_volunteer_accepted, purpose, allocation, masking, control, assignment,"
                + " endpoint, interventions, duration, selection, timing, official_title, results_reference, oc_oid)"
                + " VALUES (1, 'ISO-" + tag + "', 'ISO-" + tag + "', '" + s.siteName() + "', '', NOW(), NOW(), NOW(),"
                + " 1, 1, 1, '" + s.principalInvestigator() + "', '', '', '', '', '', '', '', '', '', '',"
                + " 'observational', '', NOW(), 'default', 0, 'default', '', '', '', '', '', '', '', 'both', '', '',"
                + " false, 'Natural History', '', '', '', '', '', '', 'longitudinal', 'Convenience Sample',"
                + " 'Retrospective', '', false, 'S_ISO" + tag + "') RETURNING study_id");
        s.oid = "S_ISO" + tag;
        s.personId = one(c, "INSERT INTO subject (status_id, gender, unique_identifier, date_of_birth, date_created,"
                + " owner_id, dob_collected) VALUES (1, 'f', '" + s.personUniqueId() + "', '" + s.dob()
                + "', now(), 1, true) RETURNING subject_id");
        s.studySubjectId = one(c, "INSERT INTO study_subject (label, secondary_label, subject_id, study_id, status_id,"
                + " enrollment_date, date_created, owner_id, oc_oid) VALUES ('" + s.label() + "', '"
                + s.secondaryLabel() + "', " + s.personId + ", " + s.studyId + ", 1, '2026-01-15', now(), 1,"
                + " 'SS_ISO" + tag + "') RETURNING study_subject_id");
        s.eventId = one(c, "INSERT INTO study_event (study_event_definition_id, study_subject_id, sample_ordinal,"
                + " date_start, owner_id, status_id, date_created, subject_event_status_id, start_time_flag,"
                + " end_time_flag, location) VALUES (2, " + s.studySubjectId + ", 1, now(), 1, 1, now(), 3, false,"
                + " false, 'LOC-SITE" + tag + "') RETURNING study_event_id");
        s.eventCrfId = one(c, "INSERT INTO event_crf (study_event_id, crf_version_id, date_interviewed,"
                + " completion_status_id, status_id, date_completed, owner_id, date_created, study_subject_id,"
                + " electronic_signature_status, sdv_status) VALUES (" + s.eventId + ", 1, now(), 1, 1, now(), 1,"
                + " now(), " + s.studySubjectId + ", false, false) RETURNING event_crf_id");
        // Item 2 (I_CONSENT_SIGNED) is text; items 3..5 are numeric.
        s.itemDataId = one(c, "INSERT INTO item_data (item_id, event_crf_id, status_id, value, date_created, owner_id,"
                + " ordinal, deleted) VALUES (2, " + s.eventCrfId + ", 1, '" + s.itemValue() + "', now(), 1, 1,"
                + " false) RETURNING item_data_id");
        exec(c, "INSERT INTO item_data (item_id, event_crf_id, status_id, value, date_created, owner_id, ordinal,"
                + " deleted) VALUES (3, " + s.eventCrfId + ", 1, '" + (tag.endsWith("A") ? "171" : "188")
                + "', now(), 1, 1, false)");
        s.itemNoteId = one(c, "INSERT INTO discrepancy_note (description, detailed_notes, discrepancy_note_type_id,"
                + " resolution_status_id, date_created, owner_id, entity_type, study_id) VALUES ('" + s.noteText()
                + "', '" + s.noteDetail() + "', 3, 1, now(), 1, 'itemData', " + s.studyId
                + ") RETURNING discrepancy_note_id");
        exec(c, "INSERT INTO dn_item_data_map (item_data_id, discrepancy_note_id, column_name, activated) VALUES ("
                + s.itemDataId + ", " + s.itemNoteId + ", 'value', true)");
        s.subjectNoteId = one(c, "INSERT INTO discrepancy_note (description, detailed_notes, discrepancy_note_type_id,"
                + " resolution_status_id, date_created, owner_id, entity_type, study_id) VALUES ('" + s.noteText()
                + "-SUBJ', '" + s.noteDetail() + "', 3, 1, now(), 1, 'studySub', " + s.studyId
                + ") RETURNING discrepancy_note_id");
        exec(c, "INSERT INTO dn_study_subject_map (study_subject_id, discrepancy_note_id, column_name) VALUES ("
                + s.studySubjectId + ", " + s.subjectNoteId + ", 'enrollment_date')");
        s.datasetId = one(c, "INSERT INTO dataset (study_id, status_id, name, description, sql_statement, num_runs,"
                + " date_created, owner_id) VALUES (" + s.studyId + ", 1, '" + s.datasetName() + "', 'DSDESC-SITE"
                + tag + "', '', 0, now(), 1) RETURNING dataset_id");
        s.archivedFileId = one(c, "INSERT INTO archived_dataset_file (name, dataset_id, export_format_id,"
                + " file_reference, run_time, file_size, date_created, owner_id) VALUES ('" + s.fileName() + "', "
                + s.datasetId + ", 1, '" + s.fileReference() + "', 1, 10, now(), 1) RETURNING archived_dataset_file_id");
    }

    /** A user with {@code role} on {@code studyId} only, whose active study is that study. */
    private static void user(Connection c, String name, int studyId, String role) throws SQLException {
        exec(c, "INSERT INTO user_account (user_name, passwd, first_name, last_name, email, active_study,"
                + " institutional_affiliation, status_id, owner_id, date_created, user_type_id, enabled,"
                + " account_non_locked, lock_counter, run_webservices, authtype, enable_api_key, passwd_timestamp)"
                + " VALUES ('" + name + "', 'x', 'F', 'L', '" + name + "@example.invalid', " + studyId
                + ", 'EXTERNAL', 1, 1, now(), 2, true, true, 0, false, 'STANDARD', false, now())");
        exec(c, "INSERT INTO study_user_role (role_name, study_id, status_id, owner_id, date_created, user_name)"
                + " VALUES ('" + role + "', " + studyId + ", 1, 1, now(), '" + name + "')");
    }

    static int one(Connection c, String sql) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    static void exec(Connection c, String sql) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }

    static int query(DataSource ds, String sql) throws SQLException {
        try (Connection c = ds.getConnection()) {
            return one(c, sql);
        }
    }

    static String text(DataSource ds, String sql) throws SQLException {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    static void update(DataSource ds, String sql) throws SQLException {
        try (Connection c = ds.getConnection()) {
            exec(c, sql);
        }
    }
}
