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
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.i18n.util.ResourceBundleProvider;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Phase E.6 build-study tracker — operator-discretion task ack IT.
 *
 * <p>Pins three contract surfaces for {@link BuildStudyApiController}:
 * <ul>
 *   <li><strong>Auto-complete on positive count.</strong> Seeding one
 *       {@code study_group_class} row flips {@code groups.status}
 *       from {@code optional} to {@code complete} in the GET payload.</li>
 *   <li><strong>Manual ack on zero count.</strong> POST to
 *       {@code /build-status/acknowledge} with
 *       {@code taskId=groups} flips the status without seeding a
 *       group_class — the GET tracker reflects the acknowledgement.</li>
 *   <li><strong>Bad taskId rejected.</strong> POST with
 *       {@code taskId=bogus} returns 400 + ValidationErrorBody
 *       with {@code errors[].field=taskId}, and the DB has no ack
 *       row written.</li>
 * </ul>
 *
 * <p>Default seed (study_id=1, oid=S_DEFAULTS1) has zero groups,
 * zero rules, and zero sites, so the optional cards default to
 * "optional" without further fixture setup.
 */
class BuildStudyAcknowledgeDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static final String DEFAULT_STUDY_OID = "S_DEFAULTS1";
    private static final int DEFAULT_STUDY_ID = 1;

    @BeforeEach
    void truncateAcks() throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM study_build_task_ack WHERE study_id = ?")) {
            ps.setInt(1, DEFAULT_STUDY_ID);
            ps.executeUpdate();
        }
    }

    @AfterEach
    void cleanGroupClasses() throws SQLException {
        // Wipe any group_class rows the auto-complete test inserted so
        // a subsequent test sees the default zero-count baseline.
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM study_group_class WHERE name = ?")) {
            ps.setString(1, "IT_AUTO_COMPLETE_GROUP");
            ps.executeUpdate();
        }
    }

    private MockMvc mockMvc() {
        BuildStudyApiController controller = new BuildStudyApiController(
                DATA_SOURCE,
                new SiteVisibilityFilter(DATA_SOURCE));
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void buildStatusAutoCompletesWhenOptionalCountExceedsZero() throws Exception {
        seedGroupClass();

        mockMvc().perform(get("/api/v1/studies/" + DEFAULT_STUDY_OID + "/build-status")
                .session(adminSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks[?(@.id == 'groups')].status").value("complete"));
    }

    @Test
    void buildStatusReturnsCompleteWhenOperatorAcknowledgedZeroCount() throws Exception {
        // POST acknowledge for groups — no group_class row exists.
        mockMvc().perform(post("/api/v1/studies/" + DEFAULT_STUDY_OID + "/build-status/acknowledge")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"taskId\":\"groups\"}")
                .session(adminSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks[?(@.id == 'groups')].status").value("complete"));

        // Follow-up GET still reflects the acknowledgement.
        mockMvc().perform(get("/api/v1/studies/" + DEFAULT_STUDY_OID + "/build-status")
                .session(adminSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks[?(@.id == 'groups')].status").value("complete"));

        // DB has exactly one ack row.
        assertEquals(1, countAcks(DEFAULT_STUDY_ID, "groups"));
    }

    @Test
    void acknowledgeRejectsUnknownTaskId() throws Exception {
        mockMvc().perform(post("/api/v1/studies/" + DEFAULT_STUDY_OID + "/build-status/acknowledge")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"taskId\":\"bogus\"}")
                .session(adminSession()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'taskId')]").exists());

        assertEquals(0, countAcks(DEFAULT_STUDY_ID, "bogus"));
    }

    @Test
    void acknowledgeIsIdempotent() throws Exception {
        for (int i = 0; i < 2; i++) {
            mockMvc().perform(post("/api/v1/studies/" + DEFAULT_STUDY_OID + "/build-status/acknowledge")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"taskId\":\"sites\"}")
                    .session(adminSession()))
                    .andExpect(status().isOk());
        }
        assertEquals(1, countAcks(DEFAULT_STUDY_ID, "sites"));
    }

    /* ====================================================================== */
    /* Helpers                                                                */
    /* ====================================================================== */

    private MockHttpSession adminSession() {
        ResourceBundleProvider.updateLocale(Locale.ENGLISH);
        MockHttpSession session = new MockHttpSession();
        UserAccountBean ub = new UserAccountBean();
        ub.setId(1);
        ub.setName("root");
        session.setAttribute("userBean", ub);
        StudyBean study = new StudyBean();
        study.setId(DEFAULT_STUDY_ID);
        study.setOid(DEFAULT_STUDY_OID);
        study.setName("default-study");
        session.setAttribute("study", study);
        StudyUserRoleBean role = new StudyUserRoleBean();
        role.setRole(Role.STUDYDIRECTOR);
        role.setStudyId(DEFAULT_STUDY_ID);
        session.setAttribute("userRole", role);
        return session;
    }

    private void seedGroupClass() throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO study_group_class "
                             + "(name, study_id, group_class_type_id, status_id, "
                             + " owner_id, date_created, subject_assignment) "
                             + "VALUES (?, ?, 1, 1, 1, now(), 'optional')")) {
            ps.setString(1, "IT_AUTO_COMPLETE_GROUP");
            ps.setInt(2, DEFAULT_STUDY_ID);
            ps.executeUpdate();
        }
    }

    private int countAcks(int studyId, String taskId) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM study_build_task_ack "
                             + "WHERE study_id = ? AND task_id = ?")) {
            ps.setInt(1, studyId);
            ps.setString(2, taskId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        }
        return 0;
    }
}
