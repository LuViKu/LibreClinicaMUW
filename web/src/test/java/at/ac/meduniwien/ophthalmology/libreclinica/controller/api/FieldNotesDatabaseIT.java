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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * A query on a field of the subject, a visit or a CRF header, as legacy
 * notes them (View Subject, the visit page, the CRF header): stored on that
 * row through its mapping table, listed with its subject, visit and value,
 * and answered like any other thread.
 *
 * <p>Before, the API attached notes to item data only.
 *
 * <p>Seed: M-001 is study subject 1 of subject 1 (female, born 1962-01-01,
 * enrolled 2020-10-06); visit 1 is its V1 Inclusion of 2020-10-06, with
 * event CRF 1. Visit 4 belongs to M-002.
 */
class FieldNotesDatabaseIT extends AbstractApiControllerDatabaseIT {

    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(
                        new DiscrepancyApiController(DATA_SOURCE, new SiteVisibilityFilter(DATA_SOURCE)))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void aMonitorQueriesTheDateOfBirth() throws Exception {
        int note = created("\"entityType\":\"subject\",\"column\":\"date_of_birth\"");
        try {
            assertEquals(1, mapped("dn_subject_map", "subject_id", note, "date_of_birth"));
            JsonNode listed = listed(note);
            assertEquals("M-001", listed.get("subjectId").asText());
            assertEquals("subject", listed.get("entityType").asText());
            assertEquals("date_of_birth", listed.get("column").asText());
            assertEquals("1962-01-01", listed.get("itemValue").asText());
        } finally {
            NoteFixtures.delete(DATA_SOURCE, note);
        }
    }

    @Test
    void aMonitorQueriesTheEnrolmentDate() throws Exception {
        int note = created("\"entityType\":\"studySub\",\"column\":\"enrollment_date\"");
        try {
            assertEquals(1, mapped("dn_study_subject_map", "study_subject_id", note, "enrollment_date"));
            JsonNode listed = listed(note);
            assertEquals("M-001", listed.get("subjectId").asText());
            assertEquals("2020-10-06", listed.get("itemValue").asText());
        } finally {
            NoteFixtures.delete(DATA_SOURCE, note);
        }
    }

    @Test
    void aMonitorQueriesAVisitDate() throws Exception {
        int note = created("\"entityType\":\"studyEvent\",\"column\":\"start_date\",\"eventId\":\"1\"");
        try {
            assertEquals(1, mapped("dn_study_event_map", "study_event_id", note, "start_date"));
            JsonNode listed = listed(note);
            assertEquals("M-001", listed.get("subjectId").asText());
            assertEquals("V1 Inclusion", listed.get("eventName").asText());
            assertEquals("2020-10-06", listed.get("itemValue").asText());
            assertEquals("1", listed.get("entityId").asText());
        } finally {
            NoteFixtures.delete(DATA_SOURCE, note);
        }
    }

    @Test
    void aMonitorQueriesTheInterviewerOfACrf() throws Exception {
        int note = created("\"entityType\":\"eventCrf\",\"column\":\"interviewer_name\",\"eventCrfOid\":\"1\"");
        try {
            assertEquals(1, mapped("dn_event_crf_map", "event_crf_id", note, "interviewer_name"));
            JsonNode listed = listed(note);
            assertEquals("M-001", listed.get("subjectId").asText());
            assertEquals("1", listed.get("eventCrfOid").asText());
        } finally {
            NoteFixtures.delete(DATA_SOURCE, note);
        }
    }

    @Test
    void aVisitOfAnotherSubjectIsRefused() throws Exception {
        raise("\"entityType\":\"studyEvent\",\"column\":\"start_date\",\"eventId\":\"4\"")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(containsString("of subject 'M-001'")));
    }

    @Test
    void aFieldLegacyDoesNotNoteIsRefused() throws Exception {
        raise("\"entityType\":\"subject\",\"column\":\"weight\"")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("Unknown column 'weight'")));
        raise("\"entityType\":\"study\",\"column\":\"name\"")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("Unknown entityType 'study'")));
    }

    @Test
    void aFieldQueryIsAnsweredLikeAnyOther() throws Exception {
        int note = created("\"entityType\":\"subject\",\"column\":\"gender\"");
        try {
            mvc().perform(post("/api/v1/discrepancies/" + note + "/thread")
                            .session(ClinicalWriteFixtures.sessionAs(DATA_SOURCE, "manual_investigator"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"newStatus\":\"updated\",\"description\":\"Female, per the source\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("updated"))
                    .andExpect(jsonPath("$.subjectId").value("M-001"))
                    .andExpect(jsonPath("$.column").value("gender"))
                    .andExpect(jsonPath("$.itemValue").value("f"));
        } finally {
            NoteFixtures.delete(DATA_SOURCE, note);
        }
    }

    /* ------------------------------------------------------------------ */

    private ResultActions raise(String target) throws Exception {
        return mvc().perform(post("/api/v1/discrepancies").session(monitor())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"subjectId\":\"M-001\"," + target
                        + ",\"type\":\"query\",\"description\":\"Please confirm against the source\"}"));
    }

    private int created(String target) throws Exception {
        String body = raise(target)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.subjectId").value("M-001"))
                .andExpect(jsonPath("$.itemOid").value(""))
                .andReturn().getResponse().getContentAsString();
        return new ObjectMapper().readTree(body).get("id").asInt();
    }

    private JsonNode listed(int note) throws Exception {
        String body = mvc().perform(get("/api/v1/discrepancies").session(monitor()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        for (JsonNode row : new ObjectMapper().readTree(body)) {
            if (row.get("id").asInt() == note) return row;
        }
        throw new AssertionError("note " + note + " is not listed: " + body);
    }

    /** Rows of the mapping table that tie the note to the seed row 1 on the column. */
    private static int mapped(String table, String idColumn, int note, String column) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM " + table + " WHERE discrepancy_note_id = ? AND "
                             + idColumn + " = 1 AND column_name = ?")) {
            ps.setInt(1, note);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static MockHttpSession monitor() {
        return ClinicalWriteFixtures.sessionAs(DATA_SOURCE, "manual_monitor");
    }
}
