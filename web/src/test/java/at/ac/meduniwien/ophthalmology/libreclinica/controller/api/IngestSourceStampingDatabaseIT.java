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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import at.ac.meduniwien.ophthalmology.libreclinica.dao.core.CoreResources;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.LocationMode;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.IngestArtifactStore;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.IngestSourceService;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.remidio.RemidioGatewayClient;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * DR-040 phase 2, slice D2 — the two ingresses that have no credential, the
 * DICOM C-STORE hand-off and the Remidio pull, file what they receive under an
 * ingest source.
 *
 * <p>The rule pinned here, for both:
 * <ul>
 *   <li>The first file from a calling AE title (or from the Remidio account)
 *       makes an <em>unassigned</em> source, once; later files find it.</li>
 *   <li>A file is stamped with the source ({@code ingest_source_id}) and with
 *       the source's location as its origin. While the source has none, the
 *       origin is the only active location, and nothing when there are
 *       several: the file then waits for a system administrator, and assigning
 *       the source places it.</li>
 *   <li>A sender the platform cannot name (no AE title, an invalid one, or one
 *       past the cap on automatically made AE sources) gets no source, only
 *       the default-location rule. The hand-off is never refused for it.</li>
 * </ul>
 *
 * <p>Seeded fixture used: study_event 3 = M-001 / V3 Day 90 / 2021-01-04.
 */
@SuppressWarnings("null")
class IngestSourceStampingDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static final String TOKEN = "stamping-it-token";
    /** Every SOP UID this IT writes starts with this so the cleanup is exact. */
    private static final String UID_PREFIX = "1.2.826.0.1.3680043.9.7890.D2.";
    private static final String SITE = "d2_site";
    private static final AtomicInteger SEQ = new AtomicInteger(100);
    private static final int ROOT = 1;
    /** How many AE titles the app makes sources of (DicomIngestApiController.MAX_AE_SOURCES). */
    private static final int AE_LIMIT = 200;

    @TempDir
    static Path STORE_ROOT;

    private static java.util.Properties SAVED_DATAINFO;
    private static int defaultId;
    private static MockMvc handoffMvc;
    private static IngestSourceService sources;

    @BeforeAll
    static void configure() throws Exception {
        java.lang.reflect.Field f = CoreResources.class.getDeclaredField("DATAINFO");
        f.setAccessible(true);
        java.util.Properties live = (java.util.Properties) f.get(null);
        assertNotNull(live, "DATAINFO must be set by AbstractApiControllerDatabaseIT");
        SAVED_DATAINFO = new java.util.Properties();
        SAVED_DATAINFO.putAll(live);
        live.setProperty("core.dicom.ingest.token", TOKEN);
        // The Remidio pull stores the images it downloads.
        live.setProperty(IngestArtifactStore.CONFIG_KEY_STORE_PATH,
                Files.createDirectories(STORE_ROOT.resolve("ingest")).toString());
        defaultId = LifecycleFixtures.intQuery("SELECT location_id FROM location WHERE code = 'MUW-AUGEN'");
        sources = new IngestSourceService(DATA_SOURCE);
    }

    /** A controller of its own per test: its once-an-hour log of the limit must not carry over from another test. */
    @BeforeEach
    void freshController() {
        handoffMvc = ProductionMvc.standalone(new DicomIngestApiController(DATA_SOURCE))
                .setControllerAdvice(new ApiExceptionHandler()).build();
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

    /** Files before sources before places: the keys point that way. Back to single mode. */
    @AfterEach
    void clean() throws Exception {
        LocationMode.set(LocationMode.SINGLE);
        try (Connection c = DATA_SOURCE.getConnection()) {
            exec(c, "DELETE FROM audit_log_event WHERE audit_table IN ('ingest_item', 'ingest_source')");
            exec(c, "DELETE FROM ingest_item WHERE sop_instance_uid LIKE '" + UID_PREFIX + "%' "
                    + "OR remidio_image_id LIKE 'd2-im-%'");
            exec(c, "DELETE FROM remidio_exam WHERE site_custom_id IN ('" + SITE + "', 'd2_other')");
            exec(c, "DELETE FROM remidio_visit_exam WHERE study_event_id = 3");
            exec(c, "DELETE FROM ingest_source");
            exec(c, "DELETE FROM location WHERE code LIKE 'D2-%'");
        }
    }

    /* ====================================================================== */
    /* DICOM: the calling AE title is the source                               */
    /* ====================================================================== */

    @Test
    void theFirstFileOfACallingAeMakesASourceOnceAndStampsEveryFile() throws Exception {
        long first = handoff("D2-CAMERA");
        long second = handoff("D2-CAMERA");

        assertEquals(1, count("SELECT count(*) FROM ingest_source WHERE kind = 'dicom_ae'"),
                "one source however many files");
        long source = sourceId("dicom_ae", "D2-CAMERA");
        assertEquals("D2-CAMERA", text("SELECT display_name FROM ingest_source WHERE ingest_source_id = " + source));
        assertEquals(Integer.valueOf(defaultId), locationOfSource(source),
                "single-location mode: a source seen for the first time is at the one location");
        assertEquals(Long.valueOf(source), sourceOfItem(first));
        assertEquals(Long.valueOf(source), sourceOfItem(second));
        assertEquals(1, count("SELECT count(*) FROM audit_log_event WHERE audit_table = 'ingest_source' "
                + "AND audit_log_event_type_id = 201 AND entity_id = " + source),
                "the first sight is audited, once");
    }

    @Test
    void inMultiModeASourceSeenForTheFirstTimeHasNoPlaceEvenWithOneLocation() throws Exception {
        LocationMode.set(LocationMode.MULTI);
        long item = handoff("D2-CAMERA");
        long source = sourceId("dicom_ae", "D2-CAMERA");
        assertNull(locationOfSource(source), "multi mode: a new camera waits for an administrator");
        assertNull(originOf(item), "and so does what it sends");
        sources.assignLocation(source, defaultId, ROOT);
        assertEquals(Integer.valueOf(defaultId), originOf(item), "assigning the source places the file");
    }

    @Test
    void inSingleModeANewSourcesFilesAreAtTheOneLocation() throws Exception {
        assertEquals(1, count("SELECT count(*) FROM location WHERE status_id = 1"), "this check is about one location");
        long item = handoff("D2-CAMERA");
        assertNotNull(sourceOfItem(item), "the file carries its source");
        assertEquals(Integer.valueOf(defaultId), originOf(item));
    }

    @Test
    void anUnassignedSourceWithSeveralLocationsLeavesTheOriginEmptyUntilTheSourceIsAssigned() throws Exception {
        int other = location("D2-B");
        long item = handoff("D2-CAMERA");

        assertNotNull(sourceOfItem(item), "the file carries its source");
        assertNull(originOf(item), "with two places an unassigned camera is nowhere in particular");

        sources.assignLocation(sourceOfItem(item), other, ROOT);
        assertEquals(Integer.valueOf(other), originOf(item),
                "assigning the source places the file that waited for it");
        assertEquals(Integer.valueOf(other), originOf(handoff("D2-CAMERA")), "and the next one lands there");
    }

    @Test
    void anAssignedSourceFilesAtItsOwnLocationWhileSeveralLocationsExist() throws Exception {
        location("D2-B");
        handoff("D2-CAMERA");
        sources.assignLocation(sourceId("dicom_ae", "D2-CAMERA"), defaultId, ROOT);

        assertEquals(Integer.valueOf(defaultId), originOf(handoff("D2-CAMERA")),
                "the source's place decides, though there is no default once a second location exists");
        assertEquals(1, count("SELECT count(*) FROM ingest_source WHERE kind = 'dicom_ae'"));
    }

    @Test
    void aSourceAssignedAtASingleLocationStillFilesThere() throws Exception {
        handoff("D2-CAMERA");
        sources.assignLocation(sourceId("dicom_ae", "D2-CAMERA"), defaultId, ROOT);
        assertEquals(Integer.valueOf(defaultId), originOf(handoff("D2-CAMERA")));
    }

    @Test
    void aWorklistBoundFileAlsoCarriesItsSource() throws Exception {
        MvcResult r = send(UID_PREFIX + SEQ.incrementAndGet(), "D2-CAMERA", "LC3")
                .andExpect(status().isCreated()).andReturn();
        assertEquals("BOUND", jsonOf(r, "status"));
        long item = Long.parseLong(jsonOf(r, "imageIngestId"));
        assertNotNull(sourceOfItem(item));
        assertEquals(Integer.valueOf(defaultId), originOf(item));
    }

    @Test
    void aResentFileIsStillAnswered200WithoutASecondRow() throws Exception {
        String uid = UID_PREFIX + "resent";
        send(uid, "D2-CAMERA", null).andExpect(status().isCreated());
        send(uid, "D2-CAMERA", null).andExpect(status().isOk()).andExpect(jsonPath("$.duplicate").value(true));
        assertEquals(1, count("SELECT count(*) FROM ingest_item WHERE sop_instance_uid = '" + uid + "'"));
        assertEquals(1, count("SELECT count(*) FROM ingest_source WHERE kind = 'dicom_ae'"));
    }

    /* ====================================================================== */
    /* DICOM: a title that cannot be a source                                  */
    /* ====================================================================== */

    @Test
    void aMissingOrInvalidAeTitleMakesNoSourceAndFilesUnderTheDefaultLocationRule() throws Exception {
        // Missing: an older receiver that sends no title at all, or a blank one.
        for (String ae : new String[] {null, "", "   "}) {
            long item = handoff(ae);
            assertNull(sourceOfItem(item), "no title, no source: " + ae);
            assertEquals(Integer.valueOf(defaultId), originOf(item));
        }
        // Invalid: longer than 16, or a character an AE title cannot have.
        for (String ae : new String[] {"ABCDEFGHIJKLMNOPQ", "bad;title", "a/b", "tab\there", "ärzte"}) {
            long item = handoff(ae);
            assertNull(sourceOfItem(item), "an invalid title is no source: " + ae);
            assertEquals(Integer.valueOf(defaultId), originOf(item));
        }
        assertEquals(0, count("SELECT count(*) FROM ingest_source"), "nothing was made for any of them");
        assertNotNull(sourceOfItem(handoff("D2-CAMERA")), "a valid title right after them is a source");

        location("D2-B");
        assertNull(originOf(handoff(null)), "with two places there is no default either");
        assertNull(originOf(handoff("bad;title")));
    }

    @Test
    void aTitleWithSpacesAroundItIsTheSameSourceAsTheTrimmedOne() throws Exception {
        long a = handoff("D2-CAMERA");
        long b = handoff("  D2-CAMERA ");
        assertEquals(sourceOfItem(a), sourceOfItem(b));
        assertEquals(1, count("SELECT count(*) FROM ingest_source WHERE kind = 'dicom_ae'"));
    }

    /* ====================================================================== */
    /* DICOM: a limit on the titles the platform makes sources of              */
    /* ====================================================================== */

    /**
     * The receiver's allow-list is empty by default, and then any sender that
     * reaches its port is accepted. The app therefore stops making sources
     * after a fixed number of AE titles; a file from a title past that is
     * filed all the same, with no source.
     */
    @Test
    void pastTheLimitANewTitleGetsNoSourceButItsFileIsFiledAndKnownTitlesStillResolve() throws Exception {
        exec("INSERT INTO ingest_source (kind, source_key, display_name) "
                + "SELECT 'dicom_ae', 'FILL' || g, 'fill ' || g FROM generate_series(1, " + AE_LIMIT + ") g");
        try (LogCapture logged = new LogCapture(DicomIngestApiController.class)) {
            long newcomer = handoff("D2-NEWCOMER");
            assertNull(sourceOfItem(newcomer), "past the limit a new title is no source");
            assertEquals(Integer.valueOf(defaultId), originOf(newcomer), "but the file is filed, by the default rule");
            assertEquals(AE_LIMIT, count("SELECT count(*) FROM ingest_source WHERE kind = 'dicom_ae'"));
            assertEquals(0, count("SELECT count(*) FROM ingest_source WHERE source_key = 'D2-NEWCOMER'"));

            long known = handoff("FILL7");
            assertEquals(Long.valueOf(sourceId("dicom_ae", "FILL7")), sourceOfItem(known),
                    "a title that is already a source keeps resolving past the limit");

            handoff("D2-NEWCOMER-2");
            List<ILoggingEvent> warnings = logged.appender.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
            assertEquals(1, warnings.size(), "the refusal is logged, once however many titles knock: " + warnings);
            String line = warnings.get(0).getFormattedMessage();
            assertTrue(line.contains("limit"), line);
            assertFalse(line.contains("NEWCOMER"), "what a sender calls itself is not written to the log: " + line);
        }
    }

    @Test
    void roomAgainBelowTheLimitMakesTheSourceAgain() throws Exception {
        exec("INSERT INTO ingest_source (kind, source_key, display_name) "
                + "SELECT 'dicom_ae', 'FILL' || g, 'fill ' || g FROM generate_series(1, " + AE_LIMIT + ") g");
        assertNull(sourceOfItem(handoff("D2-LATE")));
        exec("DELETE FROM ingest_source WHERE source_key = 'FILL1'");
        long item = handoff("D2-LATE");
        assertEquals(Long.valueOf(sourceId("dicom_ae", "D2-LATE")), sourceOfItem(item));
    }

    @Test
    void theLimitCountsOnlyAeTitles() throws Exception {
        exec("INSERT INTO ingest_source (kind, source_key, display_name) "
                + "SELECT 'remidio', 'ACC' || g, 'acc ' || g FROM generate_series(1, " + AE_LIMIT + ") g");
        assertNotNull(sourceOfItem(handoff("D2-CAMERA")), "other kinds of source do not use up the AE titles' room");
    }

    @Test
    void theHandoffsLimitIsTwoHundred() {
        assertEquals(AE_LIMIT, DicomIngestApiController.MAX_AE_SOURCES);
    }

    /* ====================================================================== */
    /* The service: the limited form of resolveAutoSource                      */
    /* ====================================================================== */

    @Test
    void theLimitedFormMakesSourcesUpToTheLimitThenAnswersEmptyAndWritesNothing() {
        assertTrue(sources.resolveAutoSource("dicom_ae", "LIM1", "LIM1", 2).isPresent());
        assertTrue(sources.resolveAutoSource("dicom_ae", "LIM2", "LIM2", 2).isPresent());
        assertTrue(sources.resolveAutoSource("dicom_ae", "LIM3", "LIM3", 2).isEmpty(), "past the limit");
        assertTrue(sources.resolveAutoSource("dicom_ae", "LIM4", "LIM4", 0).isEmpty(), "a limit of none makes none");

        assertEquals(2, countOf("SELECT count(*) FROM ingest_source WHERE kind = 'dicom_ae'"));
        assertEquals(2, countOf("SELECT count(*) FROM audit_log_event WHERE audit_table = 'ingest_source'"),
                "a source that was not made is not audited");
        assertEquals("LIM1", sources.resolveAutoSource("dicom_ae", "LIM1", "other name", 2).orElseThrow().sourceKey(),
                "an existing source is found whatever the count, and keeps its name");
        assertEquals("LIM1", sources.resolveAutoSource("dicom_ae", "LIM1", "x", 0).orElseThrow().displayName());
    }

    @Test
    void theLimitedFormStillRejectsAKeyThatIsNotATitleAndAKindThatIsNotAutomatic() {
        var invalid = org.junit.jupiter.api.Assertions.assertThrows(IngestSourceService.Refusal.class,
                () -> sources.resolveAutoSource("dicom_ae", "bad;title", "x", 5));
        assertEquals("sourceKey", invalid.field());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> sources.resolveAutoSource("uploader", "x", "x", 5));
    }

    @Test
    void callersThatMeetAFullTableAtOnceMakeExactlyTheRoomThatIsLeft() throws Exception {
        for (int i = 1; i <= 3; i++) sources.resolveAutoSource("dicom_ae", "PRE" + i, "PRE" + i);
        int callers = 12;
        int limit = 5;

        List<Boolean> made = inParallel(callers, i ->
                sources.resolveAutoSource("dicom_ae", "RACE" + i, "RACE" + i, limit).isPresent());

        assertEquals(limit - 3, made.stream().filter(m -> m).count(), "room for two, however many knock: " + made);
        assertEquals(limit, countOf("SELECT count(*) FROM ingest_source WHERE kind = 'dicom_ae'"));
    }

    @Test
    void callersForOneNewTitleAtOnceMakeOneSource() throws Exception {
        List<Long> ids = inParallel(8, i ->
                sources.resolveAutoSource("dicom_ae", "SAME", "SAME", 50).orElseThrow().id());

        assertEquals(1, ids.stream().distinct().count(), "every caller gets the same source: " + ids);
        assertEquals(1, countOf("SELECT count(*) FROM ingest_source WHERE source_key = 'SAME'"));
        assertEquals(1, countOf("SELECT count(*) FROM audit_log_event WHERE audit_table = 'ingest_source' "
                + "AND audit_log_event_type_id = 201"), "the first sight is audited once");
    }

    /* ====================================================================== */
    /* Remidio: the account is the source                                      */
    /* ====================================================================== */

    @Test
    void thePullMakesTheAccountSourceOnceAndStampsEveryImage() throws Exception {
        long first = remidioPull(SITE, false);
        long second = remidioPull(SITE, false);

        assertEquals(1, count("SELECT count(*) FROM ingest_source WHERE kind = 'remidio'"));
        long source = sourceId("remidio", SITE);
        assertEquals("Remidio", text("SELECT display_name FROM ingest_source WHERE ingest_source_id = " + source));
        assertEquals(Integer.valueOf(defaultId), locationOfSource(source),
                "single-location mode: the account is at the one location");
        assertEquals(Long.valueOf(source), sourceOfItem(first));
        assertEquals(Long.valueOf(source), sourceOfItem(second));
        assertEquals(Integer.valueOf(defaultId), originOf(first), "one location: the default");
        assertEquals(1, count("SELECT count(*) FROM audit_log_event WHERE audit_table = 'ingest_source' "
                + "AND audit_log_event_type_id = 201 AND entity_id = " + source));
    }

    @Test
    void aVisitBoundImageCarriesTheSourceToo() throws Exception {
        long item = remidioPull(SITE, true);
        assertEquals("BOUND", text("SELECT status FROM ingest_item WHERE ingest_item_id = " + item));
        assertEquals(Long.valueOf(sourceId("remidio", SITE)), sourceOfItem(item));
        assertEquals(Integer.valueOf(defaultId), originOf(item));
    }

    @Test
    void withSeveralLocationsAnUnassignedAccountLeavesTheOriginEmptyUntilItIsAssigned() throws Exception {
        int other = location("D2-B");
        long item = remidioPull(SITE, false);
        assertNull(originOf(item));
        assertNotNull(sourceOfItem(item));

        sources.assignLocation(sourceOfItem(item), other, ROOT);
        assertEquals(Integer.valueOf(other), originOf(item), "assigning the account places the images that waited");
        assertEquals(Integer.valueOf(other), originOf(remidioPull(SITE, false)), "and the next one lands there");
    }

    @Test
    void anotherRemidioSiteIsAnotherSource() throws Exception {
        long here = remidioPull(SITE, false);
        long there = remidioPull("d2_other", false);
        assertEquals(2, count("SELECT count(*) FROM ingest_source WHERE kind = 'remidio'"));
        assertFalse(sourceOfItem(here).equals(sourceOfItem(there)));
    }

    /* ====================================================================== */
    /* The ingresses                                                           */
    /* ====================================================================== */

    private static org.springframework.test.web.servlet.ResultActions send(String sopUid, String ae, String accession)
            throws Exception {
        return handoffMvc.perform(post("/api/v1/internal/dicom-ingest")
                .header("X-MUW-Dicom-Token", TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload(sopUid, ae, accession)));
    }

    /** One accepted hand-off from the calling AE title (null: an older receiver sends none); the new row's id. */
    private static long handoff(String ae) throws Exception {
        MvcResult r = send(UID_PREFIX + SEQ.incrementAndGet(), ae, null).andExpect(status().isCreated()).andReturn();
        return Long.parseLong(jsonOf(r, "imageIngestId"));
    }

    /** The sidecar's payload; {@code ae} null leaves the field out, as a receiver before this change does. */
    private static String payload(String sopUid, String ae, String accession) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("sopInstanceUid", sopUid);
        m.put("sopClassUid", "1.2.840.10008.5.1.4.1.1.77.1.5.1");
        m.put("studyInstanceUid", "1.2.3.4.5");
        m.put("seriesInstanceUid", "1.2.3.4.5.6");
        m.put("modality", "OP");
        m.put("patientId", "M-001");
        m.put("patientName", "M-001^");
        if (accession != null) m.put("accessionNumber", accession);
        m.put("studyDate", "2021-01-04");
        m.put("laterality", "OD");
        if (ae != null) m.put("sourceAeTitle", ae);
        m.put("dicomPath", "/tmp/it/" + sopUid + ".dcm");
        m.put("previewPngPath", "/tmp/it/" + sopUid + ".png");
        return tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(m);
    }

    private static String jsonOf(MvcResult r, String field) throws Exception {
        return tools.jackson.databind.json.JsonMapper.builder().build()
                .readTree(r.getResponse().getContentAsString()).get(field).asText();
    }

    /**
     * One image of one exam the cloud lists, pulled. {@code bind}: the exam is the one the patient sync made for
     * visit 3, so the image binds to it; otherwise its label names nobody and it lands unbound.
     */
    private static long remidioPull(String site, boolean bind) throws Exception {
        long exam = 930_000_000_000_000L + SEQ.incrementAndGet();
        long examMs = Instant.parse("2021-01-04T09:00:00Z").toEpochMilli();
        String imageId = "d2-im-" + exam;
        if (bind) {
            exec("DELETE FROM remidio_visit_exam WHERE study_event_id = 3");
            exec("INSERT INTO remidio_visit_exam (study_event_id, remidio_exam_id, exam_custom_id) VALUES (3, "
                    + exam + ", 'M-001 V3')");
        }
        String mrn = bind ? "M-001" : "D2-NOBODY";
        String listing = "[{\"patientDetails\":{\"mrn\":\"" + mrn + "\",\"firstName\":\"x\",\"lastName\":\"IT\"},"
                + "\"examDetails\":{\"id\":\"" + exam + "\",\"examDate\":" + examMs
                + ",\"deviceType\":[\"FOP\"],\"examState\":\"ACTIVE\"},"
                + "\"images\":{\"fopImages\":{\"STANDARD\":[{\"id\":\"" + imageId + "\",\"examId\":\"" + exam
                + "\",\"date\":" + examMs + ",\"laterality\":\"RIGHT\",\"deviceType\":\"FOP\","
                + "\"path\":\"https://storage.googleapis.com/b/" + imageId + ".jpg?X-Goog-Signature=s\"}]}}}]";
        RemidioGatewayClient.Settings settings = new RemidioGatewayClient.Settings(
                "https://remidio.example", "PACS_GATEWAY", "client-jwt", "bot@example.org", "pw", site);
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
            out.writeBytes(("d2 " + uri.getPath()).getBytes(StandardCharsets.US_ASCII));
            out.writeBytes(new byte[] {(byte) 0xFF, (byte) 0xD9});
            return new RemidioGatewayClient.Download(200, new ByteArrayInputStream(out.toByteArray()));
        };
        LocalDate day = LocalDate.of(2021, 1, 4);
        RemidioPullService.Summary s = new RemidioPullService(DATA_SOURCE,
                new RemidioGatewayClient(settings, wire, files)).pull(day, day);
        assertEquals(1, bind ? s.bound() : s.unbound(), s.line());
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT ingest_item_id FROM ingest_item WHERE remidio_image_id = ?")) {
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

    /* ====================================================================== */
    /* Database and log helpers                                                */
    /* ====================================================================== */

    /** A second active location, which only a multi-location deployment has: no default location any more. */
    private static int location(String code) throws Exception {
        LocationMode.set(LocationMode.MULTI);
        try (Connection c = DATA_SOURCE.getConnection()) {
            return LifecycleFixtures.insertOne(c, "INSERT INTO location (code, name) VALUES ('" + code
                    + "', 'Second place " + code + "') RETURNING location_id");
        }
    }

    private static long sourceId(String kind, String key) throws Exception {
        return Long.parseLong(text("SELECT ingest_source_id FROM ingest_source WHERE kind = '" + kind
                + "' AND source_key = '" + key + "'"));
    }

    private static Integer locationOfSource(long sourceId) throws Exception {
        String v = text("SELECT location_id FROM ingest_source WHERE ingest_source_id = " + sourceId);
        return v == null ? null : Integer.valueOf(v);
    }

    private static Long sourceOfItem(long itemId) throws Exception {
        String v = text("SELECT ingest_source_id FROM ingest_item WHERE ingest_item_id = " + itemId);
        return v == null ? null : Long.valueOf(v);
    }

    private static Integer originOf(long itemId) throws Exception {
        String v = text("SELECT origin_location_id FROM ingest_item WHERE ingest_item_id = " + itemId);
        return v == null ? null : Integer.valueOf(v);
    }

    /** The first column of the one row the query returns, as text; null for a SQL NULL. */
    private static String text(String sql) throws Exception {
        try (Connection c = DATA_SOURCE.getConnection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            assertTrue(rs.next(), "no row for: " + sql);
            return rs.getString(1);
        }
    }

    private static int count(String sql) throws Exception {
        return LifecycleFixtures.intQuery(sql);
    }

    private static void exec(String sql) throws Exception {
        try (Connection c = DATA_SOURCE.getConnection()) {
            exec(c, sql);
        }
    }

    private static void exec(Connection c, String sql) throws Exception {
        try (Statement s = c.createStatement()) {
            s.executeUpdate(sql);
        }
    }

    private static int countOf(String sql) {
        try {
            return count(sql);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Runs the work on {@code n} threads that start together; the results in caller order. */
    private static <T> List<T> inParallel(int n, java.util.function.IntFunction<T> work) throws Exception {
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(n);
        try {
            java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
            List<java.util.concurrent.Future<T>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) {
                int caller = i;
                futures.add(pool.submit(() -> {
                    go.await();
                    return work.apply(caller);
                }));
            }
            go.countDown();
            List<T> out = new java.util.ArrayList<>();
            for (java.util.concurrent.Future<T> f : futures) out.add(f.get(60, java.util.concurrent.TimeUnit.SECONDS));
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Captures what a class logs at WARN and above for the length of the block. The test configuration may set
     * the class's logger higher, so the level is set here and put back after.
     */
    private static final class LogCapture implements AutoCloseable {
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        private final ch.qos.logback.classic.Logger log;
        private final Level before;

        LogCapture(Class<?> logger) {
            log = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(logger);
            before = log.getLevel();
            log.setLevel(Level.WARN);
            appender.start();
            log.addAppender(appender);
        }

        @Override
        public void close() {
            log.detachAppender(appender);
            log.setLevel(before);
        }
    }
}
