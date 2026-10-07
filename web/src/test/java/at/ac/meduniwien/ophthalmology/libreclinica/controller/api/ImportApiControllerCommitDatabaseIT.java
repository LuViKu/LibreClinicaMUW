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

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.UserType;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.RuleSetDao;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyEventDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.service.rule.RuleSetService;
import at.ac.meduniwien.ophthalmology.libreclinica.service.rule.RuleSetServiceInterface;
import at.ac.meduniwien.ophthalmology.libreclinica.service.rule.StudyEventBeanListener;
import at.ac.meduniwien.ophthalmology.libreclinica.service.xml.OdmJaxbContext;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * {@code POST /api/v1/import/commit} writes an ODM file through the legacy
 * import pipeline: the values and their audit rows as the legacy import
 * writes them, what the preview showed and nothing else, and nothing at all
 * when the file cannot be imported.
 *
 * <p>Fixture: the demo seed's Demographics CRF (version {@code F_DEMOGRAPHICS_V1})
 * on the default study. Each test imports into its own subject's visit:
 * <ul>
 *   <li>M-005 V3 — scheduled visit, CRF not started (inserts);</li>
 *   <li>M-001 V3 — CRF started, I_HEIGHT_CM stored as 162 (overwrite);</li>
 *   <li>M-003 V1 — CRF signed; M-006 V1 — visit signed (refusals);</li>
 *   <li>M-005 V2, M-002 V1, M-002 V2 — started CRFs without a blood
 *       pressure value (single use, stale preview, rejected value).</li>
 * </ul>
 * Item ids: 1 I_CONSENT_DATE, 3 I_HEIGHT_CM, 5 I_BLOOD_PRESSURE_SYS.
 */
@SuppressWarnings("resource") // the context is only a bean-lookup holder for the legacy DAOs and lives as long as the test JVM
class ImportApiControllerCommitDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int STUDY_ID = 1;

    /**
     * The seeded Demographics CRF is authored by Liquibase and, unlike a CRF
     * uploaded through the application, has no item group. The legacy
     * validator reads an item's form metadata through its item group
     * ({@code ItemFormMetadataDAO.findAllByItemId}) and refuses an item
     * without one, so the fixture adds the "Ungrouped" group an upload
     * would have created.
     */
    @BeforeAll
    static void ungroupedItemGroup() throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection()) {
            int groupId;
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO item_group (name, crf_id, status_id, date_created, owner_id, oc_oid) "
                            + "VALUES ('Ungrouped', 1, 1, NOW(), 1, 'IG_DEMOG_UNGROUPED') RETURNING item_group_id");
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                groupId = rs.getInt(1);
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO item_group_metadata (item_group_id, crf_version_id, item_id, ordinal, "
                            + "show_group, repeating_group) "
                            + "SELECT ?, 1, i.item_id, i.item_id, true, false FROM item i WHERE i.item_id BETWEEN 1 AND 5")) {
                ps.setInt(1, groupId);
                ps.executeUpdate();
            }
        }
    }

    /**
     * Marking a CRF complete updates its visit, and StudyEventDAO.update
     * hands the visit to the rule listener, which reads the rule sets from
     * the Spring context the application sets. Here the context has none.
     */
    @BeforeAll
    static void ruleListenerContext() {
        GenericApplicationContext ctx = new GenericApplicationContext();
        ctx.registerBean("ruleSetDao", RuleSetDao.class, () -> Mockito.mock(RuleSetDao.class));
        ctx.registerBean("ruleSetService", RuleSetService.class, () -> Mockito.mock(RuleSetService.class));
        ctx.refresh();
        new StudyEventBeanListener(new StudyEventDAO(DATA_SOURCE)).setApplicationContext(ctx);
    }

    @AfterAll
    static void dropRuleListenerContext() {
        new StudyEventBeanListener(new StudyEventDAO(DATA_SOURCE)).setApplicationContext(null);
    }

    private MockMvc mockMvc() {
        // A rule service that finds no rules for the study, as for a study without any.
        ImportApiController controller = new ImportApiController(
                DATA_SOURCE, new OdmJaxbContext(), Mockito.mock(RuleSetServiceInterface.class));
        return ProductionMvc.standalone(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    /* ---------------------------------------------------------------- */
    /* Writes                                                           */
    /* ---------------------------------------------------------------- */

    @Test
    void commitStartsTheCrfAndWritesEachValueWithItsAuditRow() throws Exception {
        MockHttpSession session = sysadminSession();
        String token = upload(session, odm("SS_M005", "SE_V3_DAY90",
                "I_CONSENT_DATE", "2021-02-10", "I_HEIGHT_CM", "171", "I_BLOOD_PRESSURE_SYS", "118"))
                .andExpect(jsonPath("$.insertCount").value(3))
                .andExpect(jsonPath("$.overwriteCount").value(0))
                .andExpect(jsonPath("$.errorCount").value(0))
                .andReturn().getResponse().getContentAsString().transform(ImportApiControllerCommitDatabaseIT::token);

        commit(session, token, null, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rowsInserted").value(3))
                .andExpect(jsonPath("$.rowsOverwritten").value(0))
                .andExpect(jsonPath("$.auditLogStudyId").value(STUDY_ID));

        int eventCrfId = eventCrfOf(15);
        assertTrue(eventCrfId > 0, "the not-started CRF was not created");
        assertEquals("171", valueOf(3, eventCrfId));
        assertEquals("118", valueOf(5, eventCrfId));
        assertEquals("2021-02-10", valueOf(1, eventCrfId));
        // The item_data insert trigger's row for each value, as for a legacy import.
        assertEquals(3, count("SELECT COUNT(*) FROM audit_log_event a JOIN item_data id "
                + "ON id.item_data_id = a.entity_id WHERE a.audit_table = 'item_data' "
                + "AND a.audit_log_event_type_id = 1 AND a.reason_for_change = 'initial value' "
                + "AND id.event_crf_id = ?", eventCrfId));
        // Marked complete, as the legacy save does for a form without an EventCRFStatus.
        assertEquals(2, count("SELECT status_id FROM event_crf WHERE event_crf_id = ?", eventCrfId));
        assertEquals(1, count("SELECT COUNT(*) FROM audit_log_event WHERE audit_log_event_type_id = 80 "
                + "AND audit_table = 'study' AND entity_id = ? AND new_value LIKE ? AND new_value LIKE ?",
                STUDY_ID, "%token=" + token + "%", "%outcome=committed inserted=3%"));
    }

    @Test
    void anOverwriteNeedsAReasonAndRecordsItNextToTheValueChange() throws Exception {
        MockHttpSession session = sysadminSession();
        String token = upload(session, odm("SS_M001", "SE_V3_DAY90",
                "I_HEIGHT_CM", "165", "I_BLOOD_PRESSURE_SYS", "121"))
                .andExpect(jsonPath("$.insertCount").value(1))
                .andExpect(jsonPath("$.overwriteCount").value(1))
                .andExpect(jsonPath("$.rows[0].action").value("overwrite"))
                .andExpect(jsonPath("$.rows[0].before").value("162"))
                .andExpect(jsonPath("$.rows[1].action").value("insert"))
                .andReturn().getResponse().getContentAsString().transform(ImportApiControllerCommitDatabaseIT::token);

        // No reason: refused, and the token stays usable.
        commit(session, token, null, "replace")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("reasonForChange"));
        assertEquals("162", valueOf(3, 3));

        commit(session, token, "Source document re-read at monitoring visit", "replace")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rowsOverwritten").value(1))
                .andExpect(jsonPath("$.rowsInserted").value(1));

        assertEquals("165", valueOf(3, 3));
        assertEquals("121", valueOf(5, 3));
        // The item_data update trigger's value-change row, as for a legacy import ...
        assertEquals(1, count("SELECT COUNT(*) FROM audit_log_event WHERE audit_table = 'item_data' "
                + "AND entity_id = ? AND audit_log_event_type_id = 1 AND old_value = '162' AND new_value = '165'", 8));
        // ... and the reason, on its own row next to it.
        assertEquals(1, count("SELECT COUNT(*) FROM audit_log_event WHERE audit_table = 'item_data' "
                + "AND entity_id = ? AND audit_log_event_type_id = 140 "
                + "AND reason_for_change = 'Source document re-read at monitoring visit'", 8));
    }

    @Test
    void aTokenCommitsOnce() throws Exception {
        MockHttpSession session = sysadminSession();
        String token = upload(session, odm("SS_M005", "SE_V2_DAY30", "I_BLOOD_PRESSURE_SYS", "125"))
                .andReturn().getResponse().getContentAsString().transform(ImportApiControllerCommitDatabaseIT::token);

        commit(session, token, null, null).andExpect(status().isOk());
        commit(session, token, null, null)
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.message").value(containsString("already consumed")));

        assertEquals(1, count("SELECT COUNT(*) FROM item_data WHERE item_id = 5 AND event_crf_id = ?", 11));
    }

    /** Skip mode leaves a stored value as it is and needs no reason; new values are still written. */
    @Test
    void skipModeLeavesStoredValuesAndWritesTheNewOnes() throws Exception {
        MockHttpSession session = sysadminSession();
        String token = upload(session, odm("SS_M001", "SE_V2_DAY30",
                "I_HEIGHT_CM", "166", "I_BLOOD_PRESSURE_SYS", "119"))
                .andExpect(jsonPath("$.insertCount").value(1))
                .andExpect(jsonPath("$.overwriteCount").value(1))
                .andReturn().getResponse().getContentAsString().transform(ImportApiControllerCommitDatabaseIT::token);

        commit(session, token, null, "bogus")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("overwriteMode"));
        assertEquals("162", valueOf(3, 2));

        commit(session, token, null, "skip")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rowsInserted").value(1))
                .andExpect(jsonPath("$.rowsOverwritten").value(0))
                .andExpect(jsonPath("$.rowsSkipped").value(1));

        assertEquals("162", valueOf(3, 2), "skip mode overwrote a stored value");
        assertEquals("119", valueOf(5, 2));
        assertEquals(0, count("SELECT COUNT(*) FROM audit_log_event WHERE audit_table = 'item_data' "
                + "AND entity_id = ? AND (new_value = '166' OR audit_log_event_type_id = 140)", 6));
    }

    /* ---------------------------------------------------------------- */
    /* Refusals: nothing written                                        */
    /* ---------------------------------------------------------------- */

    /**
     * A locked or frozen study takes no import, as legacy refuses it; the
     * refusal does not spend the token. M-004's V2 (visit 11), scheduled
     * here, has no CRF yet, so a commit would start one.
     */
    @Test
    void aLockedOrFrozenStudyTakesNoImport() throws Exception {
        MockHttpSession session = sysadminSession();
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE study_event SET subject_event_status_id = 1, date_start = COALESCE(date_start, now()) "
                             + "WHERE study_event_id = 11")) {
            ps.executeUpdate();
        }
        String odm = odm("SS_M004", "SE_V2_DAY30", "I_BLOOD_PRESSURE_SYS", "127");
        String token = upload(session, odm)
                .andExpect(jsonPath("$.insertCount").value(1))
                .andReturn().getResponse().getContentAsString().transform(ImportApiControllerCommitDatabaseIT::token);
        int statusBefore = count("SELECT status_id FROM study WHERE study_id = ?", STUDY_ID);
        try {
            for (int closed : new int[] {6, 9}) {
                setStudyStatus(closed);
                commit(session, token, null, null).andExpect(status().isConflict());
                assertEquals(0, eventCrfOf(11), "an import into a study with status " + closed + " started a CRF");
                MockMultipartFile file = new MockMultipartFile("file", "import.xml",
                        MediaType.APPLICATION_XML_VALUE, odm.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                mockMvc().perform(multipart("/api/v1/import").file(file).session(session))
                        .andExpect(status().isConflict());
            }
        } finally {
            setStudyStatus(statusBefore);
        }

        commit(session, token, null, null).andExpect(status().isOk());
        assertEquals("127", valueOf(5, eventCrfOf(11)));
    }

    @Test
    void aSignedCrfIsNotWrittenInto() throws Exception {
        MockHttpSession session = sysadminSession();
        int before = count("SELECT COUNT(*) FROM item_data WHERE event_crf_id = ?", 6);
        String token = upload(session, odm("SS_M003", "SE_V1_INCLUSION", "I_BLOOD_PRESSURE_SYS", "140"))
                .andExpect(jsonPath("$.insertCount").value(0))
                .andExpect(jsonPath("$.warningCount").value(1))
                .andExpect(jsonPath("$.rows[0].action").value("skip"))
                .andReturn().getResponse().getContentAsString().transform(ImportApiControllerCommitDatabaseIT::token);

        commit(session, token, null, null).andExpect(status().isUnprocessableEntity());

        assertEquals(before, count("SELECT COUNT(*) FROM item_data WHERE event_crf_id = ?", 6));
        assertEquals(8, count("SELECT status_id FROM event_crf WHERE event_crf_id = ?", 6));
    }

    @Test
    void aSignedVisitRefusesTheFile() throws Exception {
        MockHttpSession session = sysadminSession();
        int before = count("SELECT COUNT(*) FROM item_data WHERE event_crf_id = ?", 12);
        String token = upload(session, odm("SS_M006", "SE_V1_INCLUSION", "I_BLOOD_PRESSURE_SYS", "140"))
                .andExpect(jsonPath("$.errorCount").value(1))
                .andExpect(jsonPath("$.rows[0].status").value("error"))
                .andReturn().getResponse().getContentAsString().transform(ImportApiControllerCommitDatabaseIT::token);

        commit(session, token, null, null).andExpect(status().isUnprocessableEntity());

        assertEquals(before, count("SELECT COUNT(*) FROM item_data WHERE event_crf_id = ?", 12));
    }

    /** A value stored after the preview showed its row as new must not be overwritten unseen. */
    @Test
    void aValueStoredSinceThePreviewRefusesTheCommit() throws Exception {
        MockHttpSession session = sysadminSession();
        String token = upload(session, odm("SS_M002", "SE_V1_INCLUSION", "I_BLOOD_PRESSURE_SYS", "130"))
                .andExpect(jsonPath("$.insertCount").value(1))
                .andReturn().getResponse().getContentAsString().transform(ImportApiControllerCommitDatabaseIT::token);
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement("INSERT INTO item_data "
                     + "(item_id, event_crf_id, status_id, value, date_created, owner_id, ordinal) "
                     + "VALUES (5, 4, 1, '131', now(), 1, 1)")) {
            ps.executeUpdate();
        }

        commit(session, token, null, null).andExpect(status().isConflict());

        assertEquals("131", valueOf(5, 4));
    }

    /** The legacy "hard" check: a value its item's type rejects stops the file. */
    @Test
    void aValueTheItemRejectsRefusesTheFile() throws Exception {
        MockHttpSession session = sysadminSession();
        String token = upload(session, odm("SS_M002", "SE_V2_DAY30", "I_BLOOD_PRESSURE_SYS", "high"))
                .andExpect(jsonPath("$.errorCount").value(0))
                .andReturn().getResponse().getContentAsString().transform(ImportApiControllerCommitDatabaseIT::token);

        commit(session, token, null, null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].message").value(containsString("not an integer")));

        assertEquals(0, count("SELECT COUNT(*) FROM item_data WHERE item_id = 5 AND event_crf_id = ?", 5));
    }

    /* ---------------------------------------------------------------- */
    /* Helpers                                                          */
    /* ---------------------------------------------------------------- */

    private org.springframework.test.web.servlet.ResultActions upload(MockHttpSession session, String odm)
            throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "import.xml",
                MediaType.APPLICATION_XML_VALUE, odm.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return mockMvc().perform(multipart("/api/v1/import").file(file).session(session))
                .andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions commit(MockHttpSession session, String token,
                                                                       String reason, String mode) throws Exception {
        StringBuilder body = new StringBuilder("{\"previewToken\":\"").append(token).append('"');
        if (reason != null) body.append(",\"reasonForChange\":").append(JSON.writeValueAsString(reason));
        if (mode != null) body.append(",\"overwriteMode\":\"").append(mode).append('"');
        body.append('}');
        return mockMvc().perform(post("/api/v1/import/commit")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.toString())
                .session(session));
    }

    private static String token(String previewJson) {
        try {
            JsonNode node = JSON.readTree(previewJson);
            return node.get("previewToken").asText();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** One subject, one visit, one Demographics form: item OID / value pairs. */
    private static String odm(String subjectOid, String eventOid, String... itemValues) {
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < itemValues.length; i += 2) {
            items.append("            <ItemData ItemOID=\"").append(itemValues[i])
                    .append("\" Value=\"").append(itemValues[i + 1]).append("\"/>\n");
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<ODM xmlns=\"http://www.cdisc.org/ns/odm/v1.3\" ODMVersion=\"1.3\" FileType=\"Snapshot\"\n"
                + "     FileOID=\"LCMUW_IMPORT_COMMIT_IT\" CreationDateTime=\"2026-09-30T00:00:00Z\">\n"
                + "  <ClinicalData StudyOID=\"S_DEFAULTS1\" MetaDataVersionOID=\"v1.0.0\">\n"
                + "    <SubjectData SubjectKey=\"" + subjectOid + "\">\n"
                + "      <StudyEventData StudyEventOID=\"" + eventOid + "\">\n"
                + "        <FormData FormOID=\"F_DEMOGRAPHICS_V1\">\n"
                + "          <ItemGroupData ItemGroupOID=\"IG_DEMOG_UNGROUPED\" TransactionType=\"Insert\">\n"
                + items
                + "          </ItemGroupData>\n"
                + "        </FormData>\n"
                + "      </StudyEventData>\n"
                + "    </SubjectData>\n"
                + "  </ClinicalData>\n"
                + "</ODM>\n";
    }

    private static MockHttpSession sysadminSession() {
        MockHttpSession session = new MockHttpSession();
        UserAccountBean ub = new UserAccountBean();
        ub.setId(1);
        ub.setName("root");
        ub.addUserType(UserType.SYSADMIN);
        session.setAttribute("userBean", ub);
        StudyBean study = new StudyBean();
        study.setId(STUDY_ID);
        study.setOid("S_DEFAULTS1");
        study.setName("Default Study");
        session.setAttribute("study", study);
        return session;
    }

    private static void setStudyStatus(int statusId) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE study SET status_id = ? WHERE study_id = ?")) {
            ps.setInt(1, statusId);
            ps.setInt(2, STUDY_ID);
            ps.executeUpdate();
        }
    }

    private static int eventCrfOf(int studyEventId) throws SQLException {
        return count("SELECT COALESCE(MAX(event_crf_id), 0) FROM event_crf WHERE study_event_id = ? "
                + "AND crf_version_id = 1", studyEventId);
    }

    private static String valueOf(int itemId, int eventCrfId) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT value FROM item_data WHERE item_id = ? AND event_crf_id = ? AND ordinal = 1")) {
            ps.setInt(1, itemId);
            ps.setInt(2, eventCrfId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static int count(String sql, Object... params) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }
}
