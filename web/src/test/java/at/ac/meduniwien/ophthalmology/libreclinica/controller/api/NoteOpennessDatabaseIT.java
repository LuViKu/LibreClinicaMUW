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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;

import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Only a question is an open discrepancy. An annotation or a reason for
 * change is stored Not Applicable, as legacy stores them, and none of either
 * holds a CRF out of source data verification.
 *
 * <p>Before, the SPA stored every new note as New, so an annotation counted
 * as an open query and its CRF could not be verified.
 */
class NoteOpennessDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static final int NEW = 1;
    private static final int CLOSED = 4;
    private static final int NOT_APPLICABLE = 5;
    private static final int FAILED_VALIDATION = 1;
    private static final int ANNOTATION = 2;
    private static final int QUERY = 3;

    private MockMvc mvc() {
        SiteVisibilityFilter filter = new SiteVisibilityFilter(DATA_SOURCE);
        return MockMvcBuilders.standaloneSetup(
                        new DiscrepancyApiController(DATA_SOURCE, filter),
                        new SdvApiController(DATA_SOURCE, filter))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void anAnnotationIsStoredNotApplicable() throws Exception {
        // Event CRF 10 (subject 5): I_HEIGHT_CM is item data 27.
        int note = created(investigator(), 5, "10", "annotation", "Measured with shoes on");
        try {
            assertEquals(NOT_APPLICABLE, NoteFixtures.statusOf(DATA_SOURCE, note));
        } finally {
            NoteFixtures.delete(DATA_SOURCE, note);
        }
    }

    @Test
    void aReasonForChangeIsStoredNotApplicable() throws Exception {
        // Event CRF 11 (subject 5); a reason for change needs the director.
        int note = created(ClinicalWriteFixtures.sessionAs(DATA_SOURCE, "manual_dm"), 5, "11",
                "reason-for-change", "Transcription error corrected");
        try {
            assertEquals(NOT_APPLICABLE, NoteFixtures.statusOf(DATA_SOURCE, note));
        } finally {
            NoteFixtures.delete(DATA_SOURCE, note);
        }
    }

    @Test
    void aQueryStillOpensNew() throws Exception {
        int note = created(monitor(), 7, "15", "query", "Please confirm the height");
        try {
            assertEquals(NEW, NoteFixtures.statusOf(DATA_SOURCE, note));
        } finally {
            NoteFixtures.delete(DATA_SOURCE, note);
        }
    }

    @Test
    void aFailedValidationCheckStillOpensNew() throws Exception {
        int note = created(monitor(), 7, "15", "failed-validation", "Height out of range");
        try {
            assertEquals(NEW, NoteFixtures.statusOf(DATA_SOURCE, note));
        } finally {
            NoteFixtures.delete(DATA_SOURCE, note);
        }
    }

    @Test
    void anAnnotationLeftNewDoesNotHoldItsCrfOutOfSdv() throws Exception {
        // An annotation the SPA stored as New before this fix, on complete,
        // unverified event CRF 4 (item data 11), which has no other note.
        int note = NoteFixtures.insertItemNote(DATA_SOURCE, ANNOTATION, NEW,
                Instant.now().minus(Duration.ofDays(2)), 11, "Measured with shoes on");
        try {
            JsonNode row = sdvRow("4");
            assertEquals("pending", row.get("status").asText());
            assertEquals(0, row.get("openQueries").asInt());
        } finally {
            NoteFixtures.delete(DATA_SOURCE, note);
        }
    }

    @Test
    void anOpenQueryStillHoldsItsCrfOutOfSdv() throws Exception {
        int note = NoteFixtures.insertItemNote(DATA_SOURCE, QUERY, NEW,
                Instant.now().minus(Duration.ofDays(2)), 11, "Height looks low");
        try {
            JsonNode row = sdvRow("4");
            assertEquals("query", row.get("status").asText());
            assertEquals(1, row.get("openQueries").asInt());
        } finally {
            NoteFixtures.delete(DATA_SOURCE, note);
        }
    }

    @Test
    void aFailedValidationCheckHoldsItsCrfOutOfSdv() throws Exception {
        int note = NoteFixtures.insertItemNote(DATA_SOURCE, FAILED_VALIDATION, NEW,
                Instant.now().minus(Duration.ofDays(2)), 11, "Value out of range");
        try {
            JsonNode row = sdvRow("4");
            assertEquals("query", row.get("status").asText());
            assertEquals(1, row.get("openQueries").asInt());
        } finally {
            NoteFixtures.delete(DATA_SOURCE, note);
        }
    }

    @Test
    void anOpenQueryOnTheCrfHeaderHoldsItsCrfOutOfSdv() throws Exception {
        // A query on the interview date of event CRF 4 is about that CRF.
        int note = NoteFixtures.insertEventCrfNote(DATA_SOURCE, QUERY, NEW,
                Instant.now().minus(Duration.ofDays(2)), 4, "date_interviewed", "Interview date?");
        try {
            JsonNode row = sdvRow("4");
            assertEquals("query", row.get("status").asText());
            assertEquals(1, row.get("openQueries").asInt());
        } finally {
            NoteFixtures.delete(DATA_SOURCE, note);
        }
    }

    @Test
    void aClosedQueryOrAnAnnotationOnTheCrfHeaderDoesNotHoldItsCrf() throws Exception {
        int closed = NoteFixtures.insertEventCrfNote(DATA_SOURCE, QUERY, CLOSED,
                Instant.now().minus(Duration.ofDays(2)), 4, "date_interviewed", "Interview date?");
        int annotation = NoteFixtures.insertEventCrfNote(DATA_SOURCE, ANNOTATION, NEW,
                Instant.now().minus(Duration.ofDays(2)), 4, "interviewer_name", "Signed by the deputy");
        try {
            JsonNode row = sdvRow("4");
            assertEquals("pending", row.get("status").asText());
            assertEquals(0, row.get("openQueries").asInt());
        } finally {
            NoteFixtures.delete(DATA_SOURCE, closed);
            NoteFixtures.delete(DATA_SOURCE, annotation);
        }
    }

    @Test
    void aQueryOnTheVisitDoesNotHoldItsCrfOutOfSdv() throws Exception {
        // SDV verifies a CRF; a query on the visit's date is not about the
        // CRF's data, and it holds none of the visit's CRFs.
        int note = NoteFixtures.insertStudyEventNote(DATA_SOURCE, QUERY, NEW,
                Instant.now().minus(Duration.ofDays(2)), NoteFixtures.studyEventOf(DATA_SOURCE, 4),
                "start_date", "Visit date?");
        try {
            JsonNode row = sdvRow("4");
            assertEquals("pending", row.get("status").asText());
            assertEquals(0, row.get("openQueries").asInt());
        } finally {
            NoteFixtures.delete(DATA_SOURCE, note);
        }
    }

    /* ------------------------------------------------------------------ */

    /** Creates a note on I_HEIGHT_CM of the event CRF through the API; returns its id. */
    private int created(MockHttpSession session, int studySubjectId, String eventCrfOid,
                        String type, String description) throws Exception {
        String subject = NoteFixtures.subjectLabel(DATA_SOURCE, studySubjectId);
        String body = mvc().perform(post("/api/v1/discrepancies").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectId\":\"" + subject + "\",\"itemOid\":\"I_HEIGHT_CM\","
                                + "\"eventCrfOid\":\"" + eventCrfOid + "\",\"type\":\"" + type + "\","
                                + "\"description\":\"" + description + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value(type))
                .andReturn().getResponse().getContentAsString();
        return new ObjectMapper().readTree(body).get("id").asInt();
    }

    private JsonNode sdvRow(String eventCrfOid) throws Exception {
        String body = mvc().perform(get("/api/v1/sdv").session(monitor()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        for (JsonNode row : new ObjectMapper().readTree(body)) {
            if (eventCrfOid.equals(row.get("eventCrfOid").asText())) return row;
        }
        throw new AssertionError("event CRF " + eventCrfOid + " is not in the SDV list: " + body);
    }

    private static MockHttpSession monitor() {
        return ClinicalWriteFixtures.sessionAs(DATA_SOURCE, "manual_monitor");
    }

    private static MockHttpSession investigator() {
        return ClinicalWriteFixtures.sessionAs(DATA_SOURCE, "manual_investigator");
    }
}
