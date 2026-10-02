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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;
import at.ac.meduniwien.ophthalmology.libreclinica.service.crf.CrfFileStorageService;
import at.ac.meduniwien.ophthalmology.libreclinica.service.crf.EventCrfPresenceRegistry;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RetinalResultItemDataPopulator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The SPA's CRF writes refuse an event CRF that is removed, or whose visit,
 * subject or study is (and one that is locked), against a real database.
 * Restoring the CRF stays open and makes it writable again.
 *
 * <p>Seed (Default Study): event CRFs 5, 9, 11 and 16 are in data entry, on
 * subjects M-002, M-004, M-005 and M-007; event CRF 9 is on visit 10.
 */
class ClinicalWriteStateDatabaseIT extends AbstractApiControllerDatabaseIT {

    @TempDir
    static Path attachments;

    private MockMvc mvc() {
        CrfFileStorageService storage = new CrfFileStorageService() {
            @Override
            public Path baseDir() {
                return attachments;
            }
        };
        return MockMvcBuilders.standaloneSetup(new EventCrfsApiController(DATA_SOURCE,
                        new SiteVisibilityFilter(DATA_SOURCE), storage, new EventCrfPresenceRegistry(),
                        new RetinalResultItemDataPopulator(DATA_SOURCE)))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private static MockHttpSession investigator() {
        return ClinicalWriteFixtures.sessionAs(DATA_SOURCE, "manual_investigator");
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static MockMultipartFile pdf() {
        return new MockMultipartFile("file", "scan.pdf", "application/pdf", "%PDF-1.4".getBytes());
    }

    /** The writes of the SPA's CRF endpoints, against event CRF {@code id}. */
    private static List<Supplier<MockHttpServletRequestBuilder>> writes(int id) {
        String crf = "/api/v1/eventCrfs/" + id;
        return List.of(
                () -> json(post(crf + "/items"), "{\"values\":{\"I_WEIGHT_KG\":\"71.0\"}}"),
                () -> post(crf + "/groups/IG_ROWS/rows"),
                () -> delete(crf + "/groups/IG_ROWS/rows/2"),
                () -> multipart(crf + "/items/I_CONSENT_DATE/file").file(pdf()),
                () -> delete(crf + "/items/I_CONSENT_SIGNED/file"),
                () -> post(crf + "/markComplete"),
                () -> post(crf + "/markIncomplete"),
                () -> json(post(crf + "/dde-commit"), "{\"values\":{}}"),
                () -> json(post(crf + "/dde-conflicts/I_HEIGHT_CM/resolve"),
                        "{\"winner\":\"ide\",\"reasonForChange\":\"checked\"}"),
                () -> post(crf + ":autoPopulateRetinal"));
    }

    private void assertEveryWriteRefused(int eventCrfId, String code) throws Exception {
        for (Supplier<MockHttpServletRequestBuilder> write : writes(eventCrfId)) {
            MvcResult result = mvc().perform(write.get().session(investigator())).andReturn();
            String body = result.getResponse().getContentAsString();
            assertEquals(409, result.getResponse().getStatus(),
                    result.getRequest().getMethod() + " " + result.getRequest().getRequestURI() + ": " + body);
            assertTrue(body.contains(code), body);
        }
    }

    @Test
    void aRemovedCrfTakesNoWriteUntilItIsRestored() throws Exception {
        ClinicalWriteFixtures.execute(DATA_SOURCE, "UPDATE event_crf SET status_id = 5 WHERE event_crf_id = 16");

        assertEveryWriteRefused(16, "EVENT_CRF_REMOVED");

        mvc().perform(post("/api/v1/eventCrfs/16/restore")
                        .session(ClinicalWriteFixtures.sessionAs(DATA_SOURCE, "manual_dm")))
                .andExpect(status().isNoContent());
        mvc().perform(json(post("/api/v1/eventCrfs/16/items"), "{\"values\":{\"I_WEIGHT_KG\":\"71.0\"}}")
                        .session(investigator()))
                .andExpect(status().isOk());
    }

    @Test
    void aCrfOnARemovedVisitTakesNoWrite() throws Exception {
        ClinicalWriteFixtures.execute(DATA_SOURCE, "UPDATE study_event SET status_id = 5 WHERE study_event_id = 10");
        try {
            mvc().perform(json(post("/api/v1/eventCrfs/9/items"), "{\"values\":{\"I_WEIGHT_KG\":\"72.0\"}}")
                            .session(investigator()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("EVENT_CRF_REMOVED"));
        } finally {
            ClinicalWriteFixtures.execute(DATA_SOURCE, "UPDATE study_event SET status_id = 1 WHERE study_event_id = 10");
        }
    }

    @Test
    void aCrfOfARemovedSubjectTakesNoWrite() throws Exception {
        ClinicalWriteFixtures.execute(DATA_SOURCE, "UPDATE study_subject SET status_id = 7 WHERE study_subject_id = 5");
        try {
            mvc().perform(json(post("/api/v1/eventCrfs/11/items"), "{\"values\":{\"I_WEIGHT_KG\":\"73.0\"}}")
                            .session(investigator()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("EVENT_CRF_REMOVED"));
        } finally {
            ClinicalWriteFixtures.execute(DATA_SOURCE, "UPDATE study_subject SET status_id = 1 WHERE study_subject_id = 5");
        }
    }

    @Test
    void aCrfOfALockedSubjectTakesNoWrite() throws Exception {
        ClinicalWriteFixtures.execute(DATA_SOURCE, "UPDATE study_subject SET status_id = 6 WHERE study_subject_id = 2");
        try {
            assertEveryWriteRefused(5, "EVENT_CRF_LOCKED");
        } finally {
            ClinicalWriteFixtures.execute(DATA_SOURCE, "UPDATE study_subject SET status_id = 1 WHERE study_subject_id = 2");
        }
    }
}
