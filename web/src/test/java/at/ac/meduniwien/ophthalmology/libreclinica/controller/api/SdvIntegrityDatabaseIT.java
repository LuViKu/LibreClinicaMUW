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
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;
import at.ac.meduniwien.ophthalmology.libreclinica.service.crf.CrfFileStorageService;
import at.ac.meduniwien.ophthalmology.libreclinica.service.crf.EventCrfPresenceRegistry;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RetinalResultItemDataPopulator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Source data verification stays consistent with the data, against a real
 * database.
 *
 * <ul>
 *   <li>Only a complete event CRF can be verified: complete by legacy status
 *       (completed or locked) or by the SPA's completion date, the second
 *       pass's date where double data entry applies. The SDV list offers
 *       nothing else.</li>
 *   <li>A change to the data of a verified event CRF withdraws the
 *       verification, as legacy administrative editing does, with the same
 *       audit row: type 32, TRUE to FALSE, by the editor.</li>
 *   <li>Every un-verify needs a reason, {@code verified: false} included,
 *       and the reason is recorded.</li>
 * </ul>
 *
 * <p>Seed (Default Study): event CRFs 1, 2, 4, 10, 11 and 15 are complete;
 * 3, 5, 9 and 16 are still in data entry; 6 to 8 and 12 to 14 are signed and
 * verified. Event definition CRF 2 carries CRF 1 into the V2 visits (event
 * CRFs 2, 5, 7, 11, 13 and 16).
 */
class SdvIntegrityDatabaseIT extends AbstractApiControllerDatabaseIT {

    /** The {@code event_crf} trigger's "EventCRF SDV Status" audit type. */
    private static final int AUDIT_SDV_STATUS = 32;

    @TempDir
    static Path attachments;

    private MockMvc mvc() {
        SiteVisibilityFilter filter = new SiteVisibilityFilter(DATA_SOURCE);
        CrfFileStorageService storage = new CrfFileStorageService() {
            @Override
            public Path baseDir() {
                return attachments;
            }
        };
        return MockMvcBuilders.standaloneSetup(
                        new EventCrfsApiController(DATA_SOURCE, filter, storage,
                                new EventCrfPresenceRegistry(),
                                new RetinalResultItemDataPopulator(DATA_SOURCE)),
                        new SdvApiController(DATA_SOURCE, filter))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    /* ------------------------------------------------------------------ */
    /* Only a complete CRF can be verified                                */
    /* ------------------------------------------------------------------ */

    @Test
    void theListOffersOnlyCompleteCrfs() throws Exception {
        List<String> listed = listedEventCrfs();

        assertTrue(listed.containsAll(List.of("1", "2", "4", "10", "11", "15",
                "6", "7", "8", "12", "13", "14")), "complete CRFs are listed: " + listed);
        for (String inDataEntry : List.of("3", "5", "9", "16")) {
            assertFalse(listed.contains(inDataEntry),
                    "event CRF " + inDataEntry + " is still in data entry: " + listed);
        }
    }

    @Test
    void aCrfStillInDataEntryIsNotVerified() throws Exception {
        try {
            mvc().perform(json(post("/api/v1/sdv/verify"), "{\"eventCrfOids\":[\"3\"]}")
                            .session(monitor()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.rejected").value(hasItem("3")))
                    .andExpect(jsonPath("$.verified").isEmpty());
            assertFalse(sdvStatus(3));
        } finally {
            setSdvStatus(3, false);
        }
    }

    @Test
    void aCompleteCrfIsVerified() throws Exception {
        try {
            mvc().perform(json(post("/api/v1/sdv/verify"), "{\"eventCrfOids\":[\"4\"]}")
                            .session(monitor()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.verified").value(hasItem("4")));
            assertTrue(sdvStatus(4));
        } finally {
            setSdvStatus(4, false);
        }
    }

    @Test
    void aCrfCompletedInTheLegacyUiIsVerified() throws Exception {
        // Legacy completes a CRF by status (2), without the SPA's date.
        ClinicalWriteFixtures.execute(DATA_SOURCE,
                "UPDATE event_crf SET status_id = 2 WHERE event_crf_id = 16");
        try {
            assertTrue(listedEventCrfs().contains("16"));
            mvc().perform(json(post("/api/v1/sdv/verify"), "{\"eventCrfOids\":[\"16\"]}")
                            .session(monitor()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.verified").value(hasItem("16")));
        } finally {
            ClinicalWriteFixtures.execute(DATA_SOURCE,
                    "UPDATE event_crf SET status_id = 1, sdv_status = false WHERE event_crf_id = 16");
        }
    }

    @Test
    void doubleDataEntryIsCompleteOnlyAfterItsSecondPass() throws Exception {
        ClinicalWriteFixtures.execute(DATA_SOURCE,
                "UPDATE event_definition_crf SET double_entry = true WHERE event_definition_crf_id = 2");
        try {
            // Event CRF 11 has its first pass complete, not its second.
            assertFalse(listedEventCrfs().contains("11"));
            mvc().perform(json(post("/api/v1/sdv/verify"), "{\"eventCrfOids\":[\"11\"]}")
                            .session(monitor()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.rejected").value(hasItem("11")));
            assertFalse(sdvStatus(11));

            ClinicalWriteFixtures.execute(DATA_SOURCE,
                    "UPDATE event_crf SET date_validate_completed = now() WHERE event_crf_id = 11");
            assertTrue(listedEventCrfs().contains("11"));
            mvc().perform(json(post("/api/v1/sdv/verify"), "{\"eventCrfOids\":[\"11\"]}")
                            .session(monitor()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.verified").value(hasItem("11")));
        } finally {
            ClinicalWriteFixtures.execute(DATA_SOURCE,
                    "UPDATE event_definition_crf SET double_entry = false WHERE event_definition_crf_id = 2");
            ClinicalWriteFixtures.execute(DATA_SOURCE,
                    "UPDATE event_crf SET date_validate_completed = NULL, sdv_status = false "
                            + "WHERE event_crf_id = 11");
        }
    }

    /* ------------------------------------------------------------------ */
    /* A change to verified data withdraws the verification               */
    /* ------------------------------------------------------------------ */

    @Test
    void changingAVerifiedValueWithdrawsTheVerification() throws Exception {
        setSdvStatus(4, true);
        int investigator = userId("manual_investigator");
        int withdrawalsBefore = sdvWithdrawalsAuditedFor(4, investigator);
        try {
            // Event CRF 4 is complete, so the change carries a reason.
            mvc().perform(json(post("/api/v1/eventCrfs/4/items"),
                            "{\"values\":{\"I_WEIGHT_KG\":\"82.0\"},"
                                    + "\"reasons\":{\"I_WEIGHT_KG\":\"transcription error\"}}")
                            .session(investigatorSession()))
                    .andExpect(status().isOk());

            assertFalse(sdvStatus(4), "the change withdraws the verification");
            assertEquals(investigator, sdvUpdateId(4), "the editor is recorded");
            assertEquals(withdrawalsBefore + 1, sdvWithdrawalsAuditedFor(4, investigator),
                    "the event_crf trigger writes the audit row, as for legacy editing");
        } finally {
            setSdvStatus(4, false);
        }
    }

    @Test
    void savingAnUnchangedValueKeepsTheVerification() throws Exception {
        String weight = ClinicalWriteFixtures.storedValue(DATA_SOURCE, 4, "I_WEIGHT_KG", 1);
        setSdvStatus(4, true);
        try {
            mvc().perform(json(post("/api/v1/eventCrfs/4/items"),
                            "{\"values\":{\"I_WEIGHT_KG\":\"" + weight + "\"}}")
                            .session(investigatorSession()))
                    .andExpect(status().isOk());

            assertTrue(sdvStatus(4), "nothing changed, so the verification stands");
        } finally {
            setSdvStatus(4, false);
        }
    }

    @Test
    void changingARepeatingRowWithdrawsTheVerification() throws Exception {
        setSdvStatus(10, true);
        try {
            mvc().perform(json(post("/api/v1/eventCrfs/10/items"),
                            "{\"groups\":[{\"groupOid\":\"IG_READINGS\",\"rowOrdinal\":2,"
                                    + "\"values\":{\"I_BLOOD_PRESSURE_SYS\":\"125\"}}]}")
                            .session(investigatorSession()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.groupRowsSaved").value(1));

            assertFalse(sdvStatus(10));
        } finally {
            setSdvStatus(10, false);
        }
    }

    @Test
    void deletingARepeatingRowWithdrawsTheVerification() throws Exception {
        // A repeating group holding the systolic reading on CRF version 1,
        // and a second row of it on event CRF 15.
        ClinicalWriteFixtures.execute(DATA_SOURCE,
                "INSERT INTO item_group (item_group_id, name, crf_id, status_id, date_created, "
                        + "owner_id, oc_oid) VALUES (9901, 'IG_SDV_ROWS', 1, 1, now(), 1, 'IG_SDV_ROWS')");
        ClinicalWriteFixtures.execute(DATA_SOURCE,
                "INSERT INTO item_group_metadata (item_group_metadata_id, item_group_id, "
                        + "crf_version_id, item_id, ordinal, repeat_max, repeating_group, show_group) "
                        + "VALUES (9901, 9901, 1, 5, 1, 10, true, true)");
        ClinicalWriteFixtures.execute(DATA_SOURCE,
                "INSERT INTO item_data (item_id, event_crf_id, status_id, value, date_created, "
                        + "owner_id, ordinal, deleted) VALUES (5, 15, 1, '130', now(), 1, 2, false)");
        setSdvStatus(15, true);
        try {
            mvc().perform(delete("/api/v1/eventCrfs/15/groups/IG_SDV_ROWS/rows/2")
                            .session(investigatorSession()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.itemDataRowsDeleted").value(1));

            assertFalse(sdvStatus(15));
        } finally {
            setSdvStatus(15, false);
        }
    }

    @Test
    void uploadingAFileWithdrawsTheVerification() throws Exception {
        setSdvStatus(1, true);
        try {
            mvc().perform(multipart("/api/v1/eventCrfs/1/items/I_CONSENT_DATE/file").file(pdf())
                            .session(investigatorSession()))
                    .andExpect(status().isOk());

            assertFalse(sdvStatus(1));
        } finally {
            setSdvStatus(1, false);
        }
    }

    @Test
    void deletingAFileWithdrawsTheVerification() throws Exception {
        mvc().perform(multipart("/api/v1/eventCrfs/1/items/I_CONSENT_SIGNED/file").file(pdf())
                        .session(investigatorSession()))
                .andExpect(status().isOk());
        setSdvStatus(1, true);
        try {
            mvc().perform(delete("/api/v1/eventCrfs/1/items/I_CONSENT_SIGNED/file")
                            .session(investigatorSession()))
                    .andExpect(status().isNoContent());

            assertFalse(sdvStatus(1));
        } finally {
            setSdvStatus(1, false);
        }
    }

    @Test
    void resolvingADoubleEntryConflictWithdrawsTheVerification() throws Exception {
        ClinicalWriteFixtures.execute(DATA_SOURCE,
                "UPDATE event_definition_crf SET double_entry = true WHERE event_definition_crf_id = 2");
        try {
            // A second clerk keys a different height into event CRF 2.
            mvc().perform(json(post("/api/v1/eventCrfs/2/dde-commit"),
                            "{\"values\":{\"I_HEIGHT_CM\":\"163\"}}")
                            .session(investigatorSession()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.mismatchCount").value(1));
            setSdvStatus(2, true);

            mvc().perform(json(post("/api/v1/eventCrfs/2/dde-conflicts/I_HEIGHT_CM/resolve"),
                            "{\"winner\":\"dde\",\"reasonForChange\":\"the source says 163\"}")
                            .session(ClinicalWriteFixtures.sessionAs(DATA_SOURCE, "manual_dm")))
                    .andExpect(status().isOk());

            assertEquals("163", ClinicalWriteFixtures.storedValue(DATA_SOURCE, 2, "I_HEIGHT_CM", 1));
            assertFalse(sdvStatus(2));
        } finally {
            ClinicalWriteFixtures.execute(DATA_SOURCE,
                    "UPDATE event_definition_crf SET double_entry = false WHERE event_definition_crf_id = 2");
            ClinicalWriteFixtures.execute(DATA_SOURCE,
                    "UPDATE event_crf SET date_validate_completed = NULL, sdv_status = false "
                            + "WHERE event_crf_id = 2");
        }
    }

    /* ------------------------------------------------------------------ */
    /* Every un-verify needs a reason, and records it                     */
    /* ------------------------------------------------------------------ */

    @Test
    void unverifyRecordsTheReason() throws Exception {
        // Event CRF 6 is seeded verified.
        try {
            mvc().perform(json(post("/api/v1/sdv/unverify"),
                            "{\"eventCrfOids\":[\"6\"],\"reason\":\"source document replaced\"}")
                            .session(monitor()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.unverified").value(hasItem("6")));

            assertFalse(sdvStatus(6));
            assertEquals(1, unverifyReasonsAudited(6, "source document replaced"));
        } finally {
            setSdvStatus(6, true);
        }
    }

    @Test
    void verifiedFalseWithoutAReasonIsRefused() throws Exception {
        try {
            mvc().perform(json(post("/api/v1/sdv/verify"),
                            "{\"eventCrfOids\":[\"7\"],\"verified\":false}")
                            .session(monitor()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(containsString("'reason' is required")));

            assertTrue(sdvStatus(7), "event CRF 7 stays verified");
        } finally {
            setSdvStatus(7, true);
        }
    }

    @Test
    void verifiedFalseWithAReasonUnverifiesAndRecordsTheReason() throws Exception {
        try {
            mvc().perform(json(post("/api/v1/sdv/verify"),
                            "{\"eventCrfOids\":[\"8\"],\"verified\":false,"
                                    + "\"reason\":\"wrong source compared\"}")
                            .session(monitor()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.unverified").value(hasItem("8")));

            assertFalse(sdvStatus(8));
            assertEquals(1, unverifyReasonsAudited(8, "wrong source compared"));
        } finally {
            setSdvStatus(8, true);
        }
    }

    @Test
    void verifiedFalseNeedsARoleThatMayUnverify() throws Exception {
        try {
            mvc().perform(json(post("/api/v1/sdv/verify"),
                            "{\"eventCrfOids\":[\"12\"],\"verified\":false,\"reason\":\"mine\"}")
                            .session(investigatorSession()))
                    .andExpect(status().isForbidden());

            assertTrue(sdvStatus(12), "event CRF 12 stays verified");
        } finally {
            setSdvStatus(12, true);
        }
    }

    /* ------------------------------------------------------------------ */
    /* Helpers                                                            */
    /* ------------------------------------------------------------------ */

    private static MockHttpSession monitor() {
        return ClinicalWriteFixtures.sessionAs(DATA_SOURCE, "manual_monitor");
    }

    private static MockHttpSession investigatorSession() {
        return ClinicalWriteFixtures.sessionAs(DATA_SOURCE, "manual_investigator");
    }

    private static int userId(String userName) {
        return ClinicalWriteFixtures.userId(DATA_SOURCE, userName);
    }

    private List<String> listedEventCrfs() throws Exception {
        String body = mvc().perform(get("/api/v1/sdv").session(monitor()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> listed = new ArrayList<>();
        for (JsonNode row : new ObjectMapper().readTree(body)) {
            listed.add(row.get("eventCrfOid").asText());
        }
        return listed;
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request,
                                                      String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static MockMultipartFile pdf() {
        return new MockMultipartFile("file", "source.pdf", "application/pdf", new byte[] {1, 2, 3});
    }

    private static boolean sdvStatus(int eventCrfId) throws SQLException {
        return ClinicalWriteFixtures.sdvStatus(DATA_SOURCE, eventCrfId);
    }

    private static void setSdvStatus(int eventCrfId, boolean verified) throws SQLException {
        ClinicalWriteFixtures.setSdvStatus(DATA_SOURCE, eventCrfId, verified);
    }

    private static int sdvUpdateId(int eventCrfId) throws SQLException {
        return intColumn("SELECT sdv_update_id FROM event_crf WHERE event_crf_id = ?", eventCrfId);
    }

    private static int sdvWithdrawalsAuditedFor(int eventCrfId, int userId) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM audit_log_event WHERE audit_log_event_type_id = ? "
                             + "AND audit_table = 'event_crf' AND entity_id = ? AND user_id = ? "
                             + "AND old_value = 'TRUE' AND new_value = 'FALSE'")) {
            ps.setInt(1, AUDIT_SDV_STATUS);
            ps.setInt(2, eventCrfId);
            ps.setInt(3, userId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static int unverifyReasonsAudited(int eventCrfId, String reason) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM audit_log_event WHERE audit_log_event_type_id = ? "
                             + "AND audit_table = 'event_crf' AND entity_id = ? "
                             + "AND reason_for_change = ?")) {
            ps.setInt(1, AuditTypeIds.EVENT_CRF_SDV_UNVERIFIED);
            ps.setInt(2, eventCrfId);
            ps.setString(3, reason);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static int intColumn(String sql, int id) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }
}
