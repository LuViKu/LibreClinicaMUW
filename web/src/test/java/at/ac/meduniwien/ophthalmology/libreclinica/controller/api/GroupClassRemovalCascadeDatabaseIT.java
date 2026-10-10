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

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.UserType;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.i18n.util.ResourceBundleProvider;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Removing a subject group class in the SPA does what legacy
 * {@code RemoveSubjectGroupClassServlet} does: the class becomes removed and
 * its subject assignments auto-removed; restoring it
 * ({@code RestoreSubjectGroupClassServlet}) brings back exactly the
 * auto-removed assignments.
 *
 * <p>The fixture is an arm with two groups and three assignments, one of
 * them removed on its own. Status ids: 1 available, 5 removed,
 * 7 auto-removed.
 */
class GroupClassRemovalCascadeDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static final String STUDY_OID = "S_DEFAULTS1";
    private static final int STUDY_ID = 1;

    private int classId;
    private int assignedA;
    private int assignedB;
    private int removedOnItsOwn;

    private MockMvc mockMvc() {
        return ProductionMvc.standalone(new GroupClassesApiController(DATA_SOURCE))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @BeforeEach
    void seedArm() throws SQLException {
        classId = insertReturningId("INSERT INTO study_group_class "
                + "(name, study_id, group_class_type_id, status_id, owner_id, date_created, subject_assignment) "
                + "VALUES ('IT_CASCADE_ARM', " + STUDY_ID + ", 1, 1, 1, now(), 'optional') "
                + "RETURNING study_group_class_id");
        int groupA = insertReturningId("INSERT INTO study_group (name, description, study_group_class_id) "
                + "VALUES ('Arm A', '', " + classId + ") RETURNING study_group_id");
        int groupB = insertReturningId("INSERT INTO study_group (name, description, study_group_class_id) "
                + "VALUES ('Arm B', '', " + classId + ") RETURNING study_group_id");
        assignedA = assign(1, groupA, 1);
        assignedB = assign(2, groupB, 1);
        removedOnItsOwn = assign(4, groupA, 5);
    }

    @AfterEach
    void dropArm() throws SQLException {
        exec("DELETE FROM subject_group_map WHERE study_group_class_id = " + classId);
        exec("DELETE FROM study_group WHERE study_group_class_id = " + classId);
        exec("DELETE FROM study_group_class WHERE study_group_class_id = " + classId);
    }

    @Test
    void removalImpactNamesTheGroupsAndTheAssignmentsTheRemovalTakesWithIt() throws Exception {
        mockMvc().perform(get("/api/v1/studies/" + STUDY_OID + "/group-classes/" + classId + "/removal-impact")
                .session(adminSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groups").value(2))
                .andExpect(jsonPath("$.subjectAssignments").value(2));
    }

    @Test
    void removalMarksTheAssignmentsAutoRemovedAndRestoreBringsThemBack() throws Exception {
        mockMvc().perform(post("/api/v1/studies/" + STUDY_OID + "/group-classes/" + classId + "/disable")
                .session(adminSession()))
                .andExpect(status().isOk());

        assertEquals(5, statusOf("study_group_class", classId));
        assertEquals(7, statusOf("subject_group_map", assignedA));
        assertEquals(7, statusOf("subject_group_map", assignedB));
        assertEquals(5, statusOf("subject_group_map", removedOnItsOwn),
                "an assignment removed on its own must stay removed");

        mockMvc().perform(post("/api/v1/studies/" + STUDY_OID + "/group-classes/" + classId + "/restore")
                .session(adminSession()))
                .andExpect(status().isOk());

        assertEquals(1, statusOf("study_group_class", classId));
        assertEquals(1, statusOf("subject_group_map", assignedA));
        assertEquals(1, statusOf("subject_group_map", assignedB));
        assertEquals(5, statusOf("subject_group_map", removedOnItsOwn));
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

    private int assign(int studySubjectId, int groupId, int statusId) throws SQLException {
        return insertReturningId("INSERT INTO subject_group_map "
                + "(study_group_class_id, study_subject_id, study_group_id, status_id, owner_id, date_created) "
                + "VALUES (" + classId + ", " + studySubjectId + ", " + groupId + ", " + statusId + ", 1, now()) "
                + "RETURNING subject_group_map_id");
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

    private static int insertReturningId(String sql) throws SQLException {
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
