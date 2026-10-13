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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
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
import org.mockito.ArgumentCaptor;
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
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.E2eSourceRetentionService;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.ImageIngestRetentionService;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.IngestArtifactStore;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.E2eSidecarClient;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RemoteRetinalInferenceClient;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.StudySubjectFinder;

/**
 * DR-041 (amendment b) - one upload of a multimodal Spectralis .e2e, converted on the server into
 * one DICOM per series. Both sidecars are stubs here (the real ones are tested in Python):
 * <ul>
 *   <li>the converter's {@code /e2e/describe} lists the series, {@code /e2e/convert} writes one
 *       small DICOM-shaped file per requested key into the convert directory;</li>
 *   <li>the DICOM sidecar's {@code /describe} reads such a file, "pseudonymises" it in place (the
 *       label in the file becomes the pseudonym the app sent, or nothing) and answers the way the
 *       real one does.</li>
 * </ul>
 * What matters is what the app asks of them, what it makes of the answers, and what it leaves on
 * disk and in the database.
 *
 * <p>The stub's JSON is built with Jackson, never by string concatenation, so a Windows path in a
 * test cannot produce invalid escapes.
 */
@SuppressWarnings({"null", "resource"})
class E2eSplitUploadDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static final String BASE = "/api/v1/public/upload";
    private static final String STAFF = "/api/v1/ingest/upload";
    private static final String TOKEN = "e2e-it-token";
    private static final String DICOM_TOKEN = "dicom-it-token";
    private static final String MODALITY_PREFIX = "E2E_SPLIT_IT_";
    private static final String OCT_SOP = "1.2.840.10008.5.1.4.1.1.77.1.5.4";
    private static final String OP_SOP = "1.2.840.10008.5.1.4.1.1.77.1.5.1";

    @TempDir
    static Path STORE_ROOT;

    @TempDir
    static Path E2E_ROOT;

    @TempDir
    static Path CONVERT_ROOT;

    @TempDir
    static Path SOURCE_ROOT;

    private static java.util.Properties SAVED_DATAINFO;
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final byte[] PNG = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk"
                    + "+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==");

    /* ---- the stub sidecars ---- */
    private static HttpServer STUB;
    private static volatile int describeStatus = 200;
    private static volatile int convertStatus = 200;
    private static volatile int dicomStatus = 200;
    private static volatile boolean samePixels;
    private static volatile Object describeAnswer;
    private static final List<Map<String, String>> DESCRIBE_CALLS = new CopyOnWriteArrayList<>();
    private static final List<Map<String, String>> CONVERT_CALLS = new CopyOnWriteArrayList<>();
    private static final List<Map<String, String>> DICOM_CALLS = new CopyOnWriteArrayList<>();

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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> describedSeries(String key) {
        for (Map<String, Object> s : (List<Map<String, Object>>) ((Map<String, Object>) describeAnswer).get("series")) {
            if (key.equals(s.get("seriesKey"))) return s;
        }
        return null;
    }

    private static void reply(com.sun.net.httpserver.HttpExchange ex, int status, Object body) throws java.io.IOException {
        byte[] out = JSON.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, out.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(out);
        }
    }

    /** What the stub writes for a converted series: a DICOM-shaped file the DICOM stub can read back. */
    private static byte[] dicomBytes(String key, String kind, int frames, String lat, String label, String salt) {
        String px = "px-" + key + "-" + (samePixels ? "same" : salt);
        String text = "key=" + key + ";kind=" + kind + ";frames=" + frames + ";lat=" + (lat == null ? "" : lat)
                + ";px=" + px + ";label=" + (label == null ? "" : label);
        byte[] t = text.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[132 + t.length];
        System.arraycopy("DICM".getBytes(StandardCharsets.US_ASCII), 0, out, 128, 4);
        System.arraycopy(t, 0, out, 132, t.length);
        return out;
    }

    private static Map<String, String> parseDicom(byte[] data) {
        Map<String, String> m = new HashMap<>();
        for (String kv : new String(data, 132, data.length - 132, StandardCharsets.UTF_8).split(";")) {
            String[] p = kv.split("=", 2);
            m.put(p[0], p.length > 1 ? p[1] : "");
        }
        return m;
    }

    /**
     * The DICOM sidecar's {@code /describe}: reads a converted file, "pseudonymises" it in place (the
     * label in the file becomes the pseudonym the app sent, or nothing) and describes it as the real one does.
     */
    private static com.sun.net.httpserver.HttpHandler dicomHandler() {
        return ex -> {
            JsonNode body = JSON.readTree(ex.getRequestBody());
            Path p = Path.of(body.path("path").asText());
            Map<String, String> call = new LinkedHashMap<>();
            call.put("file", p.getFileName().toString());
            call.put("pseudonym", body.hasNonNull("pseudonym") ? body.path("pseudonym").asText() : "<none>");
            call.put("token", String.valueOf(ex.getRequestHeaders().getFirst("X-MUW-Dicom-Token")));
            DICOM_CALLS.add(call);
            if (!DICOM_TOKEN.equals(call.get("token"))) {
                reply(ex, 401, Map.of());
                return;
            }
            if (dicomStatus != 200) {
                reply(ex, dicomStatus, Map.of("message", "boom"));
                return;
            }
            byte[] data = Files.readAllBytes(p);
            Map<String, String> m = parseDicom(data);
            // "pseudonymise" in place: the label in the file becomes the pseudonym (or nothing)
            String pseudonym = body.hasNonNull("pseudonym") ? body.path("pseudonym").asText() : "";
            Files.write(p, new String(data, StandardCharsets.UTF_8).replaceAll("label=[^;]*$", "label=" + pseudonym)
                    .getBytes(StandardCharsets.UTF_8));
            String name = p.getFileName().toString();
            Path preview = p.resolveSibling(name.substring(0, name.lastIndexOf('.')) + ".png");
            Files.write(preview, PNG);
            String kind = m.get("kind");
            int frames = Integer.parseInt(m.get("frames"));
            boolean opt = "OCT".equals(kind) || "OCT_LINE".equals(kind);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("sopInstanceUid", "1.2.826.0.1.3680043.8.498." + Math.abs(sha256(data).hashCode()) + "." + m.get("key"));
            out.put("sopClassUid", opt ? OCT_SOP : OP_SOP);
            out.put("studyInstanceUid", "1.2.3");
            out.put("seriesInstanceUid", "1.2.3." + m.get("key"));
            out.put("modality", opt ? "OPT" : "OP");
            out.put("laterality", m.get("lat").isEmpty() ? null : m.get("lat"));
            out.put("manufacturer", "Heidelberg Retina Angiograph");
            out.put("manufacturerModelName", "SPECTRALISHX202");
            out.put("previewPngPath", preview.toString());
            out.put("identityRemoved", true);
            out.put("changedTags", 2);
            out.put("pixelSha256", sha256(m.get("px").getBytes(StandardCharsets.UTF_8)));
            out.put("numberOfFrames", frames);
            out.put("octVolume", opt && frames > 1);
            reply(ex, 200, out);
        };
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
            if (!TOKEN.equals(call.get("token"))) {
                reply(ex, 401, Map.of());
            } else if (describeStatus != 200) {
                reply(ex, describeStatus, Map.of("detail", "boom"));
            } else {
                reply(ex, 200, describeAnswer);
            }
        });
        STUB.createContext("/e2e/convert", ex -> {
            JsonNode body = JSON.readTree(ex.getRequestBody());
            Map<String, String> call = new LinkedHashMap<>();
            call.put("storedName", body.path("storedName").asText());
            call.put("label", body.has("label") ? body.path("label").asText() : "<none>");
            List<String> keys = new ArrayList<>();
            for (JsonNode k : body.path("seriesKeys")) keys.add(k.asText());
            call.put("keys", String.join(",", keys));
            call.put("token", String.valueOf(ex.getRequestHeaders().getFirst("X-MUW-Inference-Token")));
            CONVERT_CALLS.add(call);
            if (!TOKEN.equals(call.get("token"))) {
                reply(ex, 401, Map.of());
                return;
            }
            if (convertStatus != 200) {
                reply(ex, convertStatus, Map.of("detail", "boom"));
                return;
            }
            Path staged = E2E_ROOT.resolve(call.get("storedName"));
            if (!Files.isRegularFile(staged)) {
                reply(ex, 404, Map.of("detail", "E2E file not found"));
                return;
            }
            byte[] e2e = Files.readAllBytes(staged);
            String salt = (e2e[100] & 0xFF) + "-" + (e2e[101] & 0xFF);
            String name = call.get("storedName");
            Path dir = CONVERT_ROOT.resolve(name.substring(0, name.length() - ".e2e".length()));
            Files.createDirectories(dir);
            List<Map<String, Object>> files = new ArrayList<>();
            for (String key : keys) {
                Map<String, Object> s = describedSeries(key);
                if (s == null) {
                    reply(ex, 400, Map.of("detail", "Unknown seriesKey"));
                    return;
                }
                String kind = String.valueOf(s.get("seriesModality"));
                int frames = ((Number) s.get("frames")).intValue();
                String label = body.has("label") ? body.path("label").asText() : null;
                byte[] dcm = dicomBytes(key, kind, frames, (String) s.get("laterality"), label, salt);
                Files.write(dir.resolve(key + ".dcm"), dcm);
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("seriesKey", key);
                f.put("file", key + ".dcm");
                f.put("sha256", sha256(dcm));
                f.put("bytes", dcm.length);
                f.put("frames", frames);
                f.put("sopClassUid", "OCT".equals(kind) || "OCT_LINE".equals(kind) ? OCT_SOP : OP_SOP);
                if ("OCT".equals(kind)) {
                    Files.writeString(dir.resolve(key + ".geometry.json"), "{}");
                    f.put("geometryFile", key + ".geometry.json");
                }
                files.add(f);
            }
            reply(ex, 200, Map.of("files", files));
        });
        STUB.createContext("/describe", dicomHandler());
        STUB.start();

        java.lang.reflect.Field f = CoreResources.class.getDeclaredField("DATAINFO");
        f.setAccessible(true);
        java.util.Properties live = (java.util.Properties) f.get(null);
        assertNotNull(live, "DATAINFO must be set by AbstractApiControllerDatabaseIT");
        SAVED_DATAINFO = new java.util.Properties();
        SAVED_DATAINFO.putAll(live);
        String base = "http://127.0.0.1:" + STUB.getAddress().getPort();
        live.setProperty(IngestArtifactStore.CONFIG_KEY_STORE_PATH, STORE_ROOT.toString());
        live.setProperty("core.retinalInference.e2eUploadsPath", E2E_ROOT.toString());
        live.setProperty("core.retinalInference.e2eConvertPath", CONVERT_ROOT.toString());
        live.setProperty("core.retinalInference.e2eSourcePath", SOURCE_ROOT.toString());
        live.setProperty("core.retinalInference.preprocessUrl", base);
        live.setProperty("core.retinalInference.preprocessToken", TOKEN);
        live.setProperty(DicomDescribeClient.KEY_URL, base + "/describe");
        live.setProperty(DicomDescribeClient.KEY_TOKEN, DICOM_TOKEN);
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
        convertStatus = 200;
        dicomStatus = 200;
        samePixels = false;
        describeAnswer = answer("matches", sixSeries("2021-01-04"));
        DESCRIBE_CALLS.clear();
        CONVERT_CALLS.clear();
        DICOM_CALLS.clear();
    }

    @AfterEach
    void cleanRows() throws Exception {
        exec("DELETE FROM audit_log_event WHERE audit_table IN ('ingest_item', 'retinal_inference_job', 'e2e_source')");
        exec("DELETE FROM retinal_inference_job WHERE ingest_item_id IN "
                + "(SELECT ingest_item_id FROM ingest_item WHERE source_kind = 'portal-oct')");
        exec("DELETE FROM ingest_item WHERE source_kind = 'portal-oct'");
        exec("DELETE FROM study_setting WHERE setting_key LIKE 'ingest.%'");
        exec("DELETE FROM event_definition_imaging WHERE imaging_modality_id IN "
                + "(SELECT imaging_modality_id FROM imaging_modality WHERE code LIKE '" + MODALITY_PREFIX + "%')");
        exec("DELETE FROM imaging_modality WHERE code LIKE '" + MODALITY_PREFIX + "%'");
        for (Path dir : List.of(E2E_ROOT, SOURCE_ROOT, CONVERT_ROOT, STORE_ROOT.resolve("dicom"))) {
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> s = Files.walk(dir)) {
                s.sorted(java.util.Comparator.reverseOrder()).filter(p -> !p.equals(dir)).forEach(p -> p.toFile().delete());
            }
        }
    }

    private static void exec(String sql) throws Exception {
        try (Connection c = DATA_SOURCE.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }

    /* ---------------- builders ---------------- */

    private RemoteRetinalInferenceClient remote;
    private RetinalInferenceApiController inference;

    private MockMvc publicMvc() {
        return publicMvc(false);
    }

    private MockMvc publicMvc(boolean remoteConfigured) {
        StudySubjectFinder finder = new StudySubjectFinder(DATA_SOURCE);
        remote = Mockito.mock(RemoteRetinalInferenceClient.class);
        Mockito.when(remote.isConfigured()).thenReturn(remoteConfigured);
        inference = Mockito.mock(RetinalInferenceApiController.class);
        PublicOctUploadController oct = new PublicOctUploadController(DATA_SOURCE, finder, remote, inference);
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

    private static String sha256(byte[] bytes) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder();
            for (byte x : d) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
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

    /** An item row by series key, found through the digest of the .e2e it was made from. */
    private Map<String, Object> item(String sourceSha, String key) throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT ingest_item_id, status, laterality, acquisition_date::text AS acq, "
                             + "acquisition_date_source, scan_index, series_modality, series_frames, "
                             + "imaging_modality_id, bound_study_event_id, bound_study_subject_id, "
                             + "stored_path, preview_png_path, oct_volume, kind, source_kind, patient_id, sha256, "
                             + "byte_size, device, modality, sop_class_uid, e2e_source_sha256, original_filename "
                             + "FROM ingest_item WHERE e2e_source_sha256 = ? AND e2e_series_key = ?")) {
            ps.setString(1, sourceSha);
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
        if (!Files.isDirectory(dir)) return 0;
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile).count();
        }
    }

    private static Path storedDicomDir() {
        return STORE_ROOT.resolve("dicom");
    }

    private static long dcmFiles() throws Exception {
        if (!Files.isDirectory(storedDicomDir())) return 0;
        try (Stream<Path> s = Files.list(storedDicomDir())) {
            return s.filter(p -> p.toString().endsWith(".dcm")).count();
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
    void oneFileBecomesOneDicomItemPerSeriesWithItsOwnFileEyeAndDate() throws Exception {
        MvcResult r = splitCommit(1, "park", "true");
        assertEquals(201, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        JsonNode body = json(r);
        String sha = sha256(e2e(1));
        assertEquals(sha, body.path("sha256").asText());
        assertEquals("matches", body.path("headerPatient").asText());
        assertTrue(body.path("parked").asBoolean());
        assertEquals(6, body.path("items").size());
        assertEquals(0, body.path("excluded").size());
        assertEquals("e2e", body.path("kind").asText());
        assertEquals("s0", body.path("items").get(0).path("seriesKey").asText(), "describe order is kept");
        assertEquals("OCT", body.path("items").get(0).path("seriesModality").asText());
        assertEquals(97, body.path("items").get(0).path("frames").asInt());
        assertEquals("UNBOUND", body.path("items").get(0).path("status").asText());
        assertEquals(0, body.path("items").get(0).path("jobs").size());
        assertFalse(body.path("items").get(0).path("heldBack").asBoolean());

        // The app asked the sidecar to describe and then to convert, once each, with the token.
        assertEquals(1, DESCRIBE_CALLS.size());
        assertEquals("M-001", DESCRIBE_CALLS.get(0).get("expectedLabel"));
        assertTrue(DESCRIBE_CALLS.get(0).get("storedName").matches("[A-Za-z0-9-]+\\.e2e"));
        assertEquals(1, CONVERT_CALLS.size());
        assertEquals("s0,s1,s2,s3,s4,s5", CONVERT_CALLS.get(0).get("keys"));
        assertEquals(DESCRIBE_CALLS.get(0).get("storedName"), CONVERT_CALLS.get(0).get("storedName"));
        assertEquals("<none>", CONVERT_CALLS.get(0).get("label"), "a parked file is converted without a label");

        // Disk: the .e2e moved to the source area; nothing is left staged or in the convert directory.
        assertEquals(0, filesIn(E2E_ROOT), "the upload directory is empty again");
        assertEquals(1, filesIn(SOURCE_ROOT));
        try (Stream<Path> s = Files.list(SOURCE_ROOT)) {
            assertEquals(DESCRIBE_CALLS.get(0).get("storedName"), s.findFirst().get().getFileName().toString());
        }
        assertEquals(0, filesIn(CONVERT_ROOT));
        assertEquals(6, dcmFiles(), "one DICOM per series in the DICOM ingest store");

        Map<String, Object> s0 = item(sha, "s0");
        assertEquals("dicom", s0.get("kind"));
        assertEquals("portal-oct", s0.get("source_kind"));
        assertEquals("OD", s0.get("laterality"));
        assertEquals("2021-01-04", s0.get("acq"));
        assertEquals("file", s0.get("acquisition_date_source"));
        assertNull(s0.get("scan_index"), "a DICOM item has no scan index");
        assertEquals("OCT", s0.get("series_modality"));
        assertEquals(97, s0.get("series_frames"));
        assertEquals(Boolean.TRUE, s0.get("oct_volume"), "analysable through the DICOM OCT path");
        assertEquals(OCT_SOP, s0.get("sop_class_uid"));
        assertEquals("spectralis", s0.get("device"));
        assertEquals(sha, s0.get("e2e_source_sha256"));
        assertEquals("M-001", s0.get("patient_id"));
        assertEquals("OCT_OD_s0.dcm", s0.get("original_filename"), "the E2E's own name is not kept");
        assertTrue(Files.isRegularFile(Path.of((String) s0.get("stored_path"))));
        assertTrue(Files.isRegularFile(Path.of((String) s0.get("preview_png_path"))));
        assertEquals("OS", item(sha, "s1").get("laterality"));
        Map<String, Object> ir = item(sha, "s2");
        assertEquals("IR", ir.get("series_modality"));
        assertEquals(Boolean.FALSE, ir.get("oct_volume"), "not analysable");
        assertEquals(OP_SOP, ir.get("sop_class_uid"));
        assertEquals(12, item(sha, "s4").get("series_frames"));
        Map<String, Object> line = item(sha, "s5");
        assertEquals("OCT_LINE", line.get("series_modality"));
        assertEquals(Boolean.FALSE, line.get("oct_volume"), "one frame is a line scan, not a volume");
        // Every item has a file of its own, and its digest is that of the bytes the converter wrote.
        List<Object> paths = new ArrayList<>();
        for (String key : List.of("s0", "s1", "s2", "s3", "s4", "s5")) {
            Map<String, Object> it = item(sha, key);
            assertFalse(paths.contains(it.get("stored_path")), "own stored file for " + key);
            paths.add(it.get("stored_path"));
            assertEquals("dicom", it.get("kind"));
        }
        // The DICOM sidecar was told to blank the identity of a parked file.
        assertEquals(6, DICOM_CALLS.size());
        for (Map<String, String> call : DICOM_CALLS) assertEquals("<none>", call.get("pseudonym"));
    }

    @Test
    void aVisitBindsEverySeriesFilesTheModalityByTokenAndStartsJobsForOctVolumesOnly() throws Exception {
        int[] mod = catalogueForVisit3("fluid");
        MvcResult r = splitCommit(2, "studyEventId", "3");
        assertEquals(201, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        JsonNode body = json(r);
        assertFalse(body.path("parked").asBoolean());
        assertEquals("M-001", body.path("subjectLabel").asText());
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

        // The matched subject's label is the only identity: sent to the converter and to the DICOM sidecar.
        assertEquals("M-001", CONVERT_CALLS.get(0).get("label"));
        for (Map<String, String> call : DICOM_CALLS) assertEquals("M-001", call.get("pseudonym"));

        // Jobs: OCT volumes only (through the DICOM OCT path: the job names the item's own DICOM, scan 0).
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT ii.e2e_series_key, j.task, j.scan_index, j.eye_laterality, j.status, j.e2e_path, "
                             + "ii.stored_path, ii.ingest_item_id "
                             + "FROM retinal_inference_job j JOIN ingest_item ii "
                             + "  ON ii.ingest_item_id = j.ingest_item_id "
                             + " WHERE ii.e2e_source_sha256 = ? ORDER BY ii.e2e_series_key")) {
            ps.setString(1, sha);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("s0", rs.getString(1));
                assertEquals("fluid", rs.getString(2));
                assertEquals(0, rs.getInt(3));
                assertEquals("OD", rs.getString(4));
                assertEquals("queued", rs.getString(5));
                assertEquals(rs.getString(7), rs.getString(6), "the job reads the item's own DICOM");
                assertTrue(rs.getString(6).endsWith(".dcm"));
                assertTrue(rs.next());
                assertEquals("s1", rs.getString(1));
                assertEquals("OS", rs.getString(4));
                assertFalse(rs.next(), "no job for IR, FA or the line scan");
            }
        }
        // The jobs are listed in the response, under their item.
        assertEquals(1, body.path("items").get(0).path("jobs").size());
        assertEquals("fluid", body.path("items").get(0).path("jobs").get(0).path("task").asText());
        assertEquals(0, body.path("items").get(2).path("jobs").size());
    }

    @Test
    void anOctVolumeJobGoesThroughTheDicomPreprocessPathWithTheDicomItself() throws Exception {
        catalogueForVisit3("fluid");
        MockMvc mvc = publicMvc(true);
        MvcResult r = mvc.perform(multipart(BASE + "/commit").file(file(30)).param("patientId", "M-001")
                .param("split", "true").param("studyEventId", "3")).andReturn();
        assertEquals(201, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        JsonNode jobs = json(r).path("items").get(0).path("jobs");
        assertEquals(1, jobs.size());
        assertEquals("remote_pending", jobs.get(0).path("status").asText());

        // The follower preprocesses the stored DICOM (scan 0, the eye from the series) ...
        ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
        Mockito.verify(remote, Mockito.timeout(10000)).preprocessUpload(
                eq(jobs.get(0).path("jobId").asLong()), anyString(), bytes.capture(), eq("OD"), anyString(), eq(0));
        assertEquals("DICM", new String(bytes.getValue(), 128, 4, StandardCharsets.US_ASCII),
                "what /preprocess receives is a DICOM Part-10 file, not the .e2e");
        // ... and hands the job to the GPU host, naming the same file.
        String stored = (String) item(sha256(e2e(30)), "s0").get("stored_path");
        Mockito.verify(inference, Mockito.timeout(10000)).handleRemote(
                eq(jobs.get(0).path("jobId").asLong()), eq("fluid"), eq(stored), eq("OD"), anyInt(), any(), anyInt());
        // Only the OCT volumes were preprocessed: two OCT volumes, two calls.
        Mockito.verify(remote, Mockito.timeout(10000).times(2))
                .preprocessUpload(anyLong(), anyString(), any(byte[].class), anyString(), anyString(), anyInt());
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
        assertEquals(6, count("e2e_source_sha256 = '" + sha + "' AND status = 'UNBOUND' AND imaging_modality_id IS NULL "
                + "AND bound_study_event_id IS NULL"));
        assertEquals(0, count("e2e_source_sha256 = '" + sha + "' AND status <> 'UNBOUND'"));
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM retinal_inference_job j "
                     + "JOIN ingest_item ii ON ii.ingest_item_id = j.ingest_item_id WHERE ii.e2e_source_sha256 = ?")) {
            ps.setString(1, sha);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertEquals(0, rs.getInt(1), "a parked file starts nothing");
            }
        }
        assertEquals("<none>", CONVERT_CALLS.get(0).get("label"));
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
        assertEquals(6, count("e2e_source_sha256 = '" + sha256(e2e(5)) + "' AND status = 'UNBOUND'"));
        for (Map<String, String> call : DICOM_CALLS) assertEquals("<none>", call.get("pseudonym"));
    }

    @Test
    void theSameFileTwiceAnswers409WithTheExistingItemsAndKeepsNothingFromTheSecond() throws Exception {
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
        assertEquals(0, filesIn(E2E_ROOT), "the second copy is not kept");
        assertEquals(1, filesIn(SOURCE_ROOT), "one source copy");
        assertEquals(1, DESCRIBE_CALLS.size(), "and was not described again");
        assertEquals(1, CONVERT_CALLS.size(), "nor converted again");
        assertEquals(6, count("e2e_source_sha256 = '" + sha256(e2e(6)) + "'"));
        assertEquals(6, dcmFiles());
    }

    @Test
    void theSameExportIsStillRefusedAfterItsSourceFileHasBeenDeleted() throws Exception {
        MvcResult first = splitCommit(7, "park", "true");
        assertEquals(201, first.getResponse().getStatus());
        String sha = sha256(e2e(7));
        // The retention window passes: the source copy goes, the items stay.
        try (Stream<Path> s = Files.list(SOURCE_ROOT)) {
            for (Path p : s.toList()) Files.setLastModifiedTime(p, FileTime.from(Instant.now().minus(Duration.ofDays(91))));
        }
        assertEquals(1, new E2eSourceRetentionService(DATA_SOURCE, SOURCE_ROOT, 90).garbageCollect());
        assertEquals(0, filesIn(SOURCE_ROOT));
        assertEquals(6, count("e2e_source_sha256 = '" + sha + "' AND status = 'UNBOUND'"), "the items are kept");
        assertEquals(6, dcmFiles(), "and so are their DICOMs");

        assertEquals(409, splitCommit(7, "park", "true").getResponse().getStatus());
        publicMvc().perform(get(BASE + "/preflight").param("sha256", sha).param("split", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exists").value(true))
                .andExpect(jsonPath("$.existingIngestItemIds.length()").value(6));
        assertEquals(1, DESCRIBE_CALLS.size(), "the second upload was refused before it was described");
        // The retention pass left an audit row.
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT new_value FROM audit_log_event WHERE audit_log_event_type_id = 209 AND audit_table = 'e2e_source'");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next(), "a retention audit row");
            assertTrue(rs.getString(1).contains("deleted 1 E2E source copies"));
        }
    }

    @Test
    void retentionKeepsASourceCopyThatIsStillInsideTheWindow() throws Exception {
        assertEquals(201, splitCommit(8, "park", "true").getResponse().getStatus());
        assertEquals(0, new E2eSourceRetentionService(DATA_SOURCE, SOURCE_ROOT, 90).garbageCollect());
        assertEquals(1, filesIn(SOURCE_ROOT));
        assertEquals(1, new E2eSourceRetentionService(DATA_SOURCE, SOURCE_ROOT, 90)
                .garbageCollect(Instant.now().plus(Duration.ofDays(91))));
        assertEquals(0, filesIn(SOURCE_ROOT));
        assertEquals(6, count("source_kind = 'portal-oct'"));
    }

    @Test
    void preflightSaysWhetherASplitFileIsAlreadyThere() throws Exception {
        String sha = sha256(e2e(9));
        publicMvc().perform(get(BASE + "/preflight").param("sha256", sha).param("split", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exists").value(false))
                .andExpect(jsonPath("$.split").value(true))
                .andExpect(jsonPath("$.existingIngestItemIds").isEmpty());
        assertEquals(201, splitCommit(9, "park", "true").getResponse().getStatus());
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
        publicMvc().perform(multipart(BASE + "/commit").file(file(10)).param("split", "true")
                .param("patientId", "M-001").param("park", "true").param("laterality", "OD"))
                .andExpect(status().isBadRequest());
        // a visit that does not exist
        publicMvc().perform(multipart(BASE + "/commit").file(file(10)).param("split", "true")
                .param("patientId", "M-001").param("studyEventId", "999999"))
                .andExpect(status().isNotFound());
        // park and a visit together
        publicMvc().perform(multipart(BASE + "/commit").file(file(10)).param("split", "true")
                .param("patientId", "M-001").param("studyEventId", "3").param("park", "true"))
                .andExpect(status().isBadRequest());
        assertEquals(0, filesIn(E2E_ROOT));
        assertEquals(0, filesIn(SOURCE_ROOT));
        assertEquals(0, count("source_kind = 'portal-oct'"));
        assertTrue(DESCRIBE_CALLS.isEmpty());
    }

    /* ---------------- leaving series out ---------------- */

    @Test
    void excludedSeriesAreNotConvertedStoredOrItemised() throws Exception {
        MvcResult r = splitCommit(11, "park", "true", "excludeSeries", "s2, s3,s4");
        assertEquals(201, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        JsonNode body = json(r);
        assertEquals(3, body.path("items").size());
        List<String> kept = new ArrayList<>();
        for (JsonNode n : body.path("items")) kept.add(n.path("seriesKey").asText());
        assertEquals(List.of("s0", "s1", "s5"), kept);
        List<String> excluded = new ArrayList<>();
        for (JsonNode n : body.path("excluded")) excluded.add(n.asText());
        assertEquals(List.of("s2", "s3", "s4"), excluded);

        assertEquals("s0,s1,s5", CONVERT_CALLS.get(0).get("keys"), "only the kept series are converted");
        assertEquals(3, dcmFiles());
        assertEquals(3, DICOM_CALLS.size());
        String sha = sha256(e2e(11));
        assertEquals(3, count("e2e_source_sha256 = '" + sha + "'"));
        assertEquals(0, count("e2e_source_sha256 = '" + sha + "' AND e2e_series_key IN ('s2','s3','s4')"));
        assertEquals(1, filesIn(SOURCE_ROOT), "the original is kept whole");
    }

    @Test
    void anUnknownExcludeKeyIs400AndNothingIsStoredOrConverted() throws Exception {
        MvcResult r = splitCommit(12, "park", "true", "excludeSeries", "s2,s99");
        assertEquals(400, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        assertTrue(json(r).path("message").asText().contains("excludeSeries"));
        assertEquals(1, DESCRIBE_CALLS.size(), "checked against the server's own describe");
        assertTrue(CONVERT_CALLS.isEmpty(), "nothing was converted");
        assertTrue(DICOM_CALLS.isEmpty());
        assertEquals(0, filesIn(E2E_ROOT));
        assertEquals(0, filesIn(SOURCE_ROOT));
        assertEquals(0, filesIn(CONVERT_ROOT));
        assertEquals(0, dcmFiles());
        assertEquals(0, count("source_kind = 'portal-oct'"));
        // and the same upload without the bad key still works: nothing was remembered
        assertEquals(201, splitCommit(12, "park", "true", "excludeSeries", "s2").getResponse().getStatus());
    }

    @Test
    void excludingEverySeriesIs400AndNothingIsStored() throws Exception {
        MvcResult r = splitCommit(13, "park", "true", "excludeSeries", "s0,s1,s2,s3,s4,s5");
        assertEquals(400, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        assertTrue(CONVERT_CALLS.isEmpty());
        assertEquals(0, filesIn(E2E_ROOT) + filesIn(SOURCE_ROOT) + filesIn(CONVERT_ROOT) + dcmFiles());
        assertEquals(0, count("source_kind = 'portal-oct'"));
    }

    @Test
    void aMalformedExcludeKeyIs400BeforeAnythingHappens() throws Exception {
        for (String bad : List.of("s2,../x", "s 2", "s2;DROP", "x".repeat(40))) {
            MvcResult r = splitCommit(14, "park", "true", "excludeSeries", bad);
            assertEquals(400, r.getResponse().getStatus(), bad);
        }
        assertTrue(DESCRIBE_CALLS.isEmpty(), "not even described");
        assertEquals(0, filesIn(E2E_ROOT) + filesIn(SOURCE_ROOT));
    }

    @Test
    void anExcludedFileIsStillOneExportForDuplicateDetection() throws Exception {
        assertEquals(201, splitCommit(15, "park", "true", "excludeSeries", "s2").getResponse().getStatus());
        MvcResult again = splitCommit(15, "park", "true");
        assertEquals(409, again.getResponse().getStatus());
        assertEquals(5, json(again).path("existingIngestItemIds").size());
    }

    /* ---------------- failures leave nothing behind ---------------- */

    @Test
    void aSidecarThatFailsLeavesNothingBehindAndAnswersWithAClearStatus() throws Exception {
        describeStatus = 500;
        MvcResult r = splitCommit(16, "park", "true");
        assertEquals(502, r.getResponse().getStatus());
        assertTrue(json(r).path("message").asText().contains("image converter"));
        assertEquals(0, filesIn(E2E_ROOT) + filesIn(SOURCE_ROOT));
        assertEquals(0, count("source_kind = 'portal-oct'"));

        // An answer the app cannot use is a 502 too (a duplicate key).
        describeStatus = 200;
        describeAnswer = answer("matches", List.of(
                series("x", "IR", "IR", "OD", null, 1, null), series("x", "IR", "IR", "OS", null, 1, null)));
        assertEquals(502, splitCommit(16, "park", "true").getResponse().getStatus());
        assertEquals(0, filesIn(E2E_ROOT) + filesIn(SOURCE_ROOT));
    }

    @Test
    void aFailedConversionLeavesNothingBehind() throws Exception {
        convertStatus = 500;
        MvcResult r = splitCommit(17, "park", "true");
        assertEquals(502, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        assertEquals(0, filesIn(E2E_ROOT) + filesIn(SOURCE_ROOT) + filesIn(CONVERT_ROOT) + dcmFiles());
        assertEquals(0, count("source_kind = 'portal-oct'"));
    }

    @Test
    void aDicomServiceThatFailsHalfWayRemovesTheFilesAlreadyStored() throws Exception {
        // the first two describes succeed, the third fails
        dicomStatus = 200;
        describeAnswer = answer("matches", sixSeries("2021-01-04"));
        java.util.concurrent.atomic.AtomicInteger n = new java.util.concurrent.atomic.AtomicInteger();
        STUB.removeContext("/describe");
        STUB.createContext("/describe", ex -> {
            JsonNode body = JSON.readTree(ex.getRequestBody());
            if (n.incrementAndGet() >= 3) {
                reply(ex, 500, Map.of("message", "boom"));
                return;
            }
            Path p = Path.of(body.path("path").asText());
            Files.write(p.resolveSibling(p.getFileName().toString().replace(".dcm", ".png")), PNG);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("sopInstanceUid", "1.2.3." + n.get());
            out.put("sopClassUid", OP_SOP);
            out.put("modality", "OP");
            out.put("previewPngPath", p.resolveSibling(p.getFileName().toString().replace(".dcm", ".png")).toString());
            out.put("pixelSha256", sha256(("px" + n.get()).getBytes(StandardCharsets.UTF_8)));
            reply(ex, 200, out);
        });
        try {
            MvcResult r = splitCommit(18, "park", "true");
            assertEquals(502, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        } finally {
            STUB.removeContext("/describe");
            restoreDicomStub();
        }
        assertEquals(0, dcmFiles(), "the DICOMs stored before the failure are removed");
        assertEquals(0, filesIn(STORE_ROOT.resolve("dicom")), "and their previews");
        assertEquals(0, filesIn(E2E_ROOT) + filesIn(SOURCE_ROOT) + filesIn(CONVERT_ROOT));
        assertEquals(0, count("source_kind = 'portal-oct'"));
    }

    /** Put the shared DICOM stub back after a test replaced it. */
    private static void restoreDicomStub() {
        STUB.createContext("/describe", dicomHandler());
    }

    @Test
    void aSidecarThatCannotBeReachedIs503() throws Exception {
        java.lang.reflect.Field f = CoreResources.class.getDeclaredField("DATAINFO");
        f.setAccessible(true);
        java.util.Properties live = (java.util.Properties) f.get(null);
        String saved = live.getProperty("core.retinalInference.preprocessUrl");
        try {
            live.setProperty("core.retinalInference.preprocessUrl", "http://127.0.0.1:1");
            MvcResult r = splitCommit(19, "park", "true");
            assertEquals(503, r.getResponse().getStatus());
            assertEquals(0, filesIn(E2E_ROOT) + filesIn(SOURCE_ROOT));
            live.setProperty("core.retinalInference.preprocessUrl", "");
            assertEquals(503, splitCommit(19, "park", "true").getResponse().getStatus());
        } finally {
            live.setProperty("core.retinalInference.preprocessUrl", saved);
        }
    }

    @Test
    void anUnreachableDicomServiceIs503AndKeepsNothing() throws Exception {
        java.lang.reflect.Field f = CoreResources.class.getDeclaredField("DATAINFO");
        f.setAccessible(true);
        java.util.Properties live = (java.util.Properties) f.get(null);
        String saved = live.getProperty(DicomDescribeClient.KEY_URL);
        try {
            live.setProperty(DicomDescribeClient.KEY_URL, "http://127.0.0.1:1/describe");
            MvcResult r = splitCommit(21, "park", "true");
            assertEquals(503, r.getResponse().getStatus(), r.getResponse().getContentAsString());
            assertEquals(0, dcmFiles());
            assertEquals(0, filesIn(E2E_ROOT) + filesIn(SOURCE_ROOT) + filesIn(CONVERT_ROOT));
        } finally {
            live.setProperty(DicomDescribeClient.KEY_URL, saved);
        }
    }

    /* ---------------- the inbox, the bind and the undo work on ordinary DICOM items ---------------- */

    @Test
    void binding_a_parked_series_from_the_inbox_resolves_its_modality_by_token_and_unbinding_clears_it()
            throws Exception {
        int[] mod = catalogueForVisit3("fluid");
        assertEquals(201, splitCommit(22, "park", "true").getResponse().getStatus());
        String sha = sha256(e2e(22));
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
    void theInboxListsTheSeriesFieldsAndPreviewsThroughTheOrdinaryDicomPreview() throws Exception {
        assertEquals(201, splitCommit(23, "park", "true").getResponse().getStatus());
        String sha = sha256(e2e(23));
        long fa = ((Number) item(sha, "s4").get("ingest_item_id")).longValue();

        MockMvc inbox = inboxMvc();
        inbox.perform(get("/api/v1/ingest/inbox").param("kind", "dicom").session(dm()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.id == " + fa + ")].seriesModality").value("FA"))
                .andExpect(jsonPath("$.items[?(@.id == " + fa + ")].seriesFrames").value(12))
                .andExpect(jsonPath("$.items[?(@.id == " + fa + ")].seriesKey").value("s4"))
                .andExpect(jsonPath("$.items[?(@.id == " + fa + ")].hasPreview").value(true))
                .andExpect(jsonPath("$.items[?(@.id == " + fa + ")].analysable").value(false));
        inbox.perform(get("/api/v1/ingest/" + fa).session(dm()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seriesModality").value("FA"))
                .andExpect(jsonPath("$.previewUrl").value("/pages/api/v1/ingest/" + fa + "/preview"));
        MvcResult png = inbox.perform(get("/api/v1/ingest/" + fa + "/preview").session(dm()))
                .andExpect(status().isOk()).andReturn();
        assertArrayEquals(PNG, png.getResponse().getContentAsByteArray());
        // The E2E-specific preview endpoint is gone.
        inbox.perform(get("/api/v1/ingest/" + fa + "/series-image").session(dm()))
                .andExpect(status().isNotFound());
        // An OCT volume is analysable through the DICOM path.
        long oct = ((Number) item(sha, "s0").get("ingest_item_id")).longValue();
        inbox.perform(get("/api/v1/ingest/" + oct).session(dm()))
                .andExpect(jsonPath("$.analysable").value(true))
                .andExpect(jsonPath("$.octVolume").value(true));
    }

    @Test
    void takingBackOneParkedSeriesDeletesOnlyItsOwnFileAndTheSourceStaysUntilRetention() throws Exception {
        MvcResult r = splitCommit(24, "park", "true");
        assertEquals(201, r.getResponse().getStatus());
        List<Long> ids = new ArrayList<>();
        for (JsonNode n : json(r).path("items")) ids.add(n.path("ingestItemId").asLong());
        String sha = sha256(e2e(24));
        Path first = Path.of((String) item(sha, "s0").get("stored_path"));
        assertTrue(Files.exists(first));

        publicMvc().perform(delete(BASE + "/items/" + ids.get(0))).andExpect(status().isNoContent());

        assertFalse(Files.exists(first), "its own DICOM goes");
        assertEquals(5, dcmFiles(), "the others stay");
        assertEquals(1, filesIn(SOURCE_ROOT), "the source copy is the retention job's");
        assertEquals(5, count("e2e_source_sha256 = '" + sha + "'"));
    }

    @Test
    void theSamePicturesFromAnotherExportAreHeldBackAsTwinsAndParked() throws Exception {
        samePixels = true;
        assertEquals(201, splitCommit(25, "park", "true").getResponse().getStatus());
        catalogueForVisit3("fluid");
        MvcResult r = splitCommit(26, "studyEventId", "3");
        assertEquals(201, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        JsonNode body = json(r);
        assertTrue(body.path("parked").asBoolean(), "every picture was already here");
        assertTrue(body.path("items").get(0).path("heldBack").asBoolean());
        assertFalse(body.path("items").get(0).path("sameImageAs").isNull());
        assertEquals(0, body.path("items").get(0).path("jobs").size());
    }

    @Test
    void theStaffRouteSplitsToo_attributesEveryItemToTheUploader_andRefusesSplitOfOtherKinds() throws Exception {
        MvcResult r = staffMvc().perform(multipart(STAFF + "/commit").file(file(27))
                .param("patientId", "M-001").param("split", "true").param("park", "true")
                .param("excludeSeries", "s5").session(dm()))
                .andReturn();
        assertEquals(201, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        assertEquals(5, json(r).path("items").size());
        assertEquals("s5", json(r).path("excluded").get(0).asText());
        assertEquals(5, count("e2e_source_sha256 = '" + sha256(e2e(27)) + "' AND origin_study_id = 1"));

        staffMvc().perform(multipart(STAFF + "/commit").file(file(27)).param("patientId", "M-001")
                .param("split", "true").param("park", "true").session(dm()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.duplicate").value(true))
                .andExpect(jsonPath("$.existingIngestItemIds.length()").value(5));
        staffMvc().perform(get(STAFF + "/preflight").param("sha256", sha256(e2e(27))).param("split", "true")
                .session(dm()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exists").value(true));
        // unknown exclude key: the same 400
        staffMvc().perform(multipart(STAFF + "/commit").file(file(28)).param("patientId", "M-001")
                .param("split", "true").param("excludeSeries", "nope").session(dm()))
                .andExpect(status().isBadRequest());
        // no session: no access
        staffMvc().perform(multipart(STAFF + "/commit").file(file(28)).param("split", "true"))
                .andExpect(status().is4xxClientError());
        // A PNG with split=true is refused.
        staffMvc().perform(multipart(STAFF + "/commit")
                .file(new MockMultipartFile("file", "a.png", "image/png", PNG))
                .param("patientId", "M-001").param("split", "true").session(dm()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void theUnsplitPathIsUnchanged() throws Exception {
        MvcResult r = publicMvc().perform(multipart(BASE + "/commit").file(file(29))
                .param("patientId", "M-001").param("scanDate", "2021-01-04").param("laterality", "OD")
                .param("park", "true")).andReturn();
        assertEquals(201, r.getResponse().getStatus());
        assertEquals(1, count("sha256 = '" + sha256(e2e(29)) + "' AND e2e_series_key IS NULL AND scan_index = 0"));
        assertTrue(DESCRIBE_CALLS.isEmpty(), "the per-volume route never asks the sidecar to describe");
        // A second volume of the same file is still its own row (the old (sha256, scan_index) rule).
        assertEquals(201, publicMvc().perform(multipart(BASE + "/commit").file(file(29))
                .param("patientId", "M-001").param("scanDate", "2021-01-04").param("laterality", "OS")
                .param("scanIndex", "1").param("park", "true")).andReturn().getResponse().getStatus());
        assertEquals(409, publicMvc().perform(multipart(BASE + "/commit").file(file(29))
                .param("patientId", "M-001").param("scanDate", "2021-01-04").param("laterality", "OS")
                .param("scanIndex", "1").param("park", "true")).andReturn().getResponse().getStatus());
        // ... and an explicit split=false is the unsplit route too
        assertEquals(409, publicMvc().perform(multipart(BASE + "/commit").file(file(29))
                .param("patientId", "M-001").param("scanDate", "2021-01-04").param("laterality", "OS")
                .param("scanIndex", "1").param("park", "true").param("split", "false"))
                .andReturn().getResponse().getStatus());
    }

    @Test
    void retentionOfDismissedItemsStillDeletesASharedStoredFileOnlyWhenNoItemNamesItAnyMore() throws Exception {
        // rows of the previous layout (several series sharing one stored .e2e) are still handled
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
    void aDismissedSeriesItemTakesItsOwnDicomWithItWhenRetentionRemovesIt() throws Exception {
        assertEquals(201, splitCommit(31, "park", "true").getResponse().getStatus());
        String sha = sha256(e2e(31));
        long id = ((Number) item(sha, "s2").get("ingest_item_id")).longValue();
        Path dicom = Path.of((String) item(sha, "s2").get("stored_path"));
        exec("UPDATE ingest_item SET status = 'DISMISSED', bound_at = now() - interval '45 days' "
                + "WHERE ingest_item_id = " + id);

        assertEquals(1, new ImageIngestRetentionService(DATA_SOURCE, 30, STORE_ROOT).garbageCollect());

        assertFalse(Files.exists(dicom), "its own DICOM is deleted with the row");
        assertEquals(5, count("e2e_source_sha256 = '" + sha + "'"));
        assertEquals(1, filesIn(SOURCE_ROOT), "the source copy is the other job's");
    }

    @Test
    void theDatabaseRefusesTwoRowsOfOneSeriesOfOneExport() throws Exception {
        assertEquals(201, splitCommit(32, "park", "true").getResponse().getStatus());
        String sha = sha256(e2e(32));
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO ingest_item (kind, source_kind, stored_path, received_at, status, sha256, "
                             + "e2e_source_sha256, e2e_series_key) VALUES ('dicom', 'portal-oct', '/x', now(), "
                             + "'UNBOUND', ?, ?, 's2')")) {
            ps.setString(1, "e".repeat(64));
            ps.setString(2, sha);
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
                new E2eSidecarClient.Description("none", "Muellerä", List.of())));
    }

    @Test
    void parseExcludedReadsACommaList() {
        assertEquals(List.of(), List.copyOf(E2eSplitUpload.parseExcluded(null)));
        assertEquals(List.of(), List.copyOf(E2eSplitUpload.parseExcluded("  ,, ")));
        assertEquals(Arrays.asList("s1", "s2", "s9"), List.copyOf(E2eSplitUpload.parseExcluded(" s1, s2,,s9,s1 ")));
        for (String bad : List.of("a b", "s1/..", "s1;s2", "ä")) {
            try {
                E2eSplitUpload.parseExcluded(bad);
                throw new AssertionError(bad);
            } catch (SplitUploadRejection e) {
                assertEquals(400, e.status());
            }
        }
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

    /** True when any file under the directories holds the needle. */
    private static boolean anyFileMentions(String needle, Path... dirs) throws Exception {
        for (Path dir : dirs) {
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> s = Files.walk(dir)) {
                for (Path p : s.filter(Files::isRegularFile).toList()) {
                    if (new String(Files.readAllBytes(p), java.nio.charset.StandardCharsets.ISO_8859_1).contains(needle)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @Test
    void aFileInAFlatFolderBindsBySpeakingItsOwnHeaderLabel() throws Exception {
        describeAnswer = answerWithLabel("none", "M-001", sixSeries("2021-01-04"));
        MvcResult r = flatCommit(40);
        assertEquals(201, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        JsonNode body = json(r);
        assertEquals("none", body.path("headerPatient").asText());
        assertEquals("M-001", body.path("subjectLabel").asText());
        assertFalse(body.path("parked").asBoolean());
        assertEquals("", DESCRIBE_CALLS.get(0).get("expectedLabel"), "no folder: no expected label sent");
        String sha = sha256(e2e(40));
        Map<String, Object> s0 = item(sha, "s0");
        assertEquals("BOUND", s0.get("status"));
        assertEquals(3, s0.get("bound_study_event_id"));
        assertEquals("M-001", s0.get("patient_id"));
        assertEquals("M-001", CONVERT_CALLS.get(0).get("label"), "the matched subject's own label");
    }

    @Test
    void aFolderThatAgreesWithTheHeaderLabelBinds() throws Exception {
        describeAnswer = answerWithLabel("matches", "M-001", sixSeries("2021-01-04"));
        MvcResult r = splitCommit(41);
        assertEquals(201, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        JsonNode body = json(r);
        assertEquals("matches", body.path("headerPatient").asText());
        assertEquals("M-001", body.path("subjectLabel").asText());
        assertEquals("M-001", DESCRIBE_CALLS.get(0).get("expectedLabel"));
        assertEquals("BOUND", item(sha256(e2e(41)), "s0").get("status"));
    }

    @Test
    void aFolderThatDiffersFromTheHeaderLabelParksEverythingAndNamesNoSubject() throws Exception {
        describeAnswer = answerWithLabel("differs", UNMATCHED, sixSeries("2021-01-04"));
        MvcResult r = splitCommit(42);
        assertEquals(201, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        JsonNode body = json(r);
        assertEquals("differs", body.path("headerPatient").asText());
        assertTrue(body.path("subjectLabel").isNull());
        assertTrue(body.path("parked").asBoolean());
        String sha = sha256(e2e(42));
        assertEquals(6, count("e2e_source_sha256 = '" + sha + "' AND status = 'UNBOUND' AND bound_study_event_id IS NULL "
                + "AND candidate_study_subject_id IS NULL"));
        assertFalse(r.getResponse().getContentAsString().contains(UNMATCHED));
        assertEquals(Map.of(), rowsMentioning(UNMATCHED), "the file's label is stored nowhere");
        assertFalse(anyFileMentions(UNMATCHED, STORE_ROOT, SOURCE_ROOT, CONVERT_ROOT),
                "and is in no DICOM the app stored");
    }

    @Test
    void theServerChecksTheFolderAgainstTheLabelItselfWhateverTheSidecarSays() throws Exception {
        // A sidecar that claims "matches" for a different label does not get the file bound.
        describeAnswer = answerWithLabel("matches", "M-002", sixSeries("2021-01-04"));
        MvcResult r = splitCommit(43);
        assertEquals(201, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        assertEquals("differs", json(r).path("headerPatient").asText());
        assertTrue(json(r).path("parked").asBoolean());
    }

    @Test
    void aFileWithNoLabelAnywhereIsParkedWithNothingStored() throws Exception {
        describeAnswer = answerWithLabel("none", null, sixSeries("2021-01-04"));
        MvcResult r = flatCommit(44);
        assertEquals(201, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        JsonNode body = json(r);
        assertTrue(body.path("parked").asBoolean());
        assertTrue(body.path("subjectLabel").isNull());
        String sha = sha256(e2e(44));
        assertEquals(6, count("e2e_source_sha256 = '" + sha + "' AND status = 'UNBOUND' AND patient_id IS NULL "
                + "AND candidate_study_subject_id IS NULL"));
    }

    @Test
    void aFolderLabelIsTheSubjectWhenTheFileCarriesNone() throws Exception {
        describeAnswer = answerWithLabel("empty", null, sixSeries("2021-01-04"));
        MvcResult r = splitCommit(45);
        assertEquals(201, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        assertEquals("M-001", json(r).path("subjectLabel").asText());
        assertEquals("BOUND", item(sha256(e2e(45)), "s0").get("status"));
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
            MvcResult r = flatCommit(46);
            assertEquals(201, r.getResponse().getStatus(), r.getResponse().getContentAsString());
            JsonNode body = json(r);
            assertTrue(body.path("parked").asBoolean());
            assertTrue(body.path("subjectLabel").isNull());
            assertFalse(r.getResponse().getContentAsString().contains(UNMATCHED));
            String sha = sha256(e2e(46));
            assertEquals(6, count("e2e_source_sha256 = '" + sha + "' AND status = 'UNBOUND' AND patient_id IS NULL "
                    + "AND candidate_study_subject_id IS NULL"));
            // ingest_item, audit_log_event and every other table: the value is nowhere.
            assertEquals(Map.of(), rowsMentioning(UNMATCHED));
            assertFalse(anyFileMentions(UNMATCHED, STORE_ROOT, SOURCE_ROOT, CONVERT_ROOT));
            assertEquals("<none>", CONVERT_CALLS.get(0).get("label"), "the converter never received it");
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
        assertEquals(502, flatCommit(47).getResponse().getStatus());
        assertEquals(0, filesIn(E2E_ROOT) + filesIn(SOURCE_ROOT));
        assertEquals(0, count("source_kind = 'portal-oct'"));
    }
}
