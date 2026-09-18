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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import at.ac.meduniwien.ophthalmology.libreclinica.dao.core.CoreResources;

/**
 * Characterisation IT for the DR-025 sidecar → app ingest handoff.
 *
 * <p>Pins the contract the {@code dicom-scp} sidecar depends on, written
 * before the Phase 3 unified-ingest refactor: the shared-secret gate, the
 * worklist accession auto-bind ({@code LC<study_event_id>} → BOUND with
 * {@code match_policy='worklist'}), and C-STORE idempotency on
 * {@code sop_instance_uid} (a modality that re-sends an object must not
 * create a second inbox row).
 *
 * <p>Seeded fixture used: study_event 3 = M-001 / V3 Day 90 / 2021-01-04,
 * status 3, whose first live event_crf is 3 and whose study_subject is 1.
 */
@SuppressWarnings("null")
class DicomIngestApiControllerDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static final String TOKEN = "ingest-it-token";
    /** Every SOP UID this IT writes starts with this so cleanup is exact. */
    private static final String UID_PREFIX = "1.2.826.0.1.3680043.9.7890.IT.";

    private static java.util.Properties SAVED_DATAINFO;

    @BeforeAll
    static void overrideToken() throws Exception {
        java.lang.reflect.Field f = CoreResources.class.getDeclaredField("DATAINFO");
        f.setAccessible(true);
        java.util.Properties live = (java.util.Properties) f.get(null);
        assertNotNull(live, "DATAINFO must be set by AbstractApiControllerDatabaseIT");
        SAVED_DATAINFO = new java.util.Properties();
        SAVED_DATAINFO.putAll(live);
        live.setProperty("core.dicom.ingest.token", TOKEN);
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
    void cleanIngestRows() throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM ingest_item WHERE sop_instance_uid LIKE ?")) {
            ps.setString(1, UID_PREFIX + "%");
            ps.executeUpdate();
        }
    }

    private MockMvc mockMvc() {
        return MockMvcBuilders.standaloneSetup(new DicomIngestApiController(DATA_SOURCE))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    /** Minimal sidecar payload; {@code accession} may be null. */
    private static String payload(String sopUid, String accession) {
        return "{"
                + "\"sopInstanceUid\":\"" + sopUid + "\","
                + "\"sopClassUid\":\"1.2.840.10008.5.1.4.1.1.77.1.5.1\","
                + "\"studyInstanceUid\":\"1.2.3.4.5\","
                + "\"seriesInstanceUid\":\"1.2.3.4.5.6\","
                + "\"modality\":\"OP\","
                + "\"patientId\":\"M-001\","
                + "\"patientName\":\"M-001^\","
                + (accession == null ? "" : "\"accessionNumber\":\"" + accession + "\",")
                + "\"studyDate\":\"2021-01-04\","
                + "\"laterality\":\"OD\","
                + "\"sourceAeTitle\":\"OPTOMEDLUMO\","
                + "\"dicomPath\":\"/tmp/it/" + sopUid + ".dcm\","
                + "\"previewPngPath\":\"/tmp/it/" + sopUid + ".png\""
                + "}";
    }

    private record Row(long id, String status, String matchPolicy, Integer ss, Integer se,
                       Integer ecrf, boolean hasBoundAt, Integer boundBy) {}

    private Row readRow(String sopUid) throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT ingest_item_id, status, match_policy, bound_study_subject_id, "
                             + "bound_study_event_id, bound_event_crf_id, bound_at, bound_by_user_id, "
                             + "source_kind, content_type "
                             + "FROM ingest_item WHERE sop_instance_uid = ?")) {
            ps.setString(1, sopUid);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "expected an ingest_item row for " + sopUid);
                assertEquals("dicom", rs.getString("source_kind"));
                assertEquals("application/dicom", rs.getString("content_type"));
                int ss = rs.getInt("bound_study_subject_id");
                Integer ssV = rs.wasNull() ? null : ss;
                int se = rs.getInt("bound_study_event_id");
                Integer seV = rs.wasNull() ? null : se;
                int ec = rs.getInt("bound_event_crf_id");
                Integer ecV = rs.wasNull() ? null : ec;
                int by = rs.getInt("bound_by_user_id");
                Integer byV = rs.wasNull() ? null : by;
                return new Row(rs.getLong("ingest_item_id"), rs.getString("status"),
                        rs.getString("match_policy"), ssV, seV, ecV,
                        rs.getTimestamp("bound_at") != null, byV);
            }
        }
    }

    private int countRows(String sopUid) throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM ingest_item WHERE sop_instance_uid = ?")) {
            ps.setString(1, sopUid);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /* ---------------- token gate ---------------- */

    @Test
    void ingest_withoutToken_is401() throws Exception {
        mockMvc().perform(post("/api/v1/internal/dicom-ingest")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload(UID_PREFIX + "401", null)))
                .andExpect(status().isUnauthorized());
        assertEquals(0, countRows(UID_PREFIX + "401"), "a rejected ingest must not write a row");
    }

    @Test
    void ingest_whenTokenNotConfigured_is503() throws Exception {
        java.lang.reflect.Field f = CoreResources.class.getDeclaredField("DATAINFO");
        f.setAccessible(true);
        java.util.Properties live = (java.util.Properties) f.get(null);
        live.setProperty("core.dicom.ingest.token", "");
        try {
            mockMvc().perform(post("/api/v1/internal/dicom-ingest")
                    .header("X-MUW-Dicom-Token", TOKEN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(payload(UID_PREFIX + "503", null)))
                    .andExpect(status().isServiceUnavailable());
        } finally {
            live.setProperty("core.dicom.ingest.token", TOKEN);
        }
    }

    @Test
    void ingest_missingRequiredFields_is400() throws Exception {
        mockMvc().perform(post("/api/v1/internal/dicom-ingest")
                .header("X-MUW-Dicom-Token", TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sopInstanceUid\":\"\",\"dicomPath\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    /* ---------------- binding behaviour ---------------- */

    /**
     * The core DR-025 promise: a study that answers one of our worklist items
     * comes back carrying accession {@code LC<study_event_id>} and lands
     * already bound to that visit — no inbox step for the operator.
     */
    @Test
    void ingest_withWorklistAccession_landsBoundToTheVisit() throws Exception {
        String uid = UID_PREFIX + "worklist";
        mockMvc().perform(post("/api/v1/internal/dicom-ingest")
                .header("X-MUW-Dicom-Token", TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload(uid, "LC3")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("BOUND"))
                .andExpect(jsonPath("$.imageIngestId").isNumber());

        Row row = readRow(uid);
        assertEquals("BOUND", row.status());
        assertEquals("worklist", row.matchPolicy());
        assertEquals(1, row.ss(), "study_event 3 belongs to study_subject 1 (M-001)");
        assertEquals(3, row.se());
        assertEquals(3, row.ecrf(), "first live event_crf of study_event 3");
        assertTrue(row.hasBoundAt());
        assertNull(row.boundBy(), "a system bind has no operator");
    }

    /** An accession we did not issue is data, not a binding instruction. */
    @Test
    void ingest_withForeignAccession_landsUnbound() throws Exception {
        String uid = UID_PREFIX + "foreign";
        mockMvc().perform(post("/api/v1/internal/dicom-ingest")
                .header("X-MUW-Dicom-Token", TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload(uid, "729555114864694")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("UNBOUND"));

        Row row = readRow(uid);
        assertEquals("UNBOUND", row.status());
        assertNull(row.matchPolicy());
        assertNull(row.ss());
        assertNull(row.se());
    }

    /** An accession shaped like ours but pointing at no live visit stays unbound. */
    @Test
    void ingest_withUnknownStudyEventAccession_landsUnbound() throws Exception {
        String uid = UID_PREFIX + "ghost";
        mockMvc().perform(post("/api/v1/internal/dicom-ingest")
                .header("X-MUW-Dicom-Token", TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload(uid, "LC999999")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("UNBOUND"));
        assertEquals("UNBOUND", readRow(uid).status());
    }

    /* ---------------- idempotency ---------------- */

    /**
     * The Lumo re-sends its whole backlog whenever a storage target answers,
     * so a repeated C-STORE of the same object must be a no-op.
     */
    @Test
    void ingest_sameSopInstanceUidTwice_isIdempotent() throws Exception {
        String uid = UID_PREFIX + "dup";
        String body = payload(uid, "LC3");

        mockMvc().perform(post("/api/v1/internal/dicom-ingest")
                .header("X-MUW-Dicom-Token", TOKEN)
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        long firstId = readRow(uid).id();

        mockMvc().perform(post("/api/v1/internal/dicom-ingest")
                .header("X-MUW-Dicom-Token", TOKEN)
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(true))
                .andExpect(jsonPath("$.imageIngestId").value(firstId));

        assertEquals(1, countRows(uid), "a re-sent C-STORE must not duplicate the row");
    }

    /** Camera-provided strings must never reach the log (CodeQL log-injection sink). */
    @Test
    void ingest_doesNotLogCameraStrings() throws Exception {
        // Guard by construction: assert the source carries no interpolation of
        // the request's free-text fields into a log statement.
        java.nio.file.Path src = java.nio.file.Path.of(
                "src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/controller/api/"
                        + "DicomIngestApiController.java");
        if (!java.nio.file.Files.exists(src)) return; // module layout differs when run from the root
        String text = java.nio.file.Files.readString(src);
        for (String forbidden : new String[]{"r.patientId()", "r.patientName()", "req.patientId()",
                "req.patientName()", "r.accessionNumber()", "r.sourceAeTitle()"}) {
            for (String line : text.split("\n")) {
                if (line.contains("LOG.") && line.contains(forbidden)) {
                    throw new AssertionError("camera-provided value logged: " + line.trim());
                }
            }
        }
    }

    /** Sanity: the fixture this IT leans on is really what the seed produced. */
    @Test
    void seededFixture_studyEvent3_isTheExpectedVisit() throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT se.study_subject_id, se.subject_event_status_id, ss.label "
                             + "FROM study_event se JOIN study_subject ss "
                             + "  ON ss.study_subject_id = se.study_subject_id "
                             + "WHERE se.study_event_id = 3")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt("study_subject_id"));
            assertEquals(3, rs.getInt("subject_event_status_id"));
            assertEquals("M-001", rs.getString("label"));
        }
    }
}
