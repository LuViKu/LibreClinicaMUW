/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * A Monitor may re-query, reply to, reassign, close and re-open a query, as
 * legacy's {@code ViewDiscrepancyNoteServlet} allows: Update Note and Close
 * Note on every thread that is not Not Applicable.
 *
 * <p>Before, the SPA's API let a Monitor close only a proposed resolution and
 * update only an Updated thread: a New query could be neither re-queried nor
 * closed, and a closed one never re-opened.
 */
class MonitorQueryAuthorityDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static final int NEW = 1;
    private static final int UPDATED = 2;
    private static final int RESOLUTION_PROPOSED = 3;
    private static final int CLOSED = 4;
    private static final int NOT_APPLICABLE = 5;
    private static final int ANNOTATION = 2;
    private static final int QUERY = 3;

    /** Item data 29: I_HEIGHT_CM on event CRF 11 (subject 5). */
    private static final int ITEM_DATA = 29;

    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(
                        new DiscrepancyApiController(DATA_SOURCE, new SiteVisibilityFilter(DATA_SOURCE)))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void aMonitorClosesANewQuery() throws Exception {
        int note = query(NEW);
        try {
            answer(note, monitor(), "{\"newStatus\":\"closed\",\"description\":\"Checked against source\"}")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("closed"));
            assertEquals(CLOSED, NoteFixtures.statusOf(DATA_SOURCE, note));
        } finally {
            NoteFixtures.delete(DATA_SOURCE, note);
        }
    }

    @Test
    void aMonitorReQueriesANewQueryAndReassignsIt() throws Exception {
        int note = query(NEW);
        try {
            answer(note, monitor(), "{\"newStatus\":\"updated\",\"description\":\"Please re-measure\","
                    + "\"assignedTo\":\"manual_investigator\"}")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("updated"))
                    .andExpect(jsonPath("$.assignedTo").value("manual_investigator"));
            assertEquals(UPDATED, NoteFixtures.statusOf(DATA_SOURCE, note));
            assertEquals(ClinicalWriteFixtures.userId(DATA_SOURCE, "manual_investigator"),
                    NoteFixtures.assigneeOf(DATA_SOURCE, note));
        } finally {
            NoteFixtures.delete(DATA_SOURCE, note);
        }
    }

    @Test
    void aMonitorClosesAnUpdatedQuery() throws Exception {
        int note = query(UPDATED);
        try {
            answer(note, monitor(), "{\"newStatus\":\"closed\"}")
                    .andExpect(status().isOk());
            assertEquals(CLOSED, NoteFixtures.statusOf(DATA_SOURCE, note));
        } finally {
            NoteFixtures.delete(DATA_SOURCE, note);
        }
    }

    @Test
    void aMonitorReopensAClosedQuery() throws Exception {
        int note = query(CLOSED);
        try {
            answer(note, monitor(), "{\"newStatus\":\"updated\",\"description\":\"Source differs after all\"}")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("updated"));
            assertEquals(UPDATED, NoteFixtures.statusOf(DATA_SOURCE, note));
        } finally {
            NoteFixtures.delete(DATA_SOURCE, note);
        }
    }

    @Test
    void aMonitorDoesNotProposeAResolution() throws Exception {
        int note = query(UPDATED);
        try {
            answer(note, monitor(), "{\"newStatus\":\"resolution-proposed\",\"description\":\"Fixed\"}")
                    .andExpect(status().isForbidden());
            assertEquals(UPDATED, NoteFixtures.statusOf(DATA_SOURCE, note));
        } finally {
            NoteFixtures.delete(DATA_SOURCE, note);
        }
    }

    @Test
    void anInvestigatorDoesNotCloseANewQuery() throws Exception {
        int note = query(NEW);
        try {
            answer(note, investigator(), "{\"newStatus\":\"closed\"}")
                    .andExpect(status().isForbidden());
            assertEquals(NEW, NoteFixtures.statusOf(DATA_SOURCE, note));
        } finally {
            NoteFixtures.delete(DATA_SOURCE, note);
        }
    }

    @Test
    void anInvestigatorDoesNotReopenAClosedQuery() throws Exception {
        int note = query(CLOSED);
        try {
            answer(note, investigator(), "{\"newStatus\":\"updated\",\"description\":\"Reopen\"}")
                    .andExpect(status().isForbidden());
            assertEquals(CLOSED, NoteFixtures.statusOf(DATA_SOURCE, note));
        } finally {
            NoteFixtures.delete(DATA_SOURCE, note);
        }
    }

    @Test
    void aMonitorClosesAProposedResolution() throws Exception {
        int note = query(RESOLUTION_PROPOSED);
        try {
            answer(note, monitor(), "{\"newStatus\":\"closed\",\"description\":\"Agreed\"}")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("closed"));
            assertEquals(CLOSED, NoteFixtures.statusOf(DATA_SOURCE, note));
        } finally {
            NoteFixtures.delete(DATA_SOURCE, note);
        }
    }

    @Test
    void aMonitorReQueriesAProposedResolution() throws Exception {
        int note = query(RESOLUTION_PROPOSED);
        try {
            answer(note, monitor(), "{\"newStatus\":\"updated\",\"description\":\"Source still differs\"}")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("updated"));
            assertEquals(UPDATED, NoteFixtures.statusOf(DATA_SOURCE, note));
        } finally {
            NoteFixtures.delete(DATA_SOURCE, note);
        }
    }

    @Test
    void anAnnotationTakesNoThreadEntry() throws Exception {
        // Legacy offers no reply to a Not Applicable thread either.
        int note = NoteFixtures.insertItemNote(DATA_SOURCE, ANNOTATION, NOT_APPLICABLE,
                Instant.now().minus(Duration.ofDays(3)), ITEM_DATA, "Measured with shoes on");
        try {
            answer(note, monitor(), "{\"newStatus\":\"closed\"}")
                    .andExpect(status().isBadRequest());
            answer(note, monitor(), "{\"newStatus\":\"updated\",\"description\":\"Why?\"}")
                    .andExpect(status().isBadRequest());
            assertEquals(NOT_APPLICABLE, NoteFixtures.statusOf(DATA_SOURCE, note));
        } finally {
            NoteFixtures.delete(DATA_SOURCE, note);
        }
    }

    @Test
    void aMonitorOfAnotherStudyCannotAnswer() throws Exception {
        // Bound to a study that is not Default Study, holding no grant there.
        StudyBean other = new StudyBean();
        other.setId(9002);
        other.setOid("S_OTHER");
        MockHttpSession elsewhere = monitor();
        elsewhere.setAttribute("study", other);
        int note = query(NEW);
        try {
            answer(note, elsewhere, "{\"newStatus\":\"closed\"}")
                    .andExpect(status().isForbidden());
            assertEquals(NEW, NoteFixtures.statusOf(DATA_SOURCE, note));
        } finally {
            NoteFixtures.delete(DATA_SOURCE, note);
        }
    }

    @Test
    void aDataManagerReopensAClosedQuery() throws Exception {
        // Legacy sets a Data Manager's reply to a closed thread to Updated.
        int note = query(CLOSED);
        try {
            answer(note, ClinicalWriteFixtures.sessionAs(DATA_SOURCE, "manual_dm"),
                    "{\"newStatus\":\"updated\",\"description\":\"Source differs after all\"}")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("updated"));
            assertEquals(UPDATED, NoteFixtures.statusOf(DATA_SOURCE, note));
        } finally {
            NoteFixtures.delete(DATA_SOURCE, note);
        }
    }

    @Test
    void aReassignmentToAnUnknownUserIsRefused() throws Exception {
        int note = query(NEW);
        try {
            answer(note, monitor(), "{\"newStatus\":\"updated\",\"description\":\"Please re-measure\","
                    + "\"assignedTo\":\"nobody_by_that_name\"}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(containsString("nobody_by_that_name")));
            assertEquals(NEW, NoteFixtures.statusOf(DATA_SOURCE, note));
            assertEquals(0, NoteFixtures.assigneeOf(DATA_SOURCE, note));
        } finally {
            NoteFixtures.delete(DATA_SOURCE, note);
        }
    }

    @Test
    void aReassignmentToAUserWithoutARoleInTheStudyIsRefused() throws Exception {
        // An account that holds no role in Default Study or any site of it.
        ClinicalWriteFixtures.execute(DATA_SOURCE,
                "INSERT INTO user_account (user_id, user_name, passwd, first_name, last_name, email, "
                        + "active_study, status_id, owner_id, date_created, user_type_id, enabled, "
                        + "account_non_locked, lock_counter, run_webservices, authtype, enable_api_key) "
                        + "SELECT COALESCE(MAX(user_id), 0) + 1, 'no_role_here', 'x', 'No', 'Role', "
                        + "'no_role_here@example.invalid', 1, 1, 1, now(), 2, true, true, 0, false, "
                        + "'STANDARD', false FROM user_account");
        int note = query(NEW);
        try {
            answer(note, monitor(), "{\"newStatus\":\"updated\",\"description\":\"Please re-measure\","
                    + "\"assignedTo\":\"no_role_here\"}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(containsString("no_role_here")));
            assertEquals(NEW, NoteFixtures.statusOf(DATA_SOURCE, note));
            assertEquals(0, NoteFixtures.assigneeOf(DATA_SOURCE, note));
        } finally {
            NoteFixtures.delete(DATA_SOURCE, note);
            ClinicalWriteFixtures.execute(DATA_SOURCE, "DELETE FROM user_account WHERE user_name = 'no_role_here'");
        }
    }

    @Test
    void aNewQueryForAnUnknownAssigneeIsRefused() throws Exception {
        String subject = NoteFixtures.subjectLabel(DATA_SOURCE, 7);
        int notesBefore = Integer.parseInt(countNotes());
        mvc().perform(post("/api/v1/discrepancies").session(monitor())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectId\":\"" + subject + "\",\"itemOid\":\"I_HEIGHT_CM\","
                                + "\"eventCrfOid\":\"15\",\"type\":\"query\","
                                + "\"description\":\"Please confirm\",\"assignedTo\":\"nobody_by_that_name\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("nobody_by_that_name")));
        assertEquals(notesBefore, Integer.parseInt(countNotes()), "no note is written");
    }

    /* ------------------------------------------------------------------ */

    private static String countNotes() throws Exception {
        try (java.sql.Connection c = DATA_SOURCE.getConnection();
             java.sql.PreparedStatement ps = c.prepareStatement("SELECT count(*)::text FROM discrepancy_note");
             java.sql.ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static int query(int statusId) throws Exception {
        return NoteFixtures.insertItemNote(DATA_SOURCE, QUERY, statusId,
                Instant.now().minus(Duration.ofDays(3)), ITEM_DATA, "Height looks low");
    }

    private ResultActions answer(int note, MockHttpSession session, String body) throws Exception {
        return mvc().perform(post("/api/v1/discrepancies/" + note + "/thread").session(session)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static MockHttpSession monitor() {
        return ClinicalWriteFixtures.sessionAs(DATA_SOURCE, "manual_monitor");
    }

    private static MockHttpSession investigator() {
        return ClinicalWriteFixtures.sessionAs(DATA_SOURCE, "manual_investigator");
    }
}
