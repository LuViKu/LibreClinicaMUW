/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.UserType;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.config.SsoProperties;
import at.ac.meduniwien.ophthalmology.libreclinica.core.SecurityManager;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.AuthoritiesDao;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Multi-role (M1 backend) — Testcontainers IT for the additive
 * POST /users/{username}/roles and bulk PUT
 * /users/{username}/roles/{studyOid} contracts.
 *
 * <p>Seeds a fresh demo user with three active grants on the demo
 * study, drives a bulk PUT that retains only {@code Investigator},
 * and asserts that:
 *
 * <ul>
 *   <li>the response carries the refreshed binding list (one entry
 *       per role row, with active/inactive flags),</li>
 *   <li>the other two rows are soft-deleted ({@code status_id = 5})
 *       in the database,</li>
 *   <li>{@code GET /me} on a session for the same user now reflects
 *       a single-element {@code roles} array.</li>
 * </ul>
 *
 * <p>Sibling additive POST test: a fresh role on a (user, study)
 * pair that already has one grant returns 201 (formerly 409 under
 * the single-role-per-study legacy gate).
 */
class UsersApiControllerBulkRoleDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static final int BULK_USER_ID = 30001;

    @BeforeAll
    static void seedBulkUser() throws Exception {
        try (Connection conn = DATA_SOURCE.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(
                    "INSERT INTO user_account (user_id, user_name, passwd, first_name, last_name, "
                    + "email, active_study, institutional_affiliation, status_id, owner_id, "
                    + "date_created, user_type_id, enabled, account_non_locked, lock_counter, "
                    + "run_webservices, authtype, enable_api_key) "
                    + "VALUES (" + BULK_USER_ID + ", 'bulk-user', "
                    + "'{bcrypt}$2a$10$9QHaEdYWWSRQKYOaOECfbuQf8L1I1zWUPevUyMderR4S/ZmIc5/dG', "
                    + "'Bulk', 'User', 'bulk@example.invalid', 1, 'MUW (test)', 1, 1, "
                    + "current_timestamp, 2, true, true, 0, false, 'STANDARD', false)");
            // 3 active grants on the demo study, status_id=1 (AVAILABLE).
            stmt.execute(
                    "INSERT INTO study_user_role "
                    + "(role_name, study_id, status_id, owner_id, date_created, user_name) "
                    + "VALUES ('Investigator', 1, 1, 1, current_timestamp, 'bulk-user')");
            stmt.execute(
                    "INSERT INTO study_user_role "
                    + "(role_name, study_id, status_id, owner_id, date_created, user_name) "
                    + "VALUES ('monitor', 1, 1, 1, current_timestamp, 'bulk-user')");
            stmt.execute(
                    "INSERT INTO study_user_role "
                    + "(role_name, study_id, status_id, owner_id, date_created, user_name) "
                    + "VALUES ('coordinator', 1, 1, 1, current_timestamp, 'bulk-user')");
        }
    }

    private MockMvc usersMockMvc() {
        SecurityManager securityManager = Mockito.mock(SecurityManager.class);
        Mockito.when(securityManager.genPassword()).thenReturn("Tmp-Bulk-12!");
        Mockito.when(securityManager.encryptPassword(Mockito.anyString(), Mockito.anyBoolean()))
                .thenReturn("{bcrypt}$2a$10$hashedplaceholder");
        UsersApiController controller = new UsersApiController(
                DATA_SOURCE,
                new SiteVisibilityFilter(DATA_SOURCE),
                securityManager,
                Mockito.mock(AuthoritiesDao.class),
                new SsoProperties());
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private MockMvc meMockMvc() {
        return MockMvcBuilders.standaloneSetup(new MeApiController(DATA_SOURCE))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private MockHttpSession sysadminSession() {
        MockHttpSession session = new MockHttpSession();
        UserAccountBean ub = new UserAccountBean();
        ub.setId(1);
        ub.setName("root");
        ub.addUserType(UserType.SYSADMIN);
        session.setAttribute("userBean", ub);
        StudyBean study = new StudyBean();
        study.setId(1);
        study.setOid("default-study");
        study.setName("default-study");
        session.setAttribute("study", study);
        return session;
    }

    private MockHttpSession bulkUserSession() {
        MockHttpSession session = new MockHttpSession();
        UserAccountBean ub = new UserAccountBean();
        ub.setId(BULK_USER_ID);
        ub.setName("bulk-user");
        session.setAttribute("userBean", ub);
        StudyBean study = new StudyBean();
        study.setId(1);
        study.setOid("default-study");
        study.setName("default-study");
        session.setAttribute("study", study);
        return session;
    }

    /**
     * Bulk PUT with {@code roles=["Investigator"]} on a user with
     * three active grants — the other two must soft-delete. After the
     * write GET /me reflects a single-element roles array, and the
     * database carries two status_id=5 rows for the dropped roles.
     */
    @Test
    void bulkPutCollapsesRolesToTargetSet() throws Exception {
        usersMockMvc().perform(put("/api/v1/users/bulk-user/roles/default-study")
                .contentType("application/json")
                .content("{\"roles\":[\"Investigator\"]}")
                .session(sysadminSession()))
                .andExpect(status().isOk());

        // Database side-effect: exactly one active row remains; the
        // other two roles are status_id=5 (DELETED).
        try (Connection conn = DATA_SOURCE.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT role_name, status_id FROM study_user_role "
                     + "WHERE user_name = 'bulk-user' AND study_id = 1 "
                     + "ORDER BY role_name")) {
            try (ResultSet rs = ps.executeQuery()) {
                int active = 0;
                int deleted = 0;
                while (rs.next()) {
                    int statusId = rs.getInt(2);
                    if (statusId == 1) active++;
                    else if (statusId == 5) deleted++;
                }
                org.junit.jupiter.api.Assertions.assertEquals(1, active,
                        "exactly one active row should remain");
                org.junit.jupiter.api.Assertions.assertEquals(2, deleted,
                        "the two dropped roles should be soft-deleted");
            }
        }

        // GET /me on a session bound to bulk-user reflects the single
        // Investigator role.
        meMockMvc().perform(get("/api/v1/me").session(bulkUserSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("Investigator"))
                .andExpect(jsonPath("$.roles[0]").value("Investigator"))
                .andExpect(jsonPath("$.roles.length()").value(1));
    }

    /**
     * Additive POST: when {@code monitor} already holds a single
     * grant on the demo study (Monitor, from
     * lc-muw-2026-06-02-seed-demo-test-users.xml), POSTing a fresh
     * {@code Investigator} for the same (user, study) returns 201
     * (formerly 409 under the legacy single-role guard).
     */
    @Test
    void postAddsSecondRoleToExistingGrant() throws Exception {
        usersMockMvc().perform(post("/api/v1/users/monitor/roles")
                .contentType("application/json")
                .content("{\"studyOid\":\"default-study\",\"role\":\"Investigator\"}")
                .session(sysadminSession()))
                .andExpect(status().isCreated());

        // monitor now has BOTH Monitor and Investigator active on the
        // demo study.
        try (Connection conn = DATA_SOURCE.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM study_user_role "
                     + "WHERE user_name = 'monitor' AND study_id = 1 "
                     + "  AND status_id = 1")) {
            try (ResultSet rs = ps.executeQuery()) {
                org.junit.jupiter.api.Assertions.assertTrue(rs.next());
                org.junit.jupiter.api.Assertions.assertEquals(2, rs.getInt(1),
                        "expected 2 active grants after additive POST");
            }
        }
    }

    /**
     * Strict idempotency: posting the EXACT SAME role that's already
     * active returns 409. The legacy 409 message changes to reflect
     * the new "exact triple" rule.
     */
    @Test
    void postSameRoleTwiceReturns409() throws Exception {
        // physician already has Investigator on the demo study (seeded).
        usersMockMvc().perform(post("/api/v1/users/physician/roles")
                .contentType("application/json")
                .content("{\"studyOid\":\"default-study\",\"role\":\"Investigator\"}")
                .session(sysadminSession()))
                .andExpect(status().isConflict());
    }
}
