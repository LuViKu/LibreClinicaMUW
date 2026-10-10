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

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;
import at.ac.meduniwien.ophthalmology.libreclinica.service.crf.CrfFileStorageService;
import at.ac.meduniwien.ophthalmology.libreclinica.service.crf.EventCrfPresenceRegistry;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RetinalResultItemDataPopulator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The CRF view shows each existing note on the item it is about.
 *
 * <p>Seed: event CRF 9 has note 5 on I_CONSENT_DATE (section S_IDENT) and
 * notes 6 and 7 on I_HEIGHT_CM (section S_VITALS), all New queries.
 *
 * <p>Before, the notes were read without their item mapping, so the roll-up
 * mapped no note to an item and every section counted no open query: the
 * read-only CRF offered only "new note" on every item.
 */
class EventCrfNotesRollupDatabaseIT extends AbstractApiControllerDatabaseIT {

    @TempDir
    static Path attachments;

    private MockMvc mvc() {
        CrfFileStorageService storage = new CrfFileStorageService() {
            @Override
            public Path baseDir() {
                return attachments;
            }
        };
        return ProductionMvc.standalone(
                        new EventCrfsApiController(DATA_SOURCE, new SiteVisibilityFilter(DATA_SOURCE),
                                storage, new EventCrfPresenceRegistry(),
                                new RetinalResultItemDataPopulator(DATA_SOURCE)))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void eachNoteIsMappedToItsItem() throws Exception {
        mvc().perform(get("/api/v1/eventCrfs/9/notes").session(monitor()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(3))
                .andExpect(jsonPath("$.openCount").value(3))
                .andExpect(jsonPath("$.byItemOid.I_CONSENT_DATE.totalCount").value(1))
                .andExpect(jsonPath("$.byItemOid.I_CONSENT_DATE.noteIds").value(containsInAnyOrder("5")))
                .andExpect(jsonPath("$.byItemOid.I_HEIGHT_CM.openCount").value(2))
                .andExpect(jsonPath("$.byItemOid.I_HEIGHT_CM.status").value("open"))
                .andExpect(jsonPath("$.byItemOid.I_HEIGHT_CM.noteIds").value(containsInAnyOrder("6", "7")));
    }

    @Test
    void eachSectionCountsItsOpenQueries() throws Exception {
        mvc().perform(get("/api/v1/eventCrfs/9/section-status").session(monitor()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.sectionOid=='S_IDENT')].openQueries").value(containsInAnyOrder(1)))
                .andExpect(jsonPath("$[?(@.sectionOid=='S_VITALS')].openQueries").value(containsInAnyOrder(2)));
    }

    /**
     * An annotation the SPA stored as New before annotations were stored
     * Not Applicable asks nobody anything: the CRF view does not count it as
     * an open query, as the SDV page does not. Event CRF 4 (item data 11)
     * has no other note.
     */
    @Test
    void anAnnotationLeftNewIsNotAnOpenQueryInTheCrfView() throws Exception {
        int note = NoteFixtures.insertItemNote(DATA_SOURCE, 2, 1,
                Instant.now().minus(Duration.ofDays(2)), 11, "Measured with shoes on");
        try {
            mvc().perform(get("/api/v1/eventCrfs/4/notes").session(monitor()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalCount").value(1))
                    .andExpect(jsonPath("$.openCount").value(0))
                    .andExpect(jsonPath("$.byItemOid.*.status").value(containsInAnyOrder("resolved")));
            mvc().perform(get("/api/v1/eventCrfs/4/section-status").session(monitor()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[?(@.openQueries > 0)]").isEmpty());
        } finally {
            NoteFixtures.delete(DATA_SOURCE, note);
        }
    }

    /** A failed validation check is still an open discrepancy in the CRF view. */
    @Test
    void aFailedValidationCheckIsAnOpenQueryInTheCrfView() throws Exception {
        int note = NoteFixtures.insertItemNote(DATA_SOURCE, 1, 1,
                Instant.now().minus(Duration.ofDays(2)), 11, "Value out of range");
        try {
            mvc().perform(get("/api/v1/eventCrfs/4/notes").session(monitor()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.openCount").value(1))
                    .andExpect(jsonPath("$.byItemOid.*.status").value(containsInAnyOrder("open")));
            mvc().perform(get("/api/v1/eventCrfs/4/section-status").session(monitor()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[?(@.openQueries == 1)]").isNotEmpty());
        } finally {
            NoteFixtures.delete(DATA_SOURCE, note);
        }
    }

    private static MockHttpSession monitor() {
        return ClinicalWriteFixtures.sessionAs(DATA_SOURCE, "manual_monitor");
    }
}
