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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.UserType;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.i18n.util.ResourceBundleProvider;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Removing an event definition in the SPA does what legacy
 * {@code RemoveEventDefinitionServlet} does: the definition becomes removed
 * and its CRF assignments, visits, event CRFs and values become
 * auto-removed; restoring it brings back exactly the auto-removed rows.
 *
 * <p>Each test works on its own seeded definition (V2 Day 30, V3 Day 90), so
 * the order they run in does not matter. Status ids: 1 available,
 * 5 removed, 7 auto-removed.
 */
class EventDefinitionRemovalCascadeDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static final String STUDY_OID = "S_DEFAULTS1";
    private static final int STUDY_ID = 1;

    private MockMvc mockMvc() {
        return MockMvcBuilders.standaloneSetup(new EventDefinitionsApiController(DATA_SOURCE))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    /**
     * The confirm dialog's numbers are the rows the removal then marks
     * auto-removed, and after it nothing under the definition is left
     * reachable.
     */
    @Test
    void removalImpactCountsExactlyWhatTheRemovalMarksAutoRemoved() throws Exception {
        int sedId = sedId("SE_V2_DAY30");
        int visitsBefore = count("SELECT COUNT(*) FROM study_event WHERE study_event_definition_id = ? "
                + "AND status_id <> 5", sedId);
        assertTrue(visitsBefore > 0, "seed has no visits for V2 Day 30");

        mockMvc().perform(get("/api/v1/studies/" + STUDY_OID + "/event-definitions/SE_V2_DAY30/removal-impact")
                .session(adminSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.crfAssignments").value(
                        count("SELECT COUNT(*) FROM event_definition_crf WHERE study_event_definition_id = ? "
                                + "AND parent_id IS NULL AND status_id <> 5", sedId)))
                .andExpect(jsonPath("$.visits").value(visitsBefore))
                .andExpect(jsonPath("$.subjects").value(
                        count("SELECT COUNT(DISTINCT study_subject_id) FROM study_event "
                                + "WHERE study_event_definition_id = ? AND status_id <> 5", sedId)))
                .andExpect(jsonPath("$.eventCrfs").value(countEventCrfs(sedId, "<> 5")))
                .andExpect(jsonPath("$.itemValues").value(countItemData(sedId, "<> 5")));

        int eventCrfsBefore = countEventCrfs(sedId, "<> 5");
        int itemsBefore = countItemData(sedId, "<> 5");

        mockMvc().perform(post("/api/v1/studies/" + STUDY_OID + "/event-definitions/SE_V2_DAY30/disable")
                .session(adminSession()))
                .andExpect(status().isOk());

        assertEquals(visitsBefore, count("SELECT COUNT(*) FROM study_event "
                + "WHERE study_event_definition_id = ? AND status_id = 7", sedId));
        assertEquals(eventCrfsBefore, countEventCrfs(sedId, "= 7"));
        assertEquals(itemsBefore, countItemData(sedId, "= 7"));
        assertEquals(0, count("SELECT COUNT(*) FROM study_event "
                + "WHERE study_event_definition_id = ? AND status_id NOT IN (5, 7)", sedId),
                "a visit of the removed definition is still reachable");
    }

    /**
     * A disable and a restore are inverses. A visit and a value removed on
     * their own before the definition was removed stay removed throughout.
     */
    @Test
    void removalCascadesAndRestoreBringsBackOnlyTheAutoRemovedRows() throws Exception {
        int sedId = sedId("SE_V3_DAY90");
        // M-001's V3 CRF (event_crf 3) and its one value (item_data 8); M-004's
        // V3 visit (study_event 12) has no CRF. Remove both on their own.
        exec("UPDATE item_data SET status_id = 5 WHERE item_data_id = 8");
        exec("UPDATE study_event SET status_id = 5 WHERE study_event_id = 12");
        int crfAssignments = count("SELECT COUNT(*) FROM event_definition_crf "
                + "WHERE study_event_definition_id = ? AND parent_id IS NULL", sedId);
        int visits = count("SELECT COUNT(*) FROM study_event WHERE study_event_definition_id = ?", sedId);

        mockMvc().perform(post("/api/v1/studies/" + STUDY_OID + "/event-definitions/SE_V3_DAY90/disable")
                .session(adminSession()))
                .andExpect(status().isOk());

        assertEquals(5, statusOf("study_event_definition", sedId));
        assertEquals(crfAssignments, count("SELECT COUNT(*) FROM event_definition_crf "
                + "WHERE study_event_definition_id = ? AND parent_id IS NULL AND status_id = 7", sedId));
        assertEquals(visits - 1, count("SELECT COUNT(*) FROM study_event "
                + "WHERE study_event_definition_id = ? AND status_id = 7", sedId));
        assertEquals(5, statusOf("study_event", 12), "a visit removed on its own must stay removed");
        assertEquals(7, statusOf("event_crf", 3));
        assertEquals(5, statusOf("item_data", 8), "a value removed on its own must stay removed");
        assertEquals(0, countItemData(sedId, "NOT IN (5, 7)"));

        mockMvc().perform(post("/api/v1/studies/" + STUDY_OID + "/event-definitions/SE_V3_DAY90/restore")
                .session(adminSession()))
                .andExpect(status().isOk());

        assertEquals(1, statusOf("study_event_definition", sedId));
        assertEquals(0, count("SELECT COUNT(*) FROM event_definition_crf "
                + "WHERE study_event_definition_id = ? AND status_id = 7", sedId));
        assertEquals(0, count("SELECT COUNT(*) FROM study_event "
                + "WHERE study_event_definition_id = ? AND status_id = 7", sedId));
        assertEquals(0, countEventCrfs(sedId, "= 7"));
        assertEquals(0, countItemData(sedId, "= 7"));
        assertEquals(1, statusOf("event_crf", 3));
        assertEquals(5, statusOf("study_event", 12));
        assertEquals(5, statusOf("item_data", 8));
    }

    /* ---------------------------------------------------------------- */
    /* Helpers                                                          */
    /* ---------------------------------------------------------------- */

    private static MockHttpSession adminSession() {
        ResourceBundleProvider.updateLocale(Locale.ENGLISH);
        MockHttpSession session = new MockHttpSession();
        UserAccountBean ub = new UserAccountBean();
        ub.setId(1);
        ub.setName("root");
        ub.addUserType(UserType.SYSADMIN);
        session.setAttribute("userBean", ub);
        StudyBean study = new StudyBean();
        study.setId(STUDY_ID);
        study.setOid(STUDY_OID);
        study.setName("Default Study");
        session.setAttribute("study", study);
        return session;
    }

    private static int sedId(String oid) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT study_event_definition_id FROM study_event_definition WHERE oc_oid = ?")) {
            ps.setString(1, oid);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static int countEventCrfs(int sedId, String statusPredicate) throws SQLException {
        return count("SELECT COUNT(*) FROM event_crf ec "
                + "JOIN study_event se ON se.study_event_id = ec.study_event_id "
                + "WHERE se.study_event_definition_id = ? AND ec.status_id " + statusPredicate, sedId);
    }

    private static int countItemData(int sedId, String statusPredicate) throws SQLException {
        return count("SELECT COUNT(*) FROM item_data id "
                + "JOIN event_crf ec ON ec.event_crf_id = id.event_crf_id "
                + "JOIN study_event se ON se.study_event_id = ec.study_event_id "
                + "WHERE se.study_event_definition_id = ? AND id.status_id " + statusPredicate, sedId);
    }

    private static int statusOf(String table, int id) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT status_id FROM " + table + " WHERE " + table + "_id = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static int count(String sql, int param) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
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
