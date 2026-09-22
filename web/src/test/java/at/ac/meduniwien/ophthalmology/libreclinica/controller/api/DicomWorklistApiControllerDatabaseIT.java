/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

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
        return MockMvcBuilders.standaloneSetup(new DicomWorklistApiController(DATA_SOURCE))
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
        String today = LocalDate.now().toString();
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
        String today = LocalDate.now().toString();
        mockMvc().perform(get("/api/v1/internal/dicom-worklist")
                .param("from", "not-a-date").param("to", "also-not")
                .header("X-MUW-Dicom-Token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value(today))
                .andExpect(jsonPath("$.to").value(today));
    }
}
