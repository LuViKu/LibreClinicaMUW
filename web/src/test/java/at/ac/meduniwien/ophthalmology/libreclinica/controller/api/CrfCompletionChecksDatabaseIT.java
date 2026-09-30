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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

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
        return MockMvcBuilders.standaloneSetup(new EventCrfsApiController(DATA_SOURCE,
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
