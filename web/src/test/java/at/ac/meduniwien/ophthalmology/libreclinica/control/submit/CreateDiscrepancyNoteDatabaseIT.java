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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.DiscrepancyNoteType;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.ResolutionStatus;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Status;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.SubjectEventStatus;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.LegacyServletHarness;
import at.ac.meduniwien.ophthalmology.libreclinica.control.core.SecureController;
import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.AbstractApiControllerDatabaseIT;
import at.ac.meduniwien.ophthalmology.libreclinica.core.SecurityManager;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.RuleSetDao;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.login.UserAccountDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyEventDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.i18n.util.ResourceBundleProvider;
import at.ac.meduniwien.ophthalmology.libreclinica.service.rule.StudyEventBeanListener;

/**
 * Saving a note through the single-note popup, {@code /CreateDiscrepancyNote},
 * against the real schema. It applies the rule of the note page
 * ({@link DiscrepancyNoteStatusRule}): a type or status the popup does not
 * offer the role is refused before anything is written, a monitor's
 * annotation included, which would reopen a signed event and subject and
 * clear the CRF's source data verification. A record or thread outside the
 * current study is refused too.
 * <p>
 * The notes are on an item of M-001's first visit (event CRF 1).
 */
@SuppressWarnings("resource") // the context is only a bean-lookup holder for the legacy DAOs and lives as long as the test JVM
class CreateDiscrepancyNoteDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static final int NEW = ResolutionStatus.OPEN.getId();
    private static final int UPDATED = ResolutionStatus.UPDATED.getId();
    private static final int PROPOSED = ResolutionStatus.RESOLVED.getId();
    private static final int CLOSED = ResolutionStatus.CLOSED.getId();

    private static final int FAILED_CHECK = DiscrepancyNoteType.FAILEDVAL.getId();
    private static final int ANNOTATION = DiscrepancyNoteType.ANNOTATION.getId();
    private static final int QUERY = DiscrepancyNoteType.QUERY.getId();

    /** EIAMD139, a subject of study 102. */
    private static final int OTHER_STUDY_SUBJECT = 102;

    private static final String REFUSAL = "/WEB-INF/jsp/menu.jsp";

    private LegacyServletHarness harness;
    private ApplicationContext savedListenerContext;
    private String itemData;
    private int event;
    private int studySubject;

    @BeforeEach
    void setUp() throws Exception {
        // StudyEventDAO.update notifies a rule listener that reads its rule sets
        // from the Spring context; give it one with none.
        GenericApplicationContext rules = new GenericApplicationContext();
        rules.registerBean("ruleSetDao", RuleSetDao.class, () -> mock(RuleSetDao.class));
        rules.refresh();
        Field field = StudyEventBeanListener.class.getDeclaredField("cntxt");
        field.setAccessible(true);
        savedListenerContext = (ApplicationContext) field.get(null);
        new StudyEventBeanListener(new StudyEventDAO(DATA_SOURCE)).setApplicationContext(rules);

        // The servlet reads two messages from these bundles when it is built,
        // before its first request sets them.
        SecureController.resexception = ResourceBundleProvider.getExceptionsBundle(Locale.ENGLISH);
        SecureController.respage = ResourceBundleProvider.getPageMessagesBundle(Locale.ENGLISH);
        harness = new LegacyServletHarness(DATA_SOURCE)
                .bean("securityManager", mock(SecurityManager.class))
                .bean("mailSender", mock(JavaMailSenderImpl.class));
        itemData = String.valueOf(queryInt("SELECT MIN(item_data_id) FROM item_data WHERE event_crf_id = 1"));
        event = queryInt("SELECT study_event_id FROM event_crf WHERE event_crf_id = 1");
        studySubject = queryInt("SELECT study_subject_id FROM study_event WHERE study_event_id = " + event);
    }

    @AfterEach
    void tearDown() throws Exception {
        Field field = StudyEventBeanListener.class.getDeclaredField("cntxt");
        field.setAccessible(true);
        field.set(null, savedListenerContext);
    }

    // ---- annotations on signed work -----------------------------------------------------------

    @Test
    void aMonitorCannotAnnotateASignedVerifiedItem() throws Exception {
        String description = "IT annotation " + UUID.randomUUID();
        Signed saved = signAndVerify();
        try {
            MockHttpServletResponse resp = save(user("manual_monitor"), description, ANNOTATION, 0);

            assertEquals(0, notesDescribed(description), "no note was added");
            assertSignedAndVerified();
            assertEquals(REFUSAL, resp.getForwardedUrl());
        } finally {
            saved.restore();
        }
    }

    @Test
    void anAnnotationThatFailsValidationLeavesTheSignature() throws Exception {
        // The director may annotate; a blank description is the form's error.
        Signed saved = signAndVerify();
        try {
            MockHttpServletResponse resp = save(user("manual_dm"), "", ANNOTATION, 0);

            assertSignedAndVerified();
            assertNotEquals(REFUSAL, resp.getForwardedUrl());
        } finally {
            saved.restore();
        }
    }

    @Test
    void aDirectorsAnnotationStillReopensTheSignedEvent() throws Exception {
        String description = "IT annotation " + UUID.randomUUID();
        Signed saved = signAndVerify();
        try {
            save(user("manual_dm"), description, ANNOTATION, 0);

            assertTrue(notesDescribed(description) > 0, "the note was saved");
            assertEquals(SubjectEventStatus.COMPLETED.getId(), eventStatus());
            assertEquals(0, sdv(), "the CRF needs verifying again");
        } finally {
            saved.restore();
        }
    }

    // ---- status and type ----------------------------------------------------------------------

    @Test
    void aNewThreadStartsOnlyAsThePopupOffersTheRole() throws Exception {
        String closedByInvestigator = "IT check " + UUID.randomUUID();
        MockHttpServletResponse refused = save(user("manual_investigator"), closedByInvestigator, FAILED_CHECK, CLOSED);
        assertEquals(0, notesDescribed(closedByInvestigator), "no thread was started");
        assertEquals(REFUSAL, refused.getForwardedUrl());

        String queryByInvestigator = "IT query " + UUID.randomUUID();
        save(user("manual_investigator"), queryByInvestigator, QUERY, NEW);
        assertEquals(0, notesDescribed(queryByInvestigator), "investigators are not offered queries");

        String proposedByMonitor = "IT query " + UUID.randomUUID();
        save(user("manual_monitor"), proposedByMonitor, QUERY, PROPOSED);
        assertEquals(0, notesDescribed(proposedByMonitor), "monitors are not offered 'resolution proposed'");

        String proposedByInvestigator = "IT check " + UUID.randomUUID();
        save(user("manual_investigator"), proposedByInvestigator, FAILED_CHECK, PROPOSED);
        assertEquals(2, notesDescribed(proposedByInvestigator), "the thread and its first note");

        String queryByMonitor = "IT query " + UUID.randomUUID();
        save(user("manual_monitor"), queryByMonitor, QUERY, NEW);
        assertEquals(2, notesDescribed(queryByMonitor));
    }

    @Test
    void aReplyCannotCloseAThreadForAnInvestigator() throws Exception {
        int thread = startQuery(PROPOSED);

        MockHttpServletResponse resp = post(user("manual_investigator"), "parentId", String.valueOf(thread),
                "description", "IT reply", "typeId", String.valueOf(QUERY), "resStatusId", String.valueOf(CLOSED));

        assertEquals(PROPOSED, status(thread), "the thread is still awaiting its review");
        assertEquals(1, replies(thread), "no note was added");
        assertEquals(REFUSAL, resp.getForwardedUrl());
    }

    @Test
    void aReplyToAClosedThreadIsRefusedForAResearchAssistant() throws Exception {
        int thread = startQuery(CLOSED);
        update("UPDATE study_user_role SET role_name = 'ra' WHERE user_name = 'manual_crc' AND study_id = 1");
        try {
            MockHttpServletResponse resp = post(user("manual_crc"), "parentId", String.valueOf(thread),
                    "description", "IT reply", "typeId", String.valueOf(QUERY), "resStatusId", String.valueOf(UPDATED));

            assertEquals(CLOSED, status(thread));
            assertEquals(1, replies(thread));
            assertEquals(REFUSAL, resp.getForwardedUrl());
        } finally {
            update("UPDATE study_user_role SET role_name = 'coordinator' WHERE user_name = 'manual_crc' AND study_id = 1");
        }
    }

    // ---- the study tree -----------------------------------------------------------------------

    @Test
    void aRecordOfAnotherStudyIsRefused() throws Exception {
        String description = "IT annotation " + UUID.randomUUID();

        MockHttpServletResponse resp = harness.run(new CreateDiscrepancyNoteServlet(), request(user("manual_dm"),
                "name", "studySub", "id", String.valueOf(OTHER_STUDY_SUBJECT), "column", "enrollment_date",
                "field", "enrollmentDate", "writeToDB", "1", "submitted", "1", "description", description,
                "detailedDes", "", "typeId", String.valueOf(ANNOTATION)));

        assertEquals(0, notesDescribed(description), "no note was added");
        assertEquals(REFUSAL, resp.getForwardedUrl());
    }

    @Test
    void aThreadOfAnotherStudyIsRefused() throws Exception {
        int thread = otherStudyThread();
        try {
            MockHttpServletResponse resp = post(user("manual_dm"), "parentId", String.valueOf(thread),
                    "description", "IT reply", "typeId", String.valueOf(QUERY), "resStatusId", String.valueOf(CLOSED));

            assertEquals(NEW, status(thread), "the thread is as it was");
            assertEquals(0, replies(thread));
            assertEquals(REFUSAL, resp.getForwardedUrl());
        } finally {
            removeThread(thread);
        }
    }

    // ---- helpers ------------------------------------------------------------------------------

    /** The status values {@link #signAndVerify} replaced, to put back. */
    private record Signed(int eventStatus, int subjectStatus) {
        void restore() throws SQLException {
            update("UPDATE event_crf SET sdv_status = false, update_id = NULL WHERE event_crf_id = 1");
            update("UPDATE study_event SET subject_event_status_id = " + eventStatus
                    + " WHERE study_event_id = (SELECT study_event_id FROM event_crf WHERE event_crf_id = 1)");
            update("UPDATE study_subject SET status_id = " + subjectStatus + " WHERE study_subject_id ="
                    + " (SELECT se.study_subject_id FROM study_event se JOIN event_crf ec"
                    + " ON ec.study_event_id = se.study_event_id WHERE ec.event_crf_id = 1)");
        }
    }

    /** Signs M-001's first visit and the subject, and marks event CRF 1 verified. */
    private Signed signAndVerify() throws SQLException {
        Signed saved = new Signed(eventStatus(),
                queryInt("SELECT status_id FROM study_subject WHERE study_subject_id = " + studySubject));
        update("UPDATE study_event SET subject_event_status_id = " + SubjectEventStatus.SIGNED.getId()
                + ", date_start = COALESCE(date_start, date_created) WHERE study_event_id = " + event);
        update("UPDATE study_subject SET status_id = " + Status.SIGNED.getId() + " WHERE study_subject_id = " + studySubject);
        update("UPDATE event_crf SET sdv_status = true, update_id = 1 WHERE event_crf_id = 1");
        return saved;
    }

    private void assertSignedAndVerified() throws SQLException {
        assertEquals(SubjectEventStatus.SIGNED.getId(), eventStatus(), "the event is still signed");
        assertEquals(Status.SIGNED.getId(),
                queryInt("SELECT status_id FROM study_subject WHERE study_subject_id = " + studySubject),
                "the subject is still signed");
        assertEquals(1, sdv(), "the CRF is still verified");
    }

    private int eventStatus() throws SQLException {
        return queryInt("SELECT subject_event_status_id FROM study_event WHERE study_event_id = " + event);
    }

    private static int sdv() throws SQLException {
        return queryInt("SELECT CASE WHEN sdv_status THEN 1 ELSE 0 END FROM event_crf WHERE event_crf_id = 1");
    }

    /** A query thread started by the director, then set to {@code status}. */
    private int startQuery(int status) throws Exception {
        String description = "IT thread " + UUID.randomUUID();
        save(user("manual_dm"), description, QUERY, NEW);
        int thread = queryInt("SELECT MIN(discrepancy_note_id) FROM discrepancy_note WHERE description = '" + description + "'");
        assertEquals(1, replies(thread), "the thread was started");
        update("UPDATE discrepancy_note SET resolution_status_id = " + status + " WHERE discrepancy_note_id = " + thread);
        return thread;
    }

    /** A query thread, status "new", on the enrolment date of a subject of study 102. */
    private static int otherStudyThread() throws SQLException {
        int thread = queryInt("INSERT INTO discrepancy_note (description, discrepancy_note_type_id, resolution_status_id,"
                + " date_created, owner_id, entity_type, study_id) VALUES ('IT other study', " + QUERY + ", " + NEW
                + ", now(), 1, 'studySub', 102) RETURNING discrepancy_note_id");
        update("INSERT INTO dn_study_subject_map (study_subject_id, discrepancy_note_id, column_name) VALUES ("
                + OTHER_STUDY_SUBJECT + ", " + thread + ", 'enrollment_date')");
        return thread;
    }

    private static void removeThread(int thread) throws SQLException {
        update("DELETE FROM dn_study_subject_map WHERE discrepancy_note_id IN (SELECT discrepancy_note_id"
                + " FROM discrepancy_note WHERE discrepancy_note_id = " + thread + " OR parent_dn_id = " + thread + ")");
        update("DELETE FROM discrepancy_note WHERE parent_dn_id = " + thread + " AND discrepancy_note_id <> " + thread);
        update("DELETE FROM discrepancy_note WHERE discrepancy_note_id = " + thread);
    }

    /** Saves a new thread on the item, as the popup's form posts it. */
    private MockHttpServletResponse save(UserAccountBean user, String description, int type, int status) throws Exception {
        return post(user, "parentId", "0", "description", description, "typeId", String.valueOf(type),
                "resStatusId", String.valueOf(status));
    }

    private MockHttpServletResponse post(UserAccountBean user, String... params) throws Exception {
        String[] form = { "name", "itemData", "id", itemData, "column", "value", "field", "input1",
                "writeToDB", "1", "submitted", "1", "detailedDes", "" };
        String[] all = new String[form.length + params.length];
        System.arraycopy(form, 0, all, 0, form.length);
        System.arraycopy(params, 0, all, form.length, params.length);
        return harness.run(new CreateDiscrepancyNoteServlet(), request(user, all));
    }

    private MockHttpServletRequest request(UserAccountBean user, String... params) {
        MockHttpServletRequest req = harness.request("POST", "/CreateDiscrepancyNote", user);
        for (int i = 0; i < params.length; i += 2) {
            req.addParameter(params[i], params[i + 1]);
        }
        return req;
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

    private static int status(int thread) throws SQLException {
        return queryInt("SELECT resolution_status_id FROM discrepancy_note WHERE discrepancy_note_id = " + thread);
    }

    private static int replies(int thread) throws SQLException {
        return queryInt("SELECT COUNT(*) FROM discrepancy_note WHERE parent_dn_id = " + thread
                + " AND discrepancy_note_id <> " + thread);
    }

    private static int notesDescribed(String description) throws SQLException {
        return queryInt("SELECT COUNT(*) FROM discrepancy_note WHERE description = '" + description + "'");
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

    /** Guards the fixture: the roles the tests act in, and the other study's subject. */
    @Test
    void theFixtureRowsExist() throws Exception {
        assertEquals(1, queryInt("SELECT COUNT(*) FROM study_user_role WHERE user_name = 'manual_investigator'"
                + " AND study_id = 1 AND role_name = 'Investigator'"));
        assertEquals(1, queryInt("SELECT COUNT(*) FROM study_user_role WHERE user_name = 'manual_monitor'"
                + " AND study_id = 1 AND role_name = 'monitor'"));
        assertEquals(1, queryInt("SELECT COUNT(*) FROM study_user_role WHERE user_name = 'manual_dm'"
                + " AND study_id = 1 AND role_name = 'director'"));
        assertEquals(1, queryInt("SELECT COUNT(*) FROM study_user_role WHERE user_name = 'manual_crc'"
                + " AND study_id = 1 AND role_name = 'coordinator'"));
        assertEquals(102, queryInt("SELECT study_id FROM study_subject WHERE study_subject_id = " + OTHER_STUDY_SUBJECT));
        assertEquals(1, queryInt("SELECT ss.study_id FROM study_subject ss JOIN study_event se"
                + " ON se.study_subject_id = ss.study_subject_id WHERE se.study_event_id = " + event));
    }
}
