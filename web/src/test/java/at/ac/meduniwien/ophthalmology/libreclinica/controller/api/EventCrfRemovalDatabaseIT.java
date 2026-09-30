/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.i18n.util.ResourceBundleProvider;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;
import at.ac.meduniwien.ophthalmology.libreclinica.service.crf.CrfFileStorageService;
import at.ac.meduniwien.ophthalmology.libreclinica.service.crf.EventCrfPresenceRegistry;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RetinalResultItemDataPopulator;
import at.ac.meduniwien.ophthalmology.libreclinica.service.scheduling.VisitIntervalCalculator;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Removing an event CRF in the SPA does what legacy
 * {@code RemoveEventCRFServlet} does, records who removed it and why, and the
 * SPA's restore takes it back exactly.
 *
 * <p>Seed ({@code lc-muw-2026-06-01-seed-demo-data.xml}): event CRF 9 (visit
 * 10) holds values 23 and 24, with open query threads 5 on value 23 and 6 and
 * 7 on value 24; event CRF 10 (visit 13) holds values 25 to 28; event CRF 11
 * (visit 14) values 29 and 30; event CRF 1 (visit 1) values 1 to 5 and open
 * thread 1 on value 3; event CRF 6 is signed. Each test works on its own CRF,
 * so the order they run in does not matter. Status ids: 1 available,
 * 5 removed, 7 auto-removed, 8 signed; resolution status 4 is closed.
 */
class EventCrfRemovalDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static final int STUDY_ID = 1;
    private static final String STUDY_OID = "S_DEFAULTS1";
    private static final String CLOSING_NOTE = "The item has been removed, this Discrepancy Note has been Closed.";

    private MockMvc mockMvc() {
        SiteVisibilityFilter visibility = new SiteVisibilityFilter(DATA_SOURCE);
        return MockMvcBuilders.standaloneSetup(
                        new EventCrfRemovalApiController(DATA_SOURCE, visibility),
                        new EventCrfsApiController(DATA_SOURCE, visibility,
                                Mockito.mock(CrfFileStorageService.class),
                                new EventCrfPresenceRegistry(),
                                new RetinalResultItemDataPopulator(DATA_SOURCE)),
                        new EventsApiController(DATA_SOURCE, visibility, new VisitIntervalCalculator(DATA_SOURCE)))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    /**
     * The confirm dialog's numbers are what the removal then does: the CRF
     * is removed, its values auto-removed, every open thread on them closed
     * with legacy's closing note, and the audit trail holds the removal with
     * its reason. The visit page keeps the CRF in its slot, as removed.
     */
    @Test
    void removalCascadesAsLegacyAndRecordsTheReason() throws Exception {
        MockHttpSession dm = session("manual_dm", Role.STUDYDIRECTOR);
        mockMvc().perform(get("/api/v1/eventCrfs/9/removal-impact").session(dm))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.values").value(2))
                .andExpect(jsonPath("$.openNoteThreads").value(3));

        remove(9, "Entered on the wrong subject", dm).andExpect(status().isNoContent());

        assertEquals(5, statusOf("event_crf", 9));
        assertEquals(7, statusOf("item_data", 23));
        assertEquals(7, statusOf("item_data", 24));

        int dmId = userId("manual_dm");
        for (int thread : new int[] {5, 6, 7}) {
            assertEquals(4, intOf("SELECT resolution_status_id FROM discrepancy_note WHERE discrepancy_note_id = ?",
                    thread), "thread " + thread + " is not closed");
            assertEquals(1, intOf("SELECT COUNT(*) FROM discrepancy_note dn "
                    + "JOIN dn_item_data_map m ON m.discrepancy_note_id = dn.discrepancy_note_id "
                    + "WHERE dn.parent_dn_id = ? AND dn.resolution_status_id = 4 AND dn.owner_id = " + dmId
                    + " AND dn.description = '" + CLOSING_NOTE + "'", thread),
                    "thread " + thread + " has no mapped closing note");
            assertEquals(1, intOf("SELECT COUNT(*) FROM audit_log_event WHERE audit_table = 'discrepancy_note' "
                    + "AND audit_log_event_type_id = 73 AND new_value = 'closed' AND entity_id = ?", thread),
                    "thread " + thread + " has no status-change audit row");
        }

        List<String> audit = row("SELECT user_id, entity_name, old_value, new_value, reason_for_change, study_event_id "
                + "FROM audit_log_event WHERE audit_table = 'event_crf' AND audit_log_event_type_id = 155 "
                + "AND entity_id = ?", 9);
        assertEquals(List.of(String.valueOf(dmId), "Status", "1", "5", "Entered on the wrong subject", "10"), audit);

        mockMvc().perform(get("/api/v1/events/10").session(dm))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.crfs[?(@.eventCrfId == 9)].status").value(hasItem("removed")));

        mockMvc().perform(get("/api/v1/eventCrfs/9/removal-impact").session(dm))
                .andExpect(status().isConflict());
    }

    /**
     * Restore takes a removal back exactly: the CRF and the values it
     * auto-removed are available again with their values and provenance as
     * they were, and a value removed on its own before stays removed.
     */
    @Test
    void restoreIsTheInverseOfRemove() throws Exception {
        MockHttpSession dm = session("manual_dm", Role.STUDYDIRECTOR);
        exec("UPDATE item_data SET status_id = 5 WHERE item_data_id = 28");
        exec("UPDATE item_data SET source_kind = 'modality_baseline' WHERE item_data_id = 25");
        List<String> valuesBefore = values(10);

        remove(10, "Entered on the wrong visit", dm).andExpect(status().isNoContent());
        assertEquals(5, statusOf("event_crf", 10));
        for (int value : new int[] {25, 26, 27}) assertEquals(7, statusOf("item_data", value));
        assertEquals(5, statusOf("item_data", 28));
        assertEquals("modality_baseline", row("SELECT source_kind FROM item_data WHERE item_data_id = ?", 25).get(0),
                "the removal lost the value's provenance");

        mockMvc().perform(post("/api/v1/eventCrfs/10/restore").session(dm))
                .andExpect(status().isNoContent());
        assertEquals(1, statusOf("event_crf", 10));
        for (int value : new int[] {25, 26, 27}) assertEquals(1, statusOf("item_data", value));
        assertEquals(5, statusOf("item_data", 28), "a value removed on its own must stay removed");
        assertEquals(valuesBefore, values(10));
        assertEquals("modality_baseline", row("SELECT source_kind FROM item_data WHERE item_data_id = ?", 25).get(0),
                "the restore lost the value's provenance");
    }

    /** Legacy lets only a data manager, a coordinator or a system administrator remove a CRF. */
    @Test
    void monitorsAndInvestigatorsMayNotRemove() throws Exception {
        MockHttpSession monitor = session("manual_monitor", Role.MONITOR);
        mockMvc().perform(get("/api/v1/eventCrfs/11/removal-impact").session(monitor))
                .andExpect(status().isForbidden());
        remove(11, "Not mine to remove", monitor).andExpect(status().isForbidden());
        remove(11, "Not mine to remove", session("manual_investigator", Role.INVESTIGATOR))
                .andExpect(status().isForbidden());

        assertEquals(1, statusOf("event_crf", 11));
        assertEquals(0, intOf("SELECT COUNT(*) FROM audit_log_event WHERE audit_log_event_type_id = 155 "
                + "AND entity_id = ?", 11));
    }

    @Test
    void theCoordinatorMayRemove() throws Exception {
        remove(1, "Duplicate entry", session("manual_crc", Role.COORDINATOR)).andExpect(status().isNoContent());

        assertEquals(5, statusOf("event_crf", 1));
        assertEquals(4, intOf("SELECT resolution_status_id FROM discrepancy_note WHERE discrepancy_note_id = ?", 1));
    }

    @Test
    void aRemovalWithoutAReasonIsRefused() throws Exception {
        MockHttpSession dm = session("manual_dm", Role.STUDYDIRECTOR);
        remove(11, "   ", dm).andExpect(status().isBadRequest());
        mockMvc().perform(post("/api/v1/eventCrfs/11/remove").session(dm)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        remove(11, "x".repeat(1001), dm).andExpect(status().isBadRequest());

        assertEquals(1, statusOf("event_crf", 11));
    }

    @Test
    void aRemovedCrfIsNotRemovedAgain() throws Exception {
        // Auto-removed, as when its visit was cancelled.
        exec("UPDATE event_crf SET status_id = 7 WHERE event_crf_id = 16");
        remove(16, "Twice", session("manual_dm", Role.STUDYDIRECTOR)).andExpect(status().isConflict());

        assertEquals(7, statusOf("event_crf", 16));
    }

    /** A signed CRF, or any CRF of a signed visit, is refused, as the SPA's other writes to a CRF are. */
    @Test
    void aSignedCrfOrVisitIsRefused() throws Exception {
        MockHttpSession dm = session("manual_dm", Role.STUDYDIRECTOR);
        remove(6, "Signed already", dm).andExpect(status().isConflict());
        assertEquals(8, statusOf("event_crf", 6));

        exec("UPDATE study_event SET subject_event_status_id = 8 WHERE study_event_id = 19");
        remove(15, "Visit signed", dm).andExpect(status().isConflict());
        assertEquals(1, statusOf("event_crf", 15));
    }

    /* ---------------------------------------------------------------- */
    /* Helpers                                                          */
    /* ---------------------------------------------------------------- */

    private ResultActions remove(int eventCrfId, String reason, MockHttpSession session) throws Exception {
        String body = "{\"reason\":\"" + reason.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
        return mockMvc().perform(post("/api/v1/eventCrfs/" + eventCrfId + "/remove").session(session)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static MockHttpSession session(String userName, Role role) throws SQLException {
        ResourceBundleProvider.updateLocale(Locale.ENGLISH);
        MockHttpSession session = new MockHttpSession();
        UserAccountBean ub = new UserAccountBean();
        ub.setId(userId(userName));
        ub.setName(userName);
        session.setAttribute("userBean", ub);
        StudyBean study = new StudyBean();
        study.setId(STUDY_ID);
        study.setOid(STUDY_OID);
        study.setName("Default Study");
        session.setAttribute("study", study);
        StudyUserRoleBean studyRole = new StudyUserRoleBean();
        studyRole.setRole(role);
        studyRole.setStudyId(STUDY_ID);
        session.setAttribute("userRole", studyRole);
        return session;
    }

    private static int userId(String userName) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT user_id FROM user_account WHERE user_name = ?")) {
            ps.setString(1, userName);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static int statusOf(String table, int id) throws SQLException {
        return intOf("SELECT status_id FROM " + table + " WHERE " + table + "_id = ?", id);
    }

    /** The CRF's values in id order. */
    private static List<String> values(int eventCrfId) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT value FROM item_data WHERE event_crf_id = ? ORDER BY item_data_id")) {
            ps.setInt(1, eventCrfId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getString(1));
            }
        }
        return out;
    }

    private static int intOf(String sql, int param) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /** The one row the query finds, each column as a string. */
    private static List<String> row(String sql, int param) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new AssertionError("no row for " + param + ": " + sql);
                List<String> out = new ArrayList<>();
                for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) out.add(rs.getString(i));
                if (rs.next()) throw new AssertionError("more than one row for " + param + ": " + sql);
                return out;
            }
        }
    }

    private static void exec(String sql) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }
}
