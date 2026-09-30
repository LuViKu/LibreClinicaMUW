/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;

import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;
import at.ac.meduniwien.ophthalmology.libreclinica.service.crf.CrfFileStorageService;
import at.ac.meduniwien.ophthalmology.libreclinica.service.crf.EventCrfPresenceRegistry;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RetinalResultItemDataPopulator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

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
        return MockMvcBuilders.standaloneSetup(
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

    private static MockHttpSession monitor() {
        return ClinicalWriteFixtures.sessionAs(DATA_SOURCE, "manual_monitor");
    }
}
