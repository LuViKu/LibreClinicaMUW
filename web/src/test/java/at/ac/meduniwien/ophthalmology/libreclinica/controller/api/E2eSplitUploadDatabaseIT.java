/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import at.ac.meduniwien.ophthalmology.libreclinica.testsupport.ProductionMvc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.core.CoreResources;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.DicomDescribeClient;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.ImageIngestRetentionService;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.IngestArtifactStore;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.E2eSidecarClient;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RemoteRetinalInferenceClient;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.StudySubjectFinder;

/**
 * DR-041 - one upload of a multimodal Spectralis .e2e, split on the server
 * into one ingest_item per series. The converter sidecar is a stub here (the
 * real one is built separately); what matters is what the app asks of it and
 * what it makes of the answer.
 *
 * <p>The stub's JSON is built with Jackson, never by string concatenation, so
 * a Windows path in a test cannot produce invalid escapes.
 */
@SuppressWarnings({"null", "resource"})
class E2eSplitUploadDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static final String BASE = "/api/v1/public/upload";
    private static final String STAFF = "/api/v1/ingest/upload";
    private static final String TOKEN = "e2e-it-token";
    private static final String MODALITY_PREFIX = "E2E_SPLIT_IT_";

    @TempDir
    static Path STORE_ROOT;

    @TempDir
    static Path E2E_ROOT;

    private static java.util.Properties SAVED_DATAINFO;
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final byte[] PNG = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk"
                    + "+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==");

    /* ---- the stub sidecar ---- */
    private static HttpServer STUB;
    private static volatile int describeStatus = 200;
    private static volatile Object describeAnswer;
    private static final List<Map<String, String>> DESCRIBE_CALLS = new CopyOnWriteArrayList<>();
    private static final List<Map<String, String>> IMAGE_CALLS = new CopyOnWriteArrayList<>();

    private static Map<String, Object> series(String key, String modality, String device, String lat,
                                              String at, int frames, Integer octIndex) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("seriesKey", key);
        s.put("seriesModality", modality);
        s.put("deviceModality", device);
        s.put("scanPattern", "OCT".equals(modality) ? "OCT ART Volume" : "Images");
        s.put("laterality", lat);
        s.put("acquiredAt", at);
        s.put("frames", frames);
        s.put("width", 768);
        s.put("height", 496);
        s.put("octVolumeIndex", octIndex);
        return s;
    }

    private static Map<String, Object> answer(String header, List<Map<String, Object>> series) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("headerPatient", header);
        m.put("series", series);
        return m;
    }

    /** OD OCT, OS OCT, OD IR, OS IR, OD FA time sequence, OD OCT line scan. */
    private static List<Map<String, Object>> sixSeries(String date) {
        return List.of(
                series("s0", "OCT", "IR", "OD", date + "T10:12:03", 97, 0),
                series("s1", "OCT", "IR", "OS", date + "T10:14:40", 97, 1),
                series("s2", "IR", "IR", "OD", date + "T10:11:00", 1, null),
                series("s3", "IR", "IR", "OS", date + "T10:13:00", 1, null),
                series("s4", "FA", "FA", "OD", date + "T10:30:00", 12, null),
                series("s5", "OCT_LINE", "IR", "OD", date + "T10:20:00", 1, 2));
    }

    @BeforeAll
    static void startStub() throws Exception {
        STUB = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        STUB.createContext("/e2e/describe", ex -> {
            JsonNode body = JSON.readTree(ex.getRequestBody());
            Map<String, String> call = new LinkedHashMap<>();
            call.put("storedName", body.path("storedName").asText());
            call.put("expectedLabel", body.path("expectedLabel").asText());
            call.put("token", String.valueOf(ex.getRequestHeaders().getFirst("X-MUW-Inference-Token")));
            DESCRIBE_CALLS.add(call);
            int status = describeStatus;
            byte[] out;
            if (!TOKEN.equals(call.get("token"))) {
                status = 401;
                out = "{}".getBytes(StandardCharsets.UTF_8);
            } else if (status != 200) {
                out = "{\"detail\":\"boom\"}".getBytes(StandardCharsets.UTF_8);
            } else {
                out = JSON.writeValueAsString(describeAnswer).getBytes(StandardCharsets.UTF_8);
            }
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, out.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(out);
            }
        });
        STUB.createContext("/e2e/series-image", ex -> {
            Map<String, String> call = new LinkedHashMap<>();
            for (String kv : ex.getRequestURI().getRawQuery().split("&")) {
                String[] p = kv.split("=", 2);
                call.put(p[0], p.length > 1 ? p[1] : "");
            }
            call.put("token", String.valueOf(ex.getRequestHeaders().getFirst("X-MUW-Inference-Token")));
            IMAGE_CALLS.add(call);
            boolean found = !"nope".equals(call.get("seriesKey")) && TOKEN.equals(call.get("token"));
            byte[] out = found ? PNG : new byte[0];
            if (found) ex.getResponseHeaders().add("Content-Type", "image/png");
            ex.sendResponseHeaders(found ? 200 : 404, found ? out.length : -1);
            if (found) {
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(out);
                }
            } else {
                ex.close();
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
        live.setProperty("core.retinalInference.preprocessUrl", "http://127.0.0.1:" + STUB.getAddress().getPort());
        live.setProperty("core.retinalInference.preprocessToken", TOKEN);
    }

    @AfterAll
    static void stopStub() throws Exception {
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
    void reset() {
        describeStatus = 200;
        describeAnswer = answer("matches", sixSeries("2021-01-04"));
        DESCRIBE_CALLS.clear();
        IMAGE_CALLS.clear();
    }

    @AfterEach
    void cleanRows() throws Exception {
        exec("DELETE FROM audit_log_event WHERE audit_table IN ('ingest_item', 'retinal_inference_job')");
        exec("DELETE FROM retinal_inference_job WHERE ingest_item_id IN "
                + "(SELECT ingest_item_id FROM ingest_item WHERE source_kind = 'portal-oct')");
        exec("DELETE FROM ingest_item WHERE source_kind = 'portal-oct'");
        exec("DELETE FROM study_setting WHERE setting_key LIKE 'ingest.%'");
        exec("DELETE FROM event_definition_imaging WHERE imaging_modality_id IN "
                + "(SELECT imaging_modality_id FROM imaging_modality WHERE code LIKE '" + MODALITY_PREFIX + "%')");
        exec("DELETE FROM imaging_modality WHERE code LIKE '" + MODALITY_PREFIX + "%'");
        try (Stream<Path> s = Files.list(E2E_ROOT)) {
            s.forEach(p -> p.toFile().delete());
        }
    }

    private static void exec(String sql) throws Exception {
        try (Connection c = DATA_SOURCE.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }

    /* ---------------- builders ---------------- */

    private RemoteRetinalInferenceClient remote;

    private MockMvc publicMvc() {
        StudySubjectFinder finder = new StudySubjectFinder(DATA_SOURCE);
        remote = Mockito.mock(RemoteRetinalInferenceClient.class);
        Mockito.when(remote.isConfigured()).thenReturn(false);
        PublicOctUploadController oct = new PublicOctUploadController(DATA_SOURCE, finder, remote,
                Mockito.mock(RetinalInferenceApiController.class));
        PublicUploadController c = new PublicUploadController(DATA_SOURCE, finder, oct,
                new PublicImageUploadController(DATA_SOURCE, finder),
                new IngestUploadService(DATA_SOURCE, new IngestArtifactStore(), new DicomDescribeClient("", "")));
        return ProductionMvc.standalone(c).setControllerAdvice(new ApiExceptionHandler()).build();
    }

    private MockMvc staffMvc() {
        StudySubjectFinder finder = new StudySubjectFinder(DATA_SOURCE);
        IngestUploadApiController c = new IngestUploadApiController(
                DATA_SOURCE, new SiteVisibilityFilter(DATA_SOURCE), finder,
                new PublicOctUploadController(DATA_SOURCE, finder),
                new IngestUploadService(DATA_SOURCE, new IngestArtifactStore(), new DicomDescribeClient("", "")));
        return ProductionMvc.standalone(c).setControllerAdvice(new ApiExceptionHandler()).build();
    }

    private MockMvc inboxMvc() {
        IngestInboxApiController c = new IngestInboxApiController(
                DATA_SOURCE, new SiteVisibilityFilter(DATA_SOURCE), new StudySubjectFinder(DATA_SOURCE));
        return ProductionMvc.standalone(c).setControllerAdvice(new ApiExceptionHandler()).build();
    }

    private static MockHttpSession dm() {
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

    private static byte[] e2e(int salt) {
        byte[] b = new byte[2048];
        System.arraycopy("CMDb".getBytes(StandardCharsets.US_ASCII), 0, b, 0, 4);
        b[100] = (byte) salt;
        b[101] = (byte) (salt >> 8);
        return b;
    }

    private static MockMultipartFile file(int salt) {
        return new MockMultipartFile("file", "EIAMD01D.E2E", "application/octet-stream", e2e(salt));
    }

    private static String sha256(byte[] bytes) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte x : d) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private JsonNode json(MvcResult r) throws Exception {
        return JSON.readTree(r.getResponse().getContentAsString());
    }

    private MvcResult splitCommit(int salt, String... extraParams) throws Exception {
        var req = multipart(BASE + "/commit").file(file(salt))
                .param("patientId", "M-001").param("device", "spectralis").param("split", "true");
        for (int i = 0; i < extraParams.length; i += 2) req = req.param(extraParams[i], extraParams[i + 1]);
        return publicMvc().perform(req).andReturn();
    }

    /** An item row by series key. */
    private Map<String, Object> item(String sha, String key) throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT ingest_item_id, status, laterality, acquisition_date::text AS acq, "
                             + "acquisition_date_source, scan_index, series_modality, series_frames, "
                             + "imaging_modality_id, bound_study_event_id, bound_study_subject_id, "
                             + "stored_path, oct_volume, kind, source_kind, patient_id "
                             + "FROM ingest_item WHERE sha256 = ? AND e2e_series_key = ?")) {
            ps.setString(1, sha);
            ps.setString(2, key);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "item " + key + " should exist");
                Map<String, Object> m = new LinkedHashMap<>();
                for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                    m.put(rs.getMetaData().getColumnLabel(i), rs.getObject(i));
                }
                return m;
            }
        }
    }

    private int count(String where) throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM ingest_item WHERE " + where);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static long filesIn(Path dir) throws Exception {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(Files::isRegularFile).count();
        }
    }

    /** An OCT entry (e2e_series OCT) and an IR entry on study 1, with the OCT entry on visit 3's plan. */
    private static int[] catalogueForVisit3(String tasks) throws Exception {
        try (Connection c = DATA_SOURCE.getConnection()) {
            int sed = LifecycleFixtures.intQuery(
                    "SELECT study_event_definition_id FROM study_event WHERE study_event_id = 3");
            int octId = LifecycleFixtures.insertOne(c,
                    "INSERT INTO imaging_modality (study_id, code, label_de, label_en, device, kinds_accepted, "
                            + "laterality_required, ordinal, status_id, created_by_user_id, e2e_series) "
                            + "VALUES (1, '" + MODALITY_PREFIX + "OCT', 'OCT', 'OCT', 'spectralis', 'e2e', "
                            + "false, 96, 1, 1, 'OCT') RETURNING imaging_modality_id");
            int irId = LifecycleFixtures.insertOne(c,
                    "INSERT INTO imaging_modality (study_id, code, label_de, label_en, device, kinds_accepted, "
                            + "laterality_required, ordinal, status_id, created_by_user_id, e2e_series) "
                            + "VALUES (1, '" + MODALITY_PREFIX + "IR', 'IR', 'IR', 'spectralis', 'e2e', "
                            + "false, 97, 1, 1, 'IR') RETURNING imaging_modality_id");
            exec("INSERT INTO event_definition_imaging (study_event_definition_id, imaging_modality_id, "
                    + "requirement, laterality, retinal_tasks, created_by_user_id) VALUES (" + sed + ", "
                    + octId + ", 'optional', NULL, '" + tasks + "', 1)");
            return new int[] {octId, irId};
        }
    }

    /* ---------------- tests ---------------- */

    @Test
    void oneFileBecomesOneItemPerSeriesWithTheirOwnEyeDateAndScanIndex() throws Exception {
        MvcResult r = splitCommit(1, "park", "true");
        assertEquals(201, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        JsonNode body = json(r);
        String sha = sha256(e2e(1));
        assertEquals(sha, body.path("sha256").asText());
        assertEquals("matches", body.path("headerPatient").asText());
        assertTrue(body.path("parked").asBoolean());
        assertEquals(6, body.path("items").size());
        assertEquals("s0", body.path("items").get(0).path("seriesKey").asText(), "describe order is kept");
        assertEquals("OCT", body.path("items").get(0).path("seriesModality").asText());
        assertEquals(97, body.path("items").get(0).path("frames").asInt());
        assertEquals("UNBOUND", body.path("items").get(0).path("status").asText());
        assertEquals(0, body.path("items").get(0).path("jobs").size());

        // The server asked the sidecar for the stored file's bare name and the folder label, with the token.
        assertEquals(1, DESCRIBE_CALLS.size());
        assertEquals("M-001", DESCRIBE_CALLS.get(0).get("expectedLabel"));
        assertTrue(DESCRIBE_CALLS.get(0).get("storedName").matches("[A-Za-z0-9-]+\\.e2e"));
        assertEquals(1, filesIn(E2E_ROOT), "one stored file for the six items");

        Map<String, Object> s0 = item(sha, "s0");
        assertEquals("OD", s0.get("laterality"));
        assertEquals("2021-01-04", s0.get("acq"));
        assertEquals("file", s0.get("acquisition_date_source"));
        assertEquals(0, s0.get("scan_index"));
        assertEquals("OCT", s0.get("series_modality"));
        assertEquals(97, s0.get("series_frames"));
        assertEquals(Boolean.TRUE, s0.get("oct_volume"));
        assertEquals("e2e", s0.get("kind"));
        assertEquals("portal-oct", s0.get("source_kind"));
        assertEquals("M-001", s0.get("patient_id"));
        Map<String, Object> s1 = item(sha, "s1");
        assertEquals("OS", s1.get("laterality"));
        assertEquals(1, s1.get("scan_index"));
        Map<String, Object> ir = item(sha, "s2");
        assertNull(ir.get("scan_index"), "an en-face series has no scan index");
        assertEquals("IR", ir.get("series_modality"));
        assertEquals(Boolean.FALSE, ir.get("oct_volume"), "not analysable");
        Map<String, Object> fa = item(sha, "s4");
        assertEquals(12, fa.get("series_frames"));
        Map<String, Object> line = item(sha, "s5");
        assertEquals(2, line.get("scan_index"), "a line scan keeps its volume index");
        assertEquals("OCT_LINE", line.get("series_modality"));
        assertEquals(Boolean.FALSE, line.get("oct_volume"));
        // All six share one stored path.
        assertEquals(s0.get("stored_path"), fa.get("stored_path"));
        assertEquals(s0.get("stored_path"), line.get("stored_path"));
    }

    @Test
    void aVisitBindsEverySeriesFilesTheModalityByTokenAndStartsJobsForOctOnly() throws Exception {
        int[] mod = catalogueForVisit3("fluid");
        MvcResult r = splitCommit(2, "studyEventId", "3");
        assertEquals(201, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        JsonNode body = json(r);
        assertFalse(body.path("parked").asBoolean());
        String sha = sha256(e2e(2));
        for (String key : List.of("s0", "s1", "s2", "s3", "s4", "s5")) {
            Map<String, Object> it = item(sha, key);
            assertEquals("BOUND", it.get("status"), key);
            assertEquals(3, it.get("bound_study_event_id"), key);
        }
        assertEquals(mod[0], item(sha, "s0").get("imaging_modality_id"), "OCT by its e2e_series token");
        assertEquals(mod[1], item(sha, "s2").get("imaging_modality_id"), "IR by its e2e_series token");
        assertNull(item(sha, "s4").get("imaging_modality_id"), "FA has no entry: stays without a modality");
        assertNull(item(sha, "s5").get("imaging_modality_id"), "a line scan is not filed as the OCT volume entry");

        // Jobs: OCT volumes only, each with its own scan_index.
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT ii.e2e_series_key, j.task, j.scan_index, j.eye_laterality, j.status, j.e2e_sha256 "
                             + "FROM retinal_inference_job j JOIN ingest_item ii "
                             + "  ON ii.ingest_item_id = j.ingest_item_id "
                             + " WHERE ii.sha256 = ? ORDER BY ii.e2e_series_key");
        ) {
            ps.setString(1, sha);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("s0", rs.getString(1));
                assertEquals("fluid", rs.getString(2));
                assertEquals(0, rs.getInt(3));
                assertEquals("OD", rs.getString(4));
                assertEquals("queued", rs.getString(5));
                assertEquals(sha, rs.getString(6));
                assertTrue(rs.next());
                assertEquals("s1", rs.getString(1));
                assertEquals(1, rs.getInt(3));
                assertEquals("OS", rs.getString(4));
                assertFalse(rs.next(), "no job for IR, FA or the line scan");
            }
        }
        // The jobs are listed in the response, under their item.
        assertEquals(1, body.path("items").get(0).path("jobs").size());
        assertEquals("fluid", body.path("items").get(0).path("jobs").get(0).path("task").asText());
        assertEquals(0, body.path("items").get(2).path("jobs").size());

        // And the OCT volumes are preprocessed from the stored file with their own scan_index.
        Mockito.verify(remote, Mockito.timeout(10000)).preprocessUploadFile(
                anyLong(), anyString(), any(Path.class), eq("OD"), anyString(), eq(0));
        Mockito.verify(remote, Mockito.timeout(10000)).preprocessUploadFile(
                anyLong(), anyString(), any(Path.class), eq("OS"), anyString(), eq(1));
    }

    @Test
    void aHeaderPatientThatDiffersParksEveryItemWhateverTheVisit() throws Exception {
        catalogueForVisit3("fluid");
        describeAnswer = answer("differs", sixSeries("2021-01-04"));
        MvcResult r = splitCommit(3, "studyEventId", "3");
        assertEquals(201, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        JsonNode body = json(r);
        assertEquals("differs", body.path("headerPatient").asText());
        assertTrue(body.path("parked").asBoolean());
        String sha = sha256(e2e(3));
        assertEquals(6, count("sha256 = '" + sha + "' AND status = 'UNBOUND' AND imaging_modality_id IS NULL "
                + "AND bound_study_event_id IS NULL"));
        assertEquals(0, count("sha256 = '" + sha + "' AND status <> 'UNBOUND'"));
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM retinal_inference_job j "
                     + "JOIN ingest_item ii ON ii.ingest_item_id = j.ingest_item_id WHERE ii.sha256 = ?")) {
            ps.setString(1, sha);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertEquals(0, rs.getInt(1), "a parked file starts nothing");
            }
        }
    }

    @Test
    void withoutAVisitEachSeriesResolvesItsOwnFromLabelAndDate() throws Exception {
        List<Map<String, Object>> s = new ArrayList<>(List.of(
                series("a", "IR", "IR", "OD", "2021-01-04T09:00:00", 1, null),
                series("b", "IR", "IR", "OS", "2030-06-01T09:00:00", 1, null),
                series("c", "BAF", "BAF", "OD", null, 1, null)));
        describeAnswer = answer("empty", s);
        MvcResult r = splitCommit(4);
        assertEquals(201, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        String sha = sha256(e2e(4));
        Map<String, Object> a = item(sha, "a");
        assertEquals("BOUND", a.get("status"), "label M-001 has a visit on 2021-01-04");
        assertEquals(3, a.get("bound_study_event_id"));
        assertEquals("UNBOUND", item(sha, "b").get("status"), "no visit on that date: parked on its own");
        assertEquals("UNBOUND", item(sha, "c").get("status"), "no date: parked on its own");
        assertFalse(json(r).path("parked").asBoolean(), "not everything is parked");
    }

    @Test
    void anUnknownLabelParksTheWholeFile() throws Exception {
        MvcResult r = publicMvc().perform(multipart(BASE + "/commit").file(file(5))
                .param("patientId", "NO-SUCH-SUBJECT").param("split", "true"))
                .andReturn();
        assertEquals(201, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        assertTrue(json(r).path("parked").asBoolean());
        assertEquals(6, count("sha256 = '" + sha256(e2e(5)) + "' AND status = 'UNBOUND'"));
    }

    @Test
    void theSameFileTwiceAnswers409WithTheExistingItemsAndKeepsOnlyOneStoredFile() throws Exception {
        MvcResult first = splitCommit(6, "park", "true");
        assertEquals(201, first.getResponse().getStatus());
        List<Long> ids = new ArrayList<>();
        for (JsonNode n : json(first).path("items")) ids.add(n.path("ingestItemId").asLong());

        MvcResult second = splitCommit(6, "park", "true");
        assertEquals(409, second.getResponse().getStatus());
        JsonNode dup = json(second);
        assertTrue(dup.path("duplicate").asBoolean());
        List<Long> existing = new ArrayList<>();
        for (JsonNode n : dup.path("existingIngestItemIds")) existing.add(n.asLong());
        assertEquals(ids, existing);
        assertEquals(1, filesIn(E2E_ROOT), "the second copy is not kept");
        assertEquals(1, DESCRIBE_CALLS.size(), "and was not described again");
        assertEquals(6, count("sha256 = '" + sha256(e2e(6)) + "'"));
    }

    @Test
    void preflightSaysWhetherASplitFileIsAlreadyThere() throws Exception {
        String sha = sha256(e2e(7));
        publicMvc().perform(get(BASE + "/preflight").param("sha256", sha).param("split", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exists").value(false))
                .andExpect(jsonPath("$.existingIngestItemIds").isEmpty());
        assertEquals(201, splitCommit(7, "park", "true").getResponse().getStatus());
        publicMvc().perform(get(BASE + "/preflight").param("sha256", sha).param("split", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exists").value(true))
                .andExpect(jsonPath("$.existingIngestItemIds.length()").value(6));
        publicMvc().perform(get(BASE + "/preflight").param("sha256", "zz").param("split", "true"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aSplitRequestIsChecked() throws Exception {
        // scanDate / laterality belong to the file in split mode
        publicMvc().perform(multipart(BASE + "/commit").file(file(8)).param("split", "true")
                .param("patientId", "M-001").param("park", "true").param("laterality", "OD"))
                .andExpect(status().isBadRequest());
        // a visit that does not exist
        publicMvc().perform(multipart(BASE + "/commit").file(file(8)).param("split", "true")
                .param("patientId", "M-001").param("studyEventId", "999999"))
                .andExpect(status().isNotFound());
        // park and a visit together
        publicMvc().perform(multipart(BASE + "/commit").file(file(8)).param("split", "true")
                .param("patientId", "M-001").param("studyEventId", "3").param("park", "true"))
                .andExpect(status().isBadRequest());
        assertEquals(0, filesIn(E2E_ROOT));
        assertEquals(0, count("source_kind = 'portal-oct'"));
        assertTrue(DESCRIBE_CALLS.isEmpty());
    }

    @Test
    void aSidecarThatFailsLeavesNothingBehindAndAnswersWithAClearStatus() throws Exception {
        describeStatus = 500;
        MvcResult r = splitCommit(9, "park", "true");
        assertEquals(502, r.getResponse().getStatus());
        assertTrue(json(r).path("message").asText().contains("image converter"));
        assertEquals(0, filesIn(E2E_ROOT));
        assertEquals(0, count("source_kind = 'portal-oct'"));

        // An answer the app cannot use is a 502 too (a duplicate key).
        describeStatus = 200;
        describeAnswer = answer("matches", List.of(
                series("x", "IR", "IR", "OD", null, 1, null), series("x", "IR", "IR", "OS", null, 1, null)));
        assertEquals(502, splitCommit(9, "park", "true").getResponse().getStatus());
        assertEquals(0, filesIn(E2E_ROOT));
    }

    @Test
    void aSidecarThatCannotBeReachedIs503() throws Exception {
        java.lang.reflect.Field f = CoreResources.class.getDeclaredField("DATAINFO");
        f.setAccessible(true);
        java.util.Properties live = (java.util.Properties) f.get(null);
        String saved = live.getProperty("core.retinalInference.preprocessUrl");
        try {
            live.setProperty("core.retinalInference.preprocessUrl", "http://127.0.0.1:1");
            MvcResult r = splitCommit(10, "park", "true");
            assertEquals(503, r.getResponse().getStatus());
            assertEquals(0, filesIn(E2E_ROOT));
            live.setProperty("core.retinalInference.preprocessUrl", "");
            assertEquals(503, splitCommit(10, "park", "true").getResponse().getStatus());
        } finally {
            live.setProperty("core.retinalInference.preprocessUrl", saved);
        }
    }

    @Test
    void binding_a_parked_series_from_the_inbox_resolves_its_modality_by_token_and_unbinding_clears_it()
            throws Exception {
        int[] mod = catalogueForVisit3("fluid");
        assertEquals(201, splitCommit(11, "park", "true").getResponse().getStatus());
        String sha = sha256(e2e(11));
        long ir = ((Number) item(sha, "s2").get("ingest_item_id")).longValue();
        long fa = ((Number) item(sha, "s4").get("ingest_item_id")).longValue();
        assertNull(item(sha, "s2").get("imaging_modality_id"), "parked: none");

        IngestBindService binds = new IngestBindService(DATA_SOURCE);
        IngestBindService.Actor actor = IngestBindService.Actor.system();
        assertEquals(IngestBindService.Result.OK, binds.bind(ir, 1, 3, null, IngestBindService.POLICY_MANUAL, actor));
        assertEquals(IngestBindService.Result.OK, binds.bind(fa, 1, 3, null, IngestBindService.POLICY_MANUAL, actor));
        assertEquals(mod[1], item(sha, "s2").get("imaging_modality_id"));
        assertNull(item(sha, "s4").get("imaging_modality_id"), "no entry takes FA: stays without one");

        assertEquals(IngestBindService.Result.OK, binds.unbind(ir, actor));
        assertNull(item(sha, "s2").get("imaging_modality_id"), "the modality came from the binding and goes with it");
    }

    @Test
    void theInboxListsTheSeriesFieldsAndProxiesThePreview() throws Exception {
        assertEquals(201, splitCommit(12, "park", "true").getResponse().getStatus());
        String sha = sha256(e2e(12));
        long fa = ((Number) item(sha, "s4").get("ingest_item_id")).longValue();
        String storedName = Path.of((String) item(sha, "s4").get("stored_path")).getFileName().toString();

        MockMvc inbox = inboxMvc();
        inbox.perform(get("/api/v1/ingest/inbox").param("kind", "e2e").session(dm()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.id == " + fa + ")].seriesModality").value("FA"))
                .andExpect(jsonPath("$.items[?(@.id == " + fa + ")].seriesFrames").value(12))
                .andExpect(jsonPath("$.items[?(@.id == " + fa + ")].seriesKey").value("s4"))
                .andExpect(jsonPath("$.items[?(@.id == " + fa + ")].hasPreview").value(true))
                .andExpect(jsonPath("$.items[?(@.id == " + fa + ")].analysable").value(false));
        inbox.perform(get("/api/v1/ingest/" + fa).session(dm()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seriesModality").value("FA"))
                .andExpect(jsonPath("$.previewUrl").value("/pages/api/v1/ingest/" + fa + "/series-image"));

        // The preview is the sidecar's PNG, asked for by stored name, series key and frame.
        MvcResult png = inbox.perform(get("/api/v1/ingest/" + fa + "/series-image").param("frame", "3")
                .session(dm())).andExpect(status().isOk()).andReturn();
        assertArrayEquals(PNG, png.getResponse().getContentAsByteArray());
        assertEquals("image/png", png.getResponse().getContentType());
        assertEquals(1, IMAGE_CALLS.size());
        assertEquals(storedName, IMAGE_CALLS.get(0).get("storedName"));
        assertEquals("s4", IMAGE_CALLS.get(0).get("seriesKey"));
        assertEquals("3", IMAGE_CALLS.get(0).get("frame"));
        assertEquals(TOKEN, IMAGE_CALLS.get(0).get("token"));

        // Same visibility as the other previews: no session is no access; a negative frame is refused.
        inbox.perform(get("/api/v1/ingest/" + fa + "/series-image").session(new MockHttpSession()))
                .andExpect(status().is4xxClientError());
        inbox.perform(get("/api/v1/ingest/" + fa + "/series-image").param("frame", "-1").session(dm()))
                .andExpect(status().isBadRequest());
        // A row that is not a series has none.
        long plain = ((Number) item(sha, "s0").get("ingest_item_id")).longValue();
        exec("UPDATE ingest_item SET e2e_series_key = NULL, series_modality = NULL WHERE ingest_item_id = " + plain);
        inbox.perform(get("/api/v1/ingest/" + plain + "/series-image").session(dm()))
                .andExpect(status().isNotFound());
    }

    @Test
    void anUnknownSeriesOrFrameIs404() throws Exception {
        assertEquals(201, splitCommit(13, "park", "true").getResponse().getStatus());
        String sha = sha256(e2e(13));
        long it = ((Number) item(sha, "s4").get("ingest_item_id")).longValue();
        exec("UPDATE ingest_item SET e2e_series_key = 'nope' WHERE ingest_item_id = " + it);
        inboxMvc().perform(get("/api/v1/ingest/" + it + "/series-image").session(dm()))
                .andExpect(status().isNotFound());
    }

    @Test
    void takingBackOneParkedSeriesKeepsTheSharedFileUntilTheLastOneIsGone() throws Exception {
        MvcResult r = splitCommit(14, "park", "true");
        assertEquals(201, r.getResponse().getStatus());
        List<Long> ids = new ArrayList<>();
        for (JsonNode n : json(r).path("items")) ids.add(n.path("ingestItemId").asLong());
        MockMvc mvc = publicMvc();
        for (int i = 0; i < ids.size() - 1; i++) {
            mvc.perform(delete(BASE + "/items/" + ids.get(i))).andExpect(status().isNoContent());
            assertEquals(1, filesIn(E2E_ROOT), "other series still need the file");
        }
        mvc.perform(delete(BASE + "/items/" + ids.get(ids.size() - 1))).andExpect(status().isNoContent());
        assertEquals(0, filesIn(E2E_ROOT), "the last item takes the file with it");
    }

    @Test
    void retentionDeletesASharedStoredFileOnlyWhenNoItemNamesItAnyMore() throws Exception {
        Path store = STORE_ROOT.resolve("retention");
        Files.createDirectories(store);
        Path shared = store.resolve("11111111-2222-3333-4444-555555555555.e2e");
        Files.writeString(shared, "e2e bytes");
        long a = seedDismissed(shared, "k1", 45);
        long b = seedDismissed(shared, "k2", 45);
        ImageIngestRetentionService svc = new ImageIngestRetentionService(DATA_SOURCE, 30, store);

        // Only one of the two has expired: the file stays.
        exec("UPDATE ingest_item SET bound_at = now() WHERE ingest_item_id = " + b);
        assertEquals(1, svc.garbageCollect());
        assertEquals(0, count("ingest_item_id = " + a));
        assertTrue(Files.exists(shared), "item " + b + " still names the file");

        exec("UPDATE ingest_item SET bound_at = now() - interval '45 days' WHERE ingest_item_id = " + b);
        assertEquals(1, svc.garbageCollect());
        assertFalse(Files.exists(shared), "the last reference is gone");
    }

    private long seedDismissed(Path stored, String key, int daysAgo) throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO ingest_item (kind, source_kind, content_type, stored_path, original_filename, "
                             + "received_at, status, bound_at, sha256, e2e_series_key, series_modality) "
                             + "VALUES ('e2e', 'portal-oct', 'application/octet-stream', ?, 'EIAMD01D.E2E', "
                             + "now() - (? * INTERVAL '1 day'), 'DISMISSED', now() - (? * INTERVAL '1 day'), "
                             + "?, ?, 'IR') RETURNING ingest_item_id")) {
            ps.setString(1, stored.toString());
            ps.setInt(2, daysAgo);
            ps.setInt(3, daysAgo);
            ps.setString(4, "f".repeat(63) + "1");
            ps.setString(5, key);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    @Test
    void theStaffRouteSplitsToo_attributesEveryItemToTheUploader_andRefusesSplitOfOtherKinds() throws Exception {
        MvcResult r = staffMvc().perform(multipart(STAFF + "/commit").file(file(15))
                .param("patientId", "M-001").param("split", "true").param("park", "true").session(dm()))
                .andReturn();
        assertEquals(201, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        assertEquals(6, json(r).path("items").size());
        assertEquals(6, count("sha256 = '" + sha256(e2e(15)) + "' AND origin_study_id = 1"));

        staffMvc().perform(multipart(STAFF + "/commit").file(file(15)).param("patientId", "M-001")
                .param("split", "true").param("park", "true").session(dm()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.duplicate").value(true))
                .andExpect(jsonPath("$.existingIngestItemIds.length()").value(6));
        staffMvc().perform(get(STAFF + "/preflight").param("sha256", sha256(e2e(15))).param("split", "true")
                .session(dm()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exists").value(true));

        // A PNG with split=true is refused.
        staffMvc().perform(multipart(STAFF + "/commit")
                .file(new MockMultipartFile("file", "a.png", "image/png", PNG))
                .param("patientId", "M-001").param("split", "true").session(dm()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void theUnsplitPathIsUnchanged() throws Exception {
        MvcResult r = publicMvc().perform(multipart(BASE + "/commit").file(file(16))
                .param("patientId", "M-001").param("scanDate", "2021-01-04").param("laterality", "OD")
                .param("park", "true")).andReturn();
        assertEquals(201, r.getResponse().getStatus());
        assertEquals(1, count("sha256 = '" + sha256(e2e(16)) + "' AND e2e_series_key IS NULL AND scan_index = 0"));
        assertTrue(DESCRIBE_CALLS.isEmpty(), "the per-volume route never asks the sidecar to describe");
        // A second volume of the same file is still its own row (the old (sha256, scan_index) rule).
        assertEquals(201, publicMvc().perform(multipart(BASE + "/commit").file(file(16))
                .param("patientId", "M-001").param("scanDate", "2021-01-04").param("laterality", "OS")
                .param("scanIndex", "1").param("park", "true")).andReturn().getResponse().getStatus());
        assertEquals(409, publicMvc().perform(multipart(BASE + "/commit").file(file(16))
                .param("patientId", "M-001").param("scanDate", "2021-01-04").param("laterality", "OS")
                .param("scanIndex", "1").param("park", "true")).andReturn().getResponse().getStatus());
    }

    @Test
    void theDatabaseRefusesTwoRowsOfOneSeriesButAllowsSeveralNonOctSeriesOfOneFile() throws Exception {
        assertEquals(201, splitCommit(17, "park", "true").getResponse().getStatus());
        String sha = sha256(e2e(17));
        // Several rows of one file with scan_index NULL exist already (IR, IR, FA); a repeat key does not.
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO ingest_item (kind, source_kind, stored_path, received_at, status, sha256, "
                             + "e2e_series_key) VALUES ('e2e', 'portal-oct', '/x', now(), 'UNBOUND', ?, 's2')")) {
            ps.setString(1, sha);
            try {
                ps.executeUpdate();
                throw new AssertionError("expected a unique violation");
            } catch (java.sql.SQLException e) {
                assertEquals("23505", e.getSQLState());
            }
        }
    }

    @Test
    void validateAndDateOfAreStrict() {
        assertNull(E2eSplitUpload.validate(new E2eSidecarClient.Description("matches", null, List.of())));
        assertEquals("headerPatient", E2eSplitUpload.validate(new E2eSidecarClient.Description("maybe", null, List.of())));
        assertEquals("seriesKey", E2eSplitUpload.validate(new E2eSidecarClient.Description("empty", null, List.of(
                new E2eSidecarClient.Series("../x", "IR", null, null, null, null, 1, null, null, null)))));
        assertEquals("seriesModality", E2eSplitUpload.validate(new E2eSidecarClient.Description("empty", null, List.of(
                new E2eSidecarClient.Series("k", "XX", null, null, null, null, 1, null, null, null)))));
        assertEquals(java.time.LocalDate.of(2026, 9, 30), E2eSplitUpload.dateOf("2026-09-30T10:12:03"));
        assertNull(E2eSplitUpload.dateOf("garbage"));
        assertNull(E2eSplitUpload.dateOf(null));
        // the header label must be a plain token
        assertNull(E2eSplitUpload.validate(new E2eSidecarClient.Description("none", "EIAMD005", List.of())));
        assertEquals("headerLabel", E2eSplitUpload.validate(new E2eSidecarClient.Description("none", "has space", List.of())));
        assertEquals("headerLabel", E2eSplitUpload.validate(new E2eSidecarClient.Description("none", "", List.of())));
        assertEquals("headerLabel", E2eSplitUpload.validate(
                new E2eSidecarClient.Description("none", "A".repeat(25), List.of())));
        assertEquals("headerLabel", E2eSplitUpload.validate(
                new E2eSidecarClient.Description("none", "Mueller\u00e4", List.of())));
    }

    /* ---------------- the subject is the header's surname label (maintainer rule 2026-10-13) ---------------- */

    private static final String UNMATCHED = "CLINIC-4711";

    private static Map<String, Object> answerWithLabel(String header, String label, List<Map<String, Object>> series) {
        Map<String, Object> m = answer(header, series);
        m.put("headerLabel", label);
        return m;
    }

    /** A split commit with no patientId: a file from a shared watch folder. */
    private MvcResult flatCommit(int salt, String... extraParams) throws Exception {
        var req = multipart(BASE + "/commit").file(file(salt)).param("device", "spectralis").param("split", "true");
        for (int i = 0; i < extraParams.length; i += 2) req = req.param(extraParams[i], extraParams[i + 1]);
        return publicMvc().perform(req).andReturn();
    }

    /** How many rows of how many tables mention the needle, anywhere in any column. */
    private static Map<String, Integer> rowsMentioning(String needle) throws Exception {
        Map<String, Integer> hits = new LinkedHashMap<>();
        List<String> tables = new ArrayList<>();
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT table_name FROM information_schema.tables "
                     + "WHERE table_schema = current_schema() AND table_type = 'BASE TABLE'");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) tables.add(rs.getString(1));
        }
        try (Connection c = DATA_SOURCE.getConnection()) {
            for (String t : tables) {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT count(*) FROM \"" + t.replace("\"", "\"\"") + "\" x WHERE x::text LIKE ?")) {
                    ps.setString(1, "%" + needle + "%");
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        if (rs.getInt(1) > 0) hits.put(t, rs.getInt(1));
                    }
                }
            }
        }
        return hits;
    }

    @Test
    void aFileInAFlatFolderBindsBySpeakingItsOwnHeaderLabel() throws Exception {
        describeAnswer = answerWithLabel("none", "M-001", sixSeries("2021-01-04"));
        MvcResult r = flatCommit(20);
        assertEquals(201, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        JsonNode body = json(r);
        assertEquals("none", body.path("headerPatient").asText());
        assertEquals("M-001", body.path("subjectLabel").asText());
        assertFalse(body.path("parked").asBoolean());
        assertEquals("", DESCRIBE_CALLS.get(0).get("expectedLabel"), "no folder: no expected label sent");
        String sha = sha256(e2e(20));
        Map<String, Object> s0 = item(sha, "s0");
        assertEquals("BOUND", s0.get("status"));
        assertEquals(3, s0.get("bound_study_event_id"));
        assertEquals("M-001", s0.get("patient_id"));
    }

    @Test
    void aFolderThatAgreesWithTheHeaderLabelBinds() throws Exception {
        describeAnswer = answerWithLabel("matches", "M-001", sixSeries("2021-01-04"));
        MvcResult r = splitCommit(21);
        assertEquals(201, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        JsonNode body = json(r);
        assertEquals("matches", body.path("headerPatient").asText());
        assertEquals("M-001", body.path("subjectLabel").asText());
        assertEquals("M-001", DESCRIBE_CALLS.get(0).get("expectedLabel"));
        assertEquals("BOUND", item(sha256(e2e(21)), "s0").get("status"));
    }

    @Test
    void aFolderThatDiffersFromTheHeaderLabelParksEverythingAndNamesNoSubject() throws Exception {
        describeAnswer = answerWithLabel("differs", UNMATCHED, sixSeries("2021-01-04"));
        MvcResult r = splitCommit(22);
        assertEquals(201, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        JsonNode body = json(r);
        assertEquals("differs", body.path("headerPatient").asText());
        assertTrue(body.path("subjectLabel").isNull());
        assertTrue(body.path("parked").asBoolean());
        String sha = sha256(e2e(22));
        assertEquals(6, count("sha256 = '" + sha + "' AND status = 'UNBOUND' AND bound_study_event_id IS NULL "
                + "AND candidate_study_subject_id IS NULL"));
        assertFalse(r.getResponse().getContentAsString().contains(UNMATCHED));
        assertEquals(Map.of(), rowsMentioning(UNMATCHED), "the file's label is stored nowhere");
    }

    @Test
    void theServerChecksTheFolderAgainstTheLabelItselfWhateverTheSidecarSays() throws Exception {
        // A sidecar that claims "matches" for a different label does not get the file bound.
        describeAnswer = answerWithLabel("matches", "M-002", sixSeries("2021-01-04"));
        MvcResult r = splitCommit(23);
        assertEquals(201, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        assertEquals("differs", json(r).path("headerPatient").asText());
        assertTrue(json(r).path("parked").asBoolean());
    }

    @Test
    void aFileWithNoLabelAnywhereIsParkedWithNothingStored() throws Exception {
        describeAnswer = answerWithLabel("none", null, sixSeries("2021-01-04"));
        MvcResult r = flatCommit(24);
        assertEquals(201, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        JsonNode body = json(r);
        assertTrue(body.path("parked").asBoolean());
        assertTrue(body.path("subjectLabel").isNull());
        String sha = sha256(e2e(24));
        assertEquals(6, count("sha256 = '" + sha + "' AND status = 'UNBOUND' AND patient_id IS NULL "
                + "AND candidate_study_subject_id IS NULL"));
    }

    @Test
    void aFolderLabelIsTheSubjectWhenTheFileCarriesNone() throws Exception {
        describeAnswer = answerWithLabel("empty", null, sixSeries("2021-01-04"));
        MvcResult r = splitCommit(25);
        assertEquals(201, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        assertEquals("M-001", json(r).path("subjectLabel").asText());
        assertEquals("BOUND", item(sha256(e2e(25)), "s0").get("status"));
    }

    @Test
    void anUnmatchedHeaderLabelParksTheFileAndIsStoredNowhere() throws Exception {
        ch.qos.logback.classic.Logger root =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logs =
                new ch.qos.logback.core.read.ListAppender<>();
        logs.start();
        root.addAppender(logs);
        try {
            describeAnswer = answerWithLabel("none", UNMATCHED, sixSeries("2021-01-04"));
            MvcResult r = flatCommit(26);
            assertEquals(201, r.getResponse().getStatus(), r.getResponse().getContentAsString());
            JsonNode body = json(r);
            assertTrue(body.path("parked").asBoolean());
            assertTrue(body.path("subjectLabel").isNull());
            assertFalse(r.getResponse().getContentAsString().contains(UNMATCHED));
            String sha = sha256(e2e(26));
            assertEquals(6, count("sha256 = '" + sha + "' AND status = 'UNBOUND' AND patient_id IS NULL "
                    + "AND candidate_study_subject_id IS NULL"));
            // ingest_item, audit_log_event and every other table: the value is nowhere.
            assertEquals(Map.of(), rowsMentioning(UNMATCHED));
            for (var e : logs.list) {
                assertFalse(e.getFormattedMessage().contains(UNMATCHED), "logged: " + e.getFormattedMessage());
            }
        } finally {
            root.detachAppender(logs);
        }
    }

    @Test
    void aHeaderLabelThatIsNotAPlainTokenIsRefusedAsAnUnusableAnswer() throws Exception {
        describeAnswer = answerWithLabel("none", "Mustermann Max", sixSeries("2021-01-04"));
        assertEquals(502, flatCommit(27).getResponse().getStatus());
        assertEquals(0, filesIn(E2E_ROOT));
        assertEquals(0, count("source_kind = 'portal-oct'"));
    }
}
