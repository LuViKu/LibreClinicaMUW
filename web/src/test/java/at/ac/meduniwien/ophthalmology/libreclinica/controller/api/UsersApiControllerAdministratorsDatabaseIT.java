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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.config.SsoProperties;
import at.ac.meduniwien.ophthalmology.libreclinica.core.SecurityManager;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.AuthoritiesDao;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.login.UserAccountDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.i18n.util.ResourceBundleProvider;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The user-administration surface the SPA now uses in full: creating,
 * promoting and demoting administrators (only a technical administrator
 * makes or unmakes a technical administrator), the access-review fields
 * on each row, clearing an optional field, and granting a role in a study
 * the administrator holds no role on (refused to anyone who is not a
 * system administrator).
 */
class UsersApiControllerAdministratorsDatabaseIT extends AbstractApiControllerDatabaseIT {

    @BeforeAll
    static void seed() throws SQLException {
        ResourceBundleProvider.updateLocale(Locale.ENGLISH);
        try (Connection c = DATA_SOURCE.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate(userInsert("ua_plain", 2, "Paula", "Plain", "0664 123", "MUW Ophthalmology"));
            // Read by the access-review test only, so no other test moves its dates.
            s.executeUpdate(userInsert("ua_review", 2, "Rita", "Review", "0664 999", "MUW Ophthalmology"));
            s.executeUpdate(userInsert("ua_tech", 3, "Theo", "Tech", "", "MUW IT"));
            s.executeUpdate("INSERT INTO study (unique_identifier, name, summary, date_created, owner_id, type_id, "
                    + "status_id, old_status_id, principal_investigator, protocol_type, sponsor, oc_oid) "
                    + "VALUES ('ua-other', 'Users IT other study', '', now(), 1, 1, 1, 1, 'PI', 'observational', "
                    + "'MUW', 'S_UA_OTHER')");
        }
    }

    @Test
    void everyRowCarriesTheAccessReviewFields() throws Exception {
        String review = "$[?(@.username == 'ua_review')]";
        mockMvc().perform(get("/api/v1/users").session(sessionOf("manual_admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath(review + ".firstName", contains("Rita")))
                .andExpect(jsonPath(review + ".lastName", contains("Review")))
                .andExpect(jsonPath(review + ".phone", contains("0664 999")))
                .andExpect(jsonPath(review + ".institutionalAffiliation", contains("MUW Ophthalmology")))
                .andExpect(jsonPath(review + ".userType", contains("USER")))
                .andExpect(jsonPath(review + ".createdDate", contains("2026-01-15")))
                .andExpect(jsonPath(review + ".ownerUsername", contains("root")))
                .andExpect(jsonPath(review + ".updatedDate", contains("2026-02-01")))
                .andExpect(jsonPath(review + ".updaterUsername", contains("root")))
                .andExpect(jsonPath("$[?(@.username == 'ua_tech')].userType", contains("TECHADMIN")))
                .andExpect(jsonPath("$[?(@.username == 'manual_admin')].userType", contains("SYSADMIN")));
    }

    @Test
    void aStudyScopedCallerGetsTheSlimRowWithoutTheAccountDetails() throws Exception {
        // manual_dm directs the Default Study: they see its users, not their account details.
        String crc = "$[?(@.username == 'manual_crc')]";
        mockMvc().perform(get("/api/v1/users").session(sessionOf("manual_dm")))
                .andExpect(status().isOk())
                .andExpect(jsonPath(crc + ".username", contains("manual_crc")))
                .andExpect(jsonPath(crc + ".phone", empty()))
                .andExpect(jsonPath(crc + ".institutionalAffiliation", empty()))
                .andExpect(jsonPath(crc + ".userType", empty()))
                .andExpect(jsonPath(crc + ".ownerUsername", empty()));
    }

    @Test
    void aBusinessAdministratorCreatesPromotesAndDemotesAdministrators() throws Exception {
        mockMvc().perform(post("/api/v1/users").contentType("application/json")
                        .content(newUser("ua_new_admin", "SYSADMIN"))
                        .session(sessionOf("manual_admin")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.userType").value("SYSADMIN"));
        assertEquals(1, userTypeOf("ua_new_admin"));

        mockMvc().perform(put("/api/v1/users/ua_plain").contentType("application/json")
                        .content("{\"userType\":\"SYSADMIN\"}").session(sessionOf("manual_admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userType").value("SYSADMIN"));
        assertEquals(1, userTypeOf("ua_plain"));
        mockMvc().perform(put("/api/v1/users/ua_plain").contentType("application/json")
                        .content("{\"userType\":\"USER\"}").session(sessionOf("manual_admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userType").value("USER"));
        assertEquals(2, userTypeOf("ua_plain"));
    }

    @Test
    void onlyATechnicalAdministratorMakesOrUnmakesATechnicalAdministrator() throws Exception {
        mockMvc().perform(post("/api/v1/users").contentType("application/json")
                        .content(newUser("ua_new_tech_refused", "TECHADMIN"))
                        .session(sessionOf("manual_admin")))
                .andExpect(status().isForbidden());
        mockMvc().perform(put("/api/v1/users/ua_tech").contentType("application/json")
                        .content("{\"userType\":\"SYSADMIN\"}").session(sessionOf("manual_admin")))
                .andExpect(status().isForbidden());
        mockMvc().perform(put("/api/v1/users/ua_tech").contentType("application/json")
                        .content("{\"userType\":\"USER\"}").session(sessionOf("manual_admin")))
                .andExpect(status().isForbidden());
        assertEquals(3, userTypeOf("ua_tech"));

        // root is a technical administrator.
        mockMvc().perform(post("/api/v1/users").contentType("application/json")
                        .content(newUser("ua_new_tech", "TECHADMIN"))
                        .session(sessionOf("root")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.userType").value("TECHADMIN"));
        assertEquals(3, userTypeOf("ua_new_tech"));
    }

    @Test
    void anOptionalFieldCanBeClearedARequiredOneCannot() throws Exception {
        mockMvc().perform(put("/api/v1/users/ua_tech").contentType("application/json")
                        .content("{\"phone\":\"0043 1 40400\"}").session(sessionOf("root")))
                .andExpect(status().isOk());
        mockMvc().perform(put("/api/v1/users/ua_tech").contentType("application/json")
                        .content("{\"phone\":\"\"}").session(sessionOf("root")))
                .andExpect(status().isOk());
        assertEquals("", stringColumn("phone", "ua_tech"));

        mockMvc().perform(put("/api/v1/users/ua_tech").contentType("application/json")
                        .content("{\"institutionalAffiliation\":\"\"}").session(sessionOf("root")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("institutionalAffiliation"));
        assertEquals("MUW IT", stringColumn("institutional_affiliation", "ua_tech"));

        // user_account.phone is VARCHAR(64): refuse rather than lose the write.
        mockMvc().perform(put("/api/v1/users/ua_tech").contentType("application/json")
                        .content("{\"phone\":\"" + "1".repeat(65) + "\"}").session(sessionOf("root")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("phone"));
    }

    @Test
    void aSystemAdministratorGrantsARoleInAStudyTheyHoldNoRoleOn() throws Exception {
        mockMvc().perform(put("/api/v1/users/ua_plain/roles/S_UA_OTHER").contentType("application/json")
                        .content("{\"roles\":[\"Investigator\"]}").session(sessionOf("manual_admin")))
                .andExpect(status().isOk());
        mockMvc().perform(post("/api/v1/users/ua_plain/roles").contentType("application/json")
                        .content("{\"studyOid\":\"S_UA_OTHER\",\"role\":\"Monitor\"}")
                        .session(sessionOf("manual_admin")))
                .andExpect(status().isCreated());
        assertEquals(2, intQuery("SELECT count(*) FROM study_user_role sur JOIN study s ON s.study_id = sur.study_id "
                + "WHERE s.oc_oid = 'S_UA_OTHER' AND sur.user_name = 'ua_plain' AND sur.status_id = 1"));
    }

    @Test
    void someoneWhoIsNotASystemAdministratorGrantsNoRoleAnywhere() throws Exception {
        // manual_dm directs the Default Study; that does not make them a user administrator.
        mockMvc().perform(put("/api/v1/users/ua_plain/roles/S_UA_OTHER").contentType("application/json")
                        .content("{\"roles\":[\"Investigator\"]}").session(sessionOf("manual_dm")))
                .andExpect(status().isForbidden());
        mockMvc().perform(post("/api/v1/users/ua_plain/roles").contentType("application/json")
                        .content("{\"studyOid\":\"S_UA_OTHER\",\"role\":\"Monitor\"}")
                        .session(sessionOf("manual_dm")))
                .andExpect(status().isForbidden());
        mockMvc().perform(post("/api/v1/users/ua_plain/roles").contentType("application/json")
                        .content("{\"studyOid\":\"S_DEFAULTS1\",\"role\":\"Monitor\"}")
                        .session(sessionOf("manual_dm")))
                .andExpect(status().isForbidden());
    }

    /* ------------------------------------------------------------------ */

    private MockMvc mockMvc() {
        SecurityManager securityManager = Mockito.mock(SecurityManager.class);
        Mockito.when(securityManager.genPassword()).thenReturn("Tmp-Admin-12!");
        Mockito.when(securityManager.encryptPassword(ArgumentMatchers.anyString(), ArgumentMatchers.anyBoolean()))
                .thenReturn("{bcrypt}$2a$10$hashedplaceholder");
        UsersApiController controller = new UsersApiController(DATA_SOURCE, new SiteVisibilityFilter(DATA_SOURCE),
                securityManager, Mockito.mock(AuthoritiesDao.class), new SsoProperties());
        return ProductionMvc.standalone(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    /** The account's own bean, as login puts it in the session, on the Default Study. */
    private static MockHttpSession sessionOf(String username) {
        UserAccountBean ub = new UserAccountDAO(DATA_SOURCE).findByUserName(username);
        assertTrue(ub.getId() > 0, "no seeded account " + username);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("userBean", ub);
        StudyBean study = new StudyBean();
        study.setId(1);
        study.setOid("S_DEFAULTS1");
        session.setAttribute("study", study);
        return session;
    }

    private static String newUser(String username, String userType) {
        return "{\"username\":\"" + username + "\",\"firstName\":\"New\",\"lastName\":\"Admin\","
                + "\"email\":\"" + username + "@example.invalid\",\"institutionalAffiliation\":\"MUW\","
                + "\"studyId\":1,\"role\":\"Investigator\",\"userType\":\"" + userType + "\",\"sendEmail\":false}";
    }

    private static String userInsert(String name, int type, String first, String last, String phone,
                                     String affiliation) {
        return "INSERT INTO user_account (user_name, passwd, first_name, last_name, email, phone, active_study, "
                + "institutional_affiliation, status_id, owner_id, date_created, date_updated, update_id, "
                + "user_type_id, enabled, account_non_locked, lock_counter, run_webservices, authtype, "
                + "enable_api_key) VALUES ('" + name + "', 'x', '" + first + "', '" + last + "', '" + name
                + "@example.invalid', '" + phone + "', 1, '" + affiliation + "', 1, 1, '2026-01-15', "
                + "'2026-02-01', 1, " + type + ", true, true, 0, false, 'STANDARD', false)";
    }

    private static int userTypeOf(String username) throws SQLException {
        return intQuery("SELECT user_type_id FROM user_account WHERE user_name = '" + username + "'");
    }

    private static String stringColumn(String column, String username) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT " + column + " FROM user_account WHERE user_name = '"
                     + username + "'")) {
            assertTrue(rs.next());
            return rs.getString(1);
        }
    }

    private static int intQuery(String sql) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            assertTrue(rs.next(), "no row for: " + sql);
            return rs.getInt(1);
        }
    }
}
