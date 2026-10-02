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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.ac.meduniwien.ophthalmology.libreclinica.dao.core.CoreResources;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.IngestArtifactStore;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.remidio.RemidioGatewayClient;

/**
 * DR-031 — the Remidio pull loop against a real database and a scripted
 * cloud: what it files, what it binds, and when it looks at an exam again.
 *
 * <p>The case that made this test: the patient sync creates an exam for a
 * scheduled visit, the pull lists it seconds later with no images, and the
 * capture the photographer makes into it afterwards must still arrive. Until
 * 2026-09-28 the pull recorded such an exam as done on first sight and never
 * opened it again.
 *
 * <p>Seeded fixture used: study_event 3 = M-001 / V3 Day 90 / 2021-01-04,
 * whose first live event_crf is 3 and whose study_subject is 1. Every exam
 * here is mapped to that visit the way the patient sync maps it, so every
 * filed image binds by {@code worklist}.
 */
@SuppressWarnings("null")
class RemidioPullServiceDatabaseIT extends AbstractApiControllerDatabaseIT {

    @TempDir
    static Path STORE_ROOT;

    private static java.util.Properties SAVED_DATAINFO;

    private static final int VISIT = 3;
    private static final int SUBJECT = 1;
    private static final LocalDate DAY = LocalDate.of(2021, 1, 4);
    private static final long EXAM_MS = Instant.parse("2021-01-04T09:00:00Z").toEpochMilli();
    private static final String SITE = "it_site";

    private static final RemidioGatewayClient.Settings SETTINGS = new RemidioGatewayClient.Settings(
            "https://remidio.example", "PACS_GATEWAY", "client-jwt", "bot@example.org", "pw", SITE);

    @BeforeAll
    static void pointTheStoreAtATempDir() throws Exception {
        java.lang.reflect.Field f = CoreResources.class.getDeclaredField("DATAINFO");
        f.setAccessible(true);
        java.util.Properties live = (java.util.Properties) f.get(null);
        assertNotNull(live, "DATAINFO must be set by AbstractApiControllerDatabaseIT");
        SAVED_DATAINFO = new java.util.Properties();
        SAVED_DATAINFO.putAll(live);
        live.setProperty(IngestArtifactStore.CONFIG_KEY_STORE_PATH, STORE_ROOT.toString());
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
    void cleanRemidioRows() throws Exception {
        try (Connection c = DATA_SOURCE.getConnection()) {
            exec(c, "DELETE FROM ingest_item WHERE source_kind = 'remidio'");
            exec(c, "DELETE FROM remidio_exam WHERE site_custom_id = '" + SITE + "'");
            exec(c, "DELETE FROM remidio_visit_exam WHERE study_event_id = " + VISIT);
        }
    }

    /* ------------------------------------------------------------------ */
    /* the cloud, scripted                                                 */
    /* ------------------------------------------------------------------ */

    /** A listing the test changes between passes, and every download it was asked for. */
    private static final class FakeCloud {
        volatile String listing = "[]";
        final List<URI> downloads = new CopyOnWriteArrayList<>();

        RemidioGatewayClient client() {
            RemidioGatewayClient.Transport wire = (method, uri, _, _, _) -> {
                String p = uri.getPath();
                if (p.endsWith("/api/user/loginUser")) return new RemidioGatewayClient.Response(200, ok("\"bearer\""));
                if (p.endsWith("/api/gateway/getAuthToken")) return new RemidioGatewayClient.Response(200, ok("\"cat\""));
                if (p.contains("/api/gateway/getExamsByDate/")) return new RemidioGatewayClient.Response(200, ok(listing));
                throw new IllegalStateException("unexpected call " + method + " " + p);
            };
            RemidioGatewayClient.Downloader files = (uri, _) -> {
                downloads.add(uri);
                return new RemidioGatewayClient.Download(200, new ByteArrayInputStream(jpeg(uri.getPath())));
            };
            return new RemidioGatewayClient(SETTINGS, wire, files);
        }
    }

    private static String ok(String dataJson) {
        return "{\"status\":{\"statusCode\":\"OK\",\"message\":\"HTTP Status - OK\"},\"data\":" + dataJson + "}";
    }

    /** JPEG magic, then bytes unique to the image so no two share a sha256. */
    private static byte[] jpeg(String seed) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0});
        out.writeBytes(("IT " + seed).getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(new byte[] {(byte) 0xFF, (byte) 0xD9});
        return out.toByteArray();
    }

    /** One image of the listing; {@code withLink=false} is an entry the phone has not uploaded yet. */
    private static String image(long examId, String imageId, String laterality, boolean withLink) {
        return "{\"id\":\"" + imageId + "\",\"examId\":\"" + examId + "\",\"date\":" + EXAM_MS
                + ",\"laterality\":\"" + laterality + "\",\"deviceType\":\"FOP\""
                + (withLink ? ",\"path\":\"https://storage.googleapis.com/b/" + imageId + ".jpg?X-Goog-Signature=s\"" : "")
                + "}";
    }

    /** One exam for M-001 on the visit day, holding the given STANDARD images. */
    private static String listing(long examId, String... images) {
        return "[{"
                + "\"patientDetails\":{\"mrn\":\"M-001\",\"firstName\":\"M-001\",\"lastName\":\"IT\"},"
                + "\"examDetails\":{\"id\":\"" + examId + "\",\"examDate\":" + EXAM_MS
                + ",\"deviceType\":[\"FOP\"],\"examState\":\"ACTIVE\"},"
                + "\"images\":{\"fopImages\":{\"STANDARD\":[" + String.join(",", images) + "]}}"
                + "}]";
    }

    /* ------------------------------------------------------------------ */
    /* database helpers                                                    */
    /* ------------------------------------------------------------------ */

    private static void exec(Connection c, String sql) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }

    /** The mapping the patient sync writes when it creates an exam for a visit. */
    private static void syncedExam(long examId) throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO remidio_visit_exam (study_event_id, remidio_exam_id, exam_custom_id) "
                             + "VALUES (?, ?, 'M-001 V3')")) {
            ps.setInt(1, VISIT);
            ps.setLong(2, examId);
            ps.executeUpdate();
        }
    }

    private static Integer handledCount(long examId) throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT image_count FROM remidio_exam WHERE remidio_exam_id = ?")) {
            ps.setString(1, Long.toString(examId));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : null;
            }
        }
    }

    /** "imageId laterality subject visit policy" per filed image, in image-id order. */
    private static List<String> filed() throws Exception {
        List<String> out = new java.util.ArrayList<>();
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT remidio_image_id, laterality, bound_study_subject_id, bound_study_event_id, "
                             + "match_policy FROM ingest_item WHERE source_kind = 'remidio' "
                             + "ORDER BY remidio_image_id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(rs.getString(1) + " " + rs.getString(2) + " " + rs.getObject(3) + " "
                        + rs.getObject(4) + " " + rs.getString(5));
            }
        }
        return out;
    }

    private static RemidioPullService.Summary pass(FakeCloud cloud) throws Exception {
        return new RemidioPullService(DATA_SOURCE, cloud.client()).pull(DAY, DAY);
    }

    /* ------------------------------------------------------------------ */
    /* cases                                                               */
    /* ------------------------------------------------------------------ */

    /**
     * The production sequence of 2026-09-28: the sync creates the exam, the
     * pull lists it empty, the photographer shoots into it later.
     */
    @Test
    void aCaptureIntoAnExamFirstListedEmptyArrivesAndBindsToItsVisit() throws Exception {
        long exam = 910_000_000_000_001L;
        syncedExam(exam);
        FakeCloud cloud = new FakeCloud();

        cloud.listing = listing(exam);
        RemidioPullService.Summary first = pass(cloud);
        assertEquals(1, first.newExams());
        assertEquals(0, first.images());
        assertEquals(0, handledCount(exam), "handled, at zero images");

        cloud.listing = listing(exam, image(exam, "im-a", "RIGHT", true), image(exam, "im-b", "LEFT", true));
        RemidioPullService.Summary second = pass(cloud);
        assertEquals(1, second.reopened(), "more images than handled reopens the exam: " + second.line());
        assertEquals(2, second.bound(), second.line());
        assertEquals(List.of(
                "im-a OD " + SUBJECT + " " + VISIT + " worklist",
                "im-b OS " + SUBJECT + " " + VISIT + " worklist"), filed());
        assertEquals(2, handledCount(exam));

        RemidioPullService.Summary third = pass(cloud);
        assertEquals(0, third.newExams() + third.reopened(), "nothing new, the exam stays closed");
        assertEquals(2, cloud.downloads.size(), "and nothing is downloaded twice");
    }

    /** A sitting uploaded image by image: the second eye comes a poll after the first. */
    @Test
    void anImageUploadedAfterTheFirstIsFiledWithoutRefetchingTheFirst() throws Exception {
        long exam = 910_000_000_000_002L;
        syncedExam(exam);
        FakeCloud cloud = new FakeCloud();

        cloud.listing = listing(exam, image(exam, "im-a", "RIGHT", true));
        assertEquals(1, pass(cloud).bound());

        cloud.listing = listing(exam, image(exam, "im-a", "RIGHT", true), image(exam, "im-b", "LEFT", true));
        RemidioPullService.Summary second = pass(cloud);
        assertEquals(1, second.reopened());
        assertEquals(1, second.duplicates(), "the first image is recognised by its id: " + second.line());
        assertEquals(1, second.bound());
        assertEquals(2, cloud.downloads.size(), "the first image is not fetched again");
        assertEquals(2, filed().size());
    }

    /** The state the pre-fix pull left in production: an exam recorded as handled with 0 images. */
    @Test
    void anExamTheOldRuleClosedAtZeroReopensByItself() throws Exception {
        long exam = 910_000_000_000_003L;
        syncedExam(exam);
        try (Connection c = DATA_SOURCE.getConnection()) {
            exec(c, "INSERT INTO remidio_exam (remidio_exam_id, site_custom_id, exam_date, image_count) "
                    + "VALUES ('" + exam + "', '" + SITE + "', TIMESTAMP '2021-01-04 10:00', 0)");
        }
        FakeCloud cloud = new FakeCloud();
        cloud.listing = listing(exam, image(exam, "im-a", "RIGHT", true));

        RemidioPullService.Summary s = pass(cloud);

        assertEquals(1, s.reopened(), s.line());
        assertEquals(List.of("im-a OD " + SUBJECT + " " + VISIT + " worklist"), filed());
    }

    /** An entry listed before its file is uploaded must not close the exam. */
    @Test
    void anImageListedBeforeItsLinkExistsIsFiledOnceTheLinkAppears() throws Exception {
        long exam = 910_000_000_000_004L;
        syncedExam(exam);
        FakeCloud cloud = new FakeCloud();

        cloud.listing = listing(exam, image(exam, "im-a", "RIGHT", false));
        RemidioPullService.Summary first = pass(cloud);
        assertEquals(1, first.skipped(), first.line());
        assertEquals(0, handledCount(exam), "a linkless entry is not counted as handled");

        cloud.listing = listing(exam, image(exam, "im-a", "RIGHT", true));
        RemidioPullService.Summary second = pass(cloud);
        assertEquals(1, second.reopened(), second.line());
        assertEquals(1, second.bound());
        assertEquals(1, handledCount(exam));
    }
}
