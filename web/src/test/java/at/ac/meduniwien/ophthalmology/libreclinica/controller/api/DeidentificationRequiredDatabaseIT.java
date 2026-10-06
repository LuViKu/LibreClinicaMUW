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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.core.CoreResources;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.DicomDescribeClient;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.FileKindSniffer;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.IngestArtifactStore;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.StudySubjectFinder;

/**
 * De-identification required (layer 2): the staff upload takes only a file the
 * server has itself found clean, from a caller who named a visible subject,
 * with a SHA-256 that matches, under the neutral name; and with the mode off
 * the same route behaves exactly as before.
 */
@SuppressWarnings("null")
class DeidentificationRequiredDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static final String BASE = "/api/v1/ingest/upload";
    private static final String TOKEN = "deid-test-token";
    private static final String LABEL = "M-001";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final byte[] PNG = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk"
                    + "+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==");

    @TempDir
    static Path STORE_ROOT;

    @TempDir
    static Path E2E_ROOT;

    private static java.util.Properties SAVED_DATAINFO;
    private static HttpServer STUB;
    private static final AtomicInteger VERIFY_CALLS = new AtomicInteger();
    private static final AtomicInteger DESCRIBE_CALLS = new AtomicInteger();
    private static final AtomicReference<String> VERIFY_BODY = new AtomicReference<>();
    private static final AtomicReference<String> DESCRIBE_BODY = new AtomicReference<>();
    private static volatile String verifyAnswer;
    private static volatile String describeSop;

    @BeforeAll
    static void startStubAndOverridePaths() throws Exception {
        STUB = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        STUB.createContext("/verify", ex -> {
            VERIFY_CALLS.incrementAndGet();
            VERIFY_BODY.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            answer(ex, 200, verifyAnswer);
        });
        STUB.createContext("/describe", ex -> {
            DESCRIBE_CALLS.incrementAndGet();
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            DESCRIBE_BODY.set(body);
            String path = JSON.readTree(body).path("path").asText();
            String preview = path.replaceAll("\\.dcm$", "") + ".png";
            Files.write(Path.of(preview), PNG);
            answer(ex, 200, "{\"sopInstanceUid\":\"" + describeSop + "\","
                    + "\"sopClassUid\":\"1.2.840.10008.5.1.4.1.1.77.1.5.1\","
                    + "\"studyInstanceUid\":\"1.2.3\",\"seriesInstanceUid\":\"1.2.3.4\","
                    + "\"modality\":\"OP\",\"studyDate\":\"2021-01-04\",\"acquisitionDate\":\"2021-01-04\","
                    + "\"laterality\":\"OD\",\"manufacturer\":\"Carl Zeiss Meditec\","
                    + "\"manufacturerModelName\":\"CLARUS 700\","
                    + "\"previewPngPath\":\"" + preview + "\",\"identityRemoved\":true,\"changedTags\":0}");
        });
        STUB.start();

        java.lang.reflect.Field f = CoreResources.class.getDeclaredField("DATAINFO");
        f.setAccessible(true);
        java.util.Properties live = (java.util.Properties) f.get(null);
        assertNotNull(live, "DATAINFO must be set by AbstractApiControllerDatabaseIT");
        SAVED_DATAINFO = new java.util.Properties();
        SAVED_DATAINFO.putAll(live);
        live.setProperty(IngestArtifactStore.CONFIG_KEY_STORE_PATH, STORE_ROOT.toString());
        live.setProperty("core.retinalInference.e2eUploadsPath", E2E_ROOT.toString());
    }

    private static void answer(com.sun.net.httpserver.HttpExchange ex, int status, String json) throws IOException {
        byte[] out = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, out.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(out);
        }
    }

    @AfterAll
    static void restore() throws Exception {
        STUB.stop(0);
        java.lang.reflect.Field f = CoreResources.class.getDeclaredField("DATAINFO");
        f.setAccessible(true);
        java.util.Properties live = (java.util.Properties) f.get(null);
        if (live != null && SAVED_DATAINFO != null) {
            live.clear();
            live.putAll(SAVED_DATAINFO);
        }
    }

    @BeforeEach
    void resetStub() {
        VERIFY_CALLS.set(0);
        DESCRIBE_CALLS.set(0);
        VERIFY_BODY.set(null);
        DESCRIBE_BODY.set(null);
        verifyAnswer = "{\"ok\":true,\"violations\":[]}";
        describeSop = "1.2.826.0.1.3680043.8.498." + System.nanoTime();
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
        try (Stream<Path> s = Files.walk(E2E_ROOT)) {
            s.filter(Files::isRegularFile).forEach(p -> p.toFile().delete());
        }
    }

    /* ---------------- wiring ---------------- */

    private MockMvc mockMvc(boolean required) {
        StudySubjectFinder finder = new StudySubjectFinder(DATA_SOURCE);
        PublicOctUploadController oct = new PublicOctUploadController(DATA_SOURCE, finder);
        IngestUploadApiController c = new IngestUploadApiController(
                DATA_SOURCE, new SiteVisibilityFilter(DATA_SOURCE), finder, oct,
                new IngestUploadService(DATA_SOURCE, new IngestArtifactStore(),
                        new DicomDescribeClient(
                                "http://127.0.0.1:" + STUB.getAddress().getPort() + "/describe", TOKEN)));
        c.setDeidentificationPolicy(DeidentificationPolicy.of(required));
        return MockMvcBuilders.standaloneSetup(c).setControllerAdvice(new ApiExceptionHandler()).build();
    }

    private MockHttpSession dm() {
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
        r.setRole(Role.STUDYDIRECTOR);
        s.setAttribute("userRole", r);
        return s;
    }

    /* ---------------- fixtures ---------------- */

    private static byte[] dicomBytes(int salt) {
        byte[] b = new byte[512];
        System.arraycopy("DICM".getBytes(StandardCharsets.US_ASCII), 0, b, 128, 4);
        b[200] = (byte) salt;
        return b;
    }

    private static String sha256(byte[] bytes) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (byte x : MessageDigest.getInstance("SHA-256").digest(bytes)) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static MockHttpServletRequestBuilder compliantE2e(byte[] bytes, String name) throws Exception {
        return multipart(BASE + "/commit")
                .file(new MockMultipartFile("file", name, "application/octet-stream", bytes))
                .param("patientId", LABEL)
                .param("scanDate", "2021-01-04")
                .param("laterality", "OD")
                .param("studyEventId", "3")
                .param("deidConfirmed", "true")
                .param("deidSha256", sha256(bytes));
    }

    private static MockHttpServletRequestBuilder compliantDicom(byte[] bytes, String name) throws Exception {
        return multipart(BASE + "/commit")
                .file(new MockMultipartFile("file", name, "application/dicom", bytes))
                .param("patientId", LABEL)
                .param("studyEventId", "3")
                .param("deidConfirmed", "true")
                .param("deidSha256", sha256(bytes));
    }

    private static long filesUnder(Path root) throws IOException {
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(Files::isRegularFile).count();
        }
    }

    private long countAudit(int type) throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM audit_log_event WHERE audit_log_event_type_id = ?")) {
            ps.setInt(1, type);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long ingestRows() throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM ingest_item WHERE source_kind IN ('upload', 'portal-oct')");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static void assertRejected(MvcResult r, String... violations) throws Exception {
        JsonNode body = JSON.readTree(r.getResponse().getContentAsString());
        assertEquals("DEID_REQUIRED", body.path("code").asText(), r.getResponse().getContentAsString());
        assertTrue(body.path("message").asText().length() > 0);
        List<String> got = new ArrayList<>();
        body.path("violations").forEach(n -> got.add(n.asText()));
        for (String v : violations) assertTrue(got.contains(v), v + " in " + got);
    }

    /* ---------------- (a) only E2E and DICOM ---------------- */

    @Test
    void aPngIs415AndAJpegIs415() throws Exception {
        MvcResult png = mockMvc(true).perform(multipart(BASE + "/commit")
                .file(new MockMultipartFile("file", "a.png", "image/png", PNG))
                .param("patientId", LABEL).session(dm()))
                .andExpect(status().is(415)).andReturn();
        assertRejected(png, "fileType");
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F', 0};
        MvcResult jpg = mockMvc(true).perform(multipart(BASE + "/commit")
                .file(new MockMultipartFile("file", "a.jpg", "image/jpeg", jpeg))
                .param("patientId", LABEL).session(dm()))
                .andExpect(status().is(415)).andReturn();
        assertRejected(jpg, "fileType");
        assertEquals(0, ingestRows());
    }

    @Test
    void aFileOfNoKnownKindIs415NotAnEchoOfWhyItWasNotRecognised() throws Exception {
        mockMvc(true).perform(multipart(BASE + "/commit")
                .file(new MockMultipartFile("file", "notes.txt", "text/plain", "hello".getBytes()))
                .param("patientId", LABEL).session(dm()))
                .andExpect(status().is(415))
                .andExpect(jsonPath("$.code").value("DEID_REQUIRED"));
    }

    /* ---------------- (b) the label ---------------- */

    @Test
    void thePatientIdMustBeAVisibleSubjectsLabelNotFreeText() throws Exception {
        byte[] bytes = E2eTestFiles.e2e(E2eTestFiles.patient(""));
        for (String bad : new String[] {"Max Mustermann", "NOPE-1", "m-001", " "}) {
            MvcResult r = mockMvc(true).perform(compliantE2e(bytes, "x_20210104_OD.e2e")
                    .param("patientId", bad).session(dm()))
                    .andExpect(status().isUnprocessableEntity()).andReturn();
            assertRejected(r, "patientId");
            assertFalse(r.getResponse().getContentAsString().contains("Mustermann"),
                    "a value from the request is never echoed");
        }
        assertEquals(0, ingestRows());
        assertEquals(0, filesUnder(E2E_ROOT));
    }

    @Test
    void aLabelOutsideTheCallersVisibilityIsRefused() throws Exception {
        byte[] bytes = E2eTestFiles.e2e(E2eTestFiles.patient(""));
        MockMultipartFile file = new MockMultipartFile("file", "M-001_20210104_OD.e2e", "x", bytes);
        FileKindSniffer.Sniffed kind = FileKindSniffer.sniff(bytes);
        DeidUploadGate.Check inReach = DeidUploadGate.precheck(
                DATA_SOURCE, file, kind, LABEL, "true", sha256(bytes), Set.of(1), null);
        assertTrue(inReach instanceof DeidUploadGate.Pass, String.valueOf(inReach));
        DeidUploadGate.Check outOfReach = DeidUploadGate.precheck(
                DATA_SOURCE, file, kind, LABEL, "true", sha256(bytes), Set.of(2), null);
        assertTrue(outOfReach instanceof DeidUploadGate.Reject r && r.violations().contains("patientId"));
        DeidUploadGate.Check nothingVisible = DeidUploadGate.precheck(
                DATA_SOURCE, file, kind, LABEL, "true", sha256(bytes), Set.of(), null);
        assertTrue(nothingVisible instanceof DeidUploadGate.Reject);
    }

    @Test
    void aRemovedSubjectsLabelIsNotAcceptedEither() throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE study_subject SET status_id = 5 WHERE label = 'M-007'")) {
            ps.executeUpdate();
        }
        try {
            assertFalse(DeidUploadGate.isVisibleLabel(DATA_SOURCE, "M-007", Set.of(1)));
            assertTrue(DeidUploadGate.isVisibleLabel(DATA_SOURCE, "M-001", Set.of(1)));
        } finally {
            try (Connection c = DATA_SOURCE.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                         "UPDATE study_subject SET status_id = 1 WHERE label = 'M-007'")) {
                ps.executeUpdate();
            }
        }
    }

    @Test
    void theLabelMustBeTheNamedVisitsSubject() throws Exception {
        // study_event 3 belongs to M-001; M-002 exists in the same study but is not that visit's subject
        byte[] bytes = E2eTestFiles.e2e(E2eTestFiles.patient(""));
        MvcResult r = mockMvc(true).perform(compliantE2e(bytes, "M-002_20210104_OD.e2e")
                .param("patientId", "M-002").session(dm()))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertRejected(r, "patientId");
    }

    /* ---------------- (c) the confirmation ---------------- */

    @Test
    void theConfirmationAndItsShaAreRequiredAndChecked() throws Exception {
        byte[] bytes = E2eTestFiles.e2e(E2eTestFiles.patient(""));
        String name = "M-001_20210104_OD.e2e";

        assertRejected(mockMvc(true).perform(multipart(BASE + "/commit")
                .file(new MockMultipartFile("file", name, "x", bytes))
                .param("patientId", LABEL).param("studyEventId", "3").session(dm()))
                .andExpect(status().isUnprocessableEntity()).andReturn(), "deidConfirmed", "deidSha256");

        assertRejected(mockMvc(true).perform(compliantE2e(bytes, name)
                .param("deidConfirmed", "false").session(dm()))
                .andExpect(status().isUnprocessableEntity()).andReturn(), "deidConfirmed");

        assertRejected(mockMvc(true).perform(compliantE2e(bytes, name)
                .param("deidSha256", "0".repeat(64)).session(dm()))
                .andExpect(status().isUnprocessableEntity()).andReturn(), "deidSha256");

        assertRejected(mockMvc(true).perform(compliantE2e(bytes, name)
                .param("deidSha256", "not-hex").session(dm()))
                .andExpect(status().isUnprocessableEntity()).andReturn(), "deidSha256");
        assertEquals(0, ingestRows());
        assertEquals(0, filesUnder(E2E_ROOT));
    }

    /* ---------------- (d) E2E content ---------------- */

    @Test
    void aCleanE2eIsAcceptedUnderTheNeutralNameAndTheConfirmationIsAudited() throws Exception {
        byte[] bytes = E2eTestFiles.e2e(E2eTestFiles.patient(LABEL));
        String name = "M-001_20210104_OD.e2e";
        MvcResult r = mockMvc(true).perform(compliantE2e(bytes, name).session(dm()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("e2e"))
                .andReturn();
        long id = JSON.readTree(r.getResponse().getContentAsString()).path("ingestItemId").asLong();

        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT original_filename, patient_id FROM ingest_item WHERE ingest_item_id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(name, rs.getString(1), "only the neutral name is stored");
                assertEquals(LABEL, rs.getString(2));
            }
        }
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT user_id, entity_name, new_value, audit_date FROM audit_log_event "
                             + "WHERE audit_log_event_type_id = ? AND entity_id = ?")) {
            ps.setInt(1, AuditTypeIds.DEID_UPLOAD_CONFIRMED);
            ps.setInt(2, (int) id);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "the confirmation is kept with the upload's row");
                assertEquals(1, rs.getInt("user_id"));
                assertTrue(rs.getString("new_value").contains("sha256=" + sha256(bytes)));
                assertNotNull(rs.getTimestamp("audit_date"));
                assertFalse(rs.getString("new_value").contains(LABEL), "no label in the confirmation row");
            }
        }
    }

    @Test
    void aNameInTheSurnameSlotIsRefusedAndTheFileDeleted() throws Exception {
        byte[] bytes = E2eTestFiles.e2e(E2eTestFiles.patient("Mustermann"));
        MvcResult r = mockMvc(true).perform(compliantE2e(bytes, "M-001_20210104_OD.e2e").session(dm()))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertRejected(r, "e2e.surname");
        assertFalse(r.getResponse().getContentAsString().contains("Mustermann"));
        assertEquals(0, ingestRows());
        assertEquals(0, filesUnder(E2E_ROOT), "the temp file is deleted");
        assertEquals(1, countAudit(AuditTypeIds.DEID_UPLOAD_REJECTED), "the rejection is on the trail");
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT entity_name, new_value, user_id FROM audit_log_event WHERE audit_log_event_type_id = ?")) {
            ps.setInt(1, AuditTypeIds.DEID_UPLOAD_REJECTED);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertTrue(rs.getString("entity_name").contains("e2e.surname"));
                assertTrue(rs.getString("new_value").contains(sha256(bytes)));
                assertFalse((rs.getString("entity_name") + rs.getString("new_value")).contains("Mustermann"));
                assertEquals(1, rs.getInt("user_id"));
            }
        }
    }

    @Test
    void aStructureTheServerCannotWalkIsRefusedFailClosed() throws Exception {
        // a "CMDb" header and nothing else: the shape the legacy tests upload
        byte[] stub = new byte[1024];
        System.arraycopy("CMDb".getBytes(StandardCharsets.US_ASCII), 0, stub, 0, 4);
        MvcResult r = mockMvc(true).perform(compliantE2e(stub, "M-001_20210104_OD.e2e").session(dm()))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertRejected(r, "e2e.structure");
        assertEquals(0, filesUnder(E2E_ROOT));
    }

    /* ---------------- (e) DICOM content, through the sidecar ---------------- */

    @Test
    void aCleanDicomIsVerifiedBeforeItIsDescribedAndDescribeRunsStrict() throws Exception {
        byte[] bytes = dicomBytes(1);
        mockMvc(true).perform(compliantDicom(bytes, "M-001_20210104_OD.dcm").session(dm()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("dicom"));
        assertEquals(1, VERIFY_CALLS.get());
        assertEquals(1, DESCRIBE_CALLS.get());
        assertTrue(VERIFY_BODY.get().contains("\"pseudonym\":\"M-001\""));
        assertTrue(DESCRIBE_BODY.get().contains("\"strict\":true"), "defence in depth: the rewrite is strict");
        assertEquals(1, countAudit(AuditTypeIds.DEID_UPLOAD_CONFIRMED));
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT original_filename FROM ingest_item WHERE source_kind = 'upload'");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            assertEquals("M-001_20210104_OD.dcm", rs.getString(1));
        }
    }

    @Test
    void aDicomThatFailsVerificationIsRefusedUnmodifiedAndDeleted() throws Exception {
        verifyAnswer = "{\"ok\":false,\"violations\":[\"PatientName\",\"PrivateTags\"]}";
        MvcResult r = mockMvc(true).perform(compliantDicom(dicomBytes(2), "M-001_20210104_OD.dcm").session(dm()))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertRejected(r, "PatientName", "PrivateTags");
        assertEquals(1, VERIFY_CALLS.get());
        assertEquals(0, DESCRIBE_CALLS.get(), "refused BEFORE any modification");
        assertEquals(0, ingestRows());
        assertEquals(0, filesUnder(STORE_ROOT), "the stored file is deleted");
        assertEquals(1, countAudit(AuditTypeIds.DEID_UPLOAD_REJECTED));
    }

    @Test
    void aSidecarThatCannotAnswerMeansNoUpload() throws Exception {
        verifyAnswer = "garbage";
        mockMvc(true).perform(compliantDicom(dicomBytes(3), "M-001_20210104_OD.dcm").session(dm()))
                .andExpect(status().is(502));
        assertEquals(0, DESCRIBE_CALLS.get());
        assertEquals(0, ingestRows());
        assertEquals(0, filesUnder(STORE_ROOT));
    }

    /* ---------------- (f) the name ---------------- */

    @Test
    void anyOtherFilenameIsRefusedForE2eAndDicom() throws Exception {
        for (String bad : new String[] {"scan.e2e", "Mustermann_Max_20210104_OD.e2e", "M-001_20210104_OD.png",
                "M-001_20210104_OU.e2e", "M-001_20211304_OD.e2e", "M-001_20210104_OD_x.e2e"}) {
            assertRejected(mockMvc(true).perform(compliantE2e(E2eTestFiles.e2e(E2eTestFiles.patient("")), bad).session(dm()))
                    .andExpect(status().isUnprocessableEntity()).andReturn(), "filename");
        }
        assertRejected(mockMvc(true).perform(compliantDicom(dicomBytes(4), "export.dcm").session(dm()))
                .andExpect(status().isUnprocessableEntity()).andReturn(), "filename");
        // an E2E under a .dcm name
        assertRejected(mockMvc(true).perform(compliantE2e(E2eTestFiles.e2e(E2eTestFiles.patient("")), "M-001_20210104_OD.dcm").session(dm()))
                .andExpect(status().isUnprocessableEntity()).andReturn(), "filename");
        assertEquals(0, ingestRows());
        assertEquals(0, VERIFY_CALLS.get(), "nothing reaches the sidecar for a request that is already wrong");
    }

    /* ---------------- mode off: unchanged ---------------- */

    @Test
    void withTheModeOffAPngAndTheLegacyE2eStubStillGoThrough() throws Exception {
        mockMvc(false).perform(multipart(BASE + "/commit")
                .file(new MockMultipartFile("file", "Muster_Max.png", "image/png", PNG))
                .param("patientId", "M-002").session(dm()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("UNBOUND"));
        byte[] legacy = new byte[1024];
        System.arraycopy("CMDb".getBytes(StandardCharsets.US_ASCII), 0, legacy, 0, 4);
        mockMvc(false).perform(multipart(BASE + "/commit")
                .file(new MockMultipartFile("file", "scan.e2e", "application/octet-stream", legacy))
                .param("studyEventId", "3").param("scanDate", "2021-01-04").param("laterality", "OD")
                .session(dm()))
                .andExpect(status().isCreated());
        assertEquals(0, VERIFY_CALLS.get());
        assertEquals(0, countAudit(AuditTypeIds.DEID_UPLOAD_CONFIRMED));
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT original_filename FROM ingest_item WHERE source_kind = 'upload' ORDER BY 1");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            assertEquals("Muster_Max.png", rs.getString(1), "off: the received name is kept, as before");
        }
    }

    @Test
    void withTheModeOffADicomIsDescribedWithoutVerifyOrStrict() throws Exception {
        mockMvc(false).perform(multipart(BASE + "/commit")
                .file(new MockMultipartFile("file", "export.dcm", "application/dicom", dicomBytes(5)))
                .param("studyEventId", "3").session(dm()))
                .andExpect(status().isCreated());
        assertEquals(0, VERIFY_CALLS.get());
        assertEquals(1, DESCRIBE_CALLS.get());
        assertFalse(DESCRIBE_BODY.get().contains("strict"));
    }
}
