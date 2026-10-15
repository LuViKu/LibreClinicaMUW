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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.UserType;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.config.SsoProperties;
import at.ac.meduniwien.ophthalmology.libreclinica.core.SecurityManager;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.AuthoritiesDao;
import at.ac.meduniwien.ophthalmology.libreclinica.i18n.util.ResourceBundleProvider;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;

/**
 * A user holds either a study-wide role or site roles in one study tree,
 * never both: a new binding that would overlap is refused with 409 on the
 * grant (POST) and on the role list (PUT), while changing the roles of a
 * binding the user already holds stays allowed, and bindings that overlap
 * already are left alone. Account creation names its study explicitly.
 * The users list, the role bindings and the labels carry the parent study
 * of a site.
 *
 * <p>Seeds one site under study 1 and four users: one bound to study 1, one
 * to the site, one to both (an overlap from before the rule), and one
 * bound nowhere.
 */
class UsersApiControllerScopeOverlapDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static final String SITE_NAME = "Scope IT Wien";
    private static final String SITE_OID = "S_SCOPE_IT_WIEN";

    private static int siteId;
    private static String parentName;

    @BeforeAll
    static void seedSiteAndUsers() throws Exception {
        ResourceBundleProvider.updateLocale(Locale.ENGLISH);
        try (Connection c = DATA_SOURCE.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO study (parent_study_id, unique_identifier, name, status_id, "
                            + "date_created, owner_id, oc_oid) "
                            + "VALUES (1, 'scope-it-wien', ?, 1, NOW(), 1, ?) "
                            + "RETURNING study_id")) {
                ps.setString(1, SITE_NAME);
                ps.setString(2, SITE_OID);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    siteId = rs.getInt(1);
                }
            }
            try (Statement stmt = c.createStatement()) {
                try (ResultSet rs = stmt.executeQuery("SELECT name FROM study WHERE study_id = 1")) {
                    rs.next();
                    parentName = rs.getString(1);
                }
                user(stmt, 20201, "ovl_parent");
                user(stmt, 20202, "ovl_site");
                user(stmt, 20203, "ovl_both");
                user(stmt, 20204, "ovl_free");
                binding(stmt, "ovl_parent", 1);
                binding(stmt, "ovl_site", siteId);
                binding(stmt, "ovl_both", 1);
                binding(stmt, "ovl_both", siteId);
            }
        }
    }

    private static void user(Statement stmt, int id, String name) throws Exception {
        stmt.execute(
                "INSERT INTO user_account (user_id, user_name, passwd, first_name, last_name, "
                + "email, active_study, institutional_affiliation, status_id, owner_id, "
                + "date_created, user_type_id, enabled, account_non_locked, lock_counter, "
                + "run_webservices, authtype, enable_api_key) "
                + "VALUES (" + id + ", '" + name + "', "
                + "'{bcrypt}$2a$10$9QHaEdYWWSRQKYOaOECfbuQf8L1I1zWUPevUyMderR4S/ZmIc5/dG', "
                + "'Scope', 'Overlap', '" + name + "@example.invalid', 1, 'MUW (test)', 1, 1, "
                + "current_timestamp, 2, true, true, 0, false, 'STANDARD', false)");
    }

    private static void binding(Statement stmt, String userName, int studyId) throws Exception {
        stmt.execute(
                "INSERT INTO study_user_role "
                + "(role_name, study_id, status_id, owner_id, date_created, user_name) "
                + "VALUES ('investigator', " + studyId + ", 1, 1, current_timestamp, '" + userName + "')");
    }

    private MockMvc mockMvc() {
        SecurityManager securityManager = Mockito.mock(SecurityManager.class);
        Mockito.when(securityManager.genPassword()).thenReturn("Tmp-Scope-12!");
        Mockito.when(securityManager.encryptPassword(ArgumentMatchers.anyString(), ArgumentMatchers.anyBoolean()))
                .thenReturn("{bcrypt}$2a$10$hashedplaceholder");
        UsersApiController controller = new UsersApiController(
                DATA_SOURCE,
                new SiteVisibilityFilter(DATA_SOURCE),
                securityManager,
                Mockito.mock(AuthoritiesDao.class),
                new SsoProperties());
        return ProductionMvc.standalone(controller)
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
        study.setOid("S_DEFAULTS1");
        study.setName("Default Study");
        session.setAttribute("study", study);
        return session;
    }

    private JsonNode getJson(String url) throws Exception {
        String body = mockMvc().perform(get(url).session(sysadminSession()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return new ObjectMapper().readTree(body);
    }

    private static JsonNode find(JsonNode array, String field, String value) {
        for (JsonNode row : array) {
            if (value.equals(row.path(field).asText())) {
                return row;
            }
        }
        return null;
    }

    private static int activeRows(String userName, int studyId) throws Exception {
        try (Connection c = DATA_SOURCE.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM study_user_role WHERE user_name = '"
                     + userName + "' AND study_id = " + studyId + " AND status_id = 1")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /* ---------------------------------------------------------------- */
    /* Overlap refusals                                                  */
    /* ---------------------------------------------------------------- */

    @Test
    void aSiteRoleNextToAStudyWideRoleIsRefusedOnPostAndPut() throws Exception {
        String expected = "ovl_parent already has a study-wide role in " + parentName
                + "; it covers all its sites";
        mockMvc().perform(post("/api/v1/users/ovl_parent/roles").contentType("application/json")
                        .content("{\"studyOid\":\"" + SITE_OID + "\",\"role\":\"Monitor\"}")
                        .session(sysadminSession()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(expected));
        mockMvc().perform(put("/api/v1/users/ovl_parent/roles/" + SITE_OID).contentType("application/json")
                        .content("{\"roles\":[\"Monitor\"]}")
                        .session(sysadminSession()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(expected));
        assertEquals(0, activeRows("ovl_parent", siteId));
    }

    @Test
    void aStudyWideRoleNextToSiteRolesIsRefusedOnPostAndPut() throws Exception {
        String expected = "ovl_site has roles at sites of " + parentName
                + "; remove those first or keep the site roles";
        mockMvc().perform(post("/api/v1/users/ovl_site/roles").contentType("application/json")
                        .content("{\"studyOid\":\"S_DEFAULTS1\",\"role\":\"Monitor\"}")
                        .session(sysadminSession()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(expected));
        mockMvc().perform(put("/api/v1/users/ovl_site/roles/S_DEFAULTS1").contentType("application/json")
                        .content("{\"roles\":[\"CRC\"]}")
                        .session(sysadminSession()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(expected));
        assertEquals(0, activeRows("ovl_site", 1));
    }

    @Test
    void changingTheRolesOfAnExistingBindingStaysAllowed() throws Exception {
        // A further role on the study the user is already bound to.
        mockMvc().perform(post("/api/v1/users/ovl_parent/roles").contentType("application/json")
                        .content("{\"studyOid\":\"S_DEFAULTS1\",\"role\":\"Monitor\"}")
                        .session(sysadminSession()))
                .andExpect(status().isCreated());
        // ovl_both overlaps from before the rule: both its bindings may
        // still be changed, and neither is touched by the other's change.
        mockMvc().perform(put("/api/v1/users/ovl_both/roles/" + SITE_OID).contentType("application/json")
                        .content("{\"roles\":[\"Investigator\",\"Monitor\"]}")
                        .session(sysadminSession()))
                .andExpect(status().isOk());
        mockMvc().perform(put("/api/v1/users/ovl_both/roles/S_DEFAULTS1").contentType("application/json")
                        .content("{\"roles\":[\"Investigator\",\"CRC\"]}")
                        .session(sysadminSession()))
                .andExpect(status().isOk());
        assertEquals(2, activeRows("ovl_both", siteId));
        assertEquals(2, activeRows("ovl_both", 1));
    }

    @Test
    void aUserWithoutBindingsInTheTreeMayBeGrantedASite() throws Exception {
        mockMvc().perform(post("/api/v1/users/ovl_free/roles").contentType("application/json")
                        .content("{\"studyOid\":\"" + SITE_OID + "\",\"role\":\"Investigator\"}")
                        .session(sysadminSession()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.parentStudyName").value(parentName));
    }

    /* ---------------------------------------------------------------- */
    /* Account creation                                                  */
    /* ---------------------------------------------------------------- */

    private static String newUser(String username, String studyField) {
        return "{\"username\":\"" + username + "\",\"firstName\":\"New\",\"lastName\":\"Scope\","
                + "\"email\":\"" + username + "@example.invalid\",\"institutionalAffiliation\":\"MUW\","
                + studyField + "\"role\":\"Investigator\",\"sendEmail\":false}";
    }

    @Test
    void creationWithoutAStudyIsRefusedAndNothingFallsBackToTheSessionStudy() throws Exception {
        mockMvc().perform(post("/api/v1/users").contentType("application/json")
                        .content(newUser("ovl_nostudy", ""))
                        .session(sysadminSession()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'studyId')]").exists());
        try (Connection c = DATA_SOURCE.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM user_account WHERE user_name = 'ovl_nostudy'")) {
            rs.next();
            assertEquals(0, rs.getInt(1));
        }
    }

    @Test
    void creationOnASiteNamedByOidBindsTheSiteAndLabelsIt() throws Exception {
        // No active study in the session: the request names the study.
        MockHttpSession session = sysadminSession();
        session.removeAttribute("study");
        mockMvc().perform(post("/api/v1/users").contentType("application/json")
                        .content(newUser("ovl_created", "\"studyOid\":\"" + SITE_OID + "\","))
                        .session(session))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.siteLabel").value(SITE_NAME))
                .andExpect(jsonPath("$.user.scopes[0]").value(parentName + " › " + SITE_NAME));
        assertEquals(1, activeRows("ovl_created", siteId));
        assertEquals(0, activeRows("ovl_created", 1));
        try (Connection c = DATA_SOURCE.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT active_study FROM user_account WHERE user_name = 'ovl_created'")) {
            rs.next();
            assertEquals(siteId, rs.getInt(1));
        }
    }

    @Test
    void creationRefusesAStudyIdAndOidThatDisagree() throws Exception {
        mockMvc().perform(post("/api/v1/users").contentType("application/json")
                        .content(newUser("ovl_mismatch", "\"studyId\":1,\"studyOid\":\"" + SITE_OID + "\","))
                        .session(sysadminSession()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'studyId')]").exists());
    }

    /* ---------------------------------------------------------------- */
    /* Labels                                                            */
    /* ---------------------------------------------------------------- */

    @Test
    void theUserListCarriesEveryScopeOfAUser() throws Exception {
        JsonNode users = getJson("/api/v1/users");
        JsonNode both = find(users, "username", "ovl_both");
        JsonNode site = find(users, "username", "ovl_site");
        JsonNode parent = find(users, "username", "ovl_parent");
        assertNotNull(both, "users: " + users);
        assertNotNull(site, "users: " + users);
        assertNotNull(parent, "users: " + users);

        assertEquals(List.of(parentName, parentName + " › " + SITE_NAME), texts(both.path("scopes")));
        assertEquals(List.of(parentName + " › " + SITE_NAME), texts(site.path("scopes")));
        assertEquals(List.of(parentName), texts(parent.path("scopes")));
        // siteLabel stays for compatibility.
        assertEquals(SITE_NAME, site.path("siteLabel").asText(null));
    }

    @Test
    void aSiteBindingNamesItsParentStudy() throws Exception {
        JsonNode roles = getJson("/api/v1/users/ovl_both/roles");
        JsonNode parentRow = find(roles, "studyId", "1");
        JsonNode siteRow = find(roles, "studyId", String.valueOf(siteId));
        assertNotNull(parentRow, "roles: " + roles);
        assertNotNull(siteRow, "roles: " + roles);

        assertEquals(parentName, siteRow.path("parentStudyName").asText(null));
        assertNull(parentRow.path("parentStudyName").stringValue(null), "parent: " + parentRow);
        assertTrue(siteRow.path("siteLabel").asText("").equals(SITE_NAME), "site: " + siteRow);
    }

    @Test
    void scopesIgnoreTheRoleFilter() throws Exception {
        mockMvc().perform(get("/api/v1/users?siteOid=" + SITE_OID).session(sysadminSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.username == 'ovl_both')].scopes[*]",
                        Matchers.hasItem(parentName)));
    }

    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        for (JsonNode n : array) out.add(n.asText());
        return out;
    }
}
