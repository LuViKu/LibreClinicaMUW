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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;


import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import at.ac.meduniwien.ophthalmology.libreclinica.core.ClinicZone;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.core.CoreResources;

/**
 * Characterisation IT for the DR-025 Modality Worklist source — the C-FIND
 * data the Optomed Lumo pulls before it stores anything.
 *
 * <p>Written BEFORE the Phase 3 ingest refactor so the wire contract the
 * camera depends on (token gate, date window, status filter, entry shape)
 * is pinned. Uses only seeded demo rows, so it inserts nothing:
 *
 * <ul>
 *   <li>study_event 3 — M-001, V3 Day 90, 2021-01-04, status 3 (data-entry-started)</li>
 *   <li>study_event 5 — M-002, V2 Day 30, 2020-11-08, status 3</li>
 *   <li>study_event 10 — M-004, V1 Inclusion, 2020-11-02, status 3</li>
 *   <li>study_event 2 — M-001, V2 Day 30, 2020-11-05, status 4 (completed) → must NOT appear</li>
 * </ul>
 */
@SuppressWarnings("null")
class DicomWorklistApiControllerDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static final String TOKEN = "worklist-it-token";

    private static java.util.Properties SAVED_DATAINFO;

    @BeforeAll
    static void overrideToken() throws Exception {
        java.lang.reflect.Field f = CoreResources.class.getDeclaredField("DATAINFO");
        f.setAccessible(true);
        java.util.Properties live = (java.util.Properties) f.get(null);
        assertNotNull(live, "DATAINFO must be set by AbstractApiControllerDatabaseIT");
        SAVED_DATAINFO = new java.util.Properties();
        SAVED_DATAINFO.putAll(live);
        live.setProperty("core.dicom.ingest.token", TOKEN);
    }

    @AfterAll
    static void restoreDatainfo() throws Exception {
        java.lang.reflect.Field f = CoreResources.class.getDeclaredField("DATAINFO");
        f.setAccessible(true);
        java.util.Properties live = (java.util.Properties) f.get(null);
        if (live != null && SAVED_DATAINFO != null) {
            live.clear();
            live.putAll(SAVED_DATAINFO);
        }
    }

    private MockMvc mockMvc() {
        return ProductionMvc.standalone(new DicomWorklistApiController(DATA_SOURCE))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    /* ---------------- token gate ---------------- */

    @Test
    void worklist_withoutToken_is401() throws Exception {
        mockMvc().perform(get("/api/v1/internal/dicom-worklist"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void worklist_withWrongToken_is401() throws Exception {
        mockMvc().perform(get("/api/v1/internal/dicom-worklist")
                .header("X-MUW-Dicom-Token", TOKEN + "-nope"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void worklist_whenTokenNotConfigured_is503() throws Exception {
        java.lang.reflect.Field f = CoreResources.class.getDeclaredField("DATAINFO");
        f.setAccessible(true);
        java.util.Properties live = (java.util.Properties) f.get(null);
        live.setProperty("core.dicom.ingest.token", "");
        try {
            mockMvc().perform(get("/api/v1/internal/dicom-worklist")
                    .header("X-MUW-Dicom-Token", TOKEN))
                    .andExpect(status().isServiceUnavailable());
        } finally {
            live.setProperty("core.dicom.ingest.token", TOKEN);
        }
    }

    /* ---------------- entry shape ---------------- */

    /**
     * The single scheduled/in-progress visit on 2021-01-04 is M-001's V3 Day 90.
     * This pins every field the sidecar maps onto a PS3.4 K.6 worklist item.
     */
    @Test
    void worklist_singleDay_returnsEntryWithFullShape() throws Exception {
        mockMvc().perform(get("/api/v1/internal/dicom-worklist")
                .param("from", "2021-01-04").param("to", "2021-01-04")
                .header("X-MUW-Dicom-Token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value("2021-01-04"))
                .andExpect(jsonPath("$.to").value("2021-01-04"))
                .andExpect(jsonPath("$.entries.length()").value(1))
                .andExpect(jsonPath("$.entries[0].studyEventId").value(3))
                .andExpect(jsonPath("$.entries[0].subjectLabel").value("M-001"))
                .andExpect(jsonPath("$.entries[0].gender").value("f"))
                .andExpect(jsonPath("$.entries[0].dateOfBirth").value("1962-01-01"))
                .andExpect(jsonPath("$.entries[0].date").value("2021-01-04"))
                // start_time_flag = false → no meaningful time of day
                .andExpect(jsonPath("$.entries[0].time").doesNotExist())
                .andExpect(jsonPath("$.entries[0].eventLabel").value("V3 Day 90"))
                .andExpect(jsonPath("$.entries[0].modality").value("OP"));
    }

    /**
     * Only scheduled (1) and data-entry-started (3) visits are offered: the
     * completed visit on 2020-11-05 inside the window must not appear, or the
     * camera would list visits that are already closed.
     */
    @Test
    void worklist_range_excludesCompletedVisits() throws Exception {
        mockMvc().perform(get("/api/v1/internal/dicom-worklist")
                .param("from", "2020-11-02").param("to", "2020-11-08")
                .header("X-MUW-Dicom-Token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(2))
                // ordered by date_start, then label
                .andExpect(jsonPath("$.entries[0].studyEventId").value(10))
                .andExpect(jsonPath("$.entries[0].subjectLabel").value("M-004"))
                .andExpect(jsonPath("$.entries[1].studyEventId").value(5))
                .andExpect(jsonPath("$.entries[1].subjectLabel").value("M-002"));
    }

    /* ---------------- window handling ---------------- */

    /** No params → today only; the demo seed has no visit today, so it is empty but well-formed. */
    @Test
    void worklist_withoutParams_defaultsToToday() throws Exception {
        String today = ClinicZone.today().toString();
        mockMvc().perform(get("/api/v1/internal/dicom-worklist")
                .header("X-MUW-Dicom-Token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value(today))
                .andExpect(jsonPath("$.to").value(today))
                .andExpect(jsonPath("$.entries").isArray());
    }

    /** A wildcard-wide range is clamped to 31 days so one C-FIND can't dump the schedule. */
    @Test
    void worklist_wideRange_isClampedTo31Days() throws Exception {
        mockMvc().perform(get("/api/v1/internal/dicom-worklist")
                .param("from", "2020-01-01").param("to", "2021-12-31")
                .header("X-MUW-Dicom-Token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value("2020-01-01"))
                .andExpect(jsonPath("$.to").value("2020-02-01"));
    }

    /** Inverted bounds are swapped rather than returning nothing. */
    @Test
    void worklist_invertedRange_isSwapped() throws Exception {
        mockMvc().perform(get("/api/v1/internal/dicom-worklist")
                .param("from", "2021-01-04").param("to", "2021-01-02")
                .header("X-MUW-Dicom-Token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value("2021-01-02"))
                .andExpect(jsonPath("$.to").value("2021-01-04"))
                .andExpect(jsonPath("$.entries.length()").value(1))
                .andExpect(jsonPath("$.entries[0].studyEventId").value(3));
    }

    /* ---------------- study scoping ---------------- */

    /**
     * A worklist hands the camera subject labels, sex and dates of birth. A
     * handheld on a clinic bench is shared between studies, so a camera
     * enrolled for one study must not be shown another's schedule.
     */
    @Test
    void worklist_isScopedToTheConfiguredStudies() throws Exception {
        java.lang.reflect.Field f = CoreResources.class.getDeclaredField("DATAINFO");
        f.setAccessible(true);
        java.util.Properties live = (java.util.Properties) f.get(null);

        // Unrestricted (the default): the seeded visit is offered.
        mockMvc().perform(get("/api/v1/internal/dicom-worklist")
                .param("from", "2021-01-04").param("to", "2021-01-04")
                .header("X-MUW-Dicom-Token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(1));

        // Scoped to a study that is not the seeded one → nothing is offered.
        live.setProperty("core.dicom.worklist.studyOids", "S_SOME_OTHER_STUDY");
        try {
            mockMvc().perform(get("/api/v1/internal/dicom-worklist")
                    .param("from", "2021-01-04").param("to", "2021-01-04")
                    .header("X-MUW-Dicom-Token", TOKEN))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.entries.length()").value(0));
        } finally {
            live.remove("core.dicom.worklist.studyOids");
        }

        // Scoped to the seeded study → offered again.
        live.setProperty("core.dicom.worklist.studyOids", "S_DEFAULTS1");
        try {
            mockMvc().perform(get("/api/v1/internal/dicom-worklist")
                    .param("from", "2021-01-04").param("to", "2021-01-04")
                    .header("X-MUW-Dicom-Token", TOKEN))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.entries.length()").value(1))
                    .andExpect(jsonPath("$.entries[0].subjectLabel").value("M-001"));
        } finally {
            live.remove("core.dicom.worklist.studyOids");
        }
    }

    /** Unparseable dates fall back to the default window instead of 400/500. */
    @Test
    void worklist_garbageDates_fallBackToToday() throws Exception {
        String today = ClinicZone.today().toString();
        mockMvc().perform(get("/api/v1/internal/dicom-worklist")
                .param("from", "not-a-date").param("to", "also-not")
                .header("X-MUW-Dicom-Token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value(today))
                .andExpect(jsonPath("$.to").value(today));
    }

    /* ---------------- DR-040 phase 3: the camera's place ---------------- */

    private static int intQuery(String sql) throws Exception {
        try (java.sql.Connection c = DATA_SOURCE.getConnection();
             java.sql.PreparedStatement ps = c.prepareStatement(sql);
             java.sql.ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static void exec(String sql) throws Exception {
        try (java.sql.Connection c = DATA_SOURCE.getConnection();
             java.sql.Statement st = c.createStatement()) {
            st.executeUpdate(sql);
        }
    }

    /** The visit ids a camera with this calling AE title is offered for the two November visits. */
    private java.util.Set<Integer> offeredTo(String ae) throws Exception {
        String body = mockMvc().perform(get("/api/v1/internal/dicom-worklist")
                        .param("from", "2020-11-02").param("to", "2020-11-08").param("callingAe", ae)
                        .header("X-MUW-Dicom-Token", TOKEN))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        java.util.Set<Integer> ids = new java.util.TreeSet<>();
        at.ac.meduniwien.ophthalmology.libreclinica.core.util.Json.mapper().readTree(body).get("entries")
                .forEach(e -> ids.add(e.get("studyEventId").asInt()));
        return ids;
    }

    @Test
    void aCameraIsOfferedOnlyTheVisitsAtItsLocation() throws Exception {
        int studyOf5 = intQuery("SELECT ss.study_id FROM study_event se JOIN study_subject ss "
                + "ON ss.study_subject_id = se.study_subject_id WHERE se.study_event_id = 5");
        int studyOf10 = intQuery("SELECT ss.study_id FROM study_event se JOIN study_subject ss "
                + "ON ss.study_subject_id = se.study_subject_id WHERE se.study_event_id = 10");
        int home = intQuery("SELECT location_id FROM location WHERE code = 'MUW-AUGEN'");
        int before = intQuery("SELECT COALESCE(location_id, 0) FROM study WHERE study_id = " + studyOf5);
        exec("INSERT INTO location (code, name, status_id, date_created) VALUES ('WL-B', 'Worklist B', 1, now())");
        int locB = intQuery("SELECT location_id FROM location WHERE code = 'WL-B'");
        at.ac.meduniwien.ophthalmology.libreclinica.service.auth.LocationMode.set(
                at.ac.meduniwien.ophthalmology.libreclinica.service.auth.LocationMode.MULTI);
        try {
            exec("UPDATE study SET location_id = " + locB + " WHERE study_id = " + studyOf5);
            exec("INSERT INTO ingest_source (kind, source_key, display_name, location_id, created_at) "
                    + "VALUES ('dicom_ae', 'WL-IT-CAM-B', 'WL-IT-CAM-B', " + locB + ", now())");
            exec("INSERT INTO ingest_source (kind, source_key, display_name, location_id, created_at) "
                    + "VALUES ('dicom_ae', 'WL-IT-CAM-A', 'WL-IT-CAM-A', " + home + ", now())");

            java.util.Set<Integer> atB = offeredTo("WL-IT-CAM-B");
            java.util.Set<Integer> atA = offeredTo("WL-IT-CAM-A");
            org.junit.jupiter.api.Assertions.assertTrue(atB.contains(5), "B's camera sees B's visit: " + atB);
            org.junit.jupiter.api.Assertions.assertFalse(atA.contains(5), "A's camera never sees it: " + atA);
            org.junit.jupiter.api.Assertions.assertEquals(studyOf10 == studyOf5, atB.contains(10),
                    "visit 10 goes with its own study's location: " + atB);
            org.junit.jupiter.api.Assertions.assertEquals(studyOf10 != studyOf5, atA.contains(10),
                    "and is A's when its study stayed at A: " + atA);
        } finally {
            at.ac.meduniwien.ophthalmology.libreclinica.service.auth.LocationMode.set(
                    at.ac.meduniwien.ophthalmology.libreclinica.service.auth.LocationMode.SINGLE);
            exec("UPDATE study SET location_id = " + (before == 0 ? "NULL" : String.valueOf(before))
                    + " WHERE study_id = " + studyOf5);
            exec("DELETE FROM audit_log_event WHERE audit_table = 'ingest_source'");
            exec("DELETE FROM ingest_source WHERE source_key LIKE 'WL-IT-%'");
            exec("DELETE FROM location WHERE code = 'WL-B'");
        }
    }

    @Test
    void inMultiModeAnUnplacedCameraIsOfferedNothingAndBecomesASourceToAssign() throws Exception {
        at.ac.meduniwien.ophthalmology.libreclinica.service.auth.LocationMode.set(
                at.ac.meduniwien.ophthalmology.libreclinica.service.auth.LocationMode.MULTI);
        try {
            org.junit.jupiter.api.Assertions.assertEquals(java.util.Set.of(), offeredTo("WL-IT-NEW"));
            org.junit.jupiter.api.Assertions.assertEquals(1, intQuery("SELECT count(*) FROM ingest_source "
                    + "WHERE kind = 'dicom_ae' AND source_key = 'WL-IT-NEW' AND location_id IS NULL"),
                    "the title is listed for an administrator to place");

            // An older sidecar that sends no title cannot say whose schedule to show.
            mockMvc().perform(get("/api/v1/internal/dicom-worklist")
                            .param("from", "2020-11-02").param("to", "2020-11-08")
                            .header("X-MUW-Dicom-Token", TOKEN))
                    .andExpect(status().isBadRequest());
        } finally {
            at.ac.meduniwien.ophthalmology.libreclinica.service.auth.LocationMode.set(
                    at.ac.meduniwien.ophthalmology.libreclinica.service.auth.LocationMode.SINGLE);
            exec("DELETE FROM audit_log_event WHERE audit_table = 'ingest_source'");
            exec("DELETE FROM ingest_source WHERE source_key LIKE 'WL-IT-%'");
        }
    }

    @Test
    void inSingleModeEveryCameraIsOfferedTheWholeScheduleAsBefore() throws Exception {
        try {
            org.junit.jupiter.api.Assertions.assertEquals(java.util.Set.of(5, 10), offeredTo("WL-IT-ANY"));
            org.junit.jupiter.api.Assertions.assertEquals(1, intQuery("SELECT count(*) FROM ingest_source "
                    + "WHERE source_key = 'WL-IT-ANY' AND location_id = "
                    + "(SELECT location_id FROM location WHERE code = 'MUW-AUGEN')"),
                    "a new camera is at the one location");
        } finally {
            exec("DELETE FROM audit_log_event WHERE audit_table = 'ingest_source'");
            exec("DELETE FROM ingest_source WHERE source_key LIKE 'WL-IT-%'");
        }
    }
}
