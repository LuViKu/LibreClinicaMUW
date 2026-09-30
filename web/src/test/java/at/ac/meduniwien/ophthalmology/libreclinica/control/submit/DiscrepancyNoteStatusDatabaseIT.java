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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Date;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.DiscrepancyNoteType;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.ResolutionStatus;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.LegacyServletHarness;
import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.AbstractApiControllerDatabaseIT;
import at.ac.meduniwien.ophthalmology.libreclinica.core.SecurityManager;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.login.UserAccountDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.service.managestudy.EventDefinitionCrfTagService;

/**
 * Replying to a discrepancy-note thread through {@code /CreateOneDiscrepancyNote}
 * sets the thread's status, and starting one sets the first. Against the real
 * schema: a status or note type the note page does not offer the role is
 * refused and the thread stays as it was, while the ones it offers still go
 * through. {@link DiscrepancyNoteStatusRuleTest} has the whole table.
 * <p>
 * The notes are on an item of M-001's first visit; each reply test starts a
 * query thread of its own.
 */
class DiscrepancyNoteStatusDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static final int NEW = ResolutionStatus.OPEN.getId();
    private static final int UPDATED = ResolutionStatus.UPDATED.getId();
    private static final int PROPOSED = ResolutionStatus.RESOLVED.getId();
    private static final int CLOSED = ResolutionStatus.CLOSED.getId();

    private static final int FAILED_CHECK = DiscrepancyNoteType.FAILEDVAL.getId();
    private static final int ANNOTATION = DiscrepancyNoteType.ANNOTATION.getId();
    private static final int QUERY = DiscrepancyNoteType.QUERY.getId();

    private static final String REFUSAL = "/WEB-INF/jsp/menu.jsp";

    private LegacyServletHarness harness;
    private String itemData;

    @BeforeEach
    void setUp() throws Exception {
        harness = new LegacyServletHarness(DATA_SOURCE)
                .bean("securityManager", mock(SecurityManager.class))
                .bean("mailSender", mock(JavaMailSenderImpl.class))
                .bean("eventDefinitionCrfTagService", mock(EventDefinitionCrfTagService.class));
        itemData = String.valueOf(queryInt("SELECT MIN(item_data_id) FROM item_data WHERE event_crf_id = 1"));
    }

    // ---- replies ------------------------------------------------------------------------------

    @Test
    void anInvestigatorCannotCloseAThread() throws Exception {
        int thread = startQuery(PROPOSED);

        MockHttpServletResponse resp = reply(user("manual_investigator"), thread, QUERY, CLOSED);

        assertEquals(PROPOSED, status(thread), "the thread is still awaiting its review");
        assertEquals(1, replies(thread), "no note was added");
        assertEquals(REFUSAL, resp.getForwardedUrl());
    }

    @Test
    void anInvestigatorProposesAResolution() throws Exception {
        int thread = startQuery(UPDATED);

        MockHttpServletResponse resp = reply(user("manual_investigator"), thread, QUERY, PROPOSED);

        assertEquals(PROPOSED, status(thread));
        assertEquals(2, replies(thread));
        assertNotEquals(REFUSAL, resp.getForwardedUrl());
    }

    @Test
    void aResearchAssistantCannotReopenAClosedThread() throws Exception {
        int thread = startQuery(CLOSED);
        update("UPDATE study_user_role SET role_name = 'ra' WHERE user_name = 'manual_crc' AND study_id = 1");
        try {
            MockHttpServletResponse resp = reply(user("manual_crc"), thread, QUERY, UPDATED);

            assertEquals(CLOSED, status(thread));
            assertEquals(1, replies(thread));
            assertEquals(REFUSAL, resp.getForwardedUrl());
        } finally {
            update("UPDATE study_user_role SET role_name = 'coordinator' WHERE user_name = 'manual_crc' AND study_id = 1");
        }
    }

    @Test
    void aMonitorClosesAThread() throws Exception {
        int thread = startQuery(PROPOSED);

        reply(user("manual_monitor"), thread, QUERY, CLOSED);

        assertEquals(CLOSED, status(thread));
        assertEquals(2, replies(thread));
    }

    @Test
    void aMonitorCannotProposeAResolution() throws Exception {
        int thread = startQuery(UPDATED);

        MockHttpServletResponse resp = reply(user("manual_monitor"), thread, QUERY, PROPOSED);

        assertEquals(UPDATED, status(thread));
        assertEquals(1, replies(thread));
        assertEquals(REFUSAL, resp.getForwardedUrl());
    }

    @Test
    void aDirectorReopensAClosedThread() throws Exception {
        int thread = startQuery(CLOSED);

        reply(user("manual_dm"), thread, QUERY, NEW);

        assertEquals(NEW, status(thread));
        assertEquals(2, replies(thread));
    }

    @Test
    void aReplyCannotNameAnotherReplyAsItsThread() throws Exception {
        // A thread's first reply keeps the status the thread started in.
        int thread = startQuery(UPDATED);
        int firstReply = queryInt("SELECT MIN(discrepancy_note_id) FROM discrepancy_note WHERE parent_dn_id = " + thread);

        MockHttpServletResponse resp = reply(user("manual_dm"), firstReply, QUERY, CLOSED);

        assertEquals(NEW, status(firstReply), "the first reply keeps its status");
        assertEquals(0, replies(firstReply), "no note was added under it");
        assertEquals(REFUSAL, resp.getForwardedUrl());
    }

    @Test
    void aReplyCannotTurnAQueryNotApplicable() throws Exception {
        // An annotation is saved "not applicable" and would set the thread so.
        int thread = startQuery(UPDATED);

        MockHttpServletResponse resp = reply(user("manual_dm"), thread, ANNOTATION, UPDATED);

        assertEquals(UPDATED, status(thread));
        assertEquals(1, replies(thread));
        assertEquals(REFUSAL, resp.getForwardedUrl());
    }

    // ---- new threads --------------------------------------------------------------------------

    @Test
    void aNewThreadStartsOnlyAsThePageOffersTheRole() throws Exception {
        String closedByInvestigator = "IT query " + UUID.randomUUID();
        MockHttpServletResponse refused = start(user("manual_investigator"), closedByInvestigator, FAILED_CHECK, CLOSED);
        assertEquals(0, notesDescribed(closedByInvestigator), "no thread was started");
        assertEquals(REFUSAL, refused.getForwardedUrl());

        String queryByInvestigator = "IT query " + UUID.randomUUID();
        start(user("manual_investigator"), queryByInvestigator, QUERY, NEW);
        assertEquals(0, notesDescribed(queryByInvestigator), "investigators are not offered queries");

        String proposedByInvestigator = "IT check " + UUID.randomUUID();
        start(user("manual_investigator"), proposedByInvestigator, FAILED_CHECK, PROPOSED);
        assertTrue(notesDescribed(proposedByInvestigator) > 0);

        String closedByMonitor = "IT query " + UUID.randomUUID();
        start(user("manual_monitor"), closedByMonitor, QUERY, CLOSED);
        assertTrue(notesDescribed(closedByMonitor) > 0);
    }

    @Test
    void aMonitorCannotAnnotateAnItem() throws Exception {
        // Saving an annotation also clears the CRF's source data verification
        // and reopens a signed event or subject.
        update("UPDATE event_crf SET sdv_status = true, update_id = 1 WHERE event_crf_id = 1");
        String description = "IT annotation " + UUID.randomUUID();
        try {
            MockHttpServletResponse resp = start(user("manual_monitor"), description, ANNOTATION, 0);

            assertEquals(0, notesDescribed(description), "no note was added");
            assertEquals(1, queryInt("SELECT CASE WHEN sdv_status THEN 1 ELSE 0 END FROM event_crf WHERE event_crf_id = 1"),
                    "the CRF is still verified");
            assertEquals(REFUSAL, resp.getForwardedUrl());
        } finally {
            update("UPDATE event_crf SET sdv_status = false, update_id = NULL WHERE event_crf_id = 1");
        }
    }

    @Test
    void aNewThreadWithoutATypeGetsTheFormsErrorRatherThanARefusal() throws Exception {
        // A browser sends no type when the chosen one is disabled: an
        // annotation, the investigator's default, in a frozen study.
        String description = "IT note " + UUID.randomUUID();

        MockHttpServletResponse resp = post(user("manual_investigator"), "parentId", "0", "name", "itemData", "id", itemData,
                "field", "input1", "column", "value", "description0", description, "detailedDes0", "",
                "viewDNLink0", "/ViewDiscrepancyNote?name=itemData&id=" + itemData);

        assertEquals(0, notesDescribed(description), "no thread was started");
        assertTrue(String.valueOf(resp.getForwardedUrl()).startsWith("/ViewDiscrepancyNote"),
                "back to the note page: " + resp.getForwardedUrl());
    }

    // ---- helpers ------------------------------------------------------------------------------

    /**
     * A query thread started by the director, then set to {@code status}.
     *
     * @return the thread's parent note id; the thread holds one child note
     */
    private int startQuery(int status) throws Exception {
        String description = "IT thread " + UUID.randomUUID();
        start(user("manual_dm"), description, QUERY, NEW);
        int thread = queryInt("SELECT MIN(discrepancy_note_id) FROM discrepancy_note WHERE description = '" + description + "'");
        assertEquals(1, replies(thread), "the thread was started");
        update("UPDATE discrepancy_note SET resolution_status_id = " + status + " WHERE discrepancy_note_id = " + thread);
        return thread;
    }

    private MockHttpServletResponse start(UserAccountBean user, String description, int type, int status) throws Exception {
        return post(user, "parentId", "0", "name", "itemData", "id", itemData, "field", "input1", "column", "value",
                "description0", description, "detailedDes0", "", "typeId0", String.valueOf(type),
                "resStatusId0", String.valueOf(status), "viewDNLink0", "/ViewDiscrepancyNote?name=itemData&id=" + itemData);
    }

    private MockHttpServletResponse reply(UserAccountBean user, int thread, int type, int status) throws Exception {
        String p = String.valueOf(thread);
        return post(user, "parentId", p, "name", "itemData", "id", itemData, "field", "input1", "column", "value",
                "description" + p, "IT reply", "detailedDes" + p, "", "typeId" + p, String.valueOf(type),
                "resStatusId" + p, String.valueOf(status), "viewDNLink" + p, "/ViewDiscrepancyNote?name=itemData&id=" + itemData);
    }

    private MockHttpServletResponse post(UserAccountBean user, String... params) throws Exception {
        MockHttpServletRequest req = harness.request("POST", "/CreateOneDiscrepancyNote", user);
        for (int i = 0; i < params.length; i += 2) {
            req.addParameter(params[i], params[i + 1]);
        }
        return harness.run(new CreateOneDiscrepancyNoteServlet(), req);
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

    /** Guards the fixture: the roles the tests act in. */
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
    }
}
