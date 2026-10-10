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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
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
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Removing a study subject in the SPA and restoring it gives back what the
 * removal took, as it was: a signed visit stays signed, a signed or locked
 * CRF comes back signed or locked, and each value comes back with its status
 * and provenance. A value removed on its own before stays removed.
 *
 * <p>Fixture: the demo seed's M-005 (study subject 5), with visits 13 (CRF
 * 10, values 25 to 28), 14 (CRF 11, values 29 and 30) and 15 (no CRF).
 * Status ids: 1 available, 5 removed, 6 locked, 7 auto-removed, 8 signed;
 * subject-event status 8 is signed.
 */
@SuppressWarnings("resource") // the context is only a bean-lookup holder for the legacy DAOs and lives as long as the test JVM
class SubjectRemovalCascadeDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static final int STUDY_ID = 1;

    /**
     * {@code StudyEventDAO.update} tells the rule listener about the visit,
     * and the listener reads its rule beans from the Spring context the
     * application sets; here there is none.
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
        return ProductionMvc.standalone(buildSubjectsController())
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void restoreGivesBackWhatTheRemovalTookAsItWas() throws Exception {
        exec("UPDATE study_event SET subject_event_status_id = 8 WHERE study_event_id = 13");
        exec("UPDATE event_crf SET status_id = 8 WHERE event_crf_id = 10");
        exec("UPDATE event_crf SET status_id = 6 WHERE event_crf_id = 11");
        exec("UPDATE item_data SET status_id = 5 WHERE item_data_id = 26");
        exec("UPDATE item_data SET source_kind = 'modality_baseline' WHERE item_data_id = 25");
        MockHttpSession dm = dataManagerSession();

        mockMvc().perform(post("/api/v1/subjects/M-005/remove").session(dm))
                .andExpect(status().isOk());
        assertEquals(5, statusOf("study_subject", 5));
        for (int visit : new int[] {13, 14, 15}) assertEquals(7, statusOf("study_event", visit), "visit " + visit);
        for (int crf : new int[] {10, 11}) assertEquals(7, statusOf("event_crf", crf), "event CRF " + crf);
        for (int value : new int[] {25, 27, 28, 29, 30}) {
            assertEquals(7, statusOf("item_data", value), "value " + value);
        }
        assertEquals(5, statusOf("item_data", 26));

        mockMvc().perform(post("/api/v1/subjects/M-005/restore").session(dm))
                .andExpect(status().isOk());
        assertEquals(1, statusOf("study_subject", 5));
        for (int visit : new int[] {13, 14, 15}) assertEquals(1, statusOf("study_event", visit), "visit " + visit);
        assertEquals(8, intOf("SELECT subject_event_status_id FROM study_event WHERE study_event_id = ?", 13),
                "the signed visit was unsigned");
        assertEquals(8, statusOf("event_crf", 10), "the signed CRF was unsigned");
        assertEquals(6, statusOf("event_crf", 11), "the locked CRF was unlocked");
        for (int value : new int[] {25, 27, 28, 29, 30}) {
            assertEquals(1, statusOf("item_data", value), "value " + value);
        }
        assertEquals(5, statusOf("item_data", 26), "a value removed on its own must stay removed");
        assertEquals("modality_baseline", sourceKindOf(25), "the value's provenance was lost");
    }

    /* ---------------------------------------------------------------- */
    /* Helpers                                                          */
    /* ---------------------------------------------------------------- */

    private static MockHttpSession dataManagerSession() throws SQLException {
        ResourceBundleProvider.updateLocale(Locale.ENGLISH);
        MockHttpSession session = new MockHttpSession();
        UserAccountBean ub = new UserAccountBean();
        ub.setId(userId("manual_dm"));
        ub.setName("manual_dm");
        session.setAttribute("userBean", ub);
        StudyBean study = new StudyBean();
        study.setId(STUDY_ID);
        study.setOid("S_DEFAULTS1");
        study.setName("Default Study");
        session.setAttribute("study", study);
        StudyUserRoleBean role = new StudyUserRoleBean();
        role.setRole(Role.STUDYDIRECTOR);
        role.setStudyId(STUDY_ID);
        session.setAttribute("userRole", role);
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

    private static String sourceKindOf(int itemDataId) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT source_kind FROM item_data WHERE item_data_id = ?")) {
            ps.setInt(1, itemDataId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
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

    private static void exec(String sql) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }
}
