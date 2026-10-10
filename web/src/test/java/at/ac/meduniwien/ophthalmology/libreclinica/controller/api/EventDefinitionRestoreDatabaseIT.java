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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

/**
 * Restoring an event definition gives back what its removal took, as it
 * was: a signed or locked CRF comes back signed or locked. A visit that the
 * removal of its subject took is not the definition's to give back; it stays
 * removed with the subject.
 *
 * <p>Fixture: the demo seed's V2 Day 30 ({@code SE_V2_DAY30}), with visits
 * 2 (M-001, CRF 2), 5 (M-002, CRF 5), 8 (M-003, CRF 7, signed), 11 (M-004,
 * no CRF), 14 (M-005, CRF 11), 17 (M-006, CRF 13, signed) and 20 (M-007,
 * CRF 16). Status ids: 1 available, 5 removed, 6 locked, 7 auto-removed,
 * 8 signed.
 */
class EventDefinitionRestoreDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static final String STUDY_OID = "S_DEFAULTS1";
    private static final String DEFINITION = "/api/v1/studies/" + STUDY_OID + "/event-definitions/SE_V2_DAY30";

    private MockMvc mockMvc() {
        return ProductionMvc.standalone(new EventDefinitionsApiController(DATA_SOURCE))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void restoreGivesBackWhatTheRemovalTookAsItWas() throws Exception {
        // M-005's CRF is locked. M-002 is removed, as the subject removal
        // leaves it: its visits and CRFs auto-removed, each CRF and value
        // recording the status it had.
        exec("UPDATE event_crf SET status_id = 6 WHERE event_crf_id = 11");
        exec("UPDATE study_subject SET status_id = 5 WHERE study_subject_id = 2");
        exec("UPDATE study_event SET status_id = 7 WHERE study_subject_id = 2");
        exec("UPDATE event_crf SET old_status_id = status_id, status_id = 7 WHERE study_subject_id = 2");
        exec("UPDATE item_data SET old_status_id = status_id, status_id = 7 WHERE event_crf_id IN "
                + "(SELECT event_crf_id FROM event_crf WHERE study_subject_id = 2)");

        mockMvc().perform(post(DEFINITION + "/disable").session(adminSession()))
                .andExpect(status().isOk());
        for (int visit : new int[] {2, 8, 11, 14, 17, 20}) {
            assertEquals(7, statusOf("study_event", visit), "visit " + visit);
        }
        for (int crf : new int[] {2, 7, 11, 13, 16}) assertEquals(7, statusOf("event_crf", crf), "CRF " + crf);

        mockMvc().perform(post(DEFINITION + "/restore").session(adminSession()))
                .andExpect(status().isOk());
        for (int visit : new int[] {2, 8, 11, 14, 17, 20}) {
            assertEquals(1, statusOf("study_event", visit), "visit " + visit);
        }
        int signedCrf = statusOf("event_crf", 7);
        int otherSignedCrf = statusOf("event_crf", 13);
        int lockedCrf = statusOf("event_crf", 11);
        int removedSubjectsVisit = statusOf("study_event", 5);
        int removedSubjectsCrf = statusOf("event_crf", 5);
        assertAll(
                () -> assertEquals(8, signedCrf, "the signed CRF was unsigned"),
                () -> assertEquals(8, otherSignedCrf, "the signed CRF was unsigned"),
                () -> assertEquals(6, lockedCrf, "the locked CRF was unlocked"),
                () -> assertEquals(7, removedSubjectsVisit, "the removed subject's visit came back"),
                () -> assertEquals(7, removedSubjectsCrf, "the removed subject's CRF came back"));
        for (int crf : new int[] {2, 16}) assertEquals(1, statusOf("event_crf", crf), "CRF " + crf);
        assertEquals(5, statusOf("study_subject", 2));
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
        study.setId(1);
        study.setOid(STUDY_OID);
        study.setName("Default Study");
        session.setAttribute("study", study);
        return session;
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

    private static void exec(String sql) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }
}
