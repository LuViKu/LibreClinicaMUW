/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import at.ac.meduniwien.ophthalmology.libreclinica.core.util.Json;
import at.ac.meduniwien.ophthalmology.libreclinica.testsupport.ProductionMvc;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.UserType;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.i18n.util.ResourceBundleProvider;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RemoteRetinalInferenceClient;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.StudySubjectFinder;

/**
 * DR-039 — a DICOM OCT volume takes the {@code .e2e} path: binding it starts
 * what the visit's imaging plan asks for, an operator can start one analysis
 * on it, the plan catch-up finds it, the inbox files it under "OCT scan", and
 * the plan editor lets tasks be set where DICOM arrives. A DICOM that is not
 * an OCT volume (a fundus photograph, or an OPT object whose frame count was
 * never recorded) is analysed nowhere.
 *
 * <p>No GPU host is configured, as on a single-host install: started jobs land
 * {@code queued} and nothing is dispatched.
 */
@SuppressWarnings("null")
class DicomOctVolumeDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final ObjectMapper JSON = Json.mapper();
    private static final String MARKER = "dicomoct-it-";
    private static final String DEVICE = "dicomoct-it-device";
    /** Default Study's SE_V2_DAY30. */
    private static final int SED_ID = 2;

    private static Path dir;

    @BeforeAll
    static void files() throws Exception {
        dir = Files.createTempDirectory("dicom-oct-it-");
    }

    @AfterEach
    void clean() throws Exception {
        exec("DELETE FROM event_definition_imaging WHERE study_event_definition_id = " + SED_ID);
        exec("DELETE FROM retinal_inference_job WHERE ingest_item_id IN "
                + "(SELECT ingest_item_id FROM ingest_item WHERE original_filename LIKE '" + MARKER + "%')");
        exec("DELETE FROM ingest_item WHERE original_filename LIKE '" + MARKER + "%'");
        exec("DELETE FROM imaging_modality WHERE code LIKE 'DICOMOCT_IT%'");
    }

    /* ------------------------------------------------------------------ */
    /* Fixtures                                                            */
    /* ------------------------------------------------------------------ */

    private static void exec(String sql) throws Exception {
        try (Connection c = DATA_SOURCE.getConnection()) {
            CrossSiteIsolationSupport.exec(c, sql);
        }
    }

    private static String text(String sql) throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static RetinalJobFollower follower() {
        RemoteRetinalInferenceClient remote = Mockito.mock(RemoteRetinalInferenceClient.class);
        Mockito.when(remote.isConfigured()).thenReturn(false);
        return new RetinalJobFollower(DATA_SOURCE, remote, Mockito.mock(RetinalInferenceApiController.class));
    }

    private static MockMvc inbox() {
        RemoteRetinalInferenceClient remote = Mockito.mock(RemoteRetinalInferenceClient.class);
        Mockito.when(remote.isConfigured()).thenReturn(false);
        return ProductionMvc.standalone(new IngestInboxApiController(DATA_SOURCE,
                        new SiteVisibilityFilter(DATA_SOURCE), new StudySubjectFinder(DATA_SOURCE), remote,
                        Mockito.mock(RetinalInferenceApiController.class)))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private static MockMvc eventDefinitions() {
        return ProductionMvc.standalone(new EventDefinitionsApiController(DATA_SOURCE))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private static StudyBean defaultStudy() {
        StudyBean study = new StudyBean();
        study.setId(1);
        study.setOid("S_DEFAULTS1");
        study.setName("Default Study");
        return study;
    }

    /** root as a study director: may reconcile and edit the study. */
    private static MockHttpSession director() {
        ResourceBundleProvider.updateLocale(Locale.ENGLISH);
        MockHttpSession s = new MockHttpSession();
        UserAccountBean ub = new UserAccountBean();
        ub.setId(1);
        ub.setName("root");
        ub.addUserType(UserType.SYSADMIN);
        s.setAttribute("userBean", ub);
        s.setAttribute("study", defaultStudy());
        StudyUserRoleBean r = new StudyUserRoleBean();
        r.setRole(Role.STUDYDIRECTOR);
        s.setAttribute("userRole", r);
        return s;
    }

    private static MockHttpSession investigator() {
        return ClinicalWriteFixtures.sessionAs(DATA_SOURCE, "manual_investigator");
    }

    private record Visit(int subject, int event) {}

    /** A fresh subject of Default Study with one SE_V2_DAY30 visit. */
    private static Visit visit() throws Exception {
        int n = SEQ.incrementAndGet();
        try (Connection c = DATA_SOURCE.getConnection()) {
            int ss = LifecycleFixtures.insertStudySubject(c, "DO-" + n + "-" + System.nanoTime() % 100000, 1, 1);
            int event = LifecycleFixtures.insertOne(c,
                    "INSERT INTO study_event (study_event_definition_id, study_subject_id, sample_ordinal, "
                            + "date_start, owner_id, status_id, date_created, subject_event_status_id, "
                            + "start_time_flag, end_time_flag) VALUES (" + SED_ID + ", " + ss + ", 1, now(), 1, 1, "
                            + "now(), 1, false, false) RETURNING study_event_id");
            return new Visit(ss, event);
        }
    }

    /** A catalogue entry of Default Study for {@link #DEVICE}. */
    private static int modality(String kindsAccepted) throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO imaging_modality (study_id, code, label_de, label_en, device, "
                             + "kinds_accepted, laterality_required, ordinal, status_id, created_by_user_id) "
                             + "VALUES (1, ?, 'DICOM-OCT', 'DICOM OCT', ?, ?, false, 90, 1, 1) "
                             + "RETURNING imaging_modality_id")) {
            ps.setString(1, "DICOMOCT_IT_" + SEQ.incrementAndGet());
            ps.setString(2, DEVICE);
            ps.setString(3, kindsAccepted);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /** SE_V2_DAY30's plan: this modality, with these tasks. */
    private static void plan(int modalityId, String tasks) throws Exception {
        exec("INSERT INTO event_definition_imaging (study_event_definition_id, imaging_modality_id, "
                + "requirement, laterality, retinal_tasks, created_by_user_id) VALUES (" + SED_ID + ", "
                + modalityId + ", 'optional', NULL, '" + tasks + "', 1)");
    }

    /**
     * A DICOM file of {@link #DEVICE} on disk, BOUND to {@code v} or UNBOUND.
     *
     * @param octVolume TRUE / FALSE / null as the describe sidecar classified it
     */
    private static long dicom(Boolean octVolume, Visit v) throws Exception {
        int n = SEQ.incrementAndGet();
        Path p = dir.resolve(java.util.UUID.randomUUID() + ".dcm");
        Files.write(p, ("dicom " + n).getBytes(StandardCharsets.UTF_8));
        String sha = String.format("e%063d", System.nanoTime() % 1_000_000_000_000L + n);
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO ingest_item (kind, source_kind, device, stored_path, original_filename, "
                             + "laterality, received_at, status, bound_study_subject_id, bound_study_event_id, "
                             + "sha256, byte_size, sop_class_uid, modality, oct_volume, manufacturer, "
                             + "manufacturer_model) "
                             + "VALUES ('dicom', 'upload', ?, ?, ?, 'OD', now(), ?, ?, ?, ?, 7, ?, ?, ?, "
                             + "'Heidelberg Engineering', 'SPECTRALIS') RETURNING ingest_item_id")) {
            ps.setString(1, DEVICE);
            ps.setString(2, p.toString());
            ps.setString(3, MARKER + n);
            ps.setString(4, v == null ? "UNBOUND" : "BOUND");
            if (v == null) {
                ps.setNull(5, java.sql.Types.INTEGER);
                ps.setNull(6, java.sql.Types.INTEGER);
            } else {
                ps.setInt(5, v.subject());
                ps.setInt(6, v.event());
            }
            ps.setString(7, sha);
            boolean oct = Boolean.TRUE.equals(octVolume);
            ps.setString(8, oct || octVolume == null
                    ? "1.2.840.10008.5.1.4.1.1.77.1.5.4" : "1.2.840.10008.5.1.4.1.1.77.1.5.1");
            ps.setString(9, oct || octVolume == null ? "OPT" : "OP");
            if (octVolume == null) ps.setNull(10, java.sql.Types.BOOLEAN);
            else ps.setBoolean(10, octVolume);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static List<String> tasksOf(long itemId) throws Exception {
        List<String> out = new ArrayList<>();
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT task FROM retinal_inference_job WHERE ingest_item_id = ? ORDER BY task")) {
            ps.setLong(1, itemId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getString(1));
            }
        }
        return out;
    }

    private record Answer(int status, JsonNode body) {}

    private static Answer send(MockMvc mvc,
                               org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder rb)
            throws Exception {
        MockHttpServletResponse r = mvc.perform(rb).andReturn().getResponse();
        String text = r.getContentAsString();
        return new Answer(r.getStatus(), text.isEmpty() ? JSON.createObjectNode() : JSON.readTree(text));
    }

    private static Answer bind(long itemId, Visit v) throws Exception {
        return send(inbox(), post("/api/v1/ingest/" + itemId + "/bind")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"studySubjectId\":" + v.subject() + ",\"studyEventId\":" + v.event() + "}")
                .session(director()));
    }

    private static Answer start(long itemId, String task) throws Exception {
        return send(inbox(), post("/api/v1/ingest/" + itemId + "/analyses")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"task\":\"" + task + "\"}")
                .session(investigator()));
    }

    /* ------------------------------------------------------------------ */
    /* Bind                                                                */
    /* ------------------------------------------------------------------ */

    @Test
    void bindingADicomOctVolumeEnqueuesThePlansTasks() throws Exception {
        int modalityId = modality("dicom,oct");
        plan(modalityId, "fluid,ga");
        Visit v = visit();
        long item = dicom(true, null);

        Answer a = bind(item, v);

        assertEquals(200, a.status(), a.body().toString());
        assertEquals(List.of("fluid", "ga"), tasksOf(item), "the plan's tasks, on the scan");
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT status, study_event_id, e2e_path, e2e_sha256 FROM retinal_inference_job "
                             + " WHERE ingest_item_id = ?")) {
            ps.setLong(1, item);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    assertEquals("queued", rs.getString("status"));
                    assertEquals(v.event(), rs.getInt("study_event_id"));
                    assertTrue(rs.getString("e2e_path").endsWith(".dcm"), "the job reads the stored DICOM");
                    assertNotNull(rs.getString("e2e_sha256"));
                }
            }
        }
        assertEquals(modalityId, LifecycleFixtures.intQuery(
                "SELECT COALESCE(imaging_modality_id, -1) FROM ingest_item WHERE ingest_item_id = " + item),
                "an unclassified volume is filed under its device's entry");
    }

    @Test
    void bindingAFundusDicomStartsNothing() throws Exception {
        plan(modality("dicom,oct"), "fluid");
        Visit v = visit();
        long item = dicom(false, null);

        assertEquals(200, bind(item, v).status());
        assertEquals(List.of(), tasksOf(item));
    }

    @Test
    void bindingAnUnclassifiedOptDicomStartsNothing() throws Exception {
        plan(modality("dicom,oct"), "fluid");
        Visit v = visit();
        long item = dicom(null, null);

        assertEquals(200, bind(item, v).status());
        assertEquals(List.of(), tasksOf(item), "an OPT object of unknown frame count may be a line scan");
    }

    /* ------------------------------------------------------------------ */
    /* Per-scan start                                                      */
    /* ------------------------------------------------------------------ */

    @Test
    void aDicomOctVolumeCanBeAnalysedByHand() throws Exception {
        long item = dicom(true, visit());
        Answer a = start(item, "layers");
        assertEquals(202, a.status(), a.body().toString());
        assertEquals(List.of("layers"), tasksOf(item));
    }

    @Test
    void aFundusDicomIsNotAnalysable() throws Exception {
        long item = dicom(false, visit());
        Answer a = start(item, "fluid");
        assertEquals(409, a.status(), a.body().toString());
        assertEquals("NOT_ANALYSABLE", a.body().get("code").asText());
        assertEquals(List.of(), tasksOf(item));
    }

    @Test
    void anUnclassifiedOptDicomIsNotAnalysable() throws Exception {
        long item = dicom(null, visit());
        Answer a = start(item, "fluid");
        assertEquals(409, a.status(), a.body().toString());
        assertEquals("NOT_ANALYSABLE", a.body().get("code").asText());
    }

    /* ------------------------------------------------------------------ */
    /* Plan catch-up                                                       */
    /* ------------------------------------------------------------------ */

    @Test
    void thePlanCatchUpIncludesDicomOctVolumes() throws Exception {
        long volume = dicom(true, visit());
        long fundus = dicom(false, visit());
        plan(modality("dicom,oct"), "fluid");

        RetinalJobFollower.CatchUp r = follower().catchUp(SED_ID,
                new IngestBindService.Actor(null, defaultStudy()), false);

        assertTrue(r.scans() >= 1, "the DICOM volume is one of the scans: " + r);
        assertEquals(0, r.failed(), r.toString());
        assertEquals(List.of("fluid"), tasksOf(volume));
        assertEquals(List.of(), tasksOf(fundus), "a fundus photograph is not a scan to catch up");
    }

    /* ------------------------------------------------------------------ */
    /* Plan editor                                                         */
    /* ------------------------------------------------------------------ */

    private static Answer putPlan(int modalityId, String tasksJson) throws Exception {
        return send(eventDefinitions(), put("/api/v1/studies/S_DEFAULTS1/event-definitions/" + SED_ID
                        + "/imaging-plan")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"entries\":[{\"modalityId\":" + modalityId
                        + ",\"requirement\":\"optional\",\"tasks\":" + tasksJson + "}]}")
                .session(director()));
    }

    @Test
    void thePlanEditorAcceptsTasksOnAModalityMarkedOctThatReceivesDicom() throws Exception {
        int dicomOct = modality("dicom,oct");
        Answer a = putPlan(dicomOct, "[\"fluid\"]");
        assertEquals(200, a.status(), a.body().toString());
        assertEquals("fluid", text(
                "SELECT retinal_tasks FROM event_definition_imaging WHERE study_event_definition_id = "
                        + SED_ID + " AND imaging_modality_id = " + dicomOct));
    }

    /** DR-039 — a fundus camera that also exports DICOM is not an OCT modality. */
    @Test
    void thePlanEditorRefusesTasksOnADicomModalityWithoutTheOctMarker() throws Exception {
        Answer a = putPlan(modality("dicom,image"), "[\"fluid\"]");
        assertEquals(400, a.status(), a.body().toString());
    }

    @Test
    void thePlanEditorStillRefusesTasksWhereNoOctVolumeCanArrive() throws Exception {
        int imageOnly = modality("image");
        Answer a = putPlan(imageOnly, "[\"fluid\"]");
        assertEquals(400, a.status(), a.body().toString());
    }

    /** A DICOM OCT volume is not filed under a marked modality that does not take DICOM. */
    @Test
    void aDicomVolumeIsNotFiledUnderAnE2eOnlyOctModality() throws Exception {
        plan(modality("e2e,oct"), "fluid");
        Visit v = visit();
        long item = dicom(true, null);
        assertEquals(200, bind(item, v).status());
        assertEquals(List.of(), tasksOf(item));
    }

    /**
     * The backfill: every modality that accepted e2e — the only kind tasks
     * could be set on before DR-039 — carries the marker, so its plan keeps
     * its tasks. Run against a modality created the way the old catalogue
     * did, with the changeset's own statement.
     */
    @Test
    void theBackfillMarksE2eModalitiesSoTheirPlansKeepTheirTasks() throws Exception {
        assertEquals(0, LifecycleFixtures.intQuery("SELECT count(*) FROM imaging_modality "
                + " WHERE (',' || kinds_accepted || ',') LIKE '%,e2e,%' "
                + "   AND (',' || kinds_accepted || ',') NOT LIKE '%,oct,%'"),
                "every seeded e2e modality was marked by the migration");

        int legacy = modality("e2e");
        int fundus = modality("dicom,image");
        plan(legacy, "fluid,layers");
        exec(backfillStatement());
        assertEquals("e2e,oct", text("SELECT kinds_accepted FROM imaging_modality WHERE imaging_modality_id = " + legacy));
        assertEquals("dicom,image", text("SELECT kinds_accepted FROM imaging_modality WHERE imaging_modality_id = " + fundus));
        Answer a = putPlan(legacy, "[\"fluid\",\"layers\"]");
        assertEquals(200, a.status(), "the existing plan saves unchanged: " + a.body());
    }

    /** The UPDATE of lc-muw-2026-10-09-imaging-modality-oct-marker.xml, as Liquibase runs it. */
    private static String backfillStatement() throws Exception {
        try (var in = DicomOctVolumeDatabaseIT.class.getResourceAsStream(
                "/migration/lc-muw-2026-10-09-imaging-modality-oct-marker.xml")) {
            assertNotNull(in, "the changeset is on the classpath");
            String xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            int start = xml.indexOf("<sql>") + "<sql>".length();
            return xml.substring(start, xml.indexOf("</sql>", start)).trim()
                    .replace("&lt;", "<").replace("&gt;", ">");
        }
    }

    /* ------------------------------------------------------------------ */
    /* Inbox                                                               */
    /* ------------------------------------------------------------------ */

    @Test
    void theInboxShowsADicomOctVolumeAsAnOctScan() throws Exception {
        long volume = dicom(true, null);
        long fundus = dicom(false, null);

        JsonNode oct = send(inbox(), get("/api/v1/ingest/inbox").param("kind", "e2e")
                .param("limit", "500").session(director())).body().get("items");
        JsonNode row = null;
        for (JsonNode n : oct) {
            assertFalse(n.get("id").asLong() == fundus, "a fundus DICOM is not an OCT scan");
            if (n.get("id").asLong() == volume) row = n;
        }
        assertNotNull(row, "the OCT-scan filter includes the DICOM volume: " + oct);
        assertEquals("dicom", row.get("kind").asText(), "its kind stays what the file is");
        assertTrue(row.get("octVolume").asBoolean());
        assertTrue(row.get("analysable").asBoolean());
        assertEquals("Heidelberg Engineering", row.get("manufacturer").asText());

        JsonNode images = send(inbox(), get("/api/v1/ingest/inbox").param("kind", "dicom")
                .param("limit", "500").session(director())).body().get("items");
        boolean sawFundus = false;
        for (JsonNode n : images) {
            assertFalse(n.get("id").asLong() == volume, "the DICOM-image filter leaves the volume out");
            if (n.get("id").asLong() == fundus) sawFundus = true;
        }
        assertTrue(sawFundus);

        JsonNode counts = send(inbox(), get("/api/v1/ingest/inbox/counts").session(director())).body();
        assertTrue(counts.path("byKind").path("e2e").asInt() >= 1, counts.toString());
    }
}
