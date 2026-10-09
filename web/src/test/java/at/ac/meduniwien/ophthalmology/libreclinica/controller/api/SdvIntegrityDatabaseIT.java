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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.sql.DataSource;

import at.ac.meduniwien.ophthalmology.libreclinica.core.SecurityManager;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;
import at.ac.meduniwien.ophthalmology.libreclinica.service.crf.CrfFileStorageService;
import at.ac.meduniwien.ophthalmology.libreclinica.service.crf.EventCrfPresenceRegistry;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RetinalResultItemDataPopulator;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

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
        return mvcOn(DATA_SOURCE);
    }

    /** The controllers, reading and writing through {@code dataSource}. */
    private MockMvc mvcOn(DataSource dataSource) {
        SiteVisibilityFilter filter = new SiteVisibilityFilter(DATA_SOURCE);
        CrfFileStorageService storage = new CrfFileStorageService() {
            @Override
            public Path baseDir() {
                return attachments;
            }
        };
        return ProductionMvc.standalone(
                        new EventCrfsApiController(dataSource, filter, storage,
                                new EventCrfPresenceRegistry(),
                                new RetinalResultItemDataPopulator(dataSource)),
                        new SdvApiController(dataSource, filter))
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
    void aRemovedCrfIsNeitherListedNorVerified() throws Exception {
        // Event CRF 15 is complete; removing it keeps its completion date.
        ClinicalWriteFixtures.execute(DATA_SOURCE,
                "UPDATE event_crf SET status_id = 7 WHERE event_crf_id = 15");
        try {
            assertFalse(listedEventCrfs().contains("15"));
            mvc().perform(json(post("/api/v1/sdv/verify"), "{\"eventCrfOids\":[\"15\"]}")
                            .session(monitor()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.rejected").value(hasItem("15")));
            assertFalse(sdvStatus(15));

            setSdvStatus(15, true);
            assertFalse(listedEventCrfs().contains("15"), "not even when it is verified");
        } finally {
            ClinicalWriteFixtures.execute(DATA_SOURCE,
                    "UPDATE event_crf SET status_id = 1, sdv_status = false WHERE event_crf_id = 15");
        }
    }

    @Test
    void aLockedCrfIsVerifiedWithoutACompletionDate() throws Exception {
        // Legacy locks every CRF of a definition, finished or not; event CRF
        // 16 has no completion date.
        ClinicalWriteFixtures.execute(DATA_SOURCE,
                "UPDATE event_crf SET status_id = 6 WHERE event_crf_id = 16");
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
    void aCrfCompletedInTheLegacyUiIsVerified() throws Exception {
        // Outside the SPA a CRF is complete by status (2), whether or not it
        // has the SPA's date: a participant's anonymous submission has none.
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
    void aVerifiedCrfThatIsNotCompleteStaysListedUntilItIsUnverified() throws Exception {
        // Verified while still in data entry, as before only complete CRFs
        // could be.
        setSdvStatus(3, true);
        try {
            assertEquals("verified", listedStatuses().get("3"));

            mvc().perform(json(post("/api/v1/sdv/unverify"),
                            "{\"eventCrfOids\":[\"3\"],\"reason\":\"verified before completion\"}")
                            .session(monitor()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.unverified").value(hasItem("3")));

            assertFalse(listedEventCrfs().contains("3"), "unverified, it is in data entry like the others");
        } finally {
            setSdvStatus(3, false);
        }
    }

    @Test
    void aReopenedCrfIsBackInDataEntry() throws Exception {
        // Completed in the legacy UI: status 2 and both dates.
        ClinicalWriteFixtures.execute(DATA_SOURCE,
                "UPDATE event_crf SET status_id = 2, date_completed = now(), "
                        + "date_validate_completed = now() WHERE event_crf_id = 16");
        try {
            assertTrue(listedEventCrfs().contains("16"));

            mvc().perform(post("/api/v1/eventCrfs/16/markIncomplete").session(investigatorSession()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("in-progress"));

            assertFalse(listedEventCrfs().contains("16"), "the reopened CRF is in data entry");
            mvc().perform(json(post("/api/v1/sdv/verify"), "{\"eventCrfOids\":[\"16\"]}")
                            .session(monitor()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.rejected").value(hasItem("16")));
            assertFalse(sdvStatus(16));
            assertEquals(1, intColumn("SELECT status_id FROM event_crf WHERE event_crf_id = ?", 16),
                    "legacy screens read the status: it is available again");
        } finally {
            ClinicalWriteFixtures.execute(DATA_SOURCE,
                    "UPDATE event_crf SET status_id = 1, date_completed = NULL, "
                            + "date_validate_completed = NULL, sdv_status = false WHERE event_crf_id = 16");
        }
    }

    @Test
    void aReopenedDoubleEntryCrfNeedsItsSecondPassAgain() throws Exception {
        ClinicalWriteFixtures.execute(DATA_SOURCE,
                "UPDATE event_definition_crf SET double_entry = true WHERE event_definition_crf_id = 2");
        // Event CRF 11 with both passes complete.
        ClinicalWriteFixtures.execute(DATA_SOURCE,
                "UPDATE event_crf SET date_validate_completed = now() WHERE event_crf_id = 11");
        // Completing again refuses a CRF with an empty required item, and the
        // seeded CRF 11 holds only some of Demographics' values: give it the rest.
        ClinicalWriteFixtures.execute(DATA_SOURCE,
                "INSERT INTO item_data (item_id, event_crf_id, status_id, value, date_created, owner_id, "
                        + "ordinal, deleted) "
                        + "SELECT v.item_id, 11, 1, v.value, '2001-01-01', 1, 1, false "
                        + "  FROM (VALUES (1, '2024-01-01'), (2, 'Y'), (3, '170'), (4, '70')) AS v(item_id, value) "
                        + " WHERE NOT EXISTS (SELECT 1 FROM item_data d WHERE d.event_crf_id = 11 "
                        + "                    AND d.item_id = v.item_id AND COALESCE(d.deleted, false) = false)");
        try {
            assertTrue(listedEventCrfs().contains("11"));

            mvc().perform(post("/api/v1/eventCrfs/11/markIncomplete").session(investigatorSession()))
                    .andExpect(status().isOk());
            assertFalse(listedEventCrfs().contains("11"), "the first pass is in data entry again");

            mvc().perform(post("/api/v1/eventCrfs/11/markComplete").session(investigatorSession()))
                    .andExpect(status().isOk());
            assertFalse(listedEventCrfs().contains("11"), "the second pass has to be made again");
            mvc().perform(get("/api/v1/eventCrfs/11/dde-pass").session(investigatorSession()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.pass").value("2"));
        } finally {
            ClinicalWriteFixtures.execute(DATA_SOURCE,
                    "DELETE FROM item_data WHERE event_crf_id = 11 AND date_created = '2001-01-01'");
            ClinicalWriteFixtures.execute(DATA_SOURCE,
                    "UPDATE event_definition_crf SET double_entry = false WHERE event_definition_crf_id = 2");
            ClinicalWriteFixtures.execute(DATA_SOURCE,
                    "UPDATE event_crf SET date_completed = '2020-12-12 09:00:00', "
                            + "date_validate_completed = NULL, sdv_status = false WHERE event_crf_id = 11");
            // The reopen took its visit back from completed to data entry started.
            ClinicalWriteFixtures.execute(DATA_SOURCE,
                    "UPDATE study_event SET subject_event_status_id = 4 WHERE study_event_id = 14");
        }
    }

    @Test
    void aDoubleEntryCrfSignedDuringItsFirstPassIsNotVerified() throws Exception {
        ClinicalWriteFixtures.execute(DATA_SOURCE,
                "UPDATE event_definition_crf SET double_entry = true WHERE event_definition_crf_id = 2");
        // Event CRF 16 is in its first pass. Signing the visit stamps it as
        // the sign endpoints do: signed, with a second-pass date.
        ClinicalWriteFixtures.execute(DATA_SOURCE,
                "UPDATE event_crf SET status_id = 8, date_validate_completed = now() "
                        + "WHERE event_crf_id = 16");
        try {
            assertFalse(listedEventCrfs().contains("16"));
            mvc().perform(json(post("/api/v1/sdv/verify"), "{\"eventCrfOids\":[\"16\"]}")
                            .session(monitor()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.rejected").value(hasItem("16")));
            assertFalse(sdvStatus(16));
        } finally {
            ClinicalWriteFixtures.execute(DATA_SOURCE,
                    "UPDATE event_definition_crf SET double_entry = false WHERE event_definition_crf_id = 2");
            ClinicalWriteFixtures.execute(DATA_SOURCE,
                    "UPDATE event_crf SET status_id = 1, date_validate_completed = NULL, "
                            + "sdv_status = false WHERE event_crf_id = 16");
        }
    }

    @Test
    void signingASubjectLeavesItsRemovedCrfsRemoved() throws Exception {
        int subject = newSubject("IT-SIGN");
        int kept = completedVisit(subject, 1, 1, 1);
        // The V2 visit was cancelled, which removed its CRF.
        int cancelled = completedVisit(subject, 2, 5, 7);

        SecurityManager passwords = Mockito.mock(SecurityManager.class);
        Mockito.when(passwords.verifyPassword(ArgumentMatchers.anyString(), ArgumentMatchers.any())).thenReturn(true);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                User.withUsername("manual_investigator").password("x").authorities("ROLE_USER").build(),
                null, List.of()));
        try {
            ProductionMvc.standalone(new SubjectsApiController(DATA_SOURCE, passwords,
                            new SiteVisibilityFilter(DATA_SOURCE)))
                    .setControllerAdvice(new ApiExceptionHandler())
                    .build()
                    .perform(json(post("/api/v1/subjects/SS_ITSIGN/sign"),
                            "{\"password\":\"x\",\"attestation\":true}")
                            .session(investigatorSession()))
                    .andExpect(status().isOk());
        } finally {
            SecurityContextHolder.clearContext();
        }

        assertEquals(8, eventCrfStatus(kept), "the subject's CRFs are signed");
        assertEquals(7, eventCrfStatus(cancelled), "but not the removed one");
        assertFalse(listedEventCrfs().contains(String.valueOf(cancelled)));
    }

    @Test
    void aCrfOfARemovedVisitIsNotOffered() throws Exception {
        // Signed with its subject before signing left removed CRFs alone:
        // the CRF reads signed, its visit is still removed.
        int subject = newSubject("IT-FLIPPED");
        String flipped = String.valueOf(completedVisit(subject, 2, 5, 8));

        assertFalse(listedEventCrfs().contains(flipped));
        mvc().perform(json(post("/api/v1/sdv/verify"), "{\"eventCrfOids\":[\"" + flipped + "\"]}")
                        .session(monitor()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejected").value(hasItem(flipped)));
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

    @Test
    void aCleanSecondPassCompletesDoubleDataEntry() throws Exception {
        ClinicalWriteFixtures.execute(DATA_SOURCE,
                "UPDATE event_definition_crf SET double_entry = true WHERE event_definition_crf_id = 2");
        try {
            // A second clerk keys the same values into event CRF 11.
            mvc().perform(json(post("/api/v1/eventCrfs/11/dde-commit"),
                            "{\"values\":{\"I_HEIGHT_CM\":\"170\",\"I_WEIGHT_KG\":\"64.5\"}}")
                            .session(investigatorSession()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.mismatchCount").value(0))
                    .andExpect(jsonPath("$.status").value("dde-complete"));

            assertTrue(listedEventCrfs().contains("11"),
                    "the completed second pass makes the CRF ready for verification");
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
            // Event CRF 10 is complete, so the row's value needs its reason.
            mvc().perform(json(post("/api/v1/eventCrfs/10/items"),
                            "{\"groups\":[{\"groupOid\":\"IG_READINGS\",\"rowOrdinal\":2,"
                                    + "\"values\":{\"I_BLOOD_PRESSURE_SYS\":\"125\"}}],"
                                    + "\"reasons\":{\"I_BLOOD_PRESSURE_SYS[2]\":\"late reading\"}}")
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
        // A second row of the systolic reading on event CRF 15.
        repeatingGroupOnVersion1();
        ClinicalWriteFixtures.execute(DATA_SOURCE,
                "INSERT INTO item_data (item_id, event_crf_id, status_id, value, date_created, "
                        + "owner_id, ordinal, deleted, source_kind) VALUES (5, 15, 1, '130', now(), 1, 2, false, "
                        + "'modality_baseline')");
        setSdvStatus(15, true);
        try {
            mvc().perform(delete("/api/v1/eventCrfs/15/groups/IG_SDV_ROWS/rows/2")
                            .session(investigatorSession()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.itemDataRowsDeleted").value(1));

            assertFalse(sdvStatus(15));
            // Removing a row hides a value, it does not author one: the value and what
            // produced it stay (ItemDataDAO.updateStatusOnly, not update).
            assertEquals(1, ClinicalWriteFixtures.insert(DATA_SOURCE, "SELECT COUNT(*) FROM item_data "
                    + "WHERE event_crf_id = 15 AND item_id = 5 AND ordinal = 2 AND status_id = 5 AND value = '130' "
                    + "AND source_kind = 'modality_baseline' AND update_id = " + userId("manual_investigator")),
                    "the row deletion changed more than the status");
        } finally {
            setSdvStatus(15, false);
        }
    }

    @Test
    void deletingARowThatIsAlreadyDeletedKeepsTheVerification() throws Exception {
        // A third row on event CRF 10, deleted before the CRF was verified.
        repeatingGroupOnVersion1();
        ClinicalWriteFixtures.execute(DATA_SOURCE,
                "INSERT INTO item_data (item_id, event_crf_id, status_id, value, date_created, "
                        + "owner_id, ordinal, deleted) VALUES (5, 10, 5, '128', now(), 1, 3, false)");
        setSdvStatus(10, true);
        int deletionsBefore = auditRows(13, "item_data");
        try {
            mvc().perform(delete("/api/v1/eventCrfs/10/groups/IG_SDV_ROWS/rows/3")
                            .session(investigatorSession()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.itemDataRowsDeleted").value(0));

            assertTrue(sdvStatus(10), "nothing changed, so the verification stands");
            assertEquals(deletionsBefore, auditRows(13, "item_data"), "and nothing is recorded as deleted");
        } finally {
            setSdvStatus(10, false);
        }
    }

    @Test
    void deletingARowThatHoldsNoDataKeepsTheVerification() throws Exception {
        // Row 7 of the group was added and never filled in.
        repeatingGroupOnVersion1();
        setSdvStatus(15, true);
        try {
            mvc().perform(delete("/api/v1/eventCrfs/15/groups/IG_SDV_ROWS/rows/7")
                            .session(investigatorSession()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.itemDataRowsDeleted").value(0));

            assertTrue(sdvStatus(15), "nothing changed, so the verification stands");
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
    void deletingAFileThatIsAlreadyGoneKeepsTheVerification() throws Exception {
        mvc().perform(multipart("/api/v1/eventCrfs/4/items/I_CONSENT_SIGNED/file").file(pdf())
                        .session(investigatorSession()))
                .andExpect(status().isOk());
        mvc().perform(delete("/api/v1/eventCrfs/4/items/I_CONSENT_SIGNED/file")
                        .session(investigatorSession()))
                .andExpect(status().isNoContent());
        setSdvStatus(4, true);
        try {
            // The item's row is still there, with no file in it.
            mvc().perform(delete("/api/v1/eventCrfs/4/items/I_CONSENT_SIGNED/file")
                            .session(investigatorSession()))
                    .andExpect(status().isNoContent());

            assertTrue(sdvStatus(4), "nothing changed, so the verification stands");
        } finally {
            setSdvStatus(4, false);
        }
    }

    @Test
    void resolvingADoubleEntryConflictToTheFirstPassKeepsTheVerification() throws Exception {
        ClinicalWriteFixtures.execute(DATA_SOURCE,
                "UPDATE event_definition_crf SET double_entry = true WHERE event_definition_crf_id = 2");
        try {
            String firstPass = ClinicalWriteFixtures.storedValue(DATA_SOURCE, 2, "I_HEIGHT_CM", 1);
            // A second clerk keys a height into event CRF 2 that differs from the first.
            mvc().perform(json(post("/api/v1/eventCrfs/2/dde-commit"),
                            "{\"values\":{\"I_HEIGHT_CM\":\"" + firstPass + "1\"}}")
                            .session(investigatorSession()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.mismatchCount").value(1));
            setSdvStatus(2, true);

            // The first pass was right: the stored value stays.
            mvc().perform(json(post("/api/v1/eventCrfs/2/dde-conflicts/I_HEIGHT_CM/resolve"),
                            "{\"winner\":\"ide\",\"reasonForChange\":\"the source says so\"}")
                            .session(ClinicalWriteFixtures.sessionAs(DATA_SOURCE, "manual_dm")))
                    .andExpect(status().isOk());

            assertEquals(firstPass, ClinicalWriteFixtures.storedValue(DATA_SOURCE, 2, "I_HEIGHT_CM", 1));
            assertTrue(sdvStatus(2), "nothing changed, so the verification stands");
        } finally {
            ClinicalWriteFixtures.execute(DATA_SOURCE,
                    "UPDATE event_definition_crf SET double_entry = false WHERE event_definition_crf_id = 2");
            ClinicalWriteFixtures.execute(DATA_SOURCE,
                    "UPDATE event_crf SET date_validate_completed = NULL, sdv_status = false "
                            + "WHERE event_crf_id = 2");
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
    /* A save does not write back an SDV change made while it runs        */
    /* ------------------------------------------------------------------ */

    @Test
    void aWithdrawalMadeWhileASaveRunsStands() throws Exception {
        String weight = ClinicalWriteFixtures.storedValue(DATA_SOURCE, 4, "I_WEIGHT_KG", 1);
        int director = userId("manual_dm");
        setSdvStatus(4, true);
        try {
            // The director withdraws the verification after the save has
            // loaded event CRF 4 and before it is done.
            mvcOn(whileTheRequestReadsItemData(() -> ClinicalWriteFixtures.execute(DATA_SOURCE,
                            "UPDATE event_crf SET sdv_status = false, sdv_update_id = " + director
                                    + " WHERE event_crf_id = 4")))
                    .perform(json(post("/api/v1/eventCrfs/4/items"),
                            "{\"values\":{\"I_WEIGHT_KG\":\"" + weight + "\"}}")
                            .session(investigatorSession()))
                    .andExpect(status().isOk());

            assertFalse(sdvStatus(4), "the save does not put the verification back");
            assertEquals(director, sdvUpdateId(4));
        } finally {
            setSdvStatus(4, false);
        }
    }

    @Test
    void aVerificationMadeWhileASaveRunsStands() throws Exception {
        String weight = ClinicalWriteFixtures.storedValue(DATA_SOURCE, 4, "I_WEIGHT_KG", 1);
        int monitor = userId("manual_monitor");
        try {
            mvcOn(whileTheRequestReadsItemData(() -> ClinicalWriteFixtures.execute(DATA_SOURCE,
                            "UPDATE event_crf SET sdv_status = true, sdv_update_id = " + monitor
                                    + " WHERE event_crf_id = 4")))
                    .perform(json(post("/api/v1/eventCrfs/4/items"),
                            "{\"values\":{\"I_WEIGHT_KG\":\"" + weight + "\"}}")
                            .session(investigatorSession()))
                    .andExpect(status().isOk());

            assertTrue(sdvStatus(4), "the save does not undo the verification");
            assertEquals(monitor, sdvUpdateId(4));
        } finally {
            setSdvStatus(4, false);
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

    /**
     * A subject of Default Study of its own, for a test that changes it as
     * a whole; its OID is {@code SS_} and the label without hyphens.
     */
    private static int newSubject(String label) throws SQLException {
        int person = ClinicalWriteFixtures.insert(DATA_SOURCE,
                "INSERT INTO subject (status_id, gender, unique_identifier, date_created, owner_id, "
                        + "dob_collected) VALUES (1, 'f', '" + label + "', now(), 1, false) "
                        + "RETURNING subject_id");
        return ClinicalWriteFixtures.insert(DATA_SOURCE,
                "INSERT INTO study_subject (label, subject_id, study_id, status_id, enrollment_date, "
                        + "date_created, owner_id, oc_oid) VALUES ('" + label + "', " + person + ", 1, 1, "
                        + "now(), now(), 1, 'SS_" + label.replace("-", "") + "') "
                        + "RETURNING study_subject_id");
    }

    /**
     * A completed visit of the subject at event definition
     * {@code definition}, with its CRF (version 1) complete.
     *
     * @return the event CRF id
     */
    private static int completedVisit(int subject, int definition, int visitStatus, int eventCrfStatus)
            throws SQLException {
        int visit = ClinicalWriteFixtures.insert(DATA_SOURCE,
                "INSERT INTO study_event (study_event_definition_id, study_subject_id, sample_ordinal, "
                        + "date_start, owner_id, status_id, date_created, subject_event_status_id, "
                        + "start_time_flag, end_time_flag) VALUES (" + definition + ", " + subject
                        + ", 1, now(), 1, " + visitStatus + ", now(), 4, false, false) "
                        + "RETURNING study_event_id");
        return ClinicalWriteFixtures.insert(DATA_SOURCE,
                "INSERT INTO event_crf (study_event_id, crf_version_id, date_interviewed, "
                        + "completion_status_id, status_id, date_completed, owner_id, date_created, "
                        + "study_subject_id, electronic_signature_status, sdv_status) VALUES (" + visit
                        + ", 1, now(), 1, " + eventCrfStatus + ", now(), 1, now(), " + subject
                        + ", false, false) RETURNING event_crf_id");
    }

    private static int eventCrfStatus(int eventCrfId) throws SQLException {
        return intColumn("SELECT status_id FROM event_crf WHERE event_crf_id = ?", eventCrfId);
    }

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
        return new ArrayList<>(listedStatuses().keySet());
    }

    /** The SDV list, as event CRF id to row status. */
    private Map<String, String> listedStatuses() throws Exception {
        String body = mvc().perform(get("/api/v1/sdv").session(monitor()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Map<String, String> listed = new LinkedHashMap<>();
        for (JsonNode row : new ObjectMapper().readTree(body)) {
            listed.put(row.get("eventCrfOid").asText(), row.get("status").asText());
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

    /**
     * The test database, except that the first time a request reads item
     * data, and so after it has loaded the event CRF, {@code meanwhile} runs
     * on a connection of its own: another request's change, landing while
     * this one runs.
     */
    private static DataSource whileTheRequestReadsItemData(SqlAction meanwhile) {
        AtomicBoolean ran = new AtomicBoolean();
        ClassLoader loader = SdvIntegrityDatabaseIT.class.getClassLoader();
        return (DataSource) Proxy.newProxyInstance(loader, new Class<?>[] {DataSource.class},
                (_, method, args) -> {
                    Object result = invoke(method, DATA_SOURCE, args);
                    if (!(result instanceof Connection connection)) {
                        return result;
                    }
                    return Proxy.newProxyInstance(loader, new Class<?>[] {Connection.class},
                            (_, m, a) -> {
                                if ("prepareStatement".equals(m.getName()) && a != null
                                        && a[0] instanceof String sql
                                        && sql.toLowerCase(Locale.ROOT).contains("item_data")
                                        && ran.compareAndSet(false, true)) {
                                    meanwhile.run();
                                }
                                return invoke(m, connection, a);
                            });
                });
    }

    private static Object invoke(Method method, Object target, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    @FunctionalInterface
    private interface SqlAction {
        void run() throws SQLException;
    }

    /**
     * A repeating group, IG_SDV_ROWS, holding the systolic reading (item 5)
     * on CRF version 1. Created once; later calls find it there.
     */
    private static void repeatingGroupOnVersion1() throws SQLException {
        ClinicalWriteFixtures.execute(DATA_SOURCE,
                "INSERT INTO item_group (item_group_id, name, crf_id, status_id, date_created, "
                        + "owner_id, oc_oid) SELECT 9901, 'IG_SDV_ROWS', 1, 1, now(), 1, 'IG_SDV_ROWS' "
                        + "WHERE NOT EXISTS (SELECT 1 FROM item_group WHERE item_group_id = 9901)");
        ClinicalWriteFixtures.execute(DATA_SOURCE,
                "INSERT INTO item_group_metadata (item_group_metadata_id, item_group_id, "
                        + "crf_version_id, item_id, ordinal, repeat_max, repeating_group, show_group) "
                        + "SELECT 9901, 9901, 1, 5, 1, 10, true, true "
                        + "WHERE NOT EXISTS (SELECT 1 FROM item_group_metadata "
                        + "WHERE item_group_metadata_id = 9901)");
    }

    private static int auditRows(int auditTypeId, String auditTable) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM audit_log_event WHERE audit_log_event_type_id = ? "
                             + "AND audit_table = ?")) {
            ps.setInt(1, auditTypeId);
            ps.setString(2, auditTable);
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
