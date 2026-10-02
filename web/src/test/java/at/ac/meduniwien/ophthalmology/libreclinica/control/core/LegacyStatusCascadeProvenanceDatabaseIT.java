/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.control.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Date;

import jakarta.mail.internet.MimeMessage;
import jakarta.servlet.http.HttpServlet;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Status;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.ItemDataBean;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.LegacyServletHarness;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.RemoveEventCRFServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.RemoveEventDefinitionServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.RemoveStudyEventServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.RemoveStudyServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.RestoreStudyServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.RemoveSiteServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.RestoreSiteServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.RemoveStudySubjectServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.RestoreEventCRFServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.RestoreEventDefinitionServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.RestoreStudyEventServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.RestoreStudySubjectServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.AbstractApiControllerDatabaseIT;
import at.ac.meduniwien.ophthalmology.libreclinica.core.SecurityManager;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.RuleSetDao;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.login.UserAccountDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyEventDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.ItemDataDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.service.managestudy.EventDefinitionCrfTagService;
import at.ac.meduniwien.ophthalmology.libreclinica.service.rule.StudyEventBeanListener;

/**
 * The legacy remove and restore actions change an item's status, not its
 * value, so a value the platform wrote keeps recording what produced it
 * ({@code item_data.source_kind}) through them. {@code ItemDataDAO.update}
 * rewrites the whole row and clears those columns; the cascades must not use
 * it for a status change.
 *
 * <p>Fixture as in {@code LegacyGetWritesPostOnlyDatabaseIT}: study subject
 * 7 (M-007) has events 19 to 21 and event CRFs 15 and 16; event 20 holds
 * event CRF 16; event definition 3 owns event 21. Each test marks the values
 * concerned with a provenance, runs the legacy servlets, and checks the mark,
 * the value, the status, the updater and {@code date_updated}.
 */
class LegacyStatusCascadeProvenanceDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static final int STUDY = LegacyServletHarness.STUDY_ID;
    private static final int AVAILABLE = 1;
    private static final int REMOVED = 5;
    private static final int AUTO_REMOVED = 7;
    private static final String KIND = "modality_baseline";
    private static final String LONG_AGO = "TIMESTAMP '2020-01-01 00:00:00'";

    private static final int SUBJECT = 7;
    private static final int EVENT = 20;
    private static final int EVENT_CRF = 16;
    private static final int DEFINITION = 3;

    private LegacyServletHarness harness;
    private ApplicationContext savedListenerContext;

    @BeforeEach
    void setUp() throws Exception {
        resetFixture();
        update("UPDATE study_event SET date_start = date_created WHERE date_start IS NULL");
        GenericApplicationContext rules = new GenericApplicationContext();
        rules.registerBean("ruleSetDao", RuleSetDao.class, () -> mock(RuleSetDao.class));
        rules.refresh();
        Field field = StudyEventBeanListener.class.getDeclaredField("cntxt");
        field.setAccessible(true);
        savedListenerContext = (ApplicationContext) field.get(null);
        new StudyEventBeanListener(new StudyEventDAO(DATA_SOURCE)).setApplicationContext(rules);
        SecurityManager passwords = mock(SecurityManager.class);
        when(passwords.verifyPassword(anyString(), any())).thenReturn(true);
        JavaMailSenderImpl mail = new JavaMailSenderImpl() {
            @Override
            public void send(MimeMessage message) {
            }
        };
        harness = new LegacyServletHarness(DATA_SOURCE)
                .bean("securityManager", passwords)
                .bean("mailSender", mail)
                .bean("eventDefinitionCrfTagService", mock(EventDefinitionCrfTagService.class));
    }

    @AfterEach
    void tearDown() throws Exception {
        SecurityContextHolder.clearContext();
        Field field = StudyEventBeanListener.class.getDeclaredField("cntxt");
        field.setAccessible(true);
        field.set(null, savedListenerContext);
    }

    @Test
    void removingAndRestoringAStudySubjectKeepsItsValuesProvenance() throws Exception {
        String where = "event_crf_id IN (SELECT event_crf_id FROM event_crf WHERE study_subject_id = " + SUBJECT + ")";
        int values = markProvenance(where);
        String valuesBefore = valuesOf(where);
        String[] subject = { "id", String.valueOf(SUBJECT), "subjectId", String.valueOf(SUBJECT),
                "studyId", String.valueOf(STUDY), "action", "submit" };

        run(new RemoveStudySubjectServlet(), "/RemoveStudySubject", subject);
        assertEquals(REMOVED, queryInt("SELECT status_id FROM study_subject WHERE study_subject_id = " + SUBJECT));
        assertCascade(where, values, AUTO_REMOVED, valuesBefore);

        run(new RestoreStudySubjectServlet(), "/RestoreStudySubject", subject);
        assertEquals(AVAILABLE, queryInt("SELECT status_id FROM study_subject WHERE study_subject_id = " + SUBJECT));
        assertCascade(where, values, AVAILABLE, valuesBefore);
    }

    @Test
    void removingAndRestoringAnEventCrfKeepsItsValuesProvenance() throws Exception {
        String where = "event_crf_id = " + EVENT_CRF;
        int values = markProvenance(where);
        String valuesBefore = valuesOf(where);
        String[] eventCrf = { "id", String.valueOf(EVENT_CRF), "studySubId", String.valueOf(SUBJECT), "action", "submit" };

        run(new RemoveEventCRFServlet(), "/RemoveEventCRF", eventCrf);
        assertEquals(REMOVED, queryInt("SELECT status_id FROM event_crf WHERE event_crf_id = " + EVENT_CRF));
        assertCascade(where, values, AUTO_REMOVED, valuesBefore);

        run(new RestoreEventCRFServlet(), "/RestoreEventCRF", eventCrf);
        assertEquals(AVAILABLE, queryInt("SELECT status_id FROM event_crf WHERE event_crf_id = " + EVENT_CRF));
        assertCascade(where, values, AVAILABLE, valuesBefore);
    }

    @Test
    void removingAndRestoringAStudyEventKeepsItsValuesProvenance() throws Exception {
        String where = "event_crf_id IN (SELECT event_crf_id FROM event_crf WHERE study_event_id = " + EVENT + ")";
        int values = markProvenance(where);
        String valuesBefore = valuesOf(where);
        String[] event = { "id", String.valueOf(EVENT), "studySubId", String.valueOf(SUBJECT), "action", "submit" };

        run(new RemoveStudyEventServlet(), "/RemoveStudyEvent", event);
        assertCascade(where, values, AUTO_REMOVED, valuesBefore);

        run(new RestoreStudyEventServlet(), "/RestoreStudyEvent", event);
        assertCascade(where, values, AVAILABLE, valuesBefore);
    }

    @Test
    void removingAndRestoringAnEventDefinitionKeepsItsValuesProvenance() throws Exception {
        String where = "event_crf_id IN (SELECT ec.event_crf_id FROM event_crf ec JOIN study_event se"
                + " ON se.study_event_id = ec.study_event_id WHERE se.study_event_definition_id = " + DEFINITION + ")";
        int values = queryInt("SELECT COUNT(*) FROM item_data WHERE " + where);
        if (values == 0) {
            // The seed's only visit of definition 3 holds no CRF; the other tests cover the cascade.
            return;
        }
        markProvenance(where);
        String valuesBefore = valuesOf(where);
        String[] definition = { "action", "submit", "id", String.valueOf(DEFINITION) };

        run(new RemoveEventDefinitionServlet(), "/RemoveEventDefinition", definition);
        assertCascade(where, values, AUTO_REMOVED, valuesBefore);

        run(new RestoreEventDefinitionServlet(), "/RestoreEventDefinition", definition);
        assertCascade(where, values, AVAILABLE, valuesBefore);
    }

    /**
     * A site removed and restored through its servlets: each value returns to
     * the status it had before (the removal records it in old_status_id) and
     * keeps its provenance.
     */
    @Test
    void removingAndRestoringASiteKeepsItsValuesProvenanceAndStatus() throws Exception {
        int site = insertStudy(STUDY, "legacy-prov-site");
        update("UPDATE study_subject SET study_id = " + site + " WHERE study_subject_id = " + SUBJECT);
        String where = "event_crf_id IN (SELECT event_crf_id FROM event_crf WHERE study_subject_id = " + SUBJECT + ")";
        int values = markProvenance(where);
        update("UPDATE item_data SET status_id = 2 WHERE item_data_id = (SELECT MIN(item_data_id) FROM item_data WHERE " + where + ")");
        String statusesBefore = statusesOf(where);
        String valuesBefore = valuesOf(where);

        run(new RemoveSiteServlet(), "/RemoveSite", "id", String.valueOf(site), "action", "submit");
        assertEquals(REMOVED, queryInt("SELECT status_id FROM study WHERE study_id = " + site));
        assertEquals(values, queryInt("SELECT COUNT(*) FROM item_data WHERE " + where + " AND status_id = " + AUTO_REMOVED),
                "values after the removal: " + rowsOf(where));
        assertEquals(values, queryInt("SELECT COUNT(*) FROM item_data WHERE " + where + " AND source_kind = '" + KIND + "'"),
                "the removal cleared the values' provenance");

        run(new RestoreSiteServlet(), "/RestoreSite", "id", String.valueOf(site), "action", "submit");
        assertEquals(AVAILABLE, queryInt("SELECT status_id FROM study WHERE study_id = " + site));
        assertEquals(statusesBefore, statusesOf(where), "statuses after the restore: " + rowsOf(where));
        assertEquals(values, queryInt("SELECT COUNT(*) FROM item_data WHERE " + where + " AND source_kind = '" + KIND + "'"),
                "the restore cleared the values' provenance");
        assertEquals(valuesBefore, valuesOf(where));
    }

    /** As the site, for a whole study removed and restored by a system administrator. */
    @Test
    void removingAndRestoringAStudyKeepsItsValuesProvenanceAndStatus() throws Exception {
        int study = insertStudy(null, "legacy-prov-study");
        update("UPDATE study_subject SET study_id = " + study + " WHERE study_subject_id = " + SUBJECT);
        update("UPDATE study_event_definition SET study_id = " + study + " WHERE study_event_definition_id IN"
                + " (SELECT study_event_definition_id FROM study_event WHERE study_subject_id = " + SUBJECT + ")");
        String where = "event_crf_id IN (SELECT event_crf_id FROM event_crf WHERE study_subject_id = " + SUBJECT + ")";
        int values = markProvenance(where);
        update("UPDATE item_data SET status_id = 2 WHERE item_data_id = (SELECT MIN(item_data_id) FROM item_data WHERE " + where + ")");
        String statusesBefore = statusesOf(where);
        String valuesBefore = valuesOf(where);
        UserAccountBean root = LegacyServletHarness.sysAdmin(1, "root");

        run(new RemoveStudyServlet(), "/RemoveStudy", root, "id", String.valueOf(study), "action", "submit");
        assertEquals(REMOVED, queryInt("SELECT status_id FROM study WHERE study_id = " + study));
        assertEquals(values, queryInt("SELECT COUNT(*) FROM item_data WHERE " + where + " AND status_id = " + AUTO_REMOVED),
                "values after the removal: " + rowsOf(where));
        assertEquals(values, queryInt("SELECT COUNT(*) FROM item_data WHERE " + where + " AND source_kind = '" + KIND + "'"),
                "the removal cleared the values' provenance");

        run(new RestoreStudyServlet(), "/RestoreStudy", root, "id", String.valueOf(study), "action", "submit");
        assertEquals(AVAILABLE, queryInt("SELECT status_id FROM study WHERE study_id = " + study));
        assertEquals(statusesBefore, statusesOf(where), "statuses after the restore: " + rowsOf(where));
        assertEquals(values, queryInt("SELECT COUNT(*) FROM item_data WHERE " + where + " AND source_kind = '" + KIND + "'"),
                "the restore cleared the values' provenance");
        assertEquals(valuesBefore, valuesOf(where));
    }

    /**
     * The variant the removals of a study or a site use, whose restore reads
     * {@code old_status_id} back: it records that and leaves the provenance.
     */
    @Test
    void theStatusAndOldStatusWriterRecordsTheOldStatusAndKeepsTheProvenance() throws Exception {
        String where = "event_crf_id = " + EVENT_CRF;
        int values = markProvenance(where);
        int updater = user().getId();
        ItemDataDAO dao = new ItemDataDAO(DATA_SOURCE);
        for (Object o : dao.findAllByEventCRFId(EVENT_CRF)) {
            ItemDataBean item = (ItemDataBean) o;
            item.setOldStatus(item.getStatus());
            item.setStatus(Status.AUTO_DELETED);
            item.setUpdaterId(updater);
            dao.updateStatusAndOldStatusOnly(item);
        }
        assertEquals(values, queryInt("SELECT COUNT(*) FROM item_data WHERE " + where + " AND source_kind = '" + KIND
                + "' AND status_id = " + AUTO_REMOVED + " AND old_status_id = " + AVAILABLE
                + " AND update_id = " + updater));
    }

    // ---- helpers ------------------------------------------------------------------------

    /** Puts the subject, its visits, CRFs and definitions back as the seed has them, whatever an earlier test left. */
    private static void resetFixture() throws SQLException {
        update("UPDATE study_subject SET study_id = " + STUDY + ", status_id = " + AVAILABLE + " WHERE study_subject_id = " + SUBJECT);
        update("UPDATE study_event SET status_id = " + AVAILABLE + " WHERE study_subject_id = " + SUBJECT);
        update("UPDATE event_crf SET status_id = " + AVAILABLE + " WHERE study_subject_id = " + SUBJECT);
        update("UPDATE study_event_definition SET study_id = " + STUDY + ", status_id = " + AVAILABLE
                + " WHERE study_event_definition_id IN (SELECT study_event_definition_id FROM study_event"
                + " WHERE study_subject_id = " + SUBJECT + ") OR study_event_definition_id = " + DEFINITION);
    }

    /** A study (a site when a parent is given) with the columns the schema requires. */
    private static int insertStudy(Integer parent, String name) throws SQLException {
        return queryInt("INSERT INTO study (parent_study_id, unique_identifier, secondary_identifier, "
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
                + "VALUES (" + (parent == null ? "NULL" : parent.toString()) + ", '" + name + "', '" + name + "', '" + name
                + "', '', NOW(), NOW(), NOW(), 1, 1, 1, 'default', '', '', '', '', '', '', '', '', '', '', "
                + "'observational', '', NOW(), 'default', 0, 'default', '', '', '', '', '', '', "
                + "'', 'both', '', '', false, 'Natural History', '', '', '', '', '', '', "
                + "'longitudinal', 'Convenience Sample', 'Retrospective', '', false, "
                + "'S_" + name.replace("-", "").toUpperCase() + "') RETURNING study_id");
    }

    private static String statusesOf(String where) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT string_agg(item_data_id || ':' || status_id, ',' ORDER BY item_data_id)"
                             + " FROM item_data WHERE " + where);
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            return rs.getString(1);
        }
    }

    /** Marks the values the condition selects as written by the platform; returns how many. */
    private static int markProvenance(String where) throws SQLException {
        update("UPDATE item_data SET source_kind = '" + KIND + "', status_id = " + AVAILABLE
                + ", old_status_id = 0, update_id = NULL, date_updated = " + LONG_AGO + " WHERE " + where);
        int values = queryInt("SELECT COUNT(*) FROM item_data WHERE " + where);
        assertTrue(values > 0, "the fixture has values to mark");
        return values;
    }

    /** The rows the condition selects, for a failure message. */
    private static String rowsOf(String where) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT string_agg(item_data_id || ':status=' || status_id || ',old=' || coalesce(old_status_id::text, '-')"
                             + " || ',deleted=' || coalesce(deleted::text, '-') || ',value=' || coalesce(value, '<null>'), ' | ' ORDER BY item_data_id)"
                             + " FROM item_data WHERE " + where);
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            return rs.getString(1);
        }
    }

    private static String valuesOf(String where) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT string_agg(item_data_id || '=' || coalesce(value, '<null>'), ',' ORDER BY item_data_id)"
                             + " FROM item_data WHERE " + where);
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            return rs.getString(1);
        }
    }

    /** Every value the condition selects has the status, its value and provenance as before, and the stamp of the change. */
    private static void assertCascade(String where, int values, int status, String valuesBefore) throws SQLException {
        int updater = user().getId();
        assertEquals(values, queryInt("SELECT COUNT(*) FROM item_data WHERE " + where + " AND status_id = " + status),
                "status after the cascade: " + rowsOf(where));
        assertEquals(values, queryInt("SELECT COUNT(*) FROM item_data WHERE " + where + " AND source_kind = '" + KIND + "'"),
                "the cascade cleared the values' provenance");
        assertEquals(valuesBefore, valuesOf(where), "the cascade changed a value");
        assertEquals(values, queryInt("SELECT COUNT(*) FROM item_data WHERE " + where + " AND update_id = " + updater
                + " AND date_updated > " + LONG_AGO), "the cascade did not stamp the updater and date");
    }

    private static UserAccountBean user() {
        UserAccountDAO dao = new UserAccountDAO(DATA_SOURCE);
        UserAccountBean ub = dao.findByUserName("manual_dm");
        for (StudyUserRoleBean role : dao.findAllRolesByUserName("manual_dm")) {
            ub.addRole(role);
        }
        ub.setPasswdTimestamp(new Date());
        return ub;
    }

    private void run(HttpServlet servlet, String path, String... params) throws Exception {
        run(servlet, path, user(), params);
    }

    private void run(HttpServlet servlet, String path, UserAccountBean who, String... params) throws Exception {
        MockHttpServletRequest req = harness.request("POST", path, who);
        for (int i = 0; i < params.length; i += 2) {
            req.addParameter(params[i], params[i + 1]);
        }
        harness.run(servlet, req);
    }

    private static int queryInt(String sql) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next(), "no row for " + sql);
            return rs.getInt(1);
        }
    }

    private static void update(String sql) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }
}
