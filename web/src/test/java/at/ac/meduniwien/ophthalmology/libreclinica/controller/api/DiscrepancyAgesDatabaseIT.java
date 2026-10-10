/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import at.ac.meduniwien.ophthalmology.libreclinica.testsupport.ProductionMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The notes list and its CSV show how long a note has been open and when its
 * thread last moved, from the dates stored with the note and its answers.
 *
 * <p>Before, the list read {@code days} from a query that has no such column,
 * so every note reported 0 days open and a last activity of the moment of the
 * request.
 */
class DiscrepancyAgesDatabaseIT extends AbstractApiControllerDatabaseIT {

    /** Item data 11: I_HEIGHT_CM on event CRF 4 (subject 2). No seeded note. */
    private static final int ITEM_DATA = 11;

    private MockMvc mvc() {
        return ProductionMvc.standalone(
                        new DiscrepancyApiController(DATA_SOURCE, new SiteVisibilityFilter(DATA_SOURCE)))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void aNoteNobodyAnsweredIsOpenSinceItWasCreated() throws Exception {
        // Seed note 1: a New query from 2020 that nobody has answered.
        Instant created = NoteFixtures.createdAt(DATA_SOURCE, 1);
        long before = ChronoUnit.DAYS.between(created, Instant.now());

        JsonNode note = listed("1");

        long after = ChronoUnit.DAYS.between(created, Instant.now());
        long daysOpen = note.get("daysOpen").asLong();
        assertTrue(daysOpen >= before && daysOpen <= after,
                "days open since " + created + ": " + daysOpen);
        assertTrue(daysOpen > 1000, "a note from 2020 has been open for years: " + daysOpen);
        assertEquals(seconds(created), note.get("lastActivityAt").asText());
    }

    @Test
    void anAnsweredNoteShowsItsLatestAnswer() throws Exception {
        Instant created = Instant.now().minus(Duration.ofDays(10).plusHours(1));
        Instant firstAnswer = created.plus(Duration.ofDays(3));
        Instant latestAnswer = created.plus(Duration.ofDays(6));
        int note = NoteFixtures.insertItemNote(DATA_SOURCE, 3, 2, created, ITEM_DATA, "Height recheck");
        NoteFixtures.insertChild(DATA_SOURCE, note, 2, latestAnswer, "Measured again");
        NoteFixtures.insertChild(DATA_SOURCE, note, 2, firstAnswer, "Looking into it");
        try {
            JsonNode listed = listed(String.valueOf(note));

            assertEquals(10, listed.get("daysOpen").asInt(), "open since its creation");
            assertEquals(seconds(latestAnswer), listed.get("lastActivityAt").asText());
        } finally {
            NoteFixtures.delete(DATA_SOURCE, note);
        }
    }

    @Test
    void aClosedNoteCountsTheDaysUntilItWasClosed() throws Exception {
        Instant created = Instant.now().minus(Duration.ofDays(20).plusHours(1));
        Instant closed = created.plus(Duration.ofDays(7).plusHours(2));
        int note = NoteFixtures.insertItemNote(DATA_SOURCE, 3, 4, created, ITEM_DATA, "Weight recheck");
        NoteFixtures.insertChild(DATA_SOURCE, note, 4, closed, "Confirmed against source");
        try {
            JsonNode listed = listed(String.valueOf(note));

            assertEquals(7, listed.get("daysOpen").asInt(), "open from creation until closed");
            assertEquals(seconds(closed), listed.get("lastActivityAt").asText());
        } finally {
            NoteFixtures.delete(DATA_SOURCE, note);
        }
    }

    @Test
    void theCsvCarriesTheSameAges() throws Exception {
        Instant created = Instant.now().minus(Duration.ofDays(5).plusHours(1));
        int note = NoteFixtures.insertItemNote(DATA_SOURCE, 3, 1, created, ITEM_DATA, "Unanswered question");
        try {
            String csv = mvc().perform(get("/api/v1/discrepancies/export.csv").session(monitor()))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

            String[] cells = null;
            for (String line : csv.split("\r\n")) {
                if (line.startsWith(note + ",")) cells = line.split(",");
            }
            assertNotNull(cells, "note " + note + " is exported: " + csv);
            // ID, Type, Status, Subject, Item OID, Description, Assigned to, Days open, Last activity
            assertEquals("5", cells[7], "days open");
            assertEquals(seconds(created), cells[8], "last activity");
        } finally {
            NoteFixtures.delete(DATA_SOURCE, note);
        }
    }

    /* ------------------------------------------------------------------ */

    private JsonNode listed(String noteId) throws Exception {
        String body = mvc().perform(get("/api/v1/discrepancies").session(monitor()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        for (JsonNode row : new ObjectMapper().readTree(body)) {
            if (noteId.equals(row.get("id").asText())) return row;
        }
        throw new AssertionError("note " + noteId + " is not listed: " + body);
    }

    private static MockHttpSession monitor() {
        return ClinicalWriteFixtures.sessionAs(DATA_SOURCE, "manual_monitor");
    }

    private static String seconds(Instant instant) {
        return instant.truncatedTo(ChronoUnit.SECONDS).toString();
    }
}
