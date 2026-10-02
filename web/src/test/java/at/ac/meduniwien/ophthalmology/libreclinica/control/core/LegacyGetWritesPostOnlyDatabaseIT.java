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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import jakarta.mail.internet.MimeMessage;
import jakarta.servlet.http.HttpServlet;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.control.MainMenuServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.LegacyServletHarness;
import at.ac.meduniwien.ophthalmology.libreclinica.control.login.ChangeStudyServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.ChangeDefinitionCRFOrdinalServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.ChangeDefinitionOrdinalServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.LockCRFVersionServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.RemoveEventCRFServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.RemoveEventDefinitionServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.RemoveStudyEventServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.RemoveStudySubjectServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.RemoveStudyUserRoleServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.RestoreEventCRFServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.RestoreEventDefinitionServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.RestoreStudyEventServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.RestoreStudySubjectServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.RestoreStudyUserRoleServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.SignStudySubjectServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.UnlockCRFVersionServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.submit.CreateDiscrepancyNoteServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.submit.CreateOneDiscrepancyNoteServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.AbstractApiControllerDatabaseIT;
import at.ac.meduniwien.ophthalmology.libreclinica.core.SecurityManager;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.RuleSetDao;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.login.UserAccountDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyEventDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.service.managestudy.EventDefinitionCrfTagService;
import at.ac.meduniwien.ophthalmology.libreclinica.service.rule.StudyEventBeanListener;
import at.ac.meduniwien.ophthalmology.libreclinica.web.SQLInitServlet;

/**
 * The legacy actions that changed data on a plain GET, against the real schema:
 * the GET now leaves every row as it was and answers 405, while the POST the
 * pages now send still writes. Covers the actions whose callers were GET links
 * (reordering, signing, the matrix and list actions behind confirmation pages),
 * the Monitor-reachable note and study-switch actions, and the home page, which
 * keeps answering GET but now records only the visit.
 * <p>
 * The seeded study 1 and its subjects M-001 to M-007 are the fixture; each test
 * puts back what it changes.
 */
class LegacyGetWritesPostOnlyDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static final int STUDY = LegacyServletHarness.STUDY_ID;
    private static final int AVAILABLE = 1;
    private static final int SIGNED = 8;
    private static final int LOCKED = 6;
    private static final int REMOVED = 5;
    private static final int AUTO_REMOVED = 7;

    /** M-007: study subject 7, events 19 to 21, event CRFs 15 and 16. */
    private static final int SUBJECT = 7;
    private static final int EVENT = 20;
    private static final int EVENT_CRF = 16;
    /** M-005: events 13 and 14 complete, 15 scheduled; nothing blocks signing. */
    private static final int SIGNABLE_SUBJECT = 5;

    private final List<MimeMessage> sent = new ArrayList<>();
    private SecurityManager passwords;
    private LegacyServletHarness harness;
    private ApplicationContext savedListenerContext;

    @BeforeEach
    void setUp() throws Exception {
        // Seed rows inserted by SQL lack what the legacy pages always set: a
        // scheduled event without a start date cannot be updated by
        // StudyEventDAO.
        update("UPDATE study_event SET date_start = date_created WHERE date_start IS NULL");
        // StudyEventDAO.update notifies a rule listener that reads its rule sets
        // from the Spring context; give it one with none.
        GenericApplicationContext rules = new GenericApplicationContext();
        rules.registerBean("ruleSetDao", RuleSetDao.class, () -> mock(RuleSetDao.class));
        rules.refresh();
        savedListenerContext = listenerContext();
        new StudyEventBeanListener(new StudyEventDAO(DATA_SOURCE)).setApplicationContext(rules);
        passwords = mock(SecurityManager.class);
        when(passwords.verifyPassword(anyString(), any())).thenReturn(true);
        JavaMailSenderImpl mail = new JavaMailSenderImpl() {
            @Override
            public void send(MimeMessage message) {
                sent.add(message);
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

    private static ApplicationContext listenerContext() throws ReflectiveOperationException {
        Field field = StudyEventBeanListener.class.getDeclaredField("cntxt");
        field.setAccessible(true);
        return (ApplicationContext) field.get(null);
    }

    // ---- reordering -----------------------------------------------------------------

    @Test
    void aGetDoesNotReorderEventDefinitionsAndAPostDoes() throws Exception {
        update("UPDATE study_event_definition SET ordinal = study_event_definition_id WHERE study_event_definition_id IN (1, 2)");
        try {
            MockHttpServletResponse get = run(new ChangeDefinitionOrdinalServlet(), "GET", "/ChangeDefinitionOrdinal",
                    director(), "current", "2", "previous", "1");

            assertEquals(1, definitionOrdinal(1));
            assertEquals(2, definitionOrdinal(2));
            assertEquals(405, get.getStatus());

            MockHttpServletResponse post = run(new ChangeDefinitionOrdinalServlet(), "POST", "/ChangeDefinitionOrdinal",
                    director(), "current", "2", "previous", "1");

            assertEquals(2, definitionOrdinal(1));
            assertEquals(1, definitionOrdinal(2));
            assertEquals("ListEventDefinition", post.getRedirectedUrl());
        } finally {
            update("UPDATE study_event_definition SET ordinal = study_event_definition_id WHERE study_event_definition_id IN (1, 2)");
        }
    }

    @Test
    void aGetDoesNotReorderTheCrfsOfADefinitionAndAPostDoes() throws Exception {
        int first = queryInt("SELECT event_definition_crf_id FROM event_definition_crf"
                + " WHERE study_event_definition_id = 1 AND crf_id = 1");
        int version = queryInt("SELECT MIN(crf_version_id) FROM crf_version WHERE crf_id = 2");
        update("INSERT INTO event_definition_crf (study_event_definition_id, study_id, crf_id, required_crf, double_entry,"
                + " default_version_id, status_id, owner_id, date_created, ordinal, source_data_verification_code)"
                + " VALUES (1, 1, 2, false, false, " + version + ", 1, 1, now(), 2, 1)");
        int second = queryInt("SELECT event_definition_crf_id FROM event_definition_crf"
                + " WHERE study_event_definition_id = 1 AND crf_id = 2");
        update("UPDATE event_definition_crf SET ordinal = 1 WHERE event_definition_crf_id = " + first);
        String[] move = { "current", String.valueOf(second), "previous", String.valueOf(first), "id", "1",
                "currentOrdinal", "2", "previousOrdinal", "1" };
        try {
            MockHttpServletResponse get = run(new ChangeDefinitionCRFOrdinalServlet(), "GET", "/ChangeDefinitionCRFOrdinal",
                    director(), move);

            assertEquals(1, crfOrdinal(first));
            assertEquals(2, crfOrdinal(second));
            assertEquals(405, get.getStatus());

            MockHttpServletResponse post = run(new ChangeDefinitionCRFOrdinalServlet(), "POST", "/ChangeDefinitionCRFOrdinal",
                    director(), move);

            assertEquals(2, crfOrdinal(first));
            assertEquals(1, crfOrdinal(second));
            assertEquals("/ViewEventDefinition", post.getForwardedUrl());
        } finally {
            update("DELETE FROM event_definition_crf WHERE event_definition_crf_id = " + second);
            update("UPDATE event_definition_crf SET ordinal = 1 WHERE event_definition_crf_id = " + first);
        }
    }

    // ---- remove and restore behind a confirmation page ---------------------------------

    @Test
    void aGetDoesNotRemoveTheStudySubjectAndThePostsRemoveAndRestoreIt() throws Exception {
        String[] subject = { "id", String.valueOf(SUBJECT), "subjectId", String.valueOf(SUBJECT), "studyId", String.valueOf(STUDY) };

        MockHttpServletResponse get = run(new RemoveStudySubjectServlet(), "GET", "/RemoveStudySubject", director(),
                with(subject, "action", "submit"));
        assertEquals(AVAILABLE, studySubjectStatus(SUBJECT));
        assertEquals(405, get.getStatus());

        MockHttpServletResponse confirm = run(new RemoveStudySubjectServlet(), "GET", "/RemoveStudySubject", director(),
                with(subject, "action", "confirm"));
        assertEquals("/WEB-INF/jsp/managestudy/removeStudySubject.jsp", confirm.getForwardedUrl());
        assertEquals(AVAILABLE, studySubjectStatus(SUBJECT));

        run(new RemoveStudySubjectServlet(), "POST", "/RemoveStudySubject", director(), with(subject, "action", "submit"));
        assertEquals(REMOVED, studySubjectStatus(SUBJECT));
        assertEquals(AUTO_REMOVED, queryInt("SELECT status_id FROM study_event WHERE study_event_id = " + EVENT));

        run(new RestoreStudySubjectServlet(), "POST", "/RestoreStudySubject", director(), with(subject, "action", "submit"));
        assertEquals(AVAILABLE, studySubjectStatus(SUBJECT));
        assertEquals(AVAILABLE, queryInt("SELECT status_id FROM study_event WHERE study_event_id = " + EVENT));
    }

    @Test
    void aGetDoesNotRemoveTheEventAndThePostsRemoveAndRestoreIt() throws Exception {
        String[] event = { "id", String.valueOf(EVENT), "studySubId", String.valueOf(SUBJECT) };

        MockHttpServletResponse get = run(new RemoveStudyEventServlet(), "GET", "/RemoveStudyEvent", director(),
                with(event, "action", "submit"));
        assertEquals(AVAILABLE, eventStatus(EVENT));
        assertEquals(405, get.getStatus());

        run(new RemoveStudyEventServlet(), "POST", "/RemoveStudyEvent", director(), with(event, "action", "submit"));
        assertEquals(REMOVED, eventStatus(EVENT));
        assertEquals(AUTO_REMOVED, eventCrfStatus(EVENT_CRF));

        run(new RestoreStudyEventServlet(), "POST", "/RestoreStudyEvent", director(), with(event, "action", "submit"));
        assertEquals(AVAILABLE, eventStatus(EVENT));
        assertEquals(AVAILABLE, eventCrfStatus(EVENT_CRF));
    }

    @Test
    void aGetDoesNotRemoveTheEventCrfAndThePostsRemoveAndRestoreIt() throws Exception {
        String[] eventCrf = { "id", String.valueOf(EVENT_CRF), "studySubId", String.valueOf(SUBJECT) };

        MockHttpServletResponse get = run(new RemoveEventCRFServlet(), "GET", "/RemoveEventCRF", director(),
                with(eventCrf, "action", "submit"));
        assertEquals(AVAILABLE, eventCrfStatus(EVENT_CRF));
        assertEquals(405, get.getStatus());

        run(new RemoveEventCRFServlet(), "POST", "/RemoveEventCRF", director(), with(eventCrf, "action", "submit"));
        assertEquals(REMOVED, eventCrfStatus(EVENT_CRF));

        run(new RestoreEventCRFServlet(), "POST", "/RestoreEventCRF", director(), with(eventCrf, "action", "submit"));
        assertEquals(AVAILABLE, eventCrfStatus(EVENT_CRF));
    }

    @Test
    void aGetDoesNotRemoveTheEventDefinitionAndThePostsRemoveAndRestoreIt() throws Exception {
        MockHttpServletResponse get = run(new RemoveEventDefinitionServlet(), "GET", "/RemoveEventDefinition", director(),
                "id", "3");
        assertEquals(AVAILABLE, definitionStatus(3));
        assertEquals(405, get.getStatus());

        run(new RemoveEventDefinitionServlet(), "POST", "/RemoveEventDefinition", director(), "action", "submit", "id", "3");
        assertEquals(REMOVED, definitionStatus(3));
        assertEquals(AUTO_REMOVED, eventStatus(21), "the definition's events go with it");

        run(new RestoreEventDefinitionServlet(), "POST", "/RestoreEventDefinition", director(), "action", "submit", "id", "3");
        assertEquals(AVAILABLE, definitionStatus(3));
        assertEquals(AVAILABLE, eventStatus(21));
    }

    @Test
    void aGetDoesNotArchiveTheCrfVersionAndThePostsArchiveAndRestoreIt() throws Exception {
        MockHttpServletResponse get = run(new LockCRFVersionServlet(), "GET", "/LockCRFVersion", admin(),
                "action", "confirm", "id", "1");
        assertEquals(AVAILABLE, versionStatus(1));
        assertEquals(405, get.getStatus());

        MockHttpServletResponse confirmation = run(new LockCRFVersionServlet(), "GET", "/LockCRFVersion", admin(), "id", "1");
        assertEquals("/WEB-INF/jsp/managestudy/confirmLockingCRFVersion.jsp", confirmation.getForwardedUrl());
        assertEquals(AVAILABLE, versionStatus(1));

        try {
            run(new LockCRFVersionServlet(), "POST", "/LockCRFVersion", admin(), "action", "confirm", "id", "1");
            assertEquals(LOCKED, versionStatus(1));

            run(new UnlockCRFVersionServlet(), "POST", "/UnlockCRFVersion", admin(), "action", "confirm", "id", "1");
            assertEquals(AVAILABLE, versionStatus(1));
        } finally {
            update("UPDATE crf_version SET status_id = 1 WHERE crf_version_id = 1");
        }
    }

    @Test
    void aGetDoesNotRemoveTheStudyRoleAndThePostsRemoveAndRestoreIt() throws Exception {
        String[] role = { "name", "physician", "studyId", String.valueOf(STUDY), "roleId", "4" };
        update("UPDATE study_user_role SET status_id = 1 WHERE user_name = 'physician'");

        MockHttpServletResponse get = run(new RemoveStudyUserRoleServlet(), "GET", "/RemoveStudyUserRole", director(), role);
        assertEquals(AVAILABLE, physicianRoleStatus());
        assertEquals(405, get.getStatus());

        run(new RemoveStudyUserRoleServlet(), "POST", "/RemoveStudyUserRole", director(), role);
        assertEquals(REMOVED, physicianRoleStatus());

        run(new RestoreStudyUserRoleServlet(), "POST", "/RestoreStudyUserRole", director(), role);
        assertEquals(AVAILABLE, physicianRoleStatus());
    }

    // ---- notes ---------------------------------------------------------------------------

    @Test
    void aGetDoesNotAddANoteThreadAndAPostDoes() throws Exception {
        String itemData = String.valueOf(queryInt("SELECT MIN(item_data_id) FROM item_data WHERE event_crf_id = 1"));
        String description = "IT note " + UUID.randomUUID();
        String[] note = { "parentId", "0", "name", "itemData", "id", itemData, "field", "input1", "column", "value",
                "description0", description, "detailedDes0", "", "typeId0", "3", "resStatusId0", "1",
                "viewDNLink0", "/ViewDiscrepancyNote?name=itemData&id=" + itemData };

        MockHttpServletResponse get = run(new CreateOneDiscrepancyNoteServlet(), "GET", "/CreateOneDiscrepancyNote",
                director(), note);
        assertEquals(0, notesDescribed(description));
        assertEquals(405, get.getStatus());

        run(new CreateOneDiscrepancyNoteServlet(), "POST", "/CreateOneDiscrepancyNote", director(), note);
        assertTrue(notesDescribed(description) > 0, "the POST added the thread");
    }

    @Test
    void aGetDoesNotSaveTheNoteFormAndAPostDoes() throws Exception {
        String itemData = String.valueOf(queryInt("SELECT MIN(item_data_id) FROM item_data WHERE event_crf_id = 1"));
        String description = "IT note " + UUID.randomUUID();
        String[] note = { "submitted", "1", "writeToDB", "1", "name", "itemData", "id", itemData, "field", "input1",
                "column", "value", "subjectId", "1", "description", description, "detailedDes", "", "typeId", "3",
                "resStatusId", "1" };

        MockHttpServletResponse form = run(new CreateDiscrepancyNoteServlet(), "GET", "/CreateDiscrepancyNote", director(),
                "name", "itemData", "id", itemData, "field", "input1", "column", "value", "subjectId", "1");
        assertNotEquals(405, form.getStatus(), "the note form still opens by GET");

        MockHttpServletResponse get = run(new CreateDiscrepancyNoteServlet(), "GET", "/CreateDiscrepancyNote", director(), note);
        assertEquals(0, notesDescribed(description));
        assertEquals(405, get.getStatus());

        run(new CreateDiscrepancyNoteServlet(), "POST", "/CreateDiscrepancyNote", director(), note);
        assertTrue(notesDescribed(description) > 0, "the POST saved the note");
    }

    // ---- signing -------------------------------------------------------------------------

    @Test
    void aGetDoesNotSignTheSubjectAndAPostSignsIt() throws Exception {
        authenticate("manual_dm");
        // Legacy marks a completed CRF with status 2 and signs only then; the
        // seed leaves M-005's completed CRFs at 1.
        update("UPDATE event_crf SET status_id = 2 WHERE event_crf_id IN (10, 11)");
        String[] credentials = { "id", String.valueOf(SIGNABLE_SUBJECT), "j_user", "manual_dm", "j_pass", "secret" };
        try {
            MockHttpServletResponse page = run(new SignStudySubjectServlet(), "GET", "/SignStudySubject", director(),
                    "id", String.valueOf(SIGNABLE_SUBJECT));
            assertEquals("/WEB-INF/jsp/managestudy/signStudySubject.jsp", page.getForwardedUrl());

            MockHttpServletResponse get = run(new SignStudySubjectServlet(), "GET", "/SignStudySubject", director(),
                    with(credentials, "action", "confirm"));
            assertEquals(AVAILABLE, studySubjectStatus(SIGNABLE_SUBJECT));
            assertEquals(0, queryInt("SELECT COUNT(*) FROM study_event WHERE study_subject_id = " + SIGNABLE_SUBJECT
                    + " AND subject_event_status_id = " + SIGNED));
            assertEquals(405, get.getStatus());

            run(new SignStudySubjectServlet(), "POST", "/SignStudySubject", director(), with(credentials, "action", "confirm"));
            assertEquals(SIGNED, studySubjectStatus(SIGNABLE_SUBJECT));
        } finally {
            update("UPDATE study_subject SET status_id = 1 WHERE study_subject_id = " + SIGNABLE_SUBJECT);
            update("UPDATE study_event SET subject_event_status_id = 4 WHERE study_event_id IN (13, 14)");
            update("UPDATE study_event SET subject_event_status_id = 1 WHERE study_event_id = 15");
            update("UPDATE event_crf SET status_id = 1 WHERE event_crf_id IN (10, 11)");
        }
    }

    // ---- switching the active study --------------------------------------------------------

    @Test
    void aGetDoesNotSwitchTheActiveStudyAndThePostsDo() throws Exception {
        int other = queryInt("SELECT MIN(study_id) FROM study WHERE study_id <> 1 AND parent_study_id IS NULL AND status_id = 1");
        update("INSERT INTO study_user_role (role_name, study_id, status_id, owner_id, date_created, user_name)"
                + " VALUES ('director', " + other + ", 1, 1, now(), 'manual_dm')");
        update("UPDATE user_account SET active_study = 1 WHERE user_name = 'manual_dm'");
        UserAccountBean dm = user("manual_dm");
        MockHttpSession session = new MockHttpSession();
        String studyId = String.valueOf(other);
        try {
            MockHttpServletResponse get = run(new ChangeStudyServlet(), "GET", "/ChangeStudy", dm, session,
                    "action", "submit", "studyId", studyId);
            assertEquals(1, activeStudy("manual_dm"));
            assertEquals(405, get.getStatus());

            run(new ChangeStudyServlet(), "POST", "/ChangeStudy", dm, session, "action", "confirm", "studyId", studyId);
            run(new ChangeStudyServlet(), "POST", "/ChangeStudy", dm, session, "action", "submit", "studyId", studyId);
            assertEquals(other, activeStudy("manual_dm"));
        } finally {
            update("DELETE FROM study_user_role WHERE user_name = 'manual_dm' AND study_id = " + other);
            update("UPDATE user_account SET active_study = 1 WHERE user_name = 'manual_dm'");
        }
    }

    // ---- the home page ---------------------------------------------------------------------

    @Test
    void theHomePageRecordsTheVisitWithoutRewritingTheAccount() throws Exception {
        Timestamp before = Timestamp.valueOf("2020-01-01 00:00:00");
        update("UPDATE user_account SET date_updated = '2020-01-01', update_id = 1, date_lastvisit = '2020-01-01'"
                + " WHERE user_name = 'manual_admin'");
        update("UPDATE study_user_role SET date_created = '2020-01-01' WHERE user_name = 'manual_admin' AND role_name = 'admin'");
        Properties params = sqlInitParams();
        params.setProperty("passwd_expiration_time", "360");
        params.setProperty("change_passwd_required", "1");
        try {
            MockHttpServletResponse resp = run(new MainMenuServlet(), "GET", "/MainMenu", user("manual_admin"));

            assertEquals("/WEB-INF/jsp/menu.jsp", resp.getForwardedUrl());
            assertTrue(timestamp("SELECT date_lastvisit FROM user_account WHERE user_name = 'manual_admin'").after(before),
                    "the visit is recorded");
            assertEquals(before, timestamp("SELECT date_updated FROM user_account WHERE user_name = 'manual_admin'"),
                    "the account's last change is not the visit");
            assertEquals(1, queryInt("SELECT update_id FROM user_account WHERE user_name = 'manual_admin'"));
            assertEquals(before, timestamp("SELECT date_created FROM study_user_role"
                    + " WHERE user_name = 'manual_admin' AND role_name = 'admin'"), "the admin role is left alone");
        } finally {
            params.remove("passwd_expiration_time");
            params.remove("change_passwd_required");
        }
    }

    // ---- helpers ---------------------------------------------------------------------------

    /** manual_dm: the director of study 1, with the roles login would load. */
    private static UserAccountBean director() {
        return user("manual_dm");
    }

    /** A system administrator whose roles are not loaded, like the harness's. */
    private static UserAccountBean admin() {
        return LegacyServletHarness.sysAdmin(1, "root");
    }

    private static UserAccountBean user(String name) {
        UserAccountDAO dao = new UserAccountDAO(DATA_SOURCE);
        UserAccountBean ub = dao.findByUserName(name);
        for (StudyUserRoleBean role : dao.findAllRolesByUserName(name)) {
            ub.addRole(role);
        }
        ub.setPasswdTimestamp(new Date());
        return ub;
    }

    private static void authenticate(String name) {
        User principal = new User(name, "secret", List.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    private static String[] with(String[] params, String... more) {
        String[] all = new String[params.length + more.length];
        System.arraycopy(params, 0, all, 0, params.length);
        System.arraycopy(more, 0, all, params.length, more.length);
        return all;
    }

    private MockHttpServletResponse run(HttpServlet servlet, String method, String path, UserAccountBean user,
            String... params) throws Exception {
        return run(servlet, method, path, user, null, params);
    }

    private MockHttpServletResponse run(HttpServlet servlet, String method, String path, UserAccountBean user,
            MockHttpSession session, String... params) throws Exception {
        MockHttpServletRequest req = harness.request(method, path, user);
        if (session != null) {
            // Carry what the previous request left in the session (the study
            // switch confirms before it submits), then the user of this one.
            Collections.list(session.getAttributeNames())
                    .forEach(name -> req.getSession().setAttribute(name, session.getAttribute(name)));
            req.getSession().setAttribute(SecureController.USER_BEAN_NAME, user);
        }
        for (int i = 0; i < params.length; i += 2) {
            req.addParameter(params[i], params[i + 1]);
        }
        MockHttpServletResponse resp = harness.run(servlet, req);
        if (session != null) {
            Collections.list(req.getSession().getAttributeNames())
                    .forEach(name -> session.setAttribute(name, req.getSession().getAttribute(name)));
        }
        return resp;
    }

    private static Properties sqlInitParams() throws ReflectiveOperationException {
        Field field = SQLInitServlet.class.getDeclaredField("params");
        field.setAccessible(true);
        return (Properties) field.get(null);
    }

    private static int definitionOrdinal(int id) throws SQLException {
        return queryInt("SELECT ordinal FROM study_event_definition WHERE study_event_definition_id = " + id);
    }

    private static int definitionStatus(int id) throws SQLException {
        return queryInt("SELECT status_id FROM study_event_definition WHERE study_event_definition_id = " + id);
    }

    private static int crfOrdinal(int id) throws SQLException {
        return queryInt("SELECT ordinal FROM event_definition_crf WHERE event_definition_crf_id = " + id);
    }

    private static int studySubjectStatus(int id) throws SQLException {
        return queryInt("SELECT status_id FROM study_subject WHERE study_subject_id = " + id);
    }

    private static int eventStatus(int id) throws SQLException {
        return queryInt("SELECT status_id FROM study_event WHERE study_event_id = " + id);
    }

    private static int eventCrfStatus(int id) throws SQLException {
        return queryInt("SELECT status_id FROM event_crf WHERE event_crf_id = " + id);
    }

    private static int versionStatus(int id) throws SQLException {
        return queryInt("SELECT status_id FROM crf_version WHERE crf_version_id = " + id);
    }

    private static int physicianRoleStatus() throws SQLException {
        return queryInt("SELECT status_id FROM study_user_role WHERE user_name = 'physician' AND study_id = " + STUDY);
    }

    private static int activeStudy(String userName) throws SQLException {
        return queryInt("SELECT active_study FROM user_account WHERE user_name = '" + userName + "'");
    }

    private static int notesDescribed(String description) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM discrepancy_note WHERE description = ?")) {
            ps.setString(1, description);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getInt(1);
            }
        }
    }

    private static Timestamp timestamp(String sql) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next(), "no row for " + sql);
            Timestamp value = rs.getTimestamp(1);
            assertNotEquals(null, value, sql);
            return value;
        }
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

    /** Guards the helpers against a silent no-op: every fixture row they touch exists. */
    @Test
    void theFixtureRowsExist() throws Exception {
        assertEquals(AVAILABLE, studySubjectStatus(SUBJECT));
        assertEquals(AVAILABLE, eventStatus(EVENT));
        assertEquals(AVAILABLE, eventCrfStatus(EVENT_CRF));
        assertTrue(queryInt("SELECT COUNT(*) FROM study_user_role WHERE user_name = 'manual_admin' AND role_name = 'admin'") > 0);
    }
}
