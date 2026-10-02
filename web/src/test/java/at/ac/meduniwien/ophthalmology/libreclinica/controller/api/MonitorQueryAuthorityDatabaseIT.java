/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;

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
    private static final int CLOSED = 4;
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

    /* ------------------------------------------------------------------ */

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
