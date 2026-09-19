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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.core.CoreResources;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.StudySubjectFinder;

/**
 * Characterisation IT for the DR-025 reconciliation inbox.
 *
 * <p>Pins the operator-facing contract before the Phase 3 unified-ingest
 * refactor folds this controller into one queue: what the inbox lists, the
 * role gate, one-click suggestions, bind/dismiss state transitions with
 * their audit rows, and — the 2026-09-17 hardening — that a preview of an
 * image already bound into another study is refused.
 */
@SuppressWarnings("null")
class ImageIngestApiControllerDatabaseIT extends AbstractApiControllerDatabaseIT {

    /** Store root the controller's path-confinement check resolves against. */
    @TempDir
    static Path STORE_ROOT;

    private static final int HIDDEN_STUDY_ID = 9101;
    private static final int HIDDEN_SUBJECT_ID = 9101;
    private static final int HIDDEN_STUDY_SUBJECT_ID = 9101;

    private static java.util.Properties SAVED_DATAINFO;

    @BeforeAll
    static void overrideStorePathAndSeedHiddenStudy() throws Exception {
        java.lang.reflect.Field f = CoreResources.class.getDeclaredField("DATAINFO");
        f.setAccessible(true);
        java.util.Properties live = (java.util.Properties) f.get(null);
        assertNotNull(live, "DATAINFO must be set by AbstractApiControllerDatabaseIT");
        SAVED_DATAINFO = new java.util.Properties();
        SAVED_DATAINFO.putAll(live);
        live.setProperty("core.dicom.ingest.storePath", STORE_ROOT.toString());

        // A second study the study-1 session must not be able to reach.
        try (Connection c = DATA_SOURCE.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO study (study_id, name, unique_identifier, oc_oid, "
                            + "type_id, status_id, owner_id, date_created, parent_study_id) "
                            + "VALUES (?, 'Hidden Ingest Study', 'hidden-ingest-study', 'S_HIDING', "
                            + "1, 1, 1, NOW(), NULL) ON CONFLICT (study_id) DO NOTHING")) {
                ps.setInt(1, HIDDEN_STUDY_ID);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO subject (subject_id, date_of_birth, dob_collected, gender, "
                            + "unique_identifier, status_id, date_created, owner_id) "
                            + "VALUES (?, '1970-01-01', true, 'f', 'hidden-ingest-pid', 1, NOW(), 1) "
                            + "ON CONFLICT (subject_id) DO NOTHING")) {
                ps.setInt(1, HIDDEN_SUBJECT_ID);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO study_subject (study_subject_id, label, subject_id, study_id, "
                            + "status_id, enrollment_date, date_created, owner_id, oc_oid) "
                            + "VALUES (?, 'HID-001', ?, ?, 1, NOW(), NOW(), 1, 'SS_HID001') "
                            + "ON CONFLICT (study_subject_id) DO NOTHING")) {
                ps.setInt(1, HIDDEN_STUDY_SUBJECT_ID);
                ps.setInt(2, HIDDEN_SUBJECT_ID);
                ps.setInt(3, HIDDEN_STUDY_ID);
                ps.executeUpdate();
            }
        }
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
    void cleanRows() throws Exception {
        try (Connection c = DATA_SOURCE.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM audit_log_event WHERE audit_table = 'image_ingest'")) {
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM image_ingest WHERE original_filename LIKE 'it-%'")) {
                ps.executeUpdate();
            }
        }
    }

    private MockMvc mockMvc() {
        ImageIngestApiController c = new ImageIngestApiController(
                DATA_SOURCE, new SiteVisibilityFilter(DATA_SOURCE), new StudySubjectFinder(DATA_SOURCE));
        return MockMvcBuilders.standaloneSetup(c)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    /** Data Manager on study 1, deliberately NOT a sysadmin so visibility guards apply. */
    private MockHttpSession dataManagerSession() {
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
        StudyUserRoleBean role = new StudyUserRoleBean();
        role.setRole(Role.STUDYDIRECTOR);
        s.setAttribute("userRole", role);
        return s;
    }

    private MockHttpSession monitorSession() {
        MockHttpSession s = dataManagerSession();
        StudyUserRoleBean role = new StudyUserRoleBean();
        role.setRole(Role.MONITOR);
        s.setAttribute("userRole", role);
        return s;
    }

    /** Insert an UNBOUND upload row; returns its id. A real PNG is written when {@code withPreview}. */
    private long seedUnbound(String patientId, String studyDate, boolean withPreview) throws Exception {
        String name = "it-" + System.nanoTime() + ".png";
        Path preview = STORE_ROOT.resolve(name);
        if (withPreview) {
            // 1x1 transparent PNG
            Files.write(preview, java.util.Base64.getDecoder().decode(
                    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk"
                            + "+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="));
        }
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO image_ingest (source_kind, content_type, stored_path, "
                             + "preview_png_path, original_filename, patient_id, laterality, "
                             + "study_date, received_at, status) "
                             + "VALUES ('upload', 'image/png', ?, ?, ?, ?, 'OD', ?::date, NOW(), 'UNBOUND') "
                             + "RETURNING image_ingest_id")) {
            ps.setString(1, preview.toString());
            ps.setString(2, withPreview ? preview.toString() : null);
            ps.setString(3, name);
            ps.setString(4, patientId);
            ps.setString(5, studyDate);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getLong(1);
            }
        }
    }

    private String statusOf(long id) throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT status FROM image_ingest WHERE image_ingest_id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getString(1);
            }
        }
    }

    private int auditCount(int typeId, long entityId) throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM audit_log_event WHERE audit_log_event_type_id = ? "
                             + "AND audit_table = 'image_ingest' AND entity_id = ?")) {
            ps.setInt(1, typeId);
            ps.setInt(2, (int) entityId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /* ---------------- guards ---------------- */

    @Test
    void inbox_withoutSession_is401() throws Exception {
        mockMvc().perform(get("/api/v1/image-ingest/inbox"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void inbox_asMonitor_is403() throws Exception {
        mockMvc().perform(get("/api/v1/image-ingest/inbox").session(monitorSession()))
                .andExpect(status().isForbidden());
    }

    /* ---------------- inbox ---------------- */

    /**
     * The inbox is the queue of images that have no visit yet. It is
     * deliberately cross-study (an unbound image belongs to no study), and
     * the one-click suggestion resolves the camera's PatientID against
     * visible subjects only.
     */
    @Test
    void inbox_listsUnboundRows_withSuggestionForAKnownLabel() throws Exception {
        long id = seedUnbound("M-001", "2021-01-04", true);
        mockMvc().perform(get("/api/v1/image-ingest/inbox").session(dataManagerSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.images[?(@.id == " + id + ")]").exists())
                .andExpect(jsonPath("$.images[?(@.id == " + id + ")].patientId").value("M-001"))
                .andExpect(jsonPath("$.images[?(@.id == " + id + ")].sourceKind").value("upload"))
                .andExpect(jsonPath("$.images[?(@.id == " + id + ")].hasPreview").value(true))
                .andExpect(jsonPath("$.images[?(@.id == " + id + ")].suggestion.subjectLabel")
                        .value("M-001"))
                .andExpect(jsonPath("$.images[?(@.id == " + id + ")].suggestion.studyEventId").value(3));
    }

    /** An unknown camera PatientID yields a row with no suggestion — never a wrong guess. */
    @Test
    void inbox_unknownPatientId_hasNoSuggestion() throws Exception {
        long id = seedUnbound("NOT-A-SUBJECT", "2021-01-04", false);
        String body = mockMvc().perform(get("/api/v1/image-ingest/inbox").session(dataManagerSession()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Asserted on the parsed body rather than with a JSONPath filter: a
        // filter over a present-but-null field still matches the row, so
        // `[?(@.suggestion)]` cannot express "has no suggestion".
        com.fasterxml.jackson.databind.JsonNode row = null;
        for (com.fasterxml.jackson.databind.JsonNode n :
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).get("images")) {
            if (n.get("id").asLong() == id) row = n;
        }
        assertNotNull(row, "the unbound row should be listed");
        assertEquals("NOT-A-SUBJECT", row.get("patientId").asText());
        assertTrue(row.get("suggestion").isNull(),
                "an unresolvable PatientID must not produce a bind suggestion");
    }

    /* ---------------- bind ---------------- */

    @Test
    void bind_setsBoundAndWritesAuditRow() throws Exception {
        long id = seedUnbound("M-001", "2021-01-04", true);
        mockMvc().perform(post("/api/v1/image-ingest/" + id + "/bind")
                .session(dataManagerSession())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"studySubjectId\":1,\"studyEventId\":3,\"eventCrfId\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BOUND"));

        assertEquals("BOUND", statusOf(id));
        assertEquals(1, auditCount(AuditTypeIds.IMAGE_BIND, id));
    }

    /** Binding is a one-way door; a second operator must not silently re-bind. */
    @Test
    void bind_twice_is409() throws Exception {
        long id = seedUnbound("M-001", "2021-01-04", false);
        String body = "{\"studySubjectId\":1,\"studyEventId\":3,\"eventCrfId\":3}";
        mockMvc().perform(post("/api/v1/image-ingest/" + id + "/bind")
                .session(dataManagerSession())
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        mockMvc().perform(post("/api/v1/image-ingest/" + id + "/bind")
                .session(dataManagerSession())
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
        assertEquals(1, auditCount(AuditTypeIds.IMAGE_BIND, id));
    }

    /** The bind target is visibility-checked, not just role-checked. */
    @Test
    void bind_toSubjectInInvisibleStudy_is403() throws Exception {
        long id = seedUnbound("HID-001", "2021-01-04", false);
        mockMvc().perform(post("/api/v1/image-ingest/" + id + "/bind")
                .session(dataManagerSession())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"studySubjectId\":" + HIDDEN_STUDY_SUBJECT_ID + "}"))
                .andExpect(status().isForbidden());
        assertEquals("UNBOUND", statusOf(id));
    }

    /* ---------------- dismiss ---------------- */

    @Test
    void dismiss_setsDismissedAndWritesAuditRow() throws Exception {
        long id = seedUnbound("NOT-A-SUBJECT", "2021-01-04", false);
        mockMvc().perform(post("/api/v1/image-ingest/" + id + "/dismiss")
                .session(dataManagerSession())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"device-local patient, not in the study\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISMISSED"));

        assertEquals("DISMISSED", statusOf(id));
        assertEquals(1, auditCount(AuditTypeIds.IMAGE_DISMISS, id));
    }

    /* ---------------- preview ---------------- */

    @Test
    void preview_forUnboundRow_streamsThePng() throws Exception {
        long id = seedUnbound("M-001", "2021-01-04", true);
        mockMvc().perform(get("/api/v1/image-ingest/" + id + "/preview")
                .session(dataManagerSession()))
                .andExpect(status().isOk());
    }

    @Test
    void preview_unknownId_is404() throws Exception {
        mockMvc().perform(get("/api/v1/image-ingest/999999999/preview")
                .session(dataManagerSession()))
                .andExpect(status().isNotFound());
    }

    /**
     * 2026-09-17 hardening: once an image is BOUND it is that subject's data.
     * A reconcile-role user in another study must not be able to enumerate ids
     * and pull previews — the same rule the OCT artifact stream enforces.
     */
    @Test
    void preview_forImageBoundIntoAnInvisibleStudy_is403() throws Exception {
        long id = seedUnbound("HID-001", "2021-01-04", true);
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE image_ingest SET status='BOUND', match_policy='manual', "
                             + "bound_study_subject_id=?, bound_at=NOW() WHERE image_ingest_id=?")) {
            ps.setInt(1, HIDDEN_STUDY_SUBJECT_ID);
            ps.setLong(2, id);
            ps.executeUpdate();
        }
        mockMvc().perform(get("/api/v1/image-ingest/" + id + "/preview")
                .session(dataManagerSession()))
                .andExpect(status().isForbidden());
    }

    /** A row with no preview file on disk reports 404 rather than a stack trace. */
    @Test
    void preview_whenFileMissing_is404() throws Exception {
        long id = seedUnbound("M-001", "2021-01-04", false);
        mockMvc().perform(get("/api/v1/image-ingest/" + id + "/preview")
                .session(dataManagerSession()))
                .andExpect(status().isNotFound());
    }
}
