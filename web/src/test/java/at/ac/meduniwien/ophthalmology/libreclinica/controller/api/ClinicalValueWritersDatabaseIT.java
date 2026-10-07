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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RetinalResultItemDataPopulator;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The writers that change CRF values outside the CRF form keep the rules the
 * form keeps, against a real database: the retinal populator, the nAMD
 * clinical-flags panel and the public BCVA portal write nothing into signed
 * (or locked, or removed) data, a value they change withdraws the CRF's
 * source data verification, and the row carries the provenance of the value
 * it now holds.
 *
 * <p>Seed (Default Study): event CRF 2 (M-001, V2) is complete and unsigned;
 * subject M-003 is signed, with its event CRF 6 on visit 7. The nAMD visit
 * CRF (version 21) carries the four flag items; the ophthalmology visit CRF
 * (version 2) the BCVA items.
 */
class ClinicalValueWritersDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static final int ROOT = 1;
    private static final ObjectMapper JSON = new ObjectMapper();

    private MockMvc flags() {
        return MockMvcBuilders.standaloneSetup(
                        new NamdClinicalApiController(DATA_SOURCE, new SiteVisibilityFilter(DATA_SOURCE)))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private MockMvc portal() {
        return MockMvcBuilders.standaloneSetup(new PublicBcvaEntryController(DATA_SOURCE))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    /* ------------------------------------------------------------------ */
    /* Retinal populator                                                   */
    /* ------------------------------------------------------------------ */

    @Test
    void aRetinalValueInAVerifiedCrfWithdrawsTheVerification() throws Exception {
        long job = seedDoneFluidJob(2, "OD", "{\"irf_mm3\": 0.25}");
        ClinicalWriteFixtures.setSdvStatus(DATA_SOURCE, 2, true);
        try {
            new RetinalResultItemDataPopulator(DATA_SOURCE).populateForEventCrf(2, ROOT);

            assertEquals("0.250000", ClinicalWriteFixtures.storedValue(DATA_SOURCE, 2, "I_NAMD_OD_IRF_MM3", 1));
            assertFalse(ClinicalWriteFixtures.sdvStatus(DATA_SOURCE, 2), "the changed value ends the verification");
        } finally {
            ClinicalWriteFixtures.setSdvStatus(DATA_SOURCE, 2, false);
            dropJob(job);
        }
    }

    @Test
    void noRetinalValueIsWrittenIntoASignedCrf() throws Exception {
        long job = seedDoneFluidJob(6, "OD", "{\"irf_mm3\": 0.5}");
        try {
            RetinalResultItemDataPopulator.PopulateResult result =
                    new RetinalResultItemDataPopulator(DATA_SOURCE).populateForEventCrf(6, ROOT);

            assertNull(ClinicalWriteFixtures.storedValue(DATA_SOURCE, 6, "I_NAMD_OD_IRF_MM3", 1));
            assertEquals(0, result.rowsWritten());
            assertTrue(result.warnings().stream().anyMatch(w -> w.contains("is signed")), result.warnings().toString());
        } finally {
            dropJob(job);
        }
    }

    /* ------------------------------------------------------------------ */
    /* nAMD clinical flags                                                 */
    /* ------------------------------------------------------------------ */

    @Test
    void aFlagIsAuditedAndItsChangeWithdrawsTheVerification() throws Exception {
        int eventCrfId = postFlags(3, "{\"od\":{\"hemorrhage\":true}}");
        int row = itemDataId(eventCrfId, "I_NAMD_OD_NEW_HEMORRHAGE");
        assertTrue(valueAudits(row) >= 1, "the item_data triggers audit the flag");

        ClinicalWriteFixtures.setSdvStatus(DATA_SOURCE, eventCrfId, true);
        postFlags(3, "{\"od\":{\"hemorrhage\":true}}");
        assertTrue(ClinicalWriteFixtures.sdvStatus(DATA_SOURCE, eventCrfId), "an unchanged flag keeps it");

        postFlags(3, "{\"od\":{\"hemorrhage\":false}}");
        assertFalse(ClinicalWriteFixtures.sdvStatus(DATA_SOURCE, eventCrfId), "a changed flag withdraws it");
        assertTrue(valueAudits(row) >= 2);
    }

    @Test
    void aFlagIsAPersonsValueWhateverWroteItBefore() throws Exception {
        int eventCrfId = postFlags(4, "{\"os\":{\"hemorrhage\":true}}");
        int row = itemDataId(eventCrfId, "I_NAMD_OS_NEW_HEMORRHAGE");
        ClinicalWriteFixtures.execute(DATA_SOURCE,
                "UPDATE item_data SET source_kind = 'ingest' WHERE item_data_id = " + row);

        postFlags(4, "{\"os\":{\"hemorrhage\":false}}");

        assertNull(sourceKind(row), "the machine provenance goes with the machine's value");
    }

    @Test
    void aSignedVisitTakesNoFlags() throws Exception {
        flags().perform(post("/api/v1/study-events/7/namd-clinical-flags")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"od\":{\"hemorrhage\":true}}")
                        .session(ClinicalWriteFixtures.sessionAs(DATA_SOURCE, "manual_investigator")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("signed")));

        assertEquals(0, count("SELECT count(*) FROM event_crf WHERE study_event_id = 7 AND crf_version_id = 21"),
                "no visit CRF was started on the signed visit either");
    }

    /* ------------------------------------------------------------------ */
    /* Public BCVA portal                                                  */
    /* ------------------------------------------------------------------ */

    @Test
    void thePortalRecordsItsOwnProvenanceAndEndsTheVerification() throws Exception {
        // The ophthalmology visit CRF, with its BCVA items, on the V3 visits.
        ClinicalWriteFixtures.execute(DATA_SOURCE,
                "INSERT INTO event_definition_crf (event_definition_crf_id, study_event_definition_id, study_id, "
                        + "crf_id, required_crf, double_entry, default_version_id, status_id, owner_id, "
                        + "date_created, ordinal) VALUES (9931, 3, 1, 2, false, false, 2, 1, 1, now(), 2)");
        try {
            int eventCrfId = commitBcva(3, "1.0");
            int row = itemDataIdByName(eventCrfId, "VA_OD_ETDRS");
            assertEquals("bcva_portal", sourceKind(row));

            ClinicalWriteFixtures.execute(DATA_SOURCE,
                    "UPDATE item_data SET value = '55', source_kind = 'retinal_inference' WHERE item_data_id = " + row);
            ClinicalWriteFixtures.setSdvStatus(DATA_SOURCE, eventCrfId, true);

            commitBcva(3, "1.0");

            assertEquals("bcva_portal", sourceKind(row), "the portal's value carries the portal's provenance");
            assertFalse(ClinicalWriteFixtures.sdvStatus(DATA_SOURCE, eventCrfId), "the changed value ends the verification");
            assertEquals(1, count("SELECT count(*) FROM audit_log_event WHERE audit_table = 'event_crf' "
                            + "AND audit_log_event_type_id = 32 AND new_value = 'FALSE' AND user_id IS NULL "
                            + "AND entity_id = " + eventCrfId),
                    "the withdrawal names no account: the portal's operator has none");
            assertEquals(0, count("SELECT count(*) FROM audit_log_event WHERE audit_table = 'event_crf' "
                            + "AND audit_log_event_type_id = 32 AND new_value = 'FALSE' AND user_id IS NOT NULL "
                            + "AND entity_id = " + eventCrfId),
                    "nor a stand-in account");
        } finally {
            ClinicalWriteFixtures.execute(DATA_SOURCE,
                    "DELETE FROM event_definition_crf WHERE event_definition_crf_id = 9931");
        }
    }

    @Test
    void thePortalRefusesASignedVisit() throws Exception {
        ClinicalWriteFixtures.execute(DATA_SOURCE,
                "INSERT INTO event_definition_crf (event_definition_crf_id, study_event_definition_id, study_id, "
                        + "crf_id, required_crf, double_entry, default_version_id, status_id, owner_id, "
                        + "date_created, ordinal) VALUES (9932, 1, 1, 2, false, false, 2, 1, 1, now(), 2)");
        try {
            portal().perform(post("/api/v1/public/bcva-entry/commit")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"studyEventId\":7,\"enteredBy\":\"Nurse\",\"values\":{\"OD_BCVA_DECIMAL\":1.0}}"))
                    .andExpect(status().isConflict());

            assertEquals(0, count("SELECT count(*) FROM event_crf WHERE study_event_id = 7 AND crf_version_id = 2"));
        } finally {
            ClinicalWriteFixtures.execute(DATA_SOURCE,
                    "DELETE FROM event_definition_crf WHERE event_definition_crf_id = 9932");
        }
    }

    /* ------------------------------------------------------------------ */

    private int postFlags(int studyEventId, String body) throws Exception {
        MvcResult result = flags().perform(post("/api/v1/study-events/" + studyEventId + "/namd-clinical-flags")
                        .contentType(MediaType.APPLICATION_JSON).content(body)
                        .session(ClinicalWriteFixtures.sessionAs(DATA_SOURCE, "manual_investigator")))
                .andExpect(status().isOk())
                .andReturn();
        return JSON.readTree(result.getResponse().getContentAsString()).get("eventCrfId").asInt();
    }

    private int commitBcva(int studyEventId, String decimal) throws Exception {
        MvcResult result = portal().perform(post("/api/v1/public/bcva-entry/commit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studyEventId\":" + studyEventId + ",\"enteredBy\":\"Nurse\","
                                + "\"values\":{\"OD_BCVA_DECIMAL\":" + decimal + "}}"))
                .andReturn();
        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        return JSON.readTree(result.getResponse().getContentAsString()).get("eventCrfId").asInt();
    }

    private long seedDoneFluidJob(int eventCrfId, String laterality, String payload) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection()) {
            long id;
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO retinal_inference_job "
                            + "(event_crf_id, task, e2e_path, eye_laterality, status, enqueued_at, completed_at) "
                            + "VALUES (?, 'fluid', '/tmp/it.e2e', ?, 'done', NOW(), NOW()) RETURNING job_id")) {
                ps.setInt(1, eventCrfId);
                ps.setString(2, laterality);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    id = rs.getLong(1);
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO retinal_inference_result (job_id, task, output_payload, created_at) "
                            + "VALUES (?, 'fluid', ?::jsonb, NOW())")) {
                ps.setLong(1, id);
                ps.setString(2, payload);
                ps.executeUpdate();
            }
            return id;
        }
    }

    private static void dropJob(long job) throws SQLException {
        ClinicalWriteFixtures.execute(DATA_SOURCE,
                "UPDATE item_data SET source_retinal_job_id = NULL WHERE source_retinal_job_id = " + job);
        ClinicalWriteFixtures.execute(DATA_SOURCE, "DELETE FROM retinal_inference_result WHERE job_id = " + job);
        ClinicalWriteFixtures.execute(DATA_SOURCE, "DELETE FROM retinal_inference_job WHERE job_id = " + job);
    }

    private static int itemDataId(int eventCrfId, String itemOid) throws SQLException {
        return count("SELECT d.item_data_id FROM item_data d JOIN item i ON i.item_id = d.item_id "
                + "WHERE d.event_crf_id = " + eventCrfId + " AND i.oc_oid = '" + itemOid + "'");
    }

    private static int itemDataIdByName(int eventCrfId, String itemName) throws SQLException {
        return count("SELECT d.item_data_id FROM item_data d JOIN item i ON i.item_id = d.item_id "
                + "WHERE d.event_crf_id = " + eventCrfId + " AND i.name = '" + itemName + "'");
    }

    /** Type-1 audit rows ("item value updated") on an item_data row. */
    private static int valueAudits(int itemDataId) throws SQLException {
        return count("SELECT count(*) FROM audit_log_event WHERE audit_table = 'item_data' "
                + "AND audit_log_event_type_id = 1 AND entity_id = " + itemDataId);
    }

    private static String sourceKind(int itemDataId) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT source_kind FROM item_data WHERE item_data_id = ?")) {
            ps.setInt(1, itemDataId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static int count(String sql) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }
}
