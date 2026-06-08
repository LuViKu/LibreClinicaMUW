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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.Statement;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Multi-role (M1 backend) — Testcontainers IT pinning the {@code /me}
 * response shape when a user holds more than one active grant on the
 * bound study.
 *
 * <p>Seeds an extra demo user {@code multirole-user} (id 20001) with
 * three active {@code study_user_role} rows on study_id=1: legacy
 * {@code investigator}, {@code monitor}, and {@code coordinator}.
 * RoleMapper projects those to Investigator + Monitor + CRC; sorted
 * by priority (Monitor > CRC > Investigator) the SPA receives
 * {@code role = "Monitor"} (the highest) and {@code roles =
 * ["Monitor", "CRC", "Investigator"]}.
 */
class MeApiControllerRolesDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static final int MULTI_USER_ID = 20001;

    @BeforeAll
    static void seedMultiRoleUser() throws Exception {
        try (Connection conn = DATA_SOURCE.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(
                    "INSERT INTO user_account (user_id, user_name, passwd, first_name, last_name, "
                    + "email, active_study, institutional_affiliation, status_id, owner_id, "
                    + "date_created, user_type_id, enabled, account_non_locked, lock_counter, "
                    + "run_webservices, authtype, enable_api_key) "
                    + "VALUES (" + MULTI_USER_ID + ", 'multirole-user', "
                    + "'{bcrypt}$2a$10$9QHaEdYWWSRQKYOaOECfbuQf8L1I1zWUPevUyMderR4S/ZmIc5/dG', "
                    + "'Multi', 'Role', 'multirole@example.invalid', 1, 'MUW (test)', 1, 1, "
                    + "current_timestamp, 2, true, true, 0, false, 'STANDARD', false)");
            // Three active grants on study_id=1, each a different legacy
            // role name. status_id=1 (AVAILABLE) on every row.
            stmt.execute(
                    "INSERT INTO study_user_role "
                    + "(role_name, study_id, status_id, owner_id, date_created, user_name) "
                    + "VALUES ('Investigator', 1, 1, 1, current_timestamp, 'multirole-user')");
            stmt.execute(
                    "INSERT INTO study_user_role "
                    + "(role_name, study_id, status_id, owner_id, date_created, user_name) "
                    + "VALUES ('monitor', 1, 1, 1, current_timestamp, 'multirole-user')");
            stmt.execute(
                    "INSERT INTO study_user_role "
                    + "(role_name, study_id, status_id, owner_id, date_created, user_name) "
                    + "VALUES ('coordinator', 1, 1, 1, current_timestamp, 'multirole-user')");
        }
    }

    private MockMvc mockMvc() {
        return MockMvcBuilders.standaloneSetup(new MeApiController(DATA_SOURCE))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    /**
     * Session whose userBean is the multi-role user (not sysadmin) +
     * the demo study. The controller will call findAllRolesByUserName
     * via the real DataSource to collect the active grants.
     */
    private MockHttpSession multiRoleSession() {
        MockHttpSession session = new MockHttpSession();
        UserAccountBean ub = new UserAccountBean();
        ub.setId(MULTI_USER_ID);
        ub.setName("multirole-user");
        session.setAttribute("userBean", ub);
        StudyBean study = new StudyBean();
        study.setId(1);
        study.setOid("default-study");
        study.setName("default-study");
        session.setAttribute("study", study);
        return session;
    }

    @Test
    void getMeReturnsAllActiveRolesSortedByPriority() throws Exception {
        mockMvc().perform(get("/api/v1/me").session(multiRoleSession()))
                .andExpect(status().isOk())
                // Sorted by priority desc: Monitor (3) > CRC (2) > Investigator (1).
                // Administrator + Data Manager are absent — not in the seeded grants.
                .andExpect(jsonPath("$.role").value("Monitor"))
                .andExpect(jsonPath("$.roles[0]").value("Monitor"))
                .andExpect(jsonPath("$.roles[1]").value("CRC"))
                .andExpect(jsonPath("$.roles[2]").value("Investigator"))
                .andExpect(jsonPath("$.roles.length()").value(3));
    }

    /**
     * Single-role users still pin the shape: {@code roles} is always
     * an array, even when it carries exactly one entry. Hits the
     * existing demo user {@code monitor} (seeded in
     * lc-muw-2026-06-02-seed-demo-test-users.xml).
     */
    @Test
    void getMeReturnsSingleRoleArrayForSingleGrantUser() throws Exception {
        MockHttpSession session = new MockHttpSession();
        UserAccountBean ub = new UserAccountBean();
        ub.setId(3); // demo `monitor` user
        ub.setName("monitor");
        session.setAttribute("userBean", ub);
        StudyBean study = new StudyBean();
        study.setId(1);
        study.setOid("default-study");
        study.setName("default-study");
        session.setAttribute("study", study);

        mockMvc().perform(get("/api/v1/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("Monitor"))
                .andExpect(jsonPath("$.roles[0]").value("Monitor"))
                .andExpect(jsonPath("$.roles.length()").value(1));
    }
}
