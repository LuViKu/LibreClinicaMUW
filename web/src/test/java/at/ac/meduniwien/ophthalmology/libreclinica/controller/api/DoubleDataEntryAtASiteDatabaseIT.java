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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;
import at.ac.meduniwien.ophthalmology.libreclinica.service.crf.CrfFileStorageService;
import at.ac.meduniwien.ophthalmology.libreclinica.service.crf.EventCrfPresenceRegistry;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RetinalResultItemDataPopulator;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Double data entry at a site follows the site's own event definition CRF,
 * where the site overrides the study's, in data entry as in SDV.
 *
 * <p>Legacy site administration writes a site's own row for a CRF whenever
 * one of its settings differs from the study's, double data entry among
 * them. Data entry and SDV must read the same row: otherwise one asks for a
 * second pass the other does not wait for, or SDV waits for a second pass
 * data entry never offers.
 *
 * <p>Fixture: two sites of Default Study, each with a subject whose V2 visit
 * (event definition 2, CRF 1) has its first pass complete. At the first
 * site, the site's own row turns double data entry on while the study's row
 * (event definition CRF 2) leaves it off; at the second, the reverse. The
 * requests come from the director, who sees every site of the study.
 */
class DoubleDataEntryAtASiteDatabaseIT extends AbstractApiControllerDatabaseIT {

    /** Double data entry on at the site, off in the study. */
    private static int onAtTheSite;
    /** Double data entry off at the site, on in the study. */
    private static int offAtTheSite;

    @BeforeAll
    static void seedTwoSitesThatOverrideTheStudy() throws SQLException {
        onAtTheSite = firstPassAtASite("dde-on", true);
        offAtTheSite = firstPassAtASite("dde-off", false);
        ClinicalWriteFixtures.execute(DATA_SOURCE,
                "UPDATE event_definition_crf SET double_entry = false WHERE event_definition_crf_id = 2");
    }

    @Test
    void aSiteThatTurnsDoubleDataEntryOnGetsItsSecondPass() throws Exception {
        String id = String.valueOf(onAtTheSite);
        mvc().perform(get("/api/v1/eventCrfs/" + id + "/dde-pass").session(director()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pass").value("2"));
        assertFalse(listedForVerification().contains(id), "SDV waits for the second pass");

        mvc().perform(post("/api/v1/eventCrfs/" + id + "/dde-commit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"values\":{\"I_HEIGHT_CM\":\"170\"}}")
                        .session(director()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("dde-complete"));
        assertTrue(listedForVerification().contains(id), "the second pass completes the CRF");
    }

    @Test
    void aSiteThatTurnsDoubleDataEntryOffNeedsNoSecondPass() throws Exception {
        ClinicalWriteFixtures.execute(DATA_SOURCE,
                "UPDATE event_definition_crf SET double_entry = true WHERE event_definition_crf_id = 2");
        try {
            String id = String.valueOf(offAtTheSite);
            mvc().perform(get("/api/v1/eventCrfs/" + id + "/dde-pass").session(director()))
                    .andExpect(status().isConflict());
            assertTrue(listedForVerification().contains(id), "its first pass is all it needs");
        } finally {
            ClinicalWriteFixtures.execute(DATA_SOURCE,
                    "UPDATE event_definition_crf SET double_entry = false WHERE event_definition_crf_id = 2");
        }
    }

    /**
     * A site of Default Study with its own row for CRF 1 at V2, and a subject
     * there whose V2 CRF has its first pass complete, keyed by the
     * coordinator.
     *
     * @return the event CRF id
     */
    private static int firstPassAtASite(String name, boolean doubleEntryAtTheSite)
            throws SQLException {
        int site = ClinicalWriteFixtures.insert(DATA_SOURCE,
                "INSERT INTO study (parent_study_id, unique_identifier, secondary_identifier, "
                        + "name, summary, date_planned_start, date_planned_end, date_created, "
                        + "owner_id, type_id, status_id, principal_investigator, facility_name, "
                        + "facility_city, facility_state, facility_zip, facility_country, "
                        + "facility_recruitment_status, facility_contact_name, facility_contact_degree, "
                        + "facility_contact_phone, facility_contact_email, protocol_type, "
                        + "protocol_description, protocol_date_verification, phase, "
                        + "expected_total_enrollment, sponsor, collaborators, medline_identifier, "
                        + "url, url_description, conditions, keywords, eligibility, gender, "
                        + "age_max, age_min, healthy_volunteer_accepted, purpose, allocation, "
                        + "masking, control, assignment, endpoint, interventions, duration, "
                        + "selection, timing, official_title, results_reference, oc_oid) "
                        + "VALUES (1, '" + name + "', '" + name + "', '" + name + "', '', NOW(), NOW(), "
                        + "NOW(), 1, 1, 1, 'default', '', '', '', '', '', '', '', '', '', '', "
                        + "'observational', '', NOW(), 'default', 0, 'default', '', '', '', '', '', '', "
                        + "'', 'both', '', '', false, 'Natural History', '', '', '', '', '', '', "
                        + "'longitudinal', 'Convenience Sample', 'Retrospective', '', false, "
                        + "'S_" + name.replace("-", "").toUpperCase() + "') RETURNING study_id");
        ClinicalWriteFixtures.execute(DATA_SOURCE,
                "INSERT INTO event_definition_crf (study_event_definition_id, study_id, crf_id, "
                        + "required_crf, double_entry, require_all_text_filled, decision_conditions, "
                        + "null_values, default_version_id, status_id, owner_id, date_created, ordinal, "
                        + "electronic_signature, hide_crf, source_data_verification_code, "
                        + "selected_version_ids, parent_id) "
                        + "SELECT study_event_definition_id, " + site + ", crf_id, required_crf, "
                        + doubleEntryAtTheSite + ", require_all_text_filled, decision_conditions, "
                        + "null_values, default_version_id, status_id, owner_id, now(), ordinal, "
                        + "electronic_signature, hide_crf, source_data_verification_code, "
                        + "selected_version_ids, event_definition_crf_id "
                        + "FROM event_definition_crf WHERE event_definition_crf_id = 2");
        int person = ClinicalWriteFixtures.insert(DATA_SOURCE,
                "INSERT INTO subject (status_id, gender, unique_identifier, date_created, owner_id, "
                        + "dob_collected) VALUES (1, 'f', 'IT-" + name + "', now(), 1, false) "
                        + "RETURNING subject_id");
        int subject = ClinicalWriteFixtures.insert(DATA_SOURCE,
                "INSERT INTO study_subject (label, subject_id, study_id, status_id, enrollment_date, "
                        + "date_created, owner_id, oc_oid) VALUES ('" + name + "', " + person + ", "
                        + site + ", 1, now(), now(), 1, 'SS_" + name.replace("-", "").toUpperCase()
                        + "') RETURNING study_subject_id");
        int visit = ClinicalWriteFixtures.insert(DATA_SOURCE,
                "INSERT INTO study_event (study_event_definition_id, study_subject_id, sample_ordinal, "
                        + "date_start, owner_id, status_id, date_created, subject_event_status_id, "
                        + "start_time_flag, end_time_flag) VALUES (2, " + subject + ", 1, now(), 1, 1, "
                        + "now(), 3, false, false) RETURNING study_event_id");
        int eventCrf = ClinicalWriteFixtures.insert(DATA_SOURCE,
                "INSERT INTO event_crf (study_event_id, crf_version_id, date_interviewed, "
                        + "completion_status_id, status_id, date_completed, owner_id, date_created, "
                        + "study_subject_id, electronic_signature_status, sdv_status) VALUES ("
                        + visit + ", 1, now(), 1, 1, now(), "
                        + ClinicalWriteFixtures.userId(DATA_SOURCE, "manual_crc") + ", now(), "
                        + subject + ", false, false) RETURNING event_crf_id");
        ClinicalWriteFixtures.execute(DATA_SOURCE,
                "INSERT INTO item_data (item_id, event_crf_id, status_id, value, date_created, "
                        + "owner_id, ordinal, deleted) SELECT item_id, " + eventCrf + ", 1, '170', "
                        + "now(), 1, 1, false FROM item WHERE oc_oid = 'I_HEIGHT_CM'");
        return eventCrf;
    }

    private static MockMvc mvc() {
        SiteVisibilityFilter filter = new SiteVisibilityFilter(DATA_SOURCE);
        return ProductionMvc.standalone(
                        new EventCrfsApiController(DATA_SOURCE, filter,
                                Mockito.mock(CrfFileStorageService.class),
                                new EventCrfPresenceRegistry(),
                                new RetinalResultItemDataPopulator(DATA_SOURCE)),
                        new SdvApiController(DATA_SOURCE, filter))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private static MockHttpSession director() {
        return ClinicalWriteFixtures.sessionAs(DATA_SOURCE, "manual_dm");
    }

    private static List<String> listedForVerification() throws Exception {
        String body = mvc().perform(get("/api/v1/sdv").session(director()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> listed = new ArrayList<>();
        for (JsonNode row : new ObjectMapper().readTree(body)) {
            listed.add(row.get("eventCrfOid").asText());
        }
        return listed;
    }
}
