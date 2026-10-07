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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.emptyString;
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
import java.util.ArrayList;
import java.util.List;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.EventCRFBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.EventCRFDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;
import at.ac.meduniwien.ophthalmology.libreclinica.service.crf.CrfFileStorageService;
import at.ac.meduniwien.ophthalmology.libreclinica.service.crf.EventCrfPresenceRegistry;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RetinalResultItemDataPopulator;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The checks legacy data entry makes on a CRF's values, made by the server
 * for the SPA, against a real database.
 *
 * <ul>
 *   <li>Mark complete is refused while a required item that is shown has no
 *       value; an item a simple conditional display hides is not
 *       required.</li>
 *   <li>A save is refused when a value fails the item's CRF validation
 *       ({@code func:} / {@code regexp:}), with the CRF author's message,
 *       unless the value already carries an active discrepancy note, as in
 *       legacy.</li>
 * </ul>
 *
 * <p>Demographics v1.0 (CRF version 1): consent date, consent signed (Y/N),
 * height and weight are required; systolic BP is optional. Event CRFs 3, 5, 9
 * and 16 are in data entry. Each test works on its own event CRF and undoes
 * the metadata it changes, as they share one database.
 */
class CrfCompletionChecksDatabaseIT extends AbstractApiControllerDatabaseIT {

    /** item_form_metadata of CRF version 1: consent date, systolic BP and height. */
    private static final int IFM_CONSENT_DATE = 1;
    private static final int IFM_HEIGHT = 3;
    private static final int IFM_BP_SYS = 5;

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

    /* ------------------------------------------------------------------ */
    /* Required items at mark complete                                    */
    /* ------------------------------------------------------------------ */

    @Test
    void aCrfWithEmptyRequiredItemsIsNotMarkedComplete() throws Exception {
        // Event CRF 3 holds only the height.
        mvc().perform(post("/api/v1/eventCrfs/3/markComplete").session(investigator()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field",
                        containsInAnyOrder("I_CONSENT_DATE", "I_CONSENT_SIGNED", "I_WEIGHT_KG")))
                .andExpect(jsonPath("$.message", containsString("Weight (kg)")));

        assertFalse(dateCompletedSet(3), "the CRF stays in data entry");
    }

    @Test
    void aCrfWithEveryRequiredItemIsMarkedComplete() throws Exception {
        // Event CRF 9 holds the consent date and the height; the BP is optional.
        mvc().perform(json(post("/api/v1/eventCrfs/9/items"),
                        "{\"values\":{\"I_CONSENT_SIGNED\":\"Y\",\"I_WEIGHT_KG\":\"80.5\"}}")
                        .session(investigator()))
                .andExpect(status().isOk());

        mvc().perform(post("/api/v1/eventCrfs/9/markComplete").session(investigator()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("complete"));
        assertTrue(dateCompletedSet(9));
    }

    @Test
    void aRequiredItemHiddenByItsConditionIsNotRequired() throws Exception {
        // The consent date shows only when consent is signed.
        ClinicalWriteFixtures.execute(DATA_SOURCE,
                "INSERT INTO scd_item_metadata (id, scd_item_form_metadata_id, control_item_form_metadata_id, "
                        + "control_item_name, option_value, message, version) "
                        + "VALUES (9920, " + IFM_CONSENT_DATE + ", 2, 'I_CONSENT_SIGNED', 'Y', 'Consent date', 1)");
        try {
            // Event CRF 5 holds height and weight.
            mvc().perform(json(post("/api/v1/eventCrfs/5/items"), "{\"values\":{\"I_CONSENT_SIGNED\":\"Y\"}}")
                            .session(investigator()))
                    .andExpect(status().isOk());
            mvc().perform(post("/api/v1/eventCrfs/5/markComplete").session(investigator()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("I_CONSENT_DATE")));

            mvc().perform(json(post("/api/v1/eventCrfs/5/items"), "{\"values\":{\"I_CONSENT_SIGNED\":\"N\"}}")
                            .session(investigator()))
                    .andExpect(status().isOk());
            mvc().perform(post("/api/v1/eventCrfs/5/markComplete").session(investigator()))
                    .andExpect(status().isOk());
            assertTrue(dateCompletedSet(5));
        } finally {
            ClinicalWriteFixtures.execute(DATA_SOURCE, "DELETE FROM scd_item_metadata WHERE id = 9920");
        }
    }

    /* ------------------------------------------------------------------ */
    /* The item's CRF validation on save                                  */
    /* ------------------------------------------------------------------ */

    @Test
    void aValueFailingItsCrfValidationIsNotSaved() throws Exception {
        setValidation(IFM_BP_SYS, "func: range(60, 250)", "Systolic BP must be between 60 and 250 mmHg.");
        try {
            mvc().perform(json(post("/api/v1/eventCrfs/16/items"), "{\"values\":{\"I_BLOOD_PRESSURE_SYS\":\"400\"}}")
                            .session(investigator()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("I_BLOOD_PRESSURE_SYS"))
                    .andExpect(jsonPath("$.errors[0].message",
                            containsString("Systolic BP must be between 60 and 250 mmHg.")));
            assertNull(ClinicalWriteFixtures.storedValue(DATA_SOURCE, 16, "I_BLOOD_PRESSURE_SYS", 1));

            mvc().perform(json(post("/api/v1/eventCrfs/16/items"), "{\"values\":{\"I_BLOOD_PRESSURE_SYS\":\"125\"}}")
                            .session(investigator()))
                    .andExpect(status().isOk());
            assertEquals("125", ClinicalWriteFixtures.storedValue(DATA_SOURCE, 16, "I_BLOOD_PRESSURE_SYS", 1));
        } finally {
            setValidation(IFM_BP_SYS, null, null);
        }
    }

    @Test
    void aRegularExpressionWithoutAMessageFailsWithTheLegacyOne() throws Exception {
        setValidation(IFM_HEIGHT, "regexp: /^\\d{2,3}$/", null);
        try {
            // Event CRF 3's height carries no discrepancy note, which would let it stand.
            mvc().perform(json(post("/api/v1/eventCrfs/3/items"), "{\"values\":{\"I_HEIGHT_CM\":\"1620\"}}")
                            .session(investigator()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("I_HEIGHT_CM"))
                    .andExpect(jsonPath("$.errors[0].message", not(emptyString())));
            assertEquals("162", ClinicalWriteFixtures.storedValue(DATA_SOURCE, 3, "I_HEIGHT_CM", 1));
        } finally {
            setValidation(IFM_HEIGHT, null, null);
        }
    }

    @Test
    void aValueWithADiscrepancyNoteStandsAsInLegacy() throws Exception {
        setValidation(IFM_HEIGHT, "func: range(100, 250)", "Height must be between 100 and 250 cm.");
        // Event CRF 9's height (item_data 24) carries an annotation.
        ClinicalWriteFixtures.execute(DATA_SOURCE,
                "INSERT INTO discrepancy_note (discrepancy_note_id, description, discrepancy_note_type_id, "
                        + "resolution_status_id, date_created, owner_id, entity_type, study_id) "
                        + "VALUES (9921, 'Child participant', 2, 5, now(), 1, 'itemData', 1)");
        ClinicalWriteFixtures.execute(DATA_SOURCE,
                "INSERT INTO dn_item_data_map (item_data_id, discrepancy_note_id, column_name, activated) "
                        + "VALUES (24, 9921, 'value', true)");
        try {
            mvc().perform(json(post("/api/v1/eventCrfs/9/items"), "{\"values\":{\"I_HEIGHT_CM\":\"95\"}}")
                            .session(investigator()))
                    .andExpect(status().isOk());
            assertEquals("95", ClinicalWriteFixtures.storedValue(DATA_SOURCE, 9, "I_HEIGHT_CM", 1));
        } finally {
            setValidation(IFM_HEIGHT, null, null);
            ClinicalWriteFixtures.execute(DATA_SOURCE, "DELETE FROM dn_item_data_map WHERE discrepancy_note_id = 9921");
            ClinicalWriteFixtures.execute(DATA_SOURCE, "DELETE FROM discrepancy_note WHERE discrepancy_note_id = 9921");
        }
    }

    /* ------------------------------------------------------------------ */
    /* A discrepancy note on an empty item                                */
    /* ------------------------------------------------------------------ */

    @Test
    void anEmptyRequiredItemWithANoteDoesNotHoldUpCompletion() throws Exception {
        int ec = newEventCrf();
        storeValue(ec, 1, 1, "2024-01-01");
        storeValue(ec, 2, 1, "Y");
        storeValue(ec, 3, 1, "170");
        mvc().perform(json(post("/api/v1/eventCrfs/" + ec + "/items"), "{\"values\":{\"I_WEIGHT_KG\":\"\"}}")
                        .session(investigator()))
                .andExpect(status().isOk());
        mvc().perform(post("/api/v1/eventCrfs/" + ec + "/markComplete").session(investigator()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("I_WEIGHT_KG")));

        // The site documents why the weight cannot be obtained, as legacy lets it.
        attachNote(9922, rowId(ec, 4, 1), "Scale out of order at the visit");
        try {
            mvc().perform(post("/api/v1/eventCrfs/" + ec + "/markComplete").session(investigator()))
                    .andExpect(status().isOk());
            assertTrue(dateCompletedSet(ec));
        } finally {
            dropNote(9922);
        }
    }

    @Test
    void aValueTheValidationRefusesIsSavedOnceItsNewItemCarriesANote() throws Exception {
        int ec = newEventCrf();
        setValidation(IFM_BP_SYS, "func: range(60, 250)", "Systolic BP must be between 60 and 250 mmHg.");
        try {
            String save = "{\"values\":{\"I_BLOOD_PRESSURE_SYS\":\"280\"}}";
            mvc().perform(json(post("/api/v1/eventCrfs/" + ec + "/items"), save).session(investigator()))
                    .andExpect(status().isBadRequest());
            assertNull(ClinicalWriteFixtures.storedValue(DATA_SOURCE, ec, "I_BLOOD_PRESSURE_SYS", 1));

            // The item has no row yet; the note starts an empty one to sit on.
            discrepancies().perform(json(post("/api/v1/discrepancies"),
                            "{\"subjectId\":\"M-001\",\"itemOid\":\"I_BLOOD_PRESSURE_SYS\","
                                    + "\"eventCrfOid\":\"" + ec + "\",\"type\":\"failed-validation\","
                                    + "\"description\":\"Hypertensive crisis, confirmed by repeat reading\"}")
                            .session(investigator()))
                    .andExpect(status().isCreated());
            assertEquals("", ClinicalWriteFixtures.storedValue(DATA_SOURCE, ec, "I_BLOOD_PRESSURE_SYS", 1));

            mvc().perform(json(post("/api/v1/eventCrfs/" + ec + "/items"), save).session(investigator()))
                    .andExpect(status().isOk());
            assertEquals("280", ClinicalWriteFixtures.storedValue(DATA_SOURCE, ec, "I_BLOOD_PRESSURE_SYS", 1));
        } finally {
            setValidation(IFM_BP_SYS, null, null);
        }
    }

    @Test
    void aNoteStartsNoRowInACrfThatTakesNoWrites() throws Exception {
        // Event CRF 6 is signed.
        discrepancies().perform(json(post("/api/v1/discrepancies"),
                        "{\"subjectId\":\"M-003\",\"itemOid\":\"I_BLOOD_PRESSURE_SYS\","
                                + "\"eventCrfOid\":\"6\",\"type\":\"query\",\"description\":\"Was BP taken?\"}")
                        .session(investigator()))
                .andExpect(status().isNotFound());
        assertNull(ClinicalWriteFixtures.storedValue(DATA_SOURCE, 6, "I_BLOOD_PRESSURE_SYS", 1));
    }

    /* ------------------------------------------------------------------ */
    /* Shown-ness of a required item                                      */
    /* ------------------------------------------------------------------ */

    @Test
    void aConditionInARepeatingGroupIsReadInEachRow() throws Exception {
        // Consent signed (the control) and consent date (shown when signed)
        // repeat together; height and weight are filled.
        repeatingGroup(9940, 1, 2);
        ClinicalWriteFixtures.execute(DATA_SOURCE,
                "INSERT INTO scd_item_metadata (id, scd_item_form_metadata_id, control_item_form_metadata_id, "
                        + "control_item_name, option_value, message, version) "
                        + "VALUES (9941, " + IFM_CONSENT_DATE + ", 2, 'I_CONSENT_SIGNED', 'Y', 'Consent date', 1)");
        try {
            int hiddenInRowTwo = newEventCrf();
            storeValue(hiddenInRowTwo, 3, 1, "170");
            storeValue(hiddenInRowTwo, 4, 1, "70");
            storeValue(hiddenInRowTwo, 2, 1, "Y");
            storeValue(hiddenInRowTwo, 1, 1, "2024-01-01");
            storeValue(hiddenInRowTwo, 2, 2, "N");
            assertEquals(List.of(), missingKeys(hiddenInRowTwo), "row 2's consent date is hidden by row 2's answer");

            int shownInRowTwo = newEventCrf();
            storeValue(shownInRowTwo, 3, 1, "170");
            storeValue(shownInRowTwo, 4, 1, "70");
            storeValue(shownInRowTwo, 2, 1, "N");
            storeValue(shownInRowTwo, 2, 2, "Y");
            assertEquals(List.of("I_CONSENT_DATE[2]"), missingKeys(shownInRowTwo));
        } finally {
            ClinicalWriteFixtures.execute(DATA_SOURCE, "DELETE FROM scd_item_metadata WHERE id = 9941");
            dropGroup(9940);
        }
    }

    @Test
    void anItemTheMetadataHidesIsNotRequiredUnlessARuleShowsIt() throws Exception {
        int ec = newEventCrf();
        fillAllBut(ec, 4);
        ClinicalWriteFixtures.execute(DATA_SOURCE,
                "UPDATE item_form_metadata SET show_item = false WHERE item_form_metadata_id = 4");
        try {
            assertEquals(List.of(), missingKeys(ec));

            ClinicalWriteFixtures.execute(DATA_SOURCE,
                    "INSERT INTO dyn_item_form_metadata (item_form_metadata_id, item_id, crf_version_id, "
                            + "show_item, event_crf_id, version) VALUES (4, 4, 1, true, " + ec + ", 1)");
            assertEquals(List.of("I_WEIGHT_KG"), missingKeys(ec));
        } finally {
            ClinicalWriteFixtures.execute(DATA_SOURCE, "DELETE FROM dyn_item_form_metadata WHERE event_crf_id = " + ec);
            ClinicalWriteFixtures.execute(DATA_SOURCE,
                    "UPDATE item_form_metadata SET show_item = true WHERE item_form_metadata_id = 4");
        }
    }

    @Test
    void anItemInAGroupTheMetadataHidesIsNotRequiredUnlessARuleShowsTheGroup() throws Exception {
        int ec = newEventCrf();
        fillAllBut(ec, 4);
        ClinicalWriteFixtures.execute(DATA_SOURCE,
                "INSERT INTO item_group (item_group_id, name, crf_id, status_id, date_created, owner_id, oc_oid) "
                        + "VALUES (9942, 'IG_HIDDEN', 1, 1, now(), 1, 'IG_HIDDEN')");
        ClinicalWriteFixtures.execute(DATA_SOURCE,
                "INSERT INTO item_group_metadata (item_group_metadata_id, item_group_id, crf_version_id, item_id, "
                        + "ordinal, repeat_max, repeating_group, show_group) VALUES (9942, 9942, 1, 4, 1, 1, false, false)");
        try {
            assertEquals(List.of(), missingKeys(ec));

            ClinicalWriteFixtures.execute(DATA_SOURCE,
                    "INSERT INTO dyn_item_group_metadata (item_group_metadata_id, item_group_id, show_group, "
                            + "event_crf_id, version) VALUES (9942, 9942, true, " + ec + ", 1)");
            assertEquals(List.of("I_WEIGHT_KG"), missingKeys(ec));
        } finally {
            ClinicalWriteFixtures.execute(DATA_SOURCE, "DELETE FROM dyn_item_group_metadata WHERE event_crf_id = " + ec);
            dropGroup(9942);
        }
    }

    @Test
    void everyRowOfARepeatingGroupIsChecked() throws Exception {
        ClinicalWriteFixtures.execute(DATA_SOURCE,
                "UPDATE item_form_metadata SET required = true WHERE item_form_metadata_id = " + IFM_BP_SYS);
        repeatingGroup(9943, 5);
        try {
            int twoRows = newEventCrf();
            fillAllBut(twoRows, 5);
            storeValue(twoRows, 5, 1, "120");
            storeValue(twoRows, 5, 2, "  ");
            assertEquals(List.of("I_BLOOD_PRESSURE_SYS[2]"), missingKeys(twoRows),
                    "a row holding only space is empty");

            int noRow = newEventCrf();
            fillAllBut(noRow, 5);
            assertEquals(List.of("I_BLOOD_PRESSURE_SYS[1]"), missingKeys(noRow),
                    "a group without a row is one empty row");
        } finally {
            dropGroup(9943);
            ClinicalWriteFixtures.execute(DATA_SOURCE,
                    "UPDATE item_form_metadata SET required = false WHERE item_form_metadata_id = " + IFM_BP_SYS);
        }
    }

    @Test
    void aCrfCompleteAlreadyIsNotCheckedAgain() throws Exception {
        int ec = newEventCrf();
        ClinicalWriteFixtures.execute(DATA_SOURCE,
                "UPDATE event_crf SET date_completed = now() WHERE event_crf_id = " + ec);

        mvc().perform(post("/api/v1/eventCrfs/" + ec + "/markComplete").session(investigator()))
                .andExpect(status().isOk());
    }

    /* ------------------------------------------------------------------ */
    /* The CRF validation of rows, unchanged values and blanks            */
    /* ------------------------------------------------------------------ */

    @Test
    void aRowValueFailingItsValidationIsRefusedUnderItsRowKey() throws Exception {
        int ec = newEventCrf();
        setValidation(IFM_BP_SYS, "func: range(60, 250)", "Systolic BP must be between 60 and 250 mmHg.");
        try {
            mvc().perform(json(post("/api/v1/eventCrfs/" + ec + "/items"),
                            "{\"groups\":[{\"groupOid\":\"IG_READINGS\",\"rowOrdinal\":2,"
                                    + "\"values\":{\"I_BLOOD_PRESSURE_SYS\":\"400\"}}]}")
                            .session(investigator()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("I_BLOOD_PRESSURE_SYS[2]"));
            assertNull(ClinicalWriteFixtures.storedValue(DATA_SOURCE, ec, "I_BLOOD_PRESSURE_SYS", 2));
        } finally {
            setValidation(IFM_BP_SYS, null, null);
        }
    }

    @Test
    void anUnchangedValueStoredBeforeTheValidationExistedDoesNotBlockTheSave() throws Exception {
        int ec = newEventCrf();
        storeValue(ec, 5, 1, "400");
        setValidation(IFM_BP_SYS, "func: range(60, 250)", "Systolic BP must be between 60 and 250 mmHg.");
        try {
            mvc().perform(json(post("/api/v1/eventCrfs/" + ec + "/items"),
                            "{\"values\":{\"I_BLOOD_PRESSURE_SYS\":\"400\",\"I_WEIGHT_KG\":\"70\"}}")
                            .session(investigator()))
                    .andExpect(status().isOk());
            assertEquals("70", ClinicalWriteFixtures.storedValue(DATA_SOURCE, ec, "I_WEIGHT_KG", 1));
        } finally {
            setValidation(IFM_BP_SYS, null, null);
        }
    }

    @Test
    void blankingAValidatedValueIsNotRefused() throws Exception {
        int ec = newEventCrf();
        storeValue(ec, 5, 1, "120");
        setValidation(IFM_BP_SYS, "regexp: /^\\d{2,3}$/", "Two or three digits.");
        try {
            mvc().perform(json(post("/api/v1/eventCrfs/" + ec + "/items"),
                            "{\"values\":{\"I_BLOOD_PRESSURE_SYS\":\"\"}}")
                            .session(investigator()))
                    .andExpect(status().isOk());
            assertEquals("", ClinicalWriteFixtures.storedValue(DATA_SOURCE, ec, "I_BLOOD_PRESSURE_SYS", 1));
        } finally {
            setValidation(IFM_BP_SYS, null, null);
        }
    }

    /* ------------------------------------------------------------------ */

    private MockMvc discrepancies() {
        return ProductionMvc.standalone(
                        new DiscrepancyApiController(DATA_SOURCE, new SiteVisibilityFilter(DATA_SOURCE)))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    /** A new visit of M-001 with an event CRF of Demographics v1.0 in data entry, holding nothing. */
    private static int newEventCrf() throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection()) {
            int studyEvent;
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO study_event (study_event_definition_id, study_subject_id, sample_ordinal, "
                            + "date_start, owner_id, status_id, date_created, subject_event_status_id, "
                            + "start_time_flag, end_time_flag) "
                            + "VALUES (1, 1, (SELECT COALESCE(MAX(sample_ordinal), 0) + 1 FROM study_event "
                            + "WHERE study_subject_id = 1 AND study_event_definition_id = 1), "
                            + "now(), 1, 1, now(), 3, false, false) RETURNING study_event_id");
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                studyEvent = rs.getInt(1);
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO event_crf (study_event_id, crf_version_id, date_interviewed, completion_status_id, "
                            + "status_id, owner_id, date_created, study_subject_id, electronic_signature_status, "
                            + "sdv_status) VALUES (?, 1, now(), 1, 1, 1, now(), 1, false, false) "
                            + "RETURNING event_crf_id")) {
                ps.setInt(1, studyEvent);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getInt(1);
                }
            }
        }
    }

    private static void storeValue(int eventCrfId, int itemId, int ordinal, String value) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO item_data (item_id, event_crf_id, status_id, value, date_created, owner_id, "
                             + "ordinal, deleted) VALUES (?, ?, 1, ?, now(), 1, ?, false)")) {
            ps.setInt(1, itemId);
            ps.setInt(2, eventCrfId);
            ps.setString(3, value);
            ps.setInt(4, ordinal);
            ps.executeUpdate();
        }
    }

    /** Every item of Demographics v1.0 filled, but {@code itemId}. */
    private static void fillAllBut(int eventCrfId, int itemId) throws SQLException {
        String[] values = {"2024-01-01", "Y", "170", "70", "120"};
        for (int item = 1; item <= 5; item++) {
            if (item != itemId) storeValue(eventCrfId, item, 1, values[item - 1]);
        }
    }

    private static int rowId(int eventCrfId, int itemId, int ordinal) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT item_data_id FROM item_data WHERE event_crf_id = ? AND item_id = ? AND ordinal = ?")) {
            ps.setInt(1, eventCrfId);
            ps.setInt(2, itemId);
            ps.setInt(3, ordinal);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "item " + itemId + " has a row " + ordinal + " on event_crf " + eventCrfId);
                return rs.getInt(1);
            }
        }
    }

    private static void attachNote(int noteId, int itemDataId, String description) throws SQLException {
        ClinicalWriteFixtures.execute(DATA_SOURCE,
                "INSERT INTO discrepancy_note (discrepancy_note_id, description, discrepancy_note_type_id, "
                        + "resolution_status_id, date_created, owner_id, entity_type, study_id) "
                        + "VALUES (" + noteId + ", '" + description + "', 2, 5, now(), 1, 'itemData', 1)");
        ClinicalWriteFixtures.execute(DATA_SOURCE,
                "INSERT INTO dn_item_data_map (item_data_id, discrepancy_note_id, column_name, activated) "
                        + "VALUES (" + itemDataId + ", " + noteId + ", 'value', true)");
    }

    private static void dropNote(int noteId) throws SQLException {
        ClinicalWriteFixtures.execute(DATA_SOURCE, "DELETE FROM dn_item_data_map WHERE discrepancy_note_id = " + noteId);
        ClinicalWriteFixtures.execute(DATA_SOURCE, "DELETE FROM discrepancy_note WHERE discrepancy_note_id = " + noteId);
    }

    /** A repeating group of Demographics v1.0 holding {@code itemIds}. */
    private static void repeatingGroup(int groupId, int... itemIds) throws SQLException {
        ClinicalWriteFixtures.execute(DATA_SOURCE,
                "INSERT INTO item_group (item_group_id, name, crf_id, status_id, date_created, owner_id, oc_oid) "
                        + "VALUES (" + groupId + ", 'IG_R" + groupId + "', 1, 1, now(), 1, 'IG_R" + groupId + "')");
        int ordinal = 1;
        for (int itemId : itemIds) {
            ClinicalWriteFixtures.execute(DATA_SOURCE,
                    "INSERT INTO item_group_metadata (item_group_metadata_id, item_group_id, crf_version_id, "
                            + "item_id, ordinal, repeat_max, repeating_group, show_group) VALUES ("
                            + (groupId * 10 + ordinal) + ", " + groupId + ", 1, " + itemId + ", " + ordinal
                            + ", 10, true, true)");
            ordinal++;
        }
    }

    private static void dropGroup(int groupId) throws SQLException {
        ClinicalWriteFixtures.execute(DATA_SOURCE, "DELETE FROM item_group_metadata WHERE item_group_id = " + groupId);
        ClinicalWriteFixtures.execute(DATA_SOURCE, "DELETE FROM item_group WHERE item_group_id = " + groupId);
    }

    private static List<String> missingKeys(int eventCrfId) {
        EventCRFBean ecb = new EventCRFDAO(DATA_SOURCE).findByPK(eventCrfId);
        List<String> keys = new ArrayList<>();
        for (RequiredItemsCheck.Missing m : RequiredItemsCheck.missing(DATA_SOURCE, ecb)) keys.add(m.key());
        return keys;
    }

    private static void setValidation(int itemFormMetadataId, String expression, String message)
            throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE item_form_metadata SET regexp = ?, regexp_error_msg = ? "
                             + "WHERE item_form_metadata_id = ?")) {
            ps.setString(1, expression);
            ps.setString(2, message);
            ps.setInt(3, itemFormMetadataId);
            ps.executeUpdate();
        }
    }

    private static boolean dateCompletedSet(int eventCrfId) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT date_completed IS NOT NULL FROM event_crf WHERE event_crf_id = ?")) {
            ps.setInt(1, eventCrfId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        }
    }
}
