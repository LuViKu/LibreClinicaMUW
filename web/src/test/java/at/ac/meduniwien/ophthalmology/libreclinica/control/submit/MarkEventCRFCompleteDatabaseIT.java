/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.control.submit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Date;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.LegacyServletHarness;
import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.AbstractApiControllerDatabaseIT;
import at.ac.meduniwien.ophthalmology.libreclinica.core.SecurityManager;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.RuleSetDao;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.login.UserAccountDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyEventDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.service.crfdata.DynamicsMetadataService;
import at.ac.meduniwien.ophthalmology.libreclinica.service.rule.StudyEventBeanListener;

/**
 * Who may mark an event CRF complete through {@code /MarkEventCRFComplete},
 * against the real schema. Marking a CRF whose initial entry is complete, on a
 * double-entry definition, completes it without its second entry; only the
 * data-entry roles may do that, and only for an event CRF of the current study.
 * <p>
 * Each test adds the event CRF it works on, at M-007's third visit (event 21,
 * no CRF yet), with its definition CRF switched to double entry, and removes
 * both again.
 */
class MarkEventCRFCompleteDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static final int SUBJECT = 7;
    private static final int EVENT = 21;
    private static final int DEFINITION_CRF = 3;
    private static final int VERSION = 1;
    /** EIAMD139 and its imaging-visit definition, in study 102. */
    private static final int OTHER_STUDY_SUBJECT = 102;
    private static final int OTHER_STUDY_DEFINITION = 10;

    private static final int UNAVAILABLE = 2;
    /** Initial data entry complete; with no validator yet, double entry has not started. */
    private static final int PENDING = 4;
    private static final int NOT_SCHEDULED = 2;
    private static final int DATA_ENTRY_STARTED = 3;

    private static final String REFUSAL = "/WEB-INF/jsp/menu.jsp";

    private LegacyServletHarness harness;
    private ApplicationContext savedListenerContext;
    private boolean doubleEntryBefore;

    @BeforeEach
    void setUp() throws Exception {
        update("UPDATE study_event SET date_start = date_created WHERE study_event_id = " + EVENT + " AND date_start IS NULL");
        doubleEntryBefore = queryInt("SELECT CASE WHEN double_entry THEN 1 ELSE 0 END FROM event_definition_crf"
                + " WHERE event_definition_crf_id = " + DEFINITION_CRF) == 1;
        update("UPDATE event_definition_crf SET double_entry = true WHERE event_definition_crf_id = " + DEFINITION_CRF);
        // StudyEventDAO.update notifies a rule listener that reads its rule sets
        // from the Spring context; give it one with none.
        GenericApplicationContext rules = new GenericApplicationContext();
        rules.registerBean("ruleSetDao", RuleSetDao.class, () -> mock(RuleSetDao.class));
        rules.refresh();
        Field field = StudyEventBeanListener.class.getDeclaredField("cntxt");
        field.setAccessible(true);
        savedListenerContext = (ApplicationContext) field.get(null);
        new StudyEventBeanListener(new StudyEventDAO(DATA_SOURCE)).setApplicationContext(rules);
        harness = new LegacyServletHarness(DATA_SOURCE)
                .bean("securityManager", mock(SecurityManager.class))
                .bean("mailSender", mock(JavaMailSenderImpl.class))
                .bean("dynamicsMetadataService", mock(DynamicsMetadataService.class));
    }

    @AfterEach
    void tearDown() throws Exception {
        update("UPDATE event_definition_crf SET double_entry = " + doubleEntryBefore + " WHERE event_definition_crf_id = " + DEFINITION_CRF);
        Field field = StudyEventBeanListener.class.getDeclaredField("cntxt");
        field.setAccessible(true);
        field.set(null, savedListenerContext);
    }

    @Test
    void aMonitorCannotCompleteACrfAwaitingItsSecondEntry() throws Exception {
        int ec = addEventCrf(EVENT, SUBJECT);
        try {
            MockHttpServletResponse resp = markComplete(user("manual_monitor"), ec);

            assertEquals(PENDING, status(ec), "the CRF still awaits its second entry");
            assertNull(dateValidateCompleted(ec));
            assertEquals(REFUSAL, resp.getForwardedUrl());
        } finally {
            removeEventCrf(ec);
        }
    }

    @Test
    void aCoordinatorStillCompletesIt() throws Exception {
        int ec = addEventCrf(EVENT, SUBJECT);
        try {
            MockHttpServletResponse resp = markComplete(user("manual_crc"), ec);

            assertEquals(UNAVAILABLE, status(ec), "the CRF is complete");
            assertNotEquals(REFUSAL, resp.getForwardedUrl());
        } finally {
            removeEventCrf(ec);
        }
    }

    @Test
    void anEventCrfOfAnotherStudyIsRefused() throws Exception {
        // manual_crc is coordinator in study 1; this event CRF is study 102's.
        int event = queryInt("INSERT INTO study_event (study_event_definition_id, study_subject_id, sample_ordinal,"
                + " date_start, owner_id, status_id, date_created, subject_event_status_id, start_time_flag, end_time_flag)"
                + " VALUES (" + OTHER_STUDY_DEFINITION + ", " + OTHER_STUDY_SUBJECT + ", 99, now(), 1, 1, now(), "
                + DATA_ENTRY_STARTED + ", false, false) RETURNING study_event_id");
        int ec = addEventCrf(event, OTHER_STUDY_SUBJECT);
        try {
            MockHttpServletResponse resp = markComplete(user("manual_crc"), ec);

            assertEquals(PENDING, status(ec), "the CRF is as it was");
            assertNull(dateValidateCompleted(ec));
            assertEquals("/MainMenu", resp.getForwardedUrl());
        } finally {
            removeEventCrf(ec);
            update("DELETE FROM study_event WHERE study_event_id = " + event);
        }
    }

    /** Guards the fixture: the rows the tests rely on exist and are as described. */
    @Test
    void theFixtureRowsExist() throws Exception {
        assertEquals(0, queryInt("SELECT COUNT(*) FROM event_crf WHERE study_event_id = " + EVENT), "event 21 has no CRF yet");
        assertEquals(SUBJECT, queryInt("SELECT study_subject_id FROM study_event WHERE study_event_id = " + EVENT));
        assertEquals(queryInt("SELECT study_event_definition_id FROM study_event WHERE study_event_id = " + EVENT),
                queryInt("SELECT study_event_definition_id FROM event_definition_crf WHERE event_definition_crf_id = " + DEFINITION_CRF));
        assertEquals(1, queryInt("SELECT COUNT(*) FROM event_definition_crf WHERE event_definition_crf_id = " + DEFINITION_CRF
                + " AND crf_id = (SELECT crf_id FROM crf_version WHERE crf_version_id = " + VERSION + ")"));
        assertEquals(102, queryInt("SELECT study_id FROM study_subject WHERE study_subject_id = " + OTHER_STUDY_SUBJECT));
        assertEquals(0, queryInt("SELECT COUNT(*) FROM study_user_role WHERE user_name = 'manual_crc' AND study_id = 102"));
    }

    // ---- helpers ------------------------------------------------------------------------------

    private MockHttpServletResponse markComplete(UserAccountBean user, int eventCrfId) throws Exception {
        MockHttpServletRequest req = harness.request("POST", "/MarkEventCRFComplete", user);
        req.addParameter("eventCRFId", String.valueOf(eventCrfId));
        req.addParameter("submitted", "1");
        req.addParameter("markComplete", "Yes");
        return harness.run(new MarkEventCRFCompleteServlet(), req);
    }

    /** A Demographics event CRF whose initial entry is complete, with an interviewer. */
    private static int addEventCrf(int event, int subject) throws SQLException {
        return queryInt("INSERT INTO event_crf (study_event_id, crf_version_id, completion_status_id, status_id, owner_id,"
                + " date_created, date_completed, study_subject_id, electronic_signature_status, sdv_status, interviewer_name)"
                + " VALUES (" + event + ", " + VERSION + ", 1, " + PENDING + ", 1, now(), now(), " + subject
                + ", false, false, 'IT interviewer') RETURNING event_crf_id");
    }

    private static void removeEventCrf(int eventCrfId) throws SQLException {
        update("DELETE FROM item_data WHERE event_crf_id = " + eventCrfId);
        update("DELETE FROM event_crf WHERE event_crf_id = " + eventCrfId);
        update("UPDATE study_event SET subject_event_status_id = " + NOT_SCHEDULED + " WHERE study_event_id = " + EVENT);
    }

    private static int status(int eventCrfId) throws SQLException {
        return queryInt("SELECT status_id FROM event_crf WHERE event_crf_id = " + eventCrfId);
    }

    private static String dateValidateCompleted(int eventCrfId) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT date_validate_completed FROM event_crf WHERE event_crf_id = " + eventCrfId);
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            return rs.getString(1);
        }
    }

    /** A user with the roles login would load. */
    private static UserAccountBean user(String name) {
        UserAccountDAO dao = new UserAccountDAO(DATA_SOURCE);
        UserAccountBean ub = dao.findByUserName(name);
        for (StudyUserRoleBean role : dao.findAllRolesByUserName(name)) {
            ub.addRole(role);
        }
        ub.setPasswdTimestamp(new Date());
        return ub;
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
