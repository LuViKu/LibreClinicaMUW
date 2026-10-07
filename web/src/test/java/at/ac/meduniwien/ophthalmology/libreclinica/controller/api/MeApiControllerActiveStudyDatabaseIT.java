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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.Objects;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.login.UserAccountDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.i18n.util.ResourceBundleProvider;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code POST /api/v1/me/activeStudy} for a system administrator who holds
 * no role on the study, and {@code userType} on {@code GET /api/v1/me}.
 *
 * <p>A system administrator may open any study that is not removed; the
 * session then carries the role the legacy {@code SecureController}
 * gives: none (INVALID), or on a site the role held on the parent. Anyone
 * else still needs a binding on the study.
 */
class MeApiControllerActiveStudyDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static int study;
    private static int site;

    @BeforeAll
    static void seed() throws SQLException {
        ResourceBundleProvider.updateLocale(Locale.ENGLISH);
        try (Connection c = DATA_SOURCE.getConnection()) {
            study = insertStudy(c, null, "pick-it", "S_PICK_IT", 1);
            site = insertStudy(c, study, "pick-it-a", "S_PICK_IT_A", 1);
            int removed = insertStudy(c, null, "pick-gone", "S_PICK_GONE", 5);
            // Auto-removed with its study, as RemoveStudyServlet leaves a site.
            insertStudy(c, removed, "pick-gone-a", "S_PICK_GONE_A", 7);
            // user_type_id 1 = business administrator; no binding, no active study.
            insertUser(c, "pick_admin", 1);
            // An administrator bound on the parent study only.
            insertUser(c, "pick_parent_admin", 1);
            insertRole(c, "pick_parent_admin", study, "director");
            // An administrator whose only binding on the parent study was revoked.
            insertUser(c, "pick_revoked_admin", 1);
            insertRole(c, "pick_revoked_admin", study, "director");
            try (Statement s = c.createStatement()) {
                s.executeUpdate("UPDATE study_user_role SET status_id = 5 WHERE user_name = 'pick_revoked_admin'");
            }
            // An ordinary user whose only binding is on a removed study.
            insertUser(c, "pick_user_removed", 2);
            insertRole(c, "pick_user_removed", removed, "Investigator");
        }
    }

    @Test
    void anUnboundAdministratorMayOpenAnyStudyWithoutAStudyRole() throws Exception {
        MockHttpSession session = sessionOf("pick_admin");
        mockMvc().perform(post("/api/v1/me/activeStudy").contentType("application/json")
                        .content("{\"oid\":\"S_PICK_IT\"}").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeStudy.oid").value("S_PICK_IT"))
                .andExpect(jsonPath("$.role").value("Administrator"))
                .andExpect(jsonPath("$.userType").value("SYSADMIN"));

        assertEquals(study, ((StudyBean) Objects.requireNonNull(session.getAttribute("study"))).getId());
        StudyUserRoleBean role = (StudyUserRoleBean) Objects.requireNonNull(session.getAttribute("userRole"));
        assertEquals(Role.INVALID, role.getRole(), "no binding, so no study role: the legacy 'invalid'");
        assertEquals(study, activeStudyOf("pick_admin"));
    }

    @Test
    void onASiteTheAdministratorCarriesTheRoleHeldOnTheParent() throws Exception {
        MockHttpSession session = sessionOf("pick_parent_admin");
        mockMvc().perform(post("/api/v1/me/activeStudy").contentType("application/json")
                        .content("{\"oid\":\"S_PICK_IT_A\"}").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeStudy.oid").value("S_PICK_IT_A"));
        assertEquals(site, ((StudyBean) Objects.requireNonNull(session.getAttribute("study"))).getId());
        assertEquals(Role.STUDYDIRECTOR, ((StudyUserRoleBean) Objects.requireNonNull(session.getAttribute("userRole"))).getRole());
    }

    @Test
    void aRevokedParentBindingCarriesNoRoleOntoTheSite() throws Exception {
        MockHttpSession session = sessionOf("pick_revoked_admin");
        mockMvc().perform(post("/api/v1/me/activeStudy").contentType("application/json")
                        .content("{\"oid\":\"S_PICK_IT_A\"}").session(session))
                .andExpect(status().isOk());
        assertEquals(Role.INVALID, ((StudyUserRoleBean) Objects.requireNonNull(session.getAttribute("userRole"))).getRole());
    }

    @Test
    void anyoneElseStillNeedsABindingOnTheStudy() throws Exception {
        MockHttpSession session = sessionOf("manual_dm");
        Object before = session.getAttribute("study");
        mockMvc().perform(post("/api/v1/me/activeStudy").contentType("application/json")
                        .content("{\"oid\":\"S_PICK_IT\"}").session(session))
                .andExpect(status().isForbidden());
        assertEquals(before, session.getAttribute("study"));
    }

    @Test
    void aRemovedStudyIsRefusedToEveryone() throws Exception {
        mockMvc().perform(post("/api/v1/me/activeStudy").contentType("application/json")
                        .content("{\"oid\":\"S_PICK_GONE\"}").session(sessionOf("pick_admin")))
                .andExpect(status().isConflict());
        // A site auto-removed with its study is refused as well.
        MockHttpSession admin = sessionOf("pick_admin");
        mockMvc().perform(post("/api/v1/me/activeStudy").contentType("application/json")
                        .content("{\"oid\":\"S_PICK_GONE_A\"}").session(admin))
                .andExpect(status().isConflict());
        assertNull(admin.getAttribute("study"));
        MockHttpSession user = sessionOf("pick_user_removed");
        mockMvc().perform(post("/api/v1/me/activeStudy").contentType("application/json")
                        .content("{\"oid\":\"S_PICK_GONE\"}").session(user))
                .andExpect(status().isConflict());
        assertNull(user.getAttribute("study"));
    }

    @Test
    void meReportsTheAccountType() throws Exception {
        mockMvc().perform(get("/api/v1/me").session(sessionOf("root")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userType").value("TECHADMIN"));
        mockMvc().perform(get("/api/v1/me").session(sessionOf("manual_admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userType").value("SYSADMIN"));
        mockMvc().perform(get("/api/v1/me").session(sessionOf("manual_dm")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userType").value("USER"));
    }

    /* ------------------------------------------------------------------ */

    private MockMvc mockMvc() {
        return ProductionMvc.standalone(new MeApiController(DATA_SOURCE))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    /** The session as login leaves it: the account's own bean, loaded from the database. */
    private static MockHttpSession sessionOf(String username) {
        UserAccountBean ub = new UserAccountDAO(DATA_SOURCE).findByUserName(username);
        assertTrue(ub.getId() > 0, "no seeded account " + username);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("userBean", ub);
        return session;
    }

    private static int activeStudyOf(String username) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT active_study FROM user_account WHERE user_name = '"
                     + username + "'")) {
            assertTrue(rs.next());
            return rs.getInt(1);
        }
    }

    private static int insertStudy(Connection c, Integer parent, String uid, String oid, int statusId)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO study (parent_study_id, unique_identifier, name, summary, date_created, owner_id, "
                        + "type_id, status_id, old_status_id, principal_investigator, protocol_type, sponsor, oc_oid) "
                        + "VALUES (?, ?, ?, '', now(), 1, 1, ?, 1, 'PI', 'observational', 'MUW', ?) "
                        + "RETURNING study_id")) {
            if (parent == null) ps.setNull(1, java.sql.Types.INTEGER); else ps.setInt(1, parent);
            ps.setString(2, uid);
            ps.setString(3, "Pick " + uid);
            ps.setInt(4, statusId);
            ps.setString(5, oid);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static void insertUser(Connection c, String name, int userTypeId) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.executeUpdate("INSERT INTO user_account (user_name, passwd, first_name, last_name, email, "
                    + "institutional_affiliation, status_id, owner_id, date_created, user_type_id, "
                    + "enabled, account_non_locked, lock_counter, run_webservices, authtype, enable_api_key) "
                    + "VALUES ('" + name + "', 'x', 'F', 'L', '" + name + "@example.invalid', 'MUW', 1, 1, "
                    + "now(), " + userTypeId + ", true, true, 0, false, 'STANDARD', false)");
        }
    }

    private static void insertRole(Connection c, String user, int studyId, String role) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.executeUpdate("INSERT INTO study_user_role (role_name, study_id, status_id, owner_id, date_created, "
                    + "user_name) VALUES ('" + role + "', " + studyId + ", 1, 1, now(), '" + user + "')");
        }
    }
}
