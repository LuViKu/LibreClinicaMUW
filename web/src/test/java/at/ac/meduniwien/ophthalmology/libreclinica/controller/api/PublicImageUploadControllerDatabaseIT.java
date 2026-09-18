/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import at.ac.meduniwien.ophthalmology.libreclinica.dao.core.CoreResources;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.StudySubjectFinder;

/**
 * Characterisation IT for the Remidio public image-upload portal (DR-025).
 *
 * <p>This page has no login — the reverse proxy and the rate-limit filter are
 * the only gates — so the contract pinned here is deliberately about what the
 * endpoint accepts and what it must not reveal: exact-label resolve states,
 * content-type enforcement, and that a committed image lands UNBOUND in the
 * reconciliation queue with the operator's hints preserved.
 */
@SuppressWarnings("null")
class PublicImageUploadControllerDatabaseIT extends AbstractApiControllerDatabaseIT {

    @TempDir
    static Path STORE_ROOT;

    private static java.util.Properties SAVED_DATAINFO;

    /** 1x1 transparent PNG. */
    private static final byte[] PNG = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk"
                    + "+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==");

    @BeforeAll
    static void overrideStorePath() throws Exception {
        java.lang.reflect.Field f = CoreResources.class.getDeclaredField("DATAINFO");
        f.setAccessible(true);
        java.util.Properties live = (java.util.Properties) f.get(null);
        assertNotNull(live, "DATAINFO must be set by AbstractApiControllerDatabaseIT");
        SAVED_DATAINFO = new java.util.Properties();
        SAVED_DATAINFO.putAll(live);
        live.setProperty("core.dicom.ingest.storePath", STORE_ROOT.toString());
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

    @AfterEach
    void cleanRows() throws Exception {
        try (Connection c = DATA_SOURCE.getConnection()) {
            // Audit rows first — they reference the ingest rows by id, and a
            // leftover row would make the next test's audit assertion ambiguous.
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM audit_log_event WHERE audit_table = 'ingest_item'")) {
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM ingest_item WHERE source_kind = 'upload'")) {
                ps.executeUpdate();
            }
        }
    }

    private MockMvc mockMvc() {
        PublicImageUploadController c =
                new PublicImageUploadController(DATA_SOURCE, new StudySubjectFinder(DATA_SOURCE));
        return MockMvcBuilders.standaloneSetup(c)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    /* ---------------- /resolve ---------------- */

    /** Exact label + a date the subject has a visit on → the operator gets a one-click target. */
    @Test
    void resolve_knownLabelWithVisit_isSuggested() throws Exception {
        mockMvc().perform(post("/api/v1/public/image-upload/resolve")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"patientId\":\"M-001\",\"studyDate\":\"2021-01-04\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("suggested"))
                .andExpect(jsonPath("$.candidates.length()").value(1))
                .andExpect(jsonPath("$.candidates[0].subjectLabel").value("M-001"))
                .andExpect(jsonPath("$.candidates[0].matchingEvent.studyEventId").value(3));
    }

    /** Known label, no visit that day → still resolvable, but nothing is auto-picked. */
    @Test
    void resolve_knownLabelWithoutVisit_isNovisit() throws Exception {
        mockMvc().perform(post("/api/v1/public/image-upload/resolve")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"patientId\":\"M-001\",\"studyDate\":\"1999-01-01\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("novisit"))
                .andExpect(jsonPath("$.candidates[0].matchingEvent").isEmpty());
    }

    /**
     * An unknown label must reveal nothing at all — no near-matches, no count.
     * This is the page's main information-disclosure boundary.
     */
    @Test
    void resolve_unknownLabel_revealsNothing() throws Exception {
        mockMvc().perform(post("/api/v1/public/image-upload/resolve")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"patientId\":\"ZZZ-NOT-A-SUBJECT\",\"studyDate\":\"2021-01-04\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("nopatient"))
                .andExpect(jsonPath("$.candidates.length()").value(0));
    }

    @Test
    void resolve_withoutPatientId_is400() throws Exception {
        mockMvc().perform(post("/api/v1/public/image-upload/resolve")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"studyDate\":\"2021-01-04\"}"))
                .andExpect(status().isBadRequest());
    }

    /* ---------------- /patients/search ---------------- */

    /**
     * The portal's patient-search dialog needs a lookup it can actually call:
     * it used to hit the session-gated staff endpoint and get 401.
     */
    @Test
    void search_byLabelPrefix_returnsMatchingSubjects() throws Exception {
        mockMvc().perform(get("/api/v1/public/image-upload/patients/search").param("q", "M-00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subjects").isArray())
                .andExpect(jsonPath("$.subjects[0].label").exists())
                .andExpect(jsonPath("$.subjects[0].studySubjectId").isNumber());
    }

    /**
     * Label-only projection: an unauthenticated caller must not be able to read
     * demographics off the search, however it is queried.
     */
    @Test
    void search_neverExposesDemographics() throws Exception {
        String body = mockMvc().perform(
                get("/api/v1/public/image-upload/patients/search").param("q", "M-00"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        for (String forbidden : new String[]{"dateOfBirth", "date_of_birth", "gender",
                "uniqueIdentifier", "unique_identifier", "enrollmentDate", "studyOid"}) {
            assertTrue(!body.contains(forbidden),
                    "public search leaked '" + forbidden + "': " + body);
        }
    }

    /** A one- or two-character prefix would enumerate the register, not look a subject up. */
    @Test
    void search_withTooShortPrefix_is400() throws Exception {
        mockMvc().perform(get("/api/v1/public/image-upload/patients/search").param("q", "M"))
                .andExpect(status().isBadRequest());
        mockMvc().perform(get("/api/v1/public/image-upload/patients/search").param("q", "M-"))
                .andExpect(status().isBadRequest());
    }

    /** The row cap is the endpoint's, not the caller's. */
    @Test
    void search_limitIsCappedRegardlessOfRequest() throws Exception {
        mockMvc().perform(get("/api/v1/public/image-upload/patients/search")
                .param("q", "M-0").param("limit", "5000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subjects.length()")
                        .value(org.hamcrest.Matchers.lessThanOrEqualTo(10)));
    }

    /** An unknown prefix resolves to nothing, not an error and not a hint. */
    @Test
    void search_unknownPrefix_isEmpty() throws Exception {
        mockMvc().perform(get("/api/v1/public/image-upload/patients/search").param("q", "ZZZZ"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subjects.length()").value(0));
    }

    /* ---------------- /commit ---------------- */

    @Test
    void commit_png_landsUnboundWithHints() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "fundus.png", "image/png", PNG);
        mockMvc().perform(multipart("/api/v1/public/image-upload/commit")
                .file(file)
                .param("patientId", "M-001")
                .param("laterality", "OD")
                .param("studyDate", "2021-01-04"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("UNBOUND"))
                .andExpect(jsonPath("$.imageIngestId").isNumber());

        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT source_kind, status, patient_id, laterality, acquisition_date, content_type, "
                             + "stored_path, preview_png_path, original_filename "
                             + "FROM ingest_item WHERE source_kind = 'upload'");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next(), "the commit should have written one row");
            assertEquals("UNBOUND", rs.getString("status"));
            assertEquals("M-001", rs.getString("patient_id"));
            assertEquals("OD", rs.getString("laterality"));
            assertEquals("2021-01-04", rs.getString("acquisition_date"));
            assertEquals("image/png", rs.getString("content_type"));
            assertEquals("fundus.png", rs.getString("original_filename"));
            // The upload is its own preview, and the bytes really landed in the store.
            assertEquals(rs.getString("stored_path"), rs.getString("preview_png_path"));
            Path stored = Path.of(rs.getString("stored_path"));
            assertTrue(stored.startsWith(STORE_ROOT), "file must stay inside the ingest store");
            assertTrue(Files.exists(stored), "the image bytes should be on disk");
        }
    }

    /* ---------------- /commit with a picked visit ---------------- */

    /**
     * When the operator picks the visit on the form, the image is filed against
     * it directly — the reconciliation inbox is for images that arrive without
     * one, not the default path.
     */
    @Test
    void commit_withPickedVisit_landsBoundAndAudited() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "fundus.png", "image/png", PNG);
        // study_event 3 = M-001 / V3 Day 90 / 2021-01-04, status 3.
        mockMvc().perform(multipart("/api/v1/public/image-upload/commit")
                .file(file)
                .param("patientId", "M-001")
                .param("studyDate", "2021-01-04")
                .param("studyEventId", "3"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("BOUND"));

        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT ingest_item_id, status, match_policy, bound_study_subject_id, "
                             + "bound_study_event_id, bound_event_crf_id, bound_by_user_id, bound_at "
                             + "FROM ingest_item WHERE source_kind = 'upload'");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            assertEquals("BOUND", rs.getString("status"));
            assertEquals("portal", rs.getString("match_policy"));
            assertEquals(1, rs.getInt("bound_study_subject_id"));
            assertEquals(3, rs.getInt("bound_study_event_id"));
            assertEquals(3, rs.getInt("bound_event_crf_id"));
            rs.getInt("bound_by_user_id");
            assertTrue(rs.wasNull(), "a form with no login must not claim an operator");
            assertNotNull(rs.getTimestamp("bound_at"));

            long id = rs.getLong("ingest_item_id");
            try (PreparedStatement a = c.prepareStatement(
                    "SELECT user_id, new_value FROM audit_log_event "
                            + "WHERE audit_table = 'ingest_item' AND entity_id = ? "
                            + "AND audit_log_event_type_id = ?")) {
                a.setInt(1, (int) id);
                a.setInt(2, AuditTypeIds.IMAGE_BIND);
                try (ResultSet ars = a.executeQuery()) {
                    assertTrue(ars.next(), "a system bind must still leave an audit row");
                    ars.getInt("user_id");
                    assertTrue(ars.wasNull(), "the system bind has no user");
                    assertTrue(ars.getString("new_value").contains("match_policy=portal"),
                            "the audit row should record how the bind happened");
                }
            }
        }
    }

    /**
     * The form is unauthenticated, so a bare event id cannot be trusted: it must
     * belong to a live visit on the date being filed. A mismatch is refused
     * outright rather than silently degraded to UNBOUND — the operator asked for
     * a binding and needs to know it did not happen.
     */
    @Test
    void commit_withVisitOnADifferentDate_is400AndStoresNothing() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "fundus.png", "image/png", PNG);
        mockMvc().perform(multipart("/api/v1/public/image-upload/commit")
                .file(file)
                .param("studyDate", "1999-01-01")
                .param("studyEventId", "3"))
                .andExpect(status().isBadRequest());
        assertEquals(0, uploadRowCount());
    }

    /** A completed visit is not a filing target either. */
    @Test
    void commit_withCompletedVisit_is400() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "fundus.png", "image/png", PNG);
        // study_event 1 = M-001 / V1 / 2020-10-06, status 4 (completed).
        mockMvc().perform(multipart("/api/v1/public/image-upload/commit")
                .file(file)
                .param("studyDate", "2020-10-06")
                .param("studyEventId", "1"))
                .andExpect(status().isBadRequest());
        assertEquals(0, uploadRowCount());
    }

    @Test
    void commit_withUnknownVisit_is400() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "fundus.png", "image/png", PNG);
        mockMvc().perform(multipart("/api/v1/public/image-upload/commit")
                .file(file)
                .param("studyDate", "2021-01-04")
                .param("studyEventId", "999999"))
                .andExpect(status().isBadRequest());
        assertEquals(0, uploadRowCount());
    }

    /** A camera that uploads without hints still gets its image queued. */
    @Test
    void commit_withoutHints_isAccepted() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "x.jpg", "image/jpeg", PNG);
        mockMvc().perform(multipart("/api/v1/public/image-upload/commit").file(file))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("UNBOUND"));
    }

    /** Only JPEG/PNG — the portal is not a general file drop. */
    @Test
    void commit_nonImageContentType_is400() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "evil.svg", "image/svg+xml",
                "<svg/>".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        mockMvc().perform(multipart("/api/v1/public/image-upload/commit").file(file))
                .andExpect(status().isBadRequest());
        assertEquals(0, uploadRowCount());
    }

    @Test
    void commit_emptyFile_is400() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "empty.png", "image/png", new byte[0]);
        mockMvc().perform(multipart("/api/v1/public/image-upload/commit").file(file))
                .andExpect(status().isBadRequest());
        assertEquals(0, uploadRowCount());
    }

    /** Laterality is normalised, not echoed — an out-of-range value must not reach the DB. */
    @Test
    void commit_unknownLaterality_isNormalisedAway() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "y.png", "image/png", PNG);
        mockMvc().perform(multipart("/api/v1/public/image-upload/commit")
                .file(file).param("laterality", "LEFT-ISH"))
                .andExpect(status().isCreated());
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT laterality FROM ingest_item WHERE source_kind='upload'");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            String lat = rs.getString("laterality");
            assertTrue(lat == null || lat.equals("OD") || lat.equals("OS") || lat.equals("OU"),
                    "unexpected laterality persisted: " + lat);
        }
    }

    /* ---------------- /resolve : study scope ---------------- */

    /**
     * P3.0 — the resolve endpoint honours the portal's study scope.
     *
     * <p>It did not before: the sibling search endpoint was scoped, so a portal
     * configured for one study would refuse to *search* for another study's
     * subject and then happily *resolve* the same label, naming that subject's
     * study and site back to a caller who never logged in. Both now answer the
     * same way, and an out-of-scope subject is indistinguishable from one that
     * does not exist.
     */
    @Test
    void resolve_honoursThePortalStudyScope() throws Exception {
        java.lang.reflect.Field f = CoreResources.class.getDeclaredField("DATAINFO");
        f.setAccessible(true);
        java.util.Properties live = (java.util.Properties) f.get(null);

        // Unrestricted: the seeded subject resolves.
        mockMvc().perform(post("/api/v1/public/image-upload/resolve")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"patientId\":\"M-001\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates.length()").value(1));

        live.setProperty("core.ingest.portal.studyOids", "S_SOME_OTHER_STUDY");
        try {
            mockMvc().perform(post("/api/v1/public/image-upload/resolve")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"patientId\":\"M-001\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.state").value("nopatient"))
                    .andExpect(jsonPath("$.candidates.length()").value(0));
        } finally {
            live.remove("core.ingest.portal.studyOids");
        }
    }

    /* ---------------- /visits : the today's-visits picker ---------------- */

    private static void setTodaysVisits(String value) throws Exception {
        java.lang.reflect.Field f = CoreResources.class.getDeclaredField("DATAINFO");
        f.setAccessible(true);
        java.util.Properties live = (java.util.Properties) f.get(null);
        if (value == null) live.remove("core.ingest.portal.todaysVisits");
        else live.setProperty("core.ingest.portal.todaysVisits", value);
    }

    /**
     * A list of the day's patients on a page that needs no login is a
     * disclosure in its own right, so it must stay off until someone turns it
     * on — and the path must not announce itself while it is off.
     */
    @Test
    void visits_areNotServedUnlessTheFeatureIsOn() throws Exception {
        setTodaysVisits(null);
        mockMvc().perform(get("/api/v1/public/image-upload/visits"))
                .andExpect(status().isNotFound());
        setTodaysVisits("false");
        try {
            mockMvc().perform(get("/api/v1/public/image-upload/visits"))
                    .andExpect(status().isNotFound());
        } finally {
            setTodaysVisits(null);
        }
    }

    @Test
    void visits_whenOn_listTheDaysOpenVisits() throws Exception {
        setTodaysVisits("true");
        try {
            mockMvc().perform(get("/api/v1/public/image-upload/visits").param("date", "2021-01-04"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.date").value("2021-01-04"))
                    .andExpect(jsonPath("$.visits.length()").value(1))
                    .andExpect(jsonPath("$.visits[0].studyEventId").value(3))
                    .andExpect(jsonPath("$.visits[0].subjectLabel").value("M-001"))
                    .andExpect(jsonPath("$.visits[0].eventLabel").value("V3 Day 90"));
        } finally {
            setTodaysVisits(null);
        }
    }

    /**
     * The picker shows the label, the visit and the study. Sex and date of
     * birth are on the DICOM worklist because a modality needs them; they have
     * no business on an unauthenticated web page.
     */
    @Test
    void visits_exposeNoIdentifyingFieldsBeyondTheLabel() throws Exception {
        setTodaysVisits("true");
        try {
            mockMvc().perform(get("/api/v1/public/image-upload/visits").param("date", "2021-01-04"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.visits[0].gender").doesNotExist())
                    .andExpect(jsonPath("$.visits[0].dateOfBirth").doesNotExist())
                    .andExpect(jsonPath("$.visits[0].patientId").doesNotExist());
        } finally {
            setTodaysVisits(null);
        }
    }

    /** The portal's study scope applies here too, or the list leaks across studies. */
    @Test
    void visits_honourThePortalStudyScope() throws Exception {
        setTodaysVisits("true");
        java.lang.reflect.Field f = CoreResources.class.getDeclaredField("DATAINFO");
        f.setAccessible(true);
        java.util.Properties live = (java.util.Properties) f.get(null);
        live.setProperty("core.ingest.portal.studyOids", "S_SOME_OTHER_STUDY");
        try {
            mockMvc().perform(get("/api/v1/public/image-upload/visits").param("date", "2021-01-04"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.visits.length()").value(0));
        } finally {
            live.remove("core.ingest.portal.studyOids");
            setTodaysVisits(null);
        }
    }

    /** A completed visit is closed; offering it invites filing against the wrong encounter. */
    @Test
    void visits_excludeClosedVisits() throws Exception {
        setTodaysVisits("true");
        try {
            mockMvc().perform(get("/api/v1/public/image-upload/visits").param("date", "2020-11-05"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.visits.length()").value(0));
        } finally {
            setTodaysVisits(null);
        }
    }

    private int uploadRowCount() throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM ingest_item WHERE source_kind = 'upload'");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
