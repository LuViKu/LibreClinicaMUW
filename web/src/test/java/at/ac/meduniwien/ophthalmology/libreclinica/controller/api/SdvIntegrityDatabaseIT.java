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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;
import at.ac.meduniwien.ophthalmology.libreclinica.service.crf.CrfFileStorageService;
import at.ac.meduniwien.ophthalmology.libreclinica.service.crf.EventCrfPresenceRegistry;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RetinalResultItemDataPopulator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Source data verification stays consistent with the data, against a real
 * database.
 *
 * <ul>
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

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request,
                                                      String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static boolean sdvStatus(int eventCrfId) throws SQLException {
        return ClinicalWriteFixtures.sdvStatus(DATA_SOURCE, eventCrfId);
    }

    private static void setSdvStatus(int eventCrfId, boolean verified) throws SQLException {
        ClinicalWriteFixtures.setSdvStatus(DATA_SOURCE, eventCrfId, verified);
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
}
