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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import at.ac.meduniwien.ophthalmology.libreclinica.dao.core.CoreResources;

/**
 * DR-025 — "is this patient on the camera today", asked from the subject page.
 *
 * <p>The Optomed's worklist is the visit schedule filtered to today, and the
 * operator has no way to see the camera's list without walking to the device.
 * This endpoint answers with the same query and the same scope the worklist
 * endpoint serves, so what the page says and what the camera lists cannot
 * differ. These tests pin the four answers: no camera at all, on the list,
 * open but on another day, and out of the camera's study scope.
 *
 * <p>The receiver token and the scope key live in the {@code CoreResources}
 * property bag; each test sets what it needs and the class restores the bag.
 */
class SubjectCameraWorklistDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static final String TOKEN_KEY = "core.dicom.ingest.token";
    private static final String SCOPE_KEY = "core.dicom.worklist.studyOids";

    private static java.util.Properties LIVE_DATAINFO;
    private static java.util.Properties SAVED_DATAINFO;

    @BeforeAll
    static void snapshotDataInfo() throws Exception {
        java.lang.reflect.Field f = CoreResources.class.getDeclaredField("DATAINFO");
        f.setAccessible(true);
        LIVE_DATAINFO = (java.util.Properties) f.get(null);
        assertNotNull(LIVE_DATAINFO, "DATAINFO must be set by AbstractApiControllerDatabaseIT");
        SAVED_DATAINFO = new java.util.Properties();
        SAVED_DATAINFO.putAll(LIVE_DATAINFO);
    }

    @AfterAll
    static void restoreDataInfo() {
        if (LIVE_DATAINFO != null && SAVED_DATAINFO != null) {
            LIVE_DATAINFO.clear();
            LIVE_DATAINFO.putAll(SAVED_DATAINFO);
        }
    }

    @BeforeEach
    void noReceiverUnlessTheTestSaysSo() {
        LIVE_DATAINFO.remove(TOKEN_KEY);
        LIVE_DATAINFO.remove(SCOPE_KEY);
    }

    private static void receiverConfigured() {
        LIVE_DATAINFO.setProperty(TOKEN_KEY, "it-token-0123456789abcdef");
    }

    private MockMvc mockMvc() {
        return MockMvcBuilders.standaloneSetup(buildSubjectsController())
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void anonymousCallersGetNothing() throws Exception {
        receiverConfigured();
        mockMvc().perform(get("/api/v1/subjects/M-001/worklist"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anUnknownSubjectIs404() throws Exception {
        receiverConfigured();
        mockMvc().perform(get("/api/v1/subjects/ZZZ-999/worklist").session(authenticatedSession()))
                .andExpect(status().isNotFound());
    }

    /**
     * Without a receiver there is no camera to be on. The answer must be
     * "not offered", not "not scheduled" — otherwise every subject on an
     * instance that never deployed DICOM reads as missing from a worklist
     * that does not exist.
     */
    @Test
    void withoutAReceiverTheWorklistIsNotOffered() throws Exception {
        mockMvc().perform(get("/api/v1/subjects/M-001/worklist").session(authenticatedSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.offered").value(false))
                .andExpect(jsonPath("$.today.length()").value(0))
                .andExpect(jsonPath("$.otherOpen.length()").value(0))
                .andExpect(jsonPath("$.date").value(LocalDate.now().toString()));
    }

    @Test
    void aVisitScheduledTodayIsOnTheCamerasList() throws Exception {
        receiverConfigured();
        int id = insertVisit(LocalDate.now(), /* scheduled */ 1);
        try {
            mockMvc().perform(get("/api/v1/subjects/M-001/worklist").session(authenticatedSession()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.offered").value(true))
                    .andExpect(jsonPath("$.today[?(@.studyEventId == " + id + ")].accession")
                            .value("LC" + id))
                    .andExpect(jsonPath("$.today[?(@.studyEventId == " + id + ")].status")
                            .value("scheduled"))
                    .andExpect(jsonPath("$.today[?(@.studyEventId == " + id + ")].date")
                            .value(LocalDate.now().toString()))
                    .andExpect(jsonPath("$.otherOpen[?(@.studyEventId == " + id + ")]").isEmpty());
        } finally {
            deleteVisit(id);
        }
    }

    /** The SPA addresses subjects by their {@code SS_} OID; that path must answer the same. */
    @Test
    void theSubjectOidAddressesTheSameSubject() throws Exception {
        receiverConfigured();
        int id = insertVisit(LocalDate.now(), 1);
        try {
            mockMvc().perform(get("/api/v1/subjects/SS_M001/worklist").session(authenticatedSession()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.today[?(@.studyEventId == " + id + ")].accession")
                            .value("LC" + id));
        } finally {
            deleteVisit(id);
        }
    }

    /**
     * A visit on another day is the common mistake — the patient is in the
     * chair, the Baseline was booked for next week. It must show up as open
     * but not on today's list, so the page can offer to move it.
     */
    @Test
    void aVisitOnAnotherDayIsOpenButNotOnTodaysList() throws Exception {
        receiverConfigured();
        LocalDate nextWeek = LocalDate.now().plusDays(7);
        int id = insertVisit(nextWeek, 1);
        try {
            mockMvc().perform(get("/api/v1/subjects/M-001/worklist").session(authenticatedSession()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.offered").value(true))
                    .andExpect(jsonPath("$.today[?(@.studyEventId == " + id + ")]").isEmpty())
                    .andExpect(jsonPath("$.otherOpen[?(@.studyEventId == " + id + ")].date")
                            .value(nextWeek.toString()));
        } finally {
            deleteVisit(id);
        }
    }

    /** A visit in data entry is still open — the camera lists it, so must this. */
    @Test
    void aVisitInDataEntryTodayIsStillOnTheList() throws Exception {
        receiverConfigured();
        int id = insertVisit(LocalDate.now(), /* data entry started */ 3);
        try {
            mockMvc().perform(get("/api/v1/subjects/M-001/worklist").session(authenticatedSession()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.today[?(@.studyEventId == " + id + ")].status")
                            .value("data-entry-started"));
        } finally {
            deleteVisit(id);
        }
    }

    /** A completed visit is closed: not on the camera, and not something to move to today. */
    @Test
    void aCompletedVisitTodayIsNeitherListedNorOpen() throws Exception {
        receiverConfigured();
        int id = insertVisit(LocalDate.now(), /* completed */ 4);
        try {
            mockMvc().perform(get("/api/v1/subjects/M-001/worklist").session(authenticatedSession()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.today[?(@.studyEventId == " + id + ")]").isEmpty())
                    .andExpect(jsonPath("$.otherOpen[?(@.studyEventId == " + id + ")]").isEmpty());
        } finally {
            deleteVisit(id);
        }
    }

    /**
     * The camera only sees the studies named in its scope. A subject of another
     * study is "not offered" — the page must not claim that scheduling a visit
     * would put them on a camera that will never list them.
     */
    @Test
    void aStudyOutsideTheCamerasScopeIsNotOffered() throws Exception {
        receiverConfigured();
        LIVE_DATAINFO.setProperty(SCOPE_KEY, "S_RIS_DEMO");
        int id = insertVisit(LocalDate.now(), 1);
        try {
            mockMvc().perform(get("/api/v1/subjects/M-001/worklist").session(authenticatedSession()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.offered").value(false))
                    .andExpect(jsonPath("$.today.length()").value(0));
        } finally {
            deleteVisit(id);
        }
    }

    @Test
    void aStudyInsideTheCamerasScopeIsOffered() throws Exception {
        receiverConfigured();
        LIVE_DATAINFO.setProperty(SCOPE_KEY, "S_RIS_DEMO, " + studyOid(1));
        int id = insertVisit(LocalDate.now(), 1);
        try {
            mockMvc().perform(get("/api/v1/subjects/M-001/worklist").session(authenticatedSession()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.offered").value(true))
                    .andExpect(jsonPath("$.today[?(@.studyEventId == " + id + ")].accession")
                            .value("LC" + id));
        } finally {
            deleteVisit(id);
        }
    }

    /* ------------------------------------------------------------------ */
    /* fixtures                                                            */
    /* ------------------------------------------------------------------ */

    /** A visit of the demo study's first definition for M-001, on the given day, in the given status. */
    private static int insertVisit(LocalDate day, int subjectEventStatusId) throws Exception {
        try (java.sql.Connection c = DATA_SOURCE.getConnection();
             java.sql.PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO study_event (study_event_definition_id, study_subject_id, location, "
                             + " sample_ordinal, date_start, owner_id, status_id, "
                             + " subject_event_status_id, date_created, start_time_flag, end_time_flag) "
                             + "SELECT sed.study_event_definition_id, ss.study_subject_id, '', "
                             + "       COALESCE((SELECT MAX(se2.sample_ordinal) FROM study_event se2 "
                             + "                  WHERE se2.study_subject_id = ss.study_subject_id "
                             + "                    AND se2.study_event_definition_id = sed.study_event_definition_id), 0) + 1, "
                             + "       ?::date, 1, 1, ?, NOW(), false, false "
                             + "  FROM study_subject ss, "
                             + "       (SELECT study_event_definition_id FROM study_event_definition "
                             + "         WHERE study_id = 1 ORDER BY ordinal, study_event_definition_id LIMIT 1) sed "
                             + " WHERE ss.label = 'M-001' AND ss.study_id = 1 "
                             + "RETURNING study_event_id")) {
            ps.setString(1, day.toString());
            ps.setInt(2, subjectEventStatusId);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static void deleteVisit(int studyEventId) throws Exception {
        try (java.sql.Connection c = DATA_SOURCE.getConnection();
             java.sql.PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM study_event WHERE study_event_id = ?")) {
            ps.setInt(1, studyEventId);
            ps.executeUpdate();
        }
    }

    private static String studyOid(int studyId) throws Exception {
        try (java.sql.Connection c = DATA_SOURCE.getConnection();
             java.sql.PreparedStatement ps = c.prepareStatement(
                     "SELECT oc_oid FROM study WHERE study_id = ?")) {
            ps.setInt(1, studyId);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }
}
