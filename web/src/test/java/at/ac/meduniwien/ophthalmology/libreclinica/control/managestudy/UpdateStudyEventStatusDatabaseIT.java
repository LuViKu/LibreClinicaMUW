/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.SubjectEventStatus;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.LegacyServletHarness;
import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.AbstractApiControllerDatabaseIT;
import at.ac.meduniwien.ophthalmology.libreclinica.core.SecurityManager;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.RuleSetDao;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.login.UserAccountDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyEventDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.service.rule.StudyEventBeanListener;

/**
 * The status {@code /UpdateStudyEvent} saves, against the real schema. The
 * form offers each role only some statuses: signing is the investigator's,
 * locking a coordinator's or director's, and an event whose required CRFs are
 * not complete cannot be completed or locked. A posted status outside that list
 * is refused and the event stays as it was; so is a signature confirmed by a
 * role that is not offered signing, and an event outside the current study.
 * <p>
 * The event is M-007's "V3 Day 90" (event 21): not scheduled, with no CRF,
 * so the form never offers "completed" or "locked" for it.
 */
class UpdateStudyEventStatusDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static final int SUBJECT = 7;
    private static final int EVENT = 21;
    /** EIAMD139, a subject of study 102. */
    private static final int OTHER_STUDY_SUBJECT = 102;

    private static final int NOT_SCHEDULED = SubjectEventStatus.NOT_SCHEDULED.getId();
    private static final String REFUSAL = "/MainMenu";

    private LegacyServletHarness harness;
    private SecurityManager securityManager;
    private ApplicationContext savedListenerContext;

    @BeforeEach
    void setUp() throws Exception {
        update("UPDATE study_event SET date_start = date_created WHERE study_event_id = " + EVENT + " AND date_start IS NULL");
        // StudyEventDAO.update notifies a rule listener that reads its rule sets
        // from the Spring context; give it one with none.
        GenericApplicationContext rules = new GenericApplicationContext();
        rules.registerBean("ruleSetDao", RuleSetDao.class, () -> mock(RuleSetDao.class));
        rules.refresh();
        Field field = StudyEventBeanListener.class.getDeclaredField("cntxt");
        field.setAccessible(true);
        savedListenerContext = (ApplicationContext) field.get(null);
        new StudyEventBeanListener(new StudyEventDAO(DATA_SOURCE)).setApplicationContext(rules);
        // Every password is the right one: the tests are about who may sign.
        securityManager = mock(SecurityManager.class);
        when(securityManager.verifyPassword(any(), any())).thenReturn(true);
        harness = new LegacyServletHarness(DATA_SOURCE)
                .bean("securityManager", securityManager)
                .bean("mailSender", mock(JavaMailSenderImpl.class));
    }

    @AfterEach
    void tearDown() throws Exception {
        SecurityContextHolder.clearContext();
        update("UPDATE study_event SET subject_event_status_id = " + NOT_SCHEDULED + ", status_id = 1"
                + " WHERE study_event_id = " + EVENT);
        update("UPDATE study_subject SET status_id = 1 WHERE study_subject_id = " + SUBJECT);
        update("UPDATE study_user_role SET role_name = 'coordinator' WHERE user_name = 'manual_crc' AND study_id = 1");
        Field field = StudyEventBeanListener.class.getDeclaredField("cntxt");
        field.setAccessible(true);
        field.set(null, savedListenerContext);
    }

    @Test
    void aResearchAssistantCannotLockAnEvent() throws Exception {
        update("UPDATE study_user_role SET role_name = 'ra' WHERE user_name = 'manual_crc' AND study_id = 1");

        MockHttpServletResponse resp = submit(user("manual_crc"), SUBJECT, EVENT, SubjectEventStatus.LOCKED);

        assertEquals(NOT_SCHEDULED, eventStatus());
        assertEquals(REFUSAL, resp.getForwardedUrl());
    }

    @Test
    void aResearchAssistantCannotSignAnEvent() throws Exception {
        update("UPDATE study_user_role SET role_name = 'ra' WHERE user_name = 'manual_crc' AND study_id = 1");
        UserAccountBean ra = user("manual_crc");
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(ra.getName(), null));

        MockHttpServletRequest submit = request(ra, "action", "submit", "event_id", "" + EVENT, "ss_id", "" + SUBJECT,
                "statusId", "" + SubjectEventStatus.SIGNED.getId(), "startDate", "15-Jan-2026", "startHour", "-1",
                "startMinute", "-1", "startHalf", "", "location", "");
        harness.run(new UpdateStudyEventServlet(), submit);
        MockHttpServletRequest confirm = request(ra, "action", "confirm", "event_id", "" + EVENT, "ss_id", "" + SUBJECT,
                "j_user", ra.getName(), "j_pass", "any");
        confirm.setSession(submit.getSession());
        MockHttpServletResponse resp = harness.run(new UpdateStudyEventServlet(), confirm);

        assertEquals(NOT_SCHEDULED, eventStatus(), "the event is not signed");
        assertEquals(REFUSAL, resp.getForwardedUrl());
    }

    @Test
    void anEventCannotBeCompletedWithoutItsCrfs() throws Exception {
        MockHttpServletResponse resp = submit(user("manual_crc"), SUBJECT, EVENT, SubjectEventStatus.COMPLETED);

        assertEquals(NOT_SCHEDULED, eventStatus());
        assertEquals(REFUSAL, resp.getForwardedUrl());
    }

    @Test
    void aCoordinatorStillSkipsAnEvent() throws Exception {
        MockHttpServletResponse resp = submit(user("manual_crc"), SUBJECT, EVENT, SubjectEventStatus.SKIPPED);

        assertEquals(SubjectEventStatus.SKIPPED.getId(), eventStatus());
        assertNotEquals(REFUSAL, resp.getForwardedUrl());
    }

    @Test
    void anInvestigatorStillSignsAnEvent() throws Exception {
        UserAccountBean investigator = user("manual_investigator");
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(investigator.getName(), null));

        MockHttpServletRequest submit = request(investigator, "action", "submit", "event_id", "" + EVENT, "ss_id", "" + SUBJECT,
                "statusId", "" + SubjectEventStatus.SIGNED.getId(), "startDate", "15-Jan-2026", "startHour", "-1",
                "startMinute", "-1", "startHalf", "", "location", "");
        harness.run(new UpdateStudyEventServlet(), submit);
        MockHttpServletRequest confirm = request(investigator, "action", "confirm", "event_id", "" + EVENT,
                "ss_id", "" + SUBJECT, "j_user", investigator.getName(), "j_pass", "any");
        confirm.setSession(submit.getSession());
        harness.run(new UpdateStudyEventServlet(), confirm);

        assertEquals(SubjectEventStatus.SIGNED.getId(), eventStatus());
    }

    @Test
    void anEventOfAnotherSubjectIsRefused() throws Exception {
        // ss_id names M-001; the event is M-007's.
        MockHttpServletResponse resp = submit(user("manual_crc"), 1, EVENT, SubjectEventStatus.SKIPPED);

        assertEquals(NOT_SCHEDULED, eventStatus());
        assertEquals(REFUSAL, resp.getForwardedUrl());
    }

    @Test
    void aSubjectOfAnotherStudyIsRefused() throws Exception {
        int event = queryInt("SELECT MIN(study_event_id) FROM study_event WHERE study_subject_id = " + OTHER_STUDY_SUBJECT);
        int before = queryInt("SELECT subject_event_status_id FROM study_event WHERE study_event_id = " + event);

        MockHttpServletResponse resp = submit(user("manual_crc"), OTHER_STUDY_SUBJECT, event, SubjectEventStatus.STOPPED);

        assertEquals(before, queryInt("SELECT subject_event_status_id FROM study_event WHERE study_event_id = " + event));
        assertEquals(REFUSAL, resp.getForwardedUrl());
    }

    /** Guards the fixture: the rows the tests rely on exist and are as described. */
    @Test
    void theFixtureRowsExist() throws Exception {
        assertEquals(NOT_SCHEDULED, eventStatus());
        assertEquals(SUBJECT, queryInt("SELECT study_subject_id FROM study_event WHERE study_event_id = " + EVENT));
        assertEquals(0, queryInt("SELECT COUNT(*) FROM event_crf WHERE study_event_id = " + EVENT));
        assertEquals(102, queryInt("SELECT study_id FROM study_subject WHERE study_subject_id = " + OTHER_STUDY_SUBJECT));
        assertTrue(queryInt("SELECT COUNT(*) FROM study_event WHERE study_subject_id = " + OTHER_STUDY_SUBJECT) > 0);
        assertEquals(1, queryInt("SELECT COUNT(*) FROM study_user_role WHERE user_name = 'manual_investigator'"
                + " AND study_id = 1 AND role_name = 'Investigator'"));
    }

    // ---- helpers ------------------------------------------------------------------------------

    private MockHttpServletResponse submit(UserAccountBean user, int studySubject, int event, SubjectEventStatus status)
            throws Exception {
        return harness.run(new UpdateStudyEventServlet(), request(user, "action", "submit", "event_id", "" + event,
                "ss_id", "" + studySubject, "statusId", "" + status.getId(), "startDate", "15-Jan-2026",
                "startHour", "-1", "startMinute", "-1", "startHalf", "", "location", ""));
    }

    private MockHttpServletRequest request(UserAccountBean user, String... params) {
        MockHttpServletRequest req = harness.request("POST", "/UpdateStudyEvent", user);
        for (int i = 0; i < params.length; i += 2) {
            req.addParameter(params[i], params[i + 1]);
        }
        return req;
    }

    private static int eventStatus() throws SQLException {
        return queryInt("SELECT subject_event_status_id FROM study_event WHERE study_event_id = " + EVENT);
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
