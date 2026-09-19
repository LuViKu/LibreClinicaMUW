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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.DatasetItemStatus;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Status;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.UserType;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.extract.DatasetBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.extract.ExportFormatBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.core.CoreResources;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.extract.DatasetDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.RuleSetRuleDao;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.login.UserAccountDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.service.extract.ExportFileMaterializer;
import at.ac.meduniwien.ophthalmology.libreclinica.service.extract.ExportScheduleRegistrar;
import at.ac.meduniwien.ophthalmology.libreclinica.service.extract.SynchronousExportMaterializer;
import at.ac.meduniwien.ophthalmology.libreclinica.service.study.StudySettingService;

/**
 * P3.8 — a dataset as one archive, through the job queue.
 *
 * <p>What is pinned is the gate and the shape. The bundle is off unless the
 * study turns it on, and it is refused at both ends: the queue will not accept
 * the job, and the worker will not build it even from a row that was already
 * waiting — a study that switches the export off must not have its imaging
 * leave the platform because of a job queued a minute earlier. The archive
 * holds a folder per subject under one manifest and is registered as a zip,
 * so the file table, the download endpoint and the retention sweep all see
 * one more archived file of the right kind.
 */
@SuppressWarnings("null")
class DatasetBundleExportDatabaseIT extends AbstractApiControllerDatabaseIT {

    @TempDir
    static Path FILE_ROOT;

    private static final int STUDY_ID = 1;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private Integer datasetId;

    @BeforeAll
    static void pointFilePathAtATempDir() throws Exception {
        java.lang.reflect.Field f = CoreResources.class.getDeclaredField("DATAINFO");
        f.setAccessible(true);
        java.util.Properties live = (java.util.Properties) f.get(null);
        assertNotNull(live, "DATAINFO must be set by AbstractApiControllerDatabaseIT");
        live.setProperty("filePath", FILE_ROOT.toString() + File.separator);
    }

    @AfterEach
    void cleanUp() throws Exception {
        if (datasetId != null) {
            exec("DELETE FROM export_job WHERE dataset_id = " + datasetId);
            exec("DELETE FROM archived_dataset_file WHERE dataset_id = " + datasetId);
            exec("DELETE FROM dataset WHERE dataset_id = " + datasetId);
            datasetId = null;
        }
        exec("DELETE FROM study_setting WHERE study_id = " + STUDY_ID);
        exec("DELETE FROM audit_log_event WHERE audit_table = 'study_setting'");
    }

    private void exec(String sql) throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }

    /* ---------------- fixtures ---------------- */

    private DatasetBean persistDataset() {
        UserAccountBean root = new UserAccountDAO(DATA_SOURCE).findByPK(1);
        DatasetBean ds = new DatasetBean();
        ds.setStudyId(STUDY_ID);
        ds.setName("bundle fixture " + System.nanoTime());
        ds.setDescription("P3.8 dataset bundle");
        ds.setStatus(Status.AVAILABLE);
        ds.setDatasetItemStatus(DatasetItemStatus.COMPLETED_AND_NONCOMPLETED);
        ds.setOwner(root);
        ds.setOwnerId(1);
        ds.setCreatedDate(new Date());
        ds.setNumRuns(0);
        ds.setODMMetaDataVersionName("");
        ds.setODMMetaDataVersionOid("");
        ds.setODMPriorStudyOid("");
        ds.setODMPriorMetaDataVersionOid("");
        ds.setEventIds(new ArrayList<>(List.of(1, 2, 3)));
        ds.setItemIds(new ArrayList<>(List.of(1, 2, 3, 4, 5)));
        ds.setShowSubjectUniqueIdentifier(true);
        ds.setShowEventStart(true);
        ds.setShowSubjectStatus(true);
        ds.setSQLStatement(ds.generateQuery());
        DatasetBean persisted = new DatasetDAO(DATA_SOURCE).create(ds);
        assertTrue(persisted.getId() > 0, "the fixture dataset should persist");
        datasetId = persisted.getId();
        return persisted;
    }

    private void enableBundles() throws Exception {
        new StudySettingService(DATA_SOURCE)
                .put(STUDY_ID, StudySettingService.EXPORT_BUNDLE_ENABLED, "true", 1);
    }

    private SynchronousExportMaterializer materializer() {
        RuleSetRuleDao rules = Mockito.mock(RuleSetRuleDao.class);
        Mockito.when(rules.findByRuleSetStudyIdAndStatusAvail(Mockito.anyInt()))
                .thenReturn(new ArrayList<>());
        return new SynchronousExportMaterializer(DATA_SOURCE, Mockito.mock(CoreResources.class), rules);
    }

    private Map<String, byte[]> unzip(Path zip) throws Exception {
        Map<String, byte[]> out = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) out.put(e.getName(), zis.readAllBytes());
        }
        return out;
    }

    /* ---------------- the worker ---------------- */

    @Test
    void theWorkerRefusesABundleTheStudyHasNotEnabled() {
        DatasetBean ds = persistDataset();
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> materializer().materialize(ds, "bundle", 1));
        assertTrue(refused.getMessage().contains("not enabled"), refused.getMessage());
    }

    @Test
    void aDatasetBundleHoldsEverySubjectUnderOneManifest() throws Exception {
        enableBundles();
        DatasetBean ds = persistDataset();

        ExportFileMaterializer.Result r = materializer().materialize(ds, "bundle", 1);

        assertTrue(r.name().endsWith("_bundle.zip"), r.name());
        assertTrue(r.fileReference().startsWith("existing:"),
                "registered inside the materializer, like every other format");
        int archivedId = Integer.parseInt(r.fileReference().substring("existing:".length()));

        // Registered as a zip — not as one of the text formats the table has
        // carried since OpenClinica.
        String reference;
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT export_format_id, file_reference FROM archived_dataset_file "
                             + " WHERE archived_dataset_file_id = ?")) {
            ps.setInt(1, archivedId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(ExportFormatBean.ZIPFILE.getId(), rs.getInt(1));
                reference = rs.getString(2);
            }
        }

        Map<String, byte[]> entries = unzip(Path.of(reference));
        assertTrue(entries.containsKey("manifest.json"));
        // The seed has seven live subjects M-001..M-007 in study 1, and the
        // dataset has no filters, so all of them are in.
        long casebooks = entries.keySet().stream()
                .filter(k -> k.matches("subjects/M-00[1-7]/casebook\\.xml")).count();
        assertEquals(7, casebooks, "one casebook per subject: " + entries.keySet());
        assertFalse(entries.containsKey("casebook.xml"),
                "no dataset-level casebook — each subject has its own");

        JsonNode m = JSON.readTree(entries.get("manifest.json"));
        assertEquals("dataset", m.get("scope").asText());
        assertEquals(7, m.get("subjects").size());
        // root holds no treating role on the study, so nothing is withheld
        assertEquals("none", m.get("masking").asText());
        String firstCasebook = new String(entries.get("subjects/M-001/casebook.xml"), StandardCharsets.UTF_8);
        assertTrue(firstCasebook.contains("SubjectKey=\"SS_M001\""),
                "each folder holds that subject's own casebook");
    }

    /* ---------------- the queue ---------------- */

    private MockMvc mockMvc() {
        return MockMvcBuilders.standaloneSetup(
                        new ExportJobsApiController(DATA_SOURCE, Mockito.mock(ExportScheduleRegistrar.class)))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private MockHttpSession sysadmin() {
        MockHttpSession s = new MockHttpSession();
        UserAccountBean ub = new UserAccountBean();
        ub.setId(1);
        ub.setName("root");
        ub.addUserType(UserType.SYSADMIN);
        s.setAttribute("userBean", ub);
        StudyBean study = new StudyBean();
        study.setId(STUDY_ID);
        study.setOid("S_DEFAULTS1");
        s.setAttribute("study", study);
        return s;
    }

    @Test
    void theQueueRefusesABundleTheStudyHasNotEnabled() throws Exception {
        DatasetBean ds = persistDataset();
        mockMvc().perform(post("/api/v1/datasets/" + ds.getId() + "/exports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"format\":\"bundle\"}")
                        .session(sysadmin()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("not have the multimodal export enabled")));
    }

    @Test
    void theQueueAcceptsABundleWhereTheStudyEnablesIt() throws Exception {
        enableBundles();
        DatasetBean ds = persistDataset();
        mockMvc().perform(post("/api/v1/datasets/" + ds.getId() + "/exports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"format\":\"bundle\"}")
                        .session(sysadmin()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.format").value("bundle"))
                .andExpect(jsonPath("$.status").value("queued"))
                .andExpect(jsonPath("$.datasetId").value(ds.getId()));
    }
}
