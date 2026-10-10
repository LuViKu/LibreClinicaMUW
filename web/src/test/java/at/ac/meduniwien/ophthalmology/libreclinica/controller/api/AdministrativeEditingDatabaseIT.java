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

import static org.hamcrest.Matchers.contains;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Status;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.EventCRFBean;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;
import at.ac.meduniwien.ophthalmology.libreclinica.service.crf.CrfFileStorageService;
import at.ac.meduniwien.ophthalmology.libreclinica.service.crf.EventCrfPresenceRegistry;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RetinalResultItemDataPopulator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * A CRF that has been completed stays under administrative editing after it
 * is reopened, against a real database: while the study forces a reason for
 * change ({@code adminForcedReasonForChange}, on by default), a changed value
 * is refused without one, and one given is recorded as a Reason for Change
 * note and on the audit row.
 *
 * <p>Seed (Default Study): event CRFs 2, 10, 11 and 15 are complete, CRF 3 is
 * still in initial data entry and has never been complete. Each test works on
 * its own event CRF, as they share one database.
 */
class AdministrativeEditingDatabaseIT extends AbstractApiControllerDatabaseIT {

    private MockMvc mvc() {
        return ProductionMvc.standalone(new EventCrfsApiController(DATA_SOURCE,
                        new SiteVisibilityFilter(DATA_SOURCE),
                        Mockito.mock(CrfFileStorageService.class),
                        new EventCrfPresenceRegistry(),
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

    private void reopen(int eventCrfId) throws Exception {
        mvc().perform(post("/api/v1/eventCrfs/" + eventCrfId + "/markIncomplete").session(investigator()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("in-progress"));
    }

    @Test
    void aReopenedCrfStillNeedsAReasonForAChange() throws Exception {
        reopen(10);

        mvc().perform(get("/api/v1/eventCrfs/10").session(investigator()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requiresReasonForChange").value(true));

        mvc().perform(json(post("/api/v1/eventCrfs/10/items"), "{\"values\":{\"I_HEIGHT_CM\":\"171\"}}")
                        .session(investigator()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.missingReasonItemOids", contains("I_HEIGHT_CM")));

        assertEquals("170", ClinicalWriteFixtures.storedValue(DATA_SOURCE, 10, "I_HEIGHT_CM", 1),
                "nothing is saved without the reason");
    }

    @Test
    void aReasonGivenAfterReopeningIsRecorded() throws Exception {
        reopen(11);

        mvc().perform(json(post("/api/v1/eventCrfs/11/items"),
                        "{\"values\":{\"I_HEIGHT_CM\":\"168\"},"
                                + "\"reasons\":{\"I_HEIGHT_CM\":\"height re-measured\"}}")
                        .session(investigator()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rfcCreatedCount").value(1));

        assertEquals("168", ClinicalWriteFixtures.storedValue(DATA_SOURCE, 11, "I_HEIGHT_CM", 1));
        assertEquals("height re-measured", reasonForChangeNote(11, "I_HEIGHT_CM", 1));
        assertEquals("height re-measured", reasonOnAuditRow(11, "I_HEIGHT_CM", 1));
    }

    @Test
    void anUnchangedValueNeedsNoReason() throws Exception {
        reopen(2);

        mvc().perform(json(post("/api/v1/eventCrfs/2/items"), "{\"values\":{\"I_HEIGHT_CM\":\"162\"}}")
                        .session(investigator()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rfcCreatedCount").value(0));
    }

    @Test
    void aValueInARepeatingRowNeedsItsReasonToo() throws Exception {
        reopen(15);
        String row = "{\"groups\":[{\"groupOid\":\"IG_READINGS\",\"rowOrdinal\":2,"
                + "\"values\":{\"I_BLOOD_PRESSURE_SYS\":\"121\"}}]";

        mvc().perform(json(post("/api/v1/eventCrfs/15/items"), row + "}").session(investigator()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.missingReasonItemOids", contains("I_BLOOD_PRESSURE_SYS[2]")));
        assertNull(ClinicalWriteFixtures.storedValue(DATA_SOURCE, 15, "I_BLOOD_PRESSURE_SYS", 2));

        mvc().perform(json(post("/api/v1/eventCrfs/15/items"),
                        row + ",\"reasons\":{\"I_BLOOD_PRESSURE_SYS[2]\":\"second reading added\"}}")
                        .session(investigator()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rfcCreatedCount").value(1));
        assertEquals("121", ClinicalWriteFixtures.storedValue(DATA_SOURCE, 15, "I_BLOOD_PRESSURE_SYS", 2));
        assertEquals("second reading added", reasonForChangeNote(15, "I_BLOOD_PRESSURE_SYS", 2));
    }

    @Test
    void aStudyThatDoesNotForceAReasonSavesWithout() throws Exception {
        ClinicalWriteFixtures.execute(DATA_SOURCE,
                "INSERT INTO study_parameter_value (study_parameter_value_id, study_id, value, parameter) "
                        + "VALUES (9910, 1, 'false', 'adminForcedReasonForChange')");
        try {
            mvc().perform(get("/api/v1/eventCrfs/4").session(investigator()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.requiresReasonForChange").value(false));

            reopen(4);
            mvc().perform(json(post("/api/v1/eventCrfs/4/items"), "{\"values\":{\"I_HEIGHT_CM\":\"177\"}}")
                            .session(investigator()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.rfcCreatedCount").value(0));
            assertEquals("177", ClinicalWriteFixtures.storedValue(DATA_SOURCE, 4, "I_HEIGHT_CM", 1));
        } finally {
            ClinicalWriteFixtures.execute(DATA_SOURCE,
                    "DELETE FROM study_parameter_value WHERE study_parameter_value_id = 9910");
        }
    }

    @Test
    void aCrfNeverCompletedNeedsNoReason() throws Exception {
        mvc().perform(get("/api/v1/eventCrfs/3").session(investigator()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requiresReasonForChange").value(false));

        mvc().perform(json(post("/api/v1/eventCrfs/3/items"), "{\"values\":{\"I_HEIGHT_CM\":\"163\"}}")
                        .session(investigator()))
                .andExpect(status().isOk());
        assertEquals("163", ClinicalWriteFixtures.storedValue(DATA_SOURCE, 3, "I_HEIGHT_CM", 1));
    }

    /** The newest Reason for Change note on the value's row, or null. */
    private static String reasonForChangeNote(int eventCrfId, String itemOid, int ordinal) throws SQLException {
        return firstString("SELECT dn.description FROM discrepancy_note dn "
                + "  JOIN dn_item_data_map m ON m.discrepancy_note_id = dn.discrepancy_note_id "
                + "  JOIN item_data d ON d.item_data_id = m.item_data_id "
                + "  JOIN item i ON i.item_id = d.item_id "
                + " WHERE d.event_crf_id = ? AND i.oc_oid = ? AND d.ordinal = ? "
                + "   AND dn.discrepancy_note_type_id = 4 "
                + " ORDER BY dn.discrepancy_note_id DESC LIMIT 1", eventCrfId, itemOid, ordinal);
    }

    /* ------------------------------------------------------------------ */
    /* What counts as completion evidence                                 */
    /* ------------------------------------------------------------------ */

    /**
     * One audit row on an event CRF that has no other evidence. The ids are
     * not event CRFs of the seed: the evidence is read from the audit trail
     * alone.
     */
    @ParameterizedTest(name = "type {1} on {0}, column ''{2}'' -> {3}")
    @CsvSource({
            "9801, 8,   date_completed, true",
            "9802, 10,  status_id,      true",
            "9803, 14,  status_id,      true",
            "9804, 15,  status_id,      true",
            "9805, 16,  status_id,      true",
            "9806, 138, date_completed, true",
            "9807, 11,  date_completed, true",
            "9808, 11,  ,               true",
            "9809, 11,  status_id,      false",
            "9810, 1,   date_completed, false",
    })
    void anAuditRowIsCompletionEvidenceOnlyOfItsKind(int eventCrfId, int auditType, String column,
                                                       boolean completed) throws SQLException {
        insertAudit("event_crf", eventCrfId, auditType, column);

        assertEquals(completed, AdministrativeEditing.everCompleted(DATA_SOURCE, bean(eventCrfId)));
    }

    @Test
    void aCompletionAuditedOnAnotherTableIsNotThisCrfsEvidence() throws SQLException {
        insertAudit("item_data", 9811, 8, "date_completed");

        assertFalse(AdministrativeEditing.everCompleted(DATA_SOURCE, bean(9811)));
    }

    @Test
    void theCrfsOwnStateIsEvidenceWithoutAnyAuditRow() {
        EventCRFBean completed = bean(9812);
        completed.setDateCompleted(new java.util.Date());
        assertTrue(AdministrativeEditing.everCompleted(DATA_SOURCE, completed));

        EventCRFBean secondPassDone = bean(9813);
        secondPassDone.setDateValidateCompleted(new java.util.Date());
        assertTrue(AdministrativeEditing.everCompleted(DATA_SOURCE, secondPassDone), "double data entry");

        EventCRFBean unavailable = bean(9814);
        unavailable.setStatus(Status.UNAVAILABLE);
        assertTrue(AdministrativeEditing.everCompleted(DATA_SOURCE, unavailable), "status unavailable");

        assertFalse(AdministrativeEditing.everCompleted(DATA_SOURCE, bean(9815)));
    }

    private static EventCRFBean bean(int eventCrfId) {
        EventCRFBean ecb = new EventCRFBean();
        ecb.setId(eventCrfId);
        ecb.setStatus(Status.AVAILABLE);
        return ecb;
    }

    private static void insertAudit(String table, int entityId, int auditType, String column) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO audit_log_event (audit_log_event_type_id, audit_date, user_id, audit_table, "
                             + "entity_id, entity_name, old_value, new_value) VALUES (?, now(), 1, ?, ?, ?, '', '')")) {
            ps.setInt(1, auditType);
            ps.setString(2, table);
            ps.setInt(3, entityId);
            ps.setString(4, column);
            ps.executeUpdate();
        }
    }

    /** The reason on the newest reason-for-change audit row of the value's row, or null. */
    private static String reasonOnAuditRow(int eventCrfId, String itemOid, int ordinal) throws SQLException {
        return firstString("SELECT a.reason_for_change FROM audit_log_event a "
                + "  JOIN item_data d ON d.item_data_id = a.entity_id "
                + "  JOIN item i ON i.item_id = d.item_id "
                + " WHERE a.audit_table = 'item_data' AND a.audit_log_event_type_id = "
                + AuditTypeIds.ITEM_DATA_REASON_FOR_CHANGE
                + "   AND d.event_crf_id = ? AND i.oc_oid = ? AND d.ordinal = ? "
                + " ORDER BY a.audit_id DESC LIMIT 1", eventCrfId, itemOid, ordinal);
    }

    private static String firstString(String sql, int eventCrfId, String itemOid, int ordinal)
            throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, eventCrfId);
            ps.setString(2, itemOid);
            ps.setInt(3, ordinal);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }
}
