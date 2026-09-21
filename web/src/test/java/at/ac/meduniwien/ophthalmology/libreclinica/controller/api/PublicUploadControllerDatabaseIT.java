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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import java.util.Arrays;
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
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import at.ac.meduniwien.ophthalmology.libreclinica.dao.core.CoreResources;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.DicomDescribeClient;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.IngestArtifactStore;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.StudySubjectFinder;
import at.ac.meduniwien.ophthalmology.libreclinica.service.study.StudySettingService;

/**
 * DR-029 — the combined public upload page.
 *
 * <p>Pins what the one front door does with each kind of file, and — because
 * the page has no login — what it must not do: keep a DICOM file whose
 * patient it could not replace, file against a visit that is not live on the
 * date given, or tell an anonymous caller more than the pages it replaces
 * did. The DICOM sidecar is a stub here; what matters is what the app asks
 * of it and what it does with the answer.
 */
@SuppressWarnings("null")
class PublicUploadControllerDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static final String BASE = "/api/v1/public/upload";
    private static final String TOKEN = "it-token";

    @TempDir
    static Path STORE_ROOT;

    @TempDir
    static Path E2E_ROOT;

    private static java.util.Properties SAVED_DATAINFO;

    /** 1x1 transparent PNG. */
    private static final byte[] PNG = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk"
                    + "+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==");

    /** The stub sidecar and what it saw. */
    private static HttpServer STUB;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AtomicReference<String> STUB_PSEUDONYM = new AtomicReference<>();
    private static final AtomicReference<String> STUB_PATH = new AtomicReference<>();
    private static volatile int stubStatus = 200;
    private static volatile String stubSop = "1.2.826.0.1.3680043.8.498.1";

    @BeforeAll
    static void overrideConfigAndStartStub() throws Exception {
        STUB = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        STUB.createContext("/describe", ex -> {
            JsonNode body = JSON.readTree(ex.getRequestBody());
            String path = body.path("path").asText(null);
            STUB_PATH.set(path);
            STUB_PSEUDONYM.set(body.get("pseudonym") == null || body.get("pseudonym").isNull()
                    ? null : body.get("pseudonym").asText());
            byte[] out;
            int status = stubStatus;
            if (!TOKEN.equals(ex.getRequestHeaders().getFirst(DicomDescribeClient.TOKEN_HEADER))) {
                status = 401;
                out = "{\"message\":\"missing or wrong token\"}".getBytes(StandardCharsets.UTF_8);
            } else if (status != 200) {
                out = "{\"message\":\"not a DICOM file\"}".getBytes(StandardCharsets.UTF_8);
            } else {
                // The real sidecar writes the preview beside the file and pseudonymises in place.
                String preview = path.replaceAll("\\.dcm$", "") + ".png";
                Files.write(Path.of(preview), PNG);
                out = ("{\"sopInstanceUid\":\"" + stubSop + "\","
                        + "\"sopClassUid\":\"1.2.840.10008.5.1.4.1.1.77.1.5.1\","
                        + "\"studyInstanceUid\":\"1.2.3\",\"seriesInstanceUid\":\"1.2.3.4\","
                        + "\"modality\":\"OP\",\"studyDate\":\"2021-01-04\",\"acquisitionDate\":\"2021-01-04\","
                        + "\"laterality\":\"OD\",\"manufacturer\":\"Carl Zeiss Meditec\","
                        + "\"manufacturerModelName\":\"CLARUS 700\","
                        + "\"previewPngPath\":\"" + preview + "\",\"identityRemoved\":true,\"changedTags\":4}")
                        .getBytes(StandardCharsets.UTF_8);
            }
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, out.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(out);
            }
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
        live.setProperty(DicomDescribeClient.KEY_URL, stubUrl());
        live.setProperty(DicomDescribeClient.KEY_TOKEN, TOKEN);
    }

    @AfterAll
    static void restoreConfigAndStopStub() throws Exception {
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
        stubStatus = 200;
        stubSop = "1.2.826.0.1.3680043.8.498." + System.nanoTime();
        STUB_PSEUDONYM.set(null);
        STUB_PATH.set(null);
    }

    @AfterEach
    void cleanRows() throws Exception {
        try (Connection c = DATA_SOURCE.getConnection()) {
            exec(c, "DELETE FROM audit_log_event WHERE audit_table IN ('ingest_item', 'retinal_inference_job')");
            exec(c, "DELETE FROM retinal_inference_job WHERE ingest_item_id IN "
                    + "(SELECT ingest_item_id FROM ingest_item WHERE source_kind IN ('upload', 'portal-oct'))");
            exec(c, "DELETE FROM ingest_item WHERE source_kind IN ('upload', 'portal-oct')");
            exec(c, "DELETE FROM study_setting WHERE setting_key LIKE 'ingest.%'");
        }
    }

    private static void exec(Connection c, String sql) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }

    private static String stubUrl() {
        return "http://127.0.0.1:" + STUB.getAddress().getPort() + "/describe";
    }

    private MockMvc mockMvc() {
        return mockMvc(new DicomDescribeClient(stubUrl(), TOKEN));
    }

    private MockMvc mockMvc(DicomDescribeClient describe) {
        StudySubjectFinder finder = new StudySubjectFinder(DATA_SOURCE);
        PublicUploadController c = new PublicUploadController(
                DATA_SOURCE, finder,
                new PublicOctUploadController(DATA_SOURCE, finder),
                new PublicImageUploadController(DATA_SOURCE, finder),
                new IngestUploadService(DATA_SOURCE, new IngestArtifactStore(), describe));
        return MockMvcBuilders.standaloneSetup(c)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    /* ---------------- fixtures ---------------- */

    private static byte[] dicomBytes(int salt) {
        byte[] b = new byte[512];
        System.arraycopy("DICM".getBytes(StandardCharsets.US_ASCII), 0, b, 128, 4);
        b[200] = (byte) salt;
        return b;
    }

    private static byte[] e2eBytes() {
        byte[] b = new byte[1024];
        System.arraycopy("CMDb".getBytes(StandardCharsets.US_ASCII), 0, b, 0, 4);
        return b;
    }

    private static MockMultipartFile part(String name, String claimedType, byte[] bytes) {
        return new MockMultipartFile("file", name, claimedType, bytes);
    }

    private static String sha256(byte[] bytes) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte x : d) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private long idOf(MvcResult r) throws Exception {
        return JSON.readTree(r.getResponse().getContentAsString()).path("ingestItemId").asLong();
    }

    private ResultSet row(Connection c, long id, String columns) throws Exception {
        PreparedStatement ps = c.prepareStatement(
                "SELECT " + columns + " FROM ingest_item WHERE ingest_item_id = ?");
        ps.setLong(1, id);
        ResultSet rs = ps.executeQuery();
        assertTrue(rs.next(), "row " + id + " should exist");
        return rs;
    }

    private static long filesUnder(Path root) throws IOException {
        if (!Files.exists(root)) return 0;
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(Files::isRegularFile).count();
        }
    }

    /* ---------------- the kind is the bytes ---------------- */

    @Test
    void aPngCalledJpegIsStoredAsAPng() throws Exception {
        MvcResult r = mockMvc().perform(multipart(BASE + "/commit")
                .file(part("photo.jpg", "image/jpeg", PNG))
                .param("patientId", "M-001")
                .param("laterality", "OS"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("image"))
                .andExpect(jsonPath("$.format").value("png"))
                .andExpect(jsonPath("$.status").value("UNBOUND"))
                .andExpect(jsonPath("$.laterality").value("OS"))
                .andExpect(jsonPath("$.device").value("remidio"))
                .andExpect(jsonPath("$.deidentified").value(false))
                .andReturn();
        long id = idOf(r);
        try (Connection c = DATA_SOURCE.getConnection();
             ResultSet rs = row(c, id, "content_type, stored_path, preview_png_path, sha256, "
                     + "original_filename, patient_id, source_kind, kind")) {
            assertEquals("image/png", rs.getString("content_type"));
            assertEquals("image", rs.getString("kind"));
            assertEquals("upload", rs.getString("source_kind"));
            assertEquals("photo.jpg", rs.getString("original_filename"));
            assertEquals("M-001", rs.getString("patient_id"));
            Path stored = Path.of(rs.getString("stored_path"));
            assertTrue(stored.toString().endsWith(".png"), "stored under the sniffed extension: " + stored);
            assertTrue(stored.startsWith(STORE_ROOT.resolve("image")), "under the unified image root");
            assertTrue(Files.exists(stored));
            assertEquals(stored.toString(), rs.getString("preview_png_path"));
            assertEquals(sha256(PNG), rs.getString("sha256"));
        }
    }

    @Test
    void aTextFileIsRefusedAndNothingIsKept() throws Exception {
        long before = filesUnder(STORE_ROOT);
        mockMvc().perform(multipart(BASE + "/commit")
                .file(part("notes.dcm", "application/dicom", "hello world, not a file".getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(UploadRoute.UNSUPPORTED_MESSAGE));
        assertEquals(before, filesUnder(STORE_ROOT));
        assertEquals(0, countRows("source_kind = 'upload'"));
    }

    /* ---------------- DICOM ---------------- */

    @Test
    void aDicomUploadIsDescribedPseudonymisedAndRecorded() throws Exception {
        MvcResult r = mockMvc().perform(multipart(BASE + "/commit")
                .file(part("export.dcm", "application/octet-stream", dicomBytes(1))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("dicom"))
                .andExpect(jsonPath("$.format").value("dicom"))
                .andExpect(jsonPath("$.status").value("UNBOUND"))
                .andExpect(jsonPath("$.laterality").value("OD"))
                .andExpect(jsonPath("$.acquisitionDate").value("2021-01-04"))
                .andExpect(jsonPath("$.device").value("clarus"))
                .andExpect(jsonPath("$.deidentified").value(true))
                .andReturn();
        // An upload nobody has filed carries no name at all.
        assertNull(STUB_PSEUDONYM.get());
        assertTrue(STUB_PATH.get().startsWith(STORE_ROOT.resolve("dicom").toString()),
                "the sidecar is pointed at the unified dicom root");
        long id = idOf(r);
        try (Connection c = DATA_SOURCE.getConnection();
             ResultSet rs = row(c, id, "sop_instance_uid, modality, deidentified_at, preview_png_path, "
                     + "content_type, laterality, acquisition_date, device, stored_path")) {
            assertEquals(stubSop, rs.getString("sop_instance_uid"));
            assertEquals("OP", rs.getString("modality"));
            assertNotNull(rs.getTimestamp("deidentified_at"));
            assertEquals("application/dicom", rs.getString("content_type"));
            assertEquals("OD", rs.getString("laterality"));
            assertEquals("2021-01-04", rs.getString("acquisition_date"));
            assertEquals("clarus", rs.getString("device"));
            assertTrue(Files.exists(Path.of(rs.getString("preview_png_path"))), "the preview the sidecar wrote");
            assertTrue(rs.getString("stored_path").endsWith(".dcm"));
        }
    }

    @Test
    void aDicomUploadWithoutTheSidecarIsRefusedAndNotKept() throws Exception {
        long before = filesUnder(STORE_ROOT);
        mockMvc(new DicomDescribeClient("", "")).perform(multipart(BASE + "/commit")
                .file(part("export.dcm", "application/dicom", dicomBytes(2))))
                .andExpect(status().isServiceUnavailable());
        assertEquals(0, countRows("kind = 'dicom'"));
        assertEquals(before, filesUnder(STORE_ROOT), "a file that could not be cleaned is not kept");
    }

    @Test
    void aFileTheSidecarCannotReadIsRefusedAndNotKept() throws Exception {
        stubStatus = 422;
        long before = filesUnder(STORE_ROOT);
        mockMvc().perform(multipart(BASE + "/commit")
                .file(part("export.dcm", "application/dicom", dicomBytes(3))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("the file is not a DICOM object"));
        assertEquals(0, countRows("kind = 'dicom'"));
        assertEquals(before, filesUnder(STORE_ROOT));
    }

    @Test
    void aVisitPickedDicomLandsBoundWithTheLabelAsPseudonym() throws Exception {
        MvcResult r = mockMvc().perform(multipart(BASE + "/commit")
                .file(part("export.dcm", "application/dicom", dicomBytes(4)))
                .param("studyEventId", "3")
                .param("scanDate", "2021-01-04"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("BOUND"))
                .andReturn();
        assertEquals("M-001", STUB_PSEUDONYM.get(), "the file is labelled with the visit's subject");
        long id = idOf(r);
        try (Connection c = DATA_SOURCE.getConnection();
             ResultSet rs = row(c, id, "status, match_policy, bound_study_subject_id, bound_study_event_id, "
                     + "bound_by_user_id")) {
            assertEquals("BOUND", rs.getString("status"));
            assertEquals("portal", rs.getString("match_policy"));
            assertEquals(1, rs.getInt("bound_study_subject_id"));
            assertEquals(3, rs.getInt("bound_study_event_id"));
            rs.getInt("bound_by_user_id");
            assertTrue(rs.wasNull(), "nobody is logged in on the public page");
        }
        assertEquals(1, auditRows(AuditTypeIds.IMAGE_BIND, id, true), "a system bind leaves a user-less audit row");
    }

    @Test
    void aVisitOnAnotherDateIsRefusedAndNothingIsKept() throws Exception {
        long before = filesUnder(STORE_ROOT);
        mockMvc().perform(multipart(BASE + "/commit")
                .file(part("export.dcm", "application/dicom", dicomBytes(5)))
                .param("studyEventId", "3")
                .param("scanDate", "1999-01-01"))
                .andExpect(status().isBadRequest());
        assertEquals(0, countRows("kind = 'dicom'"));
        assertEquals(before, filesUnder(STORE_ROOT));
        assertNull(STUB_PATH.get(), "the sidecar is not even asked for a file that will not be kept");
    }

    /* ---------------- dedup ---------------- */

    @Test
    void theSameBytesTwiceIsADuplicate() throws Exception {
        long first = idOf(mockMvc().perform(multipart(BASE + "/commit")
                .file(part("a.png", "image/png", PNG)))
                .andExpect(status().isCreated()).andReturn());
        mockMvc().perform(multipart(BASE + "/commit")
                .file(part("b.png", "image/png", PNG)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.duplicate").value(true))
                .andExpect(jsonPath("$.existingIngestItemId").value(first));
        assertEquals(1, countRows("kind = 'image'"));
    }

    @Test
    void theSameSopInstanceTwiceIsADuplicate() throws Exception {
        long first = idOf(mockMvc().perform(multipart(BASE + "/commit")
                .file(part("a.dcm", "application/dicom", dicomBytes(6))))
                .andExpect(status().isCreated()).andReturn());
        long before = filesUnder(STORE_ROOT);
        // Different bytes, same SOP instance: the sidecar answers the same UID.
        mockMvc().perform(multipart(BASE + "/commit")
                .file(part("a-copy.dcm", "application/dicom", dicomBytes(7))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.existingIngestItemId").value(first));
        assertEquals(1, countRows("kind = 'dicom'"));
        assertEquals(before, filesUnder(STORE_ROOT), "the duplicate and its preview are discarded");
    }

    @Test
    void preflightKnowsAStoredFile() throws Exception {
        long id = idOf(mockMvc().perform(multipart(BASE + "/commit")
                .file(part("a.png", "image/png", PNG)))
                .andExpect(status().isCreated()).andReturn());
        mockMvc().perform(get(BASE + "/preflight").param("sha256", sha256(PNG)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exists").value(true))
                .andExpect(jsonPath("$.ingestItemId").value(id));
        mockMvc().perform(get(BASE + "/preflight").param("sha256", "0".repeat(64)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exists").value(false));
        mockMvc().perform(get(BASE + "/preflight").param("sha256", "nope"))
                .andExpect(status().isBadRequest());
    }

    /* ---------------- the OCT route is delegated, not rewritten ---------------- */

    @Test
    void anE2eUploadTakesTheOctRoute() throws Exception {
        MvcResult r = mockMvc().perform(multipart(BASE + "/commit")
                .file(part("scan.e2e", "application/octet-stream", e2eBytes()))
                .param("patientId", "M-001")
                .param("scanDate", "2021-01-04")
                .param("laterality", "OD")
                .param("park", "true"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("e2e"))
                .andExpect(jsonPath("$.format").value("e2e"))
                .andExpect(jsonPath("$.status").value("UNBOUND"))
                .andExpect(jsonPath("$.ingestItemId").isNumber())
                .andReturn();
        long id = idOf(r);
        try (Connection c = DATA_SOURCE.getConnection();
             ResultSet rs = row(c, id, "kind, source_kind, stored_path")) {
            assertEquals("e2e", rs.getString("kind"));
            assertEquals("portal-oct", rs.getString("source_kind"));
            assertTrue(Path.of(rs.getString("stored_path")).startsWith(E2E_ROOT), "the OCT route's own root");
        }
    }

    @Test
    void anE2eUploadFiledByVisitTakesItsLabelFromTheVisit() throws Exception {
        MvcResult r = mockMvc().perform(multipart(BASE + "/commit")
                .file(part("scan.e2e", "application/octet-stream", e2eBytes()))
                .param("studyEventId", "3")
                .param("scanDate", "2021-01-04")
                .param("laterality", "OD"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("e2e"))
                .andReturn();
        long id = idOf(r);
        try (Connection c = DATA_SOURCE.getConnection();
             ResultSet rs = row(c, id, "patient_id, status, match_policy")) {
            assertEquals("M-001", rs.getString("patient_id"));
            assertEquals("BOUND", rs.getString("status"));
            assertEquals("visit-picked", rs.getString("match_policy"));
        }
    }

    /* ---------------- undo ---------------- */

    @Test
    void undoWithinTheWindowRemovesTheUploadAndItsFile() throws Exception {
        MvcResult r = mockMvc().perform(multipart(BASE + "/commit")
                .file(part("a.png", "image/png", PNG)))
                .andExpect(status().isCreated()).andReturn();
        long id = idOf(r);
        Path stored;
        try (Connection c = DATA_SOURCE.getConnection(); ResultSet rs = row(c, id, "stored_path")) {
            stored = Path.of(rs.getString("stored_path"));
        }
        assertTrue(Files.exists(stored));
        mockMvc().perform(delete(BASE + "/items/" + id)).andExpect(status().isNoContent());
        assertEquals(0, countRows("ingest_item_id = " + id));
        assertFalse(Files.exists(stored), "the bytes go with the row");
        mockMvc().perform(delete(BASE + "/items/" + id)).andExpect(status().isNotFound());
    }

    @Test
    void undoRefusesAFileSomebodyHasSinceReconciled() throws Exception {
        long id = idOf(mockMvc().perform(multipart(BASE + "/commit")
                .file(part("a.png", "image/png", PNG)))
                .andExpect(status().isCreated()).andReturn());
        try (Connection c = DATA_SOURCE.getConnection()) {
            exec(c, "UPDATE ingest_item SET status = 'BOUND', match_policy = 'manual', "
                    + "bound_study_subject_id = 1 WHERE ingest_item_id = " + id);
        }
        mockMvc().perform(delete(BASE + "/items/" + id)).andExpect(status().isConflict());
        assertEquals(1, countRows("ingest_item_id = " + id));
    }

    /* ---------------- per-study gate ---------------- */

    /**
     * A study that has turned an ingress off refuses the kind — and, on a
     * page with no login, refuses it in the same words as a visit outside
     * its scope, so the caller learns nothing about which studies exist.
     * Images are caught by the portal scope itself (StudyScopeConfig folds
     * ingest.image.enabled in); DICOM by the upload's own gate.
     */
    @Test
    void aStudyThatDoesNotAcceptTheKindRefusesItDiscreetly() throws Exception {
        StudySettingService settings = new StudySettingService(DATA_SOURCE);
        settings.put(1, StudySettingService.INGEST_IMAGE_ENABLED, "false", 1);
        settings.put(1, StudySettingService.INGEST_DICOM_ENABLED, "false", 1);
        long before = filesUnder(STORE_ROOT);
        mockMvc().perform(multipart(BASE + "/commit")
                .file(part("a.png", "image/png", PNG))
                .param("studyEventId", "3")
                .param("scanDate", "2021-01-04"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("that visit is not scheduled for the submitted date"));
        mockMvc().perform(multipart(BASE + "/commit")
                .file(part("export.dcm", "application/dicom", dicomBytes(8)))
                .param("studyEventId", "3")
                .param("scanDate", "2021-01-04"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("that visit is not scheduled for the submitted date"));
        assertEquals(0, countRows("source_kind = 'upload'"));
        assertEquals(before, filesUnder(STORE_ROOT));
        assertNull(STUB_PATH.get(), "a file that will be refused is never handed to the sidecar");
    }

    /* ---------------- identification answers like the pages it replaces ---------------- */

    @Test
    void resolveAndSearchAnswerLikeThePagesTheyReplace() throws Exception {
        mockMvc().perform(post(BASE + "/resolve")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"scans\":[{\"patientId\":\"M-001\",\"scanDate\":\"2021-01-04\",\"laterality\":\"OD\"},"
                        + "{\"patientId\":\"ZZZ-999\",\"scanDate\":null,\"laterality\":\"OS\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scans[0].state").value("suggested"))
                .andExpect(jsonPath("$.scans[0].candidates[0].matchingEvent.studyEventId").value(3))
                .andExpect(jsonPath("$.scans[1].state").value("nopatient"))
                .andExpect(jsonPath("$.scans[1].candidates.length()").value(0));
        mockMvc().perform(get(BASE + "/patients/search").param("q", "M-0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subjects").isArray());
        mockMvc().perform(get(BASE + "/patients/search").param("q", "M"))
                .andExpect(status().isBadRequest());
        // Today's visits stay off until the institution turns them on (C4).
        mockMvc().perform(get(BASE + "/visits")).andExpect(status().isNotFound());
        mockMvc().perform(get(BASE + "/patients/1/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    /* ---------------- helpers ---------------- */

    private int countRows(String where) throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM ingest_item WHERE " + where);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private int auditRows(int type, long entityId, boolean userless) throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM audit_log_event WHERE audit_log_event_type_id = ? "
                             + "  AND audit_table = 'ingest_item' AND entity_id = ? AND user_id IS "
                             + (userless ? "NULL" : "NOT NULL"))) {
            ps.setInt(1, type);
            ps.setInt(2, (int) entityId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    @SuppressWarnings("unused")
    private static String hex(byte[] b) {
        return Arrays.toString(b);
    }
}
