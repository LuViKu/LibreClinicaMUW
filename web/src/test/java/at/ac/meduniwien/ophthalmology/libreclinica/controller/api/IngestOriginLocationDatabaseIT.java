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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

import at.ac.meduniwien.ophthalmology.libreclinica.dao.core.CoreResources;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.IngestArtifactStore;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.remidio.RemidioGatewayClient;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.StudySubjectFinder;

/**
 * DR-040 — every device and anonymous ingress stamps the origin location of
 * the file it writes.
 *
 * <p>While exactly one location is active, a device cannot be anywhere else, so
 * the file carries that location and the inbox behaves as it always did. Once
 * a second one exists the server no longer knows where an unassigned device
 * is: the file is stamped with nothing, which the inbox shows to system
 * administrators only, until phase 2 lets an administrator say which place a
 * source belongs to. The staff paths (which carry the location of the
 * uploader's study) are in {@link LocationVisibilityDatabaseIT}, where a world
 * with two sites at two places exists.
 *
 * <p>Seeded fixture used: study_event 3 = M-001 / V3 Day 90 / 2021-01-04.
 */
@SuppressWarnings("null")
class IngestOriginLocationDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static final String TOKEN = "origin-it-token";

    @TempDir
    static Path STORE_ROOT;

    private static java.util.Properties SAVED_DATAINFO;
    private static final AtomicInteger SEQ = new AtomicInteger(1000);

    @BeforeAll
    static void pointTheStoresAtATempDir() throws Exception {
        java.lang.reflect.Field f = CoreResources.class.getDeclaredField("DATAINFO");
        f.setAccessible(true);
        java.util.Properties live = (java.util.Properties) f.get(null);
        assertNotNull(live, "DATAINFO must be set by AbstractApiControllerDatabaseIT");
        SAVED_DATAINFO = new java.util.Properties();
        SAVED_DATAINFO.putAll(live);
        live.setProperty("core.dicom.ingest.token", TOKEN);
        live.setProperty(IngestArtifactStore.CONFIG_KEY_STORE_PATH,
                Files.createDirectories(STORE_ROOT.resolve("ingest")).toString());
        // The older public image page reads its own key.
        live.setProperty("core.dicom.ingest.storePath",
                Files.createDirectories(STORE_ROOT.resolve("images")).toString());
        live.setProperty("core.retinalInference.e2eUploadsPath",
                Files.createDirectories(STORE_ROOT.resolve("e2e")).toString());
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
    void clean() throws Exception {
        try (Connection c = DATA_SOURCE.getConnection()) {
            exec(c, "DELETE FROM audit_log_event WHERE audit_table = 'ingest_item'");
            exec(c, "DELETE FROM ingest_item WHERE source_kind IN ('upload', 'dicom', 'portal-oct', 'remidio')");
            exec(c, "DELETE FROM remidio_exam WHERE site_custom_id = 'origin_it'");
            exec(c, "DELETE FROM remidio_visit_exam WHERE study_event_id = 3");
            exec(c, "DELETE FROM location WHERE code = 'ORIGIN-IT-B'");
        }
    }

    /* ------------------------------------------------------------------ */
    /* one location: every device is at it                                 */
    /* ------------------------------------------------------------------ */

    @Test
    void theDicomHandoffCarriesTheOnlyLocation() throws Exception {
        long id = dicomHandoff();
        assertEquals(onlyLocation(), originOf(id));
    }

    @Test
    void thePublicImageUploadCarriesTheOnlyLocation() throws Exception {
        long id = publicImageUpload();
        assertEquals(onlyLocation(), originOf(id));
    }

    @Test
    void thePublicOctUploadCarriesTheOnlyLocation() throws Exception {
        long id = publicOctUpload();
        assertEquals(onlyLocation(), originOf(id));
    }

    @Test
    void theUploadPageCarriesTheOnlyLocation() throws Exception {
        long id = uploadPage();
        assertEquals(onlyLocation(), originOf(id));
    }

    @Test
    void theRemidioPullCarriesTheOnlyLocation() throws Exception {
        long id = remidioPull();
        assertEquals(onlyLocation(), originOf(id));
    }

    /* ------------------------------------------------------------------ */
    /* several locations: an unassigned device is nowhere in particular    */
    /* ------------------------------------------------------------------ */

    @Test
    void withTwoLocationsAnUnassignedDeviceLeavesTheOriginEmpty() throws Exception {
        try (Connection c = DATA_SOURCE.getConnection()) {
            exec(c, "INSERT INTO location (code, name) VALUES ('ORIGIN-IT-B', 'Second place')");
        }
        assertNull(originOf(dicomHandoff()), "DICOM hand-off");
        assertNull(originOf(publicImageUpload()), "public image upload");
        assertNull(originOf(publicOctUpload()), "public OCT upload");
        assertNull(originOf(uploadPage()), "upload page");
        assertNull(originOf(remidioPull()), "Remidio pull");
    }

    /* ------------------------------------------------------------------ */
    /* the ingresses                                                       */
    /* ------------------------------------------------------------------ */

    private static long dicomHandoff() throws Exception {
        String uid = "1.2.826.0.1.3680043.9.7890.ORIGIN." + SEQ.incrementAndGet();
        String payload = "{"
                + "\"sopInstanceUid\":\"" + uid + "\","
                + "\"sopClassUid\":\"1.2.840.10008.5.1.4.1.1.77.1.5.1\","
                + "\"studyInstanceUid\":\"1.2.3.4.5\",\"seriesInstanceUid\":\"1.2.3.4.5.6\","
                + "\"modality\":\"OP\",\"patientId\":\"M-001\",\"patientName\":\"M-001^\","
                + "\"studyDate\":\"2021-01-04\",\"laterality\":\"OD\",\"sourceAeTitle\":\"OPTOMEDLUMO\","
                + "\"dicomPath\":\"/tmp/it/" + uid + ".dcm\",\"previewPngPath\":\"/tmp/it/" + uid + ".png\"}";
        MvcResult r = ProductionMvc.standalone(new DicomIngestApiController(DATA_SOURCE))
                .setControllerAdvice(new ApiExceptionHandler()).build()
                .perform(post("/api/v1/internal/dicom-ingest")
                        .header("X-MUW-Dicom-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isCreated()).andReturn();
        return idIn(r, "imageIngestId");
    }

    private static long publicImageUpload() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "fundus.png", "image/png", uniquePng());
        MvcResult r = ProductionMvc.standalone(
                        new PublicImageUploadController(DATA_SOURCE, new StudySubjectFinder(DATA_SOURCE)))
                .setControllerAdvice(new ApiExceptionHandler()).build()
                .perform(multipart("/api/v1/public/image-upload/commit").file(file)
                        .param("patientId", "M-001").param("laterality", "OD").param("studyDate", "2021-01-04"))
                .andExpect(status().isCreated()).andReturn();
        return idIn(r, "imageIngestId");
    }

    private static long publicOctUpload() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "scan.e2e", "application/octet-stream",
                uniqueBytes(256));
        MvcResult r = ProductionMvc.standalone(
                        new PublicOctUploadController(DATA_SOURCE, new StudySubjectFinder(DATA_SOURCE)))
                .setControllerAdvice(new ApiExceptionHandler()).build()
                .perform(multipart("/api/v1/public/oct-upload/commit").file(file)
                        .param("patientId", "UNKNOWN-XYZ").param("scanDate", "2020-10-06")
                        .param("laterality", "OS").param("park", "true"))
                .andExpect(status().isCreated()).andReturn();
        return idIn(r, "ingestItemId");
    }

    private static long uploadPage() throws Exception {
        StudySubjectFinder finder = new StudySubjectFinder(DATA_SOURCE);
        PublicOctUploadController oct = new PublicOctUploadController(DATA_SOURCE, finder);
        PublicImageUploadController images = new PublicImageUploadController(DATA_SOURCE, finder);
        MockMultipartFile file = new MockMultipartFile("file", "fundus.png", "image/png", uniquePng());
        MvcResult r = ProductionMvc.standalone(new PublicUploadController(DATA_SOURCE, finder, oct, images))
                .setControllerAdvice(new ApiExceptionHandler()).build()
                .perform(multipart("/api/v1/public/upload/commit").file(file)
                        .param("patientId", "M-001").param("laterality", "OD").param("scanDate", "2021-01-04"))
                .andExpect(status().isCreated()).andReturn();
        return idIn(r, "ingestItemId");
    }

    /** One image of one exam the cloud lists for the visit the patient sync mapped it to. */
    private static long remidioPull() throws Exception {
        long exam = 920_000_000_000_000L + SEQ.incrementAndGet();
        long examMs = Instant.parse("2021-01-04T09:00:00Z").toEpochMilli();
        String imageId = "origin-im-" + exam;
        try (Connection c = DATA_SOURCE.getConnection()) {
            exec(c, "DELETE FROM remidio_visit_exam WHERE study_event_id = 3");
            exec(c, "INSERT INTO remidio_visit_exam (study_event_id, remidio_exam_id, exam_custom_id) VALUES (3, "
                    + exam + ", 'M-001 V3')");
        }
        String listing = "[{\"patientDetails\":{\"mrn\":\"M-001\",\"firstName\":\"M-001\",\"lastName\":\"IT\"},"
                + "\"examDetails\":{\"id\":\"" + exam + "\",\"examDate\":" + examMs
                + ",\"deviceType\":[\"FOP\"],\"examState\":\"ACTIVE\"},"
                + "\"images\":{\"fopImages\":{\"STANDARD\":[{\"id\":\"" + imageId + "\",\"examId\":\"" + exam
                + "\",\"date\":" + examMs + ",\"laterality\":\"RIGHT\",\"deviceType\":\"FOP\","
                + "\"path\":\"https://storage.googleapis.com/b/" + imageId + ".jpg?X-Goog-Signature=s\"}]}}}]";
        RemidioGatewayClient.Settings settings = new RemidioGatewayClient.Settings(
                "https://remidio.example", "PACS_GATEWAY", "client-jwt", "bot@example.org", "pw", "origin_it");
        RemidioGatewayClient.Transport wire = (method, uri, _, _, _) -> {
            String p = uri.getPath();
            if (p.endsWith("/api/user/loginUser")) return new RemidioGatewayClient.Response(200, ok("\"bearer\""));
            if (p.endsWith("/api/gateway/getAuthToken")) return new RemidioGatewayClient.Response(200, ok("\"cat\""));
            if (p.contains("/api/gateway/getExamsByDate/")) return new RemidioGatewayClient.Response(200, ok(listing));
            throw new IllegalStateException("unexpected call " + method + " " + p);
        };
        RemidioGatewayClient.Downloader files = (uri, _) -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.writeBytes(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0});
            out.writeBytes(("origin " + uri.getPath()).getBytes(StandardCharsets.US_ASCII));
            out.writeBytes(new byte[] {(byte) 0xFF, (byte) 0xD9});
            return new RemidioGatewayClient.Download(200, new ByteArrayInputStream(out.toByteArray()));
        };
        LocalDate day = LocalDate.of(2021, 1, 4);
        RemidioPullService.Summary s = new RemidioPullService(DATA_SOURCE,
                new RemidioGatewayClient(settings, wire, files)).pull(day, day);
        assertEquals(1, s.bound(), s.line());
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT ingest_item_id FROM ingest_item WHERE remidio_image_id = ?")) {
            ps.setString(1, imageId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "the pull filed the image");
                return rs.getLong(1);
            }
        }
    }

    private static String ok(String dataJson) {
        return "{\"status\":{\"statusCode\":\"OK\",\"message\":\"HTTP Status - OK\"},\"data\":" + dataJson + "}";
    }

    /* ------------------------------------------------------------------ */
    /* helpers                                                             */
    /* ------------------------------------------------------------------ */

    private static long idIn(MvcResult r, String field) throws Exception {
        return tools.jackson.databind.json.JsonMapper.builder().build()
                .readTree(r.getResponse().getContentAsString()).get(field).asLong();
    }

    /** A picture of its own: the platform refuses a second file showing the same pixels under the same label. */
    private static byte[] uniquePng() throws Exception {
        int n = SEQ.incrementAndGet();
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(4, 4, java.awt.image.BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < 4; x++) {
            for (int y = 0; y < 4; y++) img.setRGB(x, y, (n * 2654435761L + x * 40503L + y * 9973L) % 0xFFFFFF > 0
                    ? (int) ((n * 2654435761L + x * 40503L + y * 9973L) % 0xFFFFFF) : 1);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private static byte[] uniqueBytes(int n) {
        byte[] out = new byte[n];
        byte[] seed = ("origin " + SEQ.incrementAndGet()).getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(seed, 0, out, 0, Math.min(seed.length, n));
        return out;
    }

    private static int onlyLocation() throws Exception {
        assertEquals(1, LifecycleFixtures.intQuery("SELECT count(*) FROM location WHERE status_id = 1"),
                "this check is about a single active location");
        return LifecycleFixtures.intQuery("SELECT location_id FROM location WHERE status_id = 1");
    }

    private static Integer originOf(long ingestItemId) throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT origin_location_id FROM ingest_item WHERE ingest_item_id = ?")) {
            ps.setLong(1, ingestItemId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "no ingest_item " + ingestItemId);
                int v = rs.getInt(1);
                return rs.wasNull() ? null : v;
            }
        }
    }

    private static void exec(Connection c, String sql) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }
}
