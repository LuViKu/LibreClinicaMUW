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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.UserType;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RemoteRetinalInferenceClient;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RetinalArtifactStorageService;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RetinalJobStatusBroadcaster;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.StudySubjectFinder;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 2026-06-26 — IT for the layer-segmentation correction endpoints
 * (POST / DELETE / GET under
 * {@code /retinal-jobs/{jobId}/segmentation/corrections}).
 *
 * <p>Seeds a job with a {@code bscan_masks_dir} populated with 11 IOWA
 * surface CSVs, then exercises the save → list → delete cycle.
 */
class RetinalCorrectionEndpointIT extends AbstractApiControllerDatabaseIT {

    private static final long JOB_ID = 9701L;
    private static final int LAYER_ILM = 0;

    private Path masksDir;
    private RetinalArtifactStorageService artifactStore;
    private SiteVisibilityFilter visibilityFilter;

    @BeforeEach
    void seed() throws Exception {
        masksDir = Files.createTempDirectory("retinal-corr-it-");
        // Seed 11 minimal IOWA CSVs (3 B-scans × 4 A-scans) so the
        // controller's findLayerCsv resolves layerIndex=0 → ILM.
        String[][] iowa = {
                {"001-ILM (ILM).csv", "ILM"},
                {"002-RNFL-GCL (RNFL-GCL).csv", "RNFL-GCL"},
                {"003-GCL-IPL (GCL-IPL).csv", "GCL-IPL"},
                {"004-IPL-INL (IPL-INL).csv", "IPL-INL"},
                {"005-INL-OPL (INL-OPL).csv", "INL-OPL"},
                {"006-OPL-Henle's fiber layer (OPL-HFL).csv", "OPL-HFL"},
                {"007-Boundary of myoid and ellipsoid of inner segments (BMEIS).csv", "BMEIS"},
                {"008-IS#OS junction (IS#OSJ).csv", "IS#OSJ"},
                {"009-Inner boundary of OPR (IB_OPR).csv", "IB_OPR"},
                {"010-Inner boundary of RPE (IB_RPE).csv", "IB_RPE"},
                {"011-Outer boundary of RPE (OB_RPE).csv", "OB_RPE"},
        };
        // CSV layout: 3-element header (cols, n_bscans, n_rows) +
        // 3 padded rows (4 real cols + 1 sentinel "100").
        String body = "4,3,1024\n"
                + "1.0,2.0,3.0,4.0,100\n"
                + "5.0,6.0,7.0,8.0,100\n"
                + "9.0,10.0,11.0,12.0,100\n";
        for (String[] file : iowa) {
            Files.writeString(masksDir.resolve(file[0]), body, StandardCharsets.UTF_8);
        }

        artifactStore = new RetinalArtifactStorageService();
        visibilityFilter = new SiteVisibilityFilter(DATA_SOURCE);

        // Seed the job row + its result row (bscan_masks_dir → our temp).
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO retinal_inference_job ("
                             + "job_id, event_crf_id, task, e2e_path, eye_laterality, "
                             + "status, enqueued_at, completed_at, model_version) "
                             + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            Timestamp now = Timestamp.from(java.time.Instant.now());
            ps.setLong(1, JOB_ID);
            ps.setInt(2, 1);
            ps.setString(3, "layers");
            ps.setString(4, "/tmp/correction-it.e2e");
            ps.setString(5, "OD");
            ps.setString(6, "done");
            ps.setTimestamp(7, now);
            ps.setTimestamp(8, now);
            ps.setString(9, "iowa-layers-v1");
            ps.executeUpdate();
        }
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO retinal_inference_result ("
                             + "job_id, task, output_payload, primary_metric_value, primary_metric_unit, "
                             + "bscan_masks_dir, confidence) "
                             + "VALUES (?, ?, ?::jsonb, ?, ?, ?, ?)")) {
            // 2026-06-28 — `task` column on retinal_inference_result is
            // NOT NULL (lc-muw-2026-06-10-retinal-inference-tables.xml:85);
            // the IT had been skipping it and CI started rejecting every
            // run. Pin to 'layers' to match the JOB_ID's task above.
            ps.setLong(1, JOB_ID);
            ps.setString(2, "layers");
            ps.setString(3, "{}");
            ps.setBigDecimal(4, new BigDecimal("0.00"));
            ps.setString(5, "");
            ps.setString(6, masksDir.toString());
            ps.setDouble(7, 0.9);
            ps.executeUpdate();
        }
    }

    @AfterEach
    void cleanup() throws Exception {
        try (Connection c = DATA_SOURCE.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM retinal_inference_correction WHERE job_id = ?")) {
                ps.setLong(1, JOB_ID);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM retinal_inference_result WHERE job_id = ?")) {
                ps.setLong(1, JOB_ID);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM retinal_inference_job WHERE job_id = ?")) {
                ps.setLong(1, JOB_ID);
                ps.executeUpdate();
            }
        }
        if (masksDir != null && Files.isDirectory(masksDir)) {
            Files.walk(masksDir)
                    .sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (Exception ignored) { }
                    });
        }
    }

    private MockMvc buildMockMvc() {
        RemoteRetinalInferenceClient remoteClient = Mockito.mock(RemoteRetinalInferenceClient.class);
        Mockito.when(remoteClient.isConfigured()).thenReturn(false);
        return MockMvcBuilders.standaloneSetup(
                new RetinalResultsApiController(
                        DATA_SOURCE, visibilityFilter, artifactStore,
                        new StudySubjectFinder(DATA_SOURCE),
                        remoteClient,
                        new RetinalJobStatusBroadcaster(),
                        null))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    /** Sysadmin session — passes the canCorrectSegmentation gate. */
    private MockHttpSession sysadminSession() {
        MockHttpSession s = new MockHttpSession();
        UserAccountBean ub = new UserAccountBean();
        ub.setId(1);
        ub.setName("root");
        ub.addUserType(UserType.SYSADMIN);
        s.setAttribute("userBean", ub);
        StudyBean study = new StudyBean();
        study.setId(1);
        study.setOid("default-study");
        s.setAttribute("study", study);
        return s;
    }

    /** Non-privileged session — fails the role gate. */
    private MockHttpSession unprivilegedSession() {
        MockHttpSession s = new MockHttpSession();
        UserAccountBean ub = new UserAccountBean();
        ub.setId(2);
        ub.setName("alice");
        s.setAttribute("userBean", ub);
        StudyBean study = new StudyBean();
        study.setId(1);
        study.setOid("default-study");
        s.setAttribute("study", study);
        return s;
    }

    @Test
    void saveCorrection_writesFileAndRow() throws Exception {
        // Edit slices 0 + 2 of layer ILM. Cols=4.
        String body = "{\"layerIndex\":0,\"layerLabel\":\"ILM\","
                + "\"perSliceRows\":{\"0\":[10.0,11.0,12.0,13.0],"
                + "\"2\":[20.0,21.0,22.0,23.0]}}";

        buildMockMvc().perform(post("/api/v1/retinal-jobs/" + JOB_ID + "/segmentation/corrections")
                .session(sysadminSession())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value(JOB_ID))
                .andExpect(jsonPath("$.layerIndex").value(LAYER_ILM))
                .andExpect(jsonPath("$.layerLabel").value("ILM"))
                .andExpect(jsonPath("$.editedSliceCount").value(2))
                .andExpect(jsonPath("$.csvRelpath").value("corrections/001-ILM (ILM).csv"));

        // File on disk under corrections/.
        Path correctedFile = masksDir.resolve("corrections").resolve("001-ILM (ILM).csv");
        assertTrue(Files.isRegularFile(correctedFile), "corrected CSV landed");
        String content = Files.readString(correctedFile);
        // Slice 0 + 2 carry the edited values; slice 1 still the original.
        assertTrue(content.contains("10,11,12,13,100"), "slice 0 edited values: " + content);
        assertTrue(content.contains("5.0,6.0,7.0,8.0,100"), "slice 1 unchanged: " + content);
        assertTrue(content.contains("20,21,22,23,100"), "slice 2 edited values: " + content);

        // DB row UPSERTed.
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT layer_label, edited_slice_count, csv_relpath, edited_by_user_id "
                             + "FROM retinal_inference_correction WHERE job_id = ? AND layer_index = ?")) {
            ps.setLong(1, JOB_ID);
            ps.setInt(2, LAYER_ILM);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "correction row exists");
                assertEquals("ILM", rs.getString(1));
                assertEquals(2, rs.getInt(2));
                assertEquals("corrections/001-ILM (ILM).csv", rs.getString(3));
                assertEquals(1, rs.getInt(4));
            }
        }
    }

    @Test
    void saveCorrection_rejectsUnprivilegedRole() throws Exception {
        String body = "{\"layerIndex\":0,\"layerLabel\":\"ILM\","
                + "\"perSliceRows\":{\"0\":[10.0,11.0,12.0,13.0]}}";
        buildMockMvc().perform(post("/api/v1/retinal-jobs/" + JOB_ID + "/segmentation/corrections")
                .session(unprivilegedSession())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    void saveCorrection_400OnRowLengthMismatch() throws Exception {
        // Wrong row width (3 instead of 4) — the merge step throws
        // IllegalArgumentException which the controller translates to 400.
        String body = "{\"layerIndex\":0,\"layerLabel\":\"ILM\","
                + "\"perSliceRows\":{\"0\":[10.0,11.0,12.0]}}";
        buildMockMvc().perform(post("/api/v1/retinal-jobs/" + JOB_ID + "/segmentation/corrections")
                .session(sysadminSession())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listCorrections_returnsArrayOrderedByLayerIndex() throws Exception {
        // Save two layers, then list.
        buildMockMvc().perform(post("/api/v1/retinal-jobs/" + JOB_ID + "/segmentation/corrections")
                .session(sysadminSession())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"layerIndex\":10,\"layerLabel\":\"OB_RPE\","
                        + "\"perSliceRows\":{\"1\":[5.0,6.0,7.0,8.0]}}"))
                .andExpect(status().isOk());
        buildMockMvc().perform(post("/api/v1/retinal-jobs/" + JOB_ID + "/segmentation/corrections")
                .session(sysadminSession())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"layerIndex\":0,\"layerLabel\":\"ILM\","
                        + "\"perSliceRows\":{\"0\":[1.0,2.0,3.0,4.0]}}"))
                .andExpect(status().isOk());

        buildMockMvc().perform(get("/api/v1/retinal-jobs/" + JOB_ID + "/segmentation/corrections")
                .session(sysadminSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].layerIndex").value(0))
                .andExpect(jsonPath("$[0].layerLabel").value("ILM"))
                .andExpect(jsonPath("$[1].layerIndex").value(10))
                .andExpect(jsonPath("$[1].layerLabel").value("OB_RPE"));
    }

    @Test
    void deleteCorrection_removesFileAndRow() throws Exception {
        // Save a correction first.
        buildMockMvc().perform(post("/api/v1/retinal-jobs/" + JOB_ID + "/segmentation/corrections")
                .session(sysadminSession())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"layerIndex\":0,\"layerLabel\":\"ILM\","
                        + "\"perSliceRows\":{\"0\":[1.0,2.0,3.0,4.0]}}"))
                .andExpect(status().isOk());
        Path correctedFile = masksDir.resolve("corrections").resolve("001-ILM (ILM).csv");
        assertTrue(Files.isRegularFile(correctedFile));

        // Now delete.
        buildMockMvc().perform(delete("/api/v1/retinal-jobs/" + JOB_ID + "/segmentation/corrections/0")
                .session(sysadminSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reverted").value(true));

        assertTrue(!Files.exists(correctedFile), "correction file removed");
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM retinal_inference_correction "
                             + "WHERE job_id = ? AND layer_index = 0")) {
            ps.setLong(1, JOB_ID);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertEquals(0, rs.getInt(1), "row removed");
            }
        }
    }

    @Test
    void deleteCorrection_404WhenAbsent() throws Exception {
        buildMockMvc().perform(delete("/api/v1/retinal-jobs/" + JOB_ID + "/segmentation/corrections/0")
                .session(sysadminSession()))
                .andExpect(status().isNotFound());
    }

    @Test
    void streamSegmentation_setsCorrectedHeader() throws Exception {
        // Save a correction.
        buildMockMvc().perform(post("/api/v1/retinal-jobs/" + JOB_ID + "/segmentation/corrections")
                .session(sysadminSession())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"layerIndex\":0,\"layerLabel\":\"ILM\","
                        + "\"perSliceRows\":{\"0\":[1.0,2.0,3.0,4.0]}}"))
                .andExpect(status().isOk());

        // Stream the envelope — header lists surface index 0 as corrected.
        var result = buildMockMvc().perform(get("/api/v1/retinal-jobs/" + JOB_ID + "/segmentation")
                .session(sysadminSession()))
                .andExpect(status().isOk())
                .andReturn();
        String header = result.getResponse().getHeader("X-MUW-Seg-Corrected");
        assertNotNull(header, "X-MUW-Seg-Corrected header set");
        assertEquals("0", header, "only surface 0 corrected");
    }
}
