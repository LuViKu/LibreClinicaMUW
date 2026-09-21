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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.ItemDataBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.core.CoreResources;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.ItemDataDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;
import at.ac.meduniwien.ophthalmology.libreclinica.service.study.StudySettingService;

/**
 * P3.7 — telling a clinician's number from the platform's.
 *
 * <p>Once a value is in an ODM file, a figure an investigator typed and one an
 * image or an inference job wrote look identical. A reader auditing the export
 * has no way to separate them and no way to reach the evidence. That is the
 * problem these annotations exist for, and it was invisible from inside the
 * application: {@code item_data} has carried {@code source_kind} since nAMD
 * Slice 3, but {@link ItemDataDAO} declared the columns without ever reading
 * them back onto the bean — so every consumer holding an {@link ItemDataBean}
 * saw an operator entry.
 *
 * <p>The rule worth pinning is what the annotation refuses to claim.
 * {@code muw:ManifestPath} appears only where the bundle really carries the
 * file: a casebook pointing at an entry that is not in the zip reads as
 * evidence that has merely been misplaced, which is worse than a casebook that
 * says nothing.
 */
@SuppressWarnings("null")
class SubjectExportProvenanceDatabaseIT extends AbstractApiControllerDatabaseIT {

    @TempDir
    static Path STORE_ROOT;

    private static final String STUDY_OID = "S_DEFAULTS1";
    private static final int STUDY_ID = 1;
    private static final int STUDY_SUBJECT_ID = 1;
    private static final String LABEL = "M-001";
    private static final String BASE = "/api/v1/studies/" + STUDY_OID + "/subjects/" + LABEL + "/export";

    /** Seeded operator-entered rows on event_crf 1, subject M-001. */
    private static final int OPERATOR_ITEM_DATA_ID = 2;
    private static final int ANNOTATED_ITEM_DATA_ID = 1;
    private static final int ANNOTATED_EVENT_CRF_ID = 1;

    private static final String MARKER = "provenance-it-";
    private static final long INFERENCE_JOB_ID = 990101L;

    @BeforeAll
    static void pointTheStoreAtATempDir() throws Exception {
        java.lang.reflect.Field f = CoreResources.class.getDeclaredField("DATAINFO");
        f.setAccessible(true);
        java.util.Properties live = (java.util.Properties) f.get(null);
        assertNotNull(live, "DATAINFO must be set by AbstractApiControllerDatabaseIT");
        live.setProperty("core.ingest.storePath", STORE_ROOT.toString());
    }

    @AfterEach
    void restoreTheSeed() throws Exception {
        // The provenance columns are set per test on a seeded row; put it back
        // so the next test starts from an operator-entered value.
        exec("UPDATE item_data SET source_kind = NULL, source_ingest_item_id = NULL, "
                + "source_retinal_job_id = NULL, value = '2020-10-05' "
                + " WHERE item_data_id = " + ANNOTATED_ITEM_DATA_ID);
        exec("UPDATE item_data SET source_kind = NULL, source_ingest_item_id = NULL, "
                + "source_retinal_job_id = NULL WHERE item_data_id = " + OPERATOR_ITEM_DATA_ID);
        exec("DELETE FROM ingest_item WHERE original_filename LIKE '" + MARKER + "%'");
        exec("DELETE FROM retinal_inference_result WHERE job_id = " + INFERENCE_JOB_ID);
        exec("DELETE FROM retinal_inference_job WHERE job_id = " + INFERENCE_JOB_ID);
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

    /** A real file in the store, filed against M-001. */
    private long seedIngestedFile() throws Exception {
        Path dir = STORE_ROOT.resolve("image");
        Files.createDirectories(dir);
        String name = MARKER + System.nanoTime() + ".jpg";
        Path file = dir.resolve(name);
        Files.write(file, "fundus-bytes".getBytes(StandardCharsets.UTF_8));
        return insertIngestRow(file.toString(), name);
    }

    /** A row whose file is not where it says it is. */
    private long seedMissingFile() throws Exception {
        String name = MARKER + System.nanoTime() + ".jpg";
        return insertIngestRow(STORE_ROOT.resolve("image").resolve("gone.jpg").toString(), name);
    }

    private long insertIngestRow(String storedPath, String name) throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO ingest_item (kind, source_kind, device, stored_path, "
                             + "original_filename, laterality, received_at, status, "
                             + "bound_study_subject_id) "
                             + "VALUES ('image', 'upload', 'it-camera', ?, ?, 'OD', now(), "
                             + "'BOUND', ?) RETURNING ingest_item_id")) {
            ps.setString(1, storedPath);
            ps.setString(2, name);
            ps.setInt(3, STUDY_SUBJECT_ID);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private void markAsIngested(long ingestItemId) throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE item_data SET source_kind = 'ingest', source_ingest_item_id = ? "
                             + " WHERE item_data_id = ?")) {
            ps.setLong(1, ingestItemId);
            ps.setInt(2, ANNOTATED_ITEM_DATA_ID);
            ps.executeUpdate();
        }
    }

    /** item_data.source_retinal_job_id has a foreign key — the job has to exist. */
    private void seedJob(long jobId) throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO retinal_inference_job (job_id, event_crf_id, task, e2e_path, "
                             + "eye_laterality, status, enqueued_at, model_version) "
                             + "VALUES (?, ?, 'fluid', '/dev/null', 'OD', 'done', now(), 'v1')")) {
            ps.setLong(1, jobId);
            ps.setInt(2, ANNOTATED_EVENT_CRF_ID);
            ps.executeUpdate();
        }
    }

    private void markAsInferred(long jobId) throws Exception {
        seedJob(jobId);
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE item_data SET source_kind = 'retinal_inference', "
                             + "source_retinal_job_id = ? WHERE item_data_id = ?")) {
            ps.setLong(1, jobId);
            ps.setInt(2, ANNOTATED_ITEM_DATA_ID);
            ps.executeUpdate();
        }
    }

    /* ---------------- harness ---------------- */

    private MockMvc mockMvc() {
        return MockMvcBuilders.standaloneSetup(
                        new SubjectExportApiController(DATA_SOURCE, new SiteVisibilityFilter(DATA_SOURCE)))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private MockHttpSession dm() {
        MockHttpSession s = new MockHttpSession();
        UserAccountBean ub = new UserAccountBean();
        ub.setId(1);
        ub.setName("root");
        s.setAttribute("userBean", ub);
        StudyBean study = new StudyBean();
        study.setId(STUDY_ID);
        study.setOid(STUDY_OID);
        study.setName("Default Study");
        s.setAttribute("study", study);
        StudyUserRoleBean r = new StudyUserRoleBean();
        r.setRole(Role.STUDYDIRECTOR);
        s.setAttribute("userRole", r);
        return s;
    }

    private String exportOdm() throws Exception {
        MvcResult res = mockMvc().perform(post(BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"format\":\"odm\"}")
                        .session(dm()))
                .andExpect(status().isOk())
                .andReturn();
        return res.getResponse().getContentAsString();
    }

    private Map<String, byte[]> exportBundle() throws Exception {
        new StudySettingService(DATA_SOURCE)
                .put(STUDY_ID, StudySettingService.EXPORT_BUNDLE_ENABLED, "true", 1);
        MvcResult res = mockMvc().perform(post(BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"format\":\"bundle\"}")
                        .session(dm()))
                .andReturn();
        assertEquals(200, res.getResponse().getStatus(),
                "bundle export failed: " + res.getResponse().getContentAsString()
                        + (res.getResolvedException() == null ? ""
                                : " / " + res.getResolvedException()));
        assertEquals("application/zip", res.getResponse().getContentType());
        byte[] zip = res.getResponse().getContentAsByteArray();
        Map<String, byte[]> out = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new java.io.ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                out.put(e.getName(), zis.readAllBytes());
            }
        }
        return out;
    }

    /** The single {@code <ItemData/>} element carrying this item_data's value. */
    private static String itemDataElementFor(String odm, String value) {
        int at = odm.indexOf("Value=\"" + value + "\"");
        assertTrue(at > 0, "expected an ItemData with value " + value + " in the export");
        int start = odm.lastIndexOf("<ItemData", at);
        int end = odm.indexOf("/>", at);
        return odm.substring(start, end + 2);
    }

    /* ---------------- the bean reads what the table holds ---------------- */

    /**
     * The regression that made the annotation impossible: the DAO declared the
     * provenance columns but never mapped them, so a bean could not answer
     * "did a person write this?".
     */
    @Test
    void theBeanCarriesProvenanceBackFromTheRow() throws Exception {
        long ingestItemId = seedIngestedFile();
        markAsIngested(ingestItemId);

        ItemDataDAO dao = new ItemDataDAO(DATA_SOURCE);
        Map<Integer, ItemDataBean> byId = new LinkedHashMap<>();
        for (ItemDataBean b : dao.findAllByEventCRFId(ANNOTATED_EVENT_CRF_ID)) {
            byId.put(b.getId(), b);
        }

        ItemDataBean annotated = byId.get(ANNOTATED_ITEM_DATA_ID);
        assertNotNull(annotated);
        assertEquals("ingest", annotated.getSourceKind());
        assertEquals(Long.valueOf(ingestItemId), annotated.getSourceIngestItemId());
        assertFalse(annotated.isOperatorEntered());

        // And absence stays absence. EntityDAO turns SQL NULL into 0L, which
        // would otherwise read as "job 0 wrote this".
        ItemDataBean typed = byId.get(OPERATOR_ITEM_DATA_ID);
        assertNotNull(typed);
        assertNull(typed.getSourceKind());
        assertNull(typed.getSourceIngestItemId());
        assertNull(typed.getSourceRetinalJobId());
        assertTrue(typed.isOperatorEntered());
    }

    /**
     * A clinician correcting an auto-ticked value takes ownership of it.
     *
     * <p>The heritage update paths write a new value without touching the
     * provenance columns, so the row went on naming the image that filed the
     * original — and the export would have stated that a machine wrote a
     * number a person typed. That is precisely the false claim the annotation
     * exists to prevent, and it is louder than no annotation at all.
     */
    @Test
    void correctingAValueTakesItBackFromThePlatform() throws Exception {
        long ingestItemId = seedIngestedFile();
        markAsIngested(ingestItemId);

        ItemDataDAO dao = new ItemDataDAO(DATA_SOURCE);
        ItemDataBean row = (ItemDataBean) dao.findByPK(ANNOTATED_ITEM_DATA_ID);
        assertEquals("ingest", row.getSourceKind(), "precondition: the platform wrote it");
        row.setValue("2021-02-02");
        row.setUpdaterId(1);
        dao.updateValue(row);

        ItemDataBean after = (ItemDataBean) dao.findByPK(ANNOTATED_ITEM_DATA_ID);
        assertEquals("2021-02-02", after.getValue());
        assertNull(after.getSourceKind(), "the row is the operator's now");
        assertNull(after.getSourceIngestItemId());
        assertTrue(after.isOperatorEntered());

        String element = itemDataElementFor(exportOdm(), "2021-02-02");
        assertFalse(element.contains("muw:"),
                "the export must not credit the platform for a correction: " + element);
    }

    /* ---------------- what the casebook says ---------------- */

    @Test
    void aValueAPersonTypedCarriesNoAnnotation() throws Exception {
        String odm = exportOdm();
        // The seeded value "Y" on event_crf 1 was entered by an operator.
        String element = itemDataElementFor(odm, "Y");
        assertFalse(element.contains("muw:"),
                "annotating an operator's entry would claim the platform wrote it: " + element);
    }

    @Test
    void anIngestedValueNamesTheAcquisitionItCameFrom() throws Exception {
        long ingestItemId = seedIngestedFile();
        markAsIngested(ingestItemId);

        String odm = exportOdm();
        assertTrue(odm.contains("xmlns:muw=\"" + SubjectExportApiController.MUW_ODM_NS + "\""),
                "the namespace has to be declared for the attributes to mean anything");
        String element = itemDataElementFor(odm, "2020-10-05");
        assertTrue(element.contains("muw:SourceKind=\"ingest\""), element);
        assertTrue(element.contains("muw:IngestItemId=\"" + ingestItemId + "\""), element);
        // No manifest outside a bundle, so nothing to point at.
        assertFalse(element.contains("muw:ManifestPath"), element);
    }

    @Test
    void anInferredValueNamesTheJob() throws Exception {
        markAsInferred(INFERENCE_JOB_ID);
        String element = itemDataElementFor(exportOdm(), "2020-10-05");
        assertTrue(element.contains("muw:SourceKind=\"retinal_inference\""), element);
        assertTrue(element.contains("muw:RetinalJobId=\"" + INFERENCE_JOB_ID + "\""), element);
    }

    /* ---------------- value and evidence travel together ---------------- */

    @Test
    void insideABundleTheValuePointsAtTheFileInTheZip() throws Exception {
        long ingestItemId = seedIngestedFile();
        markAsIngested(ingestItemId);

        Map<String, byte[]> entries = exportBundle();
        String odm = new String(entries.get("casebook.xml"), StandardCharsets.UTF_8);
        String element = itemDataElementFor(odm, "2020-10-05");

        String expected = "acquisitions/" + ingestItemId + ".jpg";
        assertTrue(element.contains("muw:ManifestPath=\"" + expected + "\""), element);
        assertTrue(entries.containsKey(expected),
                "the path the casebook names has to be an entry the bundle actually carries");
    }

    /**
     * A row can name a file the store no longer has. The bundle reports that as
     * an omission; the casebook must not hand the reader a path to it.
     */
    @Test
    void aValueWhoseFileIsMissingGetsNoPath() throws Exception {
        long ingestItemId = seedMissingFile();
        markAsIngested(ingestItemId);

        Map<String, byte[]> entries = exportBundle();
        String odm = new String(entries.get("casebook.xml"), StandardCharsets.UTF_8);
        String element = itemDataElementFor(odm, "2020-10-05");

        assertTrue(element.contains("muw:IngestItemId=\"" + ingestItemId + "\""),
                "the source is still a fact worth recording: " + element);
        assertFalse(element.contains("muw:ManifestPath"),
                "a path into a bundle that does not contain the file is worse than none: " + element);
        assertTrue(new String(entries.get("manifest.json"), StandardCharsets.UTF_8)
                        .contains("ingest_item/" + ingestItemId),
                "and the manifest has to say the file is gone");
    }
}
