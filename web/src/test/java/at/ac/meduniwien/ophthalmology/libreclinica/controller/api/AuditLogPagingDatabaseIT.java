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
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.UserType;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.i18n.util.ResourceBundleProvider;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The audit logs reach the whole trail: the database filters, counts and
 * pages it, and the export writes every matching row. Until 2026-09-30 the
 * views and the export read the newest 500 rows of the study and filtered
 * those.
 *
 * <p>Fixture on the default study, each part on a day of its own:
 * <ul>
 *   <li>one row about a value of M-004 on 2020-01-02, older than all
 *       others;</li>
 *   <li>600 rows about a value of M-001 (item I_HEIGHT_CM), a second apart
 *       on 2030-01-01, so they are the newest 600;</li>
 *   <li>rows of every variant on 2031-01-01, and rows by root and by no
 *       user on 2032-01-01.</li>
 * </ul>
 */
class AuditLogPagingDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int STUDY_ID = 1;
    private static final String STUDY_OID = "S_DEFAULTS1";
    private static final int BULK = 600;

    private static int oldM004Row;

    @BeforeAll
    static void seedTrail() throws SQLException {
        // item_data 23 is M-004's consent date (event_crf 9); item_data 3 is
        // M-001's height (event_crf 1); study_event 1 is M-001's V1.
        oldM004Row = insert("INSERT INTO audit_log_event (audit_log_event_type_id, audit_date, user_id, "
                + "audit_table, entity_id, entity_name, old_value, new_value, event_crf_id) "
                + "VALUES (1, TIMESTAMP '2020-01-02 10:00:00', 1, 'item_data', 23, 'I_CONSENT_DATE', '', "
                + "'IT_OLD_M004', 9) RETURNING audit_id");
        exec("INSERT INTO audit_log_event (audit_log_event_type_id, audit_date, user_id, audit_table, "
                + "entity_id, entity_name, old_value, new_value, event_crf_id) "
                + "SELECT 1, TIMESTAMP '2030-01-01 00:00:00' + g * INTERVAL '1 second', 1, 'item_data', 3, "
                + "'I_HEIGHT_CM', '162', 'IT_BULK_' || g, 1 FROM generate_series(1, " + BULK + ") g");
        String day = "TIMESTAMP '2031-01-01 12:00:00'";
        // type, table, entity, entity_name, old, new, reason, event_crf_id, study_event_id
        variantRow(1, "item_data", 3, "I_HEIGHT_CM", "162", "163", null, 1, null, day);   // data
        variantRow(1, "item_data", 3, "I_HEIGHT_CM", "163", "164", "typo", 1, null, day); // reason
        variantRow(31, "study_event", 1, "Status", "4", "8", null, null, 1, day);          // signed
        variantRow(32, "event_crf", 1, "EventCRF SDV Status", "FALSE", "TRUE", null, 1, null, day); // sdv
        variantRow(28, "study_subject", 1, "Group", "", "Arm A", null, null, null, day);   // group change
        variantRow(55, "study", STUDY_ID, STUDY_OID, "", "rows=3", null, null, null, day); // admin
        variantRow(63, "study", STUDY_ID, STUDY_OID, "", "ack:rules", "decided", null, null, day); // reason
        variantRow(27, "item_data", 3, "I_HEIGHT_CM", "164", "165", null, 1, null, day);   // 27 on item_data -> 140
        variantRow(11, "event_crf", 1, "date_completed", "2020-10-06", "", null, 1, null, day); // -> 138 data
        variantRow(11, "event_crf", 1, "Status", "4", "2", null, 1, null, day);            // 11 data
        variantRow(140, "item_data", 3, "I_HEIGHT_CM", "165", "166", "re-read", 1, null, day); // reason
        variantRow(128, "ingest_item", 7, "status", "DISMISSED", "UNBOUND study_event_id=1", "wrong eye",
                null, 1, day); // -> 137 data; a file's reason does not count
        String actorsDay = "TIMESTAMP '2032-01-01 12:00:00'";
        exec("INSERT INTO audit_log_event (audit_log_event_type_id, audit_date, user_id, audit_table, entity_id, "
                + "entity_name, old_value, new_value) VALUES (55, " + actorsDay + ", NULL, 'study', " + STUDY_ID
                + ", '" + STUDY_OID + "', '', 'by nobody')");
        exec("INSERT INTO audit_log_event (audit_log_event_type_id, audit_date, user_id, audit_table, entity_id, "
                + "entity_name, old_value, new_value) VALUES (55, " + actorsDay + ", 1, 'study', " + STUDY_ID
                + ", '" + STUDY_OID + "', '', 'by root')");
    }

    private MockMvc mockMvc() {
        return MockMvcBuilders.standaloneSetup(
                new AuditApiController(DATA_SOURCE, new SiteVisibilityFilter(DATA_SOURCE))).build();
    }

    /* ---------------------------------------------------------------- */
    /* Reach                                                            */
    /* ---------------------------------------------------------------- */

    /** 600 newer rows of another subject used to push this one out of view. */
    @Test
    void aSubjectsOldestRowIsReachable() throws Exception {
        mockMvc().perform(get("/api/v1/audit").param("subjectId", "M-004").session(studySession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$..id", hasItem(String.valueOf(oldM004Row))));
    }

    @Test
    void theSystemLogReachesItToo() throws Exception {
        mockMvc().perform(get("/api/v1/audit/system").param("subjectId", "m-004").session(sysadminSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$..id", hasItem(String.valueOf(oldM004Row))));
    }

    @Test
    void theExportHoldsEveryMatchingRow() throws Exception {
        byte[] xlsx = mockMvc().perform(get("/api/v1/audit/export.xlsx")
                        .param("from", "2030-01-01").param("to", "2030-01-01")
                        .session(studySession()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            assertEquals(BULK, wb.getSheetAt(0).getPhysicalNumberOfRows() - 1, "data rows in the export");
        }
    }

    /* ---------------------------------------------------------------- */
    /* Paging                                                           */
    /* ---------------------------------------------------------------- */

    @Test
    void pagesCoverEveryMatchingRowOnceNewestFirst() throws Exception {
        List<String> ids = new ArrayList<>();
        List<String> afters = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            JsonNode body = page("/api/v1/audit", studySession(), "from", "2030-01-01", "to", "2030-01-01",
                    "pageSize", "250", "page", String.valueOf(page));
            assertEquals(BULK, body.get("totalCount").asInt());
            assertEquals(page, body.get("page").asInt());
            assertEquals(page < 2 ? 250 : 100, body.get("events").size(), "rows on page " + page);
            for (JsonNode e : body.get("events")) {
                ids.add(e.get("id").asText());
                afters.add(e.get("after").asText());
            }
        }
        assertEquals(BULK, new HashSet<>(ids).size(), "a row was repeated or skipped across pages");
        for (int i = 0; i < BULK; i++) {
            assertEquals("IT_BULK_" + (BULK - i), afters.get(i), "row " + i + " out of newest-first order");
        }
    }

    @Test
    void pageSizeIsCapped() throws Exception {
        JsonNode body = page("/api/v1/audit", studySession(), "from", "2030-01-01", "to", "2030-01-01",
                "pageSize", "100000");
        assertEquals(AuditApiController.MAX_PAGE_SIZE, body.get("pageSize").asInt());
        assertEquals(AuditApiController.MAX_PAGE_SIZE, body.get("events").size());
    }

    /* ---------------------------------------------------------------- */
    /* Filters                                                          */
    /* ---------------------------------------------------------------- */

    /**
     * The database's variant filter returns exactly the rows the view labels
     * with that variant, including the rows whose type id meant something
     * else and the reason override.
     */
    @Test
    void theVariantFilterReturnsTheRowsTheViewLabelsThatWay() throws Exception {
        JsonNode all = page("/api/v1/audit", studySession(), "from", "2031-01-01", "to", "2031-01-01");
        Map<String, Set<String>> byVariant = new HashMap<>();
        for (JsonNode e : all.get("events")) {
            byVariant.computeIfAbsent(e.get("variant").asText(), v -> new HashSet<>()).add(e.get("id").asText());
        }
        assertEquals(12, all.get("totalCount").asInt());
        assertEquals(4, byVariant.get("data").size(), byVariant.toString());
        assertEquals(4, byVariant.get("reason-for-change").size(), byVariant.toString());
        for (String variant : List.of("data", "signed", "sdv", "admin", "reason-for-change",
                "subject-group-change", "query")) {
            JsonNode filtered = page("/api/v1/audit", studySession(), "from", "2031-01-01", "to", "2031-01-01",
                    "variant", variant);
            Set<String> ids = new HashSet<>();
            for (JsonNode e : filtered.get("events")) ids.add(e.get("id").asText());
            assertEquals(byVariant.getOrDefault(variant, Set.of()), ids, "variant " + variant);
            assertEquals(ids.size(), filtered.get("totalCount").asInt(), "count of variant " + variant);
        }
    }

    @Test
    void theActorFilterTellsRootFromNoUser() throws Exception {
        mockMvc().perform(get("/api/v1/audit").param("from", "2032-01-01").param("to", "2032-01-01")
                        .param("actor", "system").session(studySession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.events[0].after").value("by nobody"));
        mockMvc().perform(get("/api/v1/audit").param("from", "2032-01-01").param("to", "2032-01-01")
                        .param("actor", "ROOT").session(studySession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.events[0].after").value("by root"));
    }

    @Test
    void theItemFilterFindsTheRowsAboutAnItemsValues() throws Exception {
        mockMvc().perform(get("/api/v1/audit").param("from", "2030-01-01").param("to", "2030-01-01")
                        .param("item", "i_height_cm").session(studySession()))
                .andExpect(jsonPath("$.totalCount").value(BULK));
        mockMvc().perform(get("/api/v1/audit").param("from", "2030-01-01").param("to", "2030-01-01")
                        .param("item", "I_CONSENT_DATE").session(studySession()))
                .andExpect(jsonPath("$.totalCount").value(0));
        mockMvc().perform(get("/api/v1/audit").param("item", "I_CONSENT_DATE").param("subjectId", "M-004")
                        .session(studySession()))
                .andExpect(jsonPath("$.events[*].id", hasItem(String.valueOf(oldM004Row))));
    }

    @Test
    void aDayThatIsNotADayIsRefused() throws Exception {
        mockMvc().perform(get("/api/v1/audit").param("from", "30.09.2026").session(studySession()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void facetsNameEveryActorAndSubjectNotOnlyThoseOnAPage() throws Exception {
        mockMvc().perform(get("/api/v1/audit/facets").session(studySession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actors", hasItems("root", "system")))
                .andExpect(jsonPath("$.subjects", hasItems("M-001", "M-004", "M-007")));
        mockMvc().perform(get("/api/v1/audit/facets").session(studySession()))
                .andExpect(jsonPath("$.subjects", not(hasItem("M-404"))));
    }

    /* ---------------------------------------------------------------- */
    /* Helpers                                                          */
    /* ---------------------------------------------------------------- */

    private JsonNode page(String path, MockHttpSession session, String... params) throws Exception {
        var request = get(path).session(session);
        for (int i = 0; i < params.length; i += 2) request = request.param(params[i], params[i + 1]);
        String body = mockMvc().perform(request).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode node = JSON.readTree(body);
        assertTrue(node.has("events"), body);
        return node;
    }

    private static MockHttpSession studySession() {
        ResourceBundleProvider.updateLocale(Locale.ENGLISH);
        MockHttpSession session = new MockHttpSession();
        UserAccountBean ub = new UserAccountBean();
        ub.setId(1);
        ub.setName("root");
        session.setAttribute("userBean", ub);
        StudyBean study = new StudyBean();
        study.setId(STUDY_ID);
        study.setOid(STUDY_OID);
        study.setName("Default Study");
        session.setAttribute("study", study);
        StudyUserRoleBean role = new StudyUserRoleBean();
        role.setRole(Role.ADMIN);
        role.setStudyId(STUDY_ID);
        session.setAttribute("userRole", role);
        return session;
    }

    private static MockHttpSession sysadminSession() {
        MockHttpSession session = studySession();
        ((UserAccountBean) session.getAttribute("userBean")).addUserType(UserType.SYSADMIN);
        return session;
    }

    private static void variantRow(int type, String table, int entityId, String entityName, String oldValue,
                                   String newValue, String reason, Integer eventCrfId, Integer studyEventId,
                                   String when) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement("INSERT INTO audit_log_event (audit_log_event_type_id, "
                     + "audit_date, user_id, audit_table, entity_id, entity_name, old_value, new_value, "
                     + "reason_for_change, event_crf_id, study_event_id) VALUES (?, " + when
                     + ", 1, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setInt(1, type);
            ps.setString(2, table);
            ps.setInt(3, entityId);
            ps.setString(4, entityName);
            ps.setString(5, oldValue);
            ps.setString(6, newValue);
            ps.setString(7, reason);
            ps.setObject(8, eventCrfId, java.sql.Types.INTEGER);
            ps.setObject(9, studyEventId, java.sql.Types.INTEGER);
            ps.executeUpdate();
        }
    }

    private static int insert(String sql) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static void exec(String sql) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }
}
