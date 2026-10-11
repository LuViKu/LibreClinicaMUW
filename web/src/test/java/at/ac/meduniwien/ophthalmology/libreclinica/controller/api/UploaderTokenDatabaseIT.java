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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.servlet.http.MappingMatch;

import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletMapping;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;

import at.ac.meduniwien.ophthalmology.libreclinica.dao.core.CoreResources;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.LocationScope;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.DicomDescribeClient;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.IngestArtifactStore;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.IngestSourceService;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.IngestSourceService.IssuedToken;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.UploaderTokens;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.StudySubjectFinder;
import at.ac.meduniwien.ophthalmology.libreclinica.web.PublicOctUploadRateLimitFilter;
import at.ac.meduniwien.ophthalmology.libreclinica.web.UploaderTokenFilter;

/**
 * DR-040 phase 2, slice D1 — the uploader token on the device endpoints,
 * through the real filter, the real services and the real controllers against
 * a PostgreSQL with the production changelog.
 *
 * <p>The world: the migration's location (A, with Default Study and its seeded
 * subject M-001, whose visit 3 is on 2021-01-04) and a second one (B), where a
 * site of the same study holds the subject UTK-B-001 with a visit on
 * 2021-03-03. A token is issued with {@link IngestSourceService} for each place.
 * Requests are made the way the container delivers them (context path
 * {@code /LibreClinica}, the {@code pages} dispatcher), so the filter's guard
 * is the one production runs.
 *
 * <p>Pinned:
 * <ul>
 *   <li><b>The door:</b> with no token a request is served as before only while
 *       one location is active and the setting is not {@code denied}; with a
 *       second location, or {@code denied}, it is 401 {@code uploader token
 *       required}. An unknown, replaced, revoked or removed-location token is
 *       401 {@code uploader token rejected}.</li>
 *   <li><b>The place:</b> a token answers for its location only. A subject, visit
 *       or upload of the other place is answered exactly as one that does not
 *       exist, by every lookup, the commits, the undo and the worklist.</li>
 *   <li><b>The origin:</b> every file a tokened commit files, whatever its kind
 *       and whichever page, carries the source and its location; an untokened
 *       one on a single-location install carries the location and no source.</li>
 *   <li><b>The heartbeat:</b> a tokened heartbeat is noted on its source.</li>
 * </ul>
 */
@SuppressWarnings({"null", "resource"}) // resource: the stub's request body belongs to the HttpExchange
class UploaderTokenDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static final String CTX = "/LibreClinica";
    private static final String UPLOAD = "/api/v1/public/upload";
    private static final String OCT = "/api/v1/public/oct-upload";
    private static final String IMAGE = "/api/v1/public/image-upload";
    private static final String HEARTBEAT = "/api/v1/device/uploader/heartbeat";
    private static final String WORKLIST = "/api/v1/device/optomed/worklist.txt";
    private static final String DICOM_TOKEN = "it-token";
    private static final String LEGACY_TOKEN = "legacy-worklist-secret";

    private static final String LABEL_A = "M-001";
    private static final String DAY_A = "2021-01-04";
    private static final int EVENT_A = 3;
    private static final String LABEL_B = "UTK-B-001";
    private static final String DAY_B = "2021-03-03";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AtomicInteger SEQ = new AtomicInteger(1000);

    /** 1x1 transparent PNG. */
    private static final byte[] PNG = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk"
                    + "+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==");

    @TempDir
    static Path STORE_ROOT;

    @TempDir
    static Path E2E_ROOT;

    private static java.util.Properties SAVED_DATAINFO;
    private static HttpServer STUB;

    private static int locA;
    private static int locB;
    private static int siteB;
    private static int subjectB;
    private static int eventB;
    /** A visit of the other place's subject on the same day as M-001's: an unscoped list shows both. */
    private static int eventBOnDayA;

    private IngestSourceService sources;
    private IssuedToken tokenA;
    private IssuedToken tokenB;

    @BeforeAll
    static void seedTheWorld() throws Exception {
        // The describe sidecar, as a stub: it answers a Clarus photograph and writes the preview.
        STUB = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        STUB.createContext("/describe", ex -> {
            JsonNode body = JSON.readTree(ex.getRequestBody());
            String path = body.path("path").asText(null);
            String preview = path.replaceAll("\\.dcm$", "") + ".png";
            Files.write(Path.of(preview), PNG);
            byte[] out = ("{\"sopInstanceUid\":\"1.2.826.0.1.3680043.8.498." + System.nanoTime() + "\","
                    + "\"sopClassUid\":\"1.2.840.10008.5.1.4.1.1.77.1.5.1\",\"modality\":\"OP\","
                    + "\"manufacturer\":\"Carl Zeiss Meditec\",\"manufacturerModelName\":\"CLARUS 700\","
                    + "\"studyInstanceUid\":\"1.2.3\",\"seriesInstanceUid\":\"1.2.3.4\","
                    + "\"studyDate\":\"" + DAY_A + "\",\"acquisitionDate\":\"" + DAY_A + "\",\"laterality\":\"OD\","
                    + "\"previewPngPath\":\"" + preview + "\",\"identityRemoved\":true,\"changedTags\":4}")
                    .getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, out.length);
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
        live.setProperty(IngestArtifactStore.CONFIG_KEY_STORE_PATH, Files.createDirectories(STORE_ROOT.resolve("ingest")).toString());
        live.setProperty("core.dicom.ingest.storePath", Files.createDirectories(STORE_ROOT.resolve("images")).toString());
        live.setProperty("core.retinalInference.e2eUploadsPath", E2E_ROOT.toString());
        live.setProperty(DicomDescribeClient.KEY_URL, "http://127.0.0.1:" + STUB.getAddress().getPort() + "/describe");
        live.setProperty(DicomDescribeClient.KEY_TOKEN, DICOM_TOKEN);
        live.setProperty("core.optomed.worklist.enabled", "true");
        live.setProperty("core.optomed.worklist.token", LEGACY_TOKEN);
        live.setProperty("core.ingest.portal.todaysVisits", "true");

        locA = LifecycleFixtures.intQuery("SELECT location_id FROM location WHERE code = 'MUW-AUGEN'");
        try (Connection c = DATA_SOURCE.getConnection()) {
            locB = LifecycleFixtures.insertOne(c,
                    "INSERT INTO location (code, name) VALUES ('UTK-B', 'Uploader token second place') RETURNING location_id");
            siteB = LifecycleFixtures.insertStudy(c, 1, "UTKB", "UTK Site B", "S_UTKB", 1);
            exec(c, "UPDATE study SET location_id = " + locB + " WHERE study_id = " + siteB);
            subjectB = LifecycleFixtures.insertStudySubject(c, LABEL_B, siteB, 1);
            int definition = LifecycleFixtures.intQuery(
                    "SELECT study_event_definition_id FROM study_event WHERE study_event_id = " + EVENT_A);
            eventB = LifecycleFixtures.insertEvent(c, definition, subjectB, 1);
            exec(c, "UPDATE study_event SET date_start = '" + DAY_B + " 08:00:00', subject_event_status_id = 1 "
                    + "WHERE study_event_id = " + eventB);
            int otherDefinition = LifecycleFixtures.intQuery(
                    "SELECT study_event_definition_id FROM study_event WHERE study_event_id = 2");
            eventBOnDayA = LifecycleFixtures.insertEvent(c, otherDefinition, subjectB, 1);
            exec(c, "UPDATE study_event SET date_start = '" + DAY_A + " 09:00:00', subject_event_status_id = 1 "
                    + "WHERE study_event_id = " + eventBOnDayA);
            exec(c, "UPDATE location SET status_id = 5 WHERE location_id = " + locB);
        }
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

    /** One location (A), the door at its default, a token for each place, no files of the tests. */
    @BeforeEach
    void reset() throws Exception {
        setDoor(null);
        oneLocation();
        sources = new IngestSourceService(DATA_SOURCE);
        // A token can be issued for a place only while it is available.
        tokenA = sources.issueUploader(locA, "Clarus PC " + SEQ.incrementAndGet(), 1);
        twoLocations();
        tokenB = sources.issueUploader(locB, "Heidelberg PC " + SEQ.incrementAndGet(), 1);
        oneLocation();
    }

    @AfterEach
    void clean() throws Exception {
        try (Connection c = DATA_SOURCE.getConnection()) {
            exec(c, "DELETE FROM audit_log_event WHERE audit_table IN ('ingest_item', 'retinal_inference_job', 'ingest_source')");
            exec(c, "DELETE FROM retinal_inference_job WHERE ingest_item_id IN "
                    + "(SELECT ingest_item_id FROM ingest_item WHERE source_kind IN ('upload', 'portal-oct'))");
            exec(c, "DELETE FROM ingest_item WHERE source_kind IN ('upload', 'portal-oct')");
            exec(c, "DELETE FROM ingest_source WHERE kind = 'uploader'");
            exec(c, "DELETE FROM uploader_instance");
            exec(c, "DELETE FROM event_definition_imaging WHERE imaging_modality_id IN "
                    + "(SELECT imaging_modality_id FROM imaging_modality WHERE code = 'UTK_OCT_IT')");
            exec(c, "DELETE FROM imaging_modality WHERE code = 'UTK_OCT_IT'");
        }
    }

    /* ---------------------------------------------------------------- */
    /* the world's switches                                              */
    /* ---------------------------------------------------------------- */

    private static void exec(Connection c, String sql) throws SQLException {
        try (java.sql.Statement s = c.createStatement()) {
            s.executeUpdate(sql);
        }
    }

    private static void oneLocation() throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection()) {
            exec(c, "UPDATE location SET status_id = 5 WHERE location_id = " + locB);
        }
    }

    private static void twoLocations() throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection()) {
            exec(c, "UPDATE location SET status_id = 1 WHERE location_id = " + locB);
        }
    }

    /** The front-door setting; null removes it (the default, allowed). */
    private static void setDoor(String value) throws Exception {
        java.lang.reflect.Field f = CoreResources.class.getDeclaredField("DATAINFO");
        f.setAccessible(true);
        java.util.Properties live = (java.util.Properties) f.get(null);
        if (value == null) live.remove(UploaderTokenFilter.DOOR_KEY);
        else live.setProperty(UploaderTokenFilter.DOOR_KEY, value);
    }

    /* ---------------------------------------------------------------- */
    /* the wiring: the filters, then the controllers                     */
    /* ---------------------------------------------------------------- */

    /**
     * A MockMvc over the device controllers, behind the real token filter and
     * the rate limiter (the internal deployment's, which is off). A fresh
     * filter reads the number of locations afresh; a test that changes it asks
     * for a new MockMvc.
     */
    private MockMvc mvc() {
        StudySubjectFinder finder = new StudySubjectFinder(DATA_SOURCE);
        PublicOctUploadController oct = new PublicOctUploadController(DATA_SOURCE, finder);
        PublicImageUploadController images = new PublicImageUploadController(DATA_SOURCE, finder);
        PublicUploadController combined = new PublicUploadController(DATA_SOURCE, finder, oct, images,
                new IngestUploadService(DATA_SOURCE, new IngestArtifactStore(),
                        new DicomDescribeClient("http://127.0.0.1:" + STUB.getAddress().getPort() + "/describe",
                                DICOM_TOKEN)));
        UploaderTokenFilter tokens = new UploaderTokenFilter(new IngestSourceService(DATA_SOURCE),
                new LocationScope(DATA_SOURCE));
        return ProductionMvc.standalone(combined, oct, images, new UploaderHeartbeatApiController(DATA_SOURCE),
                        new OptomedWorklistApiController(DATA_SOURCE))
                .setControllerAdvice(new ApiExceptionHandler())
                .addFilters(tokens, new PublicOctUploadRateLimitFilter(false))
                .build();
    }

    /** A request as the container delivers it to the {@code pages} dispatcher. */
    private static <B extends AbstractMockHttpServletRequestBuilder<B>> B device(B builder) {
        return builder.contextPath(CTX).servletPath("/pages").with(r -> {
            r.setHttpServletMapping(new MockHttpServletMapping("*", "/pages/*", "pages", MappingMatch.PATH));
            return r;
        });
    }

    private static String url(String path) {
        return CTX + "/pages" + path;
    }

    private static MockMultipartFile part(String name, String type, byte[] bytes) {
        return new MockMultipartFile("file", name, type, bytes);
    }

    /** An .e2e that has never been seen before. */
    private static byte[] e2e() {
        byte[] b = new byte[1024];
        System.arraycopy("CMDb".getBytes(StandardCharsets.US_ASCII), 0, b, 0, 4);
        int salt = SEQ.incrementAndGet();
        b[10] = (byte) salt;
        b[11] = (byte) (salt >> 8);
        return b;
    }

    /** A DICOM file that has never been seen before. */
    private static byte[] dicom() {
        byte[] b = new byte[512];
        System.arraycopy("DICM".getBytes(StandardCharsets.US_ASCII), 0, b, 128, 4);
        int salt = SEQ.incrementAndGet();
        b[200] = (byte) salt;
        b[201] = (byte) (salt >> 8);
        return b;
    }

    /** A picture that has never been seen before: the platform refuses the same picture twice under one label. */
    private static byte[] png() throws java.io.IOException {
        java.util.Random random = new java.util.Random(SEQ.incrementAndGet() * 7919L);
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(8, 8,
                java.awt.image.BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) image.setRGB(x, y, random.nextInt(0x1000000));
        }
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static String bodyOf(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString();
    }

    private static String message(MvcResult r) throws Exception {
        return JSON.readTree(bodyOf(r)).path("message").asText();
    }

    /* ---------------------------------------------------------------- */
    /* what the database says                                            */
    /* ---------------------------------------------------------------- */

    private static Integer intOrNull(ResultSet rs, String column) throws SQLException {
        int v = rs.getInt(column);
        return rs.wasNull() ? null : v;
    }

    /** {@code [origin_location_id, ingest_source_id]} of an ingest item. */
    private static List<Integer> originOf(long ingestItemId) throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT origin_location_id, ingest_source_id FROM ingest_item WHERE ingest_item_id = ?")) {
            ps.setLong(1, ingestItemId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "ingest_item " + ingestItemId + " exists");
                List<Integer> out = new ArrayList<>();
                out.add(intOrNull(rs, "origin_location_id"));
                out.add(intOrNull(rs, "ingest_source_id"));
                return out;
            }
        }
    }

    private static List<Integer> expectedOrigin(Integer location, IssuedToken token) {
        List<Integer> out = new ArrayList<>();
        out.add(location);
        out.add(token == null ? null : Math.toIntExact(token.source().id()));
        return out;
    }

    private static long count(String table, String where) throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM " + table + " WHERE " + where);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static long ingestItems() throws Exception {
        return count("ingest_item", "source_kind IN ('upload', 'portal-oct')");
    }

    private static long idOf(MvcResult r) throws Exception {
        JsonNode body = JSON.readTree(bodyOf(r));
        JsonNode id = body.has("ingestItemId") ? body.get("ingestItemId") : body.get("imageIngestId");
        assertNotNull(id, "an upload id in " + bodyOf(r));
        return id.asLong();
    }

    /* ---------------------------------------------------------------- */
    /* requests                                                          */
    /* ---------------------------------------------------------------- */

    private static <B extends AbstractMockHttpServletRequestBuilder<B>> B withToken(B builder, IssuedToken token) {
        return token == null ? builder : builder.header(UploaderTokenFilter.TOKEN_HEADER, token.token());
    }

    private MvcResult resolve(MockMvc m, IssuedToken token, String label, String date) throws Exception {
        return m.perform(withToken(device(post(url(UPLOAD + "/resolve")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"scans\":[{\"patientId\":\"" + label + "\",\"scanDate\":\"" + date
                        + "\",\"laterality\":\"OD\"}]}"), token)).andReturn();
    }

    private MvcResult search(MockMvc m, IssuedToken token, String prefix) throws Exception {
        return m.perform(withToken(device(get(url(UPLOAD + "/patients/search"))).param("q", prefix), token))
                .andReturn();
    }

    private MvcResult events(MockMvc m, IssuedToken token, int studySubjectId) throws Exception {
        return m.perform(withToken(device(get(url(UPLOAD + "/patients/" + studySubjectId + "/events"))), token))
                .andReturn();
    }

    private MvcResult visits(MockMvc m, IssuedToken token, String date) throws Exception {
        return m.perform(withToken(device(get(url(UPLOAD + "/visits"))).param("date", date), token)).andReturn();
    }

    private MvcResult worklist(MockMvc m, IssuedToken token, String date, boolean legacy) throws Exception {
        var req = device(get(url(WORKLIST))).param("date", date);
        if (legacy) req.header("X-MUW-Optomed-Token", LEGACY_TOKEN);
        return m.perform(withToken(req, token)).andReturn();
    }

    private MvcResult heartbeat(MockMvc m, IssuedToken token, String instanceUid) throws Exception {
        return m.perform(withToken(device(post(url(HEARTBEAT))).contentType(MediaType.APPLICATION_JSON)
                .content("{\"instanceId\":\"" + instanceUid + "\",\"kind\":\"export-watcher\",\"name\":\"CLARUS-PC\","
                        + "\"version\":\"2026-10-12\",\"heartbeatIntervalSec\":120}"), token)).andReturn();
    }

    /** A combined-page commit of an image, optionally filed to a visit. */
    private MvcResult commitImage(MockMvc m, IssuedToken token, byte[] bytes, Integer visit, String date)
            throws Exception {
        var req = device(multipart(url(UPLOAD + "/commit"))).file(part("photo.png", "image/png", bytes))
                .param("laterality", "OD");
        if (visit != null) req.param("studyEventId", String.valueOf(visit)).param("scanDate", date);
        else req.param("patientId", LABEL_A);
        return m.perform(withToken(req, token)).andReturn();
    }

    private MvcResult commitDicom(MockMvc m, IssuedToken token, Integer visit, String date) throws Exception {
        var req = device(multipart(url(UPLOAD + "/commit"))).file(part("export.dcm", "application/dicom", dicom()));
        if (visit != null) req.param("studyEventId", String.valueOf(visit)).param("scanDate", date);
        return m.perform(withToken(req, token)).andReturn();
    }

    private MvcResult commitE2e(MockMvc m, IssuedToken token, Integer visit, String date) throws Exception {
        var req = device(multipart(url(UPLOAD + "/commit"))).file(part("scan.e2e", "application/octet-stream", e2e()))
                .param("laterality", "OD");
        if (visit != null) req.param("studyEventId", String.valueOf(visit)).param("scanDate", date);
        else req.param("patientId", LABEL_A).param("scanDate", DAY_A).param("park", "true");
        return m.perform(withToken(req, token)).andReturn();
    }

    private static void assertDoor(MvcResult r, int status, String message) throws Exception {
        MockHttpServletResponse resp = r.getResponse();
        assertEquals(status, resp.getStatus(), bodyOf(r));
        assertEquals("{\"message\":\"" + message + "\"}", bodyOf(r));
        assertEquals("no-store", resp.getHeader("Cache-Control"));
    }

    /* ---------------------------------------------------------------- */
    /* the door                                                          */
    /* ---------------------------------------------------------------- */

    @Test
    void withOneLocationAnUntokenedRequestIsServedAsBefore() throws Exception {
        MockMvc m = mvc();
        MvcResult found = resolve(m, null, LABEL_A, DAY_A);
        assertEquals(200, found.getResponse().getStatus(), bodyOf(found));
        JsonNode scan = JSON.readTree(bodyOf(found)).path("scans").get(0);
        assertEquals("suggested", scan.path("state").asText());
        assertEquals(EVENT_A, scan.path("candidates").get(0).path("matchingEvent").path("studyEventId").asInt());

        // Nothing is narrowed: the other place's subject is found too, as it always was.
        MvcResult other = resolve(m, null, LABEL_B, DAY_B);
        assertEquals("suggested", JSON.readTree(bodyOf(other)).path("scans").get(0).path("state").asText());

        MvcResult filed = commitImage(m, null, png(), null, null);
        assertEquals(201, filed.getResponse().getStatus(), bodyOf(filed));
        assertEquals(expectedOrigin(locA, null), originOf(idOf(filed)), "the only location, and no source");
    }

    @Test
    void withASecondLocationEveryUntokenedDeviceRequestIsToldToSendAToken() throws Exception {
        twoLocations();
        MockMvc m = mvc();
        List<MvcResult> refused = List.of(
                resolve(m, null, LABEL_A, DAY_A),
                search(m, null, "M-00"),
                events(m, null, 1),
                visits(m, null, DAY_A),
                commitImage(m, null, png(), null, null),
                commitDicom(m, null, null, null),
                commitE2e(m, null, null, null),
                m.perform(device(delete(url(UPLOAD + "/items/1")))).andReturn(),
                m.perform(device(get(url(UPLOAD + "/preflight"))).param("sha256", "0".repeat(64))).andReturn(),
                m.perform(device(post(url(OCT + "/resolve"))).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scans\":[]}")).andReturn(),
                m.perform(device(multipart(url(IMAGE + "/commit"))).file(part("p.png", "image/png", png()))).andReturn(),
                heartbeat(m, null, "11111111-2222-4333-8444-555555555555"),
                worklist(m, null, DAY_A, true));
        for (MvcResult r : refused) {
            assertDoor(r, 401, "uploader token required");
        }
        assertEquals(0, ingestItems(), "nothing was filed");
        assertEquals(0, count("uploader_instance", "TRUE"), "no heartbeat was recorded");
    }

    @Test
    void theSettingDeniedShutsTheDoorOnASingleLocationInstallToo() throws Exception {
        setDoor("denied");
        MockMvc m = mvc();
        assertDoor(resolve(m, null, LABEL_A, DAY_A), 401, "uploader token required");
        assertEquals(200, resolve(m, tokenA, LABEL_A, DAY_A).getResponse().getStatus(), "a token still opens it");
        setDoor("allowed");
        assertEquals(200, resolve(mvc(), null, LABEL_A, DAY_A).getResponse().getStatus());
    }

    @Test
    void aTokenOpensAShutDoorForItsOwnPlace() throws Exception {
        twoLocations();
        MockMvc m = mvc();
        assertDoor(resolve(m, null, LABEL_A, DAY_A), 401, "uploader token required");
        assertEquals(200, resolve(m, tokenA, LABEL_A, DAY_A).getResponse().getStatus());
        assertEquals(200, resolve(m, tokenB, LABEL_B, DAY_B).getResponse().getStatus());
    }

    @Test
    void anUnknownTokenIsRejectedEvenWhereAnUntokenedRequestWouldBeServed() throws Exception {
        assertDoor(resolve(mvc(), new IssuedToken(tokenA.source(), UploaderTokens.generate()), LABEL_A, DAY_A),
                401, "uploader token rejected");
    }

    @Test
    void aReplacedTokenStopsWorkingAtOnceAndItsSuccessorWorks() throws Exception {
        MockMvc m = mvc();
        assertEquals(200, resolve(m, tokenA, LABEL_A, DAY_A).getResponse().getStatus());
        IssuedToken successor = sources.replaceToken(tokenA.source().id(), 1);
        assertDoor(resolve(m, tokenA, LABEL_A, DAY_A), 401, "uploader token rejected");
        assertEquals(200, resolve(m, successor, LABEL_A, DAY_A).getResponse().getStatus());
    }

    @Test
    void aRevokedTokenIsRejectedAtOnce() throws Exception {
        MockMvc m = mvc();
        assertEquals(200, resolve(m, tokenA, LABEL_A, DAY_A).getResponse().getStatus());
        sources.revoke(tokenA.source().id(), 1);
        assertDoor(resolve(m, tokenA, LABEL_A, DAY_A), 401, "uploader token rejected");
        assertDoor(heartbeat(m, tokenA, "11111111-2222-4333-8444-555555555555"), 401, "uploader token rejected");
    }

    @Test
    void aTokenOfARemovedLocationIsRejectedUntilThePlaceIsBack() throws Exception {
        twoLocations();
        MockMvc m = mvc();
        assertEquals(200, resolve(m, tokenB, LABEL_B, DAY_B).getResponse().getStatus());
        oneLocation(); // B is removed
        assertDoor(resolve(m, tokenB, LABEL_B, DAY_B), 401, "uploader token rejected");
        twoLocations();
        assertEquals(200, resolve(m, tokenB, LABEL_B, DAY_B).getResponse().getStatus());
    }

    @Test
    void theBcvaPageIsNotBehindTheDoorAndNeitherIsAStaffRoute() throws Exception {
        twoLocations();
        MockMvc m = mvc();
        // No controller is mounted for these paths here; the point is that the filter lets them through
        // to the dispatcher (404), not that it answers 401 for them.
        assertEquals(404, m.perform(device(get(url("/api/v1/public/bcva-entry/S_X/visits")))).andReturn()
                .getResponse().getStatus());
        assertEquals(404, m.perform(device(post(url("/api/v1/ingest/upload/commit")))).andReturn()
                .getResponse().getStatus());
    }

    /* ---------------------------------------------------------------- */
    /* the place: lookups                                                */
    /* ---------------------------------------------------------------- */

    /** The one scan of a resolve answer, with the echoed label taken out so two answers can be compared. */
    private static JsonNode scanOf(MvcResult r) throws Exception {
        assertEquals(200, r.getResponse().getStatus(), bodyOf(r));
        return JSON.readTree(bodyOf(r)).path("scans").get(0);
    }

    private static String shape(JsonNode scan) {
        return scan.path("state").asText() + "|" + scan.path("candidates").size();
    }

    @Test
    void aTokenResolvesVisitsOfItsOwnLocationOnly() throws Exception {
        twoLocations();
        MockMvc m = mvc();

        JsonNode ownA = scanOf(resolve(m, tokenA, LABEL_A, DAY_A));
        assertEquals("suggested", ownA.path("state").asText());
        assertEquals(EVENT_A, ownA.path("candidates").get(0).path("matchingEvent").path("studyEventId").asInt());
        JsonNode ownB = scanOf(resolve(m, tokenB, LABEL_B, DAY_B));
        assertEquals("suggested", ownB.path("state").asText());
        assertEquals(eventB, ownB.path("candidates").get(0).path("matchingEvent").path("studyEventId").asInt());

        // The other place's subject behaves exactly like one that does not exist: no state, no candidate,
        // not even a different status, so the answer is no oracle.
        JsonNode foreign = scanOf(resolve(m, tokenA, LABEL_B, DAY_B));
        JsonNode unknown = scanOf(resolve(m, tokenA, "NOBODY-9", DAY_B));
        assertEquals("nopatient", foreign.path("state").asText());
        assertEquals(0, foreign.path("candidates").size());
        assertEquals(shape(unknown), shape(foreign));
        assertEquals(unknown.path("state").asText(), scanOf(resolve(m, tokenB, LABEL_A, DAY_A)).path("state").asText());
    }

    @Test
    void thePatientSearchListsTheSubjectsOfTheTokensLocationOnly() throws Exception {
        twoLocations();
        MockMvc m = mvc();
        JsonNode own = JSON.readTree(bodyOf(search(m, tokenA, "M-00"))).path("subjects");
        assertTrue(own.size() >= 1, "A finds its own subjects");
        for (JsonNode hit : own) {
            assertTrue(hit.path("label").asText().startsWith("M-00"), "only A's subjects: " + hit);
        }
        assertEquals(0, JSON.readTree(bodyOf(search(m, tokenA, "UTK-"))).path("subjects").size(),
                "B's subject is not listed at A");
        JsonNode forB = JSON.readTree(bodyOf(search(m, tokenB, "UTK-"))).path("subjects");
        assertEquals(1, forB.size());
        assertEquals(LABEL_B, forB.get(0).path("label").asText());
        assertEquals(0, JSON.readTree(bodyOf(search(m, tokenB, "M-00"))).path("subjects").size());
    }

    @Test
    void aSubjectsVisitsAreListedOnlyToTheLocationItsSubjectIsEnrolledAt() throws Exception {
        twoLocations();
        MockMvc m = mvc();
        int subjectA = LifecycleFixtures.intQuery("SELECT study_subject_id FROM study_subject WHERE label = 'M-001'");

        assertTrue(JSON.readTree(bodyOf(events(m, tokenA, subjectA))).size() > 0, "A sees its own subject's visits");
        assertTrue(JSON.readTree(bodyOf(events(m, tokenB, subjectB))).size() > 0, "B sees its own");

        MvcResult foreign = events(m, tokenA, subjectB);
        MvcResult unknown = events(m, tokenA, 987654);
        assertEquals(200, foreign.getResponse().getStatus());
        assertEquals(bodyOf(unknown), bodyOf(foreign), "a subject of another place has no visits, as an unknown one has none");
        assertEquals("[]", bodyOf(foreign));
        assertEquals("[]", bodyOf(events(m, tokenB, subjectA)));
    }

    @Test
    void theDaysVisitsAreThoseOfTheTokensLocationOnly() throws Exception {
        twoLocations();
        MockMvc m = mvc();
        assertTrue(bodyOf(visits(m, tokenA, DAY_A)).contains(LABEL_A));
        assertFalse(bodyOf(visits(m, tokenA, DAY_B)).contains(LABEL_B), "B's visit is not at A");
        assertTrue(bodyOf(visits(m, tokenB, DAY_B)).contains(LABEL_B));
        assertFalse(bodyOf(visits(m, tokenB, DAY_A)).contains(LABEL_A));
        // Untokened on one location: the whole list, as before.
        oneLocation();
        assertTrue(bodyOf(visits(mvc(), null, DAY_B)).contains(LABEL_B));
    }

    /* ---------------------------------------------------------------- */
    /* the place: commits and undo                                       */
    /* ---------------------------------------------------------------- */

    @Test
    void aVisitOfAnotherLocationCannotBeNamedByACommit() throws Exception {
        twoLocations();
        MockMvc m = mvc();
        long before = ingestItems();
        long filesBefore = filesUnder(STORE_ROOT) + filesUnder(E2E_ROOT);

        // Image and DICOM: the same refusal as a visit that is not scheduled for the date.
        MvcResult image = commitImage(m, tokenA, png(), eventB, DAY_B);
        MvcResult imageUnknown = commitImage(m, tokenA, png(), 998877, DAY_B);
        assertEquals(400, image.getResponse().getStatus(), bodyOf(image));
        assertEquals(bodyOf(imageUnknown), bodyOf(image));
        MvcResult dicom = commitDicom(m, tokenA, eventB, DAY_B);
        MvcResult dicomUnknown = commitDicom(m, tokenA, 998877, DAY_B);
        assertEquals(400, dicom.getResponse().getStatus(), bodyOf(dicom));
        assertEquals(bodyOf(dicomUnknown), bodyOf(dicom));
        // The older image page, too.
        MvcResult older = m.perform(withToken(device(multipart(url(IMAGE + "/commit")))
                .file(part("p.png", "image/png", png())).param("studyEventId", String.valueOf(eventB))
                .param("studyDate", DAY_B), tokenA)).andReturn();
        assertEquals(400, older.getResponse().getStatus(), bodyOf(older));
        // The OCT route: the same answer as a visit that does not exist, with the label given or without it.
        MvcResult e2e = commitE2e(m, tokenA, eventB, DAY_B);
        MvcResult e2eUnknown = commitE2e(m, tokenA, 998877, DAY_B);
        assertEquals(400, e2e.getResponse().getStatus(), bodyOf(e2e));
        assertEquals(bodyOf(e2eUnknown), bodyOf(e2e));
        MvcResult labelled = m.perform(withToken(device(multipart(url(UPLOAD + "/commit")))
                .file(part("scan.e2e", "application/octet-stream", e2e())).param("laterality", "OD")
                .param("patientId", LABEL_B).param("scanDate", DAY_B).param("studyEventId", String.valueOf(eventB)),
                tokenA)).andReturn();
        MvcResult labelledUnknown = m.perform(withToken(device(multipart(url(UPLOAD + "/commit")))
                .file(part("scan.e2e", "application/octet-stream", e2e())).param("laterality", "OD")
                .param("patientId", LABEL_B).param("scanDate", DAY_B).param("studyEventId", "998877"),
                tokenA)).andReturn();
        assertEquals(404, labelled.getResponse().getStatus(), bodyOf(labelled));
        assertEquals(message(labelledUnknown).replace("998877", "N"), message(labelled).replace(String.valueOf(eventB), "N"));

        assertEquals(before, ingestItems(), "nothing was filed for either");
        assertEquals(filesBefore, filesUnder(STORE_ROOT) + filesUnder(E2E_ROOT), "and no file was kept");

        // A's own visit works, and so does B's for B.
        assertEquals(201, commitImage(m, tokenA, png(), EVENT_A, DAY_A).getResponse().getStatus());
        assertEquals(201, commitImage(m, tokenB, png(), eventB, DAY_B).getResponse().getStatus());
    }

    @Test
    void aParkedScanKeepsTheLabelButNoSubjectOfAnotherPlace() throws Exception {
        twoLocations();
        MockMvc m = mvc();
        // A scan parked under B's label by A's uploader is filed from A and bound to nobody.
        var req = device(multipart(url(UPLOAD + "/commit"))).file(part("scan.e2e", "application/octet-stream", e2e()))
                .param("patientId", LABEL_B).param("scanDate", DAY_B).param("laterality", "OD").param("park", "true");
        MvcResult r = m.perform(withToken(req, tokenA)).andReturn();
        assertEquals(201, r.getResponse().getStatus(), bodyOf(r));
        long id = idOf(r);
        assertEquals(expectedOrigin(locA, tokenA), originOf(id));
        assertEquals(0, count("ingest_item", "ingest_item_id = " + id + " AND candidate_study_subject_id IS NOT NULL"),
                "no suggestion of a subject at another place");

        // Control: under its own label the same uploader's parked scan does name the subject it found.
        var own = device(multipart(url(UPLOAD + "/commit"))).file(part("scan.e2e", "application/octet-stream", e2e()))
                .param("patientId", LABEL_A).param("scanDate", DAY_A).param("laterality", "OD").param("park", "true");
        long ownId = idOf(m.perform(withToken(own, tokenA)).andReturn());
        assertEquals(1, count("ingest_item", "ingest_item_id = " + ownId + " AND candidate_study_subject_id IS NOT NULL"));
    }

    @Test
    void anUploadCanBeTakenBackOnlyByTheLocationItArrivedAt() throws Exception {
        twoLocations();
        MockMvc m = mvc();
        long image = idOf(commitImage(m, tokenA, png(), null, null));
        long parked = idOf(commitE2e(m, tokenA, null, null));

        // B's uploader cannot see them: not found, as an upload that does not exist.
        assertEquals(404, m.perform(withToken(device(delete(url(UPLOAD + "/items/" + image))), tokenB)).andReturn()
                .getResponse().getStatus());
        assertEquals(404, m.perform(withToken(device(delete(url(OCT + "/items/" + parked))), tokenB)).andReturn()
                .getResponse().getStatus());
        assertEquals(404, m.perform(withToken(device(delete(url(UPLOAD + "/items/" + 424242))), tokenB)).andReturn()
                .getResponse().getStatus());
        assertEquals(2, count("ingest_item", "ingest_item_id IN (" + image + ", " + parked + ")"));

        // A's can.
        assertEquals(204, m.perform(withToken(device(delete(url(UPLOAD + "/items/" + image))), tokenA)).andReturn()
                .getResponse().getStatus());
        assertEquals(204, m.perform(withToken(device(delete(url(OCT + "/items/" + parked))), tokenA)).andReturn()
                .getResponse().getStatus());
        assertEquals(0, count("ingest_item", "ingest_item_id IN (" + image + ", " + parked + ")"));
    }

    @Test
    void aScansJobCanBeTakenBackOnlyByTheLocationItArrivedAt() throws Exception {
        twoLocations();
        MockMvc m = mvc();
        MvcResult filed = commitE2e(m, tokenA, EVENT_A, DAY_A);
        assertEquals(201, filed.getResponse().getStatus(), bodyOf(filed));
        JsonNode jobs = JSON.readTree(bodyOf(filed)).path("jobs");
        assertTrue(jobs.size() > 0, "a scan filed to a visit starts a job: " + bodyOf(filed));
        long job = jobs.get(0).path("jobId").asLong();

        assertEquals(404, m.perform(withToken(device(delete(url(UPLOAD + "/jobs/" + job))), tokenB)).andReturn()
                .getResponse().getStatus());
        assertEquals(1, count("retinal_inference_job", "job_id = " + job));
        assertEquals(204, m.perform(withToken(device(delete(url(UPLOAD + "/jobs/" + job))), tokenA)).andReturn()
                .getResponse().getStatus());
        assertEquals(0, count("retinal_inference_job", "job_id = " + job));
    }

    @Test
    void aDuplicateAtAnotherLocationIsOnlyAlreadyUploaded() throws Exception {
        twoLocations();
        MockMvc m = mvc();
        byte[] bytes = png();
        MvcResult first = commitImage(m, tokenA, bytes, null, null);
        assertEquals(201, first.getResponse().getStatus());
        long firstId = idOf(first);

        MvcResult again = commitImage(m, tokenA, bytes, null, null);
        assertEquals(409, again.getResponse().getStatus());
        assertEquals(firstId, JSON.readTree(bodyOf(again)).path("existingIngestItemId").asLong(),
                "its own place's duplicate is named");

        MvcResult foreign = commitImage(m, tokenB, bytes, null, null);
        assertEquals(409, foreign.getResponse().getStatus());
        assertFalse(bodyOf(foreign).contains("existingIngestItemId"), "another place's is not: " + bodyOf(foreign));
        assertFalse(bodyOf(foreign).contains(String.valueOf(firstId)), bodyOf(foreign));
    }

    private static String sha256Hex(byte[] bytes) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    @Test
    void thePreflightNamesOnlyFilesThatArrivedAtTheTokensLocation() throws Exception {
        twoLocations();
        MockMvc m = mvc();
        byte[] bytes = png();
        long id = idOf(commitImage(m, tokenA, bytes, null, null));
        String sha = sha256Hex(bytes);

        var own = JSON.readTree(bodyOf(m.perform(withToken(device(get(url(UPLOAD + "/preflight")))
                .param("sha256", sha), tokenA)).andReturn()));
        assertTrue(own.path("exists").asBoolean(), own.toString());
        assertEquals(id, own.path("ingestItemId").asLong());
        String foreign = bodyOf(m.perform(withToken(device(get(url(UPLOAD + "/preflight")))
                .param("sha256", sha), tokenB)).andReturn());
        assertFalse(JSON.readTree(foreign).path("exists").asBoolean(), foreign);
        assertFalse(foreign.contains(String.valueOf(id)), foreign);

        // The older OCT page's preflight, which answers by job.
        long scanItem = idOf(commitE2e(m, tokenA, EVENT_A, DAY_A));
        String scanSha;
        try (Connection c = DATA_SOURCE.getConnection();
             java.sql.PreparedStatement ps = c.prepareStatement(
                     "SELECT e2e_sha256 FROM retinal_inference_job WHERE ingest_item_id = ? AND scan_index = 0")) {
            ps.setLong(1, scanItem);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "the visit's plan started a job");
                scanSha = rs.getString(1);
            }
        }
        var ownScan = JSON.readTree(bodyOf(m.perform(withToken(device(get(url(OCT + "/preflight")))
                .param("sha256", scanSha), tokenA)).andReturn()));
        assertTrue(ownScan.path("exists").asBoolean(), ownScan.toString());
        var foreignScan = JSON.readTree(bodyOf(m.perform(withToken(device(get(url(OCT + "/preflight")))
                .param("sha256", scanSha), tokenB)).andReturn()));
        assertFalse(foreignScan.path("exists").asBoolean(), foreignScan.toString());
        assertTrue(foreignScan.path("jobId").isNull(), foreignScan.toString());
    }

    private static long filesUnder(Path root) throws java.io.IOException {
        if (!Files.exists(root)) return 0;
        try (java.util.stream.Stream<Path> s = Files.walk(root)) {
            return s.filter(Files::isRegularFile).count();
        }
    }

    /* ---------------------------------------------------------------- */
    /* the origin of what is filed                                       */
    /* ---------------------------------------------------------------- */

    /** One file of every kind through every public page; returns what each is called and its id. */
    private java.util.Map<String, Long> fileEverything(MockMvc m, IssuedToken token, int visit, String day,
                                                       String label) throws Exception {
        java.util.Map<String, Long> ids = new java.util.LinkedHashMap<>();
        ids.put("page: image", idOf(commitImage(m, token, png(), null, null)));
        ids.put("page: image to a visit", idOf(commitImage(m, token, png(), visit, day)));
        ids.put("page: dicom", idOf(commitDicom(m, token, null, null)));
        ids.put("page: dicom to a visit", idOf(commitDicom(m, token, visit, day)));
        ids.put("page: e2e parked", idOf(commitE2e(m, token, null, null)));
        ids.put("page: e2e to a visit", idOf(commitE2e(m, token, visit, day)));
        ids.put("older image page", idOf(m.perform(withToken(device(multipart(url(IMAGE + "/commit")))
                .file(part("p.png", "image/png", png())).param("patientId", label), token)).andReturn()));
        ids.put("older image page, to a visit", idOf(m.perform(withToken(device(multipart(url(IMAGE + "/commit")))
                .file(part("p.png", "image/png", png())).param("studyEventId", String.valueOf(visit))
                .param("studyDate", day), token)).andReturn()));
        ids.put("older oct page", idOf(m.perform(withToken(device(multipart(url(OCT + "/commit")))
                .file(part("scan.e2e", "application/octet-stream", e2e())).param("patientId", label)
                .param("scanDate", day).param("laterality", "OS").param("park", "true"), token)).andReturn()));
        ids.put("older oct page, to a visit", idOf(m.perform(withToken(device(multipart(url(OCT + "/commit")))
                .file(part("scan.e2e", "application/octet-stream", e2e())).param("patientId", label)
                .param("scanDate", day).param("laterality", "OS").param("studyEventId", String.valueOf(visit)), token))
                .andReturn()));
        return ids;
    }

    @Test
    void everyFileATokenedCommitFilesCarriesTheSourceAndItsLocation() throws Exception {
        twoLocations();
        MockMvc m = mvc();
        for (boolean forA : new boolean[] {true, false}) {
            IssuedToken token = forA ? tokenA : tokenB;
            int location = forA ? locA : locB;
            java.util.Map<String, Long> filed = fileEverything(m, token, forA ? EVENT_A : eventB,
                    forA ? DAY_A : DAY_B, forA ? LABEL_A : LABEL_B);
            assertEquals(10, filed.size());
            for (var e : filed.entrySet()) {
                assertEquals(expectedOrigin(location, token), originOf(e.getValue()),
                        (forA ? "A" : "B") + ", " + e.getKey());
            }
        }
        assertEquals(0, count("ingest_item", "source_kind IN ('upload', 'portal-oct') "
                + "AND (origin_location_id IS NULL OR ingest_source_id IS NULL)"),
                "no row of a tokened commit lacks either column");
    }

    @Test
    void theJobsOfAScanFiledToAVisitHangOffAnItemThatCarriesBothColumns() throws Exception {
        twoLocations();
        MockMvc m = mvc();
        long item = idOf(commitE2e(m, tokenA, EVENT_A, DAY_A));
        assertTrue(count("retinal_inference_job", "ingest_item_id = " + item) > 0, "the visit's plan started a job");
        assertEquals(expectedOrigin(locA, tokenA), originOf(item));
        assertEquals(0, count("retinal_inference_job j", "j.ingest_item_id IS NOT NULL AND NOT EXISTS ("
                + "SELECT 1 FROM ingest_item i WHERE i.ingest_item_id = j.ingest_item_id "
                + "AND i.origin_location_id IS NOT NULL AND i.ingest_source_id IS NOT NULL)"),
                "every job of a tokened commit has an attributed item");
    }

    @Test
    void anUntokenedCommitOnASingleLocationInstallKeepsTheLocationAndHasNoSource() throws Exception {
        MockMvc m = mvc();
        java.util.Map<String, Long> filed = fileEverything(m, null, EVENT_A, DAY_A, LABEL_A);
        for (var e : filed.entrySet()) {
            assertEquals(expectedOrigin(locA, null), originOf(e.getValue()), e.getKey());
        }
    }

    /* ---------------------------------------------------------------- */
    /* the heartbeat                                                     */
    /* ---------------------------------------------------------------- */

    private static String instanceOfSource(long sourceId) throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT instance_uid FROM ingest_source WHERE ingest_source_id = ?")) {
            ps.setLong(1, sourceId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private static JsonNode uploaderDto(long sourceId) throws Exception {
        MockMvc admin = ProductionMvc.standalone(new UploadersApiController(DATA_SOURCE))
                .setControllerAdvice(new ApiExceptionHandler()).build();
        org.springframework.mock.web.MockHttpSession session = new org.springframework.mock.web.MockHttpSession();
        at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean ub =
                new at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean();
        ub.setId(1);
        ub.setName("root");
        ub.addUserType(at.ac.meduniwien.ophthalmology.libreclinica.bean.core.UserType.SYSADMIN);
        session.setAttribute("userBean", ub);
        MvcResult r = admin.perform(get("/api/v1/uploaders").session(session)).andReturn();
        for (JsonNode n : JSON.readTree(bodyOf(r))) {
            if (n.path("id").asLong() == sourceId) return n;
        }
        throw new AssertionError("uploader " + sourceId + " not listed: " + bodyOf(r));
    }

    @Test
    void aTokenedHeartbeatIsNotedOnItsSourceAndShownOnTheUploadersPage() throws Exception {
        twoLocations();
        MockMvc m = mvc();
        String uid = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee";
        MvcResult r = heartbeat(m, tokenA, uid);
        assertEquals(200, r.getResponse().getStatus(), bodyOf(r));
        assertTrue(bodyOf(r).contains("\"recorded\":true"));

        assertEquals(uid, instanceOfSource(tokenA.source().id()));
        assertNull(instanceOfSource(tokenB.source().id()), "the other uploader is not touched");
        JsonNode dto = uploaderDto(tokenA.source().id());
        assertEquals(uid, dto.path("instanceUid").asText());
        assertFalse(dto.path("lastHeartbeatAt").isNull(), "the page shows when the program last reported");

        // The program under that token changes (the PC was re-imaged): the source follows the heartbeat.
        String next = "bbbbbbbb-bbbb-4ccc-8ddd-eeeeeeeeeeee";
        assertEquals(200, heartbeat(m, tokenA, next).getResponse().getStatus());
        assertEquals(next, instanceOfSource(tokenA.source().id()));
    }

    @Test
    void aHeartbeatWithoutATokenFollowsTheDoor() throws Exception {
        String uid = "cccccccc-bbbb-4ccc-8ddd-eeeeeeeeeeee";
        MvcResult served = heartbeat(mvc(), null, uid);
        assertEquals(200, served.getResponse().getStatus(), bodyOf(served));
        assertEquals(1, count("uploader_instance", "instance_uid = '" + uid + "'"));
        assertNull(instanceOfSource(tokenA.source().id()), "attributed to nobody");

        twoLocations();
        String other = "dddddddd-bbbb-4ccc-8ddd-eeeeeeeeeeee";
        assertDoor(heartbeat(mvc(), null, other), 401, "uploader token required");
        assertEquals(0, count("uploader_instance", "instance_uid = '" + other + "'"));
    }

    /* ---------------------------------------------------------------- */
    /* the Optomed worklist                                              */
    /* ---------------------------------------------------------------- */

    @Test
    void theWorklistListsOnlyTheVisitsOfTheTokensLocation() throws Exception {
        twoLocations();
        MockMvc m = mvc();
        MvcResult a = worklist(m, tokenA, DAY_A, false);
        MvcResult b = worklist(m, tokenB, DAY_A, false);
        assertEquals(200, a.getResponse().getStatus(), bodyOf(a));
        assertEquals(200, b.getResponse().getStatus(), bodyOf(b));
        assertEquals("no-store", a.getResponse().getHeader("Cache-Control"));
        assertTrue(bodyOf(a).contains(LABEL_A), "A's visit on that day");
        assertFalse(bodyOf(a).contains(LABEL_B), "not B's, on the same day");
        assertTrue(bodyOf(b).contains(LABEL_B));
        assertFalse(bodyOf(b).contains(LABEL_A));
    }

    @Test
    void theUploaderTokenReplacesTheSharedOneAndTheSwitchStillDecides() throws Exception {
        twoLocations();
        MockMvc m = mvc();
        // A wrong shared token does not matter once the uploader's own token has been checked...
        MvcResult both = m.perform(withToken(device(get(url(WORKLIST))).param("date", DAY_A)
                .header("X-MUW-Optomed-Token", "wrong"), tokenA)).andReturn();
        assertEquals(200, both.getResponse().getStatus(), bodyOf(both));
        assertTrue(bodyOf(both).contains(LABEL_A));

        // ...but the feature switch still does: off is a 404, token or not.
        java.lang.reflect.Field f = CoreResources.class.getDeclaredField("DATAINFO");
        f.setAccessible(true);
        java.util.Properties live = (java.util.Properties) f.get(null);
        live.setProperty("core.optomed.worklist.enabled", "false");
        try {
            assertEquals(404, worklist(m, tokenA, DAY_A, false).getResponse().getStatus());
        } finally {
            live.setProperty("core.optomed.worklist.enabled", "true");
        }
    }

    @Test
    void theSharedTokenAloneIsServedOnlyWhileTheDoorIsOpenAndThenAsBefore() throws Exception {
        // One location: the legacy path, exactly as before, and it lists every place's visits.
        MvcResult served = worklist(mvc(), null, DAY_A, true);
        assertEquals(200, served.getResponse().getStatus(), bodyOf(served));
        assertTrue(bodyOf(served).contains(LABEL_A) && bodyOf(served).contains(LABEL_B));
        // Its own gate is still there.
        assertEquals(401, mvc().perform(device(get(url(WORKLIST))).param("date", DAY_A)).andReturn()
                .getResponse().getStatus());

        // Two locations: a shared secret cannot say which place is asking.
        twoLocations();
        assertDoor(worklist(mvc(), null, DAY_A, true), 401, "uploader token required");
    }

    /* ---------------------------------------------------------------- */
    /* LocationScope.studyIdsAtLocation                                  */
    /* ---------------------------------------------------------------- */

    @Test
    void theStudiesAtALocationAreItsSitesAndTheStudiesWithoutAnyOfTheirOwn() throws Exception {
        twoLocations();
        int loose;
        try (Connection c = DATA_SOURCE.getConnection()) {
            Set<Integer> atA = LocationScope.studyIdsAtLocation(c, locA);
            assertTrue(atA.contains(1), "Default Study is at A");
            assertFalse(atA.contains(siteB));
            assertEquals(Set.of(siteB), LocationScope.studyIdsAtLocation(c, locB));

            // A removed site is nowhere.
            exec(c, "UPDATE study SET status_id = 5 WHERE study_id = " + siteB);
            assertEquals(Set.of(), LocationScope.studyIdsAtLocation(c, locB));
            exec(c, "UPDATE study SET status_id = 1 WHERE study_id = " + siteB);

            // A study with no location of its own reads as being at the default one, while there is one.
            loose = LifecycleFixtures.insertStudy(c, null, "UTKNL", "No location", "S_UTKNL", 1);
            exec(c, "UPDATE study SET location_id = NULL WHERE study_id = " + loose);
            assertFalse(LocationScope.studyIdsAtLocation(c, locA).contains(loose), "two places: at neither");
            assertFalse(LocationScope.studyIdsAtLocation(c, locB).contains(loose));
            oneLocation();
            assertTrue(LocationScope.studyIdsAtLocation(c, locA).contains(loose), "one place: at that one");
            exec(c, "DELETE FROM study WHERE study_id = " + loose);
        }
    }
}
