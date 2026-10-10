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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.UserType;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.RuleSetDao;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyEventDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.i18n.util.ResourceBundleProvider;
import at.ac.meduniwien.ophthalmology.libreclinica.service.rule.RuleSetService;
import at.ac.meduniwien.ophthalmology.libreclinica.service.rule.StudyEventBeanListener;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.context.support.GenericApplicationContext;

/**
 * The SPA's subject and event-definition removals and restores, mixed with
 * each other and with the legacy servlets' writes, which stay reachable. A
 * restore gives back what its own removal took, as it was, and leaves what
 * another removal took; a CRF a legacy path removed or changed since comes
 * back available, as the legacy restore makes it.
 *
 * <p>The legacy writes are made here as {@code EventCRFDAO.update} and
 * {@code ItemDataDAO.update} make them: the new status, {@code date_updated}
 * set to now, and {@code old_status_id} written back unchanged.
 *
 * <p>Fixture: the demo seed's subjects M-001 (study subject 1; visits 1, 2,
 * 3 with CRFs 1, 2, 3), M-002 (2; visits 4, 5, 6, CRFs 4 and 5, values 13
 * and 14 on CRF 5), M-004 (4; visits 10, 11, 12, CRF 9 with values 23 and
 * 24) and M-007 (7; visits 19, 20, 21, CRFs 15 and 16), and the event
 * definition V2 Day 30 ({@code SE_V2_DAY30}: visits 2, 5, 8, 11, 14, 17,
 * 20). Each test leaves the subjects and the definition as it found them.
 * Status ids: 1 available, 2 completed, 5 removed, 6 locked, 7 auto-removed.
 */
@SuppressWarnings("resource") // the context is only a bean-lookup holder for the legacy DAOs and lives as long as the test JVM
class RemovalRestoreAcrossPathsDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static final int STUDY_ID = 1;
    private static final String STUDY_OID = "S_DEFAULTS1";
    private static final String DEFINITION = "/api/v1/studies/" + STUDY_OID + "/event-definitions/SE_V2_DAY30";

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
        return ProductionMvc.standalone(buildSubjectsController(),
                        new EventDefinitionsApiController(DATA_SOURCE))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    /**
     * A definition removal leaves a visit its subject's removal took to that
     * removal: neither counted, nor touched, so that the subject's restore
     * still gives back its CRF as it was.
     */
    @Test
    void aDefinitionRemovalLeavesAVisitItsSubjectsRemovalTook() throws Exception {
        exec("UPDATE event_crf SET status_id = 2 WHERE event_crf_id = 5");
        exec("UPDATE item_data SET status_id = 2 WHERE event_crf_id = 5");
        MockHttpSession dm = dataManagerSession();
        mockMvc().perform(post("/api/v1/subjects/M-002/remove").session(dm)).andExpect(status().isOk());
        assertEquals(7, statusOf("study_event", 5));
        String visitAsRemoved = text("SELECT date_updated::text || '/' || update_id FROM study_event "
                + "WHERE study_event_id = ?", 5);

        mockMvc().perform(get(DEFINITION + "/removal-impact").session(adminSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visits").value(6))
                .andExpect(jsonPath("$.subjects").value(6))
                .andExpect(jsonPath("$.eventCrfs").value(count(
                        "SELECT COUNT(*) FROM event_crf ec JOIN study_event se ON se.study_event_id = ec.study_event_id "
                                + "WHERE se.study_event_definition_id = 2 AND se.status_id NOT IN (5, 7) "
                                + "AND ec.status_id NOT IN (5, 7)")))
                .andExpect(jsonPath("$.itemValues").value(count(
                        "SELECT COUNT(*) FROM item_data id JOIN event_crf ec ON ec.event_crf_id = id.event_crf_id "
                                + "JOIN study_event se ON se.study_event_id = ec.study_event_id "
                                + "WHERE se.study_event_definition_id = 2 AND se.status_id NOT IN (5, 7) "
                                + "AND ec.status_id NOT IN (5, 7) AND id.status_id NOT IN (5, 7)")));

        mockMvc().perform(post(DEFINITION + "/disable").session(adminSession())).andExpect(status().isOk());
        assertEquals(visitAsRemoved, text("SELECT date_updated::text || '/' || update_id FROM study_event "
                + "WHERE study_event_id = ?", 5), "the definition's removal took the removed subject's visit");

        mockMvc().perform(post(DEFINITION + "/restore").session(adminSession())).andExpect(status().isOk());
        assertEquals(7, statusOf("study_event", 5));
        mockMvc().perform(post("/api/v1/subjects/M-002/restore").session(dm)).andExpect(status().isOk());
        assertEquals(1, statusOf("study_event", 5));
        assertEquals(2, statusOf("event_crf", 5), "the completed CRF did not come back completed");
        for (int value : new int[] {13, 14}) assertEquals(2, statusOf("item_data", value), "value " + value);
    }

    /** A subject's restore leaves the visits of a definition removed on its own with the definition. */
    @Test
    void aSubjectRestoreLeavesARemovedDefinitionsVisitsRemoved() throws Exception {
        MockHttpSession dm = dataManagerSession();
        mockMvc().perform(post(DEFINITION + "/disable").session(adminSession())).andExpect(status().isOk());
        try {
            mockMvc().perform(post("/api/v1/subjects/M-007/remove").session(dm)).andExpect(status().isOk());
            mockMvc().perform(post("/api/v1/subjects/M-007/restore").session(dm)).andExpect(status().isOk());

            assertEquals(7, statusOf("study_event", 20), "the removed definition's visit came back");
            assertEquals(7, statusOf("event_crf", 16), "the removed definition's CRF came back");
            for (int visit : new int[] {19, 21}) assertEquals(1, statusOf("study_event", visit), "visit " + visit);
            assertEquals(1, statusOf("event_crf", 15));
        } finally {
            mockMvc().perform(post(DEFINITION + "/restore").session(adminSession())).andExpect(status().isOk());
        }
        assertEquals(1, statusOf("study_event", 20));
        assertEquals(1, statusOf("event_crf", 16));
    }

    /**
     * A subject the legacy servlet removed comes back with its CRFs and
     * values available, even when their {@code old_status_id} still holds
     * what an earlier removal recorded there.
     */
    @Test
    void aSubjectTheLegacyServletRemovedComesBackAvailable() throws Exception {
        exec("UPDATE event_crf SET old_status_id = 7 WHERE event_crf_id = 9");
        exec("UPDATE item_data SET old_status_id = 7 WHERE event_crf_id = 9");
        legacyRemove(4);

        mockMvc().perform(post("/api/v1/subjects/M-004/restore").session(dataManagerSession()))
                .andExpect(status().isOk());
        assertEquals(1, statusOf("event_crf", 9), "the CRF stayed removed under a restored subject");
        for (int value : new int[] {23, 24}) assertEquals(1, statusOf("item_data", value), "value " + value);
    }

    /**
     * What an SPA removal recorded does not outlive it: after a legacy
     * restore and a legacy removal, the SPA restore brings a CRF back as
     * the legacy removal found it, not as the first removal did.
     */
    @Test
    void aCrfComesBackAsTheLastRemovalFoundIt() throws Exception {
        exec("UPDATE event_crf SET status_id = 6 WHERE event_crf_id = 3");
        MockHttpSession dm = dataManagerSession();
        mockMvc().perform(post("/api/v1/subjects/M-001/remove").session(dm)).andExpect(status().isOk());
        // RestoreStudySubjectServlet: the subject, its visits, CRFs and values available.
        exec("UPDATE study_subject SET status_id = 1, date_updated = now() WHERE study_subject_id = 1");
        exec("UPDATE study_event SET status_id = 1, date_updated = now() WHERE study_subject_id = 1");
        exec("UPDATE event_crf SET status_id = 1, date_updated = now() WHERE study_subject_id = 1");
        exec("UPDATE item_data SET status_id = 1, date_updated = now() WHERE event_crf_id IN "
                + "(SELECT event_crf_id FROM event_crf WHERE study_subject_id = 1)");
        legacyRemove(1);

        mockMvc().perform(post("/api/v1/subjects/M-001/restore").session(dm)).andExpect(status().isOk());
        for (int crf : new int[] {1, 2, 3}) assertEquals(1, statusOf("event_crf", crf), "CRF " + crf);
        assertEquals(1, statusOf("item_data", 8));
    }

    /* ---------------------------------------------------------------- */
    /* Helpers                                                          */
    /* ---------------------------------------------------------------- */

    /** RemoveStudySubjectServlet: the subject removed, its visits, CRFs and values auto-removed. */
    private static void legacyRemove(int studySubjectId) throws SQLException {
        exec("UPDATE study_subject SET status_id = 5, date_updated = now() WHERE study_subject_id = " + studySubjectId);
        exec("UPDATE study_event SET status_id = 7, date_updated = now() WHERE study_subject_id = " + studySubjectId);
        exec("UPDATE event_crf SET status_id = 7, date_updated = now() WHERE study_subject_id = " + studySubjectId);
        exec("UPDATE item_data SET status_id = 7, date_updated = now() WHERE event_crf_id IN "
                + "(SELECT event_crf_id FROM event_crf WHERE study_subject_id = " + studySubjectId + ")");
    }

    private static MockHttpSession adminSession() {
        ResourceBundleProvider.updateLocale(Locale.ENGLISH);
        MockHttpSession session = new MockHttpSession();
        UserAccountBean ub = new UserAccountBean();
        ub.setId(1);
        ub.setName("root");
        ub.addUserType(UserType.SYSADMIN);
        session.setAttribute("userBean", ub);
        session.setAttribute("study", study());
        return session;
    }

    private static MockHttpSession dataManagerSession() throws SQLException {
        ResourceBundleProvider.updateLocale(Locale.ENGLISH);
        MockHttpSession session = new MockHttpSession();
        UserAccountBean ub = new UserAccountBean();
        ub.setId(count("SELECT user_id FROM user_account WHERE user_name = 'manual_dm'"));
        ub.setName("manual_dm");
        session.setAttribute("userBean", ub);
        session.setAttribute("study", study());
        StudyUserRoleBean role = new StudyUserRoleBean();
        role.setRole(Role.STUDYDIRECTOR);
        role.setStudyId(STUDY_ID);
        session.setAttribute("userRole", role);
        return session;
    }

    private static StudyBean study() {
        StudyBean study = new StudyBean();
        study.setId(STUDY_ID);
        study.setOid(STUDY_OID);
        study.setName("Default Study");
        return study;
    }

    private static int statusOf(String table, int id) throws SQLException {
        return Integer.parseInt(text("SELECT status_id FROM " + table + " WHERE " + table + "_id = ?", id));
    }

    private static String text(String sql, int param) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private static int count(String sql) throws SQLException {
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
