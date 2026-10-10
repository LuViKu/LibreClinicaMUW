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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

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
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;

/**
 * The SPA's CRF writes refuse an event CRF that is removed, or whose visit,
 * subject or study is (and one that is locked), against a real database.
 * Restoring the CRF stays open and makes it writable again.
 *
 * <p>A refused write changes nothing: not the CRF's values, rows, files,
 * status, completion or verification.
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
        return ProductionMvc.standalone(new EventCrfsApiController(DATA_SOURCE,
                        new SiteVisibilityFilter(DATA_SOURCE), storage, new EventCrfPresenceRegistry(),
                        new RetinalResultItemDataPopulator(DATA_SOURCE)))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private static MockHttpSession investigator() {
        return ClinicalWriteFixtures.sessionAs(DATA_SOURCE, "manual_investigator");
    }

    private static AbstractMockHttpServletRequestBuilder<?> json(AbstractMockHttpServletRequestBuilder<?> request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static MockMultipartFile pdf() {
        return new MockMultipartFile("file", "scan.pdf", "application/pdf", "%PDF-1.4".getBytes());
    }

    /** The writes of the SPA's CRF endpoints, against event CRF {@code id}. */
    private static List<Supplier<AbstractMockHttpServletRequestBuilder<?>>> writes(int id) {
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

    /** Each write is refused with {@code code}, and none of them changed anything. */
    private void assertEveryWriteRefused(int eventCrfId, String code) throws Exception {
        String before = state(eventCrfId);
        for (Supplier<AbstractMockHttpServletRequestBuilder<?>> write : writes(eventCrfId)) {
            MvcResult result = mvc().perform(write.get().session(investigator())).andReturn();
            String body = result.getResponse().getContentAsString();
            String call = result.getRequest().getMethod() + " " + result.getRequest().getRequestURI();
            assertEquals(409, result.getResponse().getStatus(), call + ": " + body);
            assertTrue(body.contains("\"code\":\"" + code + "\""), body);
            assertTrue(body.contains("\"message\""), body);
            assertEquals(before, state(eventCrfId), call + " changed the CRF");
        }
    }

    /**
     * What a write could change: the event CRF's status, completion and
     * verification, every item_data row of it, and the stored files.
     */
    private static String state(int eventCrfId) throws Exception {
        StringBuilder out = new StringBuilder();
        try (Connection c = DATA_SOURCE.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT status_id, date_completed, date_validate_completed, sdv_status, "
                            + "electronic_signature_status FROM event_crf WHERE event_crf_id = ?")) {
                ps.setInt(1, eventCrfId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    for (int i = 1; i <= 5; i++) out.append(rs.getString(i)).append('|');
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT item_data_id, item_id, ordinal, value, status_id, deleted "
                            + "FROM item_data WHERE event_crf_id = ? ORDER BY item_data_id")) {
                ps.setInt(1, eventCrfId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.append('\n');
                        for (int i = 1; i <= 6; i++) out.append(rs.getString(i)).append('|');
                    }
                }
            }
        }
        try (Stream<Path> files = Files.walk(attachments)) {
            out.append("\nfiles=").append(files.filter(Files::isRegularFile).count());
        }
        return out.toString();
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
                    .andExpect(jsonPath("$.code").value("EVENT_REMOVED"))
                    .andExpect(jsonPath("$.message").isString());
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
                    .andExpect(jsonPath("$.code").value("SUBJECT_REMOVED"))
                    .andExpect(jsonPath("$.message").isString());
        } finally {
            ClinicalWriteFixtures.execute(DATA_SOURCE, "UPDATE study_subject SET status_id = 1 WHERE study_subject_id = 5");
        }
    }

    @Test
    void aCrfOnASignedVisitTakesNoWrite() throws Exception {
        ClinicalWriteFixtures.execute(DATA_SOURCE,
                "UPDATE study_event SET subject_event_status_id = 8 WHERE study_event_id = 10");
        try {
            assertEveryWriteRefused(9, "EVENT_CRF_SIGNED");
        } finally {
            ClinicalWriteFixtures.execute(DATA_SOURCE,
                    "UPDATE study_event SET subject_event_status_id = 3 WHERE study_event_id = 10");
        }
    }

    @Test
    void aCrfOfASignedSubjectTakesNoWrite() throws Exception {
        ClinicalWriteFixtures.execute(DATA_SOURCE, "UPDATE study_subject SET status_id = 8 WHERE study_subject_id = 4");
        try {
            assertEveryWriteRefused(9, "EVENT_CRF_SIGNED");
        } finally {
            ClinicalWriteFixtures.execute(DATA_SOURCE, "UPDATE study_subject SET status_id = 1 WHERE study_subject_id = 4");
        }
    }

    @Test
    void aCrfOfALockedSubjectTakesNoWrite() throws Exception {
        ClinicalWriteFixtures.execute(DATA_SOURCE, "UPDATE study_subject SET status_id = 6 WHERE study_subject_id = 2");
        try {
            assertEveryWriteRefused(5, "SUBJECT_LOCKED");
        } finally {
            ClinicalWriteFixtures.execute(DATA_SOURCE, "UPDATE study_subject SET status_id = 1 WHERE study_subject_id = 2");
        }
    }
}
