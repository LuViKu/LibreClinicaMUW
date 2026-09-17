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
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM image_ingest WHERE source_kind = 'upload'")) {
            ps.executeUpdate();
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
                     "SELECT source_kind, status, patient_id, laterality, study_date, content_type, "
                             + "stored_path, preview_png_path, original_filename "
                             + "FROM image_ingest WHERE source_kind = 'upload'");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next(), "the commit should have written one row");
            assertEquals("UNBOUND", rs.getString("status"));
            assertEquals("M-001", rs.getString("patient_id"));
            assertEquals("OD", rs.getString("laterality"));
            assertEquals("2021-01-04", rs.getString("study_date"));
            assertEquals("image/png", rs.getString("content_type"));
            assertEquals("fundus.png", rs.getString("original_filename"));
            // The upload is its own preview, and the bytes really landed in the store.
            assertEquals(rs.getString("stored_path"), rs.getString("preview_png_path"));
            Path stored = Path.of(rs.getString("stored_path"));
            assertTrue(stored.startsWith(STORE_ROOT), "file must stay inside the ingest store");
            assertTrue(Files.exists(stored), "the image bytes should be on disk");
        }
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
                     "SELECT laterality FROM image_ingest WHERE source_kind='upload'");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            String lat = rs.getString("laterality");
            assertTrue(lat == null || lat.equals("OD") || lat.equals("OS") || lat.equals("OU"),
                    "unexpected laterality persisted: " + lat);
        }
    }

    private int uploadRowCount() throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM image_ingest WHERE source_kind = 'upload'");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
