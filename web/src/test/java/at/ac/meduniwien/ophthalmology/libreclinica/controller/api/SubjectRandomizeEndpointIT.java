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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.UserType;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 2026-07-02 — IT for the subject-randomization picker endpoint
 * ({@code POST /api/v1/studies/{oid}/group-classes/{id}/randomize}).
 *
 * <p>Covers:
 *
 * <ul>
 *   <li>Response shape (all fields populated, seed is 64 hex chars,
 *     source is one of the recognised enum values).</li>
 *   <li>Distribution — hits the endpoint {@link #TRIALS} times against
 *     a 1:1 class + asserts both groups land within a loose χ²-style
 *     bound (each ≥ 35 % of trials). A uniform picker on a fair coin
 *     comfortably clears that bar; a broken (always-same) picker fails
 *     immediately.</li>
 *   <li>Study-parameter gate — when
 *     {@code study_parameter.randomization} is not {@code enabled}
 *     the endpoint 409s.</li>
 *   <li>Wrong class type gate — a class of type Demographic (not Arm)
 *     is rejected 409.</li>
 * </ul>
 */
class SubjectRandomizeEndpointIT extends AbstractApiControllerDatabaseIT {

    private static final int TRIALS = 200;

    /** Group-class id for the seeded 2-group Arm class (populated in @BeforeEach). */
    private int armClassId;
    private int armGroupAId;
    private int armGroupBId;
    private int demographicClassId;

    @BeforeEach
    void seed() throws Exception {
        try (Connection c = DATA_SOURCE.getConnection()) {
            c.setAutoCommit(true);
            // Arm class + 2 groups on study 1 (the demo seed's default study).
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO study_group_class "
                            + "(name, study_id, group_class_type_id, subject_assignment, "
                            + " status_id, date_created, owner_id) "
                            + "VALUES ('IT Arm', 1, 1, 'optional', 1, NOW(), 1) "
                            + "RETURNING study_group_class_id")) {
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next(); armClassId = rs.getInt(1);
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO study_group (name, description, study_group_class_id) "
                            + "VALUES (?, '', ?) RETURNING study_group_id")) {
                ps.setString(1, "IT_GROUP_A"); ps.setInt(2, armClassId);
                try (ResultSet rs = ps.executeQuery()) { rs.next(); armGroupAId = rs.getInt(1); }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO study_group (name, description, study_group_class_id) "
                            + "VALUES (?, '', ?) RETURNING study_group_id")) {
                ps.setString(1, "IT_GROUP_B"); ps.setInt(2, armClassId);
                try (ResultSet rs = ps.executeQuery()) { rs.next(); armGroupBId = rs.getInt(1); }
            }
            // A Demographic class — used to prove type-Arm gating.
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO study_group_class "
                            + "(name, study_id, group_class_type_id, subject_assignment, "
                            + " status_id, date_created, owner_id) "
                            + "VALUES ('IT Demographic', 1, 3, 'optional', 1, NOW(), 1) "
                            + "RETURNING study_group_class_id")) {
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next(); demographicClassId = rs.getInt(1);
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO study_group (name, description, study_group_class_id) "
                            + "VALUES ('DEMO_A', '', ?), ('DEMO_B', '', ?)")) {
                ps.setInt(1, demographicClassId); ps.setInt(2, demographicClassId);
                ps.executeUpdate();
            }
            // Enable randomization on study 1.
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO study_parameter_value (study_id, parameter, value) "
                            + "VALUES (1, 'randomization', 'enabled') "
                            + "ON CONFLICT DO NOTHING")) {
                ps.executeUpdate();
            }
        }
    }

    @AfterEach
    void cleanup() throws Exception {
        try (Connection c = DATA_SOURCE.getConnection()) {
            c.setAutoCommit(true);
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM study_parameter_value WHERE study_id = 1 AND parameter = 'randomization'")) {
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM study_group WHERE study_group_class_id IN (?, ?)")) {
                ps.setInt(1, armClassId); ps.setInt(2, demographicClassId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM study_group_class WHERE study_group_class_id IN (?, ?)")) {
                ps.setInt(1, armClassId); ps.setInt(2, demographicClassId);
                ps.executeUpdate();
            }
        }
    }

    private MockMvc mockMvc() {
        GroupClassesApiController controller = new GroupClassesApiController(DATA_SOURCE);
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private MockHttpSession rootSession() {
        MockHttpSession s = new MockHttpSession();
        UserAccountBean ub = new UserAccountBean();
        ub.setId(1); ub.setName("root");
        ub.addUserType(UserType.SYSADMIN);  // pass StudyAdminAuthorization.userMayEditStudy
        s.setAttribute("userBean", ub);
        StudyBean study = new StudyBean();
        study.setId(1); study.setOid("S_DEFAULTS1");
        s.setAttribute("study", study);
        return s;
    }

    /** Response shape + seed hex-length check on a single call. */
    @Test
    void randomize_returnsWellFormedResponse() throws Exception {
        mockMvc().perform(post("/api/v1/studies/S_DEFAULTS1/group-classes/" + armClassId + "/randomize")
                        .session(rootSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groupId").isNumber())
                .andExpect(jsonPath("$.groupName").exists())
                .andExpect(jsonPath("$.seed").isString())
                .andExpect(jsonPath("$.source").value("RANDOMIZED_UNIFORM"))
                .andExpect(jsonPath("$.groupClassId").value(armClassId))
                .andExpect(jsonPath("$.groupClassName").value("IT Arm"))
                .andExpect(jsonPath("$.seed", org.hamcrest.Matchers.matchesRegex("[0-9a-f]{64}")));
    }

    /**
     * Distribution — {@link #TRIALS} calls; both groups must land at
     * least 35 % of the time. A broken picker (always-same, off-by-one,
     * or biased mod) fails this instantly.
     */
    @Test
    void randomize_distributesAcrossBothGroups() throws Exception {
        ObjectMapper om = new ObjectMapper();
        Map<Integer, Integer> counts = new HashMap<>();
        MockMvc mv = mockMvc();
        for (int i = 0; i < TRIALS; i++) {
            String body = mv.perform(post("/api/v1/studies/S_DEFAULTS1/group-classes/" + armClassId + "/randomize")
                            .session(rootSession()))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            JsonNode json = om.readTree(body);
            int gid = json.get("groupId").asInt();
            counts.merge(gid, 1, Integer::sum);
        }
        int a = counts.getOrDefault(armGroupAId, 0);
        int b = counts.getOrDefault(armGroupBId, 0);
        assertEquals(TRIALS, a + b, "picks must always land on one of the seeded groups");
        int floor = (int) (TRIALS * 0.35);
        assertTrue(a >= floor,
                "A too rare: A=" + a + " B=" + b + " (need each ≥ " + floor + ")");
        assertTrue(b >= floor,
                "B too rare: A=" + a + " B=" + b + " (need each ≥ " + floor + ")");
    }

    /** Study parameter gate — flip the flag off + expect 409. */
    @Test
    void randomize_rejectsWhenStudyParamDisabled() throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE study_parameter_value SET value='disabled' "
                             + "WHERE study_id=1 AND parameter='randomization'")) {
            c.setAutoCommit(true);
            ps.executeUpdate();
        }
        mockMvc().perform(post("/api/v1/studies/S_DEFAULTS1/group-classes/" + armClassId + "/randomize")
                        .session(rootSession()))
                .andExpect(status().isConflict());
    }

    /** Class type gate — Demographic class cannot be randomized. */
    @Test
    void randomize_rejectsNonArmClass() throws Exception {
        mockMvc().perform(post("/api/v1/studies/S_DEFAULTS1/group-classes/" + demographicClassId + "/randomize")
                        .session(rootSession()))
                .andExpect(status().isConflict());
    }
}
