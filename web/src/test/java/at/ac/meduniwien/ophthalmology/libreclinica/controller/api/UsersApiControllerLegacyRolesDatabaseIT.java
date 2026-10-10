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

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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

/**
 * The two data entry roles the SPA neither grants nor migrates,
 * {@code ra} and {@code ra2}, through the role endpoints. The role list
 * names them; a write changes them only when the request says so, so
 * a client that shows them as Investigator and sends that back cannot
 * turn them into Investigator, or drop them, by accident.
 */
class UsersApiControllerLegacyRolesDatabaseIT extends AbstractApiControllerDatabaseIT {

    @BeforeAll
    static void seed() throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection()) {
            user(c, "legacy-ra", "ra");
            user(c, "legacy-ra-mon", "ra", "monitor");
            user(c, "legacy-ra2", "ra2");
            user(c, "legacy-ra-single", "ra");
            user(c, "legacy-ra-post", "ra");
            user(c, "legacy-ra-remove", "ra", "monitor");
            user(c, "plain-monitor", "monitor");
            user(c, "plain-multi", "monitor", "coordinator");
            user(c, "list-ra", "ra");
            // The legacy row first, so the list meets it before the granted one.
            user(c, "list-ra2-inv", "ra2", "Investigator");
        }
    }

    @Test
    void theUserListNamesTheLegacyRoleUnlessTheUserHoldsAGrantedOne() throws Exception {
        mockMvc().perform(get("/api/v1/users").session(sysadmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.username == 'list-ra')].legacyRole", contains("ra")))
                .andExpect(jsonPath("$[?(@.username == 'list-ra2-inv')].role", contains("Investigator")))
                .andExpect(jsonPath("$[?(@.username == 'list-ra2-inv')].legacyRole", empty()));
    }

    @Test
    void theRoleFilterMatchesALegacyRoleByItsOwnName() throws Exception {
        // A legacy row is not an Investigator; a granted Investigator row is.
        mockMvc().perform(get("/api/v1/users").param("role", "Investigator").session(sysadmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.username == 'list-ra')]", empty()))
                .andExpect(jsonPath("$[?(@.username == 'list-ra2-inv')].role", contains("Investigator")))
                .andExpect(jsonPath("$[?(@.username == 'list-ra2-inv')].legacyRole", empty()));
        mockMvc().perform(get("/api/v1/users").param("role", "ra").session(sysadmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.username == 'list-ra')].legacyRole", contains("ra")))
                .andExpect(jsonPath("$[?(@.username == 'list-ra2-inv')]", empty()));
        mockMvc().perform(get("/api/v1/users").param("role", "ra2").session(sysadmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.username == 'list-ra2-inv')].legacyRole", contains("ra2")))
                .andExpect(jsonPath("$[?(@.username == 'list-ra')]", empty()));
    }

    @Test
    void theRoleListNamesTheLegacyRole() throws Exception {
        mockMvc().perform(get("/api/v1/users/legacy-ra-mon/roles").session(sysadmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.legacyRole == 'ra')]", hasSize(1)))
                .andExpect(jsonPath("$[?(@.role == 'Monitor' && @.legacyRole == null)]", hasSize(1)));
    }

    @Test
    void aSaveThatDoesNotSayWhatToDoWithTheLegacyRoleChangesNothing() throws Exception {
        // What a client that shows the legacy role as Investigator sends back.
        mockMvc().perform(put("/api/v1/users/legacy-ra/roles/S_DEFAULTS1").session(sysadmin())
                        .contentType("application/json").content("{\"roles\":[\"Investigator\"]}"))
                .andExpect(status().isConflict());
        assertEquals(List.of("ra:1"), rows("legacy-ra"));
    }

    @Test
    void keepingTheLegacyRoleLeavesItAsItIs() throws Exception {
        mockMvc().perform(put("/api/v1/users/legacy-ra-mon/roles/S_DEFAULTS1").session(sysadmin())
                        .contentType("application/json")
                        .content("{\"roles\":[\"Monitor\",\"CRC\"],\"legacyRoles\":[\"ra\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.legacyRole == 'ra' && @.active == true)]", hasSize(1)));
        assertEquals(List.of("coordinator:1", "monitor:1", "ra:1"), rows("legacy-ra-mon"));
    }

    @Test
    void droppingTheLegacyRoleIsAnExplicitChoice() throws Exception {
        mockMvc().perform(put("/api/v1/users/legacy-ra2/roles/S_DEFAULTS1").session(sysadmin())
                        .contentType("application/json")
                        .content("{\"roles\":[\"Investigator\"],\"legacyRoles\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.role == 'Investigator' && @.active == true && @.legacyRole == null)]",
                        hasSize(1)));
        // The new row's name is whatever the DAO stores for Investigator.
        List<String> rows = rows("legacy-ra2");
        assertTrue(rows.contains("ra2:5"), rows.toString());
        assertEquals(1, rows.stream().filter(r -> r.endsWith(":1")).count(), rows.toString());
    }

    @Test
    void aLegacyRoleCanOnlyBeKeptWhereItIsHeld() throws Exception {
        mockMvc().perform(put("/api/v1/users/plain-monitor/roles/S_DEFAULTS1").session(sysadmin())
                        .contentType("application/json")
                        .content("{\"roles\":[\"Monitor\"],\"legacyRoles\":[\"ra\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'legacyRoles')]").exists());
        mockMvc().perform(put("/api/v1/users/plain-monitor/roles/S_DEFAULTS1").session(sysadmin())
                        .contentType("application/json")
                        .content("{\"roles\":[\"Monitor\"],\"legacyRoles\":[\"Investigator\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'legacyRoles')]").exists());
        assertEquals(List.of("monitor:1"), rows("plain-monitor"));
    }

    @Test
    void theSingleRoleChangeLeavesLegacyAndMultiRoleBindingsAlone() throws Exception {
        mockMvc().perform(put("/api/v1/users/legacy-ra-single/roles/S_DEFAULTS1").session(sysadmin())
                        .contentType("application/json").content("{\"role\":\"Investigator\"}"))
                .andExpect(status().isConflict());
        assertEquals(List.of("ra:1"), rows("legacy-ra-single"));
        // It rewrites every row of the pair, so two roles would become one role twice.
        mockMvc().perform(put("/api/v1/users/plain-multi/roles/S_DEFAULTS1").session(sysadmin())
                        .contentType("application/json").content("{\"role\":\"Monitor\"}"))
                .andExpect(status().isConflict());
        assertEquals(List.of("coordinator:1", "monitor:1"), rows("plain-multi"));
    }

    @Test
    void grantingInvestigatorOverTheLegacyRoleIsRefused() throws Exception {
        mockMvc().perform(post("/api/v1/users/legacy-ra-post/roles").session(sysadmin())
                        .contentType("application/json")
                        .content("{\"studyOid\":\"S_DEFAULTS1\",\"role\":\"Investigator\"}"))
                .andExpect(status().isConflict());
        assertEquals(List.of("ra:1"), rows("legacy-ra-post"));
        // Any other role is added next to it, as before.
        mockMvc().perform(post("/api/v1/users/legacy-ra-post/roles").session(sysadmin())
                        .contentType("application/json")
                        .content("{\"studyOid\":\"S_DEFAULTS1\",\"role\":\"Monitor\"}"))
                .andExpect(status().isCreated());
        assertEquals(List.of("monitor:1", "ra:1"), rows("legacy-ra-post"));
    }

    @Test
    void emptyRoleSetsTakeTheUserOffTheStudy() throws Exception {
        mockMvc().perform(put("/api/v1/users/legacy-ra-remove/roles/S_DEFAULTS1").session(sysadmin())
                        .contentType("application/json").content("{\"roles\":[],\"legacyRoles\":[]}"))
                .andExpect(status().isOk());
        assertEquals(List.of("monitor:5", "ra:5"), rows("legacy-ra-remove"));
    }

    /* ------------------------------------------------------------------ */

    /** Every row of the user on the Default Study, as lower-cased {@code role_name:status_id}, sorted. */
    private static List<String> rows(String user) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT role_name, status_id FROM study_user_role WHERE user_name = ? AND study_id = 1 "
                             + "ORDER BY lower(role_name), status_id")) {
            ps.setString(1, user);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getString(1).toLowerCase(Locale.ROOT) + ":" + rs.getInt(2));
            }
        }
        return out;
    }

    private static void user(Connection c, String name, String... roles) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO user_account (user_name, passwd, first_name, last_name, email, active_study, "
                        + "institutional_affiliation, status_id, owner_id, date_created, user_type_id, enabled, "
                        + "account_non_locked, lock_counter, run_webservices, authtype, enable_api_key) "
                        + "VALUES (?, 'x', 'F', 'L', ?, 1, 'MUW', 1, 1, now(), 2, true, true, 0, false, "
                        + "'STANDARD', false)")) {
            ps.setString(1, name);
            ps.setString(2, name + "@example.invalid");
            ps.executeUpdate();
        }
        for (String role : roles) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO study_user_role (role_name, study_id, status_id, owner_id, date_created, "
                            + "user_name) VALUES (?, 1, 1, 1, now(), ?)")) {
                ps.setString(1, role);
                ps.setString(2, name);
                ps.executeUpdate();
            }
        }
    }

    private MockMvc mockMvc() {
        UsersApiController controller = new UsersApiController(
                DATA_SOURCE,
                new SiteVisibilityFilter(DATA_SOURCE),
                Mockito.mock(SecurityManager.class),
                Mockito.mock(AuthoritiesDao.class),
                new SsoProperties());
        return ProductionMvc.standalone(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private static MockHttpSession sysadmin() {
        MockHttpSession session = new MockHttpSession();
        UserAccountBean ub = new UserAccountBean();
        ub.setId(1);
        ub.setName("root");
        ub.addUserType(UserType.SYSADMIN);
        session.setAttribute("userBean", ub);
        StudyBean study = new StudyBean();
        study.setId(1);
        study.setOid("S_DEFAULTS1");
        study.setName("S_DEFAULTS1");
        session.setAttribute("study", study);
        return session;
    }
}
