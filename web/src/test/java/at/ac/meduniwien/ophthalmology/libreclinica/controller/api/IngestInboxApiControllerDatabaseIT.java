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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.UserType;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.StudySubjectFinder;

/**
 * P3.2 — one inbox for everything that arrives.
 *
 * <p>The behaviour worth pinning is not the listing. It is what a bind
 * <em>causes</em> and what undoing one <em>undoes</em>: binding a file ticks
 * the visit's "this modality was performed" box, because the file is the
 * evidence, and an unbind that left that tick standing would leave the form
 * asserting something with nothing behind it — while the person who corrected
 * the mis-bind had no reason to look.
 *
 * <p>The repoint case is the subtle one. Two images from the same camera on one
 * visit, one unbound: the box is still true, so the value stays and its
 * provenance moves to the image that remains.
 */
@SuppressWarnings("null")
class IngestInboxApiControllerDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static final String MARKER = "inbox-it-";

    /** Seeded: study_event 3 of study 1, its first live event_crf, crf_version 1. */
    private static final int EVENT_CRF_ID = 3;
    private static final int STUDY_EVENT_ID = 3;
    private static final int STUDY_SUBJECT_ID = 1;

    /** An item the demo seed leaves empty on this event_crf. */
    private static final int ITEM_ID = 5;
    private static final String ITEM_OID = "I_BLOOD_PRESSURE_SYS";
    private static final String DEVICE = "inbox-it-camera";

    @AfterEach
    void cleanRows() throws Exception {
        exec("DELETE FROM audit_log_event WHERE audit_table IN ('ingest_item', 'item_data') "
                + "AND audit_log_event_type_id IN (127, 128, 129, 130, 137, 141)");
        exec("DELETE FROM item_data WHERE event_crf_id = " + EVENT_CRF_ID + " AND item_id = " + ITEM_ID);
        exec("DELETE FROM ingest_item WHERE original_filename LIKE '" + MARKER + "%'");
        exec("DELETE FROM imaging_modality WHERE code = 'INBOX_IT'");
    }

    private void exec(String sql) throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }

    private MockMvc mockMvc() {
        return ProductionMvc.standalone(new IngestInboxApiController(
                        DATA_SOURCE, new SiteVisibilityFilter(DATA_SOURCE),
                        new StudySubjectFinder(DATA_SOURCE)))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private MockHttpSession sessionAs(Role role) {
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
        r.setRole(role);
        s.setAttribute("userRole", r);
        return s;
    }

    private MockHttpSession dm() {
        return sessionAs(Role.STUDYDIRECTOR);
    }

    /** One unreconciled file of a given kind. */
    private long seed(String kind, String sourceKind, String device, String patientId) throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO ingest_item (kind, source_kind, device, stored_path, "
                             + "original_filename, patient_id, received_at, status) "
                             + "VALUES (?, ?, ?, ?, ?, ?, now(), 'UNBOUND') RETURNING ingest_item_id")) {
            String name = MARKER + System.nanoTime();
            ps.setString(1, kind);
            ps.setString(2, sourceKind);
            ps.setString(3, device);
            ps.setString(4, "/tmp/" + name);
            ps.setString(5, name);
            ps.setString(6, patientId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /**
     * Put this device on the study's imaging catalogue with a performed
     * binding, so filing a file from it has a box to tick.
     *
     * <p>P3.4 — this used to write an ingest_performed_item_map row. What a
     * device ticks is per-study configuration now.
     */
    private void mapDeviceToItem() throws Exception {
        try (Connection c = DATA_SOURCE.getConnection()) {
            int modalityId;
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO imaging_modality (study_id, code, label_de, label_en, device, "
                            + "kinds_accepted, laterality_required, ordinal, status_id, created_by_user_id) "
                            + "VALUES (?, 'INBOX_IT', 'Testgerät', 'Test device', ?, 'dicom', "
                            + "false, 1, 1, 1) RETURNING imaging_modality_id")) {
                ps.setInt(1, 1);
                ps.setString(2, DEVICE);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    modalityId = rs.getInt(1);
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO imaging_modality_item_binding "
                            + "(imaging_modality_id, role, laterality, item_oid, performed_value, created_by_user_id) "
                            + "VALUES (?, 'performed', 'OU', ?, '1', 1)")) {
                ps.setInt(1, modalityId);
                ps.setString(2, ITEM_OID);
                ps.executeUpdate();
            }
        }
    }

    private String statusOf(long id) throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT status FROM ingest_item WHERE ingest_item_id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /** The ticked value and which file it is attributed to, or null when gone. */
    private record Tick(String value, Long sourceId) {}

    private Tick tick() throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT value, source_ingest_item_id FROM item_data "
                             + " WHERE event_crf_id = ? AND item_id = ?")) {
            ps.setInt(1, EVENT_CRF_ID);
            ps.setInt(2, ITEM_ID);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                long src = rs.getLong(2);
                return new Tick(rs.getString(1), rs.wasNull() ? null : src);
            }
        }
    }

    private int auditCount(int type) throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM audit_log_event WHERE audit_log_event_type_id = ?")) {
            ps.setInt(1, type);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /* ---------------- the gate ---------------- */

    @Test
    void anUnauthenticatedCallerSeesNothing() throws Exception {
        mockMvc().perform(get("/api/v1/ingest/inbox").session(new MockHttpSession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aMonitorMayNotReconcile() throws Exception {
        // Verify-only: a monitor reads data, they do not decide whose it is.
        mockMvc().perform(get("/api/v1/ingest/inbox").session(sessionAs(Role.MONITOR)))
                .andExpect(status().isForbidden());
    }

    /* ---------------- listing ---------------- */

    @Test
    void theInboxListsEveryKindAtOnce() throws Exception {
        seed("image", "upload", "remidio", null);
        seed("e2e", "portal-oct", "spectralis", null);
        seed("dicom", "dicom", "OPTOMEDLUMO", null);

        mockMvc().perform(get("/api/v1/ingest/inbox").session(dm()))
                .andExpect(status().isOk())
                // The point of the merge: one queue, not three.
                .andExpect(jsonPath("$.items[?(@.kind=='image')]").isNotEmpty())
                .andExpect(jsonPath("$.items[?(@.kind=='e2e')]").isNotEmpty())
                .andExpect(jsonPath("$.items[?(@.kind=='dicom')]").isNotEmpty());
    }

    @Test
    void kindNarrowsTheQueue() throws Exception {
        seed("image", "upload", "remidio", null);
        seed("e2e", "portal-oct", "spectralis", null);

        mockMvc().perform(get("/api/v1/ingest/inbox").param("kind", "e2e").session(dm()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.kind=='image')]").isEmpty())
                .andExpect(jsonPath("$.items[?(@.kind=='e2e')]").isNotEmpty());
    }

    @Test
    void deviceAndSourceNarrowTheQueue() throws Exception {
        seed("dicom", "dicom", "OPTOMEDLUMO", null);
        seed("image", "upload", "remidio", null);

        mockMvc().perform(get("/api/v1/ingest/inbox").param("device", "optomedlumo").session(dm()))
                .andExpect(status().isOk())
                // Device keys are compared case-insensitively — an AE title's
                // capitalisation is the camera's business, not the operator's.
                .andExpect(jsonPath("$.items[?(@.device=='OPTOMEDLUMO')]").isNotEmpty())
                .andExpect(jsonPath("$.items[?(@.device=='remidio')]").isEmpty());

        mockMvc().perform(get("/api/v1/ingest/inbox").param("source", "upload").session(dm()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.sourceKind=='dicom')]").isEmpty());
    }

    @Test
    void anUnknownStatusIsRefusedRatherThanTreatedAsUnbound() throws Exception {
        mockMvc().perform(get("/api/v1/ingest/inbox").param("status", "PENDING").session(dm()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void countsAreGroupedByKind() throws Exception {
        seed("image", "upload", "remidio", null);
        seed("e2e", "portal-oct", "spectralis", null);

        mockMvc().perform(get("/api/v1/ingest/inbox/counts").session(dm()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.byKind.e2e").isNumber())
                .andExpect(jsonPath("$.unbound").isNumber());
    }

    /* ---------------- bind ---------------- */

    @Test
    void bindingAFileFilesItAndTicksTheVisitsBox() throws Exception {
        mapDeviceToItem();
        long id = seed("dicom", "dicom", DEVICE, null);

        mockMvc().perform(post("/api/v1/ingest/" + id + "/bind")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studySubjectId\":" + STUDY_SUBJECT_ID
                                + ",\"studyEventId\":" + STUDY_EVENT_ID
                                + ",\"eventCrfId\":" + EVENT_CRF_ID + "}")
                        .session(dm()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BOUND"));

        assertEquals("BOUND", statusOf(id));
        Tick t = tick();
        assertNotNull(t, "the file on the visit is the evidence the device was used on it");
        assertEquals("1", t.value());
        assertEquals(Long.valueOf(id), t.sourceId(), "the value must be traceable to the file");
        assertTrue(auditCount(AuditTypeIds.IMAGE_BIND) >= 1, "somebody decided this; record it");
    }

    @Test
    void bindingAnAlreadyReconciledFileConflicts() throws Exception {
        long id = seed("image", "upload", "remidio", null);
        String body = "{\"studySubjectId\":" + STUDY_SUBJECT_ID + "}";
        mockMvc().perform(post("/api/v1/ingest/" + id + "/bind")
                .contentType(MediaType.APPLICATION_JSON).content(body).session(dm()))
                .andExpect(status().isOk());
        mockMvc().perform(post("/api/v1/ingest/" + id + "/bind")
                .contentType(MediaType.APPLICATION_JSON).content(body).session(dm()))
                .andExpect(status().isConflict());
    }

    @Test
    void bindingToASubjectThatDoesNotExistIsNotFound() throws Exception {
        long id = seed("image", "upload", "remidio", null);
        mockMvc().perform(post("/api/v1/ingest/" + id + "/bind")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"studySubjectId\":987654}").session(dm()))
                .andExpect(status().isNotFound());
    }

    /* ---------------- unbind ---------------- */

    @Test
    void unbindingReturnsTheFileAndRemovesTheValueItCaused() throws Exception {
        mapDeviceToItem();
        long id = seed("dicom", "dicom", DEVICE, null);
        mockMvc().perform(post("/api/v1/ingest/" + id + "/bind")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studySubjectId\":" + STUDY_SUBJECT_ID
                                + ",\"studyEventId\":" + STUDY_EVENT_ID
                                + ",\"eventCrfId\":" + EVENT_CRF_ID + "}")
                        .session(dm()))
                .andExpect(status().isOk());
        assertNotNull(tick());

        mockMvc().perform(post("/api/v1/ingest/" + id + "/unbind").session(dm()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNBOUND"));

        assertEquals("UNBOUND", statusOf(id));
        // The whole point: a form must not keep asserting a modality was
        // performed once the only evidence for it has been taken away.
        assertNull(tick(), "the tick the bind caused must go with it");
        assertTrue(auditCount(AuditTypeIds.INGEST_UNBIND) >= 1,
                "a CRF value disappearing is its own audit event");
    }

    /**
     * Two images from one camera on one visit. Unbinding one does not make the
     * modality un-performed — the other is still evidence — so the value stays
     * and simply points at what is left.
     */
    @Test
    void unbindingOneOfTwoFilesRepointsInsteadOfClearing() throws Exception {
        mapDeviceToItem();
        long first = seed("dicom", "dicom", DEVICE, null);
        long second = seed("dicom", "dicom", DEVICE, null);
        String body = "{\"studySubjectId\":" + STUDY_SUBJECT_ID
                + ",\"studyEventId\":" + STUDY_EVENT_ID
                + ",\"eventCrfId\":" + EVENT_CRF_ID + "}";
        for (long id : new long[] { first, second }) {
            mockMvc().perform(post("/api/v1/ingest/" + id + "/bind")
                    .contentType(MediaType.APPLICATION_JSON).content(body).session(dm()))
                    .andExpect(status().isOk());
        }
        assertEquals(Long.valueOf(first), tick().sourceId(), "the first bind wrote the value");

        mockMvc().perform(post("/api/v1/ingest/" + first + "/unbind").session(dm()))
                .andExpect(status().isOk());

        Tick t = tick();
        assertNotNull(t, "the modality was still performed — the other image proves it");
        assertEquals("1", t.value());
        assertEquals(Long.valueOf(second), t.sourceId(), "provenance moves to the file that remains");
    }

    @Test
    void unbindingSomethingThatIsNotBoundConflicts() throws Exception {
        long id = seed("image", "upload", "remidio", null);
        mockMvc().perform(post("/api/v1/ingest/" + id + "/unbind").session(dm()))
                .andExpect(status().isConflict());
    }

    /**
     * An operator's own value is not the platform's to remove, whatever an
     * unbind is undoing.
     */
    @Test
    void unbindingNeverRemovesAValueAPersonTyped() throws Exception {
        mapDeviceToItem();
        long id = seed("dicom", "dicom", DEVICE, null);
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO item_data (item_id, event_crf_id, status_id, value, date_created, "
                             + "owner_id, ordinal, deleted) VALUES (?, ?, 1, '0', now(), 1, 1, false)")) {
            ps.setInt(1, ITEM_ID);
            ps.setInt(2, EVENT_CRF_ID);
            ps.executeUpdate();
        }
        mockMvc().perform(post("/api/v1/ingest/" + id + "/bind")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"studySubjectId\":" + STUDY_SUBJECT_ID
                        + ",\"studyEventId\":" + STUDY_EVENT_ID
                        + ",\"eventCrfId\":" + EVENT_CRF_ID + "}")
                .session(dm()))
                .andExpect(status().isOk());

        mockMvc().perform(post("/api/v1/ingest/" + id + "/unbind").session(dm()))
                .andExpect(status().isOk());

        Tick t = tick();
        assertNotNull(t, "the operator's row must survive");
        assertEquals("0", t.value(), "including when they recorded 'not performed'");
    }

    /* ---------------- dismiss ---------------- */

    @Test
    void dismissingKeepsTheReason() throws Exception {
        long id = seed("image", "upload", "remidio", null);
        mockMvc().perform(post("/api/v1/ingest/" + id + "/dismiss")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"test exposure\"}")
                        .session(dm()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISMISSED"));

        assertEquals("DISMISSED", statusOf(id));
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT status_message FROM ingest_item WHERE ingest_item_id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                // The retention sweep deletes the file later; the row is then
                // the only record it ever existed.
                assertEquals("test exposure", rs.getString(1));
            }
        }
    }

    /**
     * 2026-09-27 — the trail says which file, and why, without whose.
     *
     * <p>The dismissal carries the reason and the file's reference; the
     * reference never carries the original file name, which can hold the
     * patient's name. Restoring is its own type: it was written under the
     * dismiss type and read as a second dismissal. The system audit log shows
     * both rows with their titles, the file, and the reason.
     */
    @Test
    void dismissingAndRestoringLeaveATrailThatNamesTheFile() throws Exception {
        long id = seed("image", "upload", "remidio", null);
        mockMvc().perform(post("/api/v1/ingest/" + id + "/dismiss")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"test exposure\"}")
                        .session(dm()))
                .andExpect(status().isOk());
        mockMvc().perform(post("/api/v1/ingest/" + id + "/restore").session(dm()))
                .andExpect(status().isOk());

        long dismissRow;
        long restoreRow;
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT audit_id, audit_log_event_type_id, entity_name, old_value, new_value, "
                             + "       reason_for_change "
                             + "  FROM audit_log_event "
                             + " WHERE audit_table = 'ingest_item' AND entity_id = ? ORDER BY audit_id")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "the dismissal is recorded");
                dismissRow = rs.getLong("audit_id");
                assertEquals(AuditTypeIds.IMAGE_DISMISS, rs.getInt("audit_log_event_type_id"));
                String reference = rs.getString("entity_name");
                assertTrue(reference.startsWith("file #" + id + " · remidio · image · received "),
                        "which file, in words that outlive it: " + reference);
                assertFalse(reference.contains(MARKER), "never the original file name");
                assertEquals("UNBOUND", rs.getString("old_value"));
                assertEquals("DISMISSED", rs.getString("new_value"));
                assertEquals("test exposure", rs.getString("reason_for_change"));

                assertTrue(rs.next(), "the restore is recorded");
                restoreRow = rs.getLong("audit_id");
                assertEquals(AuditTypeIds.INGEST_RESTORE, rs.getInt("audit_log_event_type_id"));
                assertEquals(reference, rs.getString("entity_name"));
                assertEquals("DISMISSED;reason=test exposure", rs.getString("old_value"));
                assertEquals("UNBOUND", rs.getString("new_value"));
                assertFalse(rs.next());
            }
        }

        MockHttpSession sysadmin = dm();
        ((UserAccountBean) sysadmin.getAttribute("userBean")).addUserType(UserType.SYSADMIN);
        ProductionMvc.standalone(new AuditApiController(DATA_SOURCE, new SiteVisibilityFilter(DATA_SOURCE)))
                .build()
                .perform(get("/api/v1/audit/system").session(sysadmin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events[?(@.id == '" + dismissRow + "')].title").value(
                        org.hamcrest.Matchers.hasItem("Ingested file dismissed")))
                .andExpect(jsonPath("$.events[?(@.id == '" + dismissRow + "')].reason").value(
                        org.hamcrest.Matchers.hasItem("test exposure")))
                .andExpect(jsonPath("$.events[?(@.id == '" + dismissRow + "' && @.details =~ /^file #" + id + " .*/)]")
                        .exists())
                .andExpect(jsonPath("$.events[?(@.id == '" + restoreRow + "')].title").value(
                        org.hamcrest.Matchers.hasItem("Ingested file restored")));
    }

    /**
     * 2026-09-27 — filing a file to a visit, and taking it off, change that
     * visit's source data, so both rows record the visit and appear in the
     * study's own audit log. The dismissal of a never-filed file does not.
     */
    @Test
    void aBindAndAnUnbindRecordTheirVisitAndReachTheStudyLog() throws Exception {
        mapDeviceToItem();
        long id = seed("dicom", "dicom", DEVICE, null);
        mockMvc().perform(post("/api/v1/ingest/" + id + "/bind")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studySubjectId\":" + STUDY_SUBJECT_ID
                                + ",\"studyEventId\":" + STUDY_EVENT_ID
                                + ",\"eventCrfId\":" + EVENT_CRF_ID + "}")
                        .session(dm()))
                .andExpect(status().isOk());
        mockMvc().perform(post("/api/v1/ingest/" + id + "/unbind").session(dm()))
                .andExpect(status().isOk());
        long dismissed = seed("image", "upload", "remidio", null);
        mockMvc().perform(post("/api/v1/ingest/" + dismissed + "/dismiss")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"wrong patient\"}")
                        .session(dm()))
                .andExpect(status().isOk());

        long bindRow = auditRowOf(id, AuditTypeIds.IMAGE_BIND);
        long unbindRow = auditRowOf(id, AuditTypeIds.INGEST_UNBIND);
        long dismissRow = auditRowOf(dismissed, AuditTypeIds.IMAGE_DISMISS);
        assertEquals(Integer.valueOf(STUDY_EVENT_ID), visitColumnOf(bindRow), "the visit the file was filed to");
        assertEquals(Integer.valueOf(STUDY_EVENT_ID), visitColumnOf(unbindRow), "the visit the file was taken off");
        assertNull(visitColumnOf(dismissRow), "a file that was never filed names no visit");

        MockHttpSession studyLog = sessionAs(Role.ADMIN);
        ((StudyUserRoleBean) studyLog.getAttribute("userRole")).setStudyId(1);
        ProductionMvc.standalone(new AuditApiController(DATA_SOURCE, new SiteVisibilityFilter(DATA_SOURCE)))
                .build()
                .perform(get("/api/v1/audit").session(studyLog))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events[*].id", org.hamcrest.Matchers.hasItem(String.valueOf(bindRow))))
                .andExpect(jsonPath("$.events[*].id", org.hamcrest.Matchers.hasItem(String.valueOf(unbindRow))))
                .andExpect(jsonPath("$.events[*].id",
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem(String.valueOf(dismissRow)))));
    }

    private long auditRowOf(long ingestItemId, int type) throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT MAX(audit_id) FROM audit_log_event "
                             + "WHERE audit_table = 'ingest_item' AND entity_id = ? AND audit_log_event_type_id = ?")) {
            ps.setLong(1, ingestItemId);
            ps.setInt(2, type);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                long row = rs.getLong(1);
                assertFalse(rs.wasNull(), "no type " + type + " row for file " + ingestItemId);
                return row;
            }
        }
    }

    private Integer visitColumnOf(long auditId) throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT study_event_id FROM audit_log_event WHERE audit_id = ?")) {
            ps.setLong(1, auditId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                int v = rs.getInt(1);
                return rs.wasNull() ? null : v;
            }
        }
    }

    /* ---------------- bulk ---------------- */

    @Test
    void bulkBindAppliesEachIndependently() throws Exception {
        long ok1 = seed("image", "upload", "remidio", null);
        long ok2 = seed("image", "upload", "remidio", null);
        long already = seed("image", "upload", "remidio", null);
        mockMvc().perform(post("/api/v1/ingest/" + already + "/dismiss")
                .contentType(MediaType.APPLICATION_JSON).content("{}").session(dm()))
                .andExpect(status().isOk());

        mockMvc().perform(post("/api/v1/ingest/bulk-bind")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[" + ok1 + "," + ok2 + "," + already + "],"
                                + "\"studySubjectId\":" + STUDY_SUBJECT_ID + "}")
                        .session(dm()))
                .andExpect(status().isOk())
                // One row that somebody already dealt with must not discard the
                // rest of the operator's selection.
                .andExpect(jsonPath("$.bound.length()").value(2))
                .andExpect(jsonPath("$.skipped.length()").value(1));

        assertEquals("BOUND", statusOf(ok1));
        assertEquals("DISMISSED", statusOf(already));
    }

    @Test
    void bulkBindRefusesAnUnboundedList() throws Exception {
        StringBuilder ids = new StringBuilder();
        for (int i = 0; i < 101; i++) ids.append(i == 0 ? "" : ",").append(i + 1);
        mockMvc().perform(post("/api/v1/ingest/bulk-bind")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[" + ids + "],\"studySubjectId\":" + STUDY_SUBJECT_ID + "}")
                        .session(dm()))
                .andExpect(status().isBadRequest());
    }

    /* ---------------- preview ---------------- */

    @Test
    void aPreviewThatDoesNotExistIsNotFound() throws Exception {
        long id = seed("image", "upload", "remidio", null);
        // The row has a stored_path but no preview_png_path.
        mockMvc().perform(get("/api/v1/ingest/" + id + "/preview").session(dm()))
                .andExpect(status().isNotFound());
    }

    /* ---------------- DR-036: twins ---------------- */

    private void setPixelSha256(long id, String fp) throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE ingest_item SET pixel_sha256 = ? WHERE ingest_item_id = ?")) {
            ps.setString(1, fp);
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    /**
     * Two files that show the same picture point at each other in the inbox,
     * and filing one of them anyway is recorded with the other's number.
     */
    @Test
    void aFileWhosePictureIsAlreadyKnownSaysSo() throws Exception {
        long earlier = seed("image", "upload", "remidio", "HAE-001");
        long later = seed("image", "upload", "remidio", "HAE-002");
        long unrelated = seed("image", "upload", "remidio", "HAE-003");
        setPixelSha256(earlier, "c".repeat(64));
        setPixelSha256(later, "c".repeat(64));
        setPixelSha256(unrelated, "d".repeat(64));

        mockMvc().perform(get("/api/v1/ingest/inbox").session(dm()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.id == " + later + ")].twin.ingestItemId").value(
                        org.hamcrest.Matchers.hasItem((int) earlier)))
                .andExpect(jsonPath("$.items[?(@.id == " + later + ")].twin.label").value(
                        org.hamcrest.Matchers.hasItem("HAE-001")))
                .andExpect(jsonPath("$.items[?(@.id == " + later + ")].twin.status").value(
                        org.hamcrest.Matchers.hasItem("UNBOUND")))
                .andExpect(jsonPath("$.items[?(@.id == " + earlier + ")].twin.ingestItemId").value(
                        org.hamcrest.Matchers.hasItem((int) later)))
                .andExpect(jsonPath("$.items[?(@.id == " + unrelated + ")].twin").value(
                        org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.nullValue())));

        mockMvc().perform(get("/api/v1/ingest/" + later).session(dm()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.twin.ingestItemId").value(earlier));

        mockMvc().perform(post("/api/v1/ingest/" + later + "/bind")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studySubjectId\":" + STUDY_SUBJECT_ID
                                + ",\"studyEventId\":" + STUDY_EVENT_ID + "}")
                        .session(dm()))
                .andExpect(status().isOk());
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT new_value FROM audit_log_event WHERE audit_log_event_type_id = ? "
                             + "   AND audit_table = 'ingest_item' AND entity_id = ?")) {
            ps.setInt(1, AuditTypeIds.IMAGE_BIND);
            ps.setLong(2, later);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertTrue(rs.getString(1).endsWith(";same_image_as=" + earlier), rs.getString(1));
            }
        }
        // Filed now: the twin's label is the subject it was filed to.
        mockMvc().perform(get("/api/v1/ingest/" + earlier).session(dm()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.twin.status").value("BOUND"))
                .andExpect(jsonPath("$.twin.label").value("M-001"));
    }

    @Test
    void aFileNobodyHasSeenBeforeIsFetchableById() throws Exception {
        long id = seed("e2e", "portal-oct", "spectralis", null);
        mockMvc().perform(get("/api/v1/ingest/" + id).session(dm()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("e2e"))
                .andExpect(jsonPath("$.id").value(id));
    }
}
