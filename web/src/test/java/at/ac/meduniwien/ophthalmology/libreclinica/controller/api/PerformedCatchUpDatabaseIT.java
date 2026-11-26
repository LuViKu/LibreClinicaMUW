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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.login.UserAccountDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.PerformedItemAutoTicker;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * A file reaches its catalogue entry by its device, and files filed before
 * their entry had a binding can be ticked afterwards.
 *
 * <p>On HealthAEye none of 176 filed fundus images ticked its box: the
 * Remidio, Optomed and Clarus entries had no binding, and the Optomed uploads
 * carried a device name ("optomed") that matched neither the entry's device
 * nor its AE title. The tick is written when a file is bound, so binding
 * them afterwards needed a catch-up.
 */
class PerformedCatchUpDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static final String STUDY_OID = "S_DEFAULTS1";
    private static final String APPLY = "/api/v1/studies/" + STUDY_OID + "/imaging-modalities/apply-performed";
    /** Seeded: study_event 3 of study 1 carries event_crf 3, which has this item. */
    private static final int STUDY_EVENT_ID = 3;
    private static final int EVENT_CRF_ID = 3;
    private static final int ITEM_ID = 5;
    private static final String ITEM_OID = "I_BLOOD_PRESSURE_SYS";
    /** Seeded: a single-select coded Y/N on the same form, empty on event_crf 3. */
    private static final int CODED_ITEM_ID = 2;
    private static final String CODED_ITEM_OID = "I_CONSENT_SIGNED";
    private static final String MARKER = "catchup-it-";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private int modalityId;
    private int subjectId;

    @BeforeEach
    void seed() throws Exception {
        try (Connection c = DATA_SOURCE.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT study_subject_id FROM study_event WHERE study_event_id = ?")) {
                ps.setInt(1, STUDY_EVENT_ID);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    subjectId = rs.getInt(1);
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO imaging_modality (study_id, code, label_de, label_en, device, kinds_accepted, "
                            + "laterality_required, auto_match_ae_title, ordinal, status_id, created_by_user_id) "
                            + "VALUES (1, 'IT_CATCHUP', 'Fundus', 'Fundus', 'it-fundus', 'image', false, "
                            + "'ITFUNDUSAE', 99, 1, 1) RETURNING imaging_modality_id")) {
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    modalityId = rs.getInt(1);
                }
            }
        }
    }

    @AfterEach
    void cleanUp() throws Exception {
        exec("DELETE FROM audit_log_event WHERE audit_log_event_type_id = 129 AND entity_id IN "
                + "(SELECT ingest_item_id FROM ingest_item WHERE original_filename LIKE '" + MARKER + "%')");
        exec("DELETE FROM item_data WHERE event_crf_id = " + EVENT_CRF_ID + " AND item_id = " + ITEM_ID);
        exec("DELETE FROM item_data WHERE event_crf_id = " + EVENT_CRF_ID + " AND item_id = " + CODED_ITEM_ID
                + " AND source_kind = 'ingest'");
        exec("DELETE FROM ingest_item WHERE original_filename LIKE '" + MARKER + "%'");
        exec("DELETE FROM imaging_modality WHERE code = 'IT_CATCHUP'");
    }

    private void exec(String sql) throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }

    private void bindPerformed() throws Exception {
        exec("INSERT INTO imaging_modality_item_binding "
                + "(imaging_modality_id, role, laterality, item_oid, performed_value, created_by_user_id) "
                + "VALUES (" + modalityId + ", 'performed', 'OU', '" + ITEM_OID + "', '1', 1)");
    }

    /** A file, BOUND (or not) to the visit, with no catalogue entry. */
    private long file(String device, boolean bound) throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO ingest_item (kind, source_kind, device, stored_path, original_filename, "
                             + "received_at, status, bound_study_subject_id, bound_study_event_id) "
                             + "VALUES ('image', 'upload', ?, '/tmp/it.jpg', ?, now(), ?, ?, ?) "
                             + "RETURNING ingest_item_id")) {
            ps.setString(1, device);
            ps.setString(2, MARKER + System.nanoTime());
            ps.setString(3, bound ? "BOUND" : "UNBOUND");
            if (bound) {
                ps.setInt(4, subjectId);
                ps.setInt(5, STUDY_EVENT_ID);
            } else {
                ps.setNull(4, java.sql.Types.INTEGER);
                ps.setNull(5, java.sql.Types.INTEGER);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private Integer modalityOf(long ingestId) throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT imaging_modality_id FROM ingest_item WHERE ingest_item_id = ?")) {
            ps.setLong(1, ingestId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                int v = rs.getInt(1);
                return rs.wasNull() ? null : v;
            }
        }
    }

    /** The tick on the form: value and author, or null when there is none. */
    private String[] tick() throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT value, owner_id FROM item_data WHERE event_crf_id = ? AND item_id = ? "
                             + "ORDER BY item_data_id")) {
            ps.setInt(1, EVENT_CRF_ID);
            ps.setInt(2, ITEM_ID);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new String[] {rs.getString(1), String.valueOf(rs.getInt(2))};
            }
        }
    }

    private MockMvc mvc() {
        return ProductionMvc.standalone(new ImagingModalitiesApiController(DATA_SOURCE))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private MockHttpSession sessionAs(Role role, int activeStudyId) {
        MockHttpSession s = new MockHttpSession();
        UserAccountBean ub = new UserAccountBean();
        ub.setId(1);
        ub.setName("root");
        s.setAttribute("userBean", ub);
        StudyBean study = new StudyBean();
        study.setId(activeStudyId);
        study.setOid(STUDY_OID);
        s.setAttribute("study", study);
        StudyUserRoleBean r = new StudyUserRoleBean();
        r.setRole(role);
        s.setAttribute("userRole", r);
        return s;
    }

    private JsonNode applyAsDataManager() throws Exception {
        MvcResult r = mvc().perform(post(APPLY).session(sessionAs(Role.STUDYDIRECTOR, 1)))
                .andExpect(status().isOk())
                .andReturn();
        return JSON.readTree(r.getResponse().getContentAsString());
    }

    @Test
    void aFileWhoseDeviceIsTheEntrysAeTitleGetsItsEntryAndItsTickOnBind() throws Exception {
        bindPerformed();
        long id = file("ITFUNDUSAE", false);
        IngestBindService.Actor actor = new IngestBindService.Actor(
                new UserAccountDAO(DATA_SOURCE).findByPK(1), null);

        assertEquals(IngestBindService.Result.OK, new IngestBindService(DATA_SOURCE)
                .bind(id, subjectId, STUDY_EVENT_ID, null, IngestBindService.POLICY_MANUAL, actor));

        assertEquals(Integer.valueOf(modalityId), modalityOf(id), "the file knows what it is");
        String[] tick = tick();
        assertNotNull(tick, "the visit's box is ticked");
        assertEquals("1", tick[0]);
    }

    @Test
    void theCatchUpTicksAFileFiledBeforeItsBindingExisted() throws Exception {
        long id = file("it-fundus", true);   // filed while the entry had no binding
        bindPerformed();                      // the binding added afterwards

        JsonNode first = applyAsDataManager();
        assertTrue(first.get("files").asInt() >= 1, first.toString());
        assertTrue(first.get("outcomes").get("WRITTEN").asInt() >= 1, first.toString());
        assertEquals(Integer.valueOf(modalityId), modalityOf(id));
        String[] tick = tick();
        assertNotNull(tick, "the tick a bind would have written");
        assertEquals("1", tick[0]);
        assertEquals(String.valueOf(PerformedItemAutoTicker.systemUserId(DATA_SOURCE)), tick[1],
                "written as the system account, not as whoever pressed the button");

        JsonNode again = applyAsDataManager();
        assertTrue(again.get("outcomes").get("ALREADY_SET").asInt() >= 1,
                "running it twice changes nothing: " + again);
    }

    @Test
    void theCatchUpLeavesAValueSomeoneEnteredAlone() throws Exception {
        file("it-fundus", true);
        bindPerformed();
        exec("INSERT INTO item_data (item_id, event_crf_id, status_id, value, date_created, owner_id, ordinal, "
                + "deleted) VALUES (" + ITEM_ID + ", " + EVENT_CRF_ID + ", 1, '0', now(), 1, 1, false)");

        JsonNode result = applyAsDataManager();
        assertTrue(result.get("outcomes").get("OPERATOR_VALUE_KEPT").asInt() >= 1, result.toString());
        assertEquals("0", tick()[0], "the operator's value stays");
    }

    /** The coded item's value on the form, written by ingest; null when none. */
    private String codedTick() throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT value FROM item_data WHERE event_crf_id = ? AND item_id = ? "
                             + "AND source_kind = 'ingest'")) {
            ps.setInt(1, EVENT_CRF_ID);
            ps.setInt(2, CODED_ITEM_ID);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /**
     * HealthAEye's "performed" items are coded Y/N, and its bindings said
     * {@code 1}. A code the item does not have shows no selection on the form
     * and exports as a code no codelist knows, so the tick is refused and
     * counted until the binding says {@code Y}.
     */
    @Test
    void aBindingWhoseValueIsNoAnswerOfTheItemTicksNothing() throws Exception {
        file("it-fundus", true);
        exec("INSERT INTO imaging_modality_item_binding "
                + "(imaging_modality_id, role, laterality, item_oid, performed_value, created_by_user_id) "
                + "VALUES (" + modalityId + ", 'performed', 'OU', '" + CODED_ITEM_OID + "', '1', 1)");

        JsonNode refused = applyAsDataManager();
        assertTrue(refused.get("outcomes").get("INVALID_VALUE").asInt() >= 1, refused.toString());
        assertEquals(null, codedTick(), "nothing is written into the Y/N item");

        exec("UPDATE imaging_modality_item_binding SET performed_value = 'Y' "
                + "WHERE imaging_modality_id = " + modalityId);
        JsonNode fixed = applyAsDataManager();
        assertTrue(fixed.get("outcomes").get("WRITTEN").asInt() >= 1, fixed.toString());
        assertEquals("Y", codedTick());
    }

    @Test
    void theCatchUpIsADataManagersForTheActiveStudyOnly() throws Exception {
        mvc().perform(post(APPLY).session(sessionAs(Role.INVESTIGATOR, 1)))
                .andExpect(status().isForbidden());
        mvc().perform(post(APPLY).session(sessionAs(Role.STUDYDIRECTOR, 999)))
                .andExpect(status().isForbidden());
    }
}
