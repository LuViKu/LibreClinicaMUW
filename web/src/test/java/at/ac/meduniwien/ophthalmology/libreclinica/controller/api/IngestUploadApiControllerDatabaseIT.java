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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.core.CoreResources;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.DicomDescribeClient;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.IngestArtifactStore;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.StudySubjectFinder;
import at.ac.meduniwien.ophthalmology.libreclinica.service.study.StudySettingService;

/**
 * DR-029 — the same uploader behind a login.
 *
 * <p>What a session changes: who may call it, whose name goes on the row and
 * in the trail, and which visits are in reach. What it does not change is
 * pinned by {@link PublicUploadControllerDatabaseIT}; this file covers the
 * difference.
 */
@SuppressWarnings("null")
class IngestUploadApiControllerDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static final String BASE = "/api/v1/ingest/upload";

    @TempDir
    static Path STORE_ROOT;

    @TempDir
    static Path E2E_ROOT;

    private static java.util.Properties SAVED_DATAINFO;
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final byte[] PNG = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk"
                    + "+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==");

    @BeforeAll
    static void overrideStorePaths() throws Exception {
        java.lang.reflect.Field f = CoreResources.class.getDeclaredField("DATAINFO");
        f.setAccessible(true);
        java.util.Properties live = (java.util.Properties) f.get(null);
        assertNotNull(live, "DATAINFO must be set by AbstractApiControllerDatabaseIT");
        SAVED_DATAINFO = new java.util.Properties();
        SAVED_DATAINFO.putAll(live);
        live.setProperty(IngestArtifactStore.CONFIG_KEY_STORE_PATH, STORE_ROOT.toString());
        live.setProperty("core.retinalInference.e2eUploadsPath", E2E_ROOT.toString());
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
            for (String sql : new String[] {
                    "DELETE FROM audit_log_event WHERE audit_table IN ('ingest_item', 'retinal_inference_job')",
                    "DELETE FROM retinal_inference_job WHERE ingest_item_id IN "
                            + "(SELECT ingest_item_id FROM ingest_item WHERE source_kind IN ('upload', 'portal-oct'))",
                    "DELETE FROM ingest_item WHERE source_kind IN ('upload', 'portal-oct')",
                    "DELETE FROM study_setting WHERE setting_key LIKE 'ingest.%'"}) {
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.executeUpdate();
                }
            }
        }
    }

    private MockMvc mockMvc() {
        StudySubjectFinder finder = new StudySubjectFinder(DATA_SOURCE);
        IngestUploadApiController c = new IngestUploadApiController(
                DATA_SOURCE, new SiteVisibilityFilter(DATA_SOURCE), finder,
                new PublicOctUploadController(DATA_SOURCE, finder),
                new IngestUploadService(DATA_SOURCE, new IngestArtifactStore(),
                        new DicomDescribeClient("", "")));
        return MockMvcBuilders.standaloneSetup(c)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private MockHttpSession sessionAs(Role role) {
        MockHttpSession s = new MockHttpSession();
        UserAccountBean ub = new UserAccountBean();
        ub.setId(1);
        ub.setName("root");
        s.setAttribute("userBean", ub);
        StudyBean study = new StudyBean();
        study.setId(1);
        study.setOid("S_DEFAULTS1");
        study.setName("Default Study");
        s.setAttribute("study", study);
        StudyUserRoleBean r = new StudyUserRoleBean();
        r.setRole(role);
        s.setAttribute("userRole", r);
        return s;
    }

    private MockHttpSession dm() {
        return sessionAs(Role.STUDYDIRECTOR);
    }

    private static MockMultipartFile png(String name) {
        return new MockMultipartFile("file", name, "image/png", PNG);
    }

    private static MockMultipartFile e2e() {
        byte[] b = new byte[1024];
        System.arraycopy("CMDb".getBytes(StandardCharsets.US_ASCII), 0, b, 0, 4);
        return new MockMultipartFile("file", "scan.e2e", "application/octet-stream", b);
    }

    private static MockMultipartFile dicom() {
        byte[] b = new byte[512];
        System.arraycopy("DICM".getBytes(StandardCharsets.US_ASCII), 0, b, 128, 4);
        return new MockMultipartFile("file", "export.dcm", "application/dicom", b);
    }

    private long idOf(MvcResult r) throws Exception {
        return JSON.readTree(r.getResponse().getContentAsString()).path("ingestItemId").asLong();
    }

    @Test
    void anAnonymousCallerIsRefused() throws Exception {
        mockMvc().perform(multipart(BASE + "/commit").file(png("a.png")).session(new MockHttpSession()))
                .andExpect(status().isUnauthorized());
        mockMvc().perform(post(BASE + "/resolve").session(new MockHttpSession())
                .contentType(MediaType.APPLICATION_JSON).content("{\"scans\":[]}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aRoleThatMayNotReconcileMayNotUploadEither() throws Exception {
        mockMvc().perform(multipart(BASE + "/commit").file(png("a.png")).session(sessionAs(Role.MONITOR)))
                .andExpect(status().isForbidden());
    }

    @Test
    void aDataManagersUploadIsAttributedToThem() throws Exception {
        MvcResult r = mockMvc().perform(multipart(BASE + "/commit")
                .file(png("fundus.png"))
                .param("studyEventId", "3")
                .param("laterality", "OD")
                .session(dm()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("BOUND"))
                .andExpect(jsonPath("$.kind").value("image"))
                .andReturn();
        long id = idOf(r);
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT status, match_policy, bound_by_user_id, bound_study_event_id, source_kind "
                             + "  FROM ingest_item WHERE ingest_item_id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("BOUND", rs.getString("status"));
                assertEquals("visit-picked", rs.getString("match_policy"));
                assertEquals(1, rs.getInt("bound_by_user_id"));
                assertEquals(3, rs.getInt("bound_study_event_id"));
                assertEquals("upload", rs.getString("source_kind"));
            }
        }
        assertEquals(1, auditRowsByUser(AuditTypeIds.IMAGE_BIND, id), "the bind is on the person's record");
    }

    @Test
    void aVisitThatDoesNotExistIs404() throws Exception {
        mockMvc().perform(multipart(BASE + "/commit")
                .file(png("a.png"))
                .param("studyEventId", "99999999")
                .session(dm()))
                .andExpect(status().isNotFound());
    }

    @Test
    void anUnfiledUploadNeedsNoVisit() throws Exception {
        mockMvc().perform(multipart(BASE + "/commit")
                .file(png("a.png"))
                .param("patientId", "M-002")
                .session(dm()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("UNBOUND"));
    }

    @Test
    void aDicomUploadWithoutTheSidecarIsRefused() throws Exception {
        mockMvc().perform(multipart(BASE + "/commit").file(dicom()).session(dm()))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void anOctScanUploadedByStaffIsAttributedToo() throws Exception {
        MvcResult r = mockMvc().perform(multipart(BASE + "/commit")
                .file(e2e())
                .param("studyEventId", "3")
                .param("scanDate", "2021-01-04")
                .param("laterality", "OD")
                .session(dm()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("e2e"))
                .andExpect(jsonPath("$.status").value("queued"))
                .andReturn();
        long id = idOf(r);
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT patient_id, status, bound_by_user_id FROM ingest_item WHERE ingest_item_id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("M-001", rs.getString("patient_id"), "the label comes from the picked visit");
                assertEquals("BOUND", rs.getString("status"));
                assertEquals(1, rs.getInt("bound_by_user_id"));
            }
        }
        assertEquals(1, auditRowsByUser(AuditTypeIds.OCT_UPLOAD_PUBLIC, id));
    }

    /** Behind a login the refusal can say why: the operator can go and change the setting. */
    @Test
    void aStudyThatDoesNotAcceptDicomSaysSo() throws Exception {
        new StudySettingService(DATA_SOURCE).put(1, StudySettingService.INGEST_DICOM_ENABLED, "false", 1);
        mockMvc().perform(multipart(BASE + "/commit")
                .file(dicom())
                .param("studyEventId", "3")
                .session(dm()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("this study does not accept dicom uploads"));
    }

    @Test
    void resolveAnswersWithinTheSessionsVisibility() throws Exception {
        mockMvc().perform(post(BASE + "/resolve")
                .session(dm())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"scans\":[{\"patientId\":\"M-001\",\"scanDate\":\"2021-01-04\",\"laterality\":\"OD\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scans[0].state").value("suggested"))
                .andExpect(jsonPath("$.scans[0].candidates[0].subjectLabel").value("M-001"));
    }

    @Test
    void undoTakesBackAFreshUpload() throws Exception {
        long id = idOf(mockMvc().perform(multipart(BASE + "/commit")
                .file(png("a.png"))
                .param("studyEventId", "3")
                .session(dm()))
                .andExpect(status().isCreated()).andReturn());
        mockMvc().perform(delete(BASE + "/items/" + id).session(dm())).andExpect(status().isNoContent());
        mockMvc().perform(delete(BASE + "/items/" + id).session(new MockHttpSession()))
                .andExpect(status().isUnauthorized());
    }

    private int auditRowsByUser(int type, long entityId) throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM audit_log_event WHERE audit_log_event_type_id = ? "
                             + "  AND audit_table = 'ingest_item' AND entity_id = ? AND user_id = 1")) {
            ps.setInt(1, type);
            ps.setInt(2, (int) entityId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }
}
